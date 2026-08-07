# TLM Self-Talk 项目指令

## 环境

- 现成 JDK（Java 21，NeoForge 1.21.1 要求）：`D:\Games\ABOUT_MINECRAFT\JAVA\`（javap 等工具在 `zulu21.44.17-ca-jdk21.0.8-win_x64\bin\`）

## 项目概况

- 东方小女仆（TLM 1.5.3-neoforge+mc1.21.1，Modrinth maven）的 NeoForge 1.21.1 附属 mod：女仆 AI 自言自语 + 主人登录欢迎语
- 技术栈：NeoForge 21.1.219 / ModDevGradle 2.0.95 / Gradle 8.14.3 / Java 21；cloth-config 仅 compileOnly（可选依赖）
- 产物 jar：`build/libs/tlm-self-talk-1.0.0.jar`；服务端部署替换 jar 后需重启服务端（代码改动无法热加载）

## 构建

- 项目**无 gradlew wrapper**，使用缓存的 Gradle 发行版：
  `"$USERPROFILE/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle.bat" <task>`
- 常用：`compileJava`（编译验证）、`build`（产出 jar）

## 架构（src/main/java/com/maidmod/selftalk）

- 根包：`Config`（COMMON 配置）/ `SelfTalkHandler`（服务端状态机，事件驱动）/ `MaidSelfTalkService`（触发与 LLM 调用）/ `SelfTalkState`（服务端内存状态）/ `SelfTalkCallback`（自话专用回调）
- `client/`：仅客户端 —— `SelfTalkClothConfig`（管理员配置界面，反射注册）、`SelfTalkPlayerSettingsScreen`（玩家开关界面）
- `mixin/`：TLM 钩子（玩家 chat 入口标记、AIChatScreen 加 💬 按钮、LLMCallback）
- `network/`：玩家配置请求/同步包
- 代码注释与 git 提交信息使用中文；避免引入非必要依赖

## 关键约定与陷阱

- 配置文件：`config/maid_self_talk-common.toml`（COMMON 档，服务端权威、不自动下发，客户端各自读本地）
- NeoForge ConfigFileWatcher **热重载**：替换 toml 立即生效、无需重启；`/reload` 命令不重载配置文件
- **HTTP 400 陷阱**：自话请求发送前必须调用 `HistoryMessagesCheck.checkMessages(messages)` 清洗未配对的 tool 消息（与 TLM `tryToChat` 同构），否则 OpenAI 兼容 API 拒绝请求；清洗失败时放弃本次触发，绝不向上抛
- 自话提示词 / 欢迎提示词**已禁止前端修改**，只能改 toml 的 `[prompt]` 段（界面只留语言字段）
- 欢迎窗口计时必须用 `server.getTickCount()`（全局单调 tick）；各维度 `gameTime` 独立计数，跨维度比较会出现负差
- cloth-config 界面通过反射注册（`MaidSelfTalkMod.registerClothConfigIfPresent`），未装 cloth-config 时自动跳过、不影响核心功能