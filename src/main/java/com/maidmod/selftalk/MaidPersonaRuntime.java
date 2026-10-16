package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.persona.PersonaFragment;
import com.maidmod.selftalk.persona.PersonaRetrievalIndex;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

/**
 * 四栏人设的运行时入口：配置读取、可用性判定、固定正文组装、动态人设索引与召回块的构造，
 * 以及配置变更后的统一清理。所有调用都在服务端主线程。
 * <p>
 * 两种人设模式的分工：
 * <ul>
 *   <li><b>原版模式</b>：人设来自 TLM 的 {@code customSetting} 或模型自带设定文件，
 *       本类只做「有没有可用人设」的判定，不读写、不迁移这份内容；</li>
 *   <li><b>四栏模式</b>：身份与性格每轮固定注入（见 {@link #coreBody}），背景与爱好按需召回
 *       （见 {@link #indexOf} 与 {@link #buildRecalledBlock}）。</li>
 * </ul>
 * 两套内容独立保存：切模式不复制、不覆盖、不删除另一套，本类也不在两者之间做任何搬运。
 */
public final class MaidPersonaRuntime {

    private MaidPersonaRuntime() {
    }

    /** 女仆当前完整人设配置（宿主不可用时为默认配置：原版模式 + 空四栏） */
    public static MaidPersonaConfig configOf(EntityMaid maid) {
        return MaidPersonaHost.configOf(maid);
    }

    /**
     * 女仆是否有「可用的人设」——各功能能否发起请求的前置判定，口径按模式分流：
     * <ul>
     *   <li>四栏模式：四栏中任一栏非空白即算有（只有背景或只有爱好同样算，
     *       本轮召回没命中不会撤销这一判定）；</li>
     *   <li>原版模式：沿用 TLM 口径（有 {@code customSetting} 或模型自带设定文件）。</li>
     * </ul>
     * 四栏全为空白时不发起自话／欢迎语／互聊；玩家聊天仍走 TLM 原生路径并显示原生「缺少人设」提示
     * （本 mod 在该路径抑制原版自动生成，避免四栏模式下生成并写入另一套人设）。
     */
    public static boolean hasUsablePersona(EntityMaid maid) {
        if (maid == null) {
            return false;
        }
        MaidPersonaConfig config = configOf(maid);
        if (config.isStructured()) {
            return config.draft().hasAnyContent();
        }
        MaidAIChatManager chatManager = maid.getAiChatManager();
        if (chatManager == null) {
            return false;
        }
        return !chatManager.customSetting.isBlank() || !chatManager.getSetting().isEmpty();
    }

    /** 是否四栏模式（固定注入身份、性格，并按需召回背景、爱好） */
    public static boolean isStructured(EntityMaid maid) {
        return configOf(maid).isStructured();
    }

    /**
     * 四栏模式下每轮固定注入的人设正文：身份 → 性格，顺序固定。
     * <p>
     * 只含这两栏：背景与爱好属于动态人设，走按需召回，绝不进入固定正文
     * （否则每轮都在请求前缀里塞入全量内容，前缀缓存与费用都无从谈起）。
     * 两栏都空白时返回空串，调用方据此回落到「无人设」路径。
     * <p>
     * 返回的是玩家原文，只按语言加固定小标题，不做改写、不做摘要。
     */
    public static String coreBody(MaidPersonaDraft draft, String language) {
        if (draft == null) {
            return StringUtils.EMPTY;
        }
        boolean zh = isZh(language);
        StringBuilder sb = new StringBuilder();
        if (!draft.identity().isBlank()) {
            sb.append(zh ? SelfTalkPrompts.PERSONA_IDENTITY_HEADING_ZH
                    : SelfTalkPrompts.PERSONA_IDENTITY_HEADING_EN).append('\n').append(draft.identity());
        }
        if (!draft.personality().isBlank()) {
            if (sb.length() > 0) {
                sb.append("\n\n");
            }
            sb.append(zh ? SelfTalkPrompts.PERSONA_PERSONALITY_HEADING_ZH
                    : SelfTalkPrompts.PERSONA_PERSONALITY_HEADING_EN).append('\n').append(draft.personality());
        }
        return sb.toString();
    }

    /** 该配置下是否有可召回的动态人设（四栏模式且背景或爱好任一非空白） */
    public static boolean hasDynamicPersona(MaidPersonaConfig config) {
        return config != null && config.isStructured() && config.draft().hasDynamicContent();
    }

