package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.network.MaidPersonaSavePayload;
import com.maidmod.selftalk.network.MaidPersonaSaveResponsePayload;
import com.maidmod.selftalk.network.MaidPersonaSplitResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 四栏人设编辑页：从原版「✎ 人设编辑」页（SettingEditScreen）进入，返回时回到那个页面。
 * <p>
 * 顶部一个模式开关（原版人设 / 四栏人设），下方固定四类（身份 / 性格 / 背景 / 爱好），
 * 左侧分类按钮 + 右侧一个多行输入框；打开时从客户端宿主读取已同步配置，复制成页面配置与
 * 「已保存基线」两份——基线用于判断是否有未保存改动，页面配置是唯一编辑源。
 * <p>
 * <b>模式与四栏是同一份待提交内容</b>：切模式与改文字都会使页面变 dirty，保存时冻结成一个完整的
 * {@link MaidPersonaConfig} 快照整份提交。原版整段人设（{@code customSetting}）既不是本页的数据源，
 * 也不是本页的写入目标——两套内容独立保存，切模式不复制、不覆盖、不删除另一套；原版模式下同样允许
 * 编辑并保存四栏内容（存着备用，切回四栏即生效）。
 * <p>
 * 保存是「请求 - 结果确认」两段式：提交时生成新 requestId 并冻结本次快照，服务端整份替换后按该
 * requestId 回结果，回包里带服务端确认的完整配置。等待期间禁止继续编辑与重复提交；只有当前打开、
 * id 匹配的页面处理响应，关闭后的迟到响应不会落到任何界面上。成功以<b>服务端确认值</b>写回客户端宿主
 * 与基线并清 dirty；失败保留页面内容与 dirty，且不得被当成已保存。
 * <p>
 * <b>AI 拆分</b>：进入本页时从原版输入框冻结一份文本快照（含未保存修改），「AI 拆分」按钮据此
 * 发起一次静默整理请求。成功且发起页面一直未离开、仍是当前页面时整份填入四栏并选中四栏模式
 * （保持基线不变，仍需手动保存）；其他情况暂存为候选，重开该女仆页面时弹确认载入。
 * 整理期间禁止编辑、切模式、保存与重复发起（允许查看分类与离开页面）；关闭界面不取消任务，
 * 完成后服务端向发起玩家发私信。候选仅保留在本次连接内，保存成功或显式丢弃才消费。
 */
@OnlyIn(Dist.CLIENT)
public class MaidPersonaEditScreen extends Screen {

    /** 左侧分类按钮列宽（模式开关同宽，与分类列左对齐） */
    private static final int CATEGORY_WIDTH = 110;
    /** 左列与输入框之间的间隔 */
    private static final int COLUMN_GAP = 12;
    /** 输入框最大宽度（窄屏时收窄） */
    private static final int BOX_MAX_WIDTH = 300;
    /** 底部按钮尺寸 */
    private static final int BOTTOM_BUTTON_WIDTH = 90;
    private static final int BOTTOM_BUTTON_HEIGHT = 20;
    /** 底部三个按钮之间的间隔 */
    private static final int BOTTOM_BUTTON_GAP = 6;
    /** 内容区顶部（模式开关 + 说明行之下） */
    private static final int CONTENT_TOP = 60;

    private final EntityMaid maid;
    /** 返回目标：进入本页的原版人设编辑页实例（不新建原版页面） */
    private final @Nullable Screen parent;

    /** 页面模式（编辑源的一部分；与四栏内容一起构成待提交配置） */
    private MaidPersonaMode mode;
    /** 四栏页面内容（编辑源；与输入框内容实时同步） */
    private final String[] draft = new String[MaidPersonaDraft.CATEGORY_COUNT];
    /** 已保存基线：最近一次「服务端确认成功」的完整配置，用于判定 dirty */
    private MaidPersonaConfig baseline;
    /** 是否显示「已保存」状态：仅当页面内容相对基线无改动时成立（render 里随 dirty 收敛） */
    private boolean savedShown;
    /** 当前分类（0..3，对应 MaidPersonaDraft 的四个栏位） */
    private int category;

    /** 本次提交的 requestId；非 null 表示正在等待结果（禁止编辑与重复提交） */
    private UUID pendingRequestId;
    /** 「保存并返回」：只在收到成功结果后才回父页面 */
    private boolean returnAfterSave;

