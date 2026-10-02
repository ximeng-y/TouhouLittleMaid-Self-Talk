package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.network.CustomPromptConfigRequestPayload;
import com.maidmod.selftalk.network.CustomPromptConfigSetPayload;
import com.maidmod.selftalk.network.EnvironmentContextConfigResponsePayload;
import com.maidmod.selftalk.network.InterChatConfigRequestPayload;
import com.maidmod.selftalk.network.InterChatConfigSetPayload;
import com.maidmod.selftalk.network.SelfTalkConfigRequestPayload;
import com.maidmod.selftalk.network.SelfTalkConfigSetPayload;
import com.maidmod.selftalk.network.SleepQuietConfigRequestPayload;
import com.maidmod.selftalk.network.SleepQuietConfigSetPayload;
import com.maidmod.selftalk.network.ToolConfigRequestPayload;
import com.maidmod.selftalk.network.ToolConfigSetPayload;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.ComponentPath;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.navigation.FocusNavigationEvent;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * 玩家独立设置界面：挂在 AI 聊天输入界面上的按钮进入。
 * 布局为左按钮列（自话/互聊/睡觉安静/Tool 四组开关）+ 右自定义 Prompt 面板（两个多行输入框 + 覆盖开关），
 * 左右两块作为整体相对屏幕中线对称摆放。
 * <p>
 * 「睡觉时安静」与自话/互聊极性相反：true = 安静（缺省），全局安静时单只不可配置；
 * 「Tool 调用」与自话/互聊极性也相反：缺省关闭，全局关闭时单只不可配置；
 * 且受管理员 PLAYER_OPTION_ENABLED + TOOL_CALL_ENABLED 双重约束。
 * 自定义 Prompt 的保存时机：不在每次按键发包，焦点切出输入框（tick 检测）或界面关闭时，
 * 与最近一次已知服务端值比较不同才发 Set 包。
 */
@OnlyIn(Dist.CLIENT)
public class SelfTalkPlayerSettingsScreen extends Screen {

    /** 自定义 Prompt 输入框字符上限（与服务端 sanitizePromptText 一致） */
    private static final int PROMPT_MAX_LENGTH = 500;
    /** 左侧按钮列宽度 */
    private static final int BUTTON_WIDTH = 150;
    /** 左右两块之间的间隔 */
    private static final int COLUMN_GAP = 20;
    /** 右侧面板期望宽度（窄屏时收窄，见 layoutX 计算） */
    private static final int PANEL_WIDTH = 190;
    /** Prompt 输入框高度：3 行文本 + 内边距 */
    private static final int PROMPT_BOX_HEIGHT = 36;
    /** 「屏幕外」鼠标坐标：浮层打开时用它绘制底层控件，避免底层出现悬停高亮 */
    private static final int OFFSCREEN = -1000;

    private final EntityMaid maid;

    // ===== 自话组状态 =====
    private boolean adminEnabled = true;
    private boolean globalEnabled = true;
    private boolean maidEnabled = true;
    // ===== 互聊组状态 =====
    private boolean interGlobalEnabled = true;
    private boolean interMaidEnabled = true;
    // ===== 睡觉时安静组状态 =====
    private boolean sleepGlobalEnabled = true;
    private boolean sleepMaidEnabled = true;
    // ===== 自定义 Prompt 组状态 =====
    private boolean promptAdminEnabled = true;
    /** 最近一次已知的服务端值（或最近一次已发包的值）：发包与回包共享的基线 */
    private String globalPromptSynced = "";
    private String maidPromptSynced = "";
    /** 用户编辑过即闩住（不清除）：界面生命周期内丢弃可能迟到的值回包，防覆盖用户输入 */
    private boolean dirtyPromptGlobal = false;
    private boolean dirtyPromptMaid = false;
    private boolean overrideEnabled = false;
    private boolean dirtyOverride = false;
    // ===== Tool 组状态 =====
    private boolean toolFeatureEnabled = true;
    private boolean toolGlobalEnabled = false;
    private boolean toolMaidEnabled = false;

    // ===== 控件 =====
    private Button globalButton;
    private Button maidButton;
    private Button interGlobalButton;
    private Button interMaidButton;
    private Button sleepGlobalButton;
    private Button sleepMaidButton;
    private Button toolGlobalButton;
    private Button toolMaidButton;
    private GuardedMultiLineEditBox globalPromptBox;
    private GuardedMultiLineEditBox maidPromptBox;
    private Button overrideButton;