    /**
     * 取（必要时重建）该女仆的人设索引。
     * <p>
     * 缓存键是<b>完整配置快照</b>（模式 + 四栏），配置不同即重建——因此保存成功后不需要额外的
     * 「清缓存」约定，下一次取用自然拿到新索引。缓存存在女仆运行时状态里（不持久化，
     * 状态条目移除即释放）。
     * <p>
     * 四栏合计上限 4096 字符，切句与分词都是毫秒级，因此<b>直接在主线程构建</b>：
     * 不新建线程池，也不像历史索引那样需要「先算后回主线程」的异步握手。
     */
    public static PersonaRetrievalIndex indexOf(EntityMaid maid, MaidPersonaConfig config) {
        SelfTalkState.State state = SelfTalkState.get(maid.getId());
        if (state.personaIndex != null && config.equals(state.personaIndexConfig)) {
            return state.personaIndex;
        }
        PersonaRetrievalIndex index = PersonaRetrievalIndex.build(config.draft());
        state.personaIndexConfig = config;
        state.personaIndex = index;
        return index;
    }

    /** 按关键词召回人设片段（索引为主线程缓存；空关键词/空库/无命中一律返回空列表） */
    public static List<PersonaFragment> recall(EntityMaid maid, MaidPersonaConfig config,
                                               List<String> personaKeywords) {
        PersonaRetrievalIndex index = indexOf(maid, config);
        if (index.isEmpty()) {
            return List.of();
        }
        return index.search(personaKeywords);
    }

    /**
     * 把召回片段渲染成注入到最后一条 user 消息动态资料区的人设块。
     * <p>
     * 形式上刻意与历史召回区分：独立的人设资料边界 + 分类名称，并显式说明这是「角色自己的背景」，
     * 供模型结合当前情境自行取用——绝不伪装成过去的 assistant 发言（那会被模型当作已说过的话）。
     * 空片段返回空串，本轮不注入任何人设块；存储内容原样保留。
     */
    public static String buildRecalledBlock(List<PersonaFragment> fragments, String language) {
        if (fragments == null || fragments.isEmpty()) {
            return StringUtils.EMPTY;
        }
        boolean zh = isZh(language);
        StringBuilder sb = new StringBuilder();
        sb.append(zh ? SelfTalkPrompts.RECALLED_PERSONA_OPEN_ZH : SelfTalkPrompts.RECALLED_PERSONA_OPEN_EN);
        PersonaFragment.Category current = null;
        for (PersonaFragment fragment : fragments) {
            if (fragment.category() != current) {
                current = fragment.category();
                sb.append('\n').append(label(current, zh));
            }
            sb.append("\n- ").append(fragment.text());
        }
        sb.append(zh ? SelfTalkPrompts.RECALLED_PERSONA_CLOSE_ZH : SelfTalkPrompts.RECALLED_PERSONA_CLOSE_EN);
        return sb.toString();
    }

    private static String label(PersonaFragment.Category category, boolean zh) {
        return switch (category) {
            case BACKGROUND -> zh ? SelfTalkPrompts.RECALLED_PERSONA_CATEGORY_BACKGROUND_ZH
                    : SelfTalkPrompts.RECALLED_PERSONA_CATEGORY_BACKGROUND_EN;
            case HOBBIES -> zh ? SelfTalkPrompts.RECALLED_PERSONA_CATEGORY_HOBBIES_ZH
                    : SelfTalkPrompts.RECALLED_PERSONA_CATEGORY_HOBBIES_EN;
        };
    }

    /**
     * 人设配置已变更（保存成功）后的统一清理：
     * <ol>
     *   <li>作废本女仆<b>自动聊天</b>路径（自话／欢迎语／互聊）在途的旧快照规划——
     *       它们的规划材料与结果都基于旧配置，按现有取消口收尾后不再派发；</li>
     *   <li>清掉链上该女仆的人设召回结果，后续轮次按新配置重新召回；</li>
     *   <li>玩家聊天路径的在途输入<b>不丢弃</b>：静默结束旧规划后按新配置重新准备并恢复原生派发
     *       （见 {@link PlayerPersonaChatSession}）。</li>
     * </ol>
     * 人设索引本身不需要显式清除：缓存键是配置快照，配置一变自然重建。
     * 已发出的网络请求不回改，后续新请求才使用新配置。
     */
    public static void onConfigChanged(EntityMaid maid) {
        if (maid == null) {
            return;
        }
        SelfTalkState.State state = SelfTalkState.peek(maid.getId());
        if (state != null) {
            state.cancelPlans(false);
        }
        InterChatChain.invalidatePersonaRecall(maid.getId());
        PlayerPersonaChatSession.onConfigChanged(maid);
    }

    private static boolean isZh(String language) {
        return SelfTalkContexts.sanitizeLanguage(language).startsWith("zh");
    }
}
