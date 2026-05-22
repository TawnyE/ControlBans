<div align="center">

# ControlBans

**Advanced, next-generation punishment & moderation system for Minecraft networks.**

[![Version](https://img.shields.io/badge/version-5.4-blue?style=flat-square)](https://github.com/TawnyE/ControlBans/releases)
[![Modrinth](https://img.shields.io/badge/Modrinth-free-brightgreen?style=flat-square)](https://modrinth.com/user/Tawny)
[![License](https://img.shields.io/badge/license-Proprietary-red?style=flat-square)](./LICENSE)
[![Folia](https://img.shields.io/badge/Folia-supported-brightgreen?style=flat-square)](https://github.com/PaperMC/Folia)
[![Paper](https://img.shields.io/badge/Paper-1.20%2B-orange?style=flat-square)](https://papermc.io)
[![Java](https://img.shields.io/badge/Java-17%2B-yellow?style=flat-square)](https://adoptium.net)

[Modrinth](https://modrinth.com/plugin/controlbans) · [Discord](https://discord.gg/your-server) · Free & Open

</div>

---

## Overview

ControlBans is a full-featured moderation platform built for serious Minecraft networks. It replaces lightweight ban plugins with a proper punishment infrastructure — fully async, multi-database, proxy-aware, and deeply configurable. Whether you're running a small SMP or a large network, ControlBans gives your staff team the tools to moderate with precision and accountability.

---

## Features

### 🔨 Punishment System

A complete punishment toolkit covering every moderation action your staff might need:

| Type | Commands | Notes |
|---|---|---|
| **Ban** | `/ban`, `/tempban`, `/unban` | Permanent & temporary |
| **IP Ban** | `/ipban`, `/unipban` | Bans all accounts on the IP |
| **Mute** | `/mute`, `/tempmute`, `/unmute` | Permanent & temporary |
| **IP Mute** | `/ipmute`, `/unipmute` | Mutes all accounts on the IP |
| **Shadow Mute** | `/shadowmute` | Player sees their own chat; nobody else does |
| **Voice Mute** | `/voicemute`, `/tempvoicemute`, `/voiceunmute` | Requires Simple Voice Chat |
| **Warn** | `/warn` | Logged to history |
| **Kick** | `/kick` | With broadcast support |
| **Freeze** | `/freeze` | Locks player movement for screenshares |
| **Void Jail** | `/voidjail`, `/unvoidjail` | Teleports player to a configurable jail location |
| **Skin Ban** | `/banskin`, `/unbanskin` | Forces default skin on the offending player |

All punishment commands support a `-s` silent flag to suppress the public broadcast while still alerting staff privately.

---

### 🗄️ Multi-Database Backend

Choose the storage backend that fits your infrastructure. All SQL backends use HikariCP connection pooling.

- **SQLite** — zero-config default, great for single servers
- **H2** — embedded, slightly faster than SQLite
- **MySQL / MariaDB** — standard choice for networks
- **PostgreSQL** — full support
- **MongoDB** — document-based alternative

On-startup schema migrations are handled automatically via `SchemaMigrator`. A built-in database janitor periodically purges expired records based on configurable retention windows.

---

### ⚡ Redis Pub/Sub Sync

Enable Redis to synchronise punishments in real-time across all nodes in a network. When a ban is issued on one backend server, every other server learns about it instantly via pub/sub — no polling, no lag.

```yaml
redis:
  enabled: true
  host: localhost
  port: 6379
  pubsub: true
  cache-ttl: 300
```

---

### 🌐 Proxy Support

ControlBans ships a **BungeeCord** entry point and a **Velocity** entry point out of the box. Plugin messages keep the backend servers and the proxy in sync without requiring a separate proxy-side install.

---

### 🛡️ Alt Detection

- Tracks IP history on every login
- `/alts <player>` opens a paginated GUI showing all accounts tied to the same IPs
- Optional automatic punishment propagation: when a player is banned, their detected alts can be banned too, with configurable safety thresholds to prevent false positives

---

### 📋 Staff Tools

| Command | Description |
|---|---|
| `/history <player>` | Full paginated punishment history with GUI |
| `/check <player>` | Quick status check — active bans, mutes, and warnings |
| `/blame <staff>` | Every punishment issued by a specific staff member |
| `/banlist` / `/mutelist` | Paginated active ban and mute lists |
| `/note <player>` | Private staff notes attached to a player profile |
| `/staff` | List of currently online staff |
| `/punish <player>` | Opens the click-through punishment GUI |
| `/report <player>` | Player-facing report submission |
| `/reports` | Staff-facing report queue with resolve workflow |

---

### 🤖 AutoMod

Rule-based automatic chat moderation running server-side before any message is broadcast:

- **Anti-spam** — rate limiting with configurable thresholds
- **Anti-caps** — normalises messages above a caps percentage
- **Custom rules** — regex-based pattern matching (e.g. block Discord invite links, profanity, etc.) with per-rule actions (warn, mute, kick, ban) and custom messages

---

### 📢 Notification System

- **Broadcast** — public server-wide punishment announcements (toggleable)
- **Staff alerts** — always-on private alert to all staff with `controlbans.alerts.receive`, regardless of silent mode
- **Action bar & boss bar** — in-game feedback for muted/voice-muted players
- **Discord webhook** — rich embeds posted to a Discord channel on every punishment and unban event

---

### 🔗 Integrations

| Integration | What it does |
|---|---|
| **Discord Webhook** | Posts punishment embeds to a configured channel |
| **DiscordSRV** (softdepend) | Hooks into existing DiscordSRV for channel routing |
| **Geyser / Floodgate** | Correctly resolves Bedrock player UUIDs and usernames |
| **Simple Voice Chat** | Powers the `/voicemute` command family |
| **MCBlacklist** | Periodically syncs the MCBlacklist global ban database and auto-bans matching players |

---

### 🌍 Localization

ControlBans ships with **10 built-in locales**. Every player-facing message is fully translatable. Set your locale with a single config key.

| Language | Code |
|---|---|
| English | `en` |
| German | `de` |
| Spanish | `es` |
| French | `fr` |
| Italian | `it` |
| Japanese | `ja` |
| Dutch | `nl` |
| Portuguese | `pt` |
| Russian | `ru` |
| Turkish | `tr` |

---

### 📦 Import & Export

Migrate from another plugin without losing your punishment history:

- **Essentials** — reads userdata YAML files directly
- **LiteBans** — connects to the LiteBans database
- **AdvancedBan** — reads from the AdvancedBan database

Export your full punishment dataset to JSON via `/controlbans export`.

---

### ⚖️ Escalation Engine

Define automatic escalation ladders so repeated offences result in progressively harsher punishments — all without manual staff intervention. Supports reason-based filtering, configurable time windows, and custom level definitions.

---

### 🔔 Appeals

Players can submit mute appeals in-game via `/appeal <message>`. Configurable submission cooldowns, maximum submissions per window, and a staff-facing review workflow.

---

## Installation

1. Drop `ControlBans.jar` into your server's `plugins/` folder.
2. Start the server once to generate config files.
3. Edit `plugins/ControlBans/config.yml` — at minimum, configure your `database` block.
4. Run `/controlbans reload` or restart the server.

**Requirements:**
- Paper (or a Paper fork) 1.20+
- Java 17+
- Folia is fully supported

---

## Configuration

The `config.yml` is heavily commented and self-documenting. Key sections:

```
config.yml
├── language          — locale selection
├── database          — backend type + connection details
├── redis             — pub/sub sync for networks
├── punishments       — defaults, durations, broadcasts
├── staff-alerts      — per-action alert toggles
├── alts-punish       — alt detection + auto-punishment
├── automod           — spam, caps, custom regex rules
├── integrations      — Discord, Geyser, MCBlacklist, Voice Chat
├── cache             — in-memory Caffeine cache settings
├── bossbar           — voice mute boss bar display
├── appeals           — cooldowns and submission limits
├── escalation        — repeat-offence ladder config
├── void-jail         — jail location + allowed commands
└── import            — source plugin connection details
```

---

## Permissions

| Permission | Description | Default |
|---|---|---|
| `controlbans.*` | All permissions | — |
| `controlbans.ban` | Ban players | op |
| `controlbans.tempban` | Temporary ban | op |
| `controlbans.unban` | Unban players | op |
| `controlbans.mute` | Mute players | op |
| `controlbans.tempmute` | Temporary mute | op |
| `controlbans.shadowmute` | Shadow mute | op |
| `controlbans.shadowmute.see` | See shadow-muted messages | op |
| `controlbans.kick` | Kick players | op |
| `controlbans.warn` | Warn players | op |
| `controlbans.ban.ip` | IP ban | op |
| `controlbans.mute.ip` | IP mute | op |
| `controlbans.freeze` | Freeze players | op |
| `controlbans.voidjail` | Void jail | op |
| `controlbans.banskin` | Skin ban | op |
| `controlbans.voicemute` | Voice mute | op |
| `controlbans.tempvoicemute` | Temporary voice mute | op |
| `controlbans.history` | View punishment history | op |
| `controlbans.check` | Check player status | op |
| `controlbans.alts` | View alt accounts | op |
| `controlbans.blame` | View staff history | op |
| `controlbans.note` | Manage player notes | op |
| `controlbans.banlist` | View ban list | op |
| `controlbans.mutelist` | View mute list | op |
| `controlbans.punish` | Punishment GUI | op |
| `controlbans.report.resolve` | Resolve player reports | op |
| `controlbans.alerts.receive` | Receive staff alerts | op |
| `controlbans.notify.silent` | See silent punishment broadcasts | op |
| `controlbans.exempt` | Exempt from punishments | false |
| `controlbans.appeal` | Submit appeals | true |
| `controlbans.report` | Report players | true |
| `controlbans.staff` | View online staff | true |
| `controlbans.bypass.chat` | Bypass chat lock / slowmode | op |
| `controlbans.bypass.spam` | Bypass AutoMod spam check | op |
| `controlbans.bypass.caps` | Bypass AutoMod caps check | op |
| `controlbans.admin` | Admin commands (reload, import, export) | op |
| `controlbans.admin.rollback` | Rollback staff actions | op |

---

## Building from Source

```bash
git clone https://github.com/TawnyE/ControlBans.git
cd ControlBans/ControlBans
mvn clean package
```

The shaded JAR will be output to `target/`.

---

## License

ControlBans is proprietary software. Redistribution, resale, or modification without written permission is prohibited. See [LICENSE](./LICENSE) for full terms.

---

<div align="center">

Made by [TawnyE](https://github.com/TawnyE) · Free on [Modrinth](https://modrinth.com/plugin/controlbans)

</div>
