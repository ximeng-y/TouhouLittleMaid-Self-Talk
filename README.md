# TLM Self-Talk

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

为 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）女仆 AI 添加**自言自语 / 欢迎语**能力的 NeoForge 附属 Mod。

## 功能

- **双状态触发**：主人在线（状态 1）与主人离线但附近有玩家（状态 2）分开配置，各自独立控制开关、触发间隔与玩家范围
- **欢迎语**：主人上线时女仆打招呼，不受半径限制，加载区块内的女仆均可触发
- **人设感知**：未设置人设（无自定义设定且模型无默认设定）的女仆不触发、不自动生成，避免无意义的 AI 输出
- **上下文隔离**：自话提示词作为 user 消息发送（与玩家 chat 相同格式，保证上下文前缀缓存一致），但不写入聊天记录、不显示在女仆聊天记录界面中；AI 回复写入记录，UI 上看起来就是女仆的纯输出
- **忘记机制**：自话上下文按条数窗口计数，超过上限时遗忘旧内容，仅保留最近一次自话，防止上下文无限膨胀
- **提示词智能注入**：随机纳入几类游戏情境信息（位置 / 附近实体 / 装备等）；主人在附近时强制注入工作状态；按主人距离切换自话提示词
- **语言跟随**：自话语言默认跟随游戏语言，也可在配置中固定为指定语言
- **玩家独立设置**：每个玩家可单独关闭女仆的自言自语；房主关闭总开关后玩家设置项自动置灰
- **触发冷却**：随机触发间隔（可配置范围）；玩家对女仆发起 chat 时自动重置自话冷却，避免两者同时抢话
- **聊天框展示**：自话内容对附近玩家可见；主人侧由原生聊天记录显示，不产生重复消息
- **配置界面**：接入 TLM 女仆 AI 设置，新增「女仆自言自语」配置页（含状态 1 / 状态 2 / 欢迎语 / 自话提示词子页），间隔采用填入式并带范围校验
- **调试命令**：`/tlm_ai_pro debug test_self_talk [<maid>]`，绕过冷却与状态机直接触发一次自话，便于测试

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Touhou Little Maid | 1.5.3 |
| Java | 21 |

> Cloth Config（15.0.140+）为可选依赖：安装后可在游戏内配置界面调整设置；未安装时配置界面自动禁用，仅能修改 `config/maid_self_talk-common.toml`。

## 安装

1. 安装 NeoForge 21.1.x 与 Touhou Little Maid 1.5.3
2. 从 [Releases](../../releases) 下载成品 jar，放入 `mods/` 目录
3. 启动游戏，开启总开关：女仆 AI 设置 → AI 全局设置 →「女仆自言自语」→ 启用；或直接修改配置文件中的 `enabled`

## 使用

### 配置

女仆 AI 聊天设置 → AI 全局设置 →「女仆自言自语」：

- **状态 1 / 状态 2**：主人在线 / 离线时的触发开关、最小与最大触发间隔（秒）、玩家半径、自话保留条数
- **欢迎语**：主人登录后打招呼的触发窗口
- **自话提示词 / 欢迎语提示词**：自定义提示词，系统会随机纳入情境信息并注入语言指令，请保证提示词引导女仆说出贴合人设、不同质化的话

### 玩家独立设置

对着女仆打开 AI 聊天输入界面（与选 AI 模型同一悬浮 UI），点击左侧新增的 💬 按钮，即可单独开启 / 关闭该女仆的自言自语。

### 调试命令

```
/tlm_ai_pro debug test_self_talk [<maid>]
```

指定女仆（缺省取附近最近的女仆）立即触发一次自话，绕过冷却与状态机，保留人设与 AI 可用性等硬性前置检查。

## 许可

本项目采用 [MIT](LICENSE) 许可。

---

# TLM Self-Talk (English)

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A NeoForge addon that gives [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) maids the ability to **talk to themselves** and **greet their owner**.

## Features

- **Two-state triggers**: Owner online (State 1) and owner offline with nearby players (State 2) are configured separately, each with its own enable switch, trigger interval and player range
- **Welcome greetings**: The maid greets the owner when they log in, not limited by range — any maid in loaded chunks can trigger
- **Persona-aware**: Maids without a persona (no custom setting and no model default setting) never trigger, and none is auto-generated
- **Context isolation**: The self-talk prompt is sent as a user message (same format as player chat, keeping the context prefix cache consistent) but is never written into chat history nor shown in the maid's chat record UI; only the AI reply enters the history, so it looks like a pure maid output
- **Forgetting mechanism**: Self-talk context is counted as a window; when it exceeds the limit, old entries are forgotten and only the latest self-talk is kept, preventing unbounded context growth
- **Smart prompt injection**: Several kinds of in-game context (location / nearby entities / equipment, etc.) are randomly picked and injected; the maid's work status is always injected when the owner is nearby; the self-talk prompt switches based on distance to the owner
- **Language following**: Self-talk language follows the game language by default, and can be pinned to a fixed language in the config
- **Per-player settings**: Each player can turn off a maid's self-talk individually; the option greys out automatically when the host disables the master switch
- **Trigger cooldown**: Random trigger intervals (configurable range); starting a chat with the maid resets the self-talk cooldown so both never speak at once
- **Chat display**: Self-talk is visible to nearby players; the owner sees it through the native chat record UI, so no duplicate messages appear
- **Config UI**: A new "Maid Self-Talk" page is added to the TLM maid AI settings (with State 1 / State 2 / Welcome / Self-Talk Prompt sub-pages); intervals use typed input fields with range validation
- **Debug command**: `/tlm_ai_pro debug test_self_talk [<maid>]` triggers one self-talk immediately, bypassing the cooldown and state machine but keeping the hard pre-checks (persona & AI availability)

## Requirements

| Item | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Touhou Little Maid | 1.5.3 |
| Java | 21 |

> Cloth Config (15.0.140+) is optional: with it installed you can tweak settings in-game; without it the config UI is disabled automatically and you can only edit `config/maid_self_talk-common.toml`.

## Installation

1. Install NeoForge 21.1.x and Touhou Little Maid 1.5.3
2. Download the release jar from [Releases](../../releases) and put it into the `mods/` folder
3. Launch the game and enable the master switch: Maid AI settings → Global AI settings → "Maid Self-Talk" → Enable; or set `enabled` in the config file directly

## Usage

### Configuration

Maid AI chat settings → Global AI settings → "Maid Self-Talk":

- **State 1 / State 2**: enable switch, min/max trigger interval (seconds), player radius and self-talk keep count for owner online / offline
- **Welcome**: the trigger window after the owner logs in
- **Self-Talk Prompt / Welcome Prompt**: customize the prompts; the system randomly injects context info and a language instruction. Keep the prompt guiding the maid to speak in persona, non-repetitive words

### Per-player settings

Open the AI chat input screen of a maid (the same floating UI where you pick the AI model) and click the new 💬 button on the left to toggle self-talk for that maid.

### Debug command

```
/tlm_ai_pro debug test_self_talk [<maid>]
```

Triggers one self-talk immediately for the given maid (or the nearest one nearby), bypassing the cooldown and state machine while keeping the hard pre-checks (persona & AI availability).

## License

Licensed under the [MIT](LICENSE) license.
