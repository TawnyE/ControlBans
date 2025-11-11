package ret.tawny.controlbans.services;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SkinBanService {

    private final ControlBansPlugin plugin;
    private final File skinBansFile;
    private FileConfiguration skinBansConfig;

    // A simple set to track UUIDs of skin-banned players, persisted in a file.
    private final Set<UUID> bannedPlayerUuids = ConcurrentHashMap.newKeySet();

    public SkinBanService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.skinBansFile = new File(plugin.getDataFolder(), "skin-bans.yml");
        loadBannedSkins();
    }

    private void loadBannedSkins() {
        if (!skinBansFile.exists()) {
            return; // No file to load
        }
        skinBansConfig = YamlConfiguration.loadConfiguration(skinBansFile);
        List<String> uuidsAsString = skinBansConfig.getStringList("banned-uuids");
        uuidsAsString.stream()
                .map(UUID::fromString)
                .forEach(bannedPlayerUuids::add);
        plugin.getLogger().info("Loaded " + bannedPlayerUuids.size() + " persistent skin bans.");
    }

    private void saveBannedSkins() {
        List<String> uuidsAsString = bannedPlayerUuids.stream()
                .map(UUID::toString)
                .collect(Collectors.toList());
        skinBansConfig.set("banned-uuids", uuidsAsString);
        try {
            skinBansConfig.save(skinBansFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save skin-bans.yml!", e);
        }
    }

    /**
     * Applies a skin ban to an online player.
     * @param player The player whose skin to ban.
     */
    public boolean banSkin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            return false; // Already banned
        }

        // Create a new, blank player profile
        PlayerProfile newProfile = Bukkit.createProfile(player.getUniqueId(), player.getName());

        // Apply the blank profile to the player
        player.setPlayerProfile(newProfile);

        // Add to the list and save
        bannedPlayerUuids.add(player.getUniqueId());
        saveBannedSkins();

        return true;
    }

    /**
     * Removes a skin ban from a player.
     * @param target The player whose skin to restore.
     */
    public boolean unbanSkin(OfflinePlayer target) {
        if (!isSkinBanned(target.getUniqueId())) {
            return false; // Not banned
        }

        // Remove from the list and save
        bannedPlayerUuids.remove(target.getUniqueId());
        saveBannedSkins();

        // If the player is online, we need to restore their skin.
        // The easiest way is to have them relog. The join listener will handle restoring the skin.
        if (target.isOnline()) {
            Player onlinePlayer = target.getPlayer();
            if (onlinePlayer != null) {
                // To restore the skin, the server needs to re-fetch the profile.
                // Kicking is the most reliable way to force a full profile refresh for all clients.
                onlinePlayer.kick(plugin.getLocaleManager().getMessage("skinban.unban-kick"));
            }
        }
        return true;
    }

    /**
     * Checks if a player's skin is currently banned.
     * @param uuid The UUID of the player.
     * @return true if the skin is banned.
     */
    public boolean isSkinBanned(UUID uuid) {
        return bannedPlayerUuids.contains(uuid);
    }

    /**
     * Handles the logic for a player joining, applying or restoring skins as needed.
     * @param player The player who just joined.
     */
    public void handlePlayerJoin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            // Player is banned, apply a blank skin
            plugin.getSchedulerAdapter().runTask(() -> {
                PlayerProfile newProfile = Bukkit.createProfile(player.getUniqueId(), player.getName());
                player.setPlayerProfile(newProfile);
                player.sendMessage(plugin.getLocaleManager().getMessage("skinban.player-notification"));
            });
        }
    }
}