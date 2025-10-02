package ret.tawny.controlbans.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    public PlayerJoinListener(ControlBansPlugin plugin, PunishmentService punishmentService) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerLogin(PlayerLoginEvent event) {
        final UUID uuid = event.getPlayer().getUniqueId();
        final String ip = event.getAddress().getHostAddress();

        // Check for active bans asynchronously
        punishmentService.getActiveBan(uuid).thenAcceptAsync(banOptional -> {
            if (banOptional.isPresent() && !banOptional.get().isExpired()) {
                Punishment ban = banOptional.get();
                String disconnectMessage = punishmentService.getKickMessageFor(ban);
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, disconnectMessage);
                return;
            }

            // If no UUID ban, check for an active IP ban
            punishmentService.getActiveIpBan(ip).thenAcceptAsync(ipBanOptional -> {
                if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
                    Punishment ipBan = ipBanOptional.get();
                    String disconnectMessage = punishmentService.getKickMessageFor(ipBan);
                    event.disallow(PlayerLoginEvent.Result.KICK_BANNED, disconnectMessage);
                }
            });
        });

        // MCBlacklist Integration Check
        plugin.getIntegrationService().checkMcBlacklist(uuid).thenAccept(isBlacklisted -> {
            if (isBlacklisted) {
                String reason = plugin.getConfigManager().getMCBlacklistReason();
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, "§cYou are on a global blacklist.\n\n§7Reason: §f" + reason);
            }
        });
    }
}