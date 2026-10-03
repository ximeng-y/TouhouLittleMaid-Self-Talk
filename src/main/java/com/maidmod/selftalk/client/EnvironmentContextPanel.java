package com.maidmod.selftalk.client;

import com.maidmod.selftalk.EnvironmentContextMode;
import com.maidmod.selftalk.EnvironmentContextOption;
import com.maidmod.selftalk.EnvironmentContextSettings;
import com.maidmod.selftalk.network.EnvironmentContextConfigRequestMessage;
import com.maidmod.selftalk.network.EnvironmentContextConfigResponseMessage;
import com.maidmod.selftalk.network.EnvironmentContextConfigSetMessage;
import com.maidmod.selftalk.network.EnvironmentContextNaturalLanguageSetMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 💬 玩家设置页内的「环境上下文」浮层（issue #14）。
 * <p>
 * 本类是<b>现有 Screen 内部管理的组件</b>，不是另一个 Screen：不接管 {@code setScreen}，
 * 不把 32 个行按钮混进页面的焦点链，事件由页面按「浮层优先、未用也不透传」的规则转发进来。
 * <p>
 * 会话模型（与服务端的约定，见 network 包的三个 payload）：
 * <ul>
 *   <li>每次从关闭状态打开都换新 {@code sessionId}；窗口 resize 保留当前会话，只重算几何；</li>
 *   <li>Request 与 Set 共用递增 {@code seq}；只接受「浮层仍打开 + session 匹配 + seq == 最新发出」的响应，
 *       因此连续点击、迟到回包、关闭重开都不会让旧状态覆盖新选择；</li>
 *   <li>点击即保存（发送<b>目标绝对模式</b>），先乐观更新按钮并显示「同步中」，
 *       未经服务端回包不宣称已保存；关闭浮层不撤销已发出的 Set；</li>
 *   <li>无待确认操作时每 {@link #POLL_TICKS} tick 取一次快照，使管理员状态的变化能反映到界面；
 *       待确认 Request 用<b>同一 seq</b> 重查（不升级 seq，避免慢回包永久失效）；
 *       超过 {@link #SET_CONFIRM_TIMEOUT_TICKS} tick 未确认的 Set 转为一次权威 Request，不自动重发 Set。</li>
 * </ul>
 * 首次快照到达前行按钮不可点击——不能拿目录默认值覆盖尚未读到的存档设置。
 */
@OnlyIn(Dist.CLIENT)
public final class EnvironmentContextPanel {

    /** 面板底色：不透明灰，不用 0xC0 半透明色代替（面板后的页面内容不得透出） */
    private static final int PANEL_BG = 0xFF404040;
    /** 面板外暗色遮罩（遮罩可半透明，面板自身不可） */
    private static final int SCRIM = 0xA0000000;
    private static final int BORDER_COLOR = 0xFF808080;
    private static final int TITLE_COLOR = 0xFFFFFFFF;
    private static final int SUBTITLE_COLOR = 0xFFB0B0B0;
    private static final int FOOTER_COLOR = 0xFFB0B0B0;
    /** 同步中 / 读取中 的强调色（与页面既有提示色一致） */
    private static final int SYNC_COLOR = 0xFFFFAA55;

    private static final int HEADER_HEIGHT = 78;
    private static final int FOOTER_HEIGHT = 30;
    private static final int ROW_HEIGHT = 24;
    private static final int BUTTON_HEIGHT = 20;
    private static final int BUTTON_WIDTH = 132;
    /** 滚动条轨道宽度 */
    private static final int TRACK_WIDTH = 6;
    /** 轨道与面板右边距 */
    private static final int TRACK_MARGIN = 4;
    /** 行按钮与轨道之间的间距 */
    private static final int TRACK_GAP = 6;
    /** 名称区左边距 */
    private static final int NAME_MARGIN = 8;
    /** 滚轮每档滚动的行数 */
    private static final int SCROLL_ROWS_PER_NOTCH = 3;
    /** 滚动条滑块最小高度（太短的滑块不好拖） */
    private static final int MIN_THUMB_HEIGHT = 16;
    /** 关闭按钮边长 */
    private static final int CLOSE_SIZE = 14;

    private static final int PANEL_MAX_WIDTH = 480;
    private static final int PANEL_MAX_HEIGHT = 360;
    private static final int SCREEN_MARGIN = 24;

    /** 无待确认操作时的周期快照间隔（client tick） */
    private static final int POLL_TICKS = 40;
    /** 待确认操作的超时（client tick）：Set 超时转权威 Request，Request 超时用同一 seq 重查 */
    private static final int SET_CONFIRM_TIMEOUT_TICKS = 40;

    /** 焦点哨兵：关闭按钮 */
    private static final int FOCUS_CLOSE = EnvironmentContextOption.ALL.size();
    /** 焦点哨兵：环境信息自然语言化总开关（在标题区、不随列表滚动） */
    private static final int FOCUS_TOGGLE = -2;

    private final Font font;

    // ===== 会话与同步状态（resize 保留，关闭重开时重置） =====
    private boolean open;
    private UUID sessionId;
    private long seq;
    /** 最新发出的序号：只有携带该序号的响应才会被接受 */
    private long latestIssuedSeq;
    /** 是否有已发出但未确认的操作 */
    private boolean awaitingResponse;
    /** 未确认的操作是否为 Set（决定超时后是转 Request 还是用同一 seq 重查） */
    private boolean pendingSet;
    /** 距最近一次发出操作经过的 client tick */
    private int ticksSinceIssue;
    /** 是否已收到过至少一次权威快照（未收到前所有行不可点击） */
    private boolean hasSnapshot;
    private boolean adminEnabled = true;
    /** 32 项模式（已存偏好补默认值后的值，非管理员强制后的有效模式） */
    private final Map<String, EnvironmentContextMode> modes = new LinkedHashMap<>();
    /** 32 项功能可用性 */
    private final Map<String, EnvironmentContextSettings.Availability> availability = new LinkedHashMap<>();
    /**
     * 环境信息自然语言化的<b>已存偏好</b>（不是管理员门控后的有效值）。
     * <p>
     * 首次权威快照到达前一律为 false 且开关不可点——客户端初始值绝不能写回覆盖服务端设置。
     */
    private boolean naturalLanguageEnabled = false;

    // ===== 布局（每次 layout 重算） =====
    private int screenWidth;
    private int screenHeight;
    private int panelX;
    private int panelY;
    private int panelWidth;
    private int panelHeight;
    private int listTop;
    private int listBottom;
    /** 列表区裁剪高度（= listBottom - listTop） */
    private int viewportHeight;
    private int contentHeight;
    private int maxScroll;
    private int buttonX;

    // ===== 交互状态 =====
    private int scrollOffset;
    private boolean draggingThumb;
    /** 拖拽起点相对滑块顶部的偏移，拖动时保证光标与滑块相对位置不跳变 */
    private int dragGrabOffset;
    /** 当前焦点：0..31 = 行，{@link #FOCUS_TOGGLE} = 总开关，{@link #FOCUS_CLOSE} = 关闭按钮，-1 = 无 */
    private int focusedIndex = -1;
    /** 已构建的行按钮（与目录同序，恒 32 个） */
    private final List<Button> rowButtons = new ArrayList<>();
    /** 标题区的「环境信息自然语言化」总开关（不随列表滚动，也不进底页焦点链） */
    private Button naturalLanguageButton;
    private Button closeButton;

    public EnvironmentContextPanel(Font font) {
        this.font = font;
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            this.modes.put(option.key(), option.defaultMode());
            this.availability.put(option.key(), EnvironmentContextSettings.Availability.PROVIDER_MISSING);
        }
    }

    public boolean isOpen() {
        return open;
    }

    // ===== 开关 =====

    /**
     * 打开浮层：换新会话并立即请求快照。
     * <p>
     * 「从关闭状态打开」才换 session——resize 走 {@link #layout} 不经过这里，因此保留当前会话。
     * 重开时清掉上一次会话的乐观值与待确认标记：宁可显示「读取中」，也不用旧会话的猜测值冒充服务端状态。
     */
    public void open() {
        this.open = true;
        this.sessionId = UUID.randomUUID();
        this.seq = 0;
        this.latestIssuedSeq = 0;
        this.awaitingResponse = false;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        this.hasSnapshot = false;
        this.scrollOffset = 0;
        this.draggingThumb = false;
        this.focusedIndex = -1;
        this.naturalLanguageEnabled = false;
        for (EnvironmentContextOption option : EnvironmentContextOption.ALL) {
            this.modes.put(option.key(), option.defaultMode());
            this.availability.put(option.key(), EnvironmentContextSettings.Availability.PROVIDER_MISSING);
        }
        sendRequest(true);
    }

    /** 关闭浮层：停止一切请求与周期查询，之后到达的回包一概忽略（已发出的 Set 不撤销） */
    public void close() {
        this.open = false;
        this.draggingThumb = false;
        this.focusedIndex = -1;
    }

    // ===== 布局 =====

    /**
     * 重算几何、钳制滚动偏移并重建按钮。
     * <p>
     * 不触碰会话、序号、32 项状态、总开关与同步标志——窗口 resize 只应改变布局，
     * 不能把默认值覆盖到已经收到的服务端设置上，也不能重置未确认的乐观值。
     */
    public void layout(int screenWidth, int screenHeight) {
        this.screenWidth = screenWidth;
        this.screenHeight = screenHeight;
        this.panelWidth = Math.min(PANEL_MAX_WIDTH, screenWidth - SCREEN_MARGIN);
        this.panelHeight = Math.min(PANEL_MAX_HEIGHT, screenHeight - SCREEN_MARGIN);
        this.panelX = (screenWidth - panelWidth) / 2;
        this.panelY = (screenHeight - panelHeight) / 2;
        this.listTop = panelY + HEADER_HEIGHT;
        this.listBottom = panelY + panelHeight - FOOTER_HEIGHT;
        this.viewportHeight = Math.max(0, listBottom - listTop);
        this.contentHeight = EnvironmentContextOption.ALL.size() * ROW_HEIGHT;
        this.maxScroll = Math.max(0, contentHeight - viewportHeight);
        this.scrollOffset = clampScroll(scrollOffset);
        this.buttonX = panelX + panelWidth - TRACK_MARGIN - TRACK_WIDTH - TRACK_GAP - BUTTON_WIDTH;

        this.rowButtons.clear();
        for (int i = 0; i < EnvironmentContextOption.ALL.size(); i++) {
            int index = i;
            Button button = Button.builder(Component.empty(), b -> cycleMode(index))
                    .bounds(buttonX, rowY(index), BUTTON_WIDTH, BUTTON_HEIGHT)
                    .build();
            // 行按钮不入 Screen 的子控件表：焦点与事件都由浮层自己管，避免污染原页面焦点链
            this.rowButtons.add(button);
        }
        // 总开关放在标题区固定位置（列表上方、不随滚动），沿用列表的左右对齐关系
        this.naturalLanguageButton = Button.builder(Component.empty(), b -> toggleNaturalLanguage())
                .bounds(buttonX, toggleY(), BUTTON_WIDTH, BUTTON_HEIGHT)
                .build();
        this.closeButton = Button.builder(Component.translatable(closeKey()), b -> close())
                .bounds(closeX(), closeY(), CLOSE_SIZE, CLOSE_SIZE)
                .build();
        refreshButtons();
    }

    /** 总开关的行 Y：标题（10）→ 作用范围（24）→ 总开关（38），与行高对齐 */
    private int toggleY() {
        return panelY + 38;
    }

    private int clampScroll(int value) {
        return Math.max(0, Math.min(value, maxScroll));
    }

    /** 行的绘制 Y：列表顶 + 序号 × 行高 − 滚动偏移 */
    private int rowY(int index) {
        return listTop + index * ROW_HEIGHT - scrollOffset;
    }

    private int closeX() {
        return panelX + panelWidth - 4 - CLOSE_SIZE;
    }

    private int closeY() {
        return panelY + 4;
    }

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

    /** 行是否与列表可视区相交（决定是否绘制与接受点击） */
    private boolean rowVisible(int index) {
        int y = rowY(index);
        return y + ROW_HEIGHT > listTop && y < listBottom;
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
        SelfTalkPackets.CHANNEL.sendToServer(new EnvironmentContextConfigRequestMessage(sessionId, latestIssuedSeq));
    }

    /**
     * 点击一行：推进三态并乐观更新，随后发送目标绝对模式。
     * <p>
     * 未收到首次快照、管理员禁用或该行不可用时按钮本就不接受点击，这里不再重复判定
     * （事件入口已按同一条件过滤）。
     */
    private void cycleMode(int index) {
        EnvironmentContextOption option = EnvironmentContextOption.ALL.get(index);
        EnvironmentContextMode next = modes.getOrDefault(option.key(), option.defaultMode()).next();
        modes.put(option.key(), next);
        refreshButtons();
        this.seq++;
        this.latestIssuedSeq = this.seq;
        this.awaitingResponse = true;
        this.pendingSet = true;
        this.ticksSinceIssue = 0;
        SelfTalkPackets.CHANNEL.sendToServer(new EnvironmentContextConfigSetMessage(
                sessionId, latestIssuedSeq, option.key(), next.id()));
    }

    /**
     * 点击总开关：翻转自然语言化偏好并乐观更新，随后发送<b>目标绝对布尔值</b>。
     * <p>
     * 与行按钮同一套会话机制（同一个 sessionId/seq、同一份超时与重查规则），
     * 因此连续点击、迟到回包、关闭重开都不会让旧状态覆盖新选择。
     * 未收到首次快照或管理员禁用时按钮不接受点击，事件入口已按同一条件过滤。
     */
    private void toggleNaturalLanguage() {
        boolean next = !naturalLanguageEnabled;
        naturalLanguageEnabled = next;
        refreshButtons();
        this.seq++;
        this.latestIssuedSeq = this.seq;
        this.awaitingResponse = true;
        this.pendingSet = true;
        this.ticksSinceIssue = 0;
        SelfTalkPackets.CHANNEL.sendToServer(new EnvironmentContextNaturalLanguageSetMessage(
                sessionId, latestIssuedSeq, next));
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
     * 应用服务端权威快照。
     * <p>
     * 只接受「浮层仍打开 + session 匹配 + seq 等于最新发出」的响应，其余一律丢弃：
     * 关闭后到达的回包、旧会话回包、被新操作取代的旧序号回包都不能改写界面。
     * 符合条件时整包更新（32 项模式 + 总开关 + 管理员状态一起应用，不部分更新），
     * 避免半新半旧的界面。
     */
    public void applyResponse(EnvironmentContextConfigResponseMessage payload) {
        if (!open || sessionId == null || !sessionId.equals(payload.getSessionId())) {
            return;
        }
        if (payload.getSeq() != latestIssuedSeq || !payload.isValidShape()) {
            return;
        }
        for (EnvironmentContextConfigResponseMessage.Row row : payload.getRows()) {
            EnvironmentContextMode mode = EnvironmentContextMode.fromId(row.modeId());
            if (mode != null) {
                modes.put(row.contextKey(), mode);
            }
            availability.put(row.contextKey(),
                    EnvironmentContextSettings.Availability.fromId(row.availability()));
        }
        this.adminEnabled = payload.isAdminEnabled();
        this.naturalLanguageEnabled = payload.isNaturalLanguageEnabled();
        this.hasSnapshot = true;
        this.awaitingResponse = false;
        this.pendingSet = false;
        this.ticksSinceIssue = 0;
        refreshButtons();
    }

    /** 是否处于「已发出未确认」状态（底部显示同步中） */
    private boolean syncing() {
        return awaitingResponse;
    }

    // ===== 按钮状态 =====

    private void refreshButtons() {
        for (int i = 0; i < rowButtons.size(); i++) {
            EnvironmentContextOption option = EnvironmentContextOption.ALL.get(i);
            Button button = rowButtons.get(i);
            button.setMessage(Component.translatable(modeKey(modes.getOrDefault(option.key(), option.defaultMode()))));
            // 禁用按钮仍显示原三态文字，只置灰——绝不能把它显示成「必定不进入」，
            // 那会把「管理员禁用」误读成「玩家选了永不包括」
            button.active = rowConfigurable(option);
        }
        if (naturalLanguageButton != null) {
            naturalLanguageButton.setMessage(onOffKey(naturalLanguageEnabled));
            naturalLanguageButton.active = toggleConfigurable();
        }
    }

    /** 该行当前是否可点击：已收到快照 && 玩家配置未被管理员禁用 && 该行可用 */
    private boolean rowConfigurable(EnvironmentContextOption option) {
        return hasSnapshot && adminEnabled
                && availability.get(option.key()) == EnvironmentContextSettings.Availability.AVAILABLE;
    }

    /** 总开关是否可点击：已收到快照 && 玩家配置未被管理员禁用 */
    private boolean toggleConfigurable() {
        return hasSnapshot && adminEnabled;
    }

    /** 开关状态文案键：复用既有 on/off 译文，不在 Java 里硬编码 UI 中文 */
    private static Component onOffKey(boolean value) {
        return Component.translatable(value
                ? "config.maid_self_talk.screen.player_settings.on"
                : "config.maid_self_talk.screen.player_settings.off");
    }

    /**
     * 该行的禁用原因文案键；可用（或尚未加载）时返回 null。
     * <p>
     * 管理员总闸优先：{@code PLAYER_OPTION_ENABLED=false} 时整页禁用，无需逐行区分原因。
     */
    private String disabledReasonKey(EnvironmentContextOption option) {
        if (!hasSnapshot) {
            return "config.maid_self_talk.screen.player_settings.context.loading";
        }
        if (!adminEnabled) {
            return "config.maid_self_talk.screen.player_settings.context.disabled.admin";
        }
        return switch (availability.get(option.key())) {
            case AVAILABLE -> null;
            case EVENT_MASTER_DISABLED -> "config.maid_self_talk.screen.player_settings.context.disabled.event_master";
            case HURT_DISABLED -> "config.maid_self_talk.screen.player_settings.context.disabled.hurt";
            case SELF_DISABLED -> "config.maid_self_talk.screen.player_settings.context.disabled.self";
            case IDENTITY_NOT_REGISTERED -> "config.maid_self_talk.screen.player_settings.context.disabled.identity";
            case PROVIDER_MISSING -> "config.maid_self_talk.screen.player_settings.context.disabled.provider";
        };
    }

    private static String closeKey() {
        return "config.maid_self_talk.screen.player_settings.context.close";
    }

    private static String modeKey(EnvironmentContextMode mode) {
        return "config.maid_self_talk.screen.player_settings.context.mode." + mode.id();
    }

    // ===== 渲染 =====

    /**
     * 绘制浮层（必须在原页面控件之后调用：浮层与其 tooltip 最后画）。
     * <p>
     * 列表使用裁剪区域，行不会画进标题或底部区；禁用 tooltip 基于按钮矩形手工判定，
     * 不依赖禁用 Button 是否仍触发原生 tooltip（{@code isMouseOver} 会因 {@code active=false} 直接返回 false）。
     */
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (!open) {
            return;
        }
        // 分开提交底页与浮层；同 Z 下不透明填充仍盖不住底页文字，必须整体抬层。
        graphics.flush();
        graphics.pose().pushPose();
        try {
            // 高于底页内容，低于原版 tooltip 的 Z=400。
            graphics.pose().translate(0.0F, 0.0F, 350.0F);
            graphics.fill(0, 0, screenWidth, screenHeight, SCRIM);
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + panelHeight, PANEL_BG);
            // 1px 边框（无 shader 依赖，用四条 fill 拼）
            graphics.fill(panelX, panelY, panelX + panelWidth, panelY + 1, BORDER_COLOR);
            graphics.fill(panelX, panelY + panelHeight - 1, panelX + panelWidth, panelY + panelHeight, BORDER_COLOR);
            graphics.fill(panelX, panelY, panelX + 1, panelY + panelHeight, BORDER_COLOR);
            graphics.fill(panelX + panelWidth - 1, panelY, panelX + panelWidth, panelY + panelHeight, BORDER_COLOR);

            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.title"),
                    panelX + NAME_MARGIN, panelY + 10, TITLE_COLOR, false);
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.scope"),
                    panelX + NAME_MARGIN, panelY + 24, SUBTITLE_COLOR, false);
            renderNaturalLanguageRow(graphics, mouseX, mouseY);

            renderList(graphics, mouseX, mouseY);
            renderFooter(graphics);
            renderCloseButton(graphics, mouseX, mouseY, partialTick);
        } finally {
            graphics.pose().popPose();
        }
        graphics.flush();

        // 恢复姿态后再绘制原版 tooltip，保持在浮层之上。
        if (inRect(mouseX, mouseY, closeX(), closeY(), CLOSE_SIZE, CLOSE_SIZE)) {
            graphics.renderTooltip(font, wrap("config.maid_self_talk.screen.player_settings.context.close.tooltip"),
                    mouseX, mouseY);
            return;
        }
        if (isOverToggle(mouseX, mouseY)) {
            graphics.renderComponentTooltip(font, toggleTooltip(), mouseX, mouseY);
            return;
        }
        int hovered = rowIndexAt(mouseX, mouseY);
        if (hovered >= 0) {
            EnvironmentContextOption option = EnvironmentContextOption.ALL.get(hovered);
            String reason = disabledReasonKey(option);
            if (reason != null) {
                graphics.renderTooltip(font, wrap(reason), mouseX, mouseY);
            } else if (nameTruncated(option)) {
                // 名称省略号截断时，悬停给出完整自然语言名称
                graphics.renderTooltip(font,
                        font.split(Component.translatable(nameKey(option)), Math.min(260, panelWidth - 40)),
                        mouseX, mouseY);
            }
        }
    }

    /** 右上角关闭按钮（面板自绘，不进页面焦点链；随布局重建） */
    private void renderCloseButton(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (closeButton == null) {
            return;
        }
        closeButton.render(graphics, mouseX, mouseY, partialTick);
    }

    /**
     * 标题区的「环境信息自然语言化」总开关：标签在左、状态按钮在右（沿用列表的左右对齐关系）。
     * <p>
     * 标签与按钮都不随列表滚动，也不在底页 Screen 的控件表里——点击与键盘事件都由浮层自己吞掉。
     */
    private void renderNaturalLanguageRow(GuiGraphics graphics, int mouseX, int mouseY) {
        if (naturalLanguageButton == null) {
            return;
        }
        int y = toggleY();
        String name = Component.translatable(
                "config.maid_self_talk.screen.player_settings.context.natural_language").getString();
        int nameWidth = buttonX - NAME_MARGIN - 4 - (panelX + NAME_MARGIN);
        graphics.drawString(font, ellipsize(name, nameWidth), panelX + NAME_MARGIN, y + 6,
                toggleConfigurable() ? TITLE_COLOR : SUBTITLE_COLOR, false);
        naturalLanguageButton.setY(y);
        naturalLanguageButton.render(graphics, mouseX, mouseY, 0.0F);
    }

    private void renderList(GuiGraphics graphics, int mouseX, int mouseY) {
        if (viewportHeight <= 0) {
            return;
        }
        graphics.enableScissor(panelX + 1, listTop, panelX + panelWidth - 1, listBottom);
        for (int i = 0; i < rowButtons.size(); i++) {
            if (!rowVisible(i)) {
                continue;
            }
            EnvironmentContextOption option = EnvironmentContextOption.ALL.get(i);
            int y = rowY(i);
            // 名称区宽度：按钮左沿到列表右边距之间的可用宽度（留 4px 间隙）
            String name = Component.translatable(nameKey(option)).getString();
            int nameWidth = buttonX - NAME_MARGIN - 4 - (panelX + NAME_MARGIN);
            graphics.drawString(font, ellipsize(name, nameWidth), panelX + NAME_MARGIN, y + 6,
                    rowConfigurable(option) ? TITLE_COLOR : SUBTITLE_COLOR, false);
            // 行按钮按当前滚动偏移重定位后绘制；鼠标坐标用屏幕外值可避免禁用态产生悬停高亮
            Button button = rowButtons.get(i);
            button.setY(y);
            button.render(graphics, mouseX, mouseY, 0.0F);
        }
        graphics.disableScissor();
        renderScrollbar(graphics);
    }

    private void renderScrollbar(GuiGraphics graphics) {
        int trackHeight = viewportHeight;
        graphics.fill(trackX(), listTop, trackX() + TRACK_WIDTH, listTop + trackHeight, 0xFF202020);
        if (maxScroll <= 0) {
            return;
        }
        int height = thumbHeight();
        int y = thumbY();
        graphics.fill(trackX(), y, trackX() + TRACK_WIDTH, y + height, 0xFFA0A0A0);
    }

    private void renderFooter(GuiGraphics graphics) {
        graphics.drawString(font, Component.translatable(
                        "config.maid_self_talk.screen.player_settings.context.footer"),
                panelX + NAME_MARGIN, listBottom + 6, FOOTER_COLOR, false);
        if (!hasSnapshot) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.loading"),
                    panelX + NAME_MARGIN, listBottom + 17, SYNC_COLOR, false);
        } else if (syncing()) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.syncing"),
                    panelX + NAME_MARGIN, listBottom + 17, SYNC_COLOR, false);
        } else if (!adminEnabled) {
            graphics.drawString(font, Component.translatable(
                            "config.maid_self_talk.screen.player_settings.context.disabled.admin"),
                    panelX + NAME_MARGIN, listBottom + 17, SYNC_COLOR, false);
        }
    }

    /** 名称键（本 mod 本地化；界面绝不直接显示 TLM key） */
    private static String nameKey(EnvironmentContextOption option) {
        return EnvironmentContextSettings.nameKey(option);
    }

    /** 名称是否会被省略号截断（决定要不要挂完整名称 tooltip） */
    private boolean nameTruncated(EnvironmentContextOption option) {
        String name = Component.translatable(nameKey(option)).getString();
        int nameWidth = buttonX - NAME_MARGIN - 4 - (panelX + NAME_MARGIN);
        return font.width(name) > nameWidth;
    }

    /** 超宽时省略号截断（按渲染宽度而非字符数） */
    private String ellipsize(String text, int maxWidth) {
        if (maxWidth <= 0 || font.width(text) <= maxWidth) {
            return text;
        }
        String ellipsis = "…";
        int ellipsisWidth = font.width(ellipsis);
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            int charWidth = font.width(String.valueOf(text.charAt(i)));
            if (width + charWidth + ellipsisWidth > maxWidth) {
                break;
            }
            sb.append(text.charAt(i));
            width += charWidth;
        }
        return sb + ellipsis;
    }

    /** tooltip 文本按面板宽度硬换行（本 mod 的禁用说明都比较长） */
    private List<net.minecraft.util.FormattedCharSequence> wrap(String key) {
        return font.split(Component.translatable(key), Math.min(260, panelWidth - 40));
    }

    /** 鼠标是否悬停在总开关按钮上（禁用按钮的 isMouseOver 因 active=false 返回 false，故手工判定） */
    private boolean isOverToggle(double mouseX, double mouseY) {
        return naturalLanguageButton != null
                && inRect(mouseX, mouseY, buttonX, toggleY(), BUTTON_WIDTH, BUTTON_HEIGHT);
    }

    /**
     * 总开关的 tooltip：金黄色实验警告常驻（可点与不可点都显示），
     * 不可点（未加载完或管理员禁用）时追加该原因。
     * <p>
     * 警告是提示性质，用金黄色（{@link ChatFormatting#GOLD}）而非「同步中」的橙色；
     * 禁用原因沿用行级同一套文案键，不另写一份措辞。
     */
    private List<Component> toggleTooltip() {
        List<Component> lines = new ArrayList<>();
        lines.add(Component.translatable(
                        "config.maid_self_talk.screen.player_settings.context.natural_language.warning")
                .withStyle(ChatFormatting.GOLD));
        if (!hasSnapshot) {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.context.loading"));
        } else if (!adminEnabled) {
            lines.add(Component.translatable(
                    "config.maid_self_talk.screen.player_settings.context.disabled.admin"));
        }
        return lines;
    }

    // ===== 事件入口（均由页面转发） =====

    /** 行命中测试：只对可见裁剪区域内的按钮生效；返回 -1 表示未命中 */
    private int rowIndexAt(double mouseX, double mouseY) {
        if (!open || mouseY < listTop || mouseY >= listBottom) {
            return -1;
        }
        for (int i = 0; i < rowButtons.size(); i++) {
            if (!rowVisible(i)) {
                continue;
            }
            int y = rowY(i);
            if (mouseX >= buttonX && mouseX < buttonX + BUTTON_WIDTH
                    && mouseY >= y && mouseY < y + BUTTON_HEIGHT) {
                return i;
            }
        }
        return -1;
    }

    private boolean inTrack(double mouseX, double mouseY) {
        return mouseX >= trackX() && mouseX < trackX() + TRACK_WIDTH
                && mouseY >= listTop && mouseY < listBottom;
    }

    /** 鼠标按下：面板外只拦截（不关闭、不透传），面板内按 关闭／总开关／行／轨道 分派 */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        if (button != 0) {
            return true;
        }
        if (inRect(mouseX, mouseY, closeX(), closeY(), CLOSE_SIZE, CLOSE_SIZE)) {
            close();
            return true;
        }
        if (isOverToggle(mouseX, mouseY)) {
            focusedIndex = FOCUS_TOGGLE;
            if (naturalLanguageButton.active) {
                naturalLanguageButton.onPress();
            }
            return true;
        }
        int index = rowIndexAt(mouseX, mouseY);
        if (index >= 0) {
            Button row = rowButtons.get(index);
            focusedIndex = index;
            if (row.active) {
                row.onPress();
            }
            return true;
        }
        if (inTrack(mouseX, mouseY)) {
            int thumbTop = thumbY();
            int height = thumbHeight();
            if (mouseY >= thumbTop && mouseY < thumbTop + height) {
                draggingThumb = true;
                dragGrabOffset = (int) mouseY - thumbTop;
            } else {
                // 点击轨道空白：按一页滚动
                scrollOffset = clampScroll(scrollOffset + (mouseY < thumbTop ? -viewportHeight : viewportHeight));
            }
            return true;
        }
        return true;
    }

    /** 鼠标释放：无论是否命中都吞掉（浮层打开期间不向下透传） */
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (!open) {
            return false;
        }
        draggingThumb = false;
        return true;
    }

    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (!open) {
            return false;
        }
        if (draggingThumb) {
            int range = viewportHeight - thumbHeight();
            if (range > 0 && maxScroll > 0) {
                int thumbTop = (int) mouseY - dragGrabOffset - listTop;
                scrollOffset = clampScroll(Math.round((float) thumbTop * maxScroll / range));
            }
        }
        return true;
    }

    /**
     * 滚轮：每档 {@link #SCROLL_ROWS_PER_NOTCH} 行。
     * <p>
     * 双线签名不同（NeoForge 传横纵两个滚动量，Forge 1.20.1 只有一个），页面适配后把纵向量传进来：
     */
    public boolean mouseScrolled(double delta) {
        if (!open || delta == 0.0D || maxScroll <= 0) {
            return open;
        }
        int rows = (int) Math.signum(delta) * SCROLL_ROWS_PER_NOTCH;
        scrollOffset = clampScroll(scrollOffset - rows * ROW_HEIGHT);
        return true;
    }

    /**
     * 按键：Esc 先关浮层；Tab／Shift+Tab 在可操作行按钮与关闭按钮间循环；Enter／Space 激活焦点按钮。
     * <p>
     * 任何按键都被吞掉（返回 true），未使用的键也不得落到 Prompt 输入框或旧开关上。
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
        if (focusedIndex == FOCUS_TOGGLE) {
            if (naturalLanguageButton != null && naturalLanguageButton.active) {
                naturalLanguageButton.onPress();
            }
            return;
        }
        if (focusedIndex >= 0 && focusedIndex < rowButtons.size()
                && rowButtons.get(focusedIndex).active) {
            rowButtons.get(focusedIndex).onPress();
        }
    }

    /**
     * 焦点移动：只在<b>可操作</b>的行按钮、总开关与关闭按钮之间循环
     * （禁用项跳过，但仍参与不可用时的兜底：全部禁用时焦点落在关闭按钮上）。
     * <p>
     * 顺序为 总开关 → 可操作条目 → 关闭按钮：总开关在列表之前，与界面自上而下的读序一致。
     * <p>
     * 聚焦到屏幕外的行时自动滚动使其可见。
     */
    private void moveFocus(int direction) {
        List<Integer> candidates = new ArrayList<>();
        if (naturalLanguageButton != null && naturalLanguageButton.active) {
            candidates.add(FOCUS_TOGGLE);
        }
        for (int i = 0; i < rowButtons.size(); i++) {
            if (rowButtons.get(i).active) {
                candidates.add(i);
            }
        }
        candidates.add(FOCUS_CLOSE);
        if (candidates.size() == 1) {
            focusedIndex = FOCUS_CLOSE;
            return;
        }
        int current = candidates.indexOf(focusedIndex);
        int nextPos = current < 0
                ? (direction > 0 ? 0 : candidates.size() - 1)
                : Math.floorMod(current + direction, candidates.size());
        focusedIndex = candidates.get(nextPos);
        if (focusedIndex >= 0 && focusedIndex < rowButtons.size()) {
            scrollIntoView(focusedIndex);
        }
    }

    /** 把指定行滚入可视区（保持整行可见，避免半行卡在边缘） */
    private void scrollIntoView(int index) {
        int rowTop = index * ROW_HEIGHT;
        int rowBottom = rowTop + ROW_HEIGHT;
        if (rowTop < scrollOffset) {
            scrollOffset = rowTop;
        } else if (rowBottom > scrollOffset + viewportHeight) {
            scrollOffset = rowBottom - viewportHeight;
        }
        scrollOffset = clampScroll(scrollOffset);
    }

    private static boolean inRect(double mouseX, double mouseY, int x, int y, int width, int height) {
        return mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
    }
}
