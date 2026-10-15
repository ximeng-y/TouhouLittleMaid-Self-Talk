package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.network.MaidPersonaSavePayload;
import com.maidmod.selftalk.network.MaidPersonaSaveResponsePayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * 结构化人设草稿编辑页：从原版「✎ 人设编辑」页（SettingEditScreen）进入，返回时回到那个页面。
 * <p>
 * 首版固定四类（身份 / 性格 / 背景 / 爱好），左侧分类按钮 + 右侧一个多行输入框；
 * 打开时从客户端宿主读取已同步草稿，复制成页面草稿与「已保存基线」两份——
 * 基线用于判断是否有未保存改动，页面草稿是唯一编辑源，原版整段人设不作为本页数据源或写入目标。
 * <p>
 * <b>本版本草稿不参与聊天</b>：这里既不读原版人设，也不把四栏写回原版人设或任何请求提示词。
 * <p>
 * 保存是「请求 - 结果确认」两段式：提交时生成新 requestId 并冻结本次快照，服务端整份替换后按该
 * requestId 回结果。等待期间禁止继续编辑与重复提交；只有当前打开、id 匹配的页面处理响应，
 * 关闭后的迟到响应不会落到任何界面上。成功才写入客户端宿主与基线并清 dirty；失败保留草稿与 dirty。
 */
@OnlyIn(Dist.CLIENT)
public class MaidPersonaEditScreen extends Screen {

    /** 左侧分类按钮列宽 */
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

    private final EntityMaid maid;
    /** 返回目标：进入本页的原版人设编辑页实例（不新建原版页面） */
    private final @Nullable Screen parent;

    /** 四栏页面草稿（编辑源；与输入框内容实时同步） */
    private final String[] draft = new String[MaidPersonaDraft.CATEGORY_COUNT];
    /** 已保存基线：最近一次服务端确认成功的四栏值，用于判定 dirty */
    private MaidPersonaDraft baseline;
    /** 是否显示「已保存」状态：仅当四栏相对基线无改动时成立（render 里随 dirty 收敛） */
    private boolean savedShown;
    /** 当前分类（0..3，对应 MaidPersonaDraft 的四个栏位） */
    private int category;

    /** 本次提交的 requestId；非 null 表示正在等待结果（禁止编辑与重复提交） */
    private UUID pendingRequestId;
    /** 本次提交的不可变快照：成功时写入宿主与基线，就是这一份 */
    private MaidPersonaDraft pendingSnapshot;
    /** 「保存并返回」：只在收到成功结果后才回父页面 */
    private boolean returnAfterSave;

    /** 程序化 setValue 期间挂起监听，防把载入的栏内容误当输入 */
    private boolean loadingBox;

    /** 本女仆仍有跨页面待确认保存：该旧基线在确认前不得进入可编辑/可提交状态 */
    private boolean awaitingConfirmation;

    private Component statusText = Component.empty();
    private int statusColor = 0xAAAAAA;

    private GuardedPersonaEditBox box;
    private Button saveButton;
    private Button saveAndBackButton;

