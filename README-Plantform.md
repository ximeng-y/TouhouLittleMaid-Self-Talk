# TLM Self-Talk

给 Touhou Little Maid（车万女仆）添加「自言自语 / 欢迎语 / 互相对话」的附属 Mod。

## 功能

- **女仆自言自语**：主人在线/主人离线但附近有玩家时，女仆会随机自言自语
- **欢迎语**：主人上线时女仆会向主人打招呼，不限距离，加载区块里的女仆都能触发
- **女仆互相对话**：女仆会随机地与其附近的女仆聊天
- **无人设不说话**：无人设的女仆不触发、不自动生成，避免自动生成人设带来的 token 消耗
- **历史有上限**：自话记录超过配置的条数上限时，自动遗忘旧的、只保留最近一次，防止上下文膨胀
- **随机注入游戏情境**：随机注入位置 / 附近实体 / 装备等情境信息，提高自话随机性
- **玩家单独开关**：每个玩家可单独开关女仆的自言自语与互聊；房主可控制总开关
- **触发冷却**：可配置的随机触发间隔区间
- **聊天可见**：自话与互聊对附近玩家可见

## 环境要求

| 加载器 | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

## 安装

1. 安装 mod：下载对应版本的 jar
2. 打开总开关：女仆 AI 设置 → AI 全局设置 →「女仆自言自语」→ 启用；「女仆互聊」需在其子页单独启用；或直接改配置文件的 `enabled`

## 使用

### 配置

女仆 AI 聊天设置 → AI 全局设置 →「女仆自言自语」：

- **状态 1 / 状态 2**：主人在线 / 离线时的开关、最小与最大触发间隔（秒，填入式、带范围校验）、玩家半径、自话保留条数
- **欢迎语**：主人上线后的打招呼触发窗口

女仆 AI 聊天设置 → AI 全局设置 →「女仆互聊」：

- **基础**：总开关（默认关闭）、最小与最大触发间隔（秒）、玩家距离（格）、女仆间距离（格）
- **上下文与连续对话**：保留轮数（问/答算一轮，超限后仅保留最近一条消息）、连续概率（每轮回答后按此概率继续）、最大链长（一次互聊的消息条数上限，连续概率拉满时的兜底）

### 玩家独立设置

对着女仆打开 AI 聊天输入界面（通常是按 T 键），点左侧 💬 按钮，单独开关这只女仆的自言自语/互聊。

## 许可

[MIT](LICENSE)

---

# TLM Self-Talk (English)

A mod that adds **self-talk / welcome greetings / maid-to-maid chats** to Touhou Little Maid maids.

## Features

- **Maid self-talk**: The maid randomly talks to herself when the owner is online, or when the owner is offline but other players are nearby
- **Welcome greetings**: The maid greets her owner when they log in — no distance limit; any maid in loaded chunks can trigger
- **Maid-to-maid chat**: A maid will randomly strike up a conversation with maids near her
- **No persona, no talking**: Maids without a persona never trigger and none is auto-generated, avoiding the token cost of auto-generating a persona
- **Bounded history**: When self-talk history exceeds the configured limit, old entries are forgotten and only the latest one is kept, preventing context bloat
- **Random in-game context**: Randomly injects context such as location / nearby entities / equipment to make self-talk more varied
- **Per-player switch**: Each player can toggle self-talk and maid-to-maid chat for individual maids; the host controls the master switch
- **Trigger cooldown**: A configurable random interval range between triggers
- **Visible in chat**: Self-talk and maid-to-maid chats are visible to nearby players

## Requirements

| Loader | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

## Installation

1. Install the mod: download the jar for your version
2. Enable the master switch: Maid AI settings → Global AI settings → "Maid Self-Talk" → Enable; Maid Inter-Chat needs to be enabled separately in its own sub-page; or set `enabled` in the config file directly

## Usage

### Configuration

Maid AI chat settings → Global AI settings → "Maid Self-Talk":

- **State 1 / State 2**: enable switch, min/max trigger interval (seconds, typed input with range validation), player radius, and self-talk keep count for owner online / offline
- **Welcome**: the greeting trigger window after the owner logs in

Maid AI chat settings → Global AI settings → "Maid Inter-Chat":

- **Basics**: master switch (off by default), min/max trigger interval (seconds), player range (blocks), and maid range (blocks)
- **Context & chain**: keep rounds (a Q/A counts as one round; only the latest message remains when exceeded), chain probability (chance to continue after each reply), max chain length (hard cap on messages per session, guarding against maxed-out chain probability)

### Per-player settings

Open the maid's AI chat input screen (usually by pressing T) and click the 💬 button on the left to toggle self-talk / maid-to-maid chat for that maid.

## License

[MIT](LICENSE)
