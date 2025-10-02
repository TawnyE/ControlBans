package ret.tawny.controlbans.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerLoginEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.Optional;
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
                event.disallow(PlayerLoginEvent.Result.KICK_BANNED, formatBanMessage(banOptional.get()));
                return;
            }

            // If no UUID ban, check for an active IP ban
            punishmentService.getActiveIpBan(ip).thenAcceptAsync(ipBanOptional -> {
                if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
                    event.disallow(PlayerLoginEvent.Result.KICK_BANNED, formatBanMessage(ipBanOptional.get()));
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

    private String formatBanMessage(Punishment punishment) {
        StringBuilder message = new StringBuilder();
        message.append("§cYou have been banned from this server.\n\n");
        message.append("§7Reason: §f").append(punishment.getReason()).append("\n");
        message.append("§7Banned by: §f").append(punishment.getStaffName()).append("\n");

        if (punishment.isPermanent()) {
            message.append("§7Duration: §fPermanent");
        } else {
            message.append("§7Expires: §f").append(TimeUtil.formatDuration(punishment.getRemainingTime() / 1000));
        }

        // You can add a configurable appeal link here
        message.append("\n\n§7Appeal at: §fyour-server.com/appeal");

        return message.toString();
    }
}