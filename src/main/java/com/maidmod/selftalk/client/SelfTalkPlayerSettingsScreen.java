package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.network.InterChatConfigRequestPayload;
import com.maidmod.selftalk.network.InterChatConfigSetPayload;
import com.maidmod.selftalk.network.SelfTalkConfigRequestPayload;
import com.maidmod.selftalk.network.SelfTalkConfigSetPayload;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

import java.util.Optional;

/**
 * 玩家独立设置界面：挂在 AI 聊天输入界面上的按钮进入。
 * 包含自言自语与互聊两组开关，各自为全局+单只。
 */
@OnlyIn(Dist.CLIENT)
public class SelfTalkPlayerSettingsScreen extends Screen {

    private final EntityMaid maid;
    private boolean adminEnabled = true;
    private boolean globalEnabled = true;
    private boolean maidEnabled = true;
    private boolean interGlobalEnabled = true;
    private boolean interMaidEnabled = true;
    private Button globalButton;
    private Button maidButton;
    private Button interGlobalButton;
    private Button interMaidButton;
    private boolean dirtySelf = false;
    private boolean dirtyInter = false;
    /** 重开全局后主动请求刷新：该响应须穿透 dirty 守卫（单只值以服务端为准） */
    private boolean refreshSelfExpected = false;
    private boolean refreshInterExpected = false;

    public SelfTalkPlayerSettingsScreen(EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.player_settings.title"));
        this.maid = maid;
    }

    @Override
    protected void init() {
        PacketDistributor.sendToServer(new SelfTalkConfigRequestPayload(this.maid.getUUID()));
        PacketDistributor.sendToServer(new InterChatConfigRequestPayload(this.maid.getUUID()));
        int cx = this.width / 2;
        int cy = this.height / 2 - 20;
        this.globalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.global", globalEnabled),
                b -> toggleGlobal()).bounds(cx - 100, cy, 200, 20).build());
        this.maidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled),
                b -> toggleMaid()).bounds(cx - 100, cy + 22, 200, 20).build());
        this.interGlobalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.inter_global", interGlobalEnabled),
                b -> toggleInterGlobal()).bounds(cx - 100, cy + 48, 200, 20).build());
        this.interMaidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.inter_maid_toggle", interMaidEnabled),
                b -> toggleInterMaid()).bounds(cx - 100, cy + 70, 200, 20).build());
        refreshButtonState();
    }

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

    public void applyResponse(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        // dirty 守卫丢弃的是打开界面时初始请求的迟到响应（防覆盖用户刚做的切换）；
        // 重开全局后的主动刷新响应必须接受，否则单只开关停留在关闭全局时的本地值
        if (!dirtySelf || refreshSelfExpected) {
            this.globalEnabled = globalEnabled;
            this.maidEnabled = maidEnabled;
        }
        refreshSelfExpected = false;
        refreshButtonState();
    }

    public void applyInterChatResponse(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        if (!dirtyInter || refreshInterExpected) {
            this.interGlobalEnabled = globalEnabled;
            this.interMaidEnabled = maidEnabled;
        }
        refreshInterExpected = false;
        refreshButtonState();
    }

    private void refreshButtonState() {
        if (this.globalButton == null || this.maidButton == null || this.interGlobalButton == null || this.interMaidButton == null) {
            return;
        }
        this.globalButton.active = this.adminEnabled;
        this.maidButton.active = this.adminEnabled && this.globalEnabled;
        this.interGlobalButton.active = this.adminEnabled;
        this.interMaidButton.active = this.adminEnabled && this.interGlobalEnabled;
        this.globalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.global", globalEnabled));
        this.maidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled));
        this.interGlobalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_global", interGlobalEnabled));
        this.interMaidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.inter_maid_toggle", interMaidEnabled));
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
                    this.width / 2, this.height / 2 + 80, 0xFF5555);
        } else if (!this.globalEnabled || !this.interGlobalEnabled) {
            String key = !this.globalEnabled ? "config.maid_self_talk.screen.player_settings.global_off_hint" : "config.maid_self_talk.screen.player_settings.inter_global_off_hint";
            graphics.drawCenteredString(this.font,
                    Component.translatable(key),
                    this.width / 2, this.height / 2 + 80, 0xFFAA55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
