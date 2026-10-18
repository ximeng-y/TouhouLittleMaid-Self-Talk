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

- 触发与调度：`SelfTalkHandler`（服务端状态机，事件驱动）/ `SelfTalkDispatcher`（派发闸门：立即派发 / 顺延入队 / 吞请求，单一入口）
- 业务服务与回调：`MaidSelfTalkService`（自话）/ `MaidInterChatService`（互聊）/ `SelfTalkCallback`、`InterChatCallback`（两条链的回调，含 Tool 轮次与历史清理）
- 提示词与上下文：`SelfTalkPrompts`（硬编码提示词与 tool policy 段）/ `SelfTalkContexts`（环境上下文、语言指令、自定义 Prompt 块）/ `SegmentTags`（段标签与玩家输入清洗）/ `SelfTalkProvenance`（来源指纹判定）
- 历史与人设：`HistoryContextSession` / `KeywordPlanningSession`（历史、人设共用规划）/ `SelfTalkHistoryAssembler`（请求历史组装）/ `AutonomousChatHistory`（独立自动聊天档案）；`MaidPersonaRuntime` / `MaidPersonaConfig` / `MaidPersonaHost`（每女仆原版/四栏模式与召回），`PlayerPersonaChatSession` 接入玩家聊天，`persona/PersonaSplitService` 管理独立 AI 拆分任务
- 预制复用：`SelfTalkReuseService`（身份指纹、匹配、入库凭据）/ `SelfTalkReuseStore`（世界 YAML 头 Markdown 库）/ `PrebuiltReuseParameters`（最低可用条数与使用概率的缺省、范围与合法性判定）/ `PrebuiltEntryCodec`（YAML 头 Markdown 编解码与诊断告警）/ `PrebuiltCustomEntries`、`PrebuiltDiagnostics`（custom/ 人类条目扫描与诊断报告）/ `SelfTalkTtsTranslationSession`、`SelfTalkTtsTranslationCallback`（命中后独立短翻译）；由 `MaidSelfTalkService` 在历史/人设规划前分流，`SelfTalkCallback` 统一交付
- 存储与状态：`PlayerSettingsStore`（玩家设置统一读写口）/ `SelfTalkState`（服务端内存状态）/ `SelfTalkMigration`（1.1.2 存储迁移）
  - neoforge 线另有 `SelfTalkAttachments`（Level 附件注册）；forge 线另有 `PlayerSettingsSavedData`（overworld SavedData）、`SelfTalkProvenanceHost`（无附件，经 mixin 挂接口）
- `client/`：仅客户端 —— `SelfTalkClothConfig`（管理员配置界面，反射注册）、`SelfTalkPlayerSettingsClient`（客户端设置缓存与保存确认）、`SelfTalkPlayerSettingsScreen`（玩家开关/Prompt 界面）、`EnvironmentContextPanel` / `SelfTalkReusePanel`（互斥浮层）、`MaidPersonaEditScreen` / `MaidPersonaSplitClient`（人设编辑与拆分候选）
- `mixin/`：TLM 钩子（玩家 chat 入口标记、AIChatScreen 加 💬 按钮、LLMCallback、历史摘要、女仆 AI 数据；forge 线另有女仆指纹挂载）
- `network/`：双线协议版本串均为 `15`；NeoForge 使用 payload，Forge 使用 message；Forge 人设保存序号 20/21、AI 拆分 22/23、预制复用 Request/Set/Response 24/25/26。版本演进见各分支 `SelfTalkPackets` 注册处注释（不向后兼容，版本串即协商拒连依据）
- 代码注释与 git 提交信息使用中文；避免引入非必要依赖

## 关键约定与陷阱

