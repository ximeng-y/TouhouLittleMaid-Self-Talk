package com.maidmod.selftalk.client;

import com.maidmod.selftalk.network.ReuseConfigRequestPayload;
import com.maidmod.selftalk.network.ReuseConfigResponsePayload;
import com.maidmod.selftalk.network.ReuseConfigSetPayload;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 💬 玩家设置页内的「预制复用」浮层（issue #91，实验性功能）。
 * <p>
 * 与「环境上下文」浮层同构的<b>现有 Screen 内部管理组件</b>：不接管 {@code setScreen}，
 * 不把按钮混进页面的焦点链，事件由页面按「浮层优先、未用也不透传」的规则转发进来。
 * <p>
 * 会话模型（与 network 三个 ReuseConfig payload 的约定）：
 * <ul>
 *   <li>每次从关闭状态打开都换新 {@code sessionId}；resize 只重算几何；</li>
 *   <li>Request 与 Set 共用递增 {@code seq}；只接受「仍打开 + session 匹配 + seq == 最新发出」的响应；</li>
 *   <li>点击即保存（发送<b>目标绝对操作码</b>），先乐观更新按钮并显示「同步中」；
 *       Set 超时不重发，转一次权威 Request 问清真实值；无待确认时每 40 tick 轮询快照。</li>
 * </ul>
 * 显示口径与响应包一致——全局默认与单只三态都是<b>已存偏好</b>（非管理员门控后的有效值）：
 * 管理员禁用时整页只读但原偏好照常显示，功能恢复后原样生效。
 * 全局默认关闭<b>不阻止</b>单只覆盖设置——单只显式开仍是有效配置，两者叠加判定由服务端负责。
 * 首次快照到达前按钮不可点击——不能拿客户端初始值覆盖尚未读到的存档设置。
 */
@OnlyIn(Dist.CLIENT)
public final class SelfTalkReusePanel {

    private static final int PANEL_BG = 0xFF404040;
    private static final int SCRIM = 0xA0000000;
    private static final int BORDER_COLOR = 0xFF808080;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFFB0B0B0;
    private static final int SYNC_COLOR = 0xFFFFAA55;
    /** 实验警告色取值与 ChatFormatting#GOLD 对齐；tooltip 行色由 withStyle(GOLD) 决定 */
    @SuppressWarnings("unused")
    private static final int EXPERIMENT_COLOR = 0xFFFFAA00;

    private static final int MARGIN = 8;
    /** 标题区总高：标题 + 作用范围 + 两行控件（含下间距） */
    private static final int HEADER_HEIGHT = 92;
    private static final int FOOTER_HEIGHT = 30;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 120;
    private static final int CLOSE_SIZE = 14;
    private static final int PANEL_MAX_WIDTH = 360;
    private static final int PANEL_MAX_HEIGHT = 170;
    private static final int SCREEN_MARGIN = 24;

    private static final int POLL_TICKS = 40;
    private static final int SET_CONFIRM_TIMEOUT_TICKS = 40;

    /** 焦点哨兵：全局默认开关 */
    private static final int FOCUS_GLOBAL = -2;
    /** 焦点哨兵：当前女仆三态 */
    private static final int FOCUS_MAID = -3;
    /** 焦点哨兵：关闭按钮 */
    private static final int FOCUS_CLOSE = -4;

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
    private String maidState = ReuseConfigResponsePayload.MAID_INHERIT;

    // ===== 布局 =====
    private int screenWidth;
    private int screenHeight;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listBottom;
    private int buttonX;

    // ===== 交互 =====
    private int focusedIndex = -1;
    private Button globalButton;
    private Button maidButton;
    private Button closeButton;

    public SelfTalkReusePanel(Font font) {
        this.font = font;
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
        this.maidState = ReuseConfigResponsePayload.MAID_INHERIT;
        this.focusedIndex = -1;
        sendRequest(true);
    }

    /** 关闭浮层：停止请求与轮询，之后到达的回包一概忽略（已发出的 Set 不撤销） */
    public void close() {
        this.open = false;
        this.focusedIndex = -1;
    }

    // ===== 布局 =====

