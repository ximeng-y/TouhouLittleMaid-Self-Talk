package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.client.renderer.entity.chatbubble.ChatBubbleRenderer;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleDataCollection;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.ChatBubbleManager;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.maidmod.selftalk.SelfTalkWaitingBubbleData;
import com.maidmod.selftalk.client.SelfTalkClientConfig;
import it.unimi.dsi.fastutil.longs.Long2ObjectAVLTreeMap;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 客户端渲染过滤：本地开关关闭时隐藏自话/互聊的等待气泡（issue #78）。
 * <p>
 * 在 {@link ChatBubbleRenderer#render} 内，重定向取得气泡集合的这一处调用
 * {@code ChatBubbleManager.getChatBubbleDataCollection()}——发生在 TLM 的 {@code size()}、
 * 左右排列与中间气泡选择<b>之前</b>，因此不会留下被隐藏气泡的布局空位。
 * 配置开启或集合为空时原样返回原集合；关闭时仅排除带自话/互聊等待标记的气泡，返回本次绘制
 * 使用的集合副本——使用原 key 与原对象引用逐项 {@code put}（不调会重算 key 的 {@code add}），
 * 保持原排序，不复制气泡数据对象，不修改管理器持有的真实集合、不调用 {@code removeChatBubble}，
 * 也不缓存过滤结果（下一帧重新读取配置与当前集合）。
 * <p>
 * 保留对象身份：不触碰 Agent Tweaks 已重定向的 {@code IChatBubbleData.getRenderer}；原对象与
 * 原生 renderer 均未替换，{@code ThinkingDisplayCache.currentFor(maid, data)} 的身份比较继续成立，
 * 开关重新开启时，尚未过期的原气泡及其思考展示可继续显示。
 */
@Mixin(value = ChatBubbleRenderer.class, remap = false)
public class SelfTalkChatBubbleRendererMixin {

    @Redirect(method = "render",
            at = @At(value = "INVOKE",
                    target = "Lcom/github/tartaricacid/touhoulittlemaid/entity/chatbubble/ChatBubbleManager;"
                            + "getChatBubbleDataCollection()"
                            + "Lcom/github/tartaricacid/touhoulittlemaid/entity/chatbubble/ChatBubbleDataCollection;"))
    private ChatBubbleDataCollection maid_self_talk$collectionForRender(ChatBubbleManager manager) {
        ChatBubbleDataCollection chatBubble = manager.getChatBubbleDataCollection();
        if (SelfTalkClientConfig.showAutonomousWaitingBubbles() || chatBubble == null || chatBubble.isEmpty()) {
            return chatBubble;
        }
        boolean anyAutonomous = false;
        for (IChatBubbleData data : chatBubble.chatBubbles().values()) {
            if (SelfTalkWaitingBubbleData.isAutonomous(data)) {
                anyAutonomous = true;
                break;
            }
        }
        if (!anyAutonomous) {
            return chatBubble;
        }
        ChatBubbleDataCollection filtered = new ChatBubbleDataCollection(new Long2ObjectAVLTreeMap<>());
        for (var entry : chatBubble.chatBubbles().long2ObjectEntrySet()) {
            IChatBubbleData data = entry.getValue();
            if (!SelfTalkWaitingBubbleData.isAutonomous(data)) {
                filtered.put(entry.getLongKey(), data);
            }
        }
        return filtered;
    }
}