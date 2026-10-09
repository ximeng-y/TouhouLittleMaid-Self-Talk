package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.AutonomousChatHistoryMigration;
import com.maidmod.selftalk.InterChatChain;
import com.maidmod.selftalk.InterChatRequest;
import com.maidmod.selftalk.MaidInterChatService;
import com.maidmod.selftalk.MaidSelfTalkService;
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

    @Inject(method = "normalChat", at = @At("HEAD"))
    private void maid_self_talk$injectInterChatContext(String message, List<LLMMessage> messages,
                                                       LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        // 旧数据迁移：本次请求的历史区即将被组装，先把旧指纹标明的自话搬进独立档案，
        // 再开始合并——迁移必须发生在读取 TLM 历史之前，否则旧自话会被重复注入（幂等）
        AutonomousChatHistoryMigration.ensureMigratedOnServerThread(maid);
        // 主人插话：真实派发的主动聊天会立即终止该女仆所在的连续互聊链，
        // 必须在注入互聊窗口之前处理，否则被丢弃的旧回复会随窗口重新进入本次请求
        InterChatChain chain = InterChatChain.activeFor(maid);
        long chainId = 0;
        if (chain != null) {
            chainId = chain.id();
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
        } else {
            // 链已释放（首轮中断已释放、旧请求还在飞行时再次插话）：按状态表登记的「当前请求」兜底
            // 作废——第二次插话的迟到回复必须同样沉默，不能经旧轮身份继续产生可见副作用
            InterChatRequest leftover = SelfTalkState.get(maid.getId()).currentInterChatRequest;
            if (leftover != null) {
                chainId = leftover.chainId();
                SelfTalkDispatcher.dropQueuedForChain(maid, chainId);
                leftover.cancel();
            }
        }
        // 玩家 chat 与自话／互聊共用同一份来源口径：TLM 历史 + 按持久顺序号合并的有效自话。
        // 独立记录不进 TLM deque，这里只写本次请求的副本；来源索引随合并一并登记，
        // 段标签据此按对象身份把独立记录归入自话段
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
    @Redirect(method = "normalChat",
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
        String content = contextPrefix + SegmentTags.OWNER_OPEN + declaration
                + StringUtils.LF + sanitizedRaw + SegmentTags.OWNER_CLOSE;
        return LLMMessage.userChat(maid, content);
    }

    @Inject(method = "normalChat", at = @At("TAIL"))
    private void maid_self_talk$onPlayerChatStart(String message, List<LLMMessage> messages,
                                                  LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        MaidSelfTalkService.onPlayerChatStart(self.getMaid());
    }
}
