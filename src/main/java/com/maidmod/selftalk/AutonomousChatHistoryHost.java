package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.ai.manager.entity.MaidAIChatData;
import com.github.tartaricacid.touhoulittlemaid.ai.service.llm.LLMMessage;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.nbt.CompoundTag;

import java.util.List;

/**
 * 独立聊天档案的存储宿主：由 {@code MaidAIChatDataMixin} 为每个 {@code MaidAIChatData}
 * 实例实现，并挂载一份 {@link AutonomousChatHistory}。
 * <p>
 * 双线统一使用这一宿主（不新增 NeoForge 附件、也不新增 Forge 实体字段），
 * 数据随该女仆 AI 数据写入世界存档；不放在静态的 {@link SelfTalkState} 中——
 * 静态状态表随实体卸载清空，而档案必须跨卸载与重启保留。
 * <p>
 * 宿主同时是 NBT 读写的唯一入口：档案自身不知道 TLM 历史 deque 的存在，
 * 由本接口在 TLM {@code writeToTag/readFromTag} 的实际调用点把<b>同一份</b>历史序列交给它，
 * 保证顺序元数据与 TLM 历史逐位对应。
 */
public interface AutonomousChatHistoryHost {

    /** 本实例的独立档案（惰性创建；客户端实例同样持有，供历史界面读取） */
    AutonomousChatHistory maid_self_talk$autonomousHistory();

    /**
     * 取某个 AI 数据实例的独立档案；宿主未挂载（未知实现）时返回 null，
     * 调用方按「无档案」处理而不是抛异常。
     */
    static AutonomousChatHistory of(MaidAIChatData data) {
        if (data instanceof AutonomousChatHistoryHost host) {
            return host.maid_self_talk$autonomousHistory();
        }
        return null;
    }

    /** 同上，按女仆取（女仆的 AI 数据为 null 时返回 null） */
    static AutonomousChatHistory of(EntityMaid maid) {
        if (maid == null) {
            return null;
        }
        return of(maid.getAiChatManager());
    }

    /**
     * 写入自定义 tag。{@code snapshot} 必须与本次 TLM 历史序列同源
     * （见 {@code MaidAIChatDataMixin} 在 {@code writeToTag} 内捕获的那一份）。
     */
    static void write(MaidAIChatData data, CompoundTag tag, List<LLMMessage> snapshot) {
        AutonomousChatHistory history = of(data);
        if (history != null) {
            history.writeTag(tag, snapshot);
        }
    }

    /**
     * 读取自定义 tag。{@code dequeInOrder} 为当前 TLM 历史（旧到新），
     * 顺序号按同一序列位置重新绑定。
     */
    static void read(MaidAIChatData data, CompoundTag tag, List<LLMMessage> dequeInOrder) {
        AutonomousChatHistory history = of(data);
        if (history != null) {
            history.readTag(tag, dequeInOrder);
        }
    }

    /**
     * 发言女仆的展示名称（仅作元数据与界面标签，绝不拼进存储正文、TTS 文本或发送给模型的原话）。
     * <p>
     * 名称取值与「附近女仆身份」上下文同源：命名牌优先，未命名的 TLM 内置模型用预置名称，
     * YSM／第三方自定义模型视为无名称，此时退化为空串（界面只显示来源标签）。
     */
    static String displayNameOf(EntityMaid speaker) {
        if (speaker == null) {
            return "";
        }
        try {
            String language = SelfTalkMaidNames.resolveQueryLanguage(speaker);
            var name = SelfTalkMaidNames.resolveName(speaker, language);
            return name == null ? "" : name.getString();
        } catch (Throwable t) {
            // 名称解析涉及模型资源，任何异常都不应影响归档主流程
            return "";
        }
    }
}