    // ===== 守卫标志 =====
    private boolean dirtySelf = false;
    private boolean dirtyInter = false;
    private boolean dirtySleep = false;
    private boolean dirtyTool = false;
    /** 重开全局后主动请求刷新：该响应须穿透 dirty 守卫（单只值以服务端为准） */
    private boolean refreshSelfExpected = false;
    private boolean refreshInterExpected = false;
    private boolean refreshSleepExpected = false;
    private boolean refreshToolExpected = false;
    /** 上一个持有焦点的输入框（tick 检测焦点切出时保存 Prompt） */
    private MultiLineEditBox lastFocusedBox;
    /** 程序化 setValue 期间挂起 value listener（防误标 dirty） */
    private boolean suspendPromptListener = false;
    /** 环境上下文入口按钮（右侧 Prompt 区，打开浮层） */
    private Button environmentContextButton;
    /** 环境上下文浮层（本 Screen 内部管理的组件，不是另一个 Screen） */
    private final EnvironmentContextPanel environmentContextPanel;
    /** 布局（init 计算，render 复用；resize 重建时刷新） */
    private int leftX;
    private int rightX;
    private int panelWidth;
    private int colTop;

    public SelfTalkPlayerSettingsScreen(EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.player_settings.title"));
        this.maid = maid;
        // Screen.font 要到 init 才赋值，此处使用已初始化的客户端字体，并保留同一浮层实例。
        this.environmentContextPanel = new EnvironmentContextPanel(Minecraft.getInstance().font);
    }