- 配置文件：`config/maid_self_talk-common.toml`（COMMON 档，服务端权威、不自动下发，客户端各自读本地）
- **配置热重载**：neoforge 线已实测确认——NeoForge ConfigFileWatcher 监视 config 目录，替换 toml 立即生效、无需重启。forge 线反编译 47.2.0 显示同样注册了 FileWatcher，但**尚未实机复验**，复验前不要断言任一侧；`/reload` 命令不重载配置文件（只重载资源/数据包）
- 玩家设置（自话/互聊/睡觉安静/自定义 Prompt/Tool/环境上下文/历史模式/预制复用开关）双线均存于 **overworld 世界存档**（neoforge 走 Level 附件、forge 走 SavedData），不随玩家实体；任何维度读同一份。预制复用的匹配参数（最低可用条数／使用概率）自 issue #108 起也在此存档：服务器默认值、个人参数权限、跟随状态与个人值均按 overworld 保存，旧 TOML 的 `hitChancePercent`／`minEligibleEntries` 仅作旧兼容保留、运行时不读取。每只女仆的人设配置和自动聊天档案另存女仆 AI 数据 NBT，不经过玩家设置
- forge 线针对 TLM 的 mixin 注入通常需显式 `remap=false`（FG6 Mixin AP 按 searge 解析 deobf 依赖方法会失败；TLM 类 prod 不混淆）；原版调用点应单独核对，如 `ItemFilmMixin` 内 `Level.addFreshEntity` 的 `@At` 在 Forge 为 `remap=true`。AIChatScreenMixin 继承 Screen 使 `this.addRenderableWidget(...)` 合法、随 reobf 重映射为 `m_142416_`；宿主方法是否 static 也须核对，编译不能代替运行时注入验证
- **HTTP 400 陷阱**：自话请求发送前必须调用 `HistoryMessagesCheck.checkMessages(messages)` 清洗未配对的 tool 消息（与 TLM `tryToChat` 同构），否则 OpenAI 兼容 API 拒绝请求；清洗失败时放弃本次触发，绝不向上抛
- **Tool 历史清理红线**：带工具调用的一轮结束后，`assistant(tool_calls)` 与其 `tool` 结果必须按本轮捕获的引用**成对**整批删除；不可改成按 role 扫全表删除（会误删玩家聊天路径的记录）。TLM 1.5.3 的 `checkMessages` 含 `removeUnpairedToolCalls`，会处理未配对的工具调用，但不能用发送前清洗代替本轮生命周期清理；无效配对仍可能导致 API 返回 400
- 欢迎窗口计时必须用 `server.getTickCount()`（全局单调 tick）；各维度 `gameTime` 独立计数，跨维度比较会出现负差
- **限流闸门**：欢迎语全局每秒最多 1 次（`rate_limit.maxTriggerPerSecond`）；自话全局每 5~8 秒（`rate_limit.selfTalkMinIntervalSeconds` / `selfTalkMaxIntervalSeconds`）放行 1 只，**互聊发起者派发共用同一自话闸门**（链式续接不走闸门）；被限流自话**不发请求**、随机退避 8~15 秒重试（玩家聊天框不会出现报错）；欢迎语不进入顺延队列、无随机退避，忙或闸门未放行时窗口内每 tick 重试，窗口过期则错过本次欢迎
- **环境事件过滤**：死亡事件固定过滤为玩家、有主动物、其他女仆——「有主动物」必须按 `OwnableEntity` 判定（马/驴/骡等坐骑不继承 `TamableAnimal` 但同样有主人）；受伤事件必须用**实际掉血**事件（neo `LivingDamageEvent.Post` / forge `LivingDamageEvent`），更早的 incoming/hurt 事件在格挡成功或减免到 0 时仍会以正数触发
- cloth-config 界面通过反射注册（`MaidSelfTalkMod.registerClothConfigIfPresent`），未装 cloth-config 时自动跳过、不影响核心功能
- **预制复用边界**：实验性、玩家默认关闭，单只可继承/强制开/强制关；管理员 `PLAYER_OPTION_ENABLED` 关闸时保留已存偏好。匹配参数分世界级默认与受控玩家覆盖两层：服务器管理员（OP ≥ 2 或单人房主）可设最低可用条数、使用概率与「是否允许个人参数」；玩家在权限开放且取消跟随后才生效个人值，管理员撤回个人参数权限时清空取消跟随表、保留个人数值。入库 UUID 冲突最多重随机两次（共三次尝试），耗尽即放弃本次入库、不淘汰旧条目。只接入自话与欢迎语，不绕过原触发和限流；命中跳过历史/人设规划与正式生成，真实生成成功才追加库存，复用投放不重复入库
- **预制库存与翻译**：机器条目位于 `<世界根>/data/maid_self_talk/prebuilt/generated/<source>/v1-<fingerprint>/entry-<UUID>.md`（YAML 头 + `## Chat`/`## TTS` 正文，schemaVersion 3），按名称与生效人设指纹共享，欢迎语/自话分别限容，仍走 n-of-m 匹配；`createdAt` 缺失或空白时用文件最后修改时间补足并记告警（非标量仍拒绝，文件时间不可用时跳过该文件，绝不用当前时刻）；人类手写条目位于同层 `custom/_global|<女仆显示名>/{welcome,self-talk}/*.md`，只读、不淘汰、不回写 TTS，env 全 key 严格相等（AND）匹配、缺 `chatLanguage` 为语言通配，entryId 由 UTF-8 相对路径派生；旧版 JSON 路径不读取、不迁移、不删除。custom/ 扫描告警覆盖写出 `custom/_diagnostics.md`，有告警具名目录的女仆主人本会话首次上线时收到一条合并私信。SnakeYAML 2.2 经 jarJar 嵌套进产物 jar（FG6 的 jarJar 依赖必须写 maven 版本区间，`2.+` 这类 Gradle 写法构建期即报错）。与女仆 NBT、玩家设置隔离，overworld 存档及停服时写出（custom/ 只在扫描与停服写诊断报告）。匹配与渲染共用一次环境快照；命中后仅按需短翻译缓存 TTS，失败只投放上屏文本，取消不投放；翻译登记为在途计划并受清空/人设变更/卸载/超时取消约束

## Code Navigation

- 如果本地存在 `.XMagent-docs/README.md`，优先按需读取索引。
- 索引不存在时，改用公开架构文档、符号搜索和源码检索。
- 不要因为索引缺失而停止任务。
- 本地索引为独立 Git 仓库，不随主仓库同步；入口、调用链、协议、存储或 Mixin 边界变动后，应同步受影响导航并记录实际核对的双线源码基线。
