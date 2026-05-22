package ret.tawny.controlbans.services;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

public class VoidJailService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private Location jailLocation;
    private final File dataFile;
    private final ReentrantLock saveLock = new ReentrantLock();

    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    private final Set<UUID> jailedPlayers = ConcurrentHashMap.newKeySet();
    private final Set<UUID> pendingUnjailedOffline = ConcurrentHashMap.newKeySet();

    public VoidJailService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.dataFile = new File(plugin.getDataFolder(), "void-jail.yml");

        loadJailLocation();
        loadData();
    }

    public void loadJailLocation() {
        String worldName = config.getJailWorld();
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().log(Level.SEVERE, "Void jail world '" + worldName + "' not found!");
            jailLocation = null;
            return;
        }

        jailLocation = new Location(world, config.getJailX(), config.getJailY(), config.getJailZ(), 0, 0);
    }

    private void loadData() {
        if (!dataFile.exists()) return;

        FileConfiguration dataConfig = YamlConfiguration.loadConfiguration(dataFile);
        ConfigurationSection playersSection = dataConfig.getConfigurationSection("players");
        if (playersSection == null) return;

        for (String uuidStr : playersSection.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(uuidStr);
                ConfigurationSection locSection = playersSection.getConfigurationSection(uuidStr + ".return");
                if (locSection != null) {
                    World world = Bukkit.getWorld(locSection.getString("world", "world"));
                    if (world != null) {
                        returnLocations.put(uuid, new Location(world, locSection.getDouble("x"), locSection.getDouble("y"), locSection.getDouble("z")));
                    }
                }
                if (playersSection.getBoolean(uuidStr + ".pending_unjail", false)) {
                    pendingUnjailedOffline.add(uuid);
                } else {
                    jailedPlayers.add(uuid);
                }
            } catch (Exception ignored) {}
        }
    }

    private void saveData() {
        plugin.getSchedulerAdapter().runTaskAsynchronously(() -> {
            saveLock.lock();
            try {
                final Set<UUID> jailedSnapshot = new HashSet<>(jailedPlayers);
                final Set<UUID> pendingSnapshot = new HashSet<>(pendingUnjailedOffline);
                final Map<UUID, Location> locationsSnapshot = new HashMap<>(returnLocations);

                FileConfiguration newDataConfig = new YamlConfiguration();
                for (UUID uuid : jailedSnapshot) {
                    String path = "players." + uuid.toString();
                    newDataConfig.set(path + ".jailed", true);
                    Location loc = locationsSnapshot.get(uuid);
                    if (loc != null && loc.getWorld() != null) {
                        newDataConfig.set(path + ".return.world", loc.getWorld().getName());
                        newDataConfig.set(path + ".return.x", loc.getX());
                        newDataConfig.set(path + ".return.y", loc.getY());
                        newDataConfig.set(path + ".return.z", loc.getZ());
                    }
                }
                for (UUID uuid : pendingSnapshot) {
                    String path = "players." + uuid.toString();
                    newDataConfig.set(path + ".pending_unjail", true);
                    Location loc = locationsSnapshot.get(uuid);
                    if (loc != null && loc.getWorld() != null) {
                        newDataConfig.set(path + ".return.world", loc.getWorld().getName());
                        newDataConfig.set(path + ".return.x", loc.getX());
                        newDataConfig.set(path + ".return.y", loc.getY());
                        newDataConfig.set(path + ".return.z", loc.getZ());
                    }
                }
                try {
                    newDataConfig.save(dataFile);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save void-jail.yml", e);
                }
            } finally {
                saveLock.unlock();
            }
        });
    }

    public void jailPlayer(Player player) {
        if (jailLocation == null) {
            player.sendMessage(plugin.getLocaleManager().getMessage("errors.voidjail-not-set"));
            return;
        }

        returnLocations.put(player.getUniqueId(), player.getLocation());
        jailedPlayers.add(player.getUniqueId());
        saveData();

        player.teleport(jailLocation);
        player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.jailed-message"));
    }

    public void unjailPlayer(OfflinePlayer target) {
        UUID uuid = target.getUniqueId();
        if (!jailedPlayers.contains(uuid)) return;

        Location returnLoc = returnLocations.remove(uuid);
        jailedPlayers.remove(uuid);
        saveData();

        Player onlinePlayer = target.getPlayer();
        if (onlinePlayer != null && returnLoc != null) {
            onlinePlayer.teleport(returnLoc);
            onlinePlayer.sendMessage(plugin.getLocaleManager().getMessage("voidjail.unjailed-message"));
        } else if (returnLoc != null) {
            pendingUnjailedOffline.add(uuid);
            saveData();
        }
    }

    public boolean hasPendingUnjail(UUID uuid) { return pendingUnjailedOffline.contains(uuid); }

    public void clearPendingUnjail(UUID uuid) { pendingUnjailedOffline.remove(uuid); }

    public boolean isJailed(UUID uuid) { return jailedPlayers.contains(uuid); }
    public Location getReturnLocation(UUID uuid) { return returnLocations.get(uuid); }
    public Location getJailLocation() { return jailLocation; }
}