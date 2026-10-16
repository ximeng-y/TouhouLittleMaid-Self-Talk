package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.network.MaidPersonaSplitRequestMessage;
import com.maidmod.selftalk.network.MaidPersonaSplitResponseMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import javax.annotation.Nullable;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * AI 拆分人设的客户端缓存（仅客户端，所有状态只在客户端主线程访问）。
 * <p>
 * 任务与页面生命周期分开：条目按女仆 UUID 保存，记录 requestId、发起页面唯一标识、
 * 是否离开过页面、状态与完整候选。整理结果不写宿主（实体卸载后回包照常接收）；
 * 候选仅保留在本次连接内，断线清除，不写 NBT。
 * <p>
 * 只接收本连接已登记且 requestId + 女仆 UUID 匹配的响应；迟到/伪造/未登记的响应一律丢弃。
 * <p>
 * 候选的消费出口只有两个：对应保存请求<b>成功</b>确认后删除；或玩家在重开确认框里
 * 显式丢弃。载入候选（页面填入）不删除——载入后未保存再关页，候选仍可找回。
 */
@OnlyIn(Dist.CLIENT)
public final class MaidPersonaSplitClient {

    private MaidPersonaSplitClient() {
    }

    /** 整理任务状态 */
    public enum Status {
        RUNNING, READY
    }

    /** 按女仆 UUID 一条的整理条目 */
    public record Entry(UUID maidUuid, UUID requestId, UUID pageId, boolean leftPage,
                        Status status, @Nullable MaidPersonaDraft candidate) {
    }

    /** 女仆 UUID -> 整理条目（每只女仆最多一条；同女仆重复发起覆盖） */
    private static final Map<UUID, Entry> ENTRIES = new HashMap<>();

    /** 保存请求 id -> {女仆 UUID, 整理 requestId}：保存结果确认后据此消费对应候选 */
    private static final Map<UUID, SaveLink> SAVE_LINKS = new HashMap<>();

    private record SaveLink(UUID maidUuid, UUID splitRequestId) {
    }

    /**
     * 发起拆分：登记条目并发送请求（页面已通过确认框，此处直接登记）。
     */
    public static void startRequest(UUID requestId, EntityMaid maid, UUID pageId, String sourceText) {
        if (requestId == null || maid == null || pageId == null) {
            return;
        }
        ENTRIES.put(maid.getUUID(), new Entry(maid.getUUID(), requestId, pageId, false,
                Status.RUNNING, null));
        SelfTalkPackets.CHANNEL.sendToServer(new MaidPersonaSplitRequestMessage(requestId,
                maid.getId(), maid.getUUID(), sourceText));
    }

    /** 该女仆是否有在途整理任务（重开页面据此显示等待并锁编辑） */
    public static boolean isRunningFor(UUID maidUuid) {
        Entry entry = ENTRIES.get(maidUuid);
        return entry != null && entry.status() == Status.RUNNING;
    }

    /** 是否存在任意在途整理任务（每名玩家服务端同时只放行一个，发起前先给可见的 busy 提示） */
    public static boolean hasAnyRunning() {
        for (Entry entry : ENTRIES.values()) {
            if (entry.status() == Status.RUNNING) {
                return true;
            }
        }
        return false;
    }

    /** 该女仆是否有待载入的 READY 候选（未保存或丢弃前禁止再次整理） */
    public static boolean hasReadyCandidate(UUID maidUuid) {
        Entry entry = ENTRIES.get(maidUuid);
        return entry != null && entry.status() == Status.READY;
    }

    /** 该女仆的整理条目（无则 {@code null}） */
    @Nullable
    public static Entry entryFor(UUID maidUuid) {
        return ENTRIES.get(maidUuid);
    }

