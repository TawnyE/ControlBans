package ret.tawny.controlbans.services;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.OfflinePlayer;
import org.bukkit.World;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class VoidJailService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private Location jailLocation;

    // Stores the original location of a player before they were jailed
    private final Map<UUID, Location> returnLocations = new ConcurrentHashMap<>();
    // A set of all currently jailed players
    private final Set<UUID> jailedPlayers = ConcurrentHashMap.newKeySet();

    public VoidJailService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        loadJailLocation();
    }

    /**
     * Loads the jail location from the config.yml file.
     */
    public void loadJailLocation() {
        String worldName = config.getJailWorld();
        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().log(Level.SEVERE, "The world '" + worldName + "' specified for the void jail does not exist! The void jail will not function.");
            jailLocation = null;
            return;
        }

        jailLocation = new Location(
                world,
                config.getJailX(),
                config.getJailY(),
                config.getJailZ(),
                0,  // Yaw
                0   // Pitch
        );

        plugin.getLogger().info("Void jail location loaded successfully.");
    }

    /**
     * Sends a player to the void jail.
     * @param player The player to be jailed.
     */
    public void jailPlayer(Player player) {
        if (jailLocation == null) {
            player.sendMessage(plugin.getLocaleManager().getMessage("errors.voidjail-not-set"));
            return;
        }

        // Store their current location so we can teleport them back
        returnLocations.put(player.getUniqueId(), player.getLocation());
        jailedPlayers.add(player.getUniqueId());

        // Teleport them to the jail
        player.teleport(jailLocation);

        // Inform them
        player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.jailed-message"));
    }

    /**
     * Releases a player from the void jail.
     * @param target The player to be released.
     */
    public void unjailPlayer(OfflinePlayer target) {
        UUID uuid = target.getUniqueId();
        if (!jailedPlayers.contains(uuid)) {
            return; // Not jailed
        }

        Location returnLoc = returnLocations.get(uuid); // Get location before removing from jail set
        jailedPlayers.remove(uuid);


        Player onlinePlayer = target.getPlayer();
        if (onlinePlayer != null && onlinePlayer.isOnline() && returnLoc != null) {
            // If the player is online, teleport them back immediately.
            onlinePlayer.teleport(returnLoc);
            onlinePlayer.sendMessage(plugin.getLocaleManager().getMessage("voidjail.unjailed-message"));
            // Clear the location now that they've been teleported
            clearReturnLocation(uuid);
        }
        // If the player is offline, the PlayerJoinListener will handle teleporting them back.
    }

    /**
     * Checks if a player is currently in the void jail.
     * @param uuid The UUID of the player.
     * @return true if the player is jailed.
     */
    public boolean isJailed(UUID uuid) {
        return jailedPlayers.contains(uuid);
    }

    /**
     * Gets the location the player should be returned to upon release.
     * @param uuid The UUID of the player.
     * @return The saved return location, or null if not found.
     */
    public Location getReturnLocation(UUID uuid) {
        return returnLocations.get(uuid);
    }

    /**
     * Gets the location of the void jail itself.
     * @return The jail location.
     */
    public Location getJailLocation() {
        return jailLocation;
    }


    /**
     * Clears a player's return location from memory.
     * @param uuid The UUID of the player.
     */
    public void clearReturnLocation(UUID uuid) {
        returnLocations.remove(uuid);
    }
}