package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.compat.curios.CuriosCompat;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraftforge.common.util.LazyOptional;
import top.theillusivec4.curios.api.CuriosApi;
import top.theillusivec4.curios.api.type.capability.ICuriosItemHandler;
import top.theillusivec4.curios.api.type.inventory.ICurioStacksHandler;
import top.theillusivec4.curios.api.type.inventory.IDynamicStackHandler;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Curios 饰品读取：本 mod 中<b>唯一</b>直接引用 {@code top.theillusivec4.curios} 的生产代码。
 * <p>
 * 隔离边界（全部是硬约束）：
 * <ul>
 *   <li>Curios 只作为 {@code compileOnly} 编译期依赖，运行时不打包、不声明前置——未安装 Curios 时
 *       功能门（{@link EnvironmentContextOption.Gate#CURIOS}）先拦截，本类的读取方法不会被调用；
 *       可用性判定读 TLM 自己的 {@link CuriosCompat} 标记，不接触任何 Curios 类型；</li>
 *   <li>返回值只含项目类型、JDK 类型与 Minecraft {@code ItemStack}，Curios 接口类型不外泄；</li>
 *   <li>物品一律取副本，不修改原容器，也不长期缓存实体、handler 或物品引用。</li>
 * </ul>
 * 读取范围只到「哪些槽位放了什么、各多少个」：不推断饰品效果、附魔、耐久或佩戴行为。
 */
final class CuriosAccessoryReader {

    private CuriosAccessoryReader() {
    }

    /** Curios 是否已安装（TLM 的 Curios 兼容是否初始化）。不接触任何 Curios 类型，未安装时也可安全调用 */
    static boolean isCuriosLoaded() {
        return CuriosCompat.isLoaded();
    }

    /** Curios 已安装且 TLM 的女仆 Curios 支持未被管理员关闭。同样不接触 Curios 类型 */
    static boolean isMaidCuriosEnabled() {
        return CuriosCompat.isLoadedOrEnable();
    }

    /**
     * 读取实体的 Curios 功能槽与外观槽。
     * <p>
     * 遍历顺序固定：先按槽位类型标识排序，再按该类型内的槽位索引顺序——同一实体同一 tick 的输出稳定，
     * 不随 {@code getCurios()} 的映射实现抖动。槽位数量以 API 实际返回为准，不硬编码戒指、项链等
     * 类型或槽位上限。空槽位跳过，重复物品与原有数量保留（不按名称合并）。
     * <p>
     * 不用 {@code getEquippedCurios()} 作为入口：它不包含外观槽。
     *
     * @return 功能槽与外观槽两组物品；{@code null} 表示该实体没有 Curios handler，
     * 或 handler 存在但功能槽与外观槽总数为零（两种情况都是「本轮无数据」）
     */
    static AccessoryStacks read(LivingEntity entity) {
        // Forge 侧是 LazyOptional（NeoForge 侧为 Optional），其余行为两线一致
        LazyOptional<ICuriosItemHandler> handler = CuriosApi.getCuriosInventory(entity);
        if (!handler.isPresent()) {
            return null;
        }
        ICuriosItemHandler curiosHandler = handler.orElse(null);
        if (curiosHandler == null) {
            return null;
        }
        Map<String, ICurioStacksHandler> curios = curiosHandler.getCurios();
        if (curios == null || curios.isEmpty()) {
            return null;
        }
        List<String> identifiers = new ArrayList<>(curios.keySet());
        Collections.sort(identifiers);
        List<ItemStack> functional = new ArrayList<>();
        List<ItemStack> cosmetic = new ArrayList<>();
        int slotCount = 0;
        for (String identifier : identifiers) {
            ICurioStacksHandler stacksHandler = curios.get(identifier);
            if (stacksHandler == null) {
                continue;
            }
            slotCount += copyNonEmpty(stacksHandler.getStacks(), functional);
            slotCount += copyNonEmpty(stacksHandler.getCosmeticStacks(), cosmetic);
        }
        if (slotCount == 0) {
            return null;
        }
        return new AccessoryStacks(List.copyOf(functional), List.copyOf(cosmetic));
    }

    /** 追加该槽位处理器中的非空物品副本，返回该处理器的槽位总数（含空槽） */
    private static int copyNonEmpty(IDynamicStackHandler stacks, List<ItemStack> out) {
        if (stacks == null) {
            return 0;
        }
        int slots = stacks.getSlots();
        for (int i = 0; i < slots; i++) {
            ItemStack stack = stacks.getStackInSlot(i);
            if (stack != null && !stack.isEmpty()) {
                out.add(stack.copy());
            }
        }
        return slots;
    }

    /**
     * 饰品读取结果：功能槽与外观槽各自一组物品，<b>两组互不合并</b>。
     * <p>
     * 两组都为空是有效信息（「有槽位但都没戴」），只有 {@code null} 才代表本轮无数据。
     */
    record AccessoryStacks(List<ItemStack> functional, List<ItemStack> cosmetic) {
    }
}