    /**
     * 丢弃候选（重开确认框「丢弃结果」）：页面与实际人设不变。
     * 只删除与给定 requestId 匹配的那一条，防误删后来的新候选。
     */
    public static void discardCandidate(UUID maidUuid, UUID requestId) {
        Entry entry = ENTRIES.get(maidUuid);
        if (entry != null && entry.status() == Status.READY && entry.requestId().equals(requestId)) {
            ENTRIES.remove(maidUuid);
        }
    }

    /**
     * 页面真正离开时标记（确认浮层不算离开；resize 不算）。
     * 离开后结果不再直接填入页面，改为暂存等重开确认。
     */
    public static void markLeft(UUID maidUuid, UUID pageId) {
        Entry entry = ENTRIES.get(maidUuid);
        if (entry != null && entry.pageId().equals(pageId)) {
            ENTRIES.put(maidUuid, new Entry(entry.maidUuid(), entry.requestId(), entry.pageId(),
                    true, entry.status(), entry.candidate()));
        }
    }

    /**
     * 页面保存提交时登记「保存请求 → 整理候选」关联：保存成功后消费对应候选。
     */
    public static void linkSave(UUID saveRequestId, UUID maidUuid, UUID splitRequestId) {
        if (saveRequestId == null || maidUuid == null || splitRequestId == null) {
            return;
        }
        SAVE_LINKS.put(saveRequestId, new SaveLink(maidUuid, splitRequestId));
    }

    /**
     * 人设保存结果确认（由 {@code SelfTalkPlayerSettingsClient} 在确认是已登记的保存响应后转交）。
     * <p>
     * 成功才删除对应候选，失败保留；即便页面关闭或实体卸载也按保存确认处理。
     * 只删除关联的那一份——当前条目的 requestId 必须与关联的 splitRequestId 一致，
     * 旧回包不能清除后来的候选。
     */
    public static void onPersonaSaveConfirmed(UUID saveRequestId, boolean success) {
        SaveLink link = SAVE_LINKS.remove(saveRequestId);
        if (!success || link == null) {
            return;
        }
        Entry entry = ENTRIES.get(link.maidUuid());
        if (entry != null && entry.status() == Status.READY
                && entry.requestId().equals(link.splitRequestId())) {
            ENTRIES.remove(link.maidUuid());
        }
    }

    /**
     * 收到拆分结果：按 requestId + 女仆 UUID 匹配本连接登记条目，状态只更新一次；
     * 随后仅通知「当前打开的该女仆人设页」（候选接收不要求实体仍在加载，绝不写宿主）。
     */
    public static void onSplitResponse(MaidPersonaSplitResponseMessage payload) {
        if (payload.getMaidUuid() == null || payload.getRequestId() == null) {
            return;
        }
        Entry entry = ENTRIES.get(payload.getMaidUuid());
        if (entry == null || entry.status() != Status.RUNNING
                || !entry.requestId().equals(payload.getRequestId())) {
            return;
        }
        if (payload.isSuccess()) {
            Entry ready = new Entry(entry.maidUuid(), entry.requestId(), entry.pageId(),
                    entry.leftPage(), Status.READY, payload.toDraft());
            ENTRIES.put(entry.maidUuid(), ready);
            notifyCurrentScreen(entry.maidUuid(), true, ready, null);
        } else {
            ENTRIES.remove(entry.maidUuid());
            notifyCurrentScreen(entry.maidUuid(), false, null, payload.getResultCode());
        }
    }

    /** 通知当前打开的该女仆人设页（不是该女仆的页面不通知，结果暂存等重开确认） */
    private static void notifyCurrentScreen(UUID maidUuid, boolean success,
                                            @Nullable Entry entry, @Nullable String failCode) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof MaidPersonaEditScreen screen && screen.isPageOf(maidUuid)) {
            screen.onSplitFinished(success, entry, failCode);
        }
    }

    /** 客户端断线：清空全部整理条目与保存关联（残留的旧连接请求不可能再回包） */
    public static void clearAll() {
        ENTRIES.clear();
        SAVE_LINKS.clear();
    }
}
