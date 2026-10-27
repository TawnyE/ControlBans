package ret.tawny.controlbans.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.SkinBanService;

/**
 * Re-applies the default-skin strip while a skin ban is active. A fresh profile (with the
 * real skin) is built by the server on every login and the client re-receives entity
 * spawn data on every respawn, so the punishment must re-strip at both points.
 */
public class SkinBanListener implements Listener {

    private final ControlBansPlugin plugin;
    private final SkinBanService skinBanService;

    public SkinBanListener(ControlBansPlugin plugin, SkinBanService skinBanService) {
        this.plugin = plugin;
        this.skinBanService = skinBanService;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (!skinBanService.isSkinBanned(player.getUniqueId())) return;
        // Delay a beat so login/profile processing has finished before we strip.
        plugin.getSchedulerAdapter().runTaskLater(
                () -> skinBanService.applyDefaultSkin(player), 20L);
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent event) {
        Player player = event.getPlayer();
        if (!skinBanService.isSkinBanned(player.getUniqueId())) return;
        plugin.getSchedulerAdapter().runTaskLater(
                () -> skinBanService.applyDefaultSkin(player), 1L);
    }
}
