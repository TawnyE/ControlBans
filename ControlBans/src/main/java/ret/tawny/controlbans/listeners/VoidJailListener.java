package ret.tawny.controlbans.listeners;

import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.player.*;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.VoidJailService;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

public class VoidJailListener implements Listener {

    private final ControlBansPlugin plugin;
    private final VoidJailService voidJailService;
    private final List<String> allowedCommands;

    public VoidJailListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.voidJailService = plugin.getVoidJailService();
        this.allowedCommands = plugin.getConfigManager().getJailAllowedCommands()
                .stream()
                .map(cmd -> cmd.toLowerCase(Locale.ROOT))
                .toList();
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        if (voidJailService.hasPendingUnjail(uuid)) {
            Location returnLoc = voidJailService.getReturnLocation(uuid);
            voidJailService.clearPendingUnjail(uuid);
            if (returnLoc != null) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                    player.teleport(returnLoc);
                    player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.unjailed-message"));
                });
            }
            return;
        }

        if (voidJailService.isJailed(uuid)) {
            Location jailLocation = plugin.getVoidJailService().getJailLocation();
            if (jailLocation != null) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> player.teleport(jailLocation));
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            Location returnLoc = voidJailService.getReturnLocation(event.getPlayer().getUniqueId());
            if (returnLoc != null && event.getTo().equals(returnLoc)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onEntityDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (voidJailService.isJailed(player.getUniqueId())) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!voidJailService.isJailed(player.getUniqueId())) {
            return;
        }

        String message = event.getMessage();
        if (message.length() < 2) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.command-blocked"));
            return;
        }

        String command = message.substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!allowedCommands.contains(command)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.command-blocked"));
        }
    }
}