package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.Role;
import org.apache.commons.lang3.StringUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 原生玩家链的请求级列表载体；复制、重建消息时显式沿用同一来源，不从正文猜测来源。 */
public final class PlayerChatContext<T> extends ArrayList<T> {
    private final Source source;

    private PlayerChatContext(Collection<? extends T> messages, Source source) {
        super(messages);
        this.source = source;
    }

    /** 仅在原生 tryToChat 调用 normalChat 的边界创建新来源。 */
    public static <T> PlayerChatContext<T> fromPlayer(List<T> messages) {
        return new PlayerChatContext<>(messages, new Source());
    }

    /** 保留本次输入的来源；未标记的列表不会因复制或重建而获得玩家身份。 */
    public static <T> List<T> rebuild(List<T> original, List<T> rebuilt) {
        Source source = sourceOf(original);
        return source == null ? new ArrayList<>(rebuilt) : new PlayerChatContext<>(rebuilt, source);
    }

    public static Source sourceOf(List<?> messages) {
        return messages instanceof PlayerChatContext<?> context ? context.source : null;
    }

    /** 修饰已经构造好的真实玩家 USER 消息；未知来源、非 USER 和原有空白边界保持原对象。 */
    public static LLMMessage decorateOwner(LLMMessage original, Source source,
                                           String declaration, Pattern contextPattern) {
        if (source == null || original.role() != Role.USER) {
            return original;
        }
        String messageWithContext = original.message();
        String contextPrefix = StringUtils.EMPTY;
        String raw = messageWithContext;
        Matcher matcher = contextPattern.matcher(messageWithContext);
        if (matcher.find() && matcher.start() == 0) {
            contextPrefix = messageWithContext.substring(0, matcher.end()) + StringUtils.LF;
            raw = messageWithContext.substring(matcher.end());
            if (raw.startsWith(StringUtils.LF)) {
                raw = raw.substring(1);
            }
        }
        if (StringUtils.isBlank(raw)) {
            return original;
        }
        // 只沿用本 mod 的段标签清洗，其他增强的未知标签与正文不另外过滤。
        String sanitizedRaw = SegmentTags.stripTagsFromPlayerInput(raw);
        String content = contextPrefix + source.personaBlock() + SegmentTags.OWNER_OPEN
                + declaration + StringUtils.LF + sanitizedRaw + SegmentTags.OWNER_CLOSE;
        // LLMMessage 为不可变 record；只换正文，角色、时间与工具元数据全部沿用实际对象。
        return new LLMMessage(original.role(), content, original.gameTime(),
                original.toolCalls(), original.toolCallId());
    }

    /** 每次输入独立持有；人设规划的消息副本不使用此载体。全部字段由服务端主线程访问。 */
    public static final class Source {
        private PlayerChatRequest request;
        private String personaBlock = "";

        public PlayerChatRequest request() {
            return request;
        }

        public void prepare(PlayerChatRequest request) {
            this.request = request;
        }

        public String personaBlock() {
            return personaBlock;
        }

        public void setPersonaBlock(String personaBlock) {
            this.personaBlock = personaBlock;
        }
    }
}
