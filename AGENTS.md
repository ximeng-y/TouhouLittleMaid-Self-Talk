# TLM Self-Talk 项目指令

## 环境

- 现成 JDK：`D:\Games\ABOUT_MINECRAFT\JAVA\`（zulu17 供 forge-1.20.1 线、zulu21 供 neoforge-1.21.1 线；javap 等在 `zulu17.62.17-ca-jdk17.0.17-win_x64\bin\` 或 `zulu21.44.17-ca-jdk21.0.8-win_x64\bin\`）

## 项目概况

- 东方小女仆（TLM 1.5.3，Modrinth maven）的附属 mod：女仆 AI 自言自语 / 主人登录欢迎语 / 女仆互聊，附玩家级设置（自话、互聊、睡觉安静、自定义 Prompt、Tool 调用）、环境事件上下文、历史检索、四栏人设与 AI 拆分，以及默认关闭的预制自话/欢迎语复用
- **双分支双线**：
  - `neoforge-1.21.1` 分支（GitHub 默认分支）：NeoForge 1.21.1（NeoForge 21.1.219 / ModDevGradle 2.0.95 / Java 21；TLM 1.5.3-neoforge+mc1.21.1）
  - `forge-1.20.1` 分支：Forge 1.20.1（Forge 47.2.0 / ForgeGradle 6 + parchment + mixingradle / Java 17；TLM 1.5.3-forge+mc1.20.1，fg.deobf）
- 产物 jar（1.0.3 起加分支后缀区分）：neoforge 线 `build/libs/tlm-self-talk-1.4.0-neoforge-1.21.1.jar`；forge 线 `build/libs/tlm-self-talk-1.4.0-forge-1.20.1.jar`（双线 jarJar 内嵌 SnakeYAML 后，正式发布 jar 由 jarJar 任务产出，forge 线原始 jar 退居 `-thin.jar`）；部署替换 jar 后需重启服务端（代码改动无法热加载）

## 构建

- 项目**无 gradlew wrapper**，使用缓存的 Gradle 发行版：
  `"$USERPROFILE/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle.bat" <task>`
- 常用：`compileJava`（编译验证）、`build`（产出 jar）
- forge-1.20.1 分支构建必须 `JAVA_HOME` 指向 JDK17（PATH 的 java 损坏）；neoforge-1.21.1 分支用 JDK21

## 架构（src/main/java/com/maidmod/selftalk）

- 触发与调度：`SelfTalkHandler` 处理服务端事件和状态，`SelfTalkDispatcher` 统一处理立即派发、顺延与冲突。
- 业务与生命周期：`MaidSelfTalkService` / `MaidInterChatService` 组织请求，`SelfTalkCallback` / `InterChatCallback` 处理工具轮次与交付，`SelfTalkRequestToken` / `SelfTalkState` 管理请求身份、取消和清空。
- 提示词与上下文：`SelfTalkPrompts` / `SelfTalkContexts` 组织提示词与环境信息，`SegmentTags` / `SelfTalkProvenance` 处理段标签和来源标记。
- 历史：`HistoryContextSession` / `SelfTalkHistoryAssembler` 规划并组装请求历史，`AutonomousChatHistory` 保存独立自动聊天档案。
- 人设：`MaidPersonaRuntime` 管理原版／四栏模式与召回，`PlayerPersonaChatSession` 接入玩家聊天，`persona/` 管理检索与 AI 拆分。
- 预制复用：`SelfTalkReuseService` / `SelfTalkReuseStore` 管理匹配与库存，`PrebuiltAudioStore` 管理预制语音；由 `MaidSelfTalkService` 分流，`SelfTalkCallback` 统一交付。
- 玩家设置：`PlayerSettingsStore` 为统一读写入口；NeoForge 使用 `SelfTalkAttachments`，Forge 使用 `PlayerSettingsSavedData`，旧档迁移见 `SelfTalkMigration`。
- `client/`：设置、人设和复用界面，以及客户端配置与保存确认；Cloth Config 为可选的反射注册入口。
- `mixin/`：接入 TLM 聊天、历史、工具与 TTS；注入清单见 `src/main/resources/maid_self_talk.mixins.json`。
- `network/`：双线协议版本串均为 `17`，NeoForge 使用 payload，Forge 使用 message；注册、字段和版本演进见各分支 `SelfTalkPackets`。协议不向后兼容，修改报文结构时同步调整版本。

## 开发约定

- 代码注释与 git 提交信息使用中文；避免引入非必要依赖
- 平台 API 与 TLM 接入按目标分支及目标版本依赖核对。

## 运行与存储边界

- COMMON 配置为 `config/maid_self_talk-common.toml`，服务端权威，不自动下发；客户端本地等待气泡显示由 `client/SelfTalkClientConfig` 的 CLIENT 配置控制。
- 玩家设置、预制复用匹配参数和预制语音世界开关统一存于 overworld，所有维度使用同一份；每只女仆的人设配置与自动聊天档案存于女仆 AI 数据 NBT。
- 预制库存与音频位于 `<世界根>/data/maid_self_talk/prebuilt/`，与玩家设置、女仆 NBT 分开管理。
- 欢迎窗口和跨维度调度统一使用 `server.getTickCount()`。
- 自话与互聊发起者共用全局限流闸门，链式续接绕过该闸门；欢迎语使用独立闸门，在窗口内等待空闲和额度，不进入顺延队列。具体间隔见 `Config`。
- 死亡事件仅记录玩家、有主动物和其他女仆；有主动物按 `OwnableEntity` 判定。受伤事件使用实际掉血事件：NeoForge 为 `LivingDamageEvent.Post`，Forge 为 `LivingDamageEvent`。

## 代码导航与索引维护

### 开始代码任务

- 先检查 `.XMagent-docs/README.md`。存在时先读该入口，按任务路由选择一个相关源码入口或模块／流程文档，再局部核对目标分支源码。
- 索引不存在时，直接使用仓库文档、符号搜索和源码检索。
- 索引以实际目标分支源码为准；核对其记录的分支与提交，区分主线和未合并实施分支。历史决策与平台踩坑按需检索可用的项目记忆。

### 代码改动后

- 主 Agent 在交付前检查代码改动是否影响入口、调用链、生命周期、平台差异、协议、存储或 Mixin 边界。
- 本地存在索引且涉及上述变化时，主 Agent 主动派出一个执行型子 Agent 维护受影响的导航，并提供改动摘要、涉及文件与符号、目标分支与提交、已完成的验证及维护范围。
- 子 Agent 少量、局部核对相关源码后更新对应导航与实际核对的源码基线，返回修改文件、核对依据和未核验事项。
- 主 Agent 轻量复查文档差异、关键源码锚点与基线；发现偏差时局部修正，再交付维护结果。子 Agent 不可用时，由主 Agent 按相同范围完成。
- `.XMagent-docs` 为独立 Git 仓库，维护时分别检查主仓库与索引仓库的工作树和提交状态。