    public MaidPersonaEditScreen(@Nullable Screen parent, EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.persona_draft.title"));
        this.parent = parent;
        this.maid = maid;
        // 打开时读客户端已同步草稿：宿主不可用（未挂载/未同步）时按空草稿处理，不阻断页面
        MaidPersonaDraft current = MaidPersonaHost.draftOf(maid);
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = current.get(i);
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
        int top = 46;
        int bottomY = this.height - 28;
        int boxHeight = Math.max(40, bottomY - 24 - top);

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
     * 切换分类：先落盘当前输入框内容，再载入目标栏。
     * 载入期间挂起监听，避免 setValue 触发的回调把目标栏内容写进刚切走的栏位。
     */
    private void switchCategory(int target) {
        if (target == this.category || this.pendingRequestId != null) {
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

    /** 四栏值相对已保存基线是否有未保存改动 */
    private boolean isDirty() {
        return !this.baseline.equals(currentDraft());
    }

    /**
     * 客户端宿主被保存回包确认更新时调用：若当前打开的是同一女仆的草稿页、
     * 且没有未保存改动，把确认值并入页面草稿与基线。
     * <p>
     * 覆盖「返回后、回包到达前重开页面」的时序——新页已把旧宿主值复制进
     * draft/baseline，若不并入，后续保存会把服务端已确认的新值覆盖回旧值。
     * 有未保存改动（用户已在页面上输入新文字）时不触碰用户输入。
     */
    void onHostConfirmed(int entityId, MaidPersonaDraft confirmed) {
        if (this.maid.getId() != entityId || this.pendingRequestId != null || isDirty()) {
            return;
        }
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = confirmed.get(i);
        }
        this.baseline = confirmed;
        this.awaitingConfirmation = false;
        this.savedShown = false;
        if (this.box != null) {
            this.loadingBox = true;
            this.box.setValue(this.draft[this.category]);
            this.loadingBox = false;
        }
    }

    /**
     * 提交保存：冻结本次快照并发出请求，随后进入等待态。
     * 超限时不发包（界面已禁用按钮，这里是纵深防御），未变更也允许提交（幂等写入）。
     */
    private void submit(boolean returnAfterSuccess) {
        if (this.pendingRequestId != null || this.awaitingConfirmation) {
            return;
        }
        MaidPersonaDraft snapshot = currentDraft();
        if (!snapshot.withinLimit()) {
            setStatus(Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"), 0xFF5555);
            return;
        }
        this.pendingRequestId = UUID.randomUUID();
        this.pendingSnapshot = snapshot;
        this.returnAfterSave = returnAfterSuccess;
        // 进入新请求：撤销旧的「已保存」成功标志（通用状态在此之后优先渲染）
        this.savedShown = false;
        setStatus(Component.translatable(
                "config.maid_self_talk.screen.persona_draft.status.saving"), 0xFFAA55);
        // 客户端级登记：页面提前关闭时，回包仍能独立更新客户端宿主（见 SelfTalkPlayerSettingsClient）
        SelfTalkPlayerSettingsClient.registerPersonaSave(this.pendingRequestId, this.maid, snapshot);
        PacketDistributor.sendToServer(new MaidPersonaSavePayload(this.pendingRequestId, this.maid.getId(),
                snapshot.identity(), snapshot.personality(), snapshot.background(), snapshot.hobbies()));
    }

    /**
     * 处理服务端结果：只认正在等待的那一个 requestId。
     * 成功时把提交时的快照作为新基线并显示「已保存」——客户端宿主的更新已由
     * {@link SelfTalkPlayerSettingsClient#onPersonaSaveResponse} 独立完成，此处不重复写；
     * 失败保留草稿与 dirty。「保存并返回」只在成功响应到达后才回父页面。
     */
    public void applySaveResponse(MaidPersonaSaveResponsePayload payload) {
        if (this.pendingRequestId == null || !this.pendingRequestId.equals(payload.requestId())) {
            return;
        }
        this.pendingRequestId = null;
        boolean returnAfterSuccess = this.returnAfterSave;
        this.returnAfterSave = false;
        if (payload.success()) {
            MaidPersonaDraft saved = this.pendingSnapshot == null ? currentDraft() : this.pendingSnapshot;
            this.pendingSnapshot = null;
            this.baseline = saved;
            this.savedShown = true;
            // 成功状态只由 savedShown 渲染：清掉通用状态文本，
            // 避免 dirty 后通用分支仍把旧的绿色「已保存」画出来
            this.statusText = Component.empty();
            if (returnAfterSuccess) {
                this.onClose();
            }
            return;
        }
        this.pendingSnapshot = null;
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
        // 输入框内容先落入页面草稿，重建控件后按栏位回填；
        // 页面草稿、当前分类、基线、dirty 与等待中的请求都是字段，天然跨 resize 保留
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
        MaidPersonaDraft current = currentDraft();
        int total = current.totalLength();
        boolean withinLimit = total <= MaidPersonaDraft.MAX_TOTAL_LENGTH;
        // dirty 由四栏值相对基线比较得出；内容一有改动，「已保存」状态立即撤销
        if (isDirty()) {
            this.savedShown = false;
        }
        // 等待结果期间禁止继续编辑与重复提交；返回始终可用（离开页面不等于撤销已发出的请求）
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
                "config.maid_self_talk.screen.persona_draft.notice"), this.width / 2, 26, 0xFFAA55);

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
        } else if (!this.statusText.getString().isEmpty()) {
            graphics.drawCenteredString(this.font, this.statusText, this.width / 2, this.height - 44,
                    this.statusColor);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /** 返回：不发起新的保存，未提交的编辑直接丢弃（服务端未收到请求，存档无变化） */
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
