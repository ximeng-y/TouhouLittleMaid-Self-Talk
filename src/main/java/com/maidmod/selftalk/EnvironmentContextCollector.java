package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.AABB;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 环境上下文的数据采集：把 Minecraft / TLM 的游戏数据取成<b>类型化快照</b>，
 * 再交给 {@link EnvironmentContextRenderer} 按语言成句。
 * <p>
 * 职责边界：本类只取值与适配，不做抽样、不决定哪些条目进入上下文（那是
 * {@link SelfTalkContexts} 与 {@link EnvironmentContextSelection} 的事）。
 * <p>
 * 为什么复杂列表走本类而<b>不</b>走提供者的字符串取值：
 * <ul>
 *   <li>上游把物品、实体、效果拼成逗号分隔的字符串，而物品名与实体名本身可能含逗号、括号和数字，
 *       反拆整段文本必然出错；{@code MobEffectInstance.toString()} 同理不可逆解析。</li>
 *   <li>因此这里从与上游<b>相同的数据源</b>另取一份类型化快照，而不是先调提供者再自己扫第二遍——
 *       同一请求里同一份世界状态只读一次，候选判定与最终文本不会拿到两批不同的数据。</li>
 * </ul>
 * 快照按请求缓存（{@link Snapshot}）：一轮里附近实体只扫描一次，「附近生物」与「附近女仆身份」
 * 两项共用同一批结果，但两项的选择仍各自独立——共用快照<b>不会</b>让被关闭的字段重新进入候选。
 * <p>
 * 所有方法都在服务端主线程调用；快照只在本次请求内存活，不进入任何持久化结构。
 */
final class EnvironmentContextCollector {

    /** 与上游 {@code NearbyEntityMaidContexts.MAX_ENTITIES} 同值（该常量为 private，只能同值重声明） */
    private static final int MAX_ENTITIES = 20;

    private EnvironmentContextCollector() {
    }

    /** 复杂列表条目：这些 key 走类型化快照，不走提供者的字符串取值 */
    static boolean isComplex(String key) {
        return switch (key) {
            case "mainhand_item", "offhand_item", "inventory_items", "armor_items",
                 "user_mainhand", "user_armor", "effects",
                 "nearby_entities", EnvironmentContextOption.KEY_IDENTITY -> true;
            default -> false;
        };
    }

    /**
     * 采集并渲染一项复杂条目。
     *
     * @return 该条目的句子；{@code null} 表示本轮无数据（不进入候选池）
     */
    static String render(Snapshot snapshot, String key, ContextLanguage lang) {
        return switch (key) {
            case "mainhand_item", "offhand_item", "inventory_items", "armor_items" ->
                    EnvironmentContextRenderer.items(key, snapshot.items(key, lang), false, lang);
            case "user_mainhand", "user_armor" -> {
                // 上游这两项在「主人取不到」与「栏位为空」两种情况下都返回同一个常量 Empty，
                // 无法从取值本身分辨。这里结合采集时主人实体是否可获取把两者分开表达：
                // 取不到主人 → 「无法获取装备信息」；主人可取但没装备 → 「当前没有装备」。
                // 两种都进入候选（上游返回的 Empty 非空白，本来就会进），语义如实即可。
                boolean ownerUnavailable = snapshot.ownerUnavailable();
                yield EnvironmentContextRenderer.items(key,
                        ownerUnavailable ? List.of() : snapshot.items(key, lang), ownerUnavailable, lang);
            }
            case "effects" -> EnvironmentContextRenderer.effects(snapshot.effects(), lang);
            case "nearby_entities" -> EnvironmentContextRenderer.nearbyEntities(snapshot.entities(), lang);
            case EnvironmentContextOption.KEY_IDENTITY -> {
                List<EnvironmentContextRenderer.IdentitySnapshot> identities = snapshot.identities(lang);
                yield identities == null ? null : EnvironmentContextRenderer.identities(identities, lang);
            }
            default -> null;
        };
    }

    // ===== 请求内快照 =====

    /** 采集到的附近实体（内部用：保留实体引用以便第二遍筛女仆，不进入长期结构） */
    private record NearbyEntry(LivingEntity entity, String typeId, String playerName,
                               double distSelf, Double distUser) {
    }

    /**
     * 一次请求内的采集快照。同一女仆、同一 tick 的世界数据最多读一次，
     * 抽样与渲染共用同一批结果。
     */
    static final class Snapshot {

        private final EntityMaid maid;
        /** {@code null} = 尚未采集；非 null 即已采集（可能是空列表） */
        private List<NearbyEntry> nearby;
        private List<EnvironmentContextRenderer.EffectSnapshot> effects;

        Snapshot(EntityMaid maid) {
            this.maid = maid;
        }

        /**
         * 附近实体扫描（与上游 {@code NearbyEntityMaidContexts} 同源）。
         * <p>
         * 扫描范围取女仆当前工作任务的 {@code searchDimension}、存活过滤、排除自身、
         * 按到自身距离升序、最多 20 个——规则与上游逐条对齐；玩家额外带上记分板名称。
         */
        private List<NearbyEntry> nearby() {
            if (nearby != null) {
                return nearby;
            }
            AABB scanBox = maid.getTask().searchDimension(maid);
            List<LivingEntity> living = maid.level().getEntitiesOfClass(LivingEntity.class, scanBox,
                    e -> e != maid && e.isAlive());
            if (living.isEmpty()) {
                nearby = List.of();
                return nearby;
            }
            LivingEntity owner = maid.getOwner();
            List<NearbyEntry> result = new ArrayList<>(MAX_ENTITIES);
            living.stream()
                    .sorted(Comparator.comparingDouble(e -> e.distanceToSqr(maid)))
                    .limit(MAX_ENTITIES)
                    .forEach(entity -> {
                        ResourceLocation type = BuiltInRegistries.ENTITY_TYPE.getKey(entity.getType());
                        result.add(new NearbyEntry(entity,
                                type == null ? "unknown" : type.toString(),
                                entity instanceof Player player ? player.getScoreboardName() : null,
                                maid.distanceTo(entity),
                                owner == null ? null : (double) owner.distanceTo(entity)));
                    });
            nearby = List.copyOf(result);
            return nearby;
        }

        /**
         * 附近实体（渲染用值快照，<b>不含</b>运行时实体编号）。
         * <p>
         * 空列表是有效信息（上游的 {@code None}），如实说成「附近没有任何生物」。
         */
        List<EnvironmentContextRenderer.EntitySnapshot> entities() {
            List<NearbyEntry> list = nearby();
            if (list.isEmpty()) {
                return List.of();
            }
            List<EnvironmentContextRenderer.EntitySnapshot> result = new ArrayList<>(list.size());
            for (NearbyEntry entry : list) {
                result.add(new EnvironmentContextRenderer.EntitySnapshot(
                        entry.typeId(), entry.playerName(), entry.distSelf(), entry.distUser()));
            }
            return List.copyOf(result);
        }

        /**
         * 附近女仆身份快照（issue #25）：<b>先截取实体列表再筛女仆</b>，不改变原有截断语义——
         * 先筛女仆再截取会让两份名单对不上（工作量任务会扩大 {@code searchDimension} 半径）。
         * <p>
         * 名称经 {@link SelfTalkMaidNames#resolveName} 解析（命名牌名称优先，TLM 内置模型预置名称次之，
         * 其余视为未命名），语言取查询者自己的聊天语言；UUID 保留供辨认，实体编号只留内部关联。
         */
        List<EnvironmentContextRenderer.IdentitySnapshot> identities(ContextLanguage lang) {
            String language = SelfTalkMaidNames.resolveQueryLanguage(maid);
            List<EnvironmentContextRenderer.IdentitySnapshot> result = new ArrayList<>();
            for (NearbyEntry entry : nearby()) {
                if (!(entry.entity() instanceof EntityMaid nearby)) {
                    continue;
                }
                var name = SelfTalkMaidNames.resolveName(nearby, language);
                result.add(new EnvironmentContextRenderer.IdentitySnapshot(
                        name == null ? null : name.getString(), nearby.getUUID(), nearby.getId()));
            }
            return List.copyOf(result);
        }

