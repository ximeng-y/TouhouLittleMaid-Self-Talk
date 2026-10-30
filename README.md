# TLM Self-Talk 

English | [简体中文](docs/README/README-zh_cn.md)

A mod that adds **self-talk / welcome greetings / maid-to-maid chats** to [Touhou Little Maid](https://github.com/TartaricAcid/TouhouLittleMaid) maids.

## Features

- **Maid self-talk**: The maid randomly talks when her owner is online, and also triggers when the owner is offline but other players are nearby
- **Welcome greetings**: The maid greets her owner when they log in — no distance limit; any maid in loaded chunks can trigger
- **Maid-to-maid chat**: A maid will randomly strike up a conversation with maids near her
- **No persona, no talking**: Maids without a persona never trigger, avoiding out-of-character replies and unpredictable token cost
- **Structured persona**: The persona page supports whole persona and structured persona modes; the structured mode has four sections (identity, personality, background, hobbies) and can selectively inject persona sections as the situation requires
- **Context control**: The number of self-talk entries kept in context is configurable; once the limit is exceeded, earlier self-talk entries are dropped and only the latest one remains. Player-initiated chat history with the maid is unaffected
- **Random in-game context**: When triggering self-talk, a few pieces of context such as current position, nearby entities, and equipment are randomly injected to keep self-talk varied
- **Environment awareness**: Maids notice nearby deaths, player injuries, their own injuries / burning / drowning, and their own shrine revivals and kills, and bring them into the next self-talk or inter-chat
- **Custom Prompt**: Customize the prompts used for self-talk / maid-to-maid chats for your own maids; supports both an all-maids setting and independent per-maid settings
- **Tool calls**: Off by default. When enabled, maids may switch states, fetch environment info, etc. on their own during self-talk / maid-to-maid chats. **Incurs extra token cost**
- **Per-player switch**: Each player can turn off self-talk for an individual maid; the host controls the master switch (on a dedicated server this requires editing the config file)
- **Trigger cooldown**: A configurable random interval range between triggers
- **Quiet while sleeping**: Allows maids to stay quiet while sleeping — self-talk and inter-chat won't trigger while they sleep
- **Visible in chat**: Self-talk and maid-to-maid chats are visible to nearby players (even if they are not the maid's owner)
- **Prebuilt content (experimental)**: see the [prebuilt content guide](docs/custom-prebuild-message/en_us.md)

## Requirements

| Loader | Minecraft | Java | Touhou Little Maid |
| --- | --- | --- | --- |
| NeoForge | 1.21.1 | 21 | 1.5.3 |
| Forge | 1.20.1 | 17 | 1.5.3 |

## Installation

1. Install the mod: download the jar for your version (both client and server must install it, using the same platform and version)
2. Launch the game and enable the master switch: Maid AI settings → Global AI settings → "Maid Self-Talk" → Enable; Maid Inter-Chat needs to be enabled separately in its own sub-page

## Usage

### Configuration

Maid AI chat settings → Global AI settings → "Maid Self-Talk":

- **State 1 / State 2**: enable switch, min/max trigger interval (seconds, typed input with range validation), player radius, and self-talk keep count for owner online / offline
- **Welcome**: the greeting trigger window after the owner logs in
- **Tool Calls**: off by default; when on, maids may call tools during self-talk / inter-chat
- **Environment Awareness**: controls whether maids notice nearby deaths, player injuries, their own injuries / burning / drowning, and creatures they kill themselves

Maid AI chat settings → Global AI settings → "Maid Inter-Chat":

- **Basics**: master switch (off by default), min/max trigger interval (seconds), player range (blocks), and maid range (blocks)
- **Context & chain**: keep rounds (a Q/A counts as one round; only the latest message remains when exceeded), chain probability (chance to continue after each reply), max chain length (hard cap on messages per session, guarding against maxed-out chain probability)

### Per-player settings

Open the maid's AI chat input screen (usually by pressing T) and click the 💬 button on the left to open the Maid Settings:

- **Self-talk / Inter-chat**: turn self-talk / inter-chat off individually
- **Sleep Quiet**: when on, self-talk / inter-chat no longer trigger while the maid is sleeping
- **Custom Prompt**: fill in separate prompts for all maids and for this maid
- **Tool**: global and per-maid switches; when enabled, this maid's self-talk / inter-chat can call tools
- **Context Settings**: choose per entry whether environment info enters the random pool, is always included, or is never included; history context can be full, compact, or retrieval mode
- **Thinking Bubbles**: controls only the waiting, tool-status and reasoning bubbles you see during self-talk / inter-chat; final replies, welcome messages and normal chat are unaffected
- **Prebuilt content (experimental)**: off by default; when on, generated content is automatically saved as a prebuilt source and reused when conditions match; see the [prebuilt content guide](docs/custom-prebuild-message/en_us.md) for custom prebuilt content

## License

[MIT](LICENSE)
