package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.network.InterChatConfigRequestMessage;
import com.maidmod.selftalk.network.InterChatConfigSetMessage;
import com.maidmod.selftalk.network.SelfTalkConfigRequestMessage;
import com.maidmod.selftalk.network.SelfTalkConfigSetMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import com.maidmod.selftalk.network.SleepQuietConfigRequestMessage;
import com.maidmod.selftalk.network.SleepQuietConfigSetMessage;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

import java.util.Optional;

/**
 * 玩家独立设置界面：挂在 AI 聊天输入界面上的按钮进入。
 * 包含自言自语、互聊与睡觉时安静三组开关，各自为全局+单只。
 * <p>
 * 注意「睡觉时安静」与另外两组极性相反：true = 睡觉时安静（不说话，缺省值），
 * 且全局安静时单只不可配置（单只 active = 全局 false）；该组不受管理员开关控制。
 */
@OnlyIn(Dist.CLIENT)
public class SelfTalkPlayerSettingsScreen extends Screen {

    private final EntityMaid maid;
    private boolean adminEnabled = true;
    private boolean globalEnabled = true;
    private boolean maidEnabled = true;
    private boolean interGlobalEnabled = true;
    private boolean interMaidEnabled = true;
    private boolean sleepGlobalEnabled = true;
    private boolean sleepMaidEnabled = true;
    private Button globalButton;
    private Button maidButton;
    private Button interGlobalButton;
    private Button interMaidButton;
    private Button sleepGlobalButton;
    private Button sleepMaidButton;
    private boolean dirtySelf = false;
    private boolean dirtyInter = false;
    private boolean dirtySleep = false;
    /** 重开全局后主动请求刷新：该响应须穿透 dirty 守卫（单只值以服务端为准） */
    private boolean refreshSelfExpected = false;
    private boolean refreshInterExpected = false;
    private boolean refreshSleepExpected = false;

    public SelfTalkPlayerSettingsScreen(EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.player_settings.title"));
        this.maid = maid;
    }

    @Override
    protected void init() {
        SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigRequestMessage(this.maid.getUUID()));
        SelfTalkPackets.CHANNEL.sendToServer(new InterChatConfigRequestMessage(this.maid.getUUID()));
        SelfTalkPackets.CHANNEL.sendToServer(new SleepQuietConfigRequestMessage(this.maid.getUUID()));
        int cx = this.width / 2;
        int cy = this.height / 2 - 24;
        this.globalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.global", globalEnabled),
                b -> toggleGlobal()).bounds(cx - 100, cy, 200, 20).build());
        this.maidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled),
                b -> toggleMaid()).bounds(cx - 100, cy + 20, 200, 20).build());
        this.interGlobalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.inter_global", interGlobalEnabled),
                b -> toggleInterGlobal()).bounds(cx - 100, cy + 40, 200, 20).build());
        this.interMaidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.inter_maid_toggle", interMaidEnabled),
                b -> toggleInterMaid()).bounds(cx - 100, cy + 60, 200, 20).build());
        this.sleepGlobalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.sleep_global", sleepGlobalEnabled),
                b -> toggleSleepGlobal()).bounds(cx - 100, cy + 80, 200, 20).build());
        this.sleepMaidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.sleep_maid_toggle", sleepMaidEnabled),
                b -> toggleSleepMaid()).bounds(cx - 100, cy + 100, 200, 20).build());
        refreshButtonState();
    }

    private void toggleGlobal() {
        boolean next = !globalEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigSetMessage(Optional.empty(), next));
        globalEnabled = next;
        dirtySelf = true;
        if (!next) {
            maidEnabled = false;
        } else {
            SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigRequestMessage(this.maid.getUUID()));
            refreshSelfExpected = true;
        }
        refreshButtonState();
    }

    private void toggleMaid() {
        boolean next = !maidEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigSetMessage(Optional.of(this.maid.getUUID()), next));
        maidEnabled = next;
        dirtySelf = true;
        refreshButtonState();
    }

    private void toggleInterGlobal() {
        boolean next = !interGlobalEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new InterChatConfigSetMessage(Optional.empty(), next));
        interGlobalEnabled = next;
        dirtyInter = true;
        if (!next) {
            interMaidEnabled = false;
        } else {
            SelfTalkPackets.CHANNEL.sendToServer(new InterChatConfigRequestMessage(this.maid.getUUID()));
            refreshInterExpected = true;
        }
        refreshButtonState();
    }

    private void toggleInterMaid() {
        boolean next = !interMaidEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new InterChatConfigSetMessage(Optional.of(this.maid.getUUID()), next));
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
        SelfTalkPackets.CHANNEL.sendToServer(new SleepQuietConfigSetMessage(Optional.empty(), next));
        sleepGlobalEnabled = next;
        dirtySleep = true;
        if (next) {
            sleepMaidEnabled = true;
        } else {
            SelfTalkPackets.CHANNEL.sendToServer(new SleepQuietConfigRequestMessage(this.maid.getUUID()));
            refreshSleepExpected = true;
        }
        refreshButtonState();
    }

    private void toggleSleepMaid() {
        boolean next = !sleepMaidEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new SleepQuietConfigSetMessage(Optional.of(this.maid.getUUID()), next));
        sleepMaidEnabled = next;
        dirtySleep = true;
        refreshButtonState();
    }

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

    private void refreshButtonState() {
        if (this.globalButton == null || this.maidButton == null || this.interGlobalButton == null
                || this.interMaidButton == null || this.sleepGlobalButton == null || this.sleepMaidButton == null) {
            return;
        }
        this.globalButton.active = this.adminEnabled;
        this.maidButton.active = this.adminEnabled && this.globalEnabled;
        this.interGlobalButton.active = this.adminEnabled;
        this.interMaidButton.active = this.adminEnabled && this.interGlobalEnabled;
        // 「睡觉时安静」不受管理员开关控制；单只仅全局允许说话（false）时可配置——与上两组激活逻辑相反
        this.sleepGlobalButton.active = true;
        this.sleepMaidButton.active = !this.sleepGlobalEnabled;
        this.globalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.global", globalEnabled));
        this.maidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled));
        this.interGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_global", interGlobalEnabled));
        this.interMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_maid_toggle", interMaidEnabled));
        this.sleepGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.sleep_global", sleepGlobalEnabled));
        this.sleepMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.sleep_maid_toggle", sleepMaidEnabled));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 60, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("config.maid_self_talk.screen.player_settings.maid", this.maid.getName()),
                this.width / 2, this.height / 2 - 40, 0xAAAAAA);
        if (!this.adminEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.admin_disabled"),
                    this.width / 2, this.height / 2 + 104, 0xFF5555);
        } else if (!this.globalEnabled || !this.interGlobalEnabled) {
            String key = !this.globalEnabled ? "config.maid_self_talk.screen.player_settings.global_off_hint" : "config.maid_self_talk.screen.player_settings.inter_global_off_hint";
            graphics.drawCenteredString(this.font,
                    Component.translatable(key),
                    this.width / 2, this.height / 2 + 104, 0xFFAA55);
        } else if (this.sleepGlobalEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.sleep_global_hint"),
                    this.width / 2, this.height / 2 + 104, 0xFFAA55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
