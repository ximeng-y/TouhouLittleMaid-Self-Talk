package com.maidmod.selftalk.citest;

import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.fml.ModList;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * CI2 可选依赖兼容 fixture。
 *
 * <p>这个类故意不导入 Cloth Config 或 Curios 的任何类型。主 Agent 只需把
 * {@code client.optional_compat} / {@code server.optional_compat} 分发到这里；
 * 基础 profile 即使没有可选 mod，也不会因为加载测试类而解析可选 API。</p>
 *
 * <p>精确接口：</p>
 * <ul>
 *     <li>客户端：{@code executeClient(action, client)}，支持
 *     {@code operation=open_config}。</li>
 *     <li>服务端：{@code executeServer(action, server)}，支持
 *     {@code operation=set_curios|clear_curios|snapshot_curios}；
 *     {@code target} 为 {@code owner} 或 {@code maid}，女仆目标需要
 *     {@code maidId}，主人目标可用 {@code player} 指定名字。</li>
 * </ul>
 */
public final class CiOptionalCompat {

    private static final String CURIOS_API = "top.theillusivec4.curios.api.CuriosApi";
    private static final String CLOTH_OPEN =
            "com.github.tartaricacid.touhoulittlemaid.compat.cloth.ClothConfigCompat";
    private static final String SELF_TALK_CLOTH_CONFIG =
            "com.maidmod.selftalk.client.SelfTalkClothConfig";

    private CiOptionalCompat() {
    }

    /**
     * 客户端 fixture 入口。参数只使用 JSON 与 Object，避免专服装载该类时链接客户端类型。
     */
    public static JsonObject executeClient(JsonObject action, Object client) {
        String operation = CiJson.str(action, "operation", "");
        if (!"open_config".equals(operation)) {
            throw new IllegalArgumentException("unknown optional client operation: " + operation);
        }
        if (!ModList.get().isLoaded("cloth_config")) {
            throw new IllegalStateException("cloth_config is not loaded");
        }
        try {
            Class<?> opener = Class.forName(CLOTH_OPEN);
            Method method = opener.getMethod("openConfigScreen");
            method.invoke(null);
            return configScreenSnapshot(client);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("failed to open the registered Cloth Config screen", e);
        }
    }

    /**
     * 服务端 fixture 入口。Curios 类只在本方法被调用且 optional profile 已安装时反射解析。
     */
    public static JsonObject executeServer(JsonObject action, MinecraftServer server) {
        String operation = CiJson.str(action, "operation", "");
        LivingEntity target = resolveTarget(action, server);
        return switch (operation) {
            case "set_curios" -> setCurios(action, target);
            case "clear_curios" -> clearCurios(action, target);
            case "snapshot_curios" -> snapshotCurios(action, target);
            default -> throw new IllegalArgumentException("unknown optional server operation: " + operation);
        };
    }

    private static JsonObject configScreenSnapshot(Object client) {
        JsonObject out = new JsonObject();
        out.addProperty("operation", "open_config");
        out.addProperty("clothLoaded", ModList.get().isLoaded("cloth_config"));
        out.addProperty("registrationClassLoaded", isClassLoaded(SELF_TALK_CLOTH_CONFIG));

        Object screen = readField(client, "screen");
        out.addProperty("screenOpen", screen != null);
        out.addProperty("screenClass", screen == null ? "" : screen.getClass().getName());
        JsonArray labels = new JsonArray();
        boolean entryVisible = false;
        if (screen != null) {
            Object title = invoke(screen, "getTitle");
            addComponentText(labels, title);
            entryVisible = containsSelfTalkLabel(textOf(title));
            Object children = invoke(screen, "children");
            if (children instanceof Iterable<?> iterable) {
                for (Object child : iterable) {
                    if (!visible(child)) {
                        continue;
                    }
                    Object message = invoke(child, "getMessage");
                    String text = textOf(message);
                    String key = translatableKey(message);
                    if (!text.isBlank()) {
                        labels.add(text);
                    }
                    if (!key.isBlank()) {
                        labels.add(key);
                    }
                    entryVisible |= containsSelfTalkLabel(text) || key.contains("config.maid_self_talk");
                }
            }
        }
        out.add("labels", labels);
        out.addProperty("entryVisible", entryVisible);
        return out;
    }

