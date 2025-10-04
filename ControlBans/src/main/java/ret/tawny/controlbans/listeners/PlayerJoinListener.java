package ret.tawny.controlbans.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

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
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        final UUID uuid = event.getUniqueId();
        final String ip = event.getAddress().getHostAddress();

        // .join() is safe here because this event is async
        Optional<Punishment> banOptional = punishmentService.getActiveBan(uuid).join();
        if (banOptional.isPresent() && !banOptional.get().isExpired()) {
            Punishment ban = banOptional.get();
            String disconnectMessage = punishmentService.getKickMessageFor(ban);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        Optional<Punishment> ipBanOptional = punishmentService.getActiveIpBan(ip).join();
        if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
            Punishment ipBan = ipBanOptional.get();
            String disconnectMessage = punishmentService.getKickMessageFor(ipBan);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        // MCBlacklist Integration Check
        boolean isBlacklisted = plugin.getIntegrationService().checkMcBlacklist(uuid).join();
        if (isBlacklisted) {
            String reason = plugin.getConfigManager().getMCBlacklistReason();
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, "§cYou are on a global blacklist.\n\n§7Reason: §f" + reason);
        }
    }
}