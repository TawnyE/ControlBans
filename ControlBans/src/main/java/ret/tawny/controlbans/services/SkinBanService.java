package ret.tawny.controlbans.services;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;
import org.bukkit.Bukkit;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * "Skin ban" enforcement: while a player is skin-banned their skin textures property is
 * stripped from their in-memory GameProfile every time it is (re)sent to clients
 * (join, respawn, world change), so everyone — and the player themselves — sees the
 * default Minecraft skin.
 * <p>
 * Nothing is ever permanently written to the player's profile: on re-login the server
 * builds a fresh profile from Mojang's session servers, so {@code /unbanskin} simply
 * stops the stripping (and restores the real skin immediately for online players by
 * re-fetching it asynchronously).
 * <p>
 * Implemented purely with Paper API ({@link Player#setPlayerProfile}) — no PacketEvents,
 * no NMS.
 */
public class SkinBanService {

    private static final String FILE_NAME = "skinbans.yml";
    private static final String LIST_KEY = "banned";

    private final ControlBansPlugin plugin;
    private final SchedulerAdapter scheduler;
    private final Set<UUID> skinBanned = ConcurrentHashMap.newKeySet();
    private final File dataFile;

    public SkinBanService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.scheduler = plugin.getSchedulerAdapter();
        this.dataFile = new File(plugin.getDataFolder(), FILE_NAME);
        load();
    }

    public boolean isSkinBanned(UUID uuid) {
        return skinBanned.contains(uuid);
    }

    public void banSkin(UUID uuid) {
        if (skinBanned.add(uuid)) {
            saveAsync();
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            applyDefaultSkin(player);
        }
    }

    public void unbanSkin(UUID uuid) {
        if (skinBanned.remove(uuid)) {
            saveAsync();
        }
        Player player = Bukkit.getPlayer(uuid);
        if (player != null && player.isOnline()) {
            restoreSkin(player);
        }
    }

    /**
     * Removes the "textures" property from the player's live profile and forces a
     * client-side respawn for all viewers so the default skin renders immediately.
     * Must be safe to call repeatedly (join/respawn reapplication).
     */
    public void applyDefaultSkin(Player player) {
        scheduler.runTaskForPlayer(player, () -> {
            if (!player.isOnline()) return;
            try {
                PlayerProfile profile = player.getPlayerProfile();
                boolean hasTextures = false;
                for (ProfileProperty property : profile.getProperties()) {
                    if ("textures".equals(property.getName())) {
                        hasTextures = true;
                        break;
                    }
                }
                if (!hasTextures) {
                    // Already stripped — nothing to do this tick.
                    return;
                }
                profile.removeProperty("textures");
                player.setPlayerProfile(profile);
                refreshViewers(player);
            } catch (Throwable t) {
                plugin.getLogger().log(Level.WARNING, "Failed to strip skin for " + player.getName(), t);
            }
        });
    }

    /**
     * Re-fetches the player's real profile asynchronously and re-applies it, then forces
     * a viewer refresh. Used by /unbanskin for immediate visual restore; a relog also
     * restores the skin because the stripped profile is never persisted anywhere.
     */
    public void restoreSkin(Player player) {
        scheduler.runTaskAsynchronously(() -> {
            PlayerProfile fresh = Bukkit.createProfile(player.getUniqueId(), player.getName());
            boolean completed;
            try {
                completed = fresh.complete();
            } catch (Throwable t) {
                completed = false;
            }
            final PlayerProfile finalProfile = fresh;
            if (completed) {
                scheduler.runTaskForPlayer(player, () -> {
                    if (!player.isOnline()) return;
                    try {
                        player.setPlayerProfile(finalProfile);
                        refreshViewers(player);
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "Failed to restore skin for " + player.getName(), t);
                    }
                });
            } else {
                // Offline mode / Mojang lookup failure — refresh anyway; relog fully restores.
                scheduler.runTaskForPlayer(player, () -> {
                    if (player.isOnline()) refreshViewers(player);
                });
            }
        });
    }

    /**
     * Forces nearby viewers to see a fresh entity spawn for {@code player} so their
     * clients re-render with the (modified) profile. Runs on the player's region thread.
     */
    private void refreshViewers(Player player) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (viewer.equals(player) || !viewer.canSee(player)) continue;
            scheduler.runTaskForPlayer(viewer, () -> {
                if (!viewer.isOnline() || !player.isOnline()) return;
                try {
                    viewer.hidePlayer(plugin, player);
                    scheduler.runTaskForPlayer(viewer, () -> {
                        if (viewer.isOnline() && player.isOnline()) {
                            try {
                                viewer.showPlayer(plugin, player);
                            } catch (Throwable ignored) {}
                        }
                    });
                } catch (Throwable ignored) {}
            });
        }
    }

    private void load() {
        try {
            if (!dataFile.exists()) return;
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
            for (String raw : config.getStringList(LIST_KEY)) {
                try {
                    skinBanned.add(UUID.fromString(raw));
                } catch (IllegalArgumentException ignored) {}
            }
        } catch (Throwable t) {
            plugin.getLogger().log(Level.WARNING, "Failed to load skin bans", t);
        }
    }

    private void saveAsync() {
        List<String> snapshot = new ArrayList<>();
        for (UUID uuid : skinBanned) {
            snapshot.add(uuid.toString());
        }
        scheduler.runTaskAsynchronously(() -> {
            try {
                YamlConfiguration config = new YamlConfiguration();
                config.set(LIST_KEY, snapshot);
                config.save(dataFile);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to save skin bans", e);
            }
        });
    }
}