    private static JsonObject setCurios(JsonObject action, LivingEntity target) {
        String itemId = CiJson.str(action, "item", "minecraft:diamond");
        int count = Math.max(1, Math.min(64, CiJson.integer(action, "count", 1)));
        boolean cosmetic = "cosmetic".equalsIgnoreCase(CiJson.str(action, "slotGroup", "functional"));
        Item item = BuiltInRegistries.ITEM.get(ResourceLocation.parse(itemId));
        if (item == null || item == Items.AIR) {
            throw new IllegalArgumentException("unknown item: " + itemId);
        }
        InventoryView inventory = readInventory(target);
        for (SlotView slot : inventory.slots()) {
            if (slot.cosmetic() == cosmetic) {
                setStack(slot.handler(), slot.index(), new ItemStack(item, count));
                JsonObject out = snapshotCurios(action, target);
                out.addProperty("operation", "set_curios");
                out.addProperty("itemId", itemId);
                out.addProperty("itemName", new ItemStack(item, count).getDisplayName().getString());
                out.addProperty("count", count);
                out.addProperty("slotGroup", cosmetic ? "cosmetic" : "functional");
                out.addProperty("slotIndex", slot.index());
                return out;
            }
        }
        throw new IllegalStateException("Curios has no " + (cosmetic ? "cosmetic" : "functional") + " slot");
    }

    private static JsonObject clearCurios(JsonObject action, LivingEntity target) {
        InventoryView inventory = readInventory(target);
        for (SlotView slot : inventory.slots()) {
            setStack(slot.handler(), slot.index(), ItemStack.EMPTY);
        }
        JsonObject out = snapshotCurios(action, target);
        out.addProperty("operation", "clear_curios");
        return out;
    }

    private static JsonObject snapshotCurios(JsonObject action, LivingEntity target) {
        InventoryView inventory = readInventory(target);
        JsonObject out = new JsonObject();
        out.addProperty("operation", "snapshot_curios");
        out.addProperty("target", CiJson.str(action, "target", ""));
        out.addProperty("entityUuid", target.getUUID().toString());
        out.addProperty("curiosLoaded", inventory.loaded());
        out.addProperty("slotCount", inventory.slots().size());
        JsonArray functional = new JsonArray();
        JsonArray cosmetic = new JsonArray();
        for (SlotView slot : inventory.slots()) {
            ItemStack stack = stack(slot.handler(), slot.index());
            if (stack.isEmpty()) {
                continue;
            }
            JsonObject item = new JsonObject();
            item.addProperty("index", slot.index());
            item.addProperty("id", itemId(stack));
            item.addProperty("name", stack.getDisplayName().getString());
            item.addProperty("count", stack.getCount());
            (slot.cosmetic() ? cosmetic : functional).add(item);
        }
        out.add("functional", functional);
        out.add("cosmetic", cosmetic);
        out.addProperty("functionalCount", functional.size());
        out.addProperty("cosmeticCount", cosmetic.size());
        return out;
    }

    private static LivingEntity resolveTarget(JsonObject action, MinecraftServer server) {
        String target = CiJson.str(action, "target", "owner").toLowerCase();
        if ("maid".equals(target)) {
            int maidId = CiJson.integer(action, "maidId", -1);
            for (EntityMaid maid : server.overworld().getEntitiesOfClass(EntityMaid.class,
                    new net.minecraft.world.phys.AABB(-30000000, -64, -30000000,
                            30000000, 320, 30000000))) {
                if (maid.getId() == maidId) {
                    return maid;
                }
            }
            throw new IllegalArgumentException("maid not found: " + maidId);
        }
        String playerName = CiJson.str(action, "player", "OwnerBot");
        ServerPlayer player = server.getPlayerList().getPlayerByName(playerName);
        if (player == null) {
            throw new IllegalArgumentException("player not online: " + playerName);
        }
        return player;
    }

    private static InventoryView readInventory(LivingEntity entity) {
        try {
            Class<?> api = Class.forName(CURIOS_API);
            Object optional = invokeStatic(api, "getCuriosInventory", entity);
            Object handler = optional instanceof Optional<?> value ? value.orElse(null) : null;
            if (handler == null) {
                throw new IllegalStateException("Curios inventory handler is absent");
            }
            Object curios = invoke(handler, "getCurios");
            if (!(curios instanceof Map<?, ?> map)) {
                throw new IllegalStateException("Curios inventory map is unavailable");
            }
            List<Map.Entry<?, ?>> entries = new ArrayList<>(map.entrySet());
            entries.sort(Comparator.comparing(entry -> String.valueOf(entry.getKey())));
            List<SlotView> slots = new ArrayList<>();
            for (Map.Entry<?, ?> entry : entries) {
                Object stacksHandler = entry.getValue();
                if (stacksHandler == null) {
                    continue;
                }
                addSlots(slots, stacksHandler, "getStacks", false);
                addSlots(slots, stacksHandler, "getCosmeticStacks", true);
            }
            return new InventoryView(true, List.copyOf(slots));
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Curios API is unavailable", e);
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new IllegalStateException("Curios inventory reflection failed", e);
        }
    }

    private static void addSlots(List<SlotView> output, Object handler, String getter, boolean cosmetic)
            throws ReflectiveOperationException {
        Object stacks = invokeRequired(handler, getter);
        int count = ((Number) invokeRequired(stacks, "getSlots")).intValue();
        for (int index = 0; index < count; index++) {
            output.add(new SlotView(stacks, index, cosmetic));
        }
    }

