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

public class VoidJailListener implements Listener {

    private final ControlBansPlugin plugin; // <-- FIX: Add this field
    private final VoidJailService voidJailService;
    private final List<String> allowedCommands;

    public VoidJailListener(ControlBansPlugin plugin) {
        this.plugin = plugin; // <-- FIX: Assign the plugin instance
        this.voidJailService = plugin.getVoidJailService();
        this.allowedCommands = plugin.getConfigManager().getJailAllowedCommands()
                .stream()
                .map(cmd -> cmd.toLowerCase(Locale.ROOT))
                .toList();
    }

    // Ensure jailed players who log in are sent back to jail
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        if (voidJailService.isJailed(player.getUniqueId())) {
            Location jailLocation = plugin.getVoidJailService().getJailLocation();
            if (jailLocation != null) {
                // Use the scheduler to teleport them on the next tick to ensure everything is loaded
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> player.teleport(jailLocation));
            }
        }
    }

    // Handle players who are released while offline
    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerQuit(PlayerQuitEvent event) {
        // This doesn't require a fix, but it's good practice.
        // If a player is released while offline, their return location should be cleared
        // when they next join, which is handled in the onPlayerJoin logic.
    }


    // Teleport them back if they try to move or teleport
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        // Allow looking around, but not moving
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (voidJailService.isJailed(event.getPlayer().getUniqueId())) {
            Location returnLoc = voidJailService.getReturnLocation(event.getPlayer().getUniqueId());
            // Allow the specific teleport that releases them from jail
            if (returnLoc != null && event.getTo().equals(returnLoc)) {
                return;
            }
            event.setCancelled(true);
        }
    }

    // Prevent interactions and damage
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
                // Prevent all damage, including void damage
                event.setCancelled(true);
            }
        }
    }

    // Block commands
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommandPreprocess(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();
        if (!voidJailService.isJailed(player.getUniqueId())) {
            return;
        }

        String command = event.getMessage().substring(1).split(" ")[0].toLowerCase(Locale.ROOT);
        if (!allowedCommands.contains(command)) {
            event.setCancelled(true);
            // Now this line will work correctly
            player.sendMessage(plugin.getLocaleManager().getMessage("voidjail.command-blocked"));
        }
    }
}