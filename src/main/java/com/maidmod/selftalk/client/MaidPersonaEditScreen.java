package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.client.gui.entity.maid.ai.SettingEditScreen;
import com.github.tartaricacid.touhoulittlemaid.client.gui.widget.button.FlatColorButton;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.network.NetworkHandler;
import com.github.tartaricacid.touhoulittlemaid.network.message.ai.SaveMaidAIDataMessage;
import com.maidmod.selftalk.MaidPersonaConfig;
import com.maidmod.selftalk.MaidPersonaDraft;
import com.maidmod.selftalk.MaidPersonaHost;
import com.maidmod.selftalk.MaidPersonaMode;
import com.maidmod.selftalk.MaidSelfTalkMod;
import com.maidmod.selftalk.network.MaidPersonaSaveMessage;
import com.maidmod.selftalk.network.MaidPersonaSaveResponseMessage;
import com.maidmod.selftalk.network.MaidPersonaSplitResponseMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.ChatFormatting;
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
import java.util.List;
import java.util.UUID;

/**
 * 统一人设编辑页：直接继承 TLM 原版「✎ 人设编辑」页（SettingEditScreen），
 * 由 AIChatScreen 的 ✎ 入口通过 mixin 重定向构造为本类。
 * <p>
 * 页面上同时保留原版的整段人设输入框与保存 / 保存并退出 / 返回按钮：
 * <ul>
 *   <li>整段人设模式（默认）：显示原版输入框；</li>
 *   <li>结构化人设模式：隐藏原版输入框（挪出屏幕，使其跟随输入框位置绘制的标签 /
 *       保存提示一并消失），同一区域改为身份 / 性格 / 背景 / 爱好四行
 *       「栏目标签 + 可滚动文本框」，四栏同时可见。</li>
 * </ul>
 * 右侧按钮列调整为：布娃娃预览在顶部，原版位置上的「保存」/「保存并退出」按钮由本页接管
 * （显式保存：确认模式 + 原生保存原版文字），其下依次为
 * 「导出设定」（从原版顶部移来）、模式切换按钮、AI 拆分按钮。
 * <p>
 * 模式与四栏内容独立于原版整段人设存储：切模式、改文字都不触碰原版输入框，
 * 两套内容互不复制、覆盖或删除。页面模式只代表编辑视图：切换按钮不发送保存请求；
 * 只有「保存」/「保存并退出」才确认模式；真正离开页面时自动保存有变化的原版文字与四栏内容，
 * 但不改变实际模式。
 * <p>
 * 保存是「请求 - 结果确认」两段式：提交时生成新 requestId 并冻结本次快照，服务端按请求意图处理后
 * 按该 requestId 回结果，回包里带服务端确认的完整配置。显式保存（confirmMode=true）整份采用请求中的
 * 模式与四栏；离页内容保存（confirmMode=false）由服务端保留当前实际模式、只并入新四栏。等待期间禁止
 * 继续编辑与重复提交；只有当前打开、id 匹配的页面处理响应，关闭后的迟到响应不会落到任何界面上。
 * 成功以<b>服务端确认值</b>写回客户端宿主与基线；失败保留页面内容与 dirty，且不得被当成已保存。
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
    /** 「导出设定」移到的位置（原版返回按钮之下） */
    private static final int EXPORT_BUTTON_Y = 150;
    /** 模式切换按钮 y（导出设定之下） */
    private static final int MODE_BUTTON_Y = 175;
    /** AI 拆分按钮 y（两个按钮之下） */
    private static final int SPLIT_BUTTON_Y = 200;
    /** 四栏内容区顶部（与原版输入框 y 一致） */
    private static final int BOX_TOP = 70;
    /** 内容区底部留白（原版输入框高度为 height - 100，即底边在 height - 30；底部两行提示需再让出 10px） */
    private static final int CONTENT_BOTTOM_MARGIN = 40;
    /** 每栏标签行高 */
    private static final int LABEL_HEIGHT = 12;
    /** 四栏文本框宽度（与原版输入框一致） */
    private static final int BOX_WIDTH = 256;
    /** 结构化模式下原版输入框的藏匿 y：原版 render 按其位置直接绘制标签与保存提示，挪出屏幕后一并隐藏 */
    private static final int VANILLA_BOX_HIDDEN_Y = -10000;
    /**
     * 布娃娃预览 y 相对原版输入框 y 的偏移：原版 drawMaid 渲染框为 90×100（posY-45..posY+55），
     * 负值把预览顶到按钮列顶部空位；模型实际占框中间，最终观感需实机微调
     */
    private static final int MAID_PREVIEW_Y_OFFSET = -36;

    private final EntityMaid maid;

    /** 原版整段人设输入框：结构化模式下从界面移除，但仍持有引用（保存 / 导出 / AI 拆分用） */
    @Nullable
    private MultiLineEditBox customSetting;
    /** 原版「导出设定」按钮：init 后重定位到按钮列下方 */
    @Nullable
    private FlatColorButton exportButton;
    /** 本页接管的「保存」按钮（替换原版回调：显式保存、不退出，位置/尺寸/文字与原版一致） */
    @Nullable
    private FlatColorButton saveButton;
    /** 本页接管的「保存并退出」按钮（显式保存，成功回包后退出） */
    @Nullable
    private FlatColorButton saveQuitButton;
    /** 原版输入框在原版布局中的位置与宽度（init 定位成功后取实际值，失败保持常量默认值） */
    private int vanillaBoxX;
    private int vanillaBoxY = BOX_TOP;
    private int vanillaBoxWidth = BOX_WIDTH;

    /** 编辑视图模式：只决定页面显示/编辑哪一套内容，不代表女仆实际使用模式（实际模式见 baseline，仅显式保存可确认） */
    private MaidPersonaMode editingMode;
    /** 四栏页面内容（编辑源；与各输入框内容实时同步） */
    private final String[] draft = new String[MaidPersonaDraft.CATEGORY_COUNT];
    /** 已保存基线：最近一次「服务端确认成功」的完整配置，用于判定 dirty */
    private MaidPersonaConfig baseline;

    /** 本次提交的 requestId；非 null 表示正在等待结果（禁止编辑与重复提交） */
    private UUID pendingRequestId;
    /** 在途请求的保存意图：true = 显式保存（确认请求中的模式），false = 仅内容保存（模式不写入） */
    private boolean pendingConfirmMode;
    /** 在途请求成功后是否退出页面（「保存并退出」登记；失败或已离开原页面则不退出） */
    private boolean pendingExitAfter;

    /** 最近一次原生保存已发出的原版文字与主人称呼：离页只在有变化时补发，避免显式保存后重复发送 */
    private String sentCustomSetting;
    private String sentOwnerName;

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
    /** 矮窗口紧凑布局：四行放不下时改 2×2 网格并收起底部两行提示 */
    private boolean compactLayout;

    public MaidPersonaEditScreen(@Nullable Screen parent, EntityMaid maid) {
        super(parent, maid);
        this.maid = maid;
        // 打开时读客户端已同步配置：宿主不可用（未挂载/未同步）时按默认配置处理，不阻断页面
        MaidPersonaConfig current = MaidPersonaHost.configOf(maid);
        this.editingMode = current.mode();
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = current.draft().get(i);
        }
        this.baseline = current;
        // 原版文字的「最近发送值」以打开页面时的内容为基线：未改动就不在离页时补发原生保存
        // （记录只在显式/离页发送时更新，不在 init/resize 重置）
        this.sentCustomSetting = this.maid.getAiChatManager().customSetting;
        this.sentOwnerName = this.maid.getAiChatManager().ownerName;
        // 跨页面待确认门禁：同女仆仍有未确认保存时（旧请求回包未到），
        // 禁止编辑与提交，等待确认后由 onHostConfirmed 并入并解锁，避免旧基线覆盖新值
        this.awaitingConfirmation = SelfTalkPlayerSettingsClient.hasPendingSaveFor(this.maid.getId());
    }

    @Override
    protected void init() {
        // 先让原版建立称呼框、整段人设框、导出 / 保存 / 保存并退出 / 返回按钮
        super.init();
        // 原版布局默认值：原版输入框定位失败时四栏按此退化布局排布
        int posX = this.width / 2 - 195;
        this.vanillaBoxX = posX;
        this.vanillaBoxY = BOX_TOP;
        this.vanillaBoxWidth = BOX_WIDTH;
        locateVanillaWidgets();

        int columnX = this.vanillaBoxX + COLUMN_OFFSET;
        // 四栏完整显示所需高度：每栏标签 12 + 输入框 20 + 框底计数带 13
        int fullContentHeight = 4 * (LABEL_HEIGHT + 33);
        int availableHeight = Math.max(4 * (LABEL_HEIGHT + 20),
                this.height - CONTENT_BOTTOM_MARGIN - this.vanillaBoxY);
        // 矮窗口（高 GUI scale）四行放不下：改 2×2 网格并收起底部提示，
        // 避免第四栏压到提示行、240 高度时计数带越出屏幕
        this.compactLayout = availableHeight < fullContentHeight;

        this.modeButton = this.addRenderableWidget(Button.builder(modeLabel(), b -> toggleMode())
                .bounds(columnX, MODE_BUTTON_Y, COLUMN_WIDTH, BUTTON_HEIGHT)
                .build());
        // 模式是唯一的人设注入策略决策点：说明两种模式的分工，警示段单独用金色（与历史模式 tooltip 同口径）
        this.modeButton.setTooltip(Tooltip.create(
                Component.translatable("config.maid_self_talk.screen.persona_draft.mode.tooltip")
                        .append("\n\n")
                        .append(Component.translatable(
                                        "config.maid_self_talk.screen.persona_draft.mode.tooltip.warning")
                                .withStyle(ChatFormatting.GOLD))));

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

        if (this.compactLayout) {
            // 2×2 网格：每行都放得下标签与计数带，宽度减半换取纵向空间
            int gap = 8;
            int columnWidth = Math.max(40, (this.vanillaBoxWidth - gap) / 2);
            int rowHeight = Math.max(LABEL_HEIGHT + 33, availableHeight / 2);
            for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                int boxX = this.vanillaBoxX + (i % 2) * (columnWidth + gap);
                int boxY = this.vanillaBoxY + (i / 2) * rowHeight + LABEL_HEIGHT;
                int boxHeight = Math.max(20, rowHeight - LABEL_HEIGHT - 15);
                addPersonaBox(i, boxX, boxY, columnWidth, boxHeight);
            }
        } else {
            int groupHeight = availableHeight / MaidPersonaDraft.CATEGORY_COUNT;
            for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                int boxY = this.vanillaBoxY + i * groupHeight + LABEL_HEIGHT;
                // 13px 给框底计数带留位，避免压到下一栏标签
                int boxHeight = Math.max(20, groupHeight - LABEL_HEIGHT - 15);
                addPersonaBox(i, this.vanillaBoxX, boxY, this.vanillaBoxWidth, boxHeight);
            }
        }

        applyModeVisibility();
    }

    /** 创建单栏输入框并接入草稿数组（四行与 2×2 布局共用） */
    private void addPersonaBox(int index, int x, int y, int width, int height) {
        GuardedPersonaEditBox box = new GuardedPersonaEditBox(this.font, x, y, width, height,
                Component.translatable("config.maid_self_talk.screen.persona_draft.placeholder"));
        box.setCharacterLimit(MaidPersonaDraft.MAX_FIELD_LENGTH);
        box.setValueListener(value -> {
            if (!this.loadingBox) {
                this.draft[index] = value;
            }
        });
        box.setValue(this.draft[index]);
        this.boxes[index] = box;
    }

    /** 从原版 init 建立的控件中找回整段人设框与导出按钮，并记录输入框的原版布局位置；同时接管两个原版保存按钮 */
    private void locateVanillaWidgets() {
        this.customSetting = null;
        this.exportButton = null;
        String exportText = Component.translatable(
                "gui.touhou_little_maid.button.maid_ai_chat_config.edit_custom_setting.export").getString();
        String saveText = Component.translatable("selectWorld.edit.save").getString();
        String saveQuitText = Component.translatable(
                "gui.touhou_little_maid.button.maid_ai_chat_config.edit_custom_setting.save_and_quit").getString();
        FlatColorButton vanillaSave = null;
        FlatColorButton vanillaSaveQuit = null;
        for (GuiEventListener child : List.copyOf(this.children())) {
            if (this.customSetting == null && child instanceof MultiLineEditBox box) {
                this.customSetting = box;
            }
            if (child instanceof FlatColorButton button) {
                String text = button.getMessage().getString();
                if (exportText.equals(text)) {
                    this.exportButton = button;
                } else if (saveText.equals(text)) {
                    vanillaSave = button;
                } else if (saveQuitText.equals(text)) {
                    vanillaSaveQuit = button;
                }
            }
        }
        if (this.customSetting != null) {
            this.vanillaBoxX = this.customSetting.getX();
            this.vanillaBoxY = this.customSetting.getY();
            this.vanillaBoxWidth = this.customSetting.getWidth();
        } else {
            MaidSelfTalkMod.LOGGER.warn("未能在原版人设页定位整段人设输入框，结构化模式按原版默认布局退化显示");
        }
        if (this.exportButton == null) {
            MaidSelfTalkMod.LOGGER.warn("未能在原版人设页定位导出设定按钮，该按钮保持原版位置");
        }
        this.saveButton = replaceSaveButton(vanillaSave, saveText, false);
        this.saveQuitButton = replaceSaveButton(vanillaSaveQuit, saveQuitText, true);
    }

    /**
     * 接管原版保存按钮：移除原按钮，在相同位置以相同文字重建为本页回调的 FlatColorButton。
     * 「保存」走显式保存（确认模式），「保存并退出」显式保存并在成功回包后退出。
     * 原按钮缺失（定位失败）时按原版布局常量退位重建，保证保存入口始终存在。
     */
    @Nullable
    private FlatColorButton replaceSaveButton(@Nullable FlatColorButton vanilla, String text, boolean exitAfter) {
        int x;
        int y;
        if (vanilla != null) {
            this.removeWidget(vanilla);
            x = vanilla.getX();
            y = vanilla.getY();
        } else {
            // 原版布局：保存按钮与输入框顶对齐、在右侧按钮列上，「保存并退出」在其下 25px
            x = this.vanillaBoxX + COLUMN_OFFSET;
            y = this.vanillaBoxY + (exitAfter ? 25 : 0);
            MaidSelfTalkMod.LOGGER.warn("未能在原版人设页定位原版保存按钮，已按原版布局重建本页保存入口");
        }
        FlatColorButton button = new FlatColorButton(x, y, COLUMN_WIDTH, BUTTON_HEIGHT,
                Component.translatable(text), b -> explicitSave(exitAfter));
        this.addRenderableWidget(button);
        return button;
    }

    /** 模式开关标签：显示当前编辑视图模式（点击切到另一个） */
    private Component modeLabel() {
        return modeName(this.editingMode);
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

    /** 按当前编辑视图模式切换原版输入框与四栏文本框的显示（同一区域互斥） */
    private void applyModeVisibility() {
        boolean structured = this.editingMode.isStructured();
        if (this.customSetting != null) {
            toggleChild(this.customSetting, !structured);
            // 原版 render/mouseReleased 按字段直接引用输入框：「自定义设定」标签与保存提示跟随其位置
            // 绘制、释放事件无条件派发给它，结构化模式下把输入框挪出屏幕隐藏这两类影响
            this.customSetting.setY(structured ? VANILLA_BOX_HIDDEN_Y : this.vanillaBoxY);
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

    /** 结构化编辑视图判定（mixin 据此跳过对隐藏原版输入框的释放派发） */
    public boolean isStructuredMode() {
        return this.editingMode.isStructured();
    }

    /**
     * 布娃娃预览位置（mixin 改写 drawMaid 参数用）：原版参数从输入框位置推导，
     * 而输入框在结构化模式下已被挪出屏幕，这里改用定位到的原版布局坐标。
     * 未定位到输入框时返回 null（mixin 不改参数，保持原版位置）。
     */
    @Nullable
    public int[] maidPreviewPos() {
        if (this.customSetting == null) {
            return null;
        }
        return new int[]{this.vanillaBoxX + this.vanillaBoxWidth + 73, this.vanillaBoxY + MAID_PREVIEW_Y_OFFSET};
    }

    /**
     * 切换模式：只改页面编辑视图（显示的输入框与按钮文字），不发送任何保存请求、
     * 不触碰两套内容本身。实际模式仍以服务端已确认的为准，需点击「保存」或「保存并退出」确认。
     */
    private void toggleMode() {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        this.editingMode = this.editingMode.isStructured()
                ? MaidPersonaMode.ORIGINAL : MaidPersonaMode.STRUCTURED;
        applyModeVisibility();
        if (this.modeButton != null) {
            this.modeButton.setMessage(modeLabel());
        }
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
     * 载入整理候选：整份填入四栏并切到结构化编辑视图，标记来源 requestId（保存时登记关联）。
     * 这里只改编辑态、不立即保存也不更新基线——AI 结果需显式保存后内容才保留，
     * 且载入本身不确认实际模式。候选保留在缓存里，直到内容保存成功或被显式丢弃。
     */
    private void loadSplitCandidate(MaidPersonaSplitClient.Entry entry) {
        if (entry.candidate() == null) {
            return;
        }
        this.editingMode = MaidPersonaMode.STRUCTURED;
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = entry.candidate().get(i);
        }
        this.loadedSplitRequestId = entry.requestId();
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
            case MaidPersonaSplitResponseMessage.RESULT_NOT_LOADED -> "split.error.not_loaded";
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

    /** 当前完整页面配置（编辑模式 + 四栏）：显式保存提交这份；内容保存只用其中的四栏 */
    private MaidPersonaConfig currentConfig() {
        return new MaidPersonaConfig(this.editingMode, currentDraft());
    }

    /** 四栏内容相对已保存基线是否有未保存改动（不含模式） */
    private boolean contentDirty() {
        return !this.baseline.draft().equals(currentDraft());
    }

    /** 编辑模式相对实际模式是否待确认（仅显式保存会确认模式） */
    private boolean modePending() {
        return this.editingMode != this.baseline.mode();
    }

    /** 页面整体未保存改动（内容变化或模式待确认），供替换确认等现有逻辑使用 */
    private boolean isDirty() {
        return contentDirty() || modePending();
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
        this.editingMode = confirmed.mode();
        for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
            this.draft[i] = confirmed.draft().get(i);
        }
        this.baseline = confirmed;
        this.awaitingConfirmation = false;
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
     * 显式保存（「保存」/「保存并退出」按钮）：原版文字始终沿用原生保存；本 mod 配置在
     * 模式待确认或四栏有变化时发送 confirmMode=true 的确认请求。「保存并退出」等待成功回包
     * 再退出、失败留在页面；无本 mod 配置改动时不制造等待请求，可直接退出。
     */
    private void explicitSave(boolean exitAfter) {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        sendVanillaSave();
        if (modePending() || contentDirty()) {
            // 请求已发出：等待回包，成功且为「保存并退出」时由回包处理退出；
            // 未发出（超限等）：留在页面，错误已在状态行提示
            submitPersona(true, exitAfter);
            return;
        }
        if (exitAfter) {
            this.onClose();
        }
    }

    /**
     * 原版文字保存：沿用 TLM 原生包发送当前 AI 数据（保留 LLM、TTS、语言等原生字段与主人称呼，
     * 从女仆当前 AI 数据直接冻结，不用默认值重建），并记录最近发送值供离页去重。
     * 该原生包没有保存确认回包，「已发出」不代表服务端已确认。
     */
    private void sendVanillaSave() {
        NetworkHandler.CHANNEL.sendToServer(new SaveMaidAIDataMessage(this.maid.getId(),
                this.maid.getAiChatManager()));
        this.sentCustomSetting = this.maid.getAiChatManager().customSetting;
        this.sentOwnerName = this.maid.getAiChatManager().ownerName;
    }

    /** 原版文字或主人称呼相对最近发送值是否有变化 */
    private boolean vanillaTextChanged() {
        return !this.maid.getAiChatManager().customSetting.equals(this.sentCustomSetting)
                || !this.maid.getAiChatManager().ownerName.equals(this.sentOwnerName);
    }

    /**
     * 提交保存：冻结本次完整配置快照并发出请求，随后进入等待态。
     * 显式保存（confirmMode=true）在整体有变化时发送，服务端确认请求中的模式与四栏；
     * 内容保存（confirmMode=false）只在四栏有变化时发送，请求中的模式不具写入效力。
     */
    private void submitPersona(boolean confirmMode, boolean exitAfter) {
        if (this.pendingRequestId != null || this.awaitingConfirmation
                || MaidPersonaSplitClient.isRunningFor(this.maid.getUUID())) {
            return;
        }
        MaidPersonaConfig snapshot = currentConfig();
        if (confirmMode ? snapshot.equals(this.baseline) : !contentDirty()) {
            return;
        }
        if (!snapshot.draft().withinLimit()) {
            setStatus(Component.translatable(
                    "config.maid_self_talk.screen.persona_draft.error.too_long"), 0xFF5555);
            return;
        }
        this.pendingRequestId = UUID.randomUUID();
        this.pendingConfirmMode = confirmMode;
        this.pendingExitAfter = exitAfter;
        setStatus(Component.translatable(
                "config.maid_self_talk.screen.persona_draft.status.saving"), 0xFFAA55);
        // 客户端级登记：页面提前关闭时，回包仍能独立定位女仆并更新客户端宿主（见 SelfTalkPlayerSettingsClient）
        SelfTalkPlayerSettingsClient.registerPersonaSave(this.pendingRequestId, this.maid);
        // 本页载入过整理候选：登记「保存请求 → 整理候选」关联，内容保存成功即可消费候选（模式仍需显式确认）
        if (this.loadedSplitRequestId != null) {
            MaidPersonaSplitClient.linkSave(this.pendingRequestId, this.maid.getUUID(),
                    this.loadedSplitRequestId);
        }
        SelfTalkPackets.CHANNEL.sendToServer(new MaidPersonaSaveMessage(this.pendingRequestId,
                this.maid.getId(), this.maid.getUUID(), confirmMode, snapshot.mode().tag(),
                snapshot.draft().identity(), snapshot.draft().personality(),
                snapshot.draft().background(), snapshot.draft().hobbies()));
    }

    /**
     * 处理服务端结果：只认正在等待的那一个 requestId，处理前先取出本次保存意图。
     * 内容保存成功：以回包更新基线与四栏确认值，保留当前编辑模式与「模式待确认」状态，不退出；
     * 显式保存成功：采用服务端确认的模式、四栏及基线，「保存并退出」且仍在原页面时才退出。
     * 失败保留页面编辑态与旧基线，清除在途与退出意图（「保存并退出」失败不自动关页），
     * 绝不把失败当成已保存。客户端宿主的更新由
     * {@link SelfTalkPlayerSettingsClient#onPersonaSaveResponse} 独立完成，此处不重复写。
     */
    public void applySaveResponse(MaidPersonaSaveResponseMessage msg) {
        if (this.pendingRequestId == null || !this.pendingRequestId.equals(msg.getRequestId())) {
            return;
        }
        boolean confirmMode = this.pendingConfirmMode;
        boolean exitAfter = this.pendingExitAfter;
        this.pendingRequestId = null;
        this.pendingConfirmMode = false;
        this.pendingExitAfter = false;
        if (msg.isSuccess()) {
            MaidPersonaConfig saved = msg.getConfirmed();
            if (confirmMode) {
                // 显式保存：模式与四栏一次采用服务端确认值
                this.editingMode = saved.mode();
                for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                    this.draft[i] = saved.draft().get(i);
                }
            } else {
                // 内容保存：只并入四栏确认值，编辑模式与「模式待确认」状态保留
                for (int i = 0; i < MaidPersonaDraft.CATEGORY_COUNT; i++) {
                    this.draft[i] = saved.draft().get(i);
                }
            }
            this.baseline = saved;
            // 成功不显示状态行：清掉通用状态文本，避免旧提示残留
            this.statusText = Component.empty();
            if (confirmMode) {
                applyModeVisibility();
            }
            refreshBoxes();
            if (confirmMode && this.modeButton != null) {
                this.modeButton.setMessage(modeLabel());
            }
            if (exitAfter && this.minecraft != null && this.minecraft.screen == this) {
                this.onClose();
            }
            return;
        }
        setStatus(errorMessage(msg.getResultCode()), 0xFF5555);
    }

    private static Component errorMessage(String resultCode) {
        String key = switch (resultCode == null ? "" : resultCode) {
            case MaidPersonaSaveResponseMessage.RESULT_RATE_LIMITED -> "error.rate_limited";
            case MaidPersonaSaveResponseMessage.RESULT_BUSY -> "error.busy";
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
        // 离页自动保存：只保存有变化的文字内容，不改变实际模式，也不等待回包
        saveUnsavedOnExit();
    }

    /**
     * 真正离页时的一次性自动保存：原版文字/称呼有变化才补发原生保存；四栏内容有变化且保存门禁
     * 允许时发送仅内容保存（confirmMode=false，服务端保留当前实际模式）。不等待回包、无失败重试；
     * 已有本 mod 保存请求在途时不叠加第二个请求。原版文字与四栏的保存判断相互独立，
     * 四栏被门禁拦下不影响原生保存。
     */
    private void saveUnsavedOnExit() {
        if (this.minecraft == null || this.minecraft.level == null) {
            // 连接不可用：无法发送保存，静默放弃（不新增离线恢复机制）
            return;
        }
        if (vanillaTextChanged()) {
            sendVanillaSave();
        }
        // 门禁（在途/跨页等待/整理互斥）与「内容有变化」判断都在 submitPersona 内
        submitPersona(false, false);
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean pending = this.pendingRequestId != null;
        // 整理任务在途（含重开页面的同女仆任务）与跨页面待确认门禁一样锁编辑与提交；
        // 离开页面不取消任务，整理中允许查看与离开
        boolean splitRunning = MaidPersonaSplitClient.isRunningFor(this.maid.getUUID());
        boolean locked = pending || this.awaitingConfirmation || splitRunning;
        if (this.modeButton != null) {
            this.modeButton.active = !locked;
        }
        // 拆分入口：原文空白、门禁/等待期间、已有 READY 候选未消费时禁用
        if (this.splitButton != null) {
            this.splitButton.active = !locked && !splitSourceText().isBlank()
                    && !MaidPersonaSplitClient.hasReadyCandidate(this.maid.getUUID());
        }
        // 接管的保存按钮与模式按钮同一禁用口径：保存等待、跨页等待与 AI 整理期间不可点
        if (this.saveButton != null) {
            this.saveButton.active = !locked;
        }
        if (this.saveQuitButton != null) {
            this.saveQuitButton.active = !locked;
        }
        for (GuardedPersonaEditBox box : this.boxes) {
            if (box != null) {
                box.setEditable(!locked);
            }
        }

        super.render(graphics, mouseX, mouseY, partialTick);

        if (this.editingMode.isStructured()) {
            // 四栏标签：画在各自文本框上方
            for (int i = 0; i < this.boxes.length; i++) {
                if (this.boxes[i] != null) {
                    graphics.drawString(this.font, categoryLabel(i),
                            this.boxes[i].getX() + 2, this.boxes[i].getY() - 10, 0xFFFFFF);
                }
            }
            // 两行常驻提示：第一行说明固定注入、第二行说明动态召回，逐行绘制（drawCenteredString 不处理换行）；
            // 紧凑布局下收起，把纵向空间留给四栏
            if (!this.compactLayout) {
                graphics.drawCenteredString(this.font, Component.translatable(
                        "config.maid_self_talk.screen.persona_draft.notice.line1"), this.width / 2,
                        this.height - 36, 0xFFAA55);
                graphics.drawCenteredString(this.font, Component.translatable(
                        "config.maid_self_talk.screen.persona_draft.notice.line2"), this.width / 2,
                        this.height - 24, 0xFFAA55);
            }
        }

        if (splitRunning) {
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
