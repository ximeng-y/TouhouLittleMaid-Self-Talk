package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.network.MaidPersonaSaveMessage;
import com.maidmod.selftalk.network.MaidPersonaSaveResponseMessage;
import com.maidmod.selftalk.network.MaidPersonaSplitResponseMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 统一人设编辑页：直接继承 TLM 原版「✎ 人设编辑」页（SettingEditScreen），
 * 由 AIChatScreen 的 ✎ 入口通过 mixin 重定向构造为本类。
 * <p>
 * 页面上同时保留原版的整段人设输入框与保存 / 保存并退出 / 返回按钮：
 * <ul>
 *   <li>整段人设模式（默认）：显示原版输入框，行为与原版一致；</li>
 *   <li>结构化人设模式：隐藏原版输入框，同一区域改为身份 / 性格 / 背景 / 爱好四行
 *       「栏目标签 + 可滚动文本框」，四栏同时可见。</li>
 * </ul>
 * 右侧按钮列调整为：布娃娃预览在顶部，原版保存按钮位置不变，其下依次为
 * 模式切换按钮、「导出设定」（从原版顶部移来）、AI 拆分按钮。
 * <p>
 * 模式与四栏内容独立于原版整段人设存储：切模式、改文字都不触碰原版输入框，
 * 两套内容互不复制、覆盖或删除。点击模式按钮立即把「模式 + 四栏快照」整份提交保存；
 * 原版保存按钮在页面有未保存改动时也会附带提交同一份快照。
 * <p>
 * 保存是「请求 - 结果确认」两段式：提交时生成新 requestId 并冻结本次快照，服务端整份替换后按该
 * requestId 回结果，回包里带服务端确认的完整配置。等待期间禁止继续编辑与重复提交；只有当前打开、
 * id 匹配的页面处理响应，关闭后的迟到响应不会落到任何界面上。成功以<b>服务端确认值</b>写回客户端宿主
 * 与基线并清 dirty；失败保留页面内容与 dirty，且不得被当成已保存。
 * <p>
 * <b>AI 拆分</b>：点击时读取原版输入框当前文本（含未保存修改），发起一次静默整理请求。
 * 成功且页面一直未离开、仍是当前页面时整份填入四栏并切到结构化模式显示（保持基线不变，
 * 仍需手动保存）；其他情况暂存为候选，重开该女仆页面时弹确认载入。
 * 整理期间禁止编辑四栏、切模式、保存与重复发起（允许查看与离开页面）；关闭界面不取消任务，
 * 完成后服务端向发起玩家发私信。候选仅保留在本次连接内，保存成功或显式丢弃才消费。
 */
@OnlyIn(Dist.CLIENT)
public class MaidPersonaEditScreen extends SettingEditScreen {

    /** 右侧按钮列相对原版 posX 的偏移（原版导出 / 保存按钮同列） */
    private static final int COLUMN_OFFSET = 265;
    /** 右侧按钮列宽（与原版按钮一致） */
    private static final int COLUMN_WIDTH = 128;
    private static final int BUTTON_HEIGHT = 20;
    /** 模式切换按钮 y（原版返回按钮之下） */
    private static final int MODE_BUTTON_Y = 150;
    /** 「导出设定」移到的位置（模式按钮之下） */
    private static final int EXPORT_BUTTON_Y = 175;
    /** AI 拆分按钮 y（两个按钮之下） */
    private static final int SPLIT_BUTTON_Y = 200;
    /** 四栏内容区顶部（与原版输入框 y 一致） */
    private static final int BOX_TOP = 70;
    /** 内容区底部留白（原版输入框高度为 height - 100，即底边在 height - 30） */
    private static final int CONTENT_BOTTOM_MARGIN = 30;
    /** 每栏标签行高 */
    private static final int LABEL_HEIGHT = 12;
    /** 四栏文本框宽度（与原版输入框一致） */
    private static final int BOX_WIDTH = 256;

    private final EntityMaid maid;

