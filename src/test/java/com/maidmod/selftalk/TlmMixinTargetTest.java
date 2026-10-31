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
 * 顺序递增，TLM 换构建或升级后编号可能漂移（neoforge 线与 forge 线的 TLM artifact 在
 * {@code MaidAIChatManager} 上就已差一号）。字符串绑定在编译期不报错，漂移只会在运行期
 * 以 {@code InjectionError} 崩溃。本自检把漂移发现提前到 gradle build / CI（issue #60 C4）。
 * <p>
 * 实现方式：直接在 classpath 的 TLM jar 里读取宿主类 .class 字节码，解析常量池并精确比对
 * 方法名／描述符条目（不用子串包含，否则 {@code lambda$onSuccess$1} 会被 {@code ...$10} 顶替通过）。
 * 不用反射的原因：枚举声明方法会连带加载 TLM 类继承的 Minecraft 实体链，在 bare JVM 的
 * dev classpath 上会因二进制不匹配抛 {@code IncompatibleClassChangeError}（本线实测）；
 * 而常量池精确比对与 Mixin「按名字在目标类字节码中匹配」的表面完全一致——名字在，mixin 就命中。
 * <p>
 * 兼容边界：本类断言对应 TLM {@code 1.5.3-neoforge+mc1.21.1}（Modrinth）。若升级 TLM，
 * 必须同步复核四个 mixin 的 {@code method} 字符串并更新本类期望值；forge 线的对应自检
 * 差异仅在 {@code lambda$onPlaySoundLocal} 的序号（forge 为 {@code $5}，本线为 {@code $4}）。
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
 * 原生聊天边界另解析方法表与 Code：精确检查 tryToChat → normalChat、
 * normalChat → List.add / LLMClient.chat 的调用指令及次数，并断言 normalChat 非 static；
 * 不以类级常量池存在引用代替方法内调用点验证。
 * 其余 named 绑定宿主（MaidAIChatData、HistorySummaryManager、client 侧）不在覆盖范围，目标缺失时由
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
                "lambda$onPlaySoundLocal$4",
                "normalChat",
                "getMessages");
        checkNativeChatBoundaries(tlmJar);
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

    /** 在 classpath 上定位 TLM jar（Modrinth 发布物，文件名含 touhou-little-maid） */
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
        Set<String> names = readConstantPoolUtf8(readClassBytes(tlmJar, hostClass), hostClass);
        for (String expected : expectedNames) {
            check(names.contains(expected), hostClass + " 常量池应含完整名字 " + expected
                    + "（TLM 构建变更会使 lambda 序号漂移或方法改名，需同步复核 mixin 的 method 绑定）");
        }
    }

    private static byte[] readClassBytes(File tlmJar, String hostClass) throws IOException {
        byte[] bytes;
        try (JarFile jar = new JarFile(tlmJar)) {
            JarEntry entry = jar.getJarEntry(HOST_PREFIX + hostClass);
            check(entry != null, tlmJar.getName() + " 应包含 " + HOST_PREFIX + hostClass);
            if (entry == null) {
                throw new AssertionError("检查失败：缺少宿主类 " + hostClass);
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
        return bytes;
    }

    /** 只检查 TLM 原始字节码，不加载 Minecraft 类，也不依赖运行时 Mixin。 */
    private static void checkNativeChatBoundaries(File tlmJar) throws IOException {
        String hostClass = "ai/manager/entity/MaidAIChatManager.class";
        String manager = HOST_PREFIX + "ai/manager/entity/MaidAIChatManager";
        String llmClient = HOST_PREFIX + "ai/service/llm/LLMClient";
        String tryDescriptor = "(Ljava/lang/String;L" + HOST_PREFIX
                + "ai/manager/entity/ChatClientInfo;L" + HOST_PREFIX + "ai/service/llm/LLMSite;)V";
        String normalDescriptor = "(Ljava/lang/String;Ljava/util/List;L" + llmClient + ";)V";
        String chatDescriptor = "(L" + HOST_PREFIX + "ai/manager/entity/LLMCallback;)V";
        try (DataInputStream in = new DataInputStream(
                new ByteArrayInputStream(readClassBytes(tlmJar, hostClass)))) {
            ConstantPool pool = readConstantPool(in, hostClass);
            in.skipNBytes(6); // access_flags、this_class、super_class
            in.skipNBytes(2L * in.readUnsignedShort()); // interfaces
            int fields = in.readUnsignedShort();
            for (int index = 0; index < fields; index++) {
                in.skipNBytes(6);
                skipAttributes(in);
            }
            int tryDeclarations = 0;
            int normalDeclarations = 0;
            int normalAccess = 0;
            byte[] tryCode = null;
            byte[] normalCode = null;
            int methods = in.readUnsignedShort();
            for (int index = 0; index < methods; index++) {
                int access = in.readUnsignedShort();
                String name = pool.utf8()[in.readUnsignedShort()];
                String descriptor = pool.utf8()[in.readUnsignedShort()];
                boolean isTry = "tryToChat".equals(name) && tryDescriptor.equals(descriptor);
                boolean isNormal = "normalChat".equals(name) && normalDescriptor.equals(descriptor);
                if (isTry) {
                    tryDeclarations++;
                }
                if (isNormal) {
                    normalDeclarations++;
                    normalAccess = access;
                }
                int attributes = in.readUnsignedShort();
                for (int attribute = 0; attribute < attributes; attribute++) {
                    String attributeName = pool.utf8()[in.readUnsignedShort()];
                    long length = Integer.toUnsignedLong(in.readInt());
                    if ((isTry || isNormal) && "Code".equals(attributeName)) {
                        in.skipNBytes(4); // max_stack、max_locals
                        int codeLength = in.readInt();
                        byte[] code = new byte[codeLength];
                        in.readFully(code);
                        in.skipNBytes(length - 8L - codeLength); // 异常表及嵌套属性
                        if (isTry) {
                            tryCode = code;
                        } else {
                            normalCode = code;
                        }
                    } else {
                        in.skipNBytes(length);
                    }
                }
            }
            check(tryDeclarations == 1, hostClass + " 应声明唯一 tryToChat" + tryDescriptor);
            check(normalDeclarations == 1, hostClass + " 应声明唯一 normalChat" + normalDescriptor);
            check((normalAccess & 0x0008) == 0, "normalChat 应为实例方法（不能带 ACC_STATIC）");
            check(tryCode != null, "tryToChat 应包含 Code 属性");
            check(normalCode != null, "normalChat 应包含 Code 属性");
            check(countInvocations(tryCode, pool, 0xb6, manager, "normalChat", normalDescriptor) == 1,
                    "tryToChat 内应恰好有一次 invokevirtual " + manager + ".normalChat" + normalDescriptor);
            check(countInvocations(normalCode, pool, 0xb9, "java/util/List", "add",
                    "(Ljava/lang/Object;)Z") == 1,
                    "normalChat 内应恰好有一次 invokeinterface java/util/List.add(Ljava/lang/Object;)Z");
            check(countInvocations(normalCode, pool, 0xb9, llmClient, "chat", chatDescriptor) == 1,
                    "normalChat 内应恰好有一次 invokeinterface " + llmClient + ".chat" + chatDescriptor);
        }
    }

    private static void skipAttributes(DataInputStream in) throws IOException {
        int attributes = in.readUnsignedShort();
        for (int index = 0; index < attributes; index++) {
            in.readUnsignedShort();
            in.skipNBytes(Integer.toUnsignedLong(in.readInt()));
        }
    }

    /** 沿指令边界遍历；禁止在操作数或 switch 数据中搜索调用指令字节。 */
    private static int countInvocations(byte[] code, ConstantPool pool, int opcode,
                                        String owner, String name, String descriptor) {
        int count = 0;
        for (int offset = 0; offset < code.length; ) {
            int instruction = code[offset] & 0xff;
            int length = instructionLength(code, offset);
            if (length <= 0 || length > code.length - offset) {
                throw new AssertionError("检查失败：Code 指令长度非法，偏移 " + offset);
            }
            if (instruction == opcode) {
                int reference = ((code[offset + 1] & 0xff) << 8) | (code[offset + 2] & 0xff);
                int expectedTag = opcode == 0xb9 ? 11 : 10; // InterfaceMethodref / Methodref
                if (pool.tags()[reference] == expectedTag) {
                    int classIndex = pool.first()[reference];
                    int nameAndType = pool.second()[reference];
                    if (owner.equals(pool.utf8()[pool.first()[classIndex]])
                            && name.equals(pool.utf8()[pool.first()[nameAndType]])
                            && descriptor.equals(pool.utf8()[pool.second()[nameAndType]])) {
                        count++;
                    }
                }
            }
            offset += length;
        }
        return count;
    }

    /** JVM 指令长度；switch 对齐相对于 Code 起点，wide 的 iinc 单独占 6 字节。 */
    private static int instructionLength(byte[] code, int offset) {
        int opcode = code[offset] & 0xff;
        return switch (opcode) {
            case 0x10, 0x12, 0x15, 0x16, 0x17, 0x18, 0x19,
                    0x36, 0x37, 0x38, 0x39, 0x3a, 0xa9, 0xbc -> 2;
            case 0x11, 0x13, 0x14, 0x84,
                    0x99, 0x9a, 0x9b, 0x9c, 0x9d, 0x9e, 0x9f, 0xa0, 0xa1, 0xa2,
                    0xa3, 0xa4, 0xa5, 0xa6, 0xa7, 0xa8,
                    0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8,
                    0xbb, 0xbd, 0xc0, 0xc1, 0xc6, 0xc7 -> 3;
            case 0xc5 -> 4;
            case 0xb9, 0xba, 0xc8, 0xc9 -> 5;
            case 0xc4 -> (code[offset + 1] & 0xff) == 0x84 ? 6 : 4;
            case 0xaa -> {
                int aligned = (offset + 4) & ~3;
                int low = readInt(code, aligned + 4);
                int high = readInt(code, aligned + 8);
                yield aligned - offset + 12 + 4 * (high - low + 1);
            }
            case 0xab -> {
                int aligned = (offset + 4) & ~3;
                yield aligned - offset + 8 + 8 * readInt(code, aligned + 4);
            }
            default -> {
                if (opcode > 0xc9) {
                    throw new AssertionError("检查失败：Code 出现未知 opcode " + opcode);
                }
                yield 1;
            }
        };
    }

    private static int readInt(byte[] bytes, int offset) {
        return ((bytes[offset] & 0xff) << 24) | ((bytes[offset + 1] & 0xff) << 16)
                | ((bytes[offset + 2] & 0xff) << 8) | (bytes[offset + 3] & 0xff);
    }

    private record ConstantPool(String[] utf8, int[] tags, int[] first, int[] second) {
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
        ConstantPool pool = readConstantPool(
                new DataInputStream(new ByteArrayInputStream(classBytes)), hostClass);
        Set<String> utf8 = new HashSet<>();
        for (String value : pool.utf8()) {
            if (value != null) {
                utf8.add(value);
            }
        }
        return utf8;
    }

    private static ConstantPool readConstantPool(DataInputStream in, String hostClass) throws IOException {
        check(in.readInt() == 0xCAFEBABE, hostClass + " 应是合法 class 文件（magic 不匹配）");
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int count = in.readUnsignedShort();
        String[] utf8 = new String[count];
        int[] tags = new int[count];
        int[] first = new int[count];
        int[] second = new int[count];
        // 常量池有效下标自 1 起；Long/Double 占两个槽位，其余各占一个
        for (int index = 1; index < count; index++) {
            int tag = in.readUnsignedByte();
            tags[index] = tag;
            switch (tag) {
                case 1:
                    int length = in.readUnsignedShort();
                    byte[] raw = new byte[length];
                    in.readFully(raw);
                    utf8[index] = new String(raw, StandardCharsets.ISO_8859_1);
                    break;
                case 7:  // Class
                case 8:  // String
                case 16: // MethodType
                case 19: // Module
                case 20: // Package
                    first[index] = in.readUnsignedShort();
                    break;
                case 15: // MethodHandle
                    in.skipBytes(3);
                    break;
                case 3:  // Integer
                case 4:  // Float
                    in.skipBytes(4);
                    break;
                case 9:  // Fieldref
                case 10: // Methodref
                case 11: // InterfaceMethodref
                case 12: // NameAndType
                case 17: // Dynamic
                case 18: // InvokeDynamic
                    first[index] = in.readUnsignedShort();
                    second[index] = in.readUnsignedShort();
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
        return new ConstantPool(utf8, tags, first, second);
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("检查失败：" + message);
        }
    }
}