    /**
     * 重算几何并重建按钮；不触碰会话、序号、偏好与同步标志——resize 只改布局，
     * 不能把客户端初始值覆盖到已收到的服务端设置上。
     */
    public void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.panelWidth = Math.min(PANEL_MAX_WIDTH, screenWidth - SCREEN_MARGIN);
        this.panelHeight = Math.min(PANEL_MAX_HEIGHT, screenHeight - SCREEN_MARGIN);
        this.panelX = (screenWidth - panelWidth) / 2;
        this.panelY = (screenHeight - panelHeight) / 2;
        this.listBottom = panelY + panelHeight - FOOTER_HEIGHT;
        this.buttonX = panelX + panelWidth - MARGIN - BUTTON_WIDTH;

        this.globalButton = Button.builder(Component.empty(), b -> toggleGlobal())
                .bounds(buttonX, rowY(0), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.maidButton = Button.builder(Component.empty(), b -> cycleMaidState())
                .bounds(buttonX, rowY(1), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.closeButton = Button.builder(Component.translatable(closeKey()), b -> close())
                .bounds(closeX(), panelY + 4, CLOSE_SIZE, CLOSE_SIZE)
                .build();
        refreshButtons();
    }

    /** 控件行 Y：全局默认 +46、当前女仆 +70 */
    private int rowY(int index) {
        return panelY + 46 + index * 24;
    }

    private int closeX() {
        return panelX + panelWidth - 4 - CLOSE_SIZE;
    }

    private static String closeKey() {
        return "config.maid_self_talk.screen.player_settings.context.close";
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
        PacketDistributor.sendToServer(new ReuseConfigRequestPayload(sessionId, latestIssuedSeq, maidUuid));
    }

    /** 点击全局默认：翻转偏好并乐观更新，随后发送目标绝对操作码（女仆维度必须为空） */
    private void toggleGlobal() {
        boolean next = !globalEnabled;
        globalEnabled = next;
        refreshButtons();
        issueSet(next ? ReuseConfigSetPayload.OP_GLOBAL_ON : ReuseConfigSetPayload.OP_GLOBAL_OFF, null);
    }

    /**
     * 点击当前女仆三态：跟随默认 → 强制开 → 强制关 → 跟随默认 循环并乐观更新，
     * 随后发送目标绝对操作码。全局默认关闭时此行<b>仍可设置</b>（服务端按叠加判定）。
     */
    private void cycleMaidState() {
        String next = switch (maidState) {
            case ReuseConfigResponsePayload.MAID_ON -> ReuseConfigResponsePayload.MAID_OFF;
            case ReuseConfigResponsePayload.MAID_OFF -> ReuseConfigResponsePayload.MAID_INHERIT;
            default -> ReuseConfigResponsePayload.MAID_ON;
        };
        maidState = next;
        refreshButtons();
        int opcode = switch (next) {
            case ReuseConfigResponsePayload.MAID_ON -> ReuseConfigSetPayload.OP_MAID_ON;
            case ReuseConfigResponsePayload.MAID_OFF -> ReuseConfigSetPayload.OP_MAID_OFF;
            default -> ReuseConfigSetPayload.OP_MAID_INHERIT;
        };
        issueSet(opcode, maidUuid);
    }

    private void issueSet(int opcode, UUID targetMaid) {
        this.seq++;
        this.latestIssuedSeq = this.seq;
        this.awaitingResponse = true;
        this.pendingSet = true;
        this.ticksSinceIssue = 0;
        PacketDistributor.sendToServer(new ReuseConfigSetPayload(sessionId, latestIssuedSeq, targetMaid, opcode));
    }

    /** 客户端每 tick 驱动：轮询快照、待确认操作的重查与超时转权威查询 */
    public void tick() {
        if (!open) {
            return;
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
        }
    }

    /**
     * 应用服务端权威快照：只接受「仍打开 + session 匹配 + seq == 最新发出 + 形状合法」的响应。
     * 整包更新（管理员状态 + 全局默认 + 单只三态），不部分应用。
     */
    public void applyResponse(ReuseConfigResponsePayload payload) {
        if (!open || sessionId == null || !sessionId.equals(payload.sessionId())) {
            return;
        }
        if (payload.seq() != latestIssuedSeq) {
            return;
        }
        this.adminEnabled = payload.adminEnabled();
        this.globalEnabled = payload.globalEnabled();
        if (payload.maidUuid() != null) {
            this.maidState = payload.maidStateId();
        }
        this.hasSnapshot = true;
        this.awaitingResponse = false;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        refreshButtons();
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
            renderRow(graphics, mouseX, mouseY, globalButton, rowY(0),
                    "config.maid_self_talk.screen.player_settings.reuse.global", maidUuid == null);
            renderRow(graphics, mouseX, mouseY, maidButton, rowY(1),
                    "config.maid_self_talk.screen.player_settings.reuse.maid", maidUuid == null);
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
        if (isOverRow(mouseX, mouseY, rowY(0))) {
            graphics.renderComponentTooltip(font, controlTooltip("reuse.global.tooltip"), mouseX, mouseY);
            return;
        }
        if (isOverRow(mouseX, mouseY, rowY(1))) {
            graphics.renderComponentTooltip(font, controlTooltip("reuse.maid.tooltip"), mouseX, mouseY);
        }
    }

    /** 一行控件：标签在左、状态按钮在右；无女仆上下文时当前女仆行整行置灰不显示按钮 */
    private void renderRow(GuiGraphics graphics, int mouseX, int mouseY,
                           Button button, int y, String labelKey, boolean hidden) {
        if (hidden) {
            return;
        }
        String name = Component.translatable(labelKey).getString();
        graphics.drawString(font, name, panelX + MARGIN, y + 6,
                configurable() ? TITLE_COLOR : SUBTITLE_COLOR, false);
        if (button != null) {
            button.setY(y);
            button.render(graphics, mouseX, mouseY, 0.0F);
        }
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

    /** tooltip 文本按面板宽度硬换行 */
    private List<net.minecraft.util.FormattedCharSequence> wrap(String key) {
        return font.split(Component.translatable(key), Math.min(260, panelWidth - 40));
    }

    private boolean isOverRow(double mouseX, double mouseY, int y) {
        return inRect(mouseX, mouseY, buttonX, y, BUTTON_WIDTH, BUTTON_HEIGHT);
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
            close();
            return true;
        }
        if (isOverRow(mouseX, mouseY, rowY(0)) && maidUuid != null) {
            focusedIndex = FOCUS_GLOBAL;
            if (globalButton.active) {
                globalButton.onPress();
            }
            return true;
        }
        if (isOverRow(mouseX, mouseY, rowY(1)) && maidUuid != null) {
            focusedIndex = FOCUS_MAID;
            if (maidButton.active) {
                maidButton.onPress();
            }
            return true;
        }
        // 面板内其余区域：只拦截不处理
        return true;
    }

    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return open;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        return open;
    }

