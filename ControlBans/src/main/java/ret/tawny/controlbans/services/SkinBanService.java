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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SkinBanService {

    private final ControlBansPlugin plugin;
    private final File skinBansFile;
    private FileConfiguration skinBansConfig;

    private final Set<UUID> bannedPlayerUuids = ConcurrentHashMap.newKeySet();

    public SkinBanService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.skinBansFile = new File(plugin.getDataFolder(), "skin-bans.yml");
        loadBannedSkins();
    }

    private void loadBannedSkins() {
        if (!skinBansFile.exists()) {
            // Ensure the config is not null
            skinBansConfig = new YamlConfiguration();
            return;
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

    public boolean banSkin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            return false; // Already banned
        }

        applyBlankSkin(player);

        bannedPlayerUuids.add(player.getUniqueId());
        saveBannedSkins();

        return true;
    }

    public boolean unbanSkin(OfflinePlayer target) {
        if (!isSkinBanned(target.getUniqueId())) {
            return false; // Not banned
        }

        bannedPlayerUuids.remove(target.getUniqueId());
        saveBannedSkins();

        if (target.isOnline()) {
            Player onlinePlayer = target.getPlayer();
            if (onlinePlayer != null) {
                onlinePlayer.kick(plugin.getLocaleManager().getMessage("skinban.unban-kick"));
            }
        }
        return true;
    }

    public boolean isSkinBanned(UUID uuid) {
        return bannedPlayerUuids.contains(uuid);
    }

    public void handlePlayerJoin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            plugin.getSchedulerAdapter().runTask(() -> {
                applyBlankSkin(player);
                player.sendMessage(plugin.getLocaleManager().getMessage("skinban.player-notification"));
            });
        }
    }

    /**
     * The correct way to apply a default skin.
     * It clones the player's current profile and strips the skin properties.
     * @param player The player to apply the blank skin to.
     */
    private void applyBlankSkin(Player player) {
        PlayerProfile profile = player.getPlayerProfile();
        PlayerProfile newProfile = profile.clone(); // Clone to create a mutable copy
        newProfile.setProperties(Collections.emptySet()); // Strip all properties (including skin)
        player.setPlayerProfile(newProfile); // Apply the new, skinless profile
    }
}