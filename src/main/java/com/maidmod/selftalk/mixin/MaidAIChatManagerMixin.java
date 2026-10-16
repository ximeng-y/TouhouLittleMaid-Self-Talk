package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.ChatClientInfo;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.setting.papi.PapiReplacer;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.AutonomousChatHistoryMigration;
import com.maidmod.selftalk.InterChatChain;
import com.maidmod.selftalk.InterChatRequest;
import com.maidmod.selftalk.MaidInterChatService;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaRuntime;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.PlayerPersonaChatSession;
import com.maidmod.selftalk.SelfTalkContexts;
import com.maidmod.selftalk.SelfTalkDispatcher;
import com.maidmod.selftalk.SelfTalkHistoryAssembler;
import com.maidmod.selftalk.SelfTalkPrompts;
import com.maidmod.selftalk.SelfTalkState;
import com.maidmod.selftalk.SegmentTags;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;

/**
 * 钩玩家 chat 真实派发入口（MaidAIChatManager.normalChat，private 方法、服务端主线程调用）。
 * <p>
 * 作用：
 * <ul>
 *   <li>HEAD：把互聊窗口内容拼接进本次请求的 messages（此刻 TLM 刚构建完 [system 设定, 摘要, ...历史]，
 *       尚未 append 玩家新消息），并就地包裹段标签（历史+窗口）；
 *       使玩家 chat 上下文 = 对话历史（含自话）+ 互聊记录 + 玩家的话；</li>
 *   <li>@Redirect {@code LLMMessage.userChat}：拦截玩家消息构造，
 *       把「这是主人在与你聊天」声明与主人段标签注入消息内容（前端无感：TLM 仍以原始话写历史）；</li>
 *   <li>TAIL：标记该女仆"玩家 chat 进行中"（计数），自话/互聊触发时若命中则跳过（防交错写历史）；
 *       清空自话/互聊计数窗口（打断连续、计数重新开始。自话记录已写入 TLM 历史、
 *       互聊记录已随 HEAD 注入本次请求，清空不丢已注入内容）。</li>
 * </ul>
 * TAIL 注入点选在请求已提交之后：TLM {@code chat()} 有大量提前返回路径
 * （LLM 关闭/token 超限/site 缺失/密钥缺失/无人设/历史压缩）不产生任何回调，
 * 若在 chat 入口标记则标记可能永不清除、该女仆自话永久停摆；
 * {@code normalChat} 是唯一创建玩家 chat 回调（LLMCallback）的路径，
 * 且 client.chat 同步抛异常时 TAIL 同样不会执行——标记与回调生命周期严格绑定。
 * HEAD 注入只向 messages 尾部追加互聊 assistant 消息（不破坏 tool 配对），
 * 且同步发生于 client.chat 之前，异步请求线程必然读到注入后的列表。
 */
@Mixin(MaidAIChatManager.class)
public abstract class MaidAIChatManagerMixin {

