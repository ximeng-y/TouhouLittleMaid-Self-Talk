package com.maidmod.selftalk.history;

/**
 * 历史消息指纹的唯一实现（自话／欢迎语来源判定用）。
 * <p>
 * 形态为 {@code role|长度|正文|gameTime}：三元组相等 ⇔ 字符串相等，带长度前缀防歧义，
 * 不存在哈希碰撞误判。
 * <p>
 * TLM 历史里有三处使用方（本类的纯文本视图、NeoForge 线的自话指纹附件、Forge 线的 mixin
 * 挂载指纹），全部委托到这里，避免同一算法多处实现后悄悄漂移。
 */
public final class HistoryFingerprint {

    private HistoryFingerprint() {
    }

    public static String of(String role, String text, long gameTime) {
        String content = text == null ? "" : text;
        return role + '|' + content.length() + '|' + content + '|' + gameTime;
    }
}