    /** 原版整段人设输入框：结构化模式下从界面移除，但仍持有引用（保存 / 导出 / AI 拆分用） */
    @Nullable
    private MultiLineEditBox customSetting;
    /** 原版「导出设定」按钮：init 后重定位到按钮列下方 */
    @Nullable
    private FlatColorButton exportButton;

    /** 页面模式（与四栏内容一起构成待提交配置） */
    private MaidPersonaMode mode;
    /** 四栏页面内容（编辑源；与各输入框内容实时同步） */
    private final String[] draft = new String[MaidPersonaDraft.CATEGORY_COUNT];
    /** 已保存基线：最近一次「服务端确认成功」的完整配置，用于判定 dirty */
    private MaidPersonaConfig baseline;
    /** 是否显示「已保存」状态：仅当页面内容相对基线无改动时成立（render 里随 dirty 收敛） */
    private boolean savedShown;

    /** 本次提交的 requestId；非 null 表示正在等待结果（禁止编辑与重复提交） */
    private UUID pendingRequestId;

    /** 程序化 setValue 期间挂起监听，防把载入的栏内容误当输入 */
    private boolean loadingBox;

    /** 本女仆仍有跨页面待确认保存：该旧基线在确认前不得进入可编辑/可提交状态 */
    private boolean awaitingConfirmation;

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
    private Button splitButton;
    private final GuardedPersonaEditBox[] boxes = new GuardedPersonaEditBox[MaidPersonaDraft.CATEGORY_COUNT];

