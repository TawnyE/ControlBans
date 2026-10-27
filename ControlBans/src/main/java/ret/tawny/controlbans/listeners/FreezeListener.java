package ret.tawny.controlbans.listeners;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.FreezeManager;
import ret.tawny.controlbans.util.ChatUtil;

import java.util.UUID;

public class FreezeListener implements Listener {

    private final ControlBansPlugin plugin;
    private final FreezeManager freezeManager;

    public FreezeListener(ControlBansPlugin plugin, FreezeManager freezeManager) {
        this.plugin = plugin;
        this.freezeManager = freezeManager;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!freezeManager.isFrozen(event.getPlayer().getUniqueId()))
            return;

        if (event.getTo() == null) return;
        if (event.getFrom().getX() != event.getTo().getX() ||
                event.getFrom().getY() != event.getTo().getY() ||
                event.getFrom().getZ() != event.getTo().getZ()) {

            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onTeleport(org.bukkit.event.player.PlayerTeleportEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getLocaleManager().getMessage("freeze.cannot-interact"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onCommand(org.bukkit.event.player.PlayerCommandPreprocessEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            String msg = event.getMessage().toLowerCase();
            // Allowlist: none — frozen players may not run commands.
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getLocaleManager().getMessage("freeze.cannot-interact"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleEnter(org.bukkit.event.vehicle.VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onVehicleExit(org.bukkit.event.vehicle.VehicleExitEvent event) {
        if (event.getExited() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamageTaken(org.bukkit.event.entity.EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onPickup(org.bukkit.event.entity.EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.getLocaleManager().getMessage("freeze.cannot-interact"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (freezeManager.isFrozen(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDamage(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player && freezeManager.isFrozen(player.getUniqueId())) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLocaleManager().getMessage("freeze.cannot-attack"));
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Capture identity up-front; never touch the Player object again after quit.
        final UUID playerUuid = event.getPlayer().getUniqueId();
        final String playerName = event.getPlayer().getName();
        if (freezeManager.isFrozen(playerUuid)) {
            plugin.getLogger().warning("Frozen player " + playerName + " logged out! Auto-banning...");

            String reason = plugin.getLocaleManager().getRawMessage("freeze.logout-ban-reason");
            plugin.getPunishmentService().banPlayer(
                    playerName,
                    reason,
                    null,
                    "Console (Freeze Auto-Ban)",
                    true,
                    false
            ).thenRun(() -> {
                freezeManager.setFrozen(playerUuid, false);
            }).exceptionally(ex -> {
                plugin.getLogger().warning("Failed to auto-ban frozen player " + playerName + " (" + playerUuid + "): " + ex.getMessage());
                freezeManager.setFrozen(playerUuid, false);
                return null;
            });
        }
    }
}
