package com.maidmod.selftalk.mixin;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.github.tartaricacid.touhoulittlemaid.item.ItemFilm;
import com.maidmod.selftalk.SelfTalkHandler;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * 神龛复活成功点：TLM 用胶片复活女仆时，{@code filmToMaid} 里唯一的
 * {@code Level.addFreshEntity} 调用即「新女仆成功加入世界」的时刻。
 * <p>
 * 这里用静态 {@code Redirect} 包裹该调用：实体加入世界成功且是女仆时，
 * 通知 {@link SelfTalkHandler#recordShrineRevive(EntityMaid)} 记录复活事实，
 * 最后原样返回原结果——不改变失败时的胶片消耗、粒子、声音等上游行为，
 * 也不捕获原调用异常后重试。
 * <p>
 * {@code MaidAndItemTransformEvent.ToMaid} 发生在读 NBT、加入世界之前，
 * 不能当作复活成功通知，因此不监听该事件而重定向 {@code addFreshEntity}。
 * <p>
 * 宿主 TLM 类在 prod 不混淆，外层 {@code remap = false}（FG6 的 Mixin AP 按 searge
 * 解析 deobf 依赖的 TLM 方法必然失败）；{@code @At} 单独覆盖为 {@code remap = true}，
 * 让 AP 经 refmap 把原版方法 {@code addFreshEntity} 映射到生产名
 * {@code m_7967_}，开发环境仍写 Mojmap 名称。
 */
@Mixin(value = ItemFilm.class, remap = false)
public abstract class ItemFilmMixin {

    @Redirect(method = "filmToMaid", remap = false,
            at = @At(value = "INVOKE", remap = true,
                    target = "Lnet/minecraft/world/level/Level;addFreshEntity(Lnet/minecraft/world/entity/Entity;)Z"))
    private static boolean maid_self_talk$onShrineMaidSpawned(Level level, Entity entity) {
        boolean added = level.addFreshEntity(entity);
        if (added && entity instanceof EntityMaid maid) {
            SelfTalkHandler.recordShrineRevive(maid);
        }
        return added;
    }
}
