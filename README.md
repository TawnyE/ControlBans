![Maintenance](https://img.shields.io/badge/Update_Frequency-Monthly-yellow)
> ⚠️ **Disclaimer**  
> This repository is updated **once per month**.  
> For the **latest version**, please download from:  
> • [BuiltByBit](https://builtbybit.com/resources/controlbans.78397/)  
> • [Spigot](https://www.spigotmc.org/resources/controlbans.129646/)  
> • [Modrinth](https://modrinth.com/plugin/controlbans)

# ControlBans

<p align="center">
  <strong>A modern, high-performance, and developer-friendly punishment system for Paper servers.</strong>
  <br />
  <br />
  <a href="https://github.com/TawnyE/ControlBans/actions">
    <img src="https://img.shields.io/github/actions/workflow/status/TawnyE/ControlBans/build.yml?branch=main&style=for-the-badge" alt="Build Status">
  </a>
  <a href="https://github.com/TawnyE/ControlBans/releases/latest">
    <img src="https://img.shields.io/github/v/release/TawnyE/ControlBans?style=for-the-badge" alt="Latest Release">
  </a>
  <a href="https://discord.gg/xRchyJFkBG">
    <img src="https://img.shields.io/badge/Discord-Join-7289DA?style=for-the-badge&logo=discord" alt="Discord">
  </a>
  <a href="https://ko-fi.com/devtawny">
    <img src="https://img.shields.io/badge/Support-Ko--fi-red?style=for-the-badge&logo=kofi" alt="Support on Ko-fi">
  </a>
</p>

---

Tired of bloated, complex, or outdated punishment plugins? **ControlBans** is a modern, feature-compact, and high-performance punishment system designed as a drop-in replacement for plugins like EssentialsBans, AdvancedBans, and even LiteBans.

Built with performance as the top priority, ControlBans uses asynchronous database queries and the industry-leading HikariCP connection pool to ensure your server never lags from a moderation command. Best of all, it uses a **LiteBans-compatible SQL schema**, meaning it works out-of-the-box with web interfaces and tools you already use, like NamelessMC.

## ✨ Features

-   ✅ **Full Punishment Suite:** Permanent/temporary bans and mutes, warnings, and kicks.
-   🚫 **IP Punishments:** Ban or mute players by their IP address to stop alternate accounts.
-   🕵️ **Alternate Account Detection:** Automatically detects and links accounts that share the same IP address.
-   📜 **Comprehensive History:** Check any player's full punishment history with a simple command.
-   🤫 **Advanced Silent Mode:** Punishments can be broadcast *only* to staff members with permission, keeping public chat clean while keeping your team informed.
-   🗃️ **Broad Database Support:** Works with MySQL, MariaDB, PostgreSQL, and SQLite out of the box.
-   ⚡ **High Performance:** All database queries are handled asynchronously to prevent any lag.
-   🔄 **LiteBans Schema Compatibility:** A true drop-in replacement. Your existing web integrations will continue to work seamlessly.
-   🔗 **Extensive Integration:** Optional hooks for DiscordSRV and Geyser/Floodgate.
-   🌐 **Built-in Web Viewer:** An optional, lightweight web panel to view punishments from your browser.
-   🚚 **Easy Migration:** A simple, powerful import system to bring your bans over from vanilla (`banned-players.json`) and other plugins.

---

## 🛣️ Roadmap Highlights

-   🔁 Automatic escalation
-   ⏰ Scheduled punishments
-   ♻️ Warn decay
-   🗂️ Punishment category system

---

## 🚀 Installation

### For Server Admins
1.  Download the latest release from the [Releases](https://github.com/TawnyE/ControlBans/releases) page or from [BuiltByBit](https://builtbybit.com/resources/controlbans.78397/).
2.  Place the `ControlBans-1.0.0.jar` file into your server's `/plugins` directory.
3.  Restart your server. The plugin will generate its default configuration files.
4.  Open `plugins/ControlBans/config.yml` and configure your database settings.
5.  Restart the server one more time to connect to the database.

### For Developers (Building from Source)
1.  **Prerequisites:**
    *   Java JDK 17 or newer
    *   Apache Maven
2.  Clone the repository: `git clone https://github.com/TawnyE/ControlBans.git`
3.  Navigate to the project directory: `cd ControlBans`
4.  Build the plugin using Maven: `mvn clean install`
5.  The compiled JAR file will be located in the `/target` directory.

---

## 💻 Commands & Permissions

The `-s` flag can be used in punishment commands to toggle between public and staff-only (silent) broadcasts.

| Command                                        | Description                                     | Permission                    |
| ---------------------------------------------- | ----------------------------------------------- | ----------------------------- |
| `/ban <player> [reason]`                       | Permanently bans a player.                      | `controlbans.ban`             |
| `/tempban <player> <time> [reason]`            | Temporarily bans a player (e.g., `1d2h3m`).     | `controlbans.tempban`         |
| `/ipban <player\|ip> <time> [reason]`          | Bans an IP address. Use `perm` for permanent.   | `controlbans.ban.ip`          |
| `/unban <player>`                              | Unbans a player.                                | `controlbans.unban`           |
| `/mute <player> [reason]`                      | Permanently mutes a player.                     | `controlbans.mute`            |
| `/tempmute <player> <time> [reason]`           | Temporarily mutes a player.                     | `controlbans.tempmute`        |
| `/ipmute <player\|ip> <time> [reason]`         | Mutes an IP address. Use `perm` for permanent.  | `controlbans.mute.ip`         |
| `/unmute <player>`                             | Unmutes a player.                               | `controlbans.unmute`          |
| `/warn <player> [reason]`                      | Warns a player.                                 | `controlbans.warn`            |
| `/kick <player> [reason]`                      | Kicks a player from the server.                 | `controlbans.kick`            |
| `/history <player>`                            | Views the punishment history of a player.       | `controlbans.history`         |
| `/alts <player>`                               | Checks for accounts sharing the same IP.        | `controlbans.alts`            |
| `/check <player>`                              | Checks the current ban/mute status of a player. | `controlbans.check`           |
| `/controlbans import <type>`                   | Imports data from another ban system.           | `controlbans.import`          |
| `/controlbans reload`                          | Reloads the configuration file.                 | `controlbans.admin`           |

**Staff Notification Permission:**
To receive silent punishment alerts, staff members need the permission: `controlbans.notify.silent`

---

## ⚙️ Configuration

ControlBans is designed to be powerful yet easy to configure. The most important section is the `database` block. By default, the plugin uses SQLite for a quick and easy setup. For larger servers, we recommend switching to MySQL or MariaDB.

The `silent-by-default` option allows you to reverse the behavior of the `-s` flag, making punishments silent by default and public only when specified.

<details>
<summary><b>Click to view the default config.yml</b></summary>

```yaml
# ControlBans Configuration
# Advanced punishment system with LiteBans compatibility
# Version: 1.0.0

# Database Configuration
database:
  # Database type: mysql, mariadb, postgresql, sqlite
  type: sqlite
  
  # Database connection details (not needed for SQLite)
  host: localhost
  port: 3306
  database: controlbans
  username: root
  password: password
  
  # SQLite file location (relative to plugin data folder)
  sqlite-file: punishments.db
  
  # Connection pool settings
  pool:
    maximum-pool-size: 10
    minimum-idle: 5
    connection-timeout: 30000
    idle-timeout: 300000
    max-lifetime: 1800000

# Alt Account Punishment
alts-punish:
  enabled: false
  # ... and so on

# Punishment Settings
punishments:
  broadcast:
    enabled: true
    console: true
    players: true
    
    # When true, all punishments will be silent (staff-only) unless the -s flag is used to make them public.
    # When false (default), punishments are public unless the -s flag is used to make them silent.
    silent-by-default: false
    
    # Format for broadcasts
    format:
      ban: "&c%player% &7was banned by &c%staff%&7: &f%reason%"
      # ...
```
</details>

---

## ❤️ Support & Contribution

This plugin is open-source and maintained for free. If you find it useful, please consider supporting its development.

*   **Support the Project:** [**Ko-fi.com/devtawny**](https://ko-fi.com/devtawny)
*   **Report a Bug or Suggest a Feature:** [**Create an Issue**](https://github.com/TawnyE/ControlBans/issues)
*   **Get Help:** Join our [**Discord Server**](https://discord.gg/xRchyJFkBG)

Contributions are welcome! If you'd like to contribute, please fork the repository and submit a pull request.
