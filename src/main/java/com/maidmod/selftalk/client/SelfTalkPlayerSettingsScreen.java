package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.maidmod.selftalk.network.SelfTalkConfigRequestMessage;
import com.maidmod.selftalk.network.SelfTalkConfigSetMessage;
import com.maidmod.selftalk.network.SelfTalkPackets;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.api.distmarker.OnlyIn;

/**
 * 玩家独立设置界面：挂在 AI 聊天输入界面（AIChatScreen）上的小方块按钮进入。
 * <p>
 * 管理员关闭玩家配置时（PLAYER_OPTION_ENABLED = false），开关置灰且视为启用。
 */
@OnlyIn(Dist.CLIENT)
public class SelfTalkPlayerSettingsScreen extends Screen {

    private final EntityMaid maid;
    /** 管理员是否允许玩家配置 */
    private boolean adminEnabled = true;
    /** 玩家独立设置：是否触发自言自语 */
    private boolean selfTalkEnabled = true;
    private Button toggleButton;

    public SelfTalkPlayerSettingsScreen(EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.player_settings.title"));
        this.maid = maid;
    }

    @Override
    protected void init() {
        // 向服务端请求当前设置（管理员开关 + 玩家当前值）
        SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigRequestMessage());
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.toggleButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.value", selfTalkEnabled),
                b -> toggle()).bounds(cx - 100, cy, 200, 20).build());
        refreshButtonState();
    }

    private void toggle() {
        boolean next = !selfTalkEnabled;
        SelfTalkPackets.CHANNEL.sendToServer(new SelfTalkConfigSetMessage(next));
        selfTalkEnabled = next;
        refreshButtonState();
    }

    /** 服务端响应到达后刷新（网络层回调） */
    public void applyResponse(boolean adminEnabled, boolean selfTalkEnabled) {
        this.adminEnabled = adminEnabled;
        this.selfTalkEnabled = selfTalkEnabled;
        refreshButtonState();
    }

    private void refreshButtonState() {
        if (this.toggleButton == null) {
            return;
        }
        this.toggleButton.active = this.adminEnabled;
        this.toggleButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.value", selfTalkEnabled));
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        super.render(graphics, mouseX, mouseY, partialTick);
        graphics.drawCenteredString(this.font, this.title, this.width / 2, this.height / 2 - 40, 0xFFFFFF);
        graphics.drawCenteredString(this.font,
                Component.translatable("config.maid_self_talk.screen.player_settings.maid", this.maid.getName()),
                this.width / 2, this.height / 2 - 20, 0xAAAAAA);
        if (!this.adminEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.admin_disabled"),
                    this.width / 2, this.height / 2 + 30, 0xFF5555);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