    public MaidPersonaEditScreen(@Nullable Screen parent, EntityMaid maid) {
        super(parent, maid);
        this.maid = maid;
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
        // 先让原版建立称呼框、整段人设框、导出 / 保存 / 保存并退出 / 返回按钮
        super.init();
        locateVanillaWidgets();

        int posX = this.width / 2 - 195;
        int columnX = posX + COLUMN_OFFSET;
        int contentHeight = Math.max(4 * (LABEL_HEIGHT + 20),
                this.height - CONTENT_BOTTOM_MARGIN - BOX_TOP);
        int groupHeight = contentHeight / MaidPersonaDraft.CATEGORY_COUNT;

        this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), b -> toggleMode())
                .bounds(columnX, MODE_BUTTON_Y, COLUMN_WIDTH, BUTTON_HEIGHT)
                .build());

        this.splitButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.persona_draft.button.split"),
                        b -> startSplit())
                .bounds(columnX, SPLIT_BUTTON_Y, COLUMN_WIDTH, BUTTON_HEIGHT)
                .build());
        this.splitButton.setTooltip(Tooltip.create(
                Component.translatable("config.maid_self_talk.screen.persona_draft.button.split.tooltip")));

        if (this.exportButton != null) {
            this.exportButton.setX(columnX);
            this.exportButton.setY(EXPORT_BUTTON_Y);
        }

        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            int index = i;
            int boxY = BOX_TOP + i * groupHeight + LABEL_HEIGHT;
            int boxHeight = Math.max(20, groupHeight - LABEL_HEIGHT - 2);
            GuardedPersonaEditBox box = new GuardedPersonaEditBox(this.font, posX, boxY,
                    BOX_WIDTH, boxHeight,
                    Component.translatable("config.maid_self_talk.screen.persona_draft.placeholder"));
            box.setValueListener(value -> {
                if (!this.loadingBox) {
                    this.draft[index] = value;
                }
            });
            box.setValue(this.draft[index]);
            this.boxes[i] = box;
        }

        applyModeVisibility();
    }

    /** 从原版 init 建立的控件中找回整段人设框与导出按钮 */
    private void locateVanillaWidgets() {
        this.customSetting = null;
        this.exportButton = null;
        String exportText = Component.translatable(
                "gui.touhou_little_maid.button.maid_ai_chat_config.edit_custom_setting.export").getString();
        for (GuiEventListener child : this.children()) {
            if (this.customSetting == null && child instanceof MultiLineEditBox box) {
                this.customSetting = box;
            }
            if (this.exportButton == null && child instanceof FlatColorButton button
                    && exportText.equals(button.getMessage().getString())) {
                this.exportButton = button;
            }
        }
    }

    /** 模式开关标签：显示当前模式（点击切到另一个） */
    private Component modeLabel() {
        return modeName(this.mode);
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

    /** 按当前模式切换原版输入框与四栏文本框的显示（同一区域互斥） */
    private void applyModeVisibility() {
        boolean structured = this.mode.isStructured();
        if (this.customSetting != null) {
            toggleChild(this.customSetting, !structured);
        }
        for (GuardedPersonaEditBox box : this.boxes) {
            if (box != null) {
                toggleChild(box, structured);
            }
        }
        if (structured) {
            this.setFocused(null);
        }
    }

    /** 把控件加入 / 移出界面（对象保留，内容与状态不丢） */
    private void toggleChild(AbstractWidget widget, boolean shown) {
        boolean present = this.children().contains(widget);
        if (shown && !present) {
            this.addRenderableWidget(widget);
        } else if (!shown && present) {
            this.removeWidget(widget);
            if (this.getFocused() == widget) {
                this.setFocused(null);
            }
        }
    }

    /** 把四个输入框刷新为页面内容 */
    private void refreshBoxes() {
        for (int i = 0; i < this.boxes.length; i++) {
            if (this.boxes[i] != null) {
                this.loadingBox = true;
                this.boxes[i].setValue(this.draft[i]);
                this.loadingBox = false;
            }
        }
    }

    /** 整段人设原文（AI 拆分的唯一资料来源）：原版输入框当前内容，结构化模式下同样可读 */
    private String splitSourceText() {
        return this.customSetting == null ? "" : this.customSetting.getValue();
    }

    /**
     * 切换模式：只改页面显示与待提交配置，不触碰两套内容本身。
     * 「只要切换就保存」——切换后立即整份提交（模式 + 四栏快照）；
     * 提交失败时显示保持切换后的模式，基线不更新，下次点原版保存会再次提交。
     */
    private void toggleMode() {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        this.mode = this.mode.isStructured() ? MaidPersonaMode.ORIGINAL : MaidPersonaMode.STRUCTURED;
        applyModeVisibility();
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
        submitPersona();
    }

    /** 当前四栏值（各输入框内容经监听实时同步，无需先落盘） */
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
        String source = splitSourceText();
        if (source.isBlank()) {
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
                    registerAndSendSplit(source);
                }
            },
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.replace.title"),
                    Component.translatable(
                            "config.maid_self_talk.screen.persona_draft.split.confirm.replace.message")));
            return;
        }
        registerAndSendSplit(source);
    }

    /** 登记并发送拆分请求（请求不携带页面状态；结果按 requestId + 女仆 UUID 回到整理缓存） */
    private void registerAndSendSplit(String source) {
        MaidPersonaSplitClient.startRequest(UUID.randomUUID(), this.maid, this.pageId, source);
        setStatus(Component.translatable(
                "config.maid_self_talk.screen.persona_draft.split.status.running"), 0xFFAA55);
    }

    /**
     * 整理任务终态通知（整理缓存只通知当前打开的该女仆页面）。
     * <p>
     * 成功且本页发起、从未离开（仍为当前页面）时整份填入四栏并切到结构化模式显示，
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
     * 载入整理候选：整份填入四栏并切到结构化模式显示，标记来源 requestId（保存时登记关联）。
     * 这里只切显示、不立即保存——AI 结果仍需玩家确认后手动保存。
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
        applyModeVisibility();
        refreshBoxes();
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
            case MaidPersonaSplitResponseMessage.RESULT_BUSY -> "split.error.busy";
            case MaidPersonaSplitResponseMessage.RESULT_RATE_LIMITED -> "error.rate_limited";
            case MaidPersonaSplitResponseMessage.RESULT_NOT_LOADED -> "error.not_loaded";
            case MaidPersonaSplitResponseMessage.RESULT_NOT_OWNER -> "error.not_owner";
            case MaidPersonaSplitResponseMessage.RESULT_EMPTY_SOURCE -> "split.error.empty_source";
            case MaidPersonaSplitResponseMessage.RESULT_TOO_LONG -> "split.error.too_long";
            case MaidPersonaSplitResponseMessage.RESULT_UNAVAILABLE -> "split.error.unavailable";
            case MaidPersonaSplitResponseMessage.RESULT_NETWORK -> "split.error.network";
            case MaidPersonaSplitResponseMessage.RESULT_TIMEOUT -> "split.error.timeout";
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
     * 原版保存按钮（保存 / 保存并退出）的附带保存：内容有改动时提交同一份快照。
     * 由 SettingEditScreenMixin 注入原版 {@code saveConfig()} 末尾调用。
     */
    public void onVanillaSave() {
        submitPersona();
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
        applyModeVisibility();
        refreshBoxes();
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
    }

    /**
     * 本次女仆的旧请求已结束（成功或失败）。
     * <p>
     * 成功回包已在 {@link #onHostConfirmed} 并入确认值；这里只负责结束跨页门禁。
     * 失败不改内容/基线、解除只读并显示对应错误（本页按 requestId 匹配时走
     * {@link #applySaveResponse}；新页无匹配 id 但同女仆的也要解除门禁）。
     */
    void onPendingSaveFinished(MaidPersonaSaveResponseMessage msg) {
        this.awaitingConfirmation = false;
        if (this.pendingRequestId != null && this.pendingRequestId.equals(msg.getRequestId())) {
            applySaveResponse(msg);
        } else if (!msg.isSuccess()) {
            // 失败且非本页请求：只提示错误，不改内容/基线（用户输入保留）
            setStatus(errorMessage(msg.getResultCode()), 0xFF5555);
        }
    }

    /**
     * 提交保存：冻结本次完整配置快照（模式 + 四栏）并发出请求，随后进入等待态。
     * 未变更时跳过（模式切换必然产生改动）；超限时不发包并提示。
     */
    private void submitPersona() {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        MaidPersonaConfig snapshot = currentConfig();
        if (snapshot.equals(this.baseline)) {
            return;
        }
        if (!snapshot.draft().withinLimit()) {
            setStatus(Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"), 0xFF5555);
            return;
        }
        this.pendingRequestId = UUID.randomUUID();
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
        SelfTalkPackets.CHANNEL.sendToServer(new MaidPersonaSaveMessage(this.pendingRequestId,
                this.maid.getId(), this.maid.getUUID(), snapshot.mode().tag(),
                snapshot.draft().identity(), snapshot.draft().personality(),
                snapshot.draft().background(), snapshot.draft().hobbies()));
    }

    /**
     * 处理服务端结果：只认正在等待的那一个 requestId。
     * 成功时以<b>服务端确认值</b>作为页面内容与基线并显示「已保存」——客户端宿主的更新已由
     * {@link SelfTalkPlayerSettingsClient#onPersonaSaveResponse} 独立完成，此处不重复写；
     * 失败保留页面内容与 dirty，绝不把失败当成已保存。
     */
    public void applySaveResponse(MaidPersonaSaveResponseMessage msg) {
        if (this.pendingRequestId == null || !this.pendingRequestId.equals(msg.getRequestId())) {
            return;
        }
        this.pendingRequestId = null;
        if (msg.isSuccess()) {
            MaidPersonaConfig saved = msg.getConfirmed();
            this.mode = saved.mode();
            for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                this.draft[i] = saved.draft().get(i);
            }
            this.baseline = saved;
            this.savedShown = true;
            // 成功状态只由 savedShown 渲染：清掉通用状态文本，
            // 避免 dirty 后通用分支仍把旧的绿色「已保存」画出来
            this.statusText = Component.empty();
            applyModeVisibility();
            refreshBoxes();
            if (this.modeButton != null) {
                this.modeButton.setMessage(modeLabel());
            }
            return;
        }
        // 失败：撤销旧的「已保存」成功标志，让通用错误提示不被遮住
        this.savedShown = false;
        setStatus(errorMessage(msg.getResultCode()), 0xFF5555);
    }

    private static Component errorMessage(String resultCode) {
        String key = switch (resultCode == null ? "" : resultCode) {
            case MaidPersonaSaveResponseMessage.RESULT_RATE_LIMITED -> "error.rate_limited";
            case MaidPersonaSaveResponseMessage.RESULT_NOT_LOADED -> "error.not_loaded";
            case MaidPersonaSaveResponseMessage.RESULT_NOT_OWNER -> "error.not_owner";
            case MaidPersonaSaveResponseMessage.RESULT_TOO_LONG -> "error.too_long";
            case MaidPersonaSaveResponseMessage.RESULT_UNAVAILABLE -> "error.unavailable";
            default -> "error.unknown";
        };
        return Component.translatable("config.maid_self_talk.screen.persona_draft." + key);
    }

    private void setStatus(Component text, int color) {
        this.statusText = text;
        this.statusColor = color;
    }

    /**
     * 1.20.1 的 Screen.tick() 为空实现、不传播子控件，需手动驱动输入框 tick（推进光标闪烁）；
     * 1.21.1 的 MultiLineEditBox 无 tick 方法，neo 线无此调用。
     */
    @Override
    public void tick() {
        super.tick();
        for (GuardedPersonaEditBox box : this.boxes) {
            if (box != null) {
                box.tick();
            }
        }
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
        // 离开页面不取消任务，整理中允许查看与离开
        boolean splitRunning = MaidPersonaSplitClient.isRunningFor(this.maid.getUUID());
        boolean locked = pending || this.awaitingConfirmation || splitRunning;
        int total = currentDraft().totalLength();
        boolean withinLimit = total <= MaidPersonaDraft.MAX_TOTAL_LENGTH;
        // dirty 由页面内容相对基线比较得出；内容一有改动（含切模式），「已保存」状态立即撤销
        if (isDirty()) {
            this.savedShown = false;
        }
        if (this.modeButton != null) {
            this.modeButton.active = !locked;
        }
        // 拆分入口：原文空白、门禁/等待期间、已有 READY 候选未消费时禁用
        if (this.splitButton != null) {
            this.splitButton.active = !locked && !splitSourceText().isBlank()
                    && !MaidPersonaSplitClient.hasReadyCandidate(this.maid.getUUID());
        }
        for (GuardedPersonaEditBox box : this.boxes) {
            if (box != null) {
                box.setEditable(!locked);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        int columnX = this.width / 2 - 195 + COLUMN_OFFSET;
        if (this.mode.isStructured()) {
            // 四栏标签：画在各自文本框上方
            for (int i = 0; i < this.boxes.length; i++) {
                if (this.boxes[i] != null) {
                    graphics.drawString(this.font, categoryLabel(i),
                            this.boxes[i].getX() + 2, this.boxes[i].getY() - 10, 0xFFFFFF);
                }
            }
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.notice"), this.width / 2,
                    this.height - 24, 0xFFAA55);
        }

        // 四栏合计字符数：按钮列底部，超限转红（此时状态行同时给出说明）
        String counter = total + " / " + MaidPersonaDraft.MAX_TOTAL_LENGTH;
        graphics.drawString(this.font, counter, columnX, SPLIT_BUTTON_Y + 28,
                withinLimit ? 0xAAAAAA : 0xFF5555);

        if (!withinLimit) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"),
                    this.width / 2, this.height - 12, 0xFF5555);
        } else if (this.savedShown) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.status.saved"),
                    this.width / 2, this.height - 12, 0x55FF55);
        } else if (splitRunning) {
            // 整理中（含重开页面的同女仆任务）：显示等待提示，允许离开页面
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.split.status.running"),
                    this.width / 2, this.height - 12, 0xFFAA55);
        } else if (!this.statusText.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.statusText, this.width / 2, this.height - 12,
                    this.statusColor);
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