    /**
     * 旧数据迁移：必须在 TLM <b>第一次读取历史并构造副本之前</b>执行。
     * <p>
     * TLM 的路径是 {@code chat() → tryToChat() → getMessages() → buildMessage()}，
     * {@code buildMessage} 会把历史 deque 拷贝进 {@code messages}；随后 {@code normalChat}
     * 拿到的已是那份拷贝。若把迁移放在 {@code normalChat} 的 HEAD，删除只作用于 deque，
     * 已经拷好的旧自话仍留在本次请求里，而下方 {@code mergeValidSelfTalk} 又把刚归档的同一条
     * 插入一次——首个请求会重复包含旧自话，还可能分属不同段标签。
     * <p>
     * 此注入点对「玩家首次聊天发生在自话触发、保存/同步或压缩之前」这一加载路径同样有效，
     * 是玩家 chat 侧唯一的保证点。幂等：迁移完成后立即返回。
     */
    @Inject(method = "getMessages", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$migrateBeforeHistorySnapshot(MaidAIChatManager chatManager, String language,
                                                             CallbackInfoReturnable<List<LLMMessage>> cir) {
        EntityMaid maid = ((MaidAIChatManager) (Object) this).getMaid();
        AutonomousChatHistoryMigration.ensureMigratedOnServerThread(maid);
        MaidPersonaConfig personaConfig = MaidPersonaRuntime.configOf(maid);
        if (!personaConfig.isStructured()) {
            // 原版模式：完整沿用 TLM 的设定选择规则（customSetting 优先，其次默认设定文件）
            return;
        }
        // 四栏模式：核心正文只由「身份 → 性格」按固定顺序构成，交给 PapiReplacer 保留语言、
        // 主人名称、可用技能与输出格式，再走 TLM 原生 buildMessage（摘要与历史读取顺序不变）。
        // 背景、爱好与召回结果不得进入这里，也不临时改写/清空/恢复 customSetting——
        // 两种模式的内容各自独立，本 mod 不在它们之间搬运
        if (!personaConfig.draft().hasAnyContent()) {
            // 四栏全部空白才算「没有人设」：返回空列表，走上原生「缺少人设」提示
            // （不回落 customSetting：那等于把另一套人设当成四栏模式的内容）
            cir.setReturnValue(new ArrayList<>());
            return;
        }
        // 只有背景／爱好、身份与性格为空时核心正文为空串，但仍要发请求（本轮靠召回提供角色资料），
        // 因此照常经过 replaceSetting 与原生 buildMessage：语言、主人名称、技能与输出格式框架都保留
        String core = MaidPersonaRuntime.coreBody(personaConfig.draft(), language);
        String setting = PapiReplacer.replaceSetting(core, maid, language);
        cir.setReturnValue(((MaidAIChatManagerAccessor) (Object) this)
                .invokeBuildMessage(setting, maid, chatManager.getHistory()));
    }

    /**
     * 四栏模式下不启动原版「自动生成人设」。
     * <p>
     * 自动生成会把结果写进 {@code customSetting}——那是原版人设的存储，四栏模式下生成它就等于
     * 替玩家改了另一套内容。此处直接显示原生「缺少人设」提示，与手动设置缺失时的提示一致。
     * 原版模式的自动生成行为完全不变（不拦）。
     */
    @Inject(method = "onSettingIsEmpty", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$suppressAutoGenSetting(ChatClientInfo clientInfo, LLMClient chatClient,
                                                       CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        if (!MaidPersonaRuntime.isStructured(self.getMaid())) {
            return;
        }
        self.getMaid().getChatBubbleManager()
                .addTextChatBubble("ai.touhou_little_maid.chat.llm.role_no_setting");
        ci.cancel();
    }

    /**
     * 玩家聊天准备（HEAD，可取消）。
     * <p>
     * 顺序即语义，与之前保持一致：迁移兜底 → 主人插话打断互聊 → （必要时）推迟本次执行 →
     * 历史合并与互聊窗口注入。三段各自幂等或只对正式列表执行一次。
     */
    @Inject(method = "normalChat", remap = false, at = @At("HEAD"), cancellable = true)
    private void maid_self_talk$injectInterChatContext(String message, List<LLMMessage> messages,
                                                       LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        // 迁移已由 getMessages 的 HEAD 完成（早于历史副本构造）；此处只做兜底，
        // 覆盖 getMessages 未被调用的异常路径，幂等且不产生重复记录
        AutonomousChatHistoryMigration.ensureMigratedOnServerThread(maid);
        interruptInterChatForOwnerChat(maid);
        // 需要人设召回时把本次执行整体推迟：此刻尚未写玩家历史、未创建正式回调、
        // 未触发 TAIL 的正式聊天计数；规划结束后在服务端主线程恢复原生方法
        if (PlayerPersonaChatSession.deferIfNeeded(maid, self, message, messages, chatClient)) {
            ci.cancel();
            return;
        }
        injectHistoryAndInterChatWindow(maid, messages);
    }

    /**
     * 主人插话：真实派发的主动聊天会立即终止该女仆所在的连续互聊链。
     * <p>
     * 必须在注入互聊窗口之前处理，否则被丢弃的旧回复会随窗口重新进入本次请求。
     * 幂等：人设召回推迟执行时，恢复后的正式调用会再跑一次，此时链已释放、请求已作废，
     * 各分支都是空操作。
     */
    private void interruptInterChatForOwnerChat(EntityMaid maid) {
        InterChatChain chain = InterChatChain.activeFor(maid);
        if (chain != null) {
            long chainId = chain.id();
            // 整条链中断：不再续接下一轮
            chain.interruptByOwnerChat(maid);
            // 丢弃顺延队列里属于本链的请求（新链的请求不是旧链的续接，绝不能被一起清掉）
            SelfTalkDispatcher.dropQueuedForChain(maid, chainId);
            // 作废本女仆在链上的当前请求（含规划阶段与已派发的正式阶段；作废后迟到结果沉默）。
            // 被动参与者（对方）的正式请求若已派发可照常显示，但不得续接下一轮——链中断标志已拦
            InterChatRequest request = chain.requestOf(maid);
            if (request != null) {
                request.cancel();
            }
            // 中断即释放注册表：在途回调持有链的直接引用、仍按中断状态自行判定交付许可；
            // 规划阶段被中断的会话没有任何回调会来收尾，注册表不能留给超时清理
            InterChatChain.release(chain);
            return;
        }
        // 链已释放（首轮中断已释放、旧请求还在飞行时再次插话）：按状态表登记的「当前请求」兜底
        // 作废——第二次插话的迟到回复必须同样沉默，不能经旧轮身份继续产生可见副作用
        InterChatRequest leftover = SelfTalkState.get(maid.getId()).currentInterChatRequest;
        if (leftover != null) {
            SelfTalkDispatcher.dropQueuedForChain(maid, leftover.chainId());
            leftover.cancel();
        }
    }

    /**
     * 把本次请求的历史区整理成本 mod 的口径：TLM 历史 + 按持久顺序号合并的有效自话 + 互聊窗口，
     * 并就地包裹段标签。
     * <p>
     * 玩家 chat 与自话／互聊共用同一份来源口径；独立记录不进 TLM deque，这里只写本次请求的副本。
     * <b>只对正式消息列表执行一次</b>：人设召回推迟执行时本次不会被调用，恢复后的正式调用才是唯一一次。
     */
    private void injectHistoryAndInterChatWindow(EntityMaid maid, List<LLMMessage> messages) {
        int prefixEnd = 0;
        while (prefixEnd < messages.size() && messages.get(prefixEnd).role() == Role.SYSTEM) {
            prefixEnd++;
        }
        SelfTalkHistoryAssembler.SourceIndex sources = new SelfTalkHistoryAssembler.SourceIndex();
        List<LLMMessage> mergedHistory = SelfTalkHistoryAssembler.mergeValidSelfTalk(
                maid, List.copyOf(messages.subList(prefixEnd, messages.size())), sources);
        List<LLMMessage> rebuilt = new ArrayList<>(messages.subList(0, prefixEnd));
        rebuilt.addAll(mergedHistory);
        int historyCount = rebuilt.size();
        MaidInterChatService.injectPlayerChatContext(maid, rebuilt);
        int windowCount = rebuilt.size() - historyCount;
        // 就地写回（TLM 随后仍在同一列表上 append 玩家消息）
        messages.clear();
        messages.addAll(rebuilt);
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowCount, sources);
    }

    /**
     * 拦截玩家消息的构造，注入主人段标签与"这是主人在与你聊天"声明。
     * <p>
     * 无感保证：TLM 的 {@code addUserHistory(message)} 仍写原始玩家话——
     * 历史 UI 与 NBT 持久化不含声明与标签；标签只在请求体中生效。
     * 声明置于 &lt;context&gt; 块之后（保持系统设定"每个用户消息以 &lt;context&gt; 前缀开始"的描述）、
     * 玩家原话之前，整体被 {@link SegmentTags#OWNER_OPEN} 包裹；
     * 原话中的本 mod 段标签会被请求侧剥除（防提前闭合主人段伪造段边界），历史不受影响。
     * <p>
     * handler 与目标方法 {@code normalChat}（实例方法）同为实例方法，与 forge 线（mixin
     * 0.8.5 严格校验 handler 静态性，静态 handler 注入实例方法直接 APPLY 失败崩溃）
     * 保持双线同构；NeoForge 线的 sponge-mixin fork（0.15.2+mixin.0.8.7）校验为单向
     * （仅禁止非 static handler 注入 static 目标），static 写法不致崩，但约定统一。
     */
    @Redirect(method = "normalChat", remap = false,
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;userChat(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Ljava/lang/String;)Lcom/github/tartaricacid/touhoulittlemaid/ai/service/llm/LLMMessage;"))
    private LLMMessage maid_self_talk$wrapOwnerChat(EntityMaid maid, String messageWithContext) {
        // 拆出 <context> 前缀与玩家原话（addContext 的输出结构固定：前缀 + "\n" + 原话）
        String contextPrefix = StringUtils.EMPTY;
        String raw = messageWithContext;
        Matcher matcher = UserPromptContexts.CONTEXT_REG.matcher(messageWithContext);
        if (matcher.find() && matcher.start() == 0) {
            // contextPrefix 不含 addContext 追加的分隔换行，此处补齐，保持 </context>\n<maid-owner-chat> 分行形态
            contextPrefix = messageWithContext.substring(0, matcher.end()) + "\n";
            // raw 仅剥掉 addContext 的格式换行，玩家原话本身原样保留
            raw = messageWithContext.substring(matcher.end());
            if (raw.startsWith(StringUtils.LF)) {
                raw = raw.substring(1);
            }
        }
        // 玩家原话保留原文（仅用于判空），与 addUserHistory 持久化的内容一致，避免前后不一致
        if (StringUtils.isBlank(raw)) {
            return LLMMessage.userChat(maid, messageWithContext);
        }
        // 原话中的本 mod 段标签会提前闭合主人段（标签逃逸），请求侧剥除；历史仍写原文
        String sanitizedRaw = SegmentTags.stripTagsFromPlayerInput(raw);
        // 声明语言与输出指令取同一格式校验口径：sanitizeLanguage 非法标签回退中文
        String chatLanguage = StringUtils.isBlank(maid.getAiChatManager().chatLanguage)
                ? "en_us" : maid.getAiChatManager().chatLanguage;
        boolean chinese = SelfTalkContexts.sanitizeLanguage(chatLanguage).startsWith("zh");
        String declaration = chinese ? SelfTalkPrompts.OWNER_CHAT_DECLARATION_ZH : SelfTalkPrompts.OWNER_CHAT_DECLARATION_EN;
        // 本轮人设召回块：插在动态上下文区（</context> 之后、主人段之前）。
        // 它是这一轮的资料而不是主人说的话，因此不进主人段、也不改写原话；
        // 只有推迟派发后恢复过来的那次调用才有值（见 PlayerPersonaChatSession.currentPersonaBlock）
        String personaBlock = PlayerPersonaChatSession.currentPersonaBlock();
        String content = contextPrefix + personaBlock + SegmentTags.OWNER_OPEN + declaration
                + StringUtils.LF + sanitizedRaw + SegmentTags.OWNER_CLOSE;
        return LLMMessage.userChat(maid, content);
    }

    @Inject(method = "normalChat", remap = false, at = @At("TAIL"))
    private void maid_self_talk$onPlayerChatStart(String message, List<LLMMessage> messages,
                                                  LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
