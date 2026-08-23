package com.maidmod.selftalk;

import java.util.Set;

/**
 * 女仆自话指纹的内存载体接口（Forge 1.20.1 无数据附件，由 {@code EntityMaidProvenanceMixin} 实现）。
 * <p>
 * 方法名统一带 {@code maid_self_talk$} 前缀，避免与 TLM 或其他 mod 的 mixin 成员冲突。
 * 集合为并发集（懒初始化）：登记/删除在 LLM 响应线程（自话回调、TLM 压缩回调），读取在服务端主线程。
 * 持久化由 {@code MaidAIChatDataMixin} 在女仆 NBT 读写时序列化/恢复本接口的数据。
 */
public interface SelfTalkProvenanceHost {

    /** 自话回复指纹集（来源判定：命中即归「自话/互聊段」） */
    Set<String> maid_self_talk$selfFingerprints();

    /** 老会话（首次启用段标签时的历史快照）指纹集（命中即段外原样） */
    Set<String> maid_self_talk$legacyFingerprints();

    /** 老会话快照是否已初始化（一次性，true 后不再变更） */
    boolean maid_self_talk$legacyInitialized();

    void maid_self_talk$setLegacyInitialized(boolean value);

    /** 清空全部指纹与初始化标记（历史清空时调用） */
    void maid_self_talk$clearProvenanceData();
}
