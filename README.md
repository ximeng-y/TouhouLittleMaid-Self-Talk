# TLM Self-Talk

给 [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid)（车万女仆）添加「自言自语 / 欢迎语 / 互相对话」能力的附属 Mod。

## 功能

- **女仆自言自语**：主人在线时会随机开口，主人离线但附近有其他玩家时同样会触发
- **欢迎语**：主人上线时女仆主动打招呼，不限距离，已加载区块中的女仆都能触发
- **女仆互相对话**：女仆会随机地与其附近的女仆聊天
- **无人设不说话**：未配置人设的女仆不触发，避免出戏/不可预计的 Token 消耗
- **上下文控制**：自言自语保留条数可配置，当超过条数上限时会自动丢弃前面的自言自语内容，仅保留最后一条。玩家与女仆的主动聊天记录不受影响
- **随机注入游戏情境**：触发自言自语时，会从女仆当前位置、附近实体、装备等情境信息中随机注入几种信息，使自言自语具有随机性
- **单独开关**：每位玩家可单独关闭某只女仆的自言自语；房主可控制总开关（服务器则需要修改配置文件）
- **触发冷却**：可配置的随机触发间隔区间
- **聊天可见**：女仆的自言自语与互聊对附近玩家可见（即使此玩家不是女仆的主人）

## 环境要求

| 加载器 | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

## 安装

1. 安装 mod：下载对应版本的 jar：
    - CurseForge：https://www.curseforge.com/minecraft/mc-mods/touhoulittlemaid-self-talk
    - Modrinth：审核中
2. 启动游戏，打开总开关：女仆 AI 设置 → AI 全局设置 →「女仆自言自语」→ 启用；「女仆互聊」需在其子页单独启用

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

A mod that adds **self-talk / welcome greetings / maid-to-maid chats** to [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) maids.

## Features

- **Maid self-talk**: The maid randomly talks when her owner is online, and also triggers when the owner is offline but other players are nearby
- **Welcome greetings**: The maid greets her owner when they log in — no distance limit; any maid in loaded chunks can trigger
- **Maid-to-maid chat**: A maid will randomly strike up a conversation with maids near her
- **No persona, no talking**: Maids without a persona never trigger, avoiding out-of-character replies and unpredictable token cost
- **Context control**: The number of self-talk entries kept in context is configurable; once the limit is exceeded, earlier self-talk entries are dropped and only the latest one remains. Player-initiated chat history with the maid is unaffected
- **Random in-game context**: When triggering self-talk, a few pieces of context such as current position, nearby entities, and equipment are randomly injected to keep self-talk varied
- **Per-player switch**: Each player can turn off self-talk for an individual maid; the host controls the master switch (on a dedicated server this requires editing the config file)
- **Trigger cooldown**: A configurable random interval range between triggers
- **Visible in chat**: Self-talk and maid-to-maid chats are visible to nearby players (even if they are not the maid's owner)

## Requirements

| Loader | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

## Installation

1. Install the mod: download the jar for your version:
    - CurseForge: https://www.curseforge.com/minecraft/mc-mods/touhoulittlemaid-self-talk
    - Modrinth: under review
2. Launch the game and enable the master switch: Maid AI settings → Global AI settings → "Maid Self-Talk" → Enable; Maid Inter-Chat needs to be enabled separately in its own sub-page

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
