package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.SelfTalkProvenanceHost;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 自话指纹的内存载体：给 EntityMaid 挂三个 @Unique 字段（duck 接口实现）。
 * <p>
 * Forge 1.20.1 无数据附件，指纹直接存实体实例字段：
 * 跨维度经 TLM TeleportHelper 原实体搬家自然保留；死亡重生/存档读档
 * 由 {@code MaidAIChatDataMixin} 的 NBT 读写钩子恢复。
 * 本 mixin 零方法注入（纯字段载体），TLM 类 prod 不混淆、无重映射问题。
 * 集合懒初始化为并发集：登记/删除在 LLM 响应线程，读取在服务端主线程；
 * getter 加 synchronized 防懒初始化竞态——首次并发访问时两线程各建集合相互覆盖会丢登记，
 * monitor 顺带保证字段引用的跨线程可见性（集合内容的线程安全由并发集自身保证）。
 */
@Mixin(EntityMaid.class)
public abstract class EntityMaidProvenanceMixin implements SelfTalkProvenanceHost {

    @Unique
    private Set<String> maid_self_talk$selfFingerprints;
    @Unique
    private Set<String> maid_self_talk$legacyFingerprints;
    @Unique
    private boolean maid_self_talk$legacyInitialized;

    @Override
    public synchronized Set<String> maid_self_talk$selfFingerprints() {
        if (this.maid_self_talk$selfFingerprints == null) {
            this.maid_self_talk$selfFingerprints = ConcurrentHashMap.newKeySet();
        }
        return this.maid_self_talk$selfFingerprints;
    }

    @Override
    public synchronized Set<String> maid_self_talk$legacyFingerprints() {
        if (this.maid_self_talk$legacyFingerprints == null) {
            this.maid_self_talk$legacyFingerprints = ConcurrentHashMap.newKeySet();
        }
        return this.maid_self_talk$legacyFingerprints;
    }

    @Override
    public boolean maid_self_talk$legacyInitialized() {
        return this.maid_self_talk$legacyInitialized;
    }

    @Override
    public void maid_self_talk$setLegacyInitialized(boolean value) {
        this.maid_self_talk$legacyInitialized = value;
    }

    @Override
    public void maid_self_talk$clearProvenanceData() {
        // 不置 null 而是清空：引用不变，避免与响应线程并发写入竞态丢失
        this.maid_self_talk$selfFingerprints().clear();
        this.maid_self_talk$legacyFingerprints().clear();
        this.maid_self_talk$legacyInitialized = false;
    }
}
