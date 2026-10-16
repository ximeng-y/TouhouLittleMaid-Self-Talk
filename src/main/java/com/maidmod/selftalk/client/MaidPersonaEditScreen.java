package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.network.MaidPersonaSaveMessage;
import com.maidmod.selftalk.network.MaidPersonaSaveResponseMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

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

    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;

    private Button modeButton;
    private GuardedPersonaEditBox box;
    private Button saveButton;
    private Button saveAndBackButton;

    public MaidPersonaEditScreen(@Nullable Screen parent, EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.persona_draft.title"));
        this.parent = parent;
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
     * 超限时不发包（界面已禁用按钮，这里是纵深防御），未变更也允许提交（幂等写入）。
     */
    private void submit(boolean returnAfterSuccess) {
        if (this.pendingRequestId != null || this.awaitingConfirmation) {
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
        SelfTalkPackets.CHANNEL.sendToServer(new MaidPersonaSaveMessage(this.pendingRequestId, this.maid.getId(),
                this.maid.getUUID(), snapshot.mode().tag(), snapshot.draft().identity(),
                snapshot.draft().personality(), snapshot.draft().background(), snapshot.draft().hobbies()));
    }

    /**
     * 处理服务端结果：只认正在等待的那一个 requestId。
     * 成功时以<b>服务端确认值</b>作为页面内容与基线并显示「已保存」——客户端宿主的更新已由
     * {@link SelfTalkPlayerSettingsClient#onPersonaSaveResponse} 独立完成，此处不重复写；
     * 失败保留页面内容与 dirty，绝不把失败当成已保存。「保存并返回」只在成功响应到达后才回父页面。
     */
    public void applySaveResponse(MaidPersonaSaveResponseMessage msg) {
        if (this.pendingRequestId == null || !this.pendingRequestId.equals(msg.getRequestId())) {
            return;
        }
        this.pendingRequestId = null;
        boolean returnAfterSuccess = this.returnAfterSave;
        this.returnAfterSave = false;
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
        if (this.box != null) {
            this.box.tick();
        }
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
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean pending = this.pendingRequestId != null;
        // 跨页面待确认门禁期间禁止编辑与提交（旧基线在确认前不可覆盖服务端新值）
        boolean locked = pending || this.awaitingConfirmation;
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

        super.render(graphics, mouseX, mouseY, partialTick);

        graphics.drawCenteredString(this.font, this.getTitle(), this.width / 2, 12, 0xFFFFFF);
        graphics.drawCenteredString(this.font, Component.translatable(
                "config.maid_self_talk.screen.persona_draft.notice"), this.width / 2, 46, 0xFFAA55);

        // 四栏合计字符数：右上角，超限转红（此时底部状态行同时给出说明）
        String counter = total + " / " + MaidPersonaDraft.MAX_TOTAL_LENGTH;
        graphics.drawString(this.font, counter, this.width - 20 - this.font.width(counter), 12,
                withinLimit ? 0xAAAAAA : 0xFF5555);

        if (!withinLimit) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"),
                    this.width / 2, this.height - 44, 0xFF5555);
        } else if (this.savedShown) {
            graphics.drawCenteredString(this.font, Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.status.saved"),
                    this.width / 2, this.height - 44, 0x55FF55);
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
