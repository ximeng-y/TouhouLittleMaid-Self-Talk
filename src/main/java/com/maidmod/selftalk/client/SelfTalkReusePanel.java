package com.maidmod.selftalk.client;

import com.maidmod.selftalk.PrebuiltReuseParameters;
import com.maidmod.selftalk.network.ReuseConfigRequestMessage;
import com.maidmod.selftalk.network.ReuseConfigResponseMessage;
import com.maidmod.selftalk.network.ReuseConfigSetMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 💬 玩家设置页内的「预制复用」浮层（issue #91 起，issue #108 扩展匹配参数）。
 * <p>
 * 与「环境上下文」浮层同构的<b>现有 Screen 内部管理组件</b>：不接管 {@code setScreen}，
 * 不把控件混进页面的焦点链，事件由页面按「浮层优先、未用也不透传」的规则转发进来。
 * <p>
 * 会话模型（与 network 三个 ReuseConfig 消息的约定）：
 * <ul>
 *   <li>每次从关闭状态打开都换新 {@code sessionId}；resize 只重算几何；</li>
 *   <li>Request 与 Set 共用递增 {@code seq}；只接受「仍打开 + session 匹配 + seq == 最新发出」的响应；</li>
 *   <li>开关点击即保存（发送<b>目标绝对操作码</b>），先乐观更新并显示「同步中」；
 *       Set 超时不重发，转一次权威 Request 问清真实值；无待确认时每 40 tick 轮询快照。</li>
 * </ul>
 * 显示口径与响应包一致——全局默认与单只三态都是<b>已存偏好</b>（非管理员门控后的有效值）：
 * 管理员禁用时整页只读但原偏好照常显示，功能恢复后原样生效。
 * 全局默认关闭<b>不阻止</b>单只覆盖设置——单只显式开仍是有效配置，两者叠加判定由服务端负责。
 * 首次快照到达前按钮不可点击——不能拿客户端初始值覆盖尚未读到的存档设置。
 * <p>
 * 匹配参数（issue #108）则在面板内分两层维护：<b>权威值</b>只来自服务端快照，
 * <b>草稿</b>是输入框里尚未提交的文本。轮询/resize 不覆盖未提交草稿，
 * 非法草稿保留原文并高亮，绝不发包。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkReusePanel {

    private static final int PANEL_BG = 0xFF404040;
    private static final int SCRIM = 0xA0000000;
    private static final int BORDER_COLOR = 0xFF808080;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFFB0B0B0;
    private static final int SYNC_COLOR = 0xFFFFAA55;
    private static final int ERROR_COLOR = 0xFFFF5555;
    /** 实验警告色取值与 ChatFormatting#GOLD 对齐；tooltip 行色由 withStyle(GOLD) 决定 */
    @SuppressWarnings("unused")
    private static final int EXPERIMENT_COLOR = 0xFFFFAA00;

    private static final int MARGIN = 8;
    private static final int FOOTER_HEIGHT = 30;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 120;
    private static final int CLOSE_SIZE = 14;
    /** 滚动条轨道宽度（与环境上下文浮层一致） */
    private static final int TRACK_WIDTH = 6;
    /** 轨道与面板右边距：轨道不贴边框，避免与 1px 边框连成一条"白边" */
    private static final int TRACK_MARGIN = 4;
    /** 右侧控件与轨道之间的间距 */
    private static final int TRACK_GAP = 6;
    /** 滚动条滑块最小高度（太短的滑块不好拖） */
    private static final int MIN_THUMB_HEIGHT = 12;
    private static final int PANEL_MAX_WIDTH = 380;
    private static final int PANEL_MAX_HEIGHT = 340;
    private static final int SCREEN_MARGIN = 24;

    private static final int ROW_HEIGHT = 24;
    /** 数值行：标签 + 输入框 + 一行固定的范围/错误说明 */
    private static final int FIELD_ROW_HEIGHT = 34;
    /** 分区标题行下方留白 */
    private static final int SECTION_GAP = 6;

    private static final int POLL_TICKS = 40;
    private static final int SET_CONFIRM_TIMEOUT_TICKS = 40;

    /** 焦点哨兵：全局默认开关 */
    private static final int FOCUS_GLOBAL = -2;
    /** 焦点哨兵：当前女仆三态 */
    private static final int FOCUS_MAID = -3;
    /** 焦点哨兵：关闭按钮 */
    private static final int FOCUS_CLOSE = -4;
    /** 焦点哨兵：跟随服务器复选框 */
    private static final int FOCUS_FOLLOW = -5;
    /** 焦点哨兵：允许玩家自定义参数复选框 */
    private static final int FOCUS_ALLOW_PLAYER = -6;
    /** 焦点哨兵：保存并复用预制语音复选框 */
    private static final int FOCUS_PREBUILT_AUDIO = -7;
    /** 数值框焦点索引：0 个人最低条数、1 个人概率、2 服务器最低条数、3 服务器概率 */
    private static final int FIELD_MIN_ELIGIBLE = 0;
    private static final int FIELD_HIT_CHANCE = 1;
    private static final int FIELD_SERVER_MIN_ELIGIBLE = 2;
    private static final int FIELD_SERVER_HIT_CHANCE = 3;
    private static final int FIELD_COUNT = 4;

    private final Font font;

    // ===== 会话与同步状态（resize 保留，关闭重开时重置） =====
    private boolean open;
    /** 当前女仆 UUID（null = 无女仆上下文，只显示全局默认行） */
    private UUID maidUuid;
    private UUID sessionId;
    private long seq;
    private long latestIssuedSeq;
    private boolean awaitingResponse;
    private boolean pendingSet;
    private int ticksSinceIssue;
    /** 是否已收到过至少一次权威快照（未收到前按钮不可点） */
    private boolean hasSnapshot;
    private boolean adminEnabled = true;
    /** 玩家默认开关的已存偏好（非管理员门控后的有效值；首次快照前 false 且不可点） */
    private boolean globalEnabled = false;
    /** 当前女仆三态的已存偏好（首次快照前 inherit 且不可点；无女仆上下文时不用） */
    private String maidState = ReuseConfigResponseMessage.MAID_INHERIT;

    // ===== 匹配参数权威值（只来自服务端快照） =====
    private boolean serverAdmin;
    private int serverMinEligible = PrebuiltReuseParameters.DEFAULT_MIN_ELIGIBLE_ENTRIES;
    private int serverHitChance = PrebuiltReuseParameters.DEFAULT_HIT_CHANCE_PERCENT;
    private boolean playerParametersAllowed;
    private boolean followingServer = true;
    private int playerMinEligible = PrebuiltReuseParameters.DEFAULT_MIN_ELIGIBLE_ENTRIES;
    private int playerHitChance = PrebuiltReuseParameters.DEFAULT_HIT_CHANCE_PERCENT;
    /** 「保存并复用预制语音」世界开关的权威值（快照同步；仅管理员可改，缺省关闭） */
    private boolean prebuiltAudioEnabled;

    // ===== 输入草稿（每框独立：文本 + 是否未提交） =====
    private final String[] drafts = new String[FIELD_COUNT];
    private final boolean[] dirty = new boolean[FIELD_COUNT];

    // ===== 布局 =====
    private int screenWidth;
    private int screenHeight;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    private int viewportHeight;
    private int contentHeight;
    private int scrollOffset;
    private int maxScroll;
    private int buttonX;
    private int fieldX;
    private int fieldWidth;
    /** 各分区/行的内容坐标（相对内容顶部），由 layoutRows 计算 */
    private int personalTitleY;
    private int globalRowY;
    private int maidRowY;
    private int followRowY;
    private int playerMinRowY;
    private int playerChanceRowY;
    private int serverTitleY;
    private int serverMinRowY;
    private int serverChanceRowY;
    private int allowRowY;
    private int audioRowY;
    private int personalFooterY;

    // ===== 交互 =====
    private int focusedIndex = -1;
    private Button globalButton;
    private Button maidButton;
    private Button closeButton;
    private Checkbox followBox;
    private Checkbox allowBox;
    private Checkbox audioBox;
    /** Forge 1.20.1 的 EditBox 需要每 tick 驱动（光标闪烁）；NeoForge 1.21.1 无 tick 方法 */
    private final EditBox[] fields = new EditBox[FIELD_COUNT];
    /** 程序性改动输入框时置位，避免触发「用户编辑」判定 */
    private boolean suppressFieldEdit;
    private boolean draggingThumb;
    private int dragGrabOffset;

    public SelfTalkReusePanel(Font font) {
        this.font = font;
        for (int i = 0; i < FIELD_COUNT; i++) {
            drafts[i] = "";
        }
    }

    public boolean isOpen() {
        return open;
    }

    // ===== 开关 =====

    /**
     * 打开浮层：换新会话并立即请求快照；女仆维度随之冻结为本次的 {@code maidUuid}。
     * 重开时清掉上一次会话的乐观值与待确认标记：宁可显示「读取中」，也不用旧会话的猜测值冒充服务端状态。
     */
    public void open(UUID maidUuid) {
        this.open = true;
        this.maidUuid = maidUuid;
        this.sessionId = UUID.randomUUID();
        this.seq = 0;
        this.latestIssuedSeq = 0;
        this.awaitingResponse = false;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        this.hasSnapshot = false;
        this.globalEnabled = false;
        this.maidState = ReuseConfigResponseMessage.MAID_INHERIT;
        this.focusedIndex = -1;
        this.serverAdmin = false;
        this.playerParametersAllowed = false;
        this.prebuiltAudioEnabled = false;
        this.followingServer = true;
        clearDrafts();
        sendRequest(true);
    }

    /**
     * 关闭浮层：只提交当前可编辑的合法待提交值（非法草稿不保存、也不阻止关闭），
     * 随后停止请求与轮询，之后到达的回包一概忽略（已发出的 Set 不撤销）。
     */
    public void close() {
        if (this.open) {
            for (int i = 0; i < FIELD_COUNT; i++) {
                submitField(i);
            }
        }
        this.open = false;
        this.focusedIndex = -1;
        setFieldFocus(null);
        this.draggingThumb = false;
    }

    // ===== 布局 =====

    /**
     * 重算几何并重建控件；不触碰会话、序号、偏好与同步标志——resize 只改布局，
     * 不能把客户端初始值覆盖到已收到的服务端设置上，也不能丢掉未提交草稿。
     */
    public void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.panelWidth = Math.min(PANEL_MAX_WIDTH, screenWidth - SCREEN_MARGIN);
        this.panelHeight = Math.min(PANEL_MAX_HEIGHT, screenHeight - SCREEN_MARGIN);
        this.panelX = (screenWidth - panelWidth) / 2;
        this.panelY = (screenHeight - panelHeight) / 2;
        this.listTop = panelY + 42;
        this.listBottom = panelY + panelHeight - FOOTER_HEIGHT;
        this.buttonX = panelX + panelWidth - TRACK_MARGIN - TRACK_WIDTH - TRACK_GAP - BUTTON_WIDTH;
        this.fieldX = buttonX;
        this.fieldWidth = BUTTON_WIDTH;
        layoutRows();
        this.viewportHeight = Math.max(0, listBottom - listTop);
        this.maxScroll = Math.max(0, contentHeight - viewportHeight);
        this.scrollOffset = clampScroll(scrollOffset);

        this.closeButton = Button.builder(Component.translatable(closeKey()), b -> close())
                .bounds(closeX(), panelY + 4, CLOSE_SIZE, CLOSE_SIZE)
                .build();
        rebuildControls();
        applyFieldValues();
        refreshButtons();
    }

    /** 行坐标按「有/无服务器区」排列：普通玩家不建立管理员区的任何行或占位 */
    private void layoutRows() {
        int y = 0;
        personalTitleY = y;
        y += 14;
        globalRowY = y;
        y += ROW_HEIGHT;
        maidRowY = y;
        y += ROW_HEIGHT;
        followRowY = y;
        y += ROW_HEIGHT;
        playerMinRowY = y;
        y += FIELD_ROW_HEIGHT;
        playerChanceRowY = y;
        y += FIELD_ROW_HEIGHT;
        personalFooterY = y;
        y += SECTION_GAP + 6;
        if (serverAdmin) {
            serverTitleY = y;
            y += 14;
            serverMinRowY = y;
            y += FIELD_ROW_HEIGHT;
            serverChanceRowY = y;
            y += FIELD_ROW_HEIGHT;
            allowRowY = y;
            y += ROW_HEIGHT;
            audioRowY = y;
            y += ROW_HEIGHT;
        } else {
            serverTitleY = -1;
            serverMinRowY = -1;
            serverChanceRowY = -1;
            allowRowY = -1;
            audioRowY = -1;
        }
        contentHeight = y;
    }

    /** 内容坐标 → 屏幕 Y（减去滚动偏移） */
    private int screenY(int contentY) {
        return listTop + contentY - scrollOffset;
    }

    private int closeX() {
        return panelX + panelWidth - 4 - CLOSE_SIZE;
    }

    private static String closeKey() {
        return "config.maid_self_talk.screen.player_settings.context.close";
    }

    /** 重建控件：优先复用输入框对象（保留输入、焦点、光标与未确认状态） */
    private void rebuildControls() {
        this.globalButton = Button.builder(Component.empty(), b -> toggleGlobal())
                .bounds(buttonX, screenY(globalRowY), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.maidButton = Button.builder(Component.empty(), b -> cycleMaidState())
                .bounds(buttonX, screenY(maidRowY), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        if (followBox == null) {
            followBox = new Checkbox(panelX + MARGIN, screenY(followRowY), 20, 20,
                    Component.empty(), true);
        }
        followBox.setX(panelX + MARGIN);
        followBox.setY(screenY(followRowY));
        if (allowBox == null) {
            allowBox = new Checkbox(panelX + MARGIN, 0, 20, 20, Component.empty(), false);
        }
        allowBox.setX(panelX + MARGIN);
        allowBox.setY(screenY(allowRowY));
        if (audioBox == null) {
            audioBox = new Checkbox(panelX + MARGIN, 0, 20, 20, Component.empty(), false);
        }
        audioBox.setX(panelX + MARGIN);
        audioBox.setY(screenY(audioRowY));
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (fields[i] == null) {
                fields[i] = new EditBox(font, fieldX, 0, fieldWidth, 18, Component.empty());
                fields[i].setMaxLength(7);
                final int index = i;
                fields[i].setResponder(text -> {
                    if (!suppressFieldEdit) {
                        drafts[index] = text;
                        dirty[index] = true;
                    }
                });
            }
            fields[i].setX(fieldX);
            fields[i].setWidth(fieldWidth);
            fields[i].setY(screenY(fieldContentY(i)));
            fields[i].setVisible(isFieldVisible(i));
        }
    }

    private int fieldContentY(int index) {
        return switch (index) {
            case FIELD_MIN_ELIGIBLE -> playerMinRowY;
            case FIELD_HIT_CHANCE -> playerChanceRowY;
            case FIELD_SERVER_MIN_ELIGIBLE -> serverMinRowY;
            default -> serverChanceRowY;
        };
    }

    /** 管理员区不存在时两个服务器数值框整体不参与布局与命中 */
    private boolean isFieldVisible(int index) {
        if (index == FIELD_SERVER_MIN_ELIGIBLE || index == FIELD_SERVER_HIT_CHANCE) {
            return serverAdmin;
        }
        return true;
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, Math.max(0, contentHeight - viewportHeight)));
    }

    /** 让某个内容坐标进入可视区（Tab 聚焦与程序滚动共用） */
    private void scrollIntoView(int contentY, int height) {
        int top = contentY;
        int bottom = contentY + height;
        if (top < scrollOffset) {
            scrollOffset = clampScroll(top);
        } else if (bottom > scrollOffset + viewportHeight) {
            scrollOffset = clampScroll(bottom - viewportHeight);
        }
    }

    // ===== 参数有效性与控件状态 =====

    /** 个人参数是否可编辑：总闸开放 + 允许个人参数 + 玩家已取消跟随 */
    private boolean personalParametersEditable() {
        return hasSnapshot && adminEnabled && playerParametersAllowed && !followingServer;
    }

    /** 跟随框可操作：总闸开放且允许个人参数（未开放时强制勾选并禁用） */
    private boolean followBoxEditable() {
        return hasSnapshot && adminEnabled && playerParametersAllowed;
    }

    /**
     * 输入框当前应显示的权威显示值：
     * 与「跟随服务器」状态一致——跟随时显示服务器值，取消跟随时显示个人值。
     */
    private int authoritativeValue(int index) {
        return switch (index) {
            case FIELD_MIN_ELIGIBLE, FIELD_HIT_CHANCE ->
                    followingServer ? serverValue(index) : playerValue(index);
            default -> serverValue(index);
        };
    }

    private int serverValue(int index) {
        return index == FIELD_SERVER_MIN_ELIGIBLE ? serverMinEligible : serverHitChance;
    }

    private int playerValue(int index) {
        return index == FIELD_MIN_ELIGIBLE ? playerMinEligible : playerHitChance;
    }

    /** 该输入框此刻是否允许编辑 */
    private boolean fieldEditable(int index) {
        if (index == FIELD_SERVER_MIN_ELIGIBLE || index == FIELD_SERVER_HIT_CHANCE) {
            return hasSnapshot && serverAdmin;
        }
        return personalParametersEditable();
    }

    /**
     * 把权威值写进未编辑的输入框；有未提交草稿的框保留草稿，不覆盖。
     * 程序性写入用 suppressFieldEdit 屏蔽 responder，不会被当成用户编辑。
     */
    private void applyFieldValues() {
        for (int i = 0; i < FIELD_COUNT; i++) {
            EditBox box = fields[i];
            if (box == null) {
                continue;
            }
            boolean editable = fieldEditable(i);
            box.setEditable(editable);
            if (!editable) {
                // 撤权/未开放/跟随中：草稿保留在面板内部但不再作为有效值显示，也不自动提交
                dirty[i] = false;
            }
            if (!dirty[i]) {
                String target = Integer.toString(authoritativeValue(i));
                if (!target.equals(box.getValue())) {
                    setFieldText(i, target);
                }
            }
        }
        if (followBox != null) {
            setCheckboxSilently(followBox, followingServer || !playerParametersAllowed);
            followBox.active = followBoxEditable();
        }
        if (allowBox != null) {
            setCheckboxSilently(allowBox, playerParametersAllowed);
            allowBox.active = hasSnapshot && serverAdmin;
        }
        if (audioBox != null) {
            setCheckboxSilently(audioBox, prebuiltAudioEnabled);
            audioBox.active = hasSnapshot && serverAdmin;
        }
    }

    private void setFieldText(int index, String text) {
        EditBox box = fields[index];
        if (box == null) {
            return;
        }
        suppressFieldEdit = true;
        try {
            box.setValue(text);
        } finally {
            suppressFieldEdit = false;
        }
        box.setCursorPosition(text.length());
    }

    /** Forge 1.20.1 的 Checkbox 没有 setSelected：用 onPress 切换（仅在状态确实不同时） */
    private static void setCheckboxSilently(Checkbox box, boolean selected) {
        if (box.selected() != selected) {
            box.onPress();
        }
    }

    // ===== 数值校验 =====

    private static boolean isMinEligibleField(int index) {
        return index == FIELD_MIN_ELIGIBLE || index == FIELD_SERVER_MIN_ELIGIBLE;
    }

    /**
     * 解析草稿：去首尾空白后必须是纯十进制整数并落在范围内。
     * 空串、正负号、小数、非数字、解析溢出与越界一律非法——不截断、不四舍五入、不钳制。
     *
     * @return 合法值；非法返回 {@code null}
     */
    private Integer parseDraft(int index) {
        String text = drafts[index] == null ? "" : drafts[index].trim();
        if (text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < '0' || c > '9') {
                return null;
            }
        }
        long value;
        try {
            value = Long.parseLong(text);
        } catch (NumberFormatException e) {
            return null;
        }
        if (isMinEligibleField(index)) {
            return PrebuiltReuseParameters.isValidMinEligibleEntries((int) Math.min(value, Integer.MAX_VALUE))
                    ? (int) value : null;
        }
        return PrebuiltReuseParameters.isValidHitChancePercent((int) Math.min(value, Integer.MAX_VALUE))
                ? (int) value : null;
    }

    private boolean fieldHasError(int index) {
        return dirty[index] && parseDraft(index) == null;
    }

    /**
     * 提交一个输入框：不脏或非法则不发送（非法草稿原样保留并高亮）。
     * 合法时规范化文本并发送该字段的绝对目标值，不碰其他字段。
     */
    private void submitField(int index) {
        if (!dirty[index]) {
            return;
        }
        Integer value = parseDraft(index);
        if (value == null) {
            return;
        }
        // 与权威值一致：不发包，只清除脏标记并规范化显示
        dirty[index] = false;
        setFieldText(index, Integer.toString(value));
        if (!fieldEditable(index) || value == authoritativeValue(index)) {
            return;
        }
        int opcode = switch (index) {
            case FIELD_MIN_ELIGIBLE -> ReuseConfigSetMessage.OP_PLAYER_MIN_ELIGIBLE;
            case FIELD_HIT_CHANCE -> ReuseConfigSetMessage.OP_PLAYER_HIT_CHANCE;
            case FIELD_SERVER_MIN_ELIGIBLE -> ReuseConfigSetMessage.OP_SERVER_MIN_ELIGIBLE;
            default -> ReuseConfigSetMessage.OP_SERVER_HIT_CHANCE;
        };
        issueSet(opcode, null, value);
    }

    /** 提交所有脏输入框（Tab 离开、点击其他控件、关闭浮层时用） */
    private void submitAllFields() {
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (isFieldVisible(i)) {
                submitField(i);
            }
        }
    }

    private void clearDrafts() {
        for (int i = 0; i < FIELD_COUNT; i++) {
            drafts[i] = "";
            dirty[i] = false;
        }
    }

    // ===== 同步 =====

    private void sendRequest(boolean newSeq) {
        if (newSeq) {
            this.seq++;
            this.latestIssuedSeq = this.seq;
        }
        this.awaitingResponse = true;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        SelfTalkPackets.CHANNEL.sendToServer(
                new ReuseConfigRequestMessage(sessionId, latestIssuedSeq, maidUuid));
    }

    /** 点击全局默认：翻转偏好并乐观更新，随后发送目标绝对操作码（女仆维度必须为空） */
    private void toggleGlobal() {
        submitAllFields();
        boolean next = !globalEnabled;
        globalEnabled = next;
        refreshButtons();
        issueSet(next ? ReuseConfigSetMessage.OP_GLOBAL_ON : ReuseConfigSetMessage.OP_GLOBAL_OFF, null, 0);
    }

    /**
     * 点击当前女仆三态：跟随默认 → 强制开 → 强制关 → 跟随默认 循环并乐观更新，
     * 随后发送目标绝对操作码。全局默认关闭时此行<b>仍可设置</b>（服务端按叠加判定）。
     */
    private void cycleMaidState() {
        submitAllFields();
        String next;
        int opcode;
        if (ReuseConfigResponseMessage.MAID_ON.equals(maidState)) {
            next = ReuseConfigResponseMessage.MAID_OFF;
            opcode = ReuseConfigSetMessage.OP_MAID_OFF;
        } else if (ReuseConfigResponseMessage.MAID_OFF.equals(maidState)) {
            next = ReuseConfigResponseMessage.MAID_INHERIT;
            opcode = ReuseConfigSetMessage.OP_MAID_INHERIT;
        } else {
            next = ReuseConfigResponseMessage.MAID_ON;
            opcode = ReuseConfigSetMessage.OP_MAID_ON;
        }
        maidState = next;
        refreshButtons();
        issueSet(opcode, maidUuid, 0);
    }

    /** 跟随框：勾选 = 跟随服务器（个人值保留但不生效），取消 = 使用个人值 */
    private void toggleFollow() {
        submitAllFields();
        if (!followBoxEditable()) {
            // 不可操作时保持权威显示，避免复选框与状态脱节
            applyFieldValues();
            return;
        }
        boolean follow = !followingServer;
        followingServer = follow;
        issueSet(ReuseConfigSetMessage.OP_FOLLOW_SERVER, null, follow ? 1 : 0);
        applyFieldValues();
    }

    /** 允许玩家自定义参数（管理员）：目标绝对值 */
    private void toggleAllowPlayerParameters() {
        if (!hasSnapshot || !serverAdmin) {
            applyFieldValues();
            return;
        }
        playerParametersAllowed = !playerParametersAllowed;
        if (!playerParametersAllowed) {
            // 撤权：服务端会清空整张取消跟随表，本地同步按跟随显示
            followingServer = true;
        }
        applyFieldValues();
        issueSet(ReuseConfigSetMessage.OP_PLAYER_PARAMETERS_ALLOWED, null, playerParametersAllowed ? 1 : 0);
    }

    /** 「保存并复用预制语音」世界开关（管理员）：目标绝对值；关闭不清空已有音频缓存 */
    private void togglePrebuiltAudio() {
        if (!hasSnapshot || !serverAdmin) {
            applyFieldValues();
            return;
        }
        prebuiltAudioEnabled = !prebuiltAudioEnabled;
        applyFieldValues();
        issueSet(ReuseConfigSetMessage.OP_PREBUILT_AUDIO, null, prebuiltAudioEnabled ? 1 : 0);
    }

    private void issueSet(int opcode, UUID targetMaid, int value) {
        this.seq++;
        this.latestIssuedSeq = this.seq;
        this.awaitingResponse = true;
        this.pendingSet = true;
        this.ticksSinceIssue = 0;
        SelfTalkPackets.CHANNEL.sendToServer(
                new ReuseConfigSetMessage(sessionId, latestIssuedSeq, targetMaid, opcode, value));
    }

    /** 客户端每 tick 驱动：轮询快照、待确认操作的重查与超时转权威查询、输入框光标 */
    public void tick() {
        if (!open) {
            return;
        }
        // 1.20.1 的 EditBox 需要手动 tick（推进光标闪烁）；浮层里没有父 Screen 代为传播
        for (EditBox box : fields) {
            if (box != null) {
                box.tick();
            }
        }
        ticksSinceIssue++;
        if (awaitingResponse) {
            if (pendingSet) {
                if (ticksSinceIssue > SET_CONFIRM_TIMEOUT_TICKS) {
                    // 已发出的 Set 不重发（可能只是慢），改用一次权威 Request 问清真实值
                    sendRequest(true);
                }
            } else if (ticksSinceIssue >= SET_CONFIRM_TIMEOUT_TICKS) {
                // 待确认的 Request 用同一 seq 重查：升级 seq 会让慢回包永久失效
                sendRequest(false);
            }
            return;
        }
        if (ticksSinceIssue >= POLL_TICKS) {
            sendRequest(true);
        }    }

    /**
     * 应用服务端权威快照：只接受「仍打开 + session 匹配 + seq == 最新发出 + 形状合法」的响应。
     * 整包更新权威值；有未提交草稿的输入框保留草稿，不被轮询覆盖。
     */
    public void applyResponse(ReuseConfigResponseMessage msg) {
        if (!open || sessionId == null || !sessionId.equals(msg.sessionId())) {
            return;
        }
        if (msg.seq() != latestIssuedSeq) {
            return;
        }
        this.adminEnabled = msg.adminEnabled();
        this.globalEnabled = msg.globalEnabled();
        if (msg.maidUuid() != null) {
            this.maidState = msg.maidStateId();
        }
        this.serverAdmin = msg.serverAdmin();
        this.serverMinEligible = msg.serverMinEligible();
        this.serverHitChance = msg.serverHitChance();
        this.playerParametersAllowed = msg.playerParametersAllowed();
        this.followingServer = msg.followingServer();
        this.playerMinEligible = msg.playerMinEligible();
        this.playerHitChance = msg.playerHitChance();
        this.prebuiltAudioEnabled = msg.prebuiltAudioEnabled();
        this.hasSnapshot = true;
        this.awaitingResponse = false;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        // 管理员区是否出现可能改变内容高度：重算行布局与滚动范围
        layoutRows();
        this.maxScroll = Math.max(0, contentHeight - viewportHeight);
        this.scrollOffset = clampScroll(scrollOffset);
        rebuildControls();
        refreshButtons();
        applyFieldValues();
    }

    private boolean syncing() {
        return awaitingResponse;
    }

    /** 可点击判定：已收到快照 && 玩家配置未被管理员禁用（全局关闭不阻止单只覆盖） */
    private boolean configurable() {
        return hasSnapshot && adminEnabled;
    }

    private void refreshButtons() {
        if (globalButton != null) {
            globalButton.setMessage(onOffKey(globalEnabled));
            globalButton.active = configurable();
        }
        if (maidButton != null) {
            maidButton.setMessage(Component.translatable(maidStateKey(maidState)));
            maidButton.active = configurable();
        }
    }

    /** 开关状态文案键：复用既有 on/off 译文 */
    private static Component onOffKey(boolean value) {
        return Component.translatable(value
                ? "config.maid_self_talk.screen.player_settings.on"
                : "config.maid_self_talk.screen.player_settings.off");
    }

    private static String maidStateKey(String state) {
        return "config.maid_self_talk.screen.player_settings.reuse.state." + state;
    }

    // ===== 渲染 =====

    /** 绘制浮层（必须在原页面控件之后调用：浮层与其 tooltip 最后画） */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!open) {
            return;
        }
        graphics.flush();
        graphics.pose().pushPose();
        try {
            graphics.pose().translate(0.0F, 0.0F, 350.0F);
            graphics.fill(0, 0, screenWidth, screenHeight, SCRIM);
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, BORDER_COLOR);
            graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, BORDER_COLOR);
            graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, BORDER_COLOR);
            graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, BORDER_COLOR);
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.reuse.title"),
                    panelX + MARGIN, panelY + 10, TITLE_COLOR, false);
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.reuse.scope"),
                    panelX + MARGIN, panelY + 24, SUBTITLE_COLOR, false);
            renderContent(graphics, mouseX, mouseY);
            renderScrollbar(graphics);
            renderFooter(graphics);
            if (closeButton != null) {
                closeButton.render(graphics, mouseX, mouseY, partialTick);
            }
        } finally {
            graphics.pose().popPose();
        }
        graphics.flush();

        // 恢复姿态后再绘制原版 tooltip，保持在浮层之上
        if (inRect(mouseX, mouseY, closeX(), panelY + 4, CLOSE_SIZE, CLOSE_SIZE)) {
            graphics.renderTooltip(font, wrap(closeKey() + ".tooltip"), mouseX, mouseY);
            return;
        }
        if (isOverButton(mouseX, mouseY, screenY(globalRowY))) {
            graphics.renderComponentTooltip(font, controlTooltip("reuse.global.tooltip"), mouseX, mouseY);
            return;
        }
        if (isOverButton(mouseX, mouseY, screenY(maidRowY))) {
            graphics.renderComponentTooltip(font, controlTooltip("reuse.maid.tooltip"), mouseX, mouseY);
            return;
        }
        if (isOverButton(mouseX, mouseY, screenY(followRowY))) {
            graphics.renderComponentTooltip(font, followTooltip(), mouseX, mouseY);
            return;
        }
        if (serverAdmin) {
            int audioY = screenY(audioRowY);
            if (isRowVisible(audioY)
                    && inRect(mouseX, mouseY, panelX + MARGIN, audioY, panelWidth - MARGIN * 2, 18)) {
                graphics.renderComponentTooltip(font, prebuiltAudioTooltip(), mouseX, mouseY);
                return;
            }
        }
        int field = fieldIndexAt(mouseX, mouseY);
        if (field >= 0) {
            graphics.renderComponentTooltip(font, fieldTooltip(field), mouseX, mouseY);
        }
    }

    /** 滚动区内容：分区标题、控件行与两行说明全部走同一滚动偏移 */
    private void renderContent(GuiGraphics graphics, int mouseX, int mouseY) {
        if (viewportHeight <= 0) {
            return;
        }
        graphics.enableScissor(panelX + 1, listTop, panelX + panelWidth - 1, listBottom);
        drawSectionTitle(graphics, "reuse.personal_section", screenY(personalTitleY));
        renderRow(graphics, mouseX, mouseY, globalButton, screenY(globalRowY), "reuse.global");
        renderRow(graphics, mouseX, mouseY, maidButton, screenY(maidRowY), "reuse.maid");
        renderCheckboxRow(graphics, mouseX, mouseY, followBox, screenY(followRowY), "reuse.follow");
        renderFieldRow(graphics, FIELD_MIN_ELIGIBLE, "reuse.player_min_eligible", false);
        renderFieldRow(graphics, FIELD_HIT_CHANCE, "reuse.player_hit_chance", true);
        graphics.drawString(font, Component.translatable(
                        "config.maid_self_talk.screen.player_settings.reuse.personal_hint"),
                panelX + MARGIN, screenY(personalFooterY), SUBTITLE_COLOR, false);
        if (serverAdmin) {
            drawSectionTitle(graphics, "reuse.server_section", screenY(serverTitleY));
            renderFieldRow(graphics, FIELD_SERVER_MIN_ELIGIBLE, "reuse.server_min_eligible", false);
            renderFieldRow(graphics, FIELD_SERVER_HIT_CHANCE, "reuse.server_hit_chance", true);
            renderCheckboxRow(graphics, mouseX, mouseY, allowBox, screenY(allowRowY), "reuse.allow_player");
            renderCheckboxRow(graphics, mouseX, mouseY, audioBox, screenY(audioRowY), "reuse.prebuilt_audio");
        }
        graphics.disableScissor();
    }

    private void drawSectionTitle(GuiGraphics graphics, String key, int y) {
        graphics.drawString(font, Component.translatable(
                "config.maid_self_talk.screen.player_settings." + key), panelX + MARGIN, y, TITLE_COLOR, false);
    }

    /** 一行按钮控件：标签在左、状态按钮在右；无女仆上下文时当前女仆行整行不显示 */
    private void renderRow(GuiGraphics graphics, int mouseX, int mouseY,
                           Button button, int y, String labelKey) {
        boolean hidden = "reuse.maid".equals(labelKey) && maidUuid == null;
        if (hidden) {
            return;
        }
        String name = Component.translatable(
                "config.maid_self_talk.screen.player_settings." + labelKey).getString();
        graphics.drawString(font, name, panelX + MARGIN, y + 6,
                configurable() ? TITLE_COLOR : SUBTITLE_COLOR, false);
        if (button != null) {
            button.setY(y);
            button.render(graphics, mouseX, mouseY, 0.0F);
        }
    }

    private void renderCheckboxRow(GuiGraphics graphics, int mouseX, int mouseY,
                                   Checkbox box, int y, String labelKey) {
        if (box == null || !isRowVisible(y)) {
            return;
        }
        box.setY(y);
        boolean enabled = box.active;
        box.render(graphics, mouseX, mouseY, 0.0F);
        // 复选框自身带 label 时居中偏移不可控；标签统一由浮层画在框右侧
        String name = Component.translatable(
                "config.maid_self_talk.screen.player_settings." + labelKey).getString();
        graphics.drawString(font, name, panelX + MARGIN + 24, y + 6,
                enabled ? TITLE_COLOR : SUBTITLE_COLOR, false);
    }

    /** 数值行：标签、输入框（右对齐）与一行固定的范围／错误说明 */
    private void renderFieldRow(GuiGraphics graphics, int index, String labelKey, boolean percent) {
        int y = screenY(fieldContentY(index));
        if (!isRowVisible(y)) {
            return;
        }
        EditBox box = fields[index];
        boolean editable = fieldEditable(index);
        String name = Component.translatable(
                "config.maid_self_talk.screen.player_settings." + labelKey).getString();
        graphics.drawString(font, name, panelX + MARGIN, y + 5,
                editable ? TITLE_COLOR : SUBTITLE_COLOR, false);
        if (box != null) {
            box.setY(y);
            box.render(graphics, 0, 0, 0.0F);
        }
        if (percent) {
            graphics.drawString(font, "%", fieldX + fieldWidth + 4, y + 5, SUBTITLE_COLOR, false);
        }
        String hintKey = fieldHasError(index)
                ? "config.maid_self_talk.screen.player_settings.reuse.error"
                        + (isMinEligibleField(index) ? ".min_eligible" : ".hit_chance")
                : "config.maid_self_talk.screen.player_settings.reuse.range"
                        + (isMinEligibleField(index) ? ".min_eligible" : ".hit_chance");
        graphics.drawString(font, Component.translatable(hintKey), panelX + MARGIN, y + 21,
                fieldHasError(index) ? ERROR_COLOR : SUBTITLE_COLOR, false);
    }

    /** 行是否与滚动视口相交（不可见行既不绘制也不接收点击） */
    private boolean isRowVisible(int y) {
        return y + 18 > listTop && y < listBottom;
    }

    private void renderScrollbar(GuiGraphics graphics) {
        if (maxScroll <= 0 || viewportHeight <= 0) {
            return;
        }
        int trackX = trackX();
        int thumbHeight = thumbHeight();
        int thumbY = thumbY();
        graphics.fill(trackX, listTop, trackX + TRACK_WIDTH, listTop + viewportHeight, 0xFF202020);
        graphics.fill(trackX, thumbY, trackX + TRACK_WIDTH, thumbY + thumbHeight, 0xFFA0A0A0);
    }

    /** 轨道左沿：与面板右边框之间留 {@link #TRACK_MARGIN}，不贴成一条白边 */
    private int trackX() {
        return panelX + panelWidth - TRACK_MARGIN - TRACK_WIDTH;
    }

    private int thumbHeight() {
        if (maxScroll <= 0 || contentHeight <= 0) {
            return viewportHeight;
        }
        return Math.max(MIN_THUMB_HEIGHT, viewportHeight * viewportHeight / contentHeight);
    }

    private int thumbY() {
        int range = viewportHeight - thumbHeight();
        if (maxScroll <= 0 || range <= 0) {
            return listTop;
        }
        return listTop + scrollOffset * range / maxScroll;
    }

    /** 轨道命中区（含滑块拖拽与轨道点击分页） */
    private boolean inTrack(double mouseX, double mouseY) {
        return maxScroll > 0 && viewportHeight > 0
                && mouseX >= trackX() && mouseX < trackX() + TRACK_WIDTH
                && mouseY >= listTop && mouseY < listBottom;
    }

    private void renderFooter(GuiGraphics graphics) {
        if (!hasSnapshot) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.loading"),
                    panelX + MARGIN, listBottom + 6, SYNC_COLOR, false);
        } else if (syncing()) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.syncing"),
                    panelX + MARGIN, listBottom + 6, SYNC_COLOR, false);
        } else if (!adminEnabled) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.disabled.admin"),
                    panelX + MARGIN, listBottom + 6, SYNC_COLOR, false);
        }
    }

    /**
     * 控件 tooltip：金黄色实验警告常驻（可点与不可点都显示），
     * 不可点（未加载完或管理员禁用）时追加该原因。
     */
    private List<Component> controlTooltip(String detailKey) {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                        "config.maid_self_talk.screen.player_settings.reuse.warning")
                .withStyle(ChatFormatting.GOLD));
        lines.add(Component.translatable("config.maid_self_talk.screen.player_settings." + detailKey));
        if (!hasSnapshot) {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.context.loading"));
        } else if (!adminEnabled) {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.context.disabled.admin"));
        }
        return lines;
    }

    private List<Component> followTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                "config.maid_self_talk.screen.player_settings.reuse.follow.tooltip"));
        if (hasSnapshot && !playerParametersAllowed) {
            lines.add(Component.translatable(
                            "config.maid_self_talk.screen.player_settings.reuse.disabled.not_allowed")
                    .withStyle(ChatFormatting.GRAY));
        } else if (hasSnapshot && !adminEnabled) {
            lines.add(Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.disabled.admin")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** 预制语音缓存行 tooltip：不可点（非管理员）时追加原因 */
    private List<Component> prebuiltAudioTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                "config.maid_self_talk.screen.player_settings.reuse.prebuilt_audio.tooltip"));
        if (hasSnapshot && !serverAdmin) {
            lines.add(Component.translatable(
                            "config.maid_self_talk.screen.player_settings.reuse.disabled.not_admin")
                    .withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    private List<Component> fieldTooltip(int index) {
        List<Component> lines = new ArrayList<>();
        if (isMinEligibleField(index)) {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.reuse.min_eligible.tooltip"));
        } else {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.reuse.hit_chance.tooltip"));
        }
        if (hasSnapshot && !fieldEditable(index)) {
            String reason = index == FIELD_SERVER_MIN_ELIGIBLE || index == FIELD_SERVER_HIT_CHANCE
                    ? "config.maid_self_talk.screen.player_settings.reuse.disabled.not_admin"
                    : "config.maid_self_talk.screen.player_settings.reuse.disabled.follow";
            lines.add(Component.translatable(reason).withStyle(ChatFormatting.GRAY));
        }
        return lines;
    }

    /** tooltip 文本按面板宽度硬换行 */
    private List<net.minecraft.util.FormattedCharSequence> wrap(String key) {
        return font.split(Component.translatable(key), Math.min(260, panelWidth - 40));
    }

    private boolean isOverButton(double mouseX, double mouseY, int y) {
        return isRowVisible(y) && inRect(mouseX, mouseY, buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    /** 鼠标下的可见数值框：-1 = 未命中 */
    private int fieldIndexAt(double mouseX, double mouseY) {
        for (int i = 0; i < FIELD_COUNT; i++) {
            if (!isFieldVisible(i)) {
                continue;
            }
            int y = screenY(fieldContentY(i));
            if (isRowVisible(y) && inRect(mouseX, mouseY, fieldX, y, fieldWidth, 18)) {
                return i;
            }
        }
        return -1;
    }

    // ===== 事件入口（均由页面转发；浮层打开期间不向下透传） =====

    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (inRect(mouseX, mouseY, closeX(), panelY + 4, CLOSE_SIZE, CLOSE_SIZE)) {
            // 关闭前只提交合法待提交值；非法草稿直接丢弃（重开重新读取权威值）
            setFieldFocus(null);
            close();
            return true;
        }
        int clickedField = fieldIndexAt(mouseX, mouseY);
        if (clickedField >= 0 && clickedField == focusedIndex && fields[clickedField].isFocused()) {
            // 点回正在编辑的框：只移动光标/选择，不触发失焦提交
            if (fieldEditable(clickedField)) {
                fields[clickedField].mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }
        // 点击其他控件前先提交旧输入框的合法改动
        setFieldFocus(null);
        submitAllFields();
        if (isOverButton(mouseX, mouseY, screenY(globalRowY)) && maidUuid != null) {
            focusedIndex = FOCUS_GLOBAL;
            if (globalButton.active) {
                globalButton.onPress();
            }
            return true;
        }
        if (isOverButton(mouseX, mouseY, screenY(maidRowY)) && maidUuid != null) {
            focusedIndex = FOCUS_MAID;
            if (maidButton.active) {
                maidButton.onPress();
            }
            return true;
        }
        int followY = screenY(followRowY);
        if (isRowVisible(followY) && inRect(mouseX, mouseY, panelX + MARGIN, followY, panelWidth - MARGIN * 2, 18)) {
            focusedIndex = FOCUS_FOLLOW;
            toggleFollow();
            return true;
        }
        if (serverAdmin) {
            int allowY = screenY(allowRowY);
            if (isRowVisible(allowY)
                    && inRect(mouseX, mouseY, panelX + MARGIN, allowY, panelWidth - MARGIN * 2, 18)) {
                focusedIndex = FOCUS_ALLOW_PLAYER;
                toggleAllowPlayerParameters();
                return true;
            }
            int audioY = screenY(audioRowY);
            if (isRowVisible(audioY)
                    && inRect(mouseX, mouseY, panelX + MARGIN, audioY, panelWidth - MARGIN * 2, 18)) {
                focusedIndex = FOCUS_PREBUILT_AUDIO;
                togglePrebuiltAudio();
                return true;
            }
        }
        int field = clickedField;
        if (field >= 0) {
            focusedIndex = field;
            if (fieldEditable(field)) {
                setFieldFocus(field);
                fields[field].mouseClicked(mouseX, mouseY, button);
            }
            return true;
        }
        if (inTrack(mouseX, mouseY)) {
            int thumbHeight = thumbHeight();
            int thumbY = thumbY();
            if (mouseY >= thumbY && mouseY < thumbY + thumbHeight) {
                draggingThumb = true;
                dragGrabOffset = (int) mouseY - thumbY;
            } else {
                // 点击轨道空白：按一页滚动
                scrollOffset = clampScroll(scrollOffset + (mouseY < thumbY ? -viewportHeight : viewportHeight));
            }
            return true;
        }
        // 面板内其余区域：只拦截不处理
        return true;
    }

    /** 鼠标释放：无论是否命中都吞掉（浮层打开期间不向下透传） */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        draggingThumb = false;
        int field = fieldIndexAt(mouseX, mouseY);
        if (field >= 0 && fields[field] != null) {
            fields[field].mouseReleased(mouseX, mouseY, button);
        }
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!open) {
            return false;
        }
        if (draggingThumb && maxScroll > 0 && viewportHeight > 0) {
            // 拖拽中不再要求鼠标停在轨道上：滑块可被拖出范围，松手才结束
            int range = viewportHeight - thumbHeight();
            if (range > 0) {
                int thumbTop = (int) mouseY - dragGrabOffset - listTop;
                scrollOffset = clampScroll(Math.round((float) thumbTop * maxScroll / range));
            }
            return true;
        }
        int field = fieldIndexAt(mouseX, mouseY);
        if (field >= 0 && fields[field] != null && fields[field].isFocused()) {
            fields[field].mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        return true;
    }

    /**
     * 滚轮：每档 {@link #ROW_HEIGHT} × 2 像素，方向由 delta 符号决定。
     * <p>
     * 只让 delta 参与一次乘法——原写法 {@code signum(delta) * delta} 把方向乘掉，
     * 上下滚动同向（都向顶部滚）。
     */
    public boolean mouseScrolled(double delta) {
        if (!open) {
            return false;
        }
        if (delta != 0.0D && maxScroll > 0) {
            scrollOffset = clampScroll(scrollOffset - (int) Math.round(delta * ROW_HEIGHT * 2));
        }
        return true;
    }

    /**
     * 按键：Esc 先关浮层；Tab／Shift+Tab 在可见可交互控件间循环；Enter 在数值框中执行校验与提交，
     * 其余焦点控件上激活对应按钮。任何按键都被吞掉（不得落到 Prompt 输入框）。
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            setFieldFocus(null);
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            int direction = net.minecraft.client.gui.screens.Screen.hasShiftDown() ? -1 : 1;
            setFieldFocus(null);
            submitAllFields();
            moveFocus(direction);
            return true;
        }
        if (focusedIndex >= 0 && focusedIndex < FIELD_COUNT && fields[focusedIndex] != null
                && fields[focusedIndex].isFocused()) {
            if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
                // 回车：校验并提交；非法内容保留并高亮，不触发旧按钮激活
                submitField(focusedIndex);
                return true;
            }
            fields[focusedIndex].keyPressed(keyCode, scanCode, modifiers);
            // 删除/粘贴等按键路径不一定回调 responder：以输入框实际文本为准同步草稿
            String current = fields[focusedIndex].getValue();
            if (!current.equals(drafts[focusedIndex])) {
                drafts[focusedIndex] = current;
                dirty[focusedIndex] = true;
            }
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            activateFocused();
            return true;
        }
        return true;
    }

    /** 字符输入：焦点在数值框时交给输入框，其余情况一律吞掉（不得落到下方 Prompt 输入框） */
    public boolean charTyped(char codePoint, int modifiers) {
        if (!open) {
            return false;
        }
        if (focusedIndex >= 0 && focusedIndex < FIELD_COUNT && fields[focusedIndex] != null
                && fields[focusedIndex].isFocused()) {
            // 写入 responder 后立即标脏：不依赖焦点是否随后转移，草稿也不会被快照覆盖
            fields[focusedIndex].charTyped(codePoint, modifiers);
            drafts[focusedIndex] = fields[focusedIndex].getValue();
            dirty[focusedIndex] = true;
        }
        return true;
    }

    /** 切换输入框焦点：null = 全部失焦时不提交（提交由调用方显式完成） */
    private void setFieldFocus(Integer index) {
        if (focusedIndex >= 0 && focusedIndex < FIELD_COUNT && fields[focusedIndex] != null) {
            fields[focusedIndex].setFocused(false);
        }
        if (index == null) {
            return;
        }
        if (fields[index] != null && fields[index].isVisible()) {
            fields[index].setFocused(true);
        }
    }

    private void activateFocused() {
        if (focusedIndex == FOCUS_CLOSE) {
            close();
            return;
        }
        if (focusedIndex == FOCUS_GLOBAL) {
            if (globalButton != null && globalButton.active && maidUuid != null) {
                globalButton.onPress();
            }
            return;
        }
        if (focusedIndex == FOCUS_MAID) {
            if (maidButton != null && maidButton.active && maidUuid != null) {
                maidButton.onPress();
            }
            return;
        }
        if (focusedIndex == FOCUS_FOLLOW) {
            toggleFollow();
            return;
        }
        if (focusedIndex == FOCUS_ALLOW_PLAYER) {
            toggleAllowPlayerParameters();
            return;
        }
        if (focusedIndex == FOCUS_PREBUILT_AUDIO) {
            togglePrebuiltAudio();
        }
    }

    /** 焦点顺序：全局默认 → 女仆三态 → 跟随框 → 两个个人数值框 →（管理员区）→ 关闭 */
    private void moveFocus(int direction) {
        List<Integer> candidates = new ArrayList<>();
        if (globalButton != null && globalButton.active && maidUuid != null) {
            candidates.add(FOCUS_GLOBAL);
        }
        if (maidButton != null && maidButton.active && maidUuid != null) {
            candidates.add(FOCUS_MAID);
        }
        if (followBox != null && followBox.active) {
            candidates.add(FOCUS_FOLLOW);
        }
        if (fieldEditable(FIELD_MIN_ELIGIBLE)) {
            candidates.add(FIELD_MIN_ELIGIBLE);
        }
        if (fieldEditable(FIELD_HIT_CHANCE)) {
            candidates.add(FIELD_HIT_CHANCE);
        }
        if (serverAdmin) {
            if (fieldEditable(FIELD_SERVER_MIN_ELIGIBLE)) {
                candidates.add(FIELD_SERVER_MIN_ELIGIBLE);
            }
            if (fieldEditable(FIELD_SERVER_HIT_CHANCE)) {
                candidates.add(FIELD_SERVER_HIT_CHANCE);
            }
            if (allowBox != null && allowBox.active) {
                candidates.add(FOCUS_ALLOW_PLAYER);
            }
            if (audioBox != null && audioBox.active) {
                candidates.add(FOCUS_PREBUILT_AUDIO);
            }
        }
        candidates.add(FOCUS_CLOSE);
        int current = candidates.indexOf(focusedIndex);
        int nextPos = current < 0
                ? (direction > 0 ? 0 : candidates.size() - 1)
                : Math.floorMod(current + direction, candidates.size());
        focusedIndex = candidates.get(nextPos);
        if (focusedIndex >= 0 && focusedIndex < FIELD_COUNT) {
            int contentY = fieldContentY(focusedIndex);
            scrollIntoView(contentY, 18);
            rebuildControls();
            // 重建会新造开关按钮对象：恢复文案与可用性，否则 Tab 后按钮空白且恢复默认可用
            refreshButtons();
            setFieldFocus(focusedIndex);
        } else {
            scrollIntoView(focusContentY(focusedIndex), 20);
        }
    }

    /** 焦点控件的近似内容坐标（用于 Tab 时滚入可视区） */
    private int focusContentY(int index) {
        return switch (index) {
            case FOCUS_GLOBAL -> globalRowY;
            case FOCUS_MAID -> maidRowY;
            case FOCUS_FOLLOW -> followRowY;
            case FOCUS_ALLOW_PLAYER -> allowRowY;
            case FOCUS_PREBUILT_AUDIO -> audioRowY;
            default -> 0;
        };
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