    @Override
    protected void init() {
        PacketDistributor.sendToServer(new SelfTalkConfigRequestPayload(this.maid.getUUID()));
        PacketDistributor.sendToServer(new InterChatConfigRequestPayload(this.maid.getUUID()));
        PacketDistributor.sendToServer(new SleepQuietConfigRequestPayload(this.maid.getUUID()));
        PacketDistributor.sendToServer(new CustomPromptConfigRequestPayload(this.maid.getUUID()));
        PacketDistributor.sendToServer(new ToolConfigRequestPayload(this.maid.getUUID()));

        int buttonW = BUTTON_WIDTH;
        int panelW = Math.min(PANEL_WIDTH, this.width - buttonW - COLUMN_GAP - 20);
        if (panelW < 110) {
            panelW = 110;
        }
        // 左右两块（按钮列 + 间隔 + Prompt 面板）作为整体相对屏幕中线对称摆放
        this.leftX = this.width / 2 - (buttonW + COLUMN_GAP + panelW) / 2;
        this.rightX = this.leftX + buttonW + COLUMN_GAP;
        this.panelWidth = panelW;

        // 垂直：按钮列 8 枚（组内步进 20、组间步进 28）总高 184，矮屏时顶部钳制保证标题可见
        this.colTop = Math.max(this.height / 2 - 92, 30);
        int leftX = this.leftX;
        int rightX = this.rightX;
        int colTop = this.colTop;

        // 组内 0 间隙（步进 20）、组间 8px（步进 28）；现有四组相对顺序不变，Tool 组追加在最后
        this.globalButton = addLeftButton(leftX, colTop, 0, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.global", globalEnabled, b -> toggleGlobal());
        this.maidButton = addLeftButton(leftX, colTop, 20, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled, b -> toggleMaid());
        this.interGlobalButton = addLeftButton(leftX, colTop, 48, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.inter_global", interGlobalEnabled, b -> toggleInterGlobal());
        this.interMaidButton = addLeftButton(leftX, colTop, 68, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.inter_maid_toggle", interMaidEnabled, b -> toggleInterMaid());
        this.sleepGlobalButton = addLeftButton(leftX, colTop, 96, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.sleep_global", sleepGlobalEnabled, b -> toggleSleepGlobal());
        this.sleepMaidButton = addLeftButton(leftX, colTop, 116, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.sleep_maid_toggle", sleepMaidEnabled, b -> toggleSleepMaid());
        Tooltip toolTooltip = Tooltip.create(Component.translatable(
                "config.maid_self_talk.screen.player_settings.tool_cache_tooltip"));
        this.toolGlobalButton = addLeftButton(leftX, colTop, 144, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.tool_global", toolGlobalEnabled, b -> toggleToolGlobal());
        this.toolGlobalButton.setTooltip(toolTooltip);
        this.toolMaidButton = addLeftButton(leftX, colTop, 164, buttonW, 20,
                "config.maid_self_talk.screen.player_settings.tool_maid_toggle", toolMaidEnabled, b -> toggleToolMaid());
        this.toolMaidButton.setTooltip(toolTooltip);

        // 右侧 Prompt 面板：标题 + 输入框 + 覆盖开关（贴在按钮列同一顶部起点）。
        // 各段之间预留 12px 以上：MultiLineEditBox 的字数上限文字由基类画在 getY()+height+4 处
        // （高 9px），紧接着的标题或按钮上沿侵入该带就会压字
        Component placeholder = Component.translatable(
                "config.maid_self_talk.screen.player_settings.custom_prompt.placeholder");
        this.globalPromptBox = new GuardedMultiLineEditBox(this.font, rightX, colTop + 12,
                panelW, PROMPT_BOX_HEIGHT, placeholder);
        this.globalPromptBox.setCharacterLimit(PROMPT_MAX_LENGTH);
        this.globalPromptBox.setValueListener(v -> {
            if (!this.suspendPromptListener) {
                this.dirtyPromptGlobal = true;
            }
        });
        this.addRenderableWidget(this.globalPromptBox);
        this.maidPromptBox = new GuardedMultiLineEditBox(this.font, rightX, colTop + 78,
                panelW, PROMPT_BOX_HEIGHT, placeholder);
        this.maidPromptBox.setCharacterLimit(PROMPT_MAX_LENGTH);
        this.maidPromptBox.setValueListener(v -> {
            if (!this.suspendPromptListener) {
                this.dirtyPromptMaid = true;
            }
        });
        this.addRenderableWidget(this.maidPromptBox);
        this.overrideButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.player_settings.custom_prompt.override",
                                onOff(overrideEnabled)),
                        b -> toggleOverride())
                .bounds(rightX, colTop + 134, panelW, 20)
                .tooltip(Tooltip.create(Component.translatable(
                        "config.maid_self_talk.screen.player_settings.custom_prompt.override.tooltip")))
                .build());
        // 环境上下文入口：放在右侧 Prompt 区（左列已有 8 枚按钮，再往左列追加会把页面继续撑高）。
        // 入口本身始终可打开——即使管理员禁止编辑，也要能进去看到禁用说明
        this.environmentContextButton = this.addRenderableWidget(Button.builder(
                        Component.translatable("config.maid_self_talk.screen.player_settings.context.entry"),
                        b -> openEnvironmentContextPanel())
                .bounds(rightX, colTop + 172, panelW, 20)
                .build());
        this.environmentContextPanel.layout(this.width, this.height);
        refreshButtonState();
    }

    /**
     * 打开环境上下文浮层：先 flush Prompt 编辑（清掉输入框焦点，内容不清空——关闭浮层后仍要能继续编辑），
     * 再让浮层换新会话并请求快照。
     */
    private void openEnvironmentContextPanel() {
        flushPromptEdits();
        this.setFocused(null);
        this.environmentContextPanel.layout(this.width, this.height);
        this.environmentContextPanel.open();
    }

    /**
     * 窗口缩放：init 重建控件会清空输入框内容，先暂存当前值、重建后恢复。
     * 否则关闭界面时 flush 会把「显示空 ≠ 基线」误判为用户清空，
     * 静默发出空串 Set 包抹掉已保存的 Prompt（数据丢失路径）。
     * <p>
     * 浮层状态（开关、滚动偏移、32 项状态、焦点、session、seq、同步标志）由浮层自身持有，
     * 这里只让它按新尺寸重算几何——<b>不</b>新建会话、不把默认值覆盖到已收到的设置上。
     */
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        String globalValue = this.globalPromptBox == null ? null : this.globalPromptBox.getValue();
        String maidValue = this.maidPromptBox == null ? null : this.maidPromptBox.getValue();
        super.resize(minecraft, width, height);
        this.suspendPromptListener = true;
        if (this.globalPromptBox != null && globalValue != null) {
            this.globalPromptBox.setValue(globalValue);
        }
        if (this.maidPromptBox != null && maidValue != null) {
            this.maidPromptBox.setValue(maidValue);
        }
        this.suspendPromptListener = false;
        this.environmentContextPanel.layout(width, height);
    }

    private Button addLeftButton(int leftX, int colTop, int yOffset, int w, int h,
                                 String key, boolean value, Button.OnPress onPress) {
        Button button = this.addRenderableWidget(Button.builder(
                Component.translatable(key, onOff(value)), onPress)
                .bounds(leftX, colTop + yOffset, w, h).build());
        return button;
    }

    /** 开关状态文案：模组自带 on/off 键，避免按钮上出现英文 true/false */
    private static Component onOff(boolean value) {
        return Component.translatable(value
                ? "config.maid_self_talk.screen.player_settings.on"
                : "config.maid_self_talk.screen.player_settings.off");
    }

    // ===== 开关切换 =====

    private void toggleGlobal() {
        boolean next = !globalEnabled;
        PacketDistributor.sendToServer(new SelfTalkConfigSetPayload(Optional.empty(), next));
        globalEnabled = next;
        dirtySelf = true;
        if (!next) {
            maidEnabled = false;
        } else {
            PacketDistributor.sendToServer(new SelfTalkConfigRequestPayload(this.maid.getUUID()));
            refreshSelfExpected = true;
        }
        refreshButtonState();
    }

    private void toggleMaid() {
        boolean next = !maidEnabled;
        PacketDistributor.sendToServer(new SelfTalkConfigSetPayload(Optional.of(this.maid.getUUID()), next));
        maidEnabled = next;
        dirtySelf = true;
        refreshButtonState();
    }

    private void toggleInterGlobal() {
        boolean next = !interGlobalEnabled;
        PacketDistributor.sendToServer(new InterChatConfigSetPayload(Optional.empty(), next));
        interGlobalEnabled = next;
        dirtyInter = true;
        if (!next) {
            interMaidEnabled = false;
        } else {
            PacketDistributor.sendToServer(new InterChatConfigRequestPayload(this.maid.getUUID()));
            refreshInterExpected = true;
        }
        refreshButtonState();
    }

    private void toggleInterMaid() {
        boolean next = !interMaidEnabled;
        PacketDistributor.sendToServer(new InterChatConfigSetPayload(Optional.of(this.maid.getUUID()), next));
        interMaidEnabled = next;
        dirtyInter = true;
        refreshButtonState();
    }

    /**
     * 「睡觉时安静」全局开关：true = 全局安静（单只随全局、不可配置）；
     * false = 允许说话，此时刷新单只有效值（名单残留项由服务端裁决）。
     * 注意极性：与自话/互聊的"全局 false 锁单只"相反。
     */
    private void toggleSleepGlobal() {
        boolean next = !sleepGlobalEnabled;
        PacketDistributor.sendToServer(new SleepQuietConfigSetPayload(Optional.empty(), next));
        sleepGlobalEnabled = next;
        dirtySleep = true;
        if (next) {
            sleepMaidEnabled = true;
        } else {
            PacketDistributor.sendToServer(new SleepQuietConfigRequestPayload(this.maid.getUUID()));
            refreshSleepExpected = true;
        }
        refreshButtonState();
    }

    private void toggleSleepMaid() {
        boolean next = !sleepMaidEnabled;
        PacketDistributor.sendToServer(new SleepQuietConfigSetPayload(Optional.of(this.maid.getUUID()), next));
        sleepMaidEnabled = next;
        dirtySleep = true;
        refreshButtonState();
    }

    /** Tool 全局开关（缺省关）：开启后刷新单只有效值，关闭则单只一并视为关闭 */
    private void toggleToolGlobal() {
        boolean next = !toolGlobalEnabled;
        PacketDistributor.sendToServer(new ToolConfigSetPayload(Optional.empty(), next));
        toolGlobalEnabled = next;
        dirtyTool = true;
        if (next) {
            PacketDistributor.sendToServer(new ToolConfigRequestPayload(this.maid.getUUID()));
            refreshToolExpected = true;
        } else {
            toolMaidEnabled = false;
        }
        refreshButtonState();
    }

    private void toggleToolMaid() {
        boolean next = !toolMaidEnabled;
        PacketDistributor.sendToServer(new ToolConfigSetPayload(Optional.of(this.maid.getUUID()), next));
        toolMaidEnabled = next;
        dirtyTool = true;
        refreshButtonState();
    }

    private void toggleOverride() {
        boolean next = !overrideEnabled;
        PacketDistributor.sendToServer(new CustomPromptConfigSetPayload(
                Optional.empty(), Optional.empty(), Optional.of(next)));
        overrideEnabled = next;
        dirtyOverride = true;
        refreshButtonState();
    }

    // ===== 响应处理 =====

    public void applyResponse(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        // dirty 守卫丢弃的是打开界面时初始请求的迟到响应（防覆盖用户刚做的切换）；
        // 重开全局后的主动刷新响应须穿透守卫，但仅当全局值与本地最新意图一致——
        // 初始响应可能晚于用户点击到达（携带陈旧值），不校验会吃掉 refresh 标志，
        // 把随后的新鲜响应挡在守卫外（此时保留标志等下一响应）
        boolean accept = !dirtySelf || (refreshSelfExpected && this.globalEnabled == globalEnabled);
        if (accept) {
            this.globalEnabled = globalEnabled;
            this.maidEnabled = maidEnabled;
            refreshSelfExpected = false;
        }
        refreshButtonState();
    }

    public void applyInterChatResponse(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        boolean accept = !dirtyInter || (refreshInterExpected && this.interGlobalEnabled == globalEnabled);
        if (accept) {
            this.interGlobalEnabled = globalEnabled;
            this.interMaidEnabled = maidEnabled;
            refreshInterExpected = false;
        }
        refreshButtonState();
    }

    /** 「睡觉时安静」响应：全局安静时单只值恒 true（随全局），其余逻辑与互聊组同构 */
    public void applySleepQuietResponse(boolean globalEnabled, boolean maidEnabled) {
        boolean accept = !dirtySleep || (refreshSleepExpected && this.sleepGlobalEnabled == globalEnabled);
        if (accept) {
            this.sleepGlobalEnabled = globalEnabled;
            this.sleepMaidEnabled = maidEnabled;
            refreshSleepExpected = false;
        }
        refreshButtonState();
    }

    /**
     * 自定义 Prompt 响应：Prompt 文本与覆盖开关分别守卫。
     * 用户编辑过的输入框（dirty 闩住）丢弃后续值回包——Set 包不回显，
     * 之后的回包只可能是打开界面时初始请求的迟到响应，接受会覆盖用户输入。
     */
    public void applyCustomPromptResponse(boolean adminEnabled, String globalPrompt,
                                          String maidPrompt, boolean overrideEnabled) {
        this.promptAdminEnabled = adminEnabled;
        if (!dirtyPromptGlobal) {
            this.globalPromptSynced = globalPrompt;
            if (this.globalPromptBox != null) {
                this.suspendPromptListener = true;
                this.globalPromptBox.setValue(globalPrompt);
                this.suspendPromptListener = false;
            }
        }
        if (!dirtyPromptMaid) {
            this.maidPromptSynced = maidPrompt;
            if (this.maidPromptBox != null) {
                this.suspendPromptListener = true;
                this.maidPromptBox.setValue(maidPrompt);
                this.suspendPromptListener = false;
            }
        }
        if (!dirtyOverride) {
            this.overrideEnabled = overrideEnabled;
        }
        refreshButtonState();
    }

    /**
     * Tool 响应回填。PLAYER_OPTION_ENABLED 与自话组/自定义 Prompt 组同源，由各自的
     * applyResponse/applyCustomPromptResponse 维护（Tool 按钮与提示链均读全局 adminEnabled），
     * 此处只需 TOOL_CALL_ENABLED 与玩家两级开关。
     */
    public void applyToolResponse(boolean toolAdminEnabled,
                                  boolean globalEnabled, boolean maidEnabled) {
        this.toolFeatureEnabled = toolAdminEnabled;
        boolean accept = !dirtyTool || (refreshToolExpected && this.toolGlobalEnabled == globalEnabled);
        if (accept) {
            this.toolGlobalEnabled = globalEnabled;
            this.toolMaidEnabled = maidEnabled;
            refreshToolExpected = false;
        }
        refreshButtonState();
    }

    // ===== 保存与刷新 =====

    /**
     * 保存 Prompt 编辑：仅当输入框当前值与已同步基线不同才发 Set 包，发包后更新基线
     * （基线同时承担"服务端已知值"与"最近一次已发包值"两个语义，回退编辑也能正确补发）。
     */
    private void flushPromptEdits() {
        if (!this.promptAdminEnabled) {
            return;
        }
        if (this.globalPromptBox != null) {
            String value = this.globalPromptBox.getValue();
            if (!value.equals(this.globalPromptSynced)) {
                PacketDistributor.sendToServer(new CustomPromptConfigSetPayload(
                        Optional.empty(), Optional.of(value), Optional.empty()));
                this.globalPromptSynced = value;
            }
        }
        if (this.maidPromptBox != null) {
            String value = this.maidPromptBox.getValue();
            if (!value.equals(this.maidPromptSynced)) {
                PacketDistributor.sendToServer(new CustomPromptConfigSetPayload(
                        Optional.of(this.maid.getUUID()), Optional.of(value), Optional.empty()));
                this.maidPromptSynced = value;
            }
        }
    }

    @Override
    public void tick() {
        super.tick();
        this.environmentContextPanel.tick();
        // 焦点从某个输入框切出时保存（界面关闭另有 onClose 兜底）
        AbstractWidget focused = this.getFocused() instanceof AbstractWidget w ? w : null;
        if (this.lastFocusedBox != null && this.lastFocusedBox != focused) {
            flushPromptEdits();
        }
        this.lastFocusedBox = focused instanceof MultiLineEditBox box ? box : null;
    }

    @Override
    public void onClose() {
        flushPromptEdits();
        super.onClose();
    }

    // ===== 环境上下文浮层：事件转发（浮层优先，未使用也不透传） =====

    /**
     * 浮层打开时，鼠标点击／释放／拖动、滚轮、按键与字符输入一律先交给浮层；
     * 浮层未用到的这些事件<b>也不得</b>透传给 Prompt 输入框或左列开关——
     * 浮层是模态的（点击面板外只拦截、不关闭、不透传）。
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.mouseClicked(mouseX, mouseY, button);
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.mouseReleased(mouseX, mouseY, button);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    /** NeoForge 1.21.1 的滚轮回调带横、纵两个滚动量，浮层只关心纵向 */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.mouseScrolled(scrollY);
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    /** Esc 先关浮层，浮层关闭后才恢复原 Screen 的关闭行为 */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.keyPressed(keyCode, scanCode, modifiers);
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (this.environmentContextPanel.isOpen()) {
            return this.environmentContextPanel.charTyped(codePoint, modifiers);
        }
        return super.charTyped(codePoint, modifiers);
    }

    /** 环境上下文浮层的权威快照回包（会话/序号校验在浮层内完成） */
    public void applyEnvironmentContextResponse(EnvironmentContextConfigResponsePayload payload) {
        this.environmentContextPanel.applyResponse(payload);
    }

    private void refreshButtonState() {
        if (this.globalButton == null || this.maidButton == null || this.interGlobalButton == null
                || this.interMaidButton == null || this.sleepGlobalButton == null || this.sleepMaidButton == null
                || this.toolGlobalButton == null || this.toolMaidButton == null
                || this.globalPromptBox == null || this.maidPromptBox == null || this.overrideButton == null
                || this.environmentContextButton == null) {
            return;
        }
        this.globalButton.active = this.adminEnabled;
        this.maidButton.active = this.adminEnabled && this.globalEnabled;
        this.interGlobalButton.active = this.adminEnabled;
        this.interMaidButton.active = this.adminEnabled && this.interGlobalEnabled;
        // 「睡觉时安静」不受管理员开关控制；单只仅全局允许说话（false）时可配置——与上两组激活逻辑相反
        this.sleepGlobalButton.active = true;
        this.sleepMaidButton.active = !this.sleepGlobalEnabled;
        // Tool：受管理员双重闸门；单只再叠加全局开关（全局关时单只不可配置，与睡觉组同思路、与自话组同形）
        this.toolGlobalButton.active = this.adminEnabled && this.toolFeatureEnabled;
        this.toolMaidButton.active = this.adminEnabled && this.toolFeatureEnabled && this.toolGlobalEnabled;
        // Prompt 控件：管理员关玩家配置时整体置灰；单只输入框在覆盖开启时仍可编辑（已填内容保留）
        this.globalPromptBox.editable = this.promptAdminEnabled;
        this.maidPromptBox.editable = this.promptAdminEnabled;
        this.overrideButton.active = this.promptAdminEnabled;

        this.globalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.global", onOff(globalEnabled)));
        this.maidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.maid_toggle", onOff(maidEnabled)));
        this.interGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_global", onOff(interGlobalEnabled)));
        this.interMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_maid_toggle", onOff(interMaidEnabled)));
        this.sleepGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.sleep_global", onOff(sleepGlobalEnabled)));
        this.sleepMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.sleep_maid_toggle", onOff(sleepMaidEnabled)));
        this.toolGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.tool_global", onOff(toolGlobalEnabled)));
        this.toolMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.tool_maid_toggle", onOff(toolMaidEnabled)));
        this.overrideButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.custom_prompt.override", onOff(overrideEnabled)));
        // 环境上下文入口始终可点（管理员禁用时也要能进去看说明）
        this.environmentContextButton.active = true;
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        boolean panelOpen = this.environmentContextPanel.isOpen();
        // 浮层打开时把原页面控件按屏幕外鼠标坐标绘制：底层按钮不产生悬停高亮，
        // 也就不会与浮层的悬停提示叠加。浮层与其 tooltip 最后绘制，位于最上层。
        if (panelOpen) {
            super.render(graphics, OFFSCREEN, OFFSCREEN, partialTick);
        } else {
            super.render(graphics, mouseX, mouseY, partialTick);
        }
        int colTop = this.colTop;
        graphics.drawCenteredString(this.font, this.title, this.width / 2, colTop - 28, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("config.maid_self_talk.screen.player_settings.maid", this.maid.getName()),
                this.width / 2, colTop - 16, 0xAAAAAA);
        // 两个输入框的区分标题（右面板顶部与单只段输入框上方）
        if (this.globalPromptBox != null && this.maidPromptBox != null) {
            graphics.drawString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.custom_prompt.global_title"),
                    this.rightX, colTop, 0xFFFFFF);
            graphics.drawString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.custom_prompt.maid_title"),
                    this.rightX, colTop + 66, 0xFFFFFF);
        }
        // 覆盖生效提示：全局段非空且覆盖开启时，单只段不会注入（输入框仍可编辑）
        if (this.overrideEnabled && this.globalPromptBox != null
                && !this.globalPromptBox.getValue().isBlank()) {
            graphics.drawString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.custom_prompt.overridden_hint"),
                    this.globalPromptBox.getX(), colTop + 158, 0xAAAAAA);
        }
        int hintY = colTop + 200;
        if (!this.adminEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.admin_disabled"),
                    this.width / 2, hintY, 0xFF5555);
        } else if (!this.globalEnabled || !this.interGlobalEnabled) {
            String key = !this.globalEnabled ? "config.maid_self_talk.screen.player_settings.global_off_hint" : "config.maid_self_talk.screen.player_settings.inter_global_off_hint";
            graphics.drawCenteredString(this.font,
                    Component.translatable(key),
                    this.width / 2, hintY, 0xFFAA55);
        } else if (!this.toolFeatureEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.tool_admin_disabled"),
                    this.width / 2, hintY, 0xFFAA55);
        } else if (!this.toolGlobalEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.tool_global_off_hint"),
                    this.width / 2, hintY, 0xFFAA55);
        } else if (this.sleepGlobalEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.sleep_global_hint"),
                    this.width / 2, hintY, 0xFFAA55);
        }
        if (panelOpen) {
            this.environmentContextPanel.render(graphics, mouseX, mouseY, partialTick);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    /**
     * 可整体禁用编辑的多行输入框。
     * {@link MultiLineEditBox} 的 {@code active} 标志不拦截编辑——mouseClicked/keyPressed/charTyped
     * 均不检查 active，点击仍会夺焦并接受输入，因此置灰必须用显式编辑闸门实现。
     * 滚轮滚动不受影响（禁用后仍可查看已填内容；滚动条拖拽走 mouseDragged，会被闸门一并拦截）。
     */
    private static class GuardedMultiLineEditBox extends MultiLineEditBox {

        private boolean editable = true;

        private GuardedMultiLineEditBox(Font font, int x, int y, int width, int height, Component placeholder) {
            super(font, x, y, width, height, placeholder, Component.empty());
        }

        /**
         * 禁用时退出 Tab 焦点链。之所以不在禁用时设 {@code active=false}：active 同时被
         * isMouseOver 检查（ContainerEventHandler 的滚轮分发经 getChildAt → isMouseOver），
         * 关掉会连禁用态滚轮查看一起失能——这里只拦焦点，不动 active。
         */
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
