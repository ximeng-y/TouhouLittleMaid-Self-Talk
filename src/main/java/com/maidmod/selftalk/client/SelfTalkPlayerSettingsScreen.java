package com.maidmod.selftalk.client;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
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
 * 玩家独立设置界面：挂在 AI 聊天输入界面（AIChatScreen）上的小方块按钮进入。
 * <p>
 * 两个选项：① 我的所有女仆是否触发自言自语（全局）；② 这只女仆是否触发（单只）。
 * 管理员关闭玩家配置时（PLAYER_OPTION_ENABLED = false），两个开关均置灰且视为启用；
 * 全局关闭时单只开关置灰（全局关则单只必然不触发）。
 */
@OnlyIn(Dist.CLIENT)
public class SelfTalkPlayerSettingsScreen extends Screen {

    private final EntityMaid maid;
    /** 管理员是否允许玩家配置 */
    private boolean adminEnabled = true;
    /** 玩家全局设置：自己的所有女仆是否触发自言自语 */
    private boolean globalEnabled = true;
    /** 这只女仆的单只有效值（全局 && 单只名单） */
    private boolean maidEnabled = true;
    private Button globalButton;
    private Button maidButton;

    public SelfTalkPlayerSettingsScreen(EntityMaid maid) {
        super(Component.translatable("config.maid_self_talk.screen.player_settings.title"));
        this.maid = maid;
    }

    @Override
    protected void init() {
        // 向服务端请求当前设置（管理员开关 + 全局值 + 这只女仆的有效值）
        PacketDistributor.sendToServer(new SelfTalkConfigRequestPayload(this.maid.getUUID()));
        int cx = this.width / 2;
        int cy = this.height / 2;
        this.globalButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.global", globalEnabled),
                b -> toggleGlobal()).bounds(cx - 100, cy, 200, 20).build());
        this.maidButton = this.addRenderableWidget(Button.builder(
                Component.translatable("config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled),
                b -> toggleMaid()).bounds(cx - 100, cy + 30, 200, 20).build());
        refreshButtonState();
    }

    private void toggleGlobal() {
        boolean next = !globalEnabled;
        PacketDistributor.sendToServer(new SelfTalkConfigSetPayload(Optional.empty(), next));
        globalEnabled = next;
        if (!next) {
            // 全局关闭后单只必然不触发，本地单只值同步为关
            maidEnabled = false;
        } else {
            // 重新打开全局时，单只值取决于服务端名单，重新拉取避免本地漂移
            PacketDistributor.sendToServer(new SelfTalkConfigRequestPayload(this.maid.getUUID()));
        }
        refreshButtonState();
    }

    private void toggleMaid() {
        boolean next = !maidEnabled;
        PacketDistributor.sendToServer(new SelfTalkConfigSetPayload(Optional.of(this.maid.getUUID()), next));
        maidEnabled = next;
        refreshButtonState();
    }

    /** 服务端响应到达后刷新（网络层回调） */
    public void applyResponse(boolean adminEnabled, boolean globalEnabled, boolean maidEnabled) {
        this.adminEnabled = adminEnabled;
        this.globalEnabled = globalEnabled;
        this.maidEnabled = maidEnabled;
        refreshButtonState();
    }

    private void refreshButtonState() {
        if (this.globalButton == null || this.maidButton == null) {
            return;
        }
        this.globalButton.active = this.adminEnabled;
        // 管理员关闭或全局关闭时，单只开关置灰
        this.maidButton.active = this.adminEnabled && this.globalEnabled;
        this.globalButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.global", globalEnabled));
        this.maidButton.setMessage(Component.translatable(
                "config.maid_self_talk.screen.player_settings.maid_toggle", maidEnabled));
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
                    this.width / 2, this.height / 2 + 55, 0xFF5555);
        } else if (!this.globalEnabled) {
            graphics.drawCenteredString(this.font,
                    Component.translatable("config.maid_self_talk.screen.player_settings.global_off_hint"),
                    this.width / 2, this.height / 2 + 55, 0xFFAA55);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
