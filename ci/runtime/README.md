# CI 2 / NeoForge Runtime

本目录提供 NeoForge 1.21.1 的真实客户端链路测试：启动一个真实专服、两个真实客户端、测试 mod、假 LLM/TTS 服务和控制 HTTP，然后按场景驱动真实客户端界面、网络包、服务端事件和产品 tick 状态机。

## 环境要求

- Windows 本地或 Ubuntu CI。
- Java 21。
- Gradle 8.14.3；项目没有 Gradle wrapper。
- 本地运行需要可用的图形环境；Linux CI 使用 Xvfb、软件 OpenGL 和空音频驱动。

## 准备与运行

```powershell
$env:JAVA_HOME = 'D:\Games\ABOUT_MINECRAFT\JAVA\zulu21.44.17-ca-jdk21.0.8-win_x64'
$Gradle = "$env:USERPROFILE\.gradle\wrapper\dists\gradle-8.14.3-bin\cv11ve7ro1n3o1j4so8xd9n66\gradle-8.14.3\bin\gradle.bat"

& $Gradle -PciRuntime=true ciPrepare --console=plain
python ci\runtime\runner.py run `
  --loader neoforge --suite all --skip-prepare `
  --gradle $Gradle --run-id local-all
```

单个场景可以使用 `--scenario <group-id-or-subscenario-id>`，例如：

```powershell
python ci\runtime\runner.py run `
  --loader neoforge --scenario history-retrieval-planning `
  --skip-prepare --gradle $Gradle --run-id local-history
```

`--suite base` 只运行不加载 Cloth Config / Curios 的基础 profile；`--suite optional` 运行可选依赖 profile。`--suite all` 会先运行基础 profile，并在可选场景开始时切换到包含 Cloth Config 和 Curios 的独立启动描述。

## 场景索引

场景定义位于 `ci/runtime/scenarios/*.json`，每个文件是一个场景组，`subScenarios` 是报告中的独立测试项。当前套件覆盖触发、欢迎语、互聊、环境上下文、历史检索、人设、预制复用、设置、错误处理、保存重启和可选依赖兼容性等主链路。

## 失败复现

失败时 Runner 返回非零退出码，并在报告目录写入：

```text
build/ci-runtime/<run-id>/report/failures/<scenario>/
```

其中包含相关进程日志尾部、请求账本和场景证据。重新运行同一场景时使用相同的 `--scenario`，不要复用失败运行中的游戏目录或进程。

## 报告字段

- `summary.json`：运行 ID、版本、场景总数、通过/失败/错误/跳过数量和每项断言。
- `junit.xml`：供 CI 展示的测试结果。
- `events.jsonl`：控制动作、产品事件和观测的有序记录。
- `requests.jsonl`：假 LLM/TTS 请求账本，不包含真实密钥。
- `report.md`：便于人工阅读的汇总。

## 为什么设置 `spawn-animals=true`

TLM 的 `EntityMaid` 继承原版 `Animal`。NeoForge 服务端在 `spawn-animals=false` 时会在 tick 中丢弃动物实体，即使测试显式生成女仆也无法稳定保留。因此测试服务器保留 `spawn-animals=true`，同时关闭 `doMobSpawning`，只允许测试动作显式生成实体。

## 测试 mod 与启动描述

只有带 `-PciRuntime=true` 的 `ciPrepare` 才会生成测试 mod 和启动描述：

```text
build/ci-runtime/maid_self_talk_ci.jar
build/ci-runtime/launch/{server,owner,other}.json
build/ci-runtime/launch/optional/{server,owner,other}.json
```

正式 `gradle build` 不加载 `ciTest`，CI 1 会检查正式 jar 不包含测试 mod 内容。