    /** 程序化 setValue 期间挂起监听，防把载入的栏内容误当输入 */
    private boolean loadingBox;

    /** 本女仆仍有跨页面待确认保存：该旧基线在确认前不得进入可编辑/可提交状态 */
    private boolean awaitingConfirmation;

    /** 进入本页时冻结的原版输入框文本快照（含未保存修改）：AI 拆分的唯一资料来源 */
    private final String splitSourceText;
    /** 本页实例唯一标识：整理任务与页面的关联（重开页面是新实例、新 id） */
    private final UUID pageId = UUID.randomUUID();
    /** 是否已真正离开本页（确认浮层不算离开；resize 不算） */
    private boolean leftPage;
    /** setScreen 到确认浮层前置位：removed() 里据此区分「浮层接管」与「真离开」 */
    private boolean openingConfirm;
    /** 本次页面访问是否已弹过「载入 AI 整理结果」确认（ESC 暂缓后本访问不再弹） */
    private boolean splitConfirmShown;
    /** 本页已载入的整理候选来源 requestId（保存时登记关联，保存成功后消费候选） */
    private UUID loadedSplitRequestId;

    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;

    private Button modeButton;
    private GuardedPersonaEditBox box;
    private Button saveButton;
    private Button saveAndBackButton;
    private Button splitButton;

