# TLM Self-Talk 项目指令

## 环境

- 现成 JDK：`D:\Games\ABOUT_MINECRAFT\JAVA\`（zulu17 供 forge-1.20.1 线、zulu21 供 neoforge-1.21.1 线；javap 等在 `zulu17.62.17-ca-jdk17.0.17-win_x64\bin\` 或 `zulu21.44.17-ca-jdk21.0.8-win_x64\bin\`）

## 项目概况

- 东方小女仆（TLM 1.5.3，Modrinth maven）的附属 mod：女仆 AI 自言自语 / 主人登录欢迎语 / 女仆互聊，附玩家级设置（自话、互聊、睡觉安静、自定义 Prompt、Tool 调用）与环境事件上下文注入
- **双分支双线**：
  - `neoforge-1.21.1` 分支（GitHub 默认分支）：NeoForge 1.21.1（NeoForge 21.1.219 / ModDevGradle 2.0.95 / Java 21；TLM 1.5.3-neoforge+mc1.21.1）
  - `forge-1.20.1` 分支：Forge 1.20.1（Forge 47.2.0 / ForgeGradle 6 + parchment + mixingradle / Java 17；TLM 1.5.3-forge+mc1.20.1，fg.deobf）
- 产物 jar（1.0.3 起加分支后缀区分）：neoforge 线 `build/libs/tlm-self-talk-1.3.0-neoforge-1.21.1.jar`；forge 线 `build/libs/tlm-self-talk-1.3.0-forge-1.20.1.jar`；部署替换 jar 后需重启服务端（代码改动无法热加载）

## 构建

- 项目**无 gradlew wrapper**，使用缓存的 Gradle 发行版：
  `"$USERPROFILE/.gradle/wrapper/dists/gradle-8.14.3-bin/cv11ve7ro1n3o1j4so8xd9n66/gradle-8.14.3/bin/gradle.bat" <task>`
- 常用：`compileJava`（编译验证）、`build`（产出 jar）
- forge-1.20.1 分支构建必须 `JAVA_HOME` 指向 JDK17（PATH 的 java 损坏）；neoforge-1.21.1 分支用 JDK21

## 架构（src/main/java/com/maidmod/selftalk）

- 触发与调度：`SelfTalkHandler`（服务端状态机，事件驱动）/ `SelfTalkDispatcher`（派发闸门：立即派发 / 顺延入队 / 吞请求，单一入口）
- 业务服务与回调：`MaidSelfTalkService`（自话）/ `MaidInterChatService`（互聊）/ `SelfTalkCallback`、`InterChatCallback`（两条链的回调，含 Tool 轮次与历史清理）
- 提示词与上下文：`SelfTalkPrompts`（硬编码提示词与 tool policy 段）/ `SelfTalkContexts`（环境上下文、语言指令、自定义 Prompt 块）/ `SegmentTags`（段标签与玩家输入清洗）/ `SelfTalkProvenance`（来源指纹判定）
- 存储与状态：`PlayerSettingsStore`（玩家设置统一读写口）/ `SelfTalkState`（服务端内存状态）/ `SelfTalkMigration`（1.1.2 存储迁移）
  - neoforge 线另有 `SelfTalkAttachments`（Level 附件注册）；forge 线另有 `PlayerSettingsSavedData`（overworld SavedData）、`SelfTalkProvenanceHost`（无附件，经 mixin 挂接口）
- `client/`：仅客户端 —— `SelfTalkClothConfig`（管理员配置界面，反射注册）、`SelfTalkPlayerSettingsClient`（客户端设置缓存）、`SelfTalkPlayerSettingsScreen`（玩家开关/Prompt 界面）
- `mixin/`：TLM 钩子（玩家 chat 入口标记、AIChatScreen 加 💬 按钮、LLMCallback、历史摘要、女仆 AI 数据；forge 线另有女仆指纹挂载）
- `network/`：5 组 payload（自话 / 互聊 / 睡觉安静 / 自定义 Prompt / Tool，各 request/response/set），协议版本串 `5`
- 代码注释与 git 提交信息使用中文；避免引入非必要依赖

## 关键约定与陷阱

- 配置文件：`config/maid_self_talk-common.toml`（COMMON 档，服务端权威、不自动下发，客户端各自读本地）
- **配置热重载**：neoforge 线已实测确认——NeoForge ConfigFileWatcher 监视 config 目录，替换 toml 立即生效、无需重启。forge 线反编译 47.2.0 显示同样注册了 FileWatcher，但**尚未实机复验**，复验前不要断言任一侧；`/reload` 命令不重载配置文件（只重载资源/数据包）
- 玩家设置（自话/互聊/睡觉安静/自定义 Prompt/Tool）双线均存于 **overworld 世界存档**（neoforge 走 Level 附件、forge 走 SavedData），不随玩家实体；任何维度读同一份
- forge 线 mixin 注入点全部 `remap=false`（FG6 Mixin AP 按 searge 解析 deobf 依赖方法必然失败；TLM 类 prod 不混淆）；AIChatScreenMixin 继承 Screen 使 `this.addRenderableWidget(...)` 合法、随 reobf 重映射为 `m_142416_`
- **HTTP 400 陷阱**：自话请求发送前必须调用 `HistoryMessagesCheck.checkMessages(messages)` 清洗未配对的 tool 消息（与 TLM `tryToChat` 同构），否则 OpenAI 兼容 API 拒绝请求；清洗失败时放弃本次触发，绝不向上抛
- **Tool 历史清理红线**：带工具调用的一轮结束后，`assistant(tool_calls)` 与其 `tool` 结果必须**成对**整批删除。`checkMessages` 只清洗孤立 `tool` 消息、**不清洗**孤立 `assistant(tool_calls)`，留下会让后续所有请求被 400 拒绝；不可改成按 role 扫全表删除（会误删玩家聊天路径的记录）
- 欢迎窗口计时必须用 `server.getTickCount()`（全局单调 tick）；各维度 `gameTime` 独立计数，跨维度比较会出现负差
- **限流闸门**：欢迎语全局每秒最多 1 次（`rate_limit.maxTriggerPerSecond`）；自话全局每 5~8 秒（`rate_limit.selfTalkMinIntervalSeconds` / `selfTalkMaxIntervalSeconds`）放行 1 只，**互聊发起者派发共用同一自话闸门**（链式续接不走闸门）；被限流自话**不发请求**、随机退避 8~15 秒重试（玩家聊天框不会出现报错）；欢迎语无退避、窗口期内每 tick 重试，排队若在窗口期内未轮到会错过该次欢迎
- **环境事件过滤**：死亡事件固定过滤为玩家、有主动物、其他女仆——「有主动物」必须按 `OwnableEntity` 判定（马/驴/骡等坐骑不继承 `TamableAnimal` 但同样有主人）；受伤事件必须用**实际掉血**事件（neo `LivingDamageEvent.Post` / forge `LivingDamageEvent`），更早的 incoming/hurt 事件在格挡成功或减免到 0 时仍会以正数触发
- cloth-config 界面通过反射注册（`MaidSelfTalkMod.registerClothConfigIfPresent`），未装 cloth-config 时自动跳过、不影响核心功能