        /**
         * 状态效果快照：类型化取标识、等级、持续时间与显示标志。
         * <p>
         * 不解析 {@code MobEffectInstance.toString()}；不新增 ambient、隐藏效果链等内容。
         * 空列表是有效信息（上游的 {@code None}）。
         */
        List<EnvironmentContextRenderer.EffectSnapshot> effects() {
            if (effects != null) {
                return effects;
            }
            List<EnvironmentContextRenderer.EffectSnapshot> result = new ArrayList<>();
            for (MobEffectInstance effect : maid.getActiveEffects()) {
                result.add(new EnvironmentContextRenderer.EffectSnapshot(
                        effect.getDescriptionId(),
                        effect.getAmplifier() + 1,
                        effect.getDuration(),
                        effect.isInfiniteDuration(),
                        effect.isVisible(),
                        effect.showIcon()));
            }
            effects = List.copyOf(result);
            return effects;
        }

        /**
         * 物品快照（保持原遍历顺序与重复堆叠，不聚合、不新增槽位／附魔／耐久／NBT）。
         * <p>
         * 候选语义与上游逐条对齐后再交给渲染：
         * <ul>
         *   <li>女仆侧 4 项（主手／副手／背包／装备）：上游空集合时返回常量 {@code Empty}（非空白，
         *       照常进入候选），因此空列表也要成句明说「没有拿东西／背包是空的」；</li>
         *   <li>主人侧 2 项（主手／装备）：上游在「主人取不到」与「栏位为空」两种情况下返回同一常量，
         *       取值层面无法分辨——这正是 {@link #ownerUnavailable()} 存在的理由，
         *       由它把「无法获取装备信息」与「当前没有装备」分开表达（两种都照常进入候选，
         *       与上游一致，不因为分辨而增删候选）。</li>
         * </ul>
         * 背包只读 {@code getAvailableBackpackInv()}，不扩展为手持与护甲。
         */
        List<EnvironmentContextRenderer.ItemSnapshot> items(String key, ContextLanguage lang) {
            boolean ownerSide = key.startsWith("user_");
            if (ownerSide) {
                LivingEntity owner = maid.getOwner();
                if (owner == null) {
                    return List.of();
                }
                return "user_mainhand".equals(key)
                        ? single(owner.getItemBySlot(EquipmentSlot.MAINHAND), lang)
                        : armor(owner, lang);
            }
            return switch (key) {
                case "mainhand_item" -> single(maid.getItemBySlot(EquipmentSlot.MAINHAND), lang);
                case "offhand_item" -> single(maid.getItemBySlot(EquipmentSlot.OFFHAND), lang);
                case "inventory_items" -> backpack(lang);
                default -> armor(maid, lang);
            };
        }

        /**
         * 主人实体当前是否不可获取：只用于主人侧 2 项的措辞区分
         * （上游对这两种情况返回同一常量，渲染侧无法自行分辨）。
         */
        boolean ownerUnavailable() {
            return maid.getOwner() == null;
        }

        private List<EnvironmentContextRenderer.ItemSnapshot> single(ItemStack stack, ContextLanguage lang) {
            return stack.isEmpty() ? List.of() : List.of(item(stack, lang));
        }

        private List<EnvironmentContextRenderer.ItemSnapshot> backpack(ContextLanguage lang) {
            List<EnvironmentContextRenderer.ItemSnapshot> result = new ArrayList<>();
            var backpack = maid.getAvailableBackpackInv();
            for (int i = 0; i < backpack.getSlots(); i++) {
                ItemStack stack = backpack.getStackInSlot(i);
                if (!stack.isEmpty()) {
                    result.add(item(stack, lang));
                }
            }
            return List.copyOf(result);
        }

        private List<EnvironmentContextRenderer.ItemSnapshot> armor(LivingEntity entity, ContextLanguage lang) {
            List<EnvironmentContextRenderer.ItemSnapshot> result = new ArrayList<>();
            entity.getArmorSlots().forEach(stack -> {
                if (!stack.isEmpty()) {
                    result.add(item(stack, lang));
                }
            });
            return List.copyOf(result);
        }

        /** 单个物品：显示名转义后加引号（玩家可控文本），数量沿用上游的 {@code 名称x数量} 写法 */
        private EnvironmentContextRenderer.ItemSnapshot item(ItemStack stack, ContextLanguage lang) {
            return new EnvironmentContextRenderer.ItemSnapshot(
                    EnvironmentContextRenderer.quote(stack.getDisplayName().getString(), lang),
                    stack.getCount());
        }
    }
}
