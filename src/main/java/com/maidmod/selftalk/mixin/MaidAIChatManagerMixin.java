package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatManager;
import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.UserPromptContexts;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMClient;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidInterChatService;
import com.maidmod.selftalk.MaidSelfTalkService;
import com.maidmod.selftalk.SelfTalkContexts;
import com.maidmod.selftalk.SelfTalkPrompts;
import com.maidmod.selftalk.SegmentTags;
import org.apache.commons.lang3.StringUtils;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

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

    @Inject(method = "normalChat", remap = false, at = @At("HEAD"))
    private void maid_self_talk$injectInterChatContext(String message, List<LLMMessage> messages,
                                                       LLMClient chatClient, CallbackInfo ci) {
        MaidAIChatManager self = (MaidAIChatManager) (Object) this;
        EntityMaid maid = self.getMaid();
        int historyCount = messages.size();
        MaidInterChatService.injectPlayerChatContext(maid, messages);
        int windowCount = messages.size() - historyCount;
        // 就地写回（TLM 随后仍在同一列表上 append 玩家消息）
        SelfTalkContexts.wrapSegments(maid, messages, historyCount, windowCount);
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
     * handler 必须与目标方法 {@code normalChat} 同为实例方法：mixin 在 APPLY 时校验
     * {@code static} 修饰符一致，静态 handler 注入实例方法会抛
     * {@code InvalidInjectionException}（应用失败 → 女仆实体实例化时崩溃）。
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
        // 声明语言与输出指令取同一白名单口径：sanitizeLanguage 未知语言回退中文
        String chatLanguage = StringUtils.isBlank(maid.getAiChatManager().chatLanguage)
                ? "en_us" : maid.getAiChatManager().chatLanguage;
        boolean chinese = SelfTalkContexts.sanitizeLanguage(chatLanguage).startsWith("zh");
        String declaration = chinese ? SelfTalkPrompts.OWNER_CHAT_DECLARATION_ZH : SelfTalkPrompts.OWNER_CHAT_DECLARATION_EN;
        String content = contextPrefix + SegmentTags.OWNER_OPEN + declaration
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
