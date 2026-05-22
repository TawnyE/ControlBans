package ret.tawny.controlbans.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.Optional;
import java.util.UUID;

public class PlayerJoinListener implements Listener {

    private static final long LOGIN_CHECK_TIMEOUT_MS = 3000L;

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    public PlayerJoinListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onPlayerPreLogin(AsyncPlayerPreLoginEvent event) {
        final UUID uuid = event.getUniqueId();
        final String ip = event.getAddress().getHostAddress();

        CompletableFuture<Optional<Punishment>> banFuture = punishmentService.getActiveBan(uuid)
                .completeOnTimeout(Optional.empty(), LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Failed to check active ban during pre-login for " + uuid + ": " + exception.getMessage());
                    return Optional.empty();
                });

        CompletableFuture<Optional<Punishment>> ipBanFuture = punishmentService.getActiveIpBan(ip)
                .completeOnTimeout(Optional.empty(), LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Failed to check active IP ban during pre-login for " + ip + ": " + exception.getMessage());
                    return Optional.empty();
                });

        CompletableFuture<Optional<Punishment>> muteFuture = punishmentService.getActiveMute(uuid)
                .completeOnTimeout(Optional.empty(), LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> Optional.empty());

        CompletableFuture<Optional<Punishment>> ipMuteFuture = punishmentService.getActiveIpMute(ip)
                .completeOnTimeout(Optional.empty(), LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> Optional.empty());

        CompletableFuture<Boolean> blacklistFuture = checkBlacklist(uuid);

        CompletableFuture.allOf(banFuture, ipBanFuture, blacklistFuture, muteFuture, ipMuteFuture)
                .completeOnTimeout(null, LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .join();

        if (plugin.getPlayerChatListener() != null) {
            plugin.getPlayerChatListener().preloadMuteState(uuid, ip);
        }

        Optional<Punishment> banOptional = banFuture.getNow(Optional.empty());
        if (banOptional.isPresent() && !banOptional.get().isExpired()) {
            Punishment ban = banOptional.get();
            Component disconnectComponent = plugin.getNotificationService().formatKickScreen(ban);
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(disconnectComponent);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        Optional<Punishment> ipBanOptional = ipBanFuture.getNow(Optional.empty());
        if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
            Punishment ipBan = ipBanOptional.get();
            Component disconnectComponent = plugin.getNotificationService().formatKickScreen(ipBan);
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(disconnectComponent);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }

        boolean isBlacklisted = blacklistFuture.getNow(false);
        if (isBlacklisted) {
            String reason = plugin.getConfigManager().getMCBlacklistReason();
            Component message = plugin.getLocaleManager().getMessage("errors.blacklisted", Placeholder.unparsed("reason", reason));
            String disconnectMessage = LegacyComponentSerializer.legacySection().serialize(message);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectMessage);
            return;
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        punishmentService.recordPlayerLogin(event.getPlayer());
        plugin.getProxyService().flushQueuedMessages(event.getPlayer());
        checkAltJoinNotification(event.getPlayer());

        if (plugin.getUpdateChecker() != null
                && plugin.getUpdateChecker().isUpdateAvailable()
                && event.getPlayer().hasPermission("controlbans.admin")) {

            String latest = plugin.getUpdateChecker().getLatestVersion();
            String current = plugin.getDescription().getVersion();

            plugin.getSchedulerAdapter().runTaskLater(() -> {
                if (event.getPlayer().isOnline()) {
                    event.getPlayer().sendMessage(
                        plugin.getLocaleManager().getMessage("update.available",
                            Placeholder.unparsed("latest", latest != null ? latest : "unknown"),
                            Placeholder.unparsed("current", current)
                        )
                    );
                }
            }, 2L * 20L);
        }
    }

    private void checkAltJoinNotification(Player player) {
        plugin.getAltService().findAltAccounts(player.getUniqueId()).thenAccept(alts -> {
            if (alts.isEmpty()) return;

            plugin.getSchedulerAdapter().runTask(() -> {
                for (UUID altUuid : alts) {
                    punishmentService.getActiveBan(altUuid).thenCompose(banOpt -> {
                        if (banOpt.isPresent() && banOpt.get().isActive()) {
                            return plugin.getStorage().getLastKnownName(altUuid).thenAccept(bannedName -> {
                                if (bannedName == null) bannedName = "Unknown";

                                net.kyori.adventure.text.Component message = plugin.getLocaleManager().getMessage("alts.join-alert",
                                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", player.getName()),
                                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("banned_player", bannedName),
                                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("confidence", "High"));

                                for (var online : plugin.getServer().getOnlinePlayers()) {
                                    if (online.hasPermission("controlbans.alerts.receive")) {
                                        online.sendMessage(message);
                                    }
                                }
                            });
                        }
                        return CompletableFuture.completedFuture(null);
                    });
                }
            });
        });
    }

    private CompletableFuture<Boolean> checkBlacklist(UUID uuid) {
        ret.tawny.controlbans.services.IntegrationService integrationService = plugin.getIntegrationService();
        if (integrationService == null) {
            return CompletableFuture.completedFuture(false);
        }

        return integrationService.checkMcBlacklist(uuid)
                .completeOnTimeout(false, LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(exception -> {
                    plugin.getLogger().warning("Failed MCBlacklist pre-login check for " + uuid + ": " + exception.getMessage());
                    return false;
                });
    }
}