    private static ItemStack stack(Object handler, int index) {
        try {
            Object result = invokeRequired(handler, "getStackInSlot", index);
            return result instanceof ItemStack value ? value : ItemStack.EMPTY;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Curios slot read failed", e);
        }
    }

    private static void setStack(Object handler, int index, ItemStack stack) {
        try {
            invokeRequired(handler, "setStackInSlot", index, stack);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Curios slot write failed", e);
        }
    }

    private static String itemId(ItemStack stack) {
        ResourceLocation key = BuiltInRegistries.ITEM.getKey(stack.getItem());
        return key == null ? "" : key.toString();
    }

    private static boolean isClassLoaded(String name) {
        try {
            Class.forName(name, false, CiOptionalCompat.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            return false;
        }
    }

    private static boolean containsSelfTalkLabel(String text) {
        return text.contains("Maid Self-Talk") || text.contains("女仆自言自语")
                || text.contains("config.maid_self_talk.title");
    }

    private static void addComponentText(JsonArray labels, Object component) {
        String text = textOf(component);
        if (!text.isBlank()) {
            labels.add(text);
        }
        String key = translatableKey(component);
        if (!key.isBlank()) {
            labels.add(key);
        }
    }

    private static String textOf(Object component) {
        if (component == null) {
            return "";
        }
        Object value = invoke(component, "getString");
        return value == null ? String.valueOf(component) : String.valueOf(value);
    }

    private static String translatableKey(Object component) {
        Object contents = invoke(component, "getContents");
        if (contents == null) {
            return "";
        }
        Object key = invoke(contents, "getKey");
        return key == null ? "" : String.valueOf(key);
    }

    private static boolean visible(Object widget) {
        Object value = readField(widget, "visible");
        return !(value instanceof Boolean bool) || bool;
    }

    private static Object readField(Object target, String name) {
        if (target == null) {
            return null;
        }
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(name);
                field.trySetAccessible();
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (ReflectiveOperationException | RuntimeException e) {
                return null;
            }
        }
        return null;
    }

    private static Object invokeStatic(Class<?> type, String name, Object... args)
            throws ReflectiveOperationException {
        Method method = findMethod(type, name, args);
        if (!Modifier.isStatic(method.getModifiers())) {
            throw new NoSuchMethodException("not static: " + name);
        }
        method.trySetAccessible();
        return method.invoke(null, args);
    }

    private static Object invokeRequired(Object target, String name, Object... args)
            throws ReflectiveOperationException {
        Object result = invoke(target, name, args);
        if (result == null) {
            throw new NoSuchMethodException(target.getClass().getName() + "#" + name);
        }
        return result;
    }

    private static Object invoke(Object target, String name, Object... args) {
        if (target == null) {
            return null;
        }
        try {
            Method method = findMethod(target.getClass(), name, args);
            method.trySetAccessible();
            return method.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    private static Method findMethod(Class<?> type, String name, Object[] args)
            throws NoSuchMethodException {
        for (Method method : type.getMethods()) {
            if (method.getName().equals(name) && compatible(method, args)) {
                return method;
            }
        }
        Class<?> current = type;
        while (current != null) {
            for (Method method : current.getDeclaredMethods()) {
                if (method.getName().equals(name) && compatible(method, args)) {
                    return method;
                }
            }
            current = current.getSuperclass();
        }
        throw new NoSuchMethodException(type.getName() + "#" + name);
    }

    private static boolean compatible(Method method, Object[] args) {
        Class<?>[] parameters = method.getParameterTypes();
        if (parameters.length != args.length) {
            return false;
        }
        for (int i = 0; i < parameters.length; i++) {
            if (args[i] == null) {
                continue;
            }
            if (parameters[i].isPrimitive()) {
                if (!primitiveWrapper(parameters[i]).isInstance(args[i])) {
                    return false;
                }
            } else if (!parameters[i].isInstance(args[i])) {
                return false;
            }
        }
        return true;
    }

    private static Class<?> primitiveWrapper(Class<?> primitive) {
        if (primitive == boolean.class) {
            return Boolean.class;
        }
        if (primitive == byte.class) {
            return Byte.class;
        }
        if (primitive == short.class) {
            return Short.class;
        }
        if (primitive == int.class) {
            return Integer.class;
        }
        if (primitive == long.class) {
            return Long.class;
        }
        if (primitive == float.class) {
            return Float.class;
        }
        if (primitive == double.class) {
            return Double.class;
        }
        if (primitive == char.class) {
            return Character.class;
        }
        return primitive;
    }

    private record InventoryView(boolean loaded, List<SlotView> slots) {
    }

    private record SlotView(Object handler, int index, boolean cosmetic) {
    }
}
