# ControlBans

**An advanced, modern punishment system for Minecraft networks.**

ControlBans is a next-generation moderation suite designed for Paper, BungeeCord, and Velocity servers. It goes beyond simple bans and mutes, offering a powerful toolkit for staff to manage players efficiently and effectively. With a focus on performance, cross-server synchronization, and creative enforcement methods, ControlBans provides a complete solution for maintaining a healthy and enjoyable community.

---

### ✨ Features

ControlBans is packed with features designed for the modern server owner:

*   **Comprehensive Punishments:** Full support for permanent/temporary bans, mutes, IP punishments, kicks, and warnings.
*   **Cross-Server Sync:** Punishments are enforced instantly across your entire BungeeCord or Velocity network. A player muted on one server is muted on all servers, immediately.
*   **Intuitive GUIs:**
    *   `/history`: A clean, paginated GUI to view a player's complete punishment history.
    *   `/alts`: An advanced GUI to view a player's alternate accounts based on shared IP addresses.
*   **The Void Jail:** A unique psychological punishment.
    *   `/voidjail`: Traps a player in an inescapable void dimension where they are frozen and unable to interact with the world or other players. A modern, frustrating alternative to a temp-ban for trolls.
*   **Skin Ban:** A subtle, non-public punishment.
    *   `/banskin`: Removes a player's custom skin, forcing them to appear as a default Steve or Alex to everyone on the server.
*   **Geyser & Floodgate Support:** Seamlessly punish Bedrock players on your cross-play server using their prefixed username.
*   **Discord Integration:** Automatically log all moderation actions to a Discord channel with richly formatted, highly configurable webhooks.
*   **Web Interface (Optional):** A lightweight, built-in web panel to view recent punishments from any browser.
*   **Data Migration:** Includes importers to easily migrate your existing data from **LiteBans** and **Essentials**.
*   **High Performance:** Built with modern, asynchronous code to ensure it never lags your server, even under heavy load.
*   **Folia Compatible:** Includes support for the new Folia server software and its regionalized task scheduling.

---

### 🚀 Installation

1.  Download the latest JAR files from the [**Releases**](https://github.com/TawnyE/ControlBans/releases) page.
2.  **For your main server (Paper/Spigot/Folia):**
    *   Place `ControlBans-VERSION.jar` into your `/plugins/` folder.
    *   Restart the server. The plugin will generate its configuration files.
    *   Configure `config.yml` to your liking.
3.  **For your proxy (BungeeCord or Velocity):**
    *   Place `ControlBans-Bungee.jar` or `ControlBans-Velocity.jar` into the proxy's `/plugins/` folder.
    *   Restart the proxy. No configuration is needed for the proxy bridges.

---

### 📋 Commands & Permissions

Below is a list of the primary commands. All punishment commands support a `-s` flag to make the punishment "silent" (not broadcasted globally).

| Command                             | Description                                  | Permission                  |
| ----------------------------------- | -------------------------------------------- | --------------------------- |
| `/ban <player> [reason]`            | Permanently bans a player.                   | `controlbans.ban`           |
| `/tempban <player> <time> [reason]` | Temporarily bans a player (e.g., `1d2h`).      | `controlbans.tempban`       |
| `/unban <player>`                   | Unbans a player.                             | `controlbans.unban`         |
| `/mute <player> [reason]`           | Permanently mutes a player.                  | `controlbans.mute`          |
| `/tempmute <player> <time> [reason]`| Temporarily mutes a player.                  | `controlbans.tempmute`      |
| `/unmute <player>`                  | Unmutes a player.                            | `controlbans.unmute`        |
| `/kick <player> [reason]`           | Kicks a player from the server.              | `controlbans.kick`          |
| `/warn <player> [reason]`           | Warns a player.                              | `controlbans.warn`          |
| `/check <player/id>`                | Checks a player's active punishments.        | `controlbans.check`         |
| `/history <player>`                 | Opens a GUI of a player's punishment history.| `controlbans.history`       |
| `/alts <player>`                    | Opens a GUI of a player's alternate accounts.| `controlbans.alts`          |
| `/voidjail <player>`                | Sends a player to the void jail.             | `controlbans.voidjail`      |
| `/unvoidjail <player>`              | Releases a player from the void jail.        | `controlbans.voidjail`      |
| `/banskin <player>`                 | Removes a player's custom skin.              | `controlbans.banskin`       |
| `/unbanskin <player>`               | Restores a player's custom skin.             | `controlbans.banskin`       |
| `/controlbans <reload/import>`      | Admin commands for the plugin.               | `controlbans.admin`         |

A full list of permissions can be found in the `plugin.yml` file. The permission `controlbans.*` grants access to all features.

---

### 🔧 Building from Source

If you want to contribute or build the plugin yourself, follow these steps:

1.  **Prerequisites:**
    *   Java Development Kit (JDK) 21 or newer
    *   Apache Maven

2.  **Clone the repository:**
    ```bash
    git clone https://github.com/TawnyE/ControlBans.git
    cd ControlBans
    ```

3.  **Build with Maven:**
    ```bash
    mvn clean install
    ```

4.  The compiled JAR files will be located in the `target` directory of each module (`ControlBans/target/`, `ControlBans-Bungee/target/`, etc.).

---

### 📄 License

This project is licensed under the **ControlFamily License v1.1**. In short, you are free to use, modify, and distribute this project for personal or commercial use, provided that you give clear and visible credit to the original author, **Tawny (ret.tawny)**.

Please read the full [LICENSE](LICENSE) file for details.
