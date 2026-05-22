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
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class SkinBanService {

    private final ControlBansPlugin plugin;
    private final File skinBansFile;
    private final Set<UUID> bannedPlayerUuids = ConcurrentHashMap.newKeySet();
    private final ReentrantLock saveLock = new ReentrantLock();

    public SkinBanService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.skinBansFile = new File(plugin.getDataFolder(), "skin-bans.yml");
        loadBannedSkins();
    }

    private void loadBannedSkins() {
        if (!skinBansFile.exists()) return;

        FileConfiguration config = YamlConfiguration.loadConfiguration(skinBansFile);
        List<String> uuidsAsString = config.getStringList("banned-uuids");

        for (String uuidStr : uuidsAsString) {
            try {
                bannedPlayerUuids.add(UUID.fromString(uuidStr));
            } catch (IllegalArgumentException ignored) {}
        }
        plugin.getLogger().info("Loaded " + bannedPlayerUuids.size() + " persistent skin bans.");
    }

    private void saveBannedSkins() {
        plugin.getSchedulerAdapter().runTaskAsynchronously(() -> {
            saveLock.lock();
            try {
                FileConfiguration config = new YamlConfiguration();
                List<String> uuidsAsString = new ArrayList<>();
                bannedPlayerUuids.forEach(uuid -> uuidsAsString.add(uuid.toString()));
                config.set("banned-uuids", uuidsAsString);
                config.save(skinBansFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.SEVERE, "Could not save skin-bans.yml!", e);
            } finally {
                saveLock.unlock();
            }
        });
    }

    public boolean banSkin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            return false;
        }

        bannedPlayerUuids.add(player.getUniqueId());
        applyBlankSkin(player);
        saveBannedSkins();

        return true;
    }

    public boolean unbanSkin(OfflinePlayer target) {
        if (!isSkinBanned(target.getUniqueId())) {
            return false;
        }

        bannedPlayerUuids.remove(target.getUniqueId());
        saveBannedSkins();

        if (target.isOnline()) {
            Player onlinePlayer = target.getPlayer();
            if (onlinePlayer != null) {
                plugin.getSchedulerAdapter().runTask(() ->
                        onlinePlayer.kick(plugin.getLocaleManager().getMessage("skinban.unban-kick"))
                );
            }
        }
        return true;
    }

    public boolean isSkinBanned(UUID uuid) {
        return bannedPlayerUuids.contains(uuid);
    }

    public void handlePlayerJoin(Player player) {
        if (isSkinBanned(player.getUniqueId())) {
            plugin.getSchedulerAdapter().runTaskLater(() -> {
                if (player.isOnline()) {
                    applyBlankSkin(player);
                    player.sendMessage(plugin.getLocaleManager().getMessage("skinban.player-notification"));
                }
            }, 5L);
        }
    }

    private void applyBlankSkin(Player player) {
        if (!Bukkit.isPrimaryThread()) {
            plugin.getSchedulerAdapter().runTask(() -> applyBlankSkin(player));
            return;
        }
        try {
            PlayerProfile newProfile = Bukkit.createProfile(player.getUniqueId(), player.getName());
            player.setPlayerProfile(newProfile);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to apply blank skin to " + player.getName());
        }
    }
}