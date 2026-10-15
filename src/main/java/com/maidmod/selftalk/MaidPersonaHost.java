package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;

/**
 * 结构化人设草稿的存储宿主：由 {@code MaidAIChatPersonaMixin} 为每个 {@code MaidAIChatData}
 * 实例实现，并挂载一份 {@link MaidPersonaDraft}。
 * <p>
 * 草稿归属于 {@code MaidAIChatData} 实例本身（随女仆 AI 数据写入世界存档），<b>不</b>放在静态表、
 * 玩家设置或聊天历史对象里：静态表随实体卸载清空，而草稿必须跨实体卸载、重新加载与服务端重启保留。
 * <p>
 * 宿主同时是读写入口：草稿的 NBT 读写只由本接口在 TLM {@code writeToTag/readFromTag} 的实际调用点触发，
 * 客户端实例同样持有（开屏同步走同一对方法），供编辑页读取。
 */
public interface MaidPersonaHost {

    /** 本实例当前的草稿（恒非 null；未写入过时为空草稿） */
    MaidPersonaDraft maid_self_talk$personaDraft();

    /** 整份替换草稿（不做逐栏写入，避免中间态） */
    void maid_self_talk$setPersonaDraft(MaidPersonaDraft draft);

    /**
     * 取某个 AI 数据实例的宿主；未挂载（未知实现）时返回 null，
     * 调用方按「草稿不可用」处理而不是抛异常。
     */
    static MaidPersonaHost hostOf(MaidAIChatData data) {
        return data instanceof MaidPersonaHost host ? host : null;
    }

    /** 同上，按女仆取（女仆或其 AI 数据为 null 时返回 null） */
    static MaidPersonaHost hostOf(EntityMaid maid) {
        return maid == null ? null : hostOf(maid.getAiChatManager());
    }

    /** 读女仆当前草稿；宿主不可用时返回空草稿 */
    static MaidPersonaDraft draftOf(EntityMaid maid) {
        MaidPersonaHost host = hostOf(maid);
        return host == null ? MaidPersonaDraft.EMPTY : host.maid_self_talk$personaDraft();
    }

    /** 整份替换女仆草稿；宿主不可用时返回 false（调用方据此回 unavailable） */
    static boolean replace(EntityMaid maid, MaidPersonaDraft draft) {
        MaidPersonaHost host = hostOf(maid);
        if (host == null) {
            return false;
        }
        host.maid_self_talk$setPersonaDraft(draft == null ? MaidPersonaDraft.EMPTY : draft);
        return true;
    }

    /** TLM 序列化 AI 数据时写出草稿（与 TLM 自有八字段互不影响） */
    static void write(MaidAIChatData data, CompoundTag tag) {
        MaidPersonaHost host = hostOf(data);
        if (host != null) {
            host.maid_self_talk$personaDraft().writeTo(tag);
        }
    }

    /** TLM 解析 AI 数据后读入草稿：每次读档都整份替换，标签缺失即空草稿 */
    static void read(MaidAIChatData data, CompoundTag tag) {
        MaidPersonaHost host = hostOf(data);
        if (host != null) {
            host.maid_self_talk$setPersonaDraft(MaidPersonaDraft.readFrom(tag));
        }
    }
}