    public MaidPersonaEditScreen(@Nullable Screen parent, EntityMaid maid, String sourceText) {
        super(Component.translatable("config.maid_self_talk.screen.persona_draft.title"));
        this.parent = parent;
        this.maid = maid;
        this.splitSourceText = sourceText == null ? "" : sourceText;
        // 打开时读客户端已同步配置：宿主不可用（未挂载/未同步）时按默认配置处理，不阻断页面
        MaidPersonaConfig current = MaidPersonaHost.configOf(maid);
        this.mode = current.mode();
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = current.draft().get(i);
        }
        this.baseline = current;
        // 跨页面待确认门禁：同女仆仍有未确认保存时（旧请求回包未到），
        // 禁止编辑与提交，等待确认后由 onHostConfirmed 并入并解锁，避免旧基线覆盖新值
        this.awaitingConfirmation = SelfTalkPlayerSettingsClient.hasPendingSaveFor(this.maid.getId());
    }

    @Override
    protected void init() {
        this.clearWidgets();

        // 左分类列 + 间隔 + 输入框作为整体相对屏幕中线对称摆放（窄屏收窄输入框）
        int boxWidth = Math.min(BOX_MAX_WIDTH, Math.max(120, this.width - CATEGORY_WIDTH - COLUMN_GAP - 40));
        int leftX = this.width / 2 - (CATEGORY_WIDTH + COLUMN_GAP + boxWidth) / 2;
        int boxX = leftX + CATEGORY_WIDTH + COLUMN_GAP;
        int top = CONTENT_TOP;
        int bottomY = this.height - 28;
        int boxHeight = Math.max(40, bottomY - 24 - top);

        this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), b -> toggleMode())
                .bounds(leftX, 22, CATEGORY_WIDTH, 20)
                .build());

        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            int index = i;
            this.addRenderableWidget(Button.builder(categoryLabel(i), b -> switchCategory(index))
                    .bounds(leftX, top + i * 24, CATEGORY_WIDTH, 20)
                    .build());
        }

        // AI 拆分入口：位于四栏分类列之下；原文空白时禁用（等待/门禁期间在 render 里统一禁用）
        this.splitButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.persona_draft.button.split"),
                        b -> startSplit())
                .bounds(leftX, top + MaidPersonaDraft.CATEGORY_COUNT * 24 + 8, CATEGORY_WIDTH, 20)
                .build());

        this.box = new GuardedPersonaEditBox(this.font, boxX, top, boxWidth, boxHeight,
                Component.translatable("config.maid_self_talk.screen.persona_draft.placeholder"));
        this.box.setValueListener(value -> {
            if (!this.loadingBox) {
                this.draft[this.category] = value;
            }
        });
        this.box.setValue(this.draft[this.category]);
        this.addRenderableWidget(this.box);

        int buttonsWidth = 3 * BOTTOM_BUTTON_WIDTH + 2 * BOTTOM_BUTTON_GAP;
        int buttonsX = this.width / 2 - buttonsWidth / 2;
        this.saveButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.persona_draft.button.save"),
                        b -> submit(false))
                .bounds(buttonsX, bottomY, BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT)
                .build());
        this.saveAndBackButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.persona_draft.button.save_and_back"),
                        b -> submit(true))
                .bounds(buttonsX + BOTTOM_BUTTON_WIDTH + BOTTOM_BUTTON_GAP, bottomY,
                        BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT)
                .build());
        this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.persona_draft.button.back"),
                        b -> this.onClose())
                .bounds(buttonsX + 2 * (BOTTOM_BUTTON_WIDTH + BOTTOM_BUTTON_GAP), bottomY,
                        BOTTOM_BUTTON_WIDTH, BOTTOM_BUTTON_HEIGHT)
                .build());
    }

    /** 模式开关标签：显示当前模式（点击切到另一个） */
    private Component modeLabel() {
        return Component.translatable("config.maid_self_talk.screen.persona_draft.mode.button", modeName(this.mode));
    }

    private static Component modeName(MaidPersonaMode mode) {
        return Component.translatable("config.maid_self_talk.screen.persona_draft.mode." + mode.tag());
    }

    private static Component categoryLabel(int index) {
        return Component.translatable("config.maid_self_talk.screen.persona_draft.category." + categoryKey(index));
    }

    private static String categoryKey(int index) {
        return switch (index) {
            case 0 -> "identity";
            case 1 -> "personality";
            case 2 -> "background";
            default -> "hobbies";
        };
    }

    /**
     * 切换模式：只改页面模式，不触碰四栏文本、也不触碰原版整段人设。
     * 模式是待提交内容的一部分，因此切换后页面进入 dirty（保存时才真正落盘）。
     */
    private void toggleMode() {
        if (this.pendingRequestId != null || this.awaitingConfirmation) {
            return;
        }
        this.mode = this.mode.isStructured() ? MaidPersonaMode.ORIGINAL : MaidPersonaMode.STRUCTURED;
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
    }

    /**
     * 切换分类：先落盘当前输入框内容，再载入目标栏。
     * 载入期间挂起监听，避免 setValue 触发的回调把目标栏内容写进刚切走的栏位。
     */
    private void switchCategory(int target) {
        if (target == this.category || this.pendingRequestId != null || this.awaitingConfirmation) {
            return;
        }
        if (this.box != null) {
            this.draft[this.category] = this.box.getValue();
        }
        this.category = target;
        if (this.box != null) {
            this.loadingBox = true;
            this.box.setValue(this.draft[target]);
            this.loadingBox = false;
        }
    }

    /** 当前四栏值（输入框内容即当前栏真值，无需先落盘） */
    private MaidPersonaDraft currentDraft() {
        return new MaidPersonaDraft(this.draft[0], this.draft[1], this.draft[2], this.draft[3]);
    }

    // ===== AI 拆分 =====

    /** 本页是否是该女仆的人设页（整理缓存按它决定通知目标） */
    boolean isPageOf(UUID maidUuid) {
        return this.maid.getUUID().equals(maidUuid);
    }

    /**
     * 发起 AI 拆分：按当前页面状态决定是否需要「替换确认」，确认后登记并发送请求。
     * 已有四栏内容（非严格空）或页面存在未保存修改时先确认替换；失败/取消不影响页面。
     */
    private void startSplit() {
        if (this.pendingRequestId != null || this.awaitingConfirmation) {
            return;
        }
        if (this.splitSourceText.isBlank()) {
            return;
        }
        UUID maidUuid = this.maid.getUUID();
        // READY 候选未保存或丢弃前禁止再次整理；任意在途任务期间同样不允许
        // （服务端每名玩家同时只放行一个任务，这里提前给出可见的 busy 提示）
        if (MaidPersonaSplitClient.hasAnyRunning() || MaidPersonaSplitClient.hasReadyCandidate(maidUuid)) {
            setStatus(Component.translatable("config.maid_self_talk.screen.persona_draft.split.error.busy"),
                    0xFF5555);
            return;
        }
        if (!currentDraft().isEmpty() || isDirty()) {
            this.openingConfirm = true;
            this.minecraft.setScreen(new ConfirmScreen(confirm -> {
                this.minecraft.setScreen(this);
                if (confirm) {
                    registerAndSendSplit();
                }
            },
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.replace.title"),
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.replace.message")));
            return;
        }
        registerAndSendSplit();
    }

    /** 登记并发送拆分请求（请求不携带页面状态；结果按 requestId + 女仆 UUID 回到整理缓存） */
    private void registerAndSendSplit() {
        MaidPersonaSplitClient.startRequest(UUID.randomUUID(), this.maid, this.pageId,
                this.splitSourceText);
        setStatus(Component.translatable(
                "config.maid_self_talk.screen.persona_draft.split.status.running"), 0xFFAA55);
    }

    /**
     * 整理任务终态通知（整理缓存只通知当前打开的该女仆页面）。
     * <p>
     * 成功且本页发起、从未离开（仍为当前页面）时整份填入四栏并选中四栏模式，
     * 撤销「已保存」显示，保持基线不变——玩家检查、修改后手动保存。
     * 其他情况不动作：READY 候选由 {@link #maybeShowSplitLoadConfirm} 的重开确认接管；
     * 失败只在本页提示，页面内容原样保留。
     */
    void onSplitFinished(boolean success, @Nullable MaidPersonaSplitClient.Entry entry,
                         @Nullable String failCode) {
        if (success) {
            if (entry == null || entry.candidate() == null
                    || entry.leftPage() || !entry.pageId().equals(this.pageId)) {
                return;
            }
            loadSplitCandidate(entry);
            this.statusText = Component.empty();
            return;
        }
        setStatus(splitErrorMessage(failCode == null ? "" : failCode), 0xFF5555);
    }

    /**
     * 载入整理候选：整份填入四栏并选中四栏模式，标记来源 requestId（保存时登记关联）。
     * 候选本身保留在缓存里，直到保存成功或被显式丢弃。
     */
    private void loadSplitCandidate(MaidPersonaSplitClient.Entry entry) {
        if (entry.candidate() == null) {
            return;
        }
        this.mode = MaidPersonaMode.STRUCTURED;
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = entry.candidate().get(i);
        }
        this.loadedSplitRequestId = entry.requestId();
        this.savedShown = false;
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
        if (this.box != null) {
            this.loadingBox = true;
            this.box.setValue(this.draft[this.category]);
            this.loadingBox = false;
        }
    }

    /**
     * 有可载入的 READY 候选时弹「载入 AI 整理结果？」确认（页面 tick 中检查，不在 init 里切屏）。
     * 保存等待门禁优先：门禁期间不弹，解除后下一 tick 再弹。每次页面访问只弹一次；
     * ESC 仅暂缓（候选保留），本次访问不再弹。
     * <p>
     * 本页成功路径刚自动填入的候选（loadedSplitRequestId 一致）不再重复询问——
     * 自动填入本身就是确认结果；候选仍保留，未保存离开后重开页面（新实例、
     * loadedSplitRequestId 为空）会照常询问。
     */
    private void maybeShowSplitLoadConfirm() {
        if (this.leftPage || this.splitConfirmShown || this.awaitingConfirmation || this.minecraft == null) {
            return;
        }
        MaidPersonaSplitClient.Entry entry = MaidPersonaSplitClient.entryFor(this.maid.getUUID());
        if (entry == null || entry.status() != MaidPersonaSplitClient.Status.READY) {
            return;
        }
        if (entry.requestId().equals(this.loadedSplitRequestId)) {
            return;
        }
        this.splitConfirmShown = true;
        this.openingConfirm = true;
        this.minecraft.setScreen(new SplitLoadConfirmScreen(entry));
    }

    private static Component splitErrorMessage(String resultCode) {
        String key = switch (resultCode) {
            case MaidPersonaSplitResponsePayload.RESULT_BUSY -> "split.error.busy";
            case MaidPersonaSplitResponsePayload.RESULT_RATE_LIMITED -> "error.rate_limited";
            case MaidPersonaSplitResponsePayload.RESULT_NOT_LOADED -> "error.not_loaded";
            case MaidPersonaSplitResponsePayload.RESULT_NOT_OWNER -> "error.not_owner";
            case MaidPersonaSplitResponsePayload.RESULT_EMPTY_SOURCE -> "split.error.empty_source";
            case MaidPersonaSplitResponsePayload.RESULT_TOO_LONG -> "split.error.too_long";
            case MaidPersonaSplitResponsePayload.RESULT_UNAVAILABLE -> "split.error.unavailable";
            case MaidPersonaSplitResponsePayload.RESULT_NETWORK -> "split.error.network";
            case MaidPersonaSplitResponsePayload.RESULT_TIMEOUT -> "split.error.timeout";
            default -> "split.error.format";
        };
        return Component.translatable("config.maid_self_talk.screen.persona_draft." + key);
    }

    /**
     * 「载入 AI 整理结果？」确认框。
     * 原生 ConfirmScreen 把 ESC 与「否」都映射到 callback(false)，而本流程要求
     * ESC 仅暂缓（候选保留、不再弹），「丢弃结果」才删除候选，因此覆写 ESC 路径。
     */
    private class SplitLoadConfirmScreen extends ConfirmScreen {

        private SplitLoadConfirmScreen(MaidPersonaSplitClient.Entry entry) {
            super(confirm -> {
                MaidPersonaEditScreen.this.minecraft.setScreen(MaidPersonaEditScreen.this);
                if (confirm) {
                    loadSplitCandidate(entry);
                } else {
                    MaidPersonaSplitClient.discardCandidate(entry.maidUuid(), entry.requestId());
                }
            },
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.load.title"),
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.load.message"),
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.load.yes"),
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.load.no"));
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
                // ESC 仅暂缓确认：候选保留，本次访问不再弹（splitConfirmShown 已置位）
                MaidPersonaEditScreen.this.minecraft.setScreen(MaidPersonaEditScreen.this);
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
    }

    // ===== 保存 =====

    /** 当前完整页面配置（模式 + 四栏），即本次保存要提交的内容 */
    private MaidPersonaConfig currentConfig() {
        return new MaidPersonaConfig(this.mode, currentDraft());
    }

    /** 页面内容（含模式）相对已保存基线是否有未保存改动 */
    private boolean isDirty() {
        return !this.baseline.equals(currentConfig());
    }

    /**
     * 客户端宿主被保存回包确认更新时调用：若当前打开的是同一女仆的人设页、
     * 且没有未保存改动，把确认值并入页面内容、模式与基线。
     * <p>
     * 覆盖「返回后、回包到达前重开页面」的时序——新页已把旧宿主值复制进
     * draft/baseline，若不并入，后续保存会把服务端已确认的新值覆盖回旧值。
     * 有未保存改动（用户已在页面上输入新文字或切了模式）时不触碰用户输入。
     */
    void onHostConfirmed(int entityId, MaidPersonaConfig confirmed) {
        if (this.maid.getId() != entityId || this.pendingRequestId != null || isDirty()) {
            return;
        }
        this.mode = confirmed.mode();
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = confirmed.draft().get(i);
        }
        this.baseline = confirmed;
        this.awaitingConfirmation = false;
        this.savedShown = false;
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
        if (this.box != null) {
            this.loadingBox = true;
            this.box.setValue(this.draft[this.category]);
            this.loadingBox = false;
        }
    }

    /**
     * 本次女仆的旧请求已结束（成功或失败）。
     * <p>
     * 成功回包已在 {@link #onHostConfirmed} 并入确认值；这里只负责结束跨页门禁。
     * 失败不改内容/基线、解除只读并显示对应错误（原页按 requestId 匹配时走
     * {@link #applySaveResponse}；新页无匹配 id 但同女仆的也要解除门禁）。
     */
    void onPendingSaveFinished(MaidPersonaSaveResponsePayload payload) {
        this.awaitingConfirmation = false;
        if (this.pendingRequestId != null && this.pendingRequestId.equals(payload.requestId())) {
            applySaveResponse(payload);
        } else if (!payload.success()) {
            // 失败且非本页请求：只提示错误，不改内容/基线（用户输入保留）
            setStatus(errorMessage(payload.resultCode()), 0xFF5555);
        }
    }

    /**
     * 提交保存：冻结本次完整配置快照（模式 + 四栏）并发出请求，随后进入等待态。
     * 超限时不发包（界面已禁用按钮，这里是纵深防御），未变更也允许提交（幂等写入）。
     */
    private void submit(boolean returnAfterSuccess) {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        if (this.box != null) {
            // 输入框里的当前内容可能尚未经过切栏落盘，提交前先固化
            this.draft[this.category] = this.box.getValue();
        }
        MaidPersonaConfig snapshot = currentConfig();
        if (!snapshot.draft().withinLimit()) {
            setStatus(Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"), 0xFF5555);
            return;
        }
        this.pendingRequestId = UUID.randomUUID();
        this.returnAfterSave = returnAfterSuccess;
        // 进入新请求：撤销旧的「已保存」成功标志（通用状态在此之后优先渲染）
        this.savedShown = false;
        setStatus(Component.translatable(
                "config.maid_self_talk.screen.persona_draft.status.saving"), 0xFFAA55);
        // 客户端级登记：页面提前关闭时，回包仍能独立定位女仆并更新客户端宿主（见 SelfTalkPlayerSettingsClient）
        SelfTalkPlayerSettingsClient.registerPersonaSave(this.pendingRequestId, this.maid);
        // 本页载入过整理候选：登记「保存请求 → 整理候选」关联，保存成功后由整理缓存消费候选
        if (this.loadedSplitRequestId != null) {
            MaidPersonaSplitClient.linkSave(this.pendingRequestId, this.maid.getUUID(),
                    this.loadedSplitRequestId);
        }
        PacketDistributor.sendToServer(new MaidPersonaSavePayload(this.pendingRequestId, this.maid.getId(),
                this.maid.getUUID(), snapshot.mode().tag(), snapshot.draft().identity(),
                snapshot.draft().personality(), snapshot.draft().background(), snapshot.draft().hobbies()));
    }

    /**
     * 处理服务端结果：只认正在等待的那一个 requestId。
     * 成功时以<b>服务端确认值</b>作为页面内容与基线并显示「已保存」——客户端宿主的更新已由
     * {@link SelfTalkPlayerSettingsClient#onPersonaSaveResponse} 独立完成，此处不重复写；
     * 失败保留页面内容与 dirty，绝不把失败当成已保存。「保存并返回」只在成功响应到达后才回父页面。
     */
    public void applySaveResponse(MaidPersonaSaveResponsePayload payload) {
        if (this.pendingRequestId == null || !this.pendingRequestId.equals(payload.requestId())) {
            return;
        }
        this.pendingRequestId = null;
        boolean returnAfterSuccess = this.returnAfterSave;
        this.returnAfterSave = false;
        if (payload.success()) {
            MaidPersonaConfig saved = payload.confirmed();
            this.mode = saved.mode();
            for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                this.draft[i] = saved.draft().get(i);
            }
            this.baseline = saved;
            this.savedShown = true;
            // 成功状态只由 savedShown 渲染：清掉通用状态文本，
            // 避免 dirty 后通用分支仍把旧的绿色「已保存」画出来
            this.statusText = Component.empty();
            if (this.box != null) {
                this.loadingBox = true;
                this.box.setValue(this.draft[this.category]);
                this.loadingBox = false;
            }
            if (this.modeButton != null) {
                this.modeButton.setMessage(modeLabel());
            }
            if (returnAfterSuccess) {
                this.onClose();
            }
            return;
        }
        // 失败：撤销旧的「已保存」成功标志，让通用错误提示不被遮住
        this.savedShown = false;
        setStatus(errorMessage(payload.resultCode()), 0xFF5555);
    }

    private static Component errorMessage(String resultCode) {
        String key = switch (resultCode == null ? "" : resultCode) {
            case MaidPersonaSaveResponsePayload.RESULT_RATE_LIMITED -> "error.rate_limited";
            case MaidPersonaSaveResponsePayload.RESULT_NOT_LOADED -> "error.not_loaded";
            case MaidPersonaSaveResponsePayload.RESULT_NOT_OWNER -> "error.not_owner";
            case MaidPersonaSaveResponsePayload.RESULT_TOO_LONG -> "error.too_long";
            case MaidPersonaSaveResponsePayload.RESULT_UNAVAILABLE -> "error.unavailable";
            default -> "error.unknown";
        };
        return Component.translatable("config.maid_self_talk.screen.persona_draft." + key);
    }

    private void setStatus(Component text, int color) {
        this.statusText = text;
        this.statusColor = color;
    }

    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        // 输入框内容先落入页面内容，重建控件后按栏位回填；
        // 页面内容、模式、当前分类、基线、dirty 与等待中的请求都是字段，天然跨 resize 保留
        if (this.box != null) {
            this.draft[this.category] = this.box.getValue();
        }
        super.resize(minecraft, width, height);
    }

    @Override
    public void tick() {
        super.tick();
        maybeShowSplitLoadConfirm();
    }

    @Override
    public void removed() {
        if (this.openingConfirm) {
            // 确认浮层只是临时接管屏幕：不算离开页面（回收标志，浮层回来后仍可继续置位）
            this.openingConfirm = false;
            return;
        }
        // 真正离开：不取消任务，只标记离开（结果改为暂存，重开该女仆页面时确认载入）
        this.leftPage = true;
        MaidPersonaSplitClient.markLeft(this.maid.getUUID(), this.pageId);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean pending = this.pendingRequestId != null;
        // 整理任务在途（含重开页面的同女仆任务）与跨页面待确认门禁一样锁编辑与提交；
        // 离开页面不取消任务，整理中允许查看分类与离开
        boolean splitRunning = MaidPersonaSplitClient.isRunningFor(this.maid.getUUID());
        boolean locked = pending || this.awaitingConfirmation || splitRunning;
        int total = currentDraft().totalLength();
        boolean withinLimit = total <= MaidPersonaDraft.MAX_TOTAL_LENGTH;
        // dirty 由页面内容相对基线比较得出；内容一有改动（含切模式），「已保存」状态立即撤销
        if (isDirty()) {
            this.savedShown = false;
        }
        // 等待结果期间禁止继续编辑与重复提交；返回始终可用（离开页面不等于撤销已发出的请求）
        if (this.modeButton != null) {
            this.modeButton.active = !locked;
        }
        if (this.box != null) {
            this.box.setEditable(!locked);
        }
        if (this.saveButton != null) {
            this.saveButton.active = !locked && withinLimit;
        }
        if (this.saveAndBackButton != null) {
            this.saveAndBackButton.active = !locked && withinLimit;
        }
        // 拆分入口：原文空白、门禁/等待期间、已有 READY 候选未消费时禁用
        if (this.splitButton != null) {
            this.splitButton.active = !locked && !this.splitSourceText.isBlank()
                    && !MaidPersonaSplitClient.hasReadyCandidate(this.maid.getUUID());
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable(
                "config.maid_self_talk.screen.persona_draft.notice"), this.width / 2, 46, 0xFFAA55);

        // 四栏合计字符数：底部左角，超限转红（此时状态行同时给出说明）
        String counter = total + " / " + MaidPersonaDraft.MAX_TOTAL_LENGTH;
        graphics.drawString(this.font, counter, 10, this.height - 44,
                withinLimit ? 0xAAAAAA : 0xFF5555);

        if (!withinLimit) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"),
                    this.width / 2, this.height - 44, 0xFF5555);
        } else if (this.savedShown) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.status.saved"),
                    this.width / 2, this.height - 44, 0x55FF55);
        } else if (splitRunning) {
            // 整理中（含重开页面的同女仆任务）：显示等待提示，允许离开页面
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.split.status.running"),
                    this.width / 2, this.height - 44, 0xFFAA55);
        } else if (!this.statusText.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.statusText, this.width / 2, this.height - 44,
                    this.statusColor);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 返回：不发起新的保存，未提交的编辑（含模式切换）直接丢弃（服务端未收到请求，存档无变化） */
    @Override
    public void onClose() {
        if (this.minecraft == null) {
            return;
        }
        if (this.parent != null) {
            this.minecraft.setScreen(this.parent);
        } else {
            super.onClose();
        }
    }

    /**
     * 等待结果时不可编辑的多行输入框。
     * {@link MultiLineEditBox} 的 {@code active} 不拦截编辑（点击仍会夺焦并接受输入），
     * 且关掉 active 会连滚轮查看一起失能，因此用显式编辑闸门实现「等待期间只读」。
     */
    private static class GuardedPersonaEditBox extends MultiLineEditBox {

        private boolean editable = true;

        private GuardedPersonaEditBox(Font font, int x, int y, int width, int height, Component placeholder) {
            super(font, x, y, width, height, placeholder, Component.empty());
        }

        private void setEditable(boolean editable) {
            this.editable = editable;
        }

        @Override
        public ComponentPath nextFocusPath(FocusNavigationEvent event) {
            return this.editable ? super.nextFocusPath(event) : null;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            return this.editable && super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
            return this.editable && super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            return this.editable && super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public boolean charTyped(char codePoint, int modifiers) {
            return this.editable && super.charTyped(codePoint, modifiers);
        }
    }
}