    public boolean mouseScrolled(double delta) {
        return open;
    }

    /**
     * 按键：Esc 先关浮层；Tab／Shift+Tab 在 全局开关 → 女仆三态 → 关闭按钮 间循环；
     * Enter／Space 激活焦点按钮。任何按键都被吞掉（不得落到 Prompt 输入框）。
     */
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!open) {
            return false;
        }
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close();
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_TAB) {
            moveFocus(net.minecraft.client.gui.screens.Screen.hasShiftDown() ? -1 : 1);
            return true;
        }
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER
                || keyCode == GLFW.GLFW_KEY_SPACE) {
            activateFocused();
            return true;
        }
        return true;
    }

    /** 字符输入：浮层没有文本框，一律吞掉（不得落到下方 Prompt 输入框） */
    public boolean charTyped(char codePoint, int modifiers) {
        return open;
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
        }
    }

    /** 焦点移动：只在可操作控件间循环；无女仆上下文时跳过女仆三态 */
    private void moveFocus(int direction) {
        List<Integer> candidates = new ArrayList<>();
        if (globalButton != null && globalButton.active && maidUuid != null) {
            candidates.add(FOCUS_GLOBAL);
        }
        if (maidButton != null && maidButton.active && maidUuid != null) {
            candidates.add(FOCUS_MAID);
        }
        candidates.add(FOCUS_CLOSE);
        int current = candidates.indexOf(focusedIndex);
        int nextPos = current < 0
                ? (direction > 0 ? 0 : candidates.size() - 1)
                : Math.floorMod(current + direction, candidates.size());
        focusedIndex = candidates.get(nextPos);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
