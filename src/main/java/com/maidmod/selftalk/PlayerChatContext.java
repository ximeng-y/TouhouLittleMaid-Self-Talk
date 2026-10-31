package com.maidmod.selftalk;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

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
