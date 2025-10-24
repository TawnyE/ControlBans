package ret.tawny.controlbans.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.services.ProxyService;

import java.util.Optional;
import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final ProxyService proxyService;

    public PlayerJoinListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
        this.proxyService = plugin.getProxyService();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        final UUID uuid = event.getUniqueId();
        final String ip = event.getAddress().getHostAddress();

        Optional<Punishment> banOptional = punishmentService.getActiveBan(uuid).join();
        if (banOptional.isPresent() && !banOptional.get().isExpired()) {
            Punishment ban = banOptional.get();
            Component disconnectComponent = punishmentService.getKickMessageFor(ban);
            // The API expects a String here, so we serialize the Component.
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(disconnectComponent);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        Optional<Punishment> ipBanOptional = punishmentService.getActiveIpBan(ip).join();
        if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
            Punishment ipBan = ipBanOptional.get();
            Component disconnectComponent = punishmentService.getKickMessageFor(ipBan);
            // The API expects a String here, so we serialize the Component.
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(disconnectComponent);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        boolean isBlacklisted = plugin.getIntegrationService().checkMcBlacklist(uuid).join();
        if (isBlacklisted) {
            String reason = plugin.getConfigManager().getMCBlacklistReason();
            Component message = plugin.getLocaleManager().getMessage("errors.blacklisted", Placeholder.unparsed("reason", reason));
            // The API expects a String here, so we serialize the Component.
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(message);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        punishmentService.recordPlayerLogin(event.getPlayer());
        proxyService.flushQueuedMessagesIfPossible(event.getPlayer());
    }
}
