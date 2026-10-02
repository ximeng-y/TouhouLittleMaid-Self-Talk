package com.maidmod.selftalk;

import com.github.tartaricacid.touhoulittlemaid.TouhouLittleMaid;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.CustomModelPack;
import com.github.tartaricacid.touhoulittlemaid.client.resource.pojo.MaidModelInfo;
import com.github.tartaricacid.touhoulittlemaid.entity.info.ServerCustomPackLoader;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 女仆名称解析（issue #25）：命名牌名称优先，其次 TLM 内置模型预置名称，其余一律视为未命名。
 * <p>
 * 解析顺序（前项命中即返回，不再继续）：
 * <ol>
 *   <li>{@code getCustomName()} 非空——命名牌名称（含使用 YSM 时的自定义名称），内容不翻译不截断；</li>
 *   <li>{@code isYsmModel()} 为真——YSM 模型的预置名在客户端资源包侧，未命名即视为未命名；</li>
 *   <li>当前模型与 TLM 内置模型包记录一致——对应语言的预置名称；</li>
 *   <li>第三方模型、未知模型、模型资料不匹配或名称无法解析——{@code null}。</li>
 * </ol>
 * 「原配」指当前正使用 TLM 内置模型，而非「出生后从未换过模型」：换成别的内置模型即返回新模型的预置名。
 * <p>
 * 内置模型的判定不从「不是 YSM」、命名空间前缀或固定模型 id 推断，而是直接解析 mod jar 内
 * {@code tlm_custom_pack} 的六份模型清单，并对每个包调用 TLM 自己的 {@code decorate}——
 * 该步骤会补全默认名称表达式、展开 {@code extra_textures} 生成带 MD5 后缀的换色变体 id，
 * 自行复制规则必然漏掉变体。清单条目还需与 {@code SERVER_MAID_MODELS} 中同 id 的当前资料
 * 逐项比对（名称表达式、模型位置、材质位置），以排除服务端模型包用同一 id 替换了名称或资源的情况。
 * <p>
 * 名称语言跟随<b>发起查询的女仆</b>（其聊天语言，未记录时用 {@link Config#SELF_TALK_LANGUAGE}），
 * 不跟随被查询女仆；翻译取同语言文件，缺失回退英文，英文也缺失则视为无法解析。
 * 这里不走 {@code ParseI18n}：它构造的是可翻译组件，而 TLM 内置模型包的语言补充位于客户端，
 * 专用服务端不保证能把它解析成人名。
 * <p>
 * 只缓存随 mod jar 固定的资源（模型索引、英文表、按语言码惰性加载的翻译表，缺失也缓存空结果），
 * 不缓存实体状态——命名、换模型、切 YSM 与语言改变都在下一次查询时反映。资源随进程重启重新加载。
 * 两个身份功能都不使用时不会主动加载这些资源；加载失败只跳过受影响的包并记一条警告，
 * 不使自话或工具调用失败。
 */
final class SelfTalkMaidNames {

    /** TLM 内置模型包在 mod jar 内的资源根（classpath 绝对路径） */
    private static final String PACK_ROOT =
            "/assets/touhou_little_maid/tlm_custom_pack/touhou_little_maid-1.0.0/assets/";
    /**
     * 内置模型包命名空间，双线同序。
     * <p>
     * 只列带 {@code maid_model.json} 的六个：{@code littlemaid_peco} 只有音效、
     * {@code next_update_model} 只有椅子模型，都不是女仆模型包。
     */
    private static final List<String> NAMESPACES = List.of(
            "authors_and_credits", "geckolib", "minecraft_15th",
            "touhou_little_maid", "touhou_little_maid_old", "touhou_little_maid_seihou");
    /** 模型清单文件名，与 {@code ServerMaidModels.getJsonFileName()} 同值 */
    private static final String MODEL_JSON = "maid_model.json";
    /** 翻译缺失时的回退语言码 */
    private static final String FALLBACK_LANGUAGE = "en_us";
    /** 模型清单泛型类型，与 TLM {@code ServerCustomPackLoader} 的 TypeToken 同构 */
    private static final Type PACK_TYPE = new TypeToken<CustomModelPack<MaidModelInfo>>() { }.getType();
    /** 非英文语言的翻译表缓存（语言码 → 合并后的键值表，线程安全） */
    private static final Map<String, Map<String, String>> LANGUAGE_CACHE = new ConcurrentHashMap<>();

    private SelfTalkMaidNames() {
    }

    /**
     * 解析发起查询的女仆所使用的名称语言：聊天语言优先，未记录（null/空/空白）时用配置的默认语言，
     * 一律经 {@link SelfTalkContexts#sanitizeLanguage} 校验并转小写。
     * <p>
     * 不猜测地区、不加白名单：没有 {@code zh.json} 就按英文回退，不擅自改写成 {@code zh_cn}。
     */
    static String resolveQueryLanguage(EntityMaid requester) {
        String chatLanguage = requester.getAiChatManager().chatLanguage;
        String language = StringUtils.isBlank(chatLanguage) ? Config.SELF_TALK_LANGUAGE.get() : chatLanguage;
        return SelfTalkContexts.sanitizeLanguage(language).toLowerCase(Locale.ROOT);
    }

    /**
     * 解析目标女仆用于展示的名称，无法解析时返回 {@code null}（调用方按未命名处理）。
     * <p>
     * 自定义名称分支在触碰模型资源之前返回，模型资源故障不影响已有的命名牌名称功能。
     *
     * @param language 查询者语言，来自 {@link #resolveQueryLanguage(EntityMaid)}
     */
    static Component resolveName(EntityMaid target, String language) {
        Component customName = target.getCustomName();
        if (customName != null) {
            return customName;
        }
        if (target.isYsmModel()) {
            return null;
        }
        String modelId = target.getModelId();
        ModelEntry entry = Builtin.INDEX.get(modelId);
        if (entry == null) {
            return null;
        }
        MaidModelInfo current = ServerCustomPackLoader.SERVER_MAID_MODELS.getInfo(modelId).orElse(null);
        if (!entry.matches(current)) {
            return null;
        }
        String resolved = resolveExpression(entry.nameExpression(), language);
        return resolved == null ? null : Component.literal(resolved);
    }

    /**
     * 名称表达式 → 实际名称：整体被 {@code {}} 包裹的按翻译键查表，其余按纯文本使用。
     * <p>
     * 空白表达式与空白翻译都视为无法解析——不回退为翻译键、模型 id 或实体类型名，
     * 也不从 id 猜人名。
     */
    private static String resolveExpression(String expression, String language) {
        if (StringUtils.isBlank(expression)) {
            return null;
        }
        if (expression.length() < 2 || expression.charAt(0) != '{'
                || expression.charAt(expression.length() - 1) != '}') {
            return expression;
        }
        String key = expression.substring(1, expression.length() - 1);
        String translated = lookup(language, key);
        return StringUtils.isBlank(translated) ? null : translated;
    }

    /** 先查目标语言，缺失（含查不到与空白翻译）回退英文 */
    private static String lookup(String language, String key) {
        String value = languageTable(language).get(key);
        return StringUtils.isBlank(value) ? Builtin.ENGLISH.get(key) : value;
    }

    /** 目标语言的合并翻译表（英文直接用启动时那份，其余按语言码惰性加载并缓存） */
    private static Map<String, String> languageTable(String language) {
        if (FALLBACK_LANGUAGE.equals(language)) {
            return Builtin.ENGLISH;
        }
        return LANGUAGE_CACHE.computeIfAbsent(language, SelfTalkMaidNames::loadLanguage);
    }

    /**
     * 合并六个命名空间的同名语言文件为一张查找表；单个命名空间缺文件属正常回退，不记警告，
     * 文件损坏只记一条（语言级缓存保证同一语言只读一次）。
     */
    private static Map<String, String> loadLanguage(String language) {
        boolean english = FALLBACK_LANGUAGE.equals(language);
        Map<String, String> table = new HashMap<>();
        for (String namespace : NAMESPACES) {
            String path = PACK_ROOT + namespace + "/lang/" + language + ".json";
            try (InputStream stream = TouhouLittleMaid.class.getResourceAsStream(path)) {
                if (stream == null) {
                    if (english) {
                        MaidSelfTalkMod.LOGGER.warn("Built-in maid model language file is missing: {}", path);
                    }
                    continue;
                }
                JsonObject json = ServerCustomPackLoader.GSON.fromJson(
                        new InputStreamReader(stream, StandardCharsets.UTF_8), JsonObject.class);
                if (json == null) {
                    continue;
                }
                for (Map.Entry<String, JsonElement> entry : json.entrySet()) {
                    JsonElement value = entry.getValue();
                    // 只收字符串项：同名文件里可能混入数组/对象形式的非名称条目
                    if (value != null && value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
                        table.put(entry.getKey(), value.getAsString());
                    }
                }
            } catch (IOException | RuntimeException e) {
                MaidSelfTalkMod.LOGGER.warn("Failed to read built-in maid model language file {}", path, e);
            }
        }
        return Map.copyOf(table);
    }

    /**
     * 解析六个内置模型包，建立「模型 id → 名称表达式 + 模型/材质位置」索引。
     * <p>
     * 排除带 {@code easter_egg} 的条目，与 TLM 服务端注册规则一致；命中重复 id 时保留先解析到的
     * （命名空间顺序固定，结果稳定）。解析失败只跳过受影响的包，不向上抛。
     */
    private static Map<String, ModelEntry> loadIndex() {
        Map<String, ModelEntry> index = new HashMap<>();
        for (String namespace : NAMESPACES) {
            String path = PACK_ROOT + namespace + "/" + MODEL_JSON;
            try (InputStream stream = TouhouLittleMaid.class.getResourceAsStream(path)) {
                if (stream == null) {
                    MaidSelfTalkMod.LOGGER.warn("Built-in maid model pack is missing: {}", path);
                    continue;
                }
                CustomModelPack<MaidModelInfo> pack = ServerCustomPackLoader.GSON.fromJson(
                        new InputStreamReader(stream, StandardCharsets.UTF_8), PACK_TYPE);
                if (pack == null) {
                    MaidSelfTalkMod.LOGGER.warn("Built-in maid model pack is empty: {}", path);
                    continue;
                }
                // 必须调上游 decorate：补全默认名称表达式、展开 extra_textures 生成换色变体 id
                pack.decorate(namespace);
                for (MaidModelInfo info : pack.getModelList()) {
                    if (info.getEasterEgg() != null || info.getModelId() == null) {
                        continue;
                    }
                    index.putIfAbsent(info.getModelId().toString(),
                            new ModelEntry(info.getName(), info.getModel(), info.getTexture()));
                }
            } catch (IOException | RuntimeException e) {
                MaidSelfTalkMod.LOGGER.warn("Failed to read built-in maid model pack {}", path, e);
            }
        }
        return Map.copyOf(index);
    }

    /**
     * 内置模型的一次解析结果：名称表达式（可能是翻译键）与模型、材质位置。
     * <p>
     * 位置用值相等比对，用于判断服务端当前注册的同 id 资料是否仍是这份内置资料。
     */
    private record ModelEntry(String nameExpression, ResourceLocation model, ResourceLocation texture) {

        /** 当前服务端资料与内置记录逐项一致（名称表达式、模型位置、材质位置）才算命中 */
        private boolean matches(MaidModelInfo current) {
            return current != null
                    && Objects.equals(nameExpression, current.getName())
                    && Objects.equals(model, current.getModel())
                    && Objects.equals(texture, current.getTexture());
        }
    }

    /**
     * 内置模型索引与英文表的持有者：类初始化即线程安全，真正读取资源推迟到首次访问，
     * 因此两个身份功能都不使用时不会触碰这些资源。
     */
    private static final class Builtin {

        private static final Map<String, ModelEntry> INDEX = loadIndex();
        private static final Map<String, String> ENGLISH = loadLanguage(FALLBACK_LANGUAGE);
    }
}
