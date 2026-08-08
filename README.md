# TLM Self-Talk

给 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）添加「自言自语 / 欢迎语」的 NeoForge 附属 Mod。

## 功能

- **女仆自言自语**：主人在线/主人离线但附近有玩家时，女仆会随机自言自语
- **欢迎语**：主人上线时女仆会向主人打招呼，不限距离，加载区块里的女仆都能触发
- **无人设不说话**：无人设的女仆不触发、不自动生成，避免自动生成人设带来的 token 消耗
- **历史有上限**：自话记录超过配置的条数上限时，自动遗忘旧的、只保留最近一次，防止上下文膨胀；玩家与女仆的主动聊天记录不受影响
- **随机注入游戏情境**：随机注入位置 / 附近实体 / 装备等情境信息，提高自话随机性
- **玩家单独开关**：每个玩家可单独关闭女仆的自言自语；房主可控制总开关（服务器环境需修改配置文件），关闭后玩家设置项自动置灰
- **触发冷却**：随机时间间隔触发（范围可配置）；玩家和女仆聊天会重置自话冷却，避免两边抢话
- **聊天可见**：自话对附近玩家可见（即使该玩家不是女仆的主人）

## 环境要求

| 项目 | 版本 |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Touhou Little Maid | 1.5.3 |
| Java | 21 |

## 安装

1. 装 NeoForge 21.1.x 和 Touhou Little Maid 1.5.3
2. 下载 jar 文件
3. 打开总开关：女仆 AI 设置 → AI 全局设置 →「女仆自言自语」→ 启用；或直接改配置文件的 `enabled`

## 使用

### 配置

女仆 AI 聊天设置 → AI 全局设置 →「女仆自言自语」：

- **状态 1 / 状态 2**：主人在线 / 离线时的开关、最小与最大触发间隔（秒，填入式、带范围校验）、玩家半径、自话保留条数
- **欢迎语**：主人上线后的打招呼触发窗口

提示词修改不在配置界面开放；若确需修改（通常不建议），可编辑 `config/maid_self_talk-common.toml` 的 `[prompt]` 段。

### 玩家独立设置

对着女仆打开 AI 聊天输入界面（通常是按 T 键），点左侧 💬 按钮，单独开关这只女仆的自言自语。

## 许可

[MIT](LICENSE)

---

# TLM Self-Talk (English)

A NeoForge mod that adds **self-talk / welcome greetings** to [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) maids.

## Features

- **Maid self-talk**: The maid randomly talks to herself when the owner is online, or when the owner is offline but other players are nearby
- **Welcome greetings**: The maid greets her owner when they log in — no distance limit; any maid in loaded chunks can trigger
- **No persona, no talking**: Maids without a persona never trigger and none is auto-generated, avoiding the token cost of auto-generating a persona
- **Bounded history**: When self-talk history exceeds the configured limit, old entries are forgotten and only the latest one is kept, preventing context bloat; player-initiated chat history with the maid is unaffected
- **Random in-game context**: Randomly injects context such as location / nearby entities / equipment to make self-talk more varied
- **Per-player switch**: Each player can turn off a maid's self-talk individually; the host controls the master switch (on a dedicated server this is done by editing the config file), and the player's option greys out automatically once it is disabled
- **Trigger cooldown**: Triggers at random time intervals (configurable range); chatting with the maid resets the self-talk cooldown so they never speak at once
- **Visible in chat**: Self-talk is visible to nearby players (even if they are not the maid's owner)

## Requirements

| Item | Version |
| --- | --- |
| Minecraft | 1.21.1 |
| NeoForge | 21.1.x |
| Touhou Little Maid | 1.5.3 |
| Java | 21 |

## Installation

1. Install NeoForge 21.1.x and Touhou Little Maid 1.5.3
2. Download the jar file
3. Enable the master switch: Maid AI settings → Global AI settings → "Maid Self-Talk" → Enable; or set `enabled` in the config file directly

## Usage

### Configuration

Maid AI chat settings → Global AI settings → "Maid Self-Talk":

- **State 1 / State 2**: enable switch, min/max trigger interval (seconds, typed input with range validation), player radius, and self-talk keep count for owner online / offline
- **Welcome**: the greeting trigger window after the owner logs in

Prompt edits are not exposed in the UI — if you really need to change them (usually not recommended), edit the `[prompt]` section of `config/maid_self_talk-common.toml` directly.

### Per-player settings

Open the maid's AI chat input screen (usually by pressing T) and click the 💬 button on the left to toggle self-talk for that maid.

## License

[MIT](LICENSE)
