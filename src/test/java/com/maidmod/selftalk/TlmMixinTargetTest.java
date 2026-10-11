package com.maidmod.selftalk;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Pattern;

/**
 * TLM mixin 绑定目标自检入口（无测试框架依赖，直接 {@code main} 跑）。
 * <p>
 * 本 mod 的 mixin 以字符串绑定 TLM 方法名，其中 {@code SelfTalkTtsMixin}、
 * {@code SelfTalkSystemTtsMixin}、{@code InterChatToolLifecycleMixin} 绑定的 5 个目标是
 * javac 为 lambda 生成的合成方法（{@code lambda$方法名$序号}）：序号按 TLM 类内 lambda 出现
 * 顺序递增，TLM 换构建或升级后编号可能漂移（forge 线与 neoforge 线的 TLM artifact 在
 * {@code MaidAIChatManager} 上就已差一号）。字符串绑定在编译期不报错，漂移只会在运行期
 * 以 {@code InjectionError} 崩溃。本自检把漂移发现提前到 gradle build / CI（issue #60 C4）。
 * <p>
 * 实现方式：直接在 classpath 的 TLM jar 里读取宿主类 .class 字节码，按字节匹配方法名／描述符。
 * 不用反射的原因：枚举声明方法会连带加载 TLM 类继承的 Minecraft 实体链，在 bare JVM 的
 * dev classpath 上会因二进制不匹配抛 {@code IncompatibleClassChangeError}（neoforge 线实测）；
 * 而字节码扫描与 Mixin「按名字在目标类字节码中匹配」的表面完全一致——名字在，mixin 就命中。
 * <p>
 * 兼容边界：本类断言对应 TLM {@code 1.5.3-forge+mc1.20.1}（Modrinth，经 fg.deobf 后
 * 自身方法名不变）。若升级 TLM，必须同步复核三个 mixin 的 {@code method} 字符串并更新
 * 本类期望值；neoforge 线的对应自检差异仅在 {@code lambda$onPlaySoundLocal} 的序号。
 * <p>
 * 覆盖边界：只覆盖承载全部 lambda 绑定的三个宿主类，外加 LLMCallback 内两处历史写入
 * {@code @Redirect} 的调用点方法名（{@code addAssistantHistory}/{@code addToolHistory}，
 * 调用点 owner 为 MaidAIChatManager）。其余 named 绑定宿主（MaidAIChatData、
 * HistorySummaryManager、EntityMaid、client 侧）不在覆盖范围，目标缺失时由
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
                "refreshWaitingChatBubble",
                "addToolResult",
                "onFunctionCall",
                "onSuccess",
                "onFailure",
                "addAssistantHistory",
                "addToolHistory");
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

    /** 断言宿主类字节码中包含全部期望名（与 Mixin 按名字匹配的表面一致） */
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
        // 常量池为 modified-UTF8：按 ISO-8859-1 读取可保持字节一一对应，
        // 方法名与描述符在其中连续出现，字节包含即 Mixin 可命中
        String pool = new String(bytes, StandardCharsets.ISO_8859_1);
        for (String expected : expectedNames) {
            check(pool.contains(expected), hostClass + " 字节码应包含 " + expected
                    + "（TLM 构建变更会使 lambda 序号漂移或方法改名，需同步复核 mixin 的 method 绑定）");
        }
    }

    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) {
            throw new AssertionError("检查失败：" + message);
        }
    }
}
