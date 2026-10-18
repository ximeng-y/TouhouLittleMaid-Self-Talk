package com.maidmod.selftalk;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * TLM mixin 绑定目标自检入口（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 本 mod 的 mixin 以字符串绑定 TLM 方法名，其中 {@code SelfTalkTtsMixin}、
 * {@code SelfTalkSystemTtsMixin}、{@code InterChatToolLifecycleMixin}、
 * {@code SelfTalkLateDisplayMixin} 绑定的 6 个目标是
 * javac 为 lambda 生成的合成方法（{@code lambda$方法名$序号}）：序号按 TLM 类内 lambda 出现
 * 顺序递增，TLM 换构建或升级后编号可能漂移（forge 线与 neoforge 线的 TLM artifact 在
 * {@code MaidAIChatManager} 上就已差一号）。字符串绑定在编译期不报错，漂移只会在运行期
 * 以 {@code InjectionError} 崩溃。本自检把漂移发现提前到 gradle build / CI（issue #60 C4）。
 * <p>
 * 实现方式：直接在 classpath 的 TLM jar 里读取宿主类 .class 字节码，解析常量池并精确比对
 * 方法名／描述符条目（不用子串包含，否则 {@code lambda$onSuccess$1} 会被 {@code ...$10} 顶替通过）。
 * 不用反射的原因：枚举声明方法会连带加载 TLM 类继承的 Minecraft 实体链，在 bare JVM 的
 * dev classpath 上会因二进制不匹配抛 {@code IncompatibleClassChangeError}（neoforge 线实测）；
 * 而常量池精确比对与 Mixin「按名字在目标类字节码中匹配」的表面完全一致——名字在，mixin 就命中。
 * <p>
 * 兼容边界：本类断言对应 TLM {@code 1.5.3-forge+mc1.20.1}（Modrinth，经 fg.deobf 后
 * 自身方法名不变）。若升级 TLM，必须同步复核四个 mixin 的 {@code method} 字符串并更新
 * 本类期望值；neoforge 线的对应自检差异仅在 {@code lambda$onPlaySoundLocal} 的序号。
 * <p>
 * 覆盖边界：覆盖承载 lambda 绑定的四个宿主类（TTSCallback、MaidAIChatManager、LLMCallback、
 * AIChatScreen），外加 LLMCallback 内两处历史写入 {@code @Redirect} 的调用点方法名
 * （{@code addAssistantHistory}/{@code addToolHistory}，调用点 owner 为 MaidAIChatManager）
 * 与 SettingEditScreen 的 {@code drawMaid}/{@code saveConfig}/{@code mouseReleased} 命名绑定、
 * AIChatScreen 常量池里的 SettingEditScreen 构造引用（类引用 + 构造描述符，类级粒度）。
 * LLMCallback 名单还包含 {@code SelfTalkLateDisplayMixin} 的延迟上屏 {@code @Inject} 目标
 * （{@code lambda$onSuccess$1}），及其 {@code @Redirect} 的 {@code getTTSSite} 调用点约束
 * （调用点在 {@code onSuccess} 内、指令 owner 为 MaidAIChatManager，方法本体声明在其父类
 * MaidAIChatData，故名字与描述符断言在 LLMCallback 常量池）。
 * 其余 named 绑定宿主
 * （MaidAIChatData、HistorySummaryManager、EntityMaid、client 侧）不在覆盖范围，目标缺失时由
 * {@code maid_self_talk.mixins.json} 的 {@code defaultRequire: 1} 在启动期兜底报错。
 * <p>
 * 运行（已由 {@code gradle check} / CI 自动执行，issue #60 C13）：
 * {@code gradle selfCheckTlmMixinTargetTest}；免 Gradle 场景可编译 test 源集后，
 * 用 {@code java -cp <test runtime classpath>} 直跑本 main。
 */
public final class TlmMixinTargetTest {

    /** 三个宿主类在 TLM jar 内的目录前缀 */
    private static final String HOST_PREFIX = "com/github/tartaricacid/touhoulittlemaid/";

    /** TTSCallback 三参构造的描述符（SelfTalkTtsMixin 的 &lt;init&gt; 绑定目标） */
    private static final String TTS_CTOR_DESCRIPTOR =
            "(Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;Ljava/lang/String;J)V";

    private static int checks = 0;

    private TlmMixinTargetTest() {
    }

    public static void main(String[] args) throws Exception {
        File tlmJar = locateTlmJar();
        checkClass(tlmJar, "ai/manager/entity/TTSCallback.class",
                "lambda$onSuccess$1",
                "lambda$onFailure$0",
                TTS_CTOR_DESCRIPTOR);
        checkClass(tlmJar, "ai/manager/entity/MaidAIChatManager.class",
                "onPlaySoundLocal",
                "lambda$onPlaySoundLocal$5",
                "normalChat",
                "getMessages");
        checkClass(tlmJar, "ai/manager/entity/LLMCallback.class",
                "onSingleCall",
                "lambda$executeSingleToolCall$5",
                "lambda$onFunctionCall$2",
                "lambda$onSuccess$1",
                "refreshWaitingChatBubble",
                "addToolResult",
                "onFunctionCall",
                "onSuccess",
                "onFailure",
                "addAssistantHistory",
                "addToolHistory",
                // SelfTalkLateDisplayMixin 的 @Redirect：onSuccess 内 getTTSSite 调用点
                // （指令 owner 为 MaidAIChatManager，方法本体声明在其父类 MaidAIChatData），
                // 名字与描述符落在本类常量池
                "getTTSSite",
                "()Lcom/github/tartaricacid/touhoulittlemaid/ai/service/tts/TTSSite;");
        // 统一人设页：✎ 按钮 onPress lambda 内的 SettingEditScreen 构造重定向（NEW 目标：
        // 类引用 + 构造描述符；常量池是类级粒度，无法定位到单个方法，与 lambda 名存在检查配合使用），
        // 以及原版页上的布娃娃位置调整、隐藏输入框释放派发跳过与保存附带提交钩子
        checkClass(tlmJar, "client/gui/entity/maid/ai/AIChatScreen.class",
                "lambda$addLeftButtons$1",
                "com/github/tartaricacid/touhoulittlemaid/client/gui/entity/maid/ai/SettingEditScreen",
                "(Lnet/minecraft/client/gui/screens/Screen;"
                        + "Lcom/github/tartaricacid/touhoulittlemaid/entity/passive/EntityMaid;)V");
        checkClass(tlmJar, "client/gui/entity/maid/ai/SettingEditScreen.class",
                "drawMaid",
                "saveConfig",
                "mouseReleased");
        System.out.println("TlmMixinTargetTest: all " + checks + " checks passed");
    }

    /** 在 classpath 上定位 TLM jar（forge 线为 fg.deobf 产物，文件名同样含 touhou-little-maid） */
    private static File locateTlmJar() {
        String[] entries = System.getProperty("java.class.path").split(Pattern.quote(File.pathSeparator));
        List<File> candidates = new ArrayList<>();
        for (String entry : entries) {
            File file = new File(entry);
            if (file.isFile() && file.getName().contains("touhou-little-maid")) {
                candidates.add(file);
            }
        }
        check(candidates.size() == 1, "classpath 上应恰好有一个 TLM jar，实际 "
                + candidates.size() + (candidates.isEmpty()
                ? "（未找到：确认 TLM 依赖在自检运行时 classpath 上）" : "：" + candidates));
        return candidates.get(0);
    }

    /** 断言宿主类常量池中存在全部期望名（Mixin 按「常量池里的完整名字」匹配，故只做精确比对） */
    private static void checkClass(File tlmJar, String hostClass, String... expectedNames) throws Exception {
        byte[] bytes;
        try (JarFile jar = new JarFile(tlmJar)) {
            JarEntry entry = jar.getJarEntry(HOST_PREFIX + hostClass);
            check(entry != null, tlmJar.getName() + " 应包含 " + HOST_PREFIX + hostClass);
            if (entry == null) {
                return;
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            try (InputStream in = jar.getInputStream(entry)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) >= 0) {
                    out.write(buffer, 0, read);
                }
            }
            bytes = out.toByteArray();
        }
        Set<String> names = readConstantPoolUtf8(bytes, hostClass);
        for (String expected : expectedNames) {
            check(names.contains(expected), hostClass + " 常量池应含完整名字 " + expected
                    + "（TLM 构建变更会使 lambda 序号漂移或方法改名，需同步复核 mixin 的 method 绑定）");
        }
    }

    /**
     * 读出 class 常量池中全部 UTF8 条目的完整内容（方法名与描述符都在其中）。
     * <p>
     * 必须逐条取完整条目、精确比对，不能用整段字节的子串包含判断：常量池里只有
     * {@code lambda$onSuccess$10} 时，子串 {@code lambda$onSuccess$1} 同样成立，会把
     * lambda 编号漂移漏报成通过，而 mixin 到启动期才因目标缺失崩溃——这正是本自检要拦的。
     * <p>
     * 按 ISO-8859-1 还原条目字节以保持与 class 文件字节一一对应（名字均为 ASCII，
     * 且 modified-UTF8 的 0 字节编码不影响等值比较）。
     */
    private static Set<String> readConstantPoolUtf8(byte[] classBytes, String hostClass) throws IOException {
        DataInputStream in = new DataInputStream(new ByteArrayInputStream(classBytes));
        check(in.readInt() == 0xCAFEBABE, hostClass + " 应是合法 class 文件（magic 不匹配）");
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        Set<String> utf8 = new HashSet<>();
        // 常量池有效下标自 1 起；Long/Double 占两个槽位，其余各占一个
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1:
                    int length = in.readUnsignedShort();
                    byte[] raw = new byte[length];
                    in.readFully(raw);
                    utf8.add(new String(raw, StandardCharsets.ISO_8859_1));
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    in.skipBytes(2);
                    break;
                case 15: // MethodHandle
                    in.skipBytes(3);
                    break;
                case 3:  // Integer
                case 4:  // Float
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    in.skipBytes(4);
                    break;
                case 5:  // Long
                case 6:  // Double
                    in.skipBytes(8);
                    index++;
                    break;
                default:
                    throw new AssertionError("检查失败：" + hostClass + " 常量池出现未知 tag " + tag
                            + "（可能为 class 文件新版本引入的新 tag，需扩展本解析）");
            }
        }
        return utf8;
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("检查失败：" + message);
        }
    }
}
