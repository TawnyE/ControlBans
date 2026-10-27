package ret.tawny.controlbans.listeners;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

        if (plugin.getPlayerChatListener() != null) {
            plugin.getPlayerChatListener().preloadMuteState(uuid, ip);
        }

        CompletableFuture<Optional<Punishment>> banFuture = punishmentService
                .getActiveBanForLogin(uuid, event.getName())
                .orTimeout(LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to check active ban for " + uuid + ": " + ex.getMessage());
                    schedulerSendDbErrorAlert(uuid.toString());
                    return null;
                });
        CompletableFuture<Optional<Punishment>> ipBanFuture = punishmentService.getActiveIpBan(ip)
                .orTimeout(LOGIN_CHECK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                .exceptionally(ex -> {
                    plugin.getLogger().warning("Failed to check active IP ban for " + ip + ": " + ex.getMessage());
                    schedulerSendDbErrorAlert(ip);
                    return null;
                });
        CompletableFuture.allOf(banFuture, ipBanFuture).join();

        // Fail-closed: DB failure (null) kicks with a retry screen instead of letting
        // a potentially banned player in.
        Optional<Punishment> banOptional = safeJoin(banFuture);
        Optional<Punishment> ipBanOptional = safeJoin(ipBanFuture);
        if (banOptional == null || ipBanOptional == null) {
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                    plugin.getLocaleManager().getMessage("alerts.db-error",
                            Placeholder.unparsed("identifier", uuid.toString())));
            return;
        }
        if (banOptional.isPresent() && !banOptional.get().isExpired()) {
            Punishment ban = banOptional.get();
            Component disconnectComponent = plugin.getNotificationService().formatKickScreen(ban);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectComponent);
            return;
        }

        if (ipBanOptional.isPresent() && !ipBanOptional.get().isExpired()) {
            Punishment ipBan = ipBanOptional.get();
            Component disconnectComponent = plugin.getNotificationService().formatKickScreen(ipBan);
            event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_BANNED, disconnectComponent);
            return;
        }

    }

    private static Optional<Punishment> safeJoin(CompletableFuture<Optional<Punishment>> f) {
        try {
            return f.join();
        } catch (Exception e) {
            return null;
        }
    }

    private void schedulerSendDbErrorAlert(String identifier) {
        plugin.getSchedulerAdapter().runTask(() -> sendDbErrorAlert(identifier));
    }

    private void sendDbErrorAlert(String identifier) {
        Component alert = plugin.getLocaleManager().getMessage("alerts.db-error",
                Placeholder.unparsed("identifier", identifier));
        plugin.getLogger().warning("[Login check failure] " + identifier);
        org.bukkit.Bukkit.getConsoleSender().sendMessage(alert);
        for (Player online : plugin.getServer().getOnlinePlayers()) {
            if (online.hasPermission("controlbans.alerts.receive")) {
                online.sendMessage(alert);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        // Register the proxy channel BEFORE flushing queued messages: the joining
        // player becomes an immediate, usable carrier for backend-to-proxy traffic.
        plugin.getProxyService().registerPlayerChannels(event.getPlayer());
        punishmentService.recordPlayerLogin(event.getPlayer());
        plugin.getAltService().invalidateAltCache(event.getPlayer().getUniqueId());
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
        if (!plugin.getConfigManager().isAltJoinAlertEnabled()) return;

        final String playerName = player.getName();
        plugin.getAltService().findAltAccounts(player.getUniqueId()).thenAccept(alts -> {
            if (alts.isEmpty()) return;

            java.util.List<CompletableFuture<String>> nameFutures = new java.util.ArrayList<>();
            for (UUID altUuid : alts) {
                CompletableFuture<String> nameFuture = punishmentService.getActiveBan(altUuid).thenCompose(banOpt -> {
                    if (banOpt.isPresent() && !banOpt.get().isExpired() && banOpt.get().isActive()) {
                        return plugin.getStorage().getLastKnownName(altUuid)
                                .thenApply(bannedName -> bannedName != null ? bannedName : "Unknown");
                    }
                    return CompletableFuture.completedFuture(null);
                });
                nameFutures.add(nameFuture);
            }

            CompletableFuture.allOf(nameFutures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                java.util.List<String> bannedNames = new java.util.ArrayList<>();
                for (CompletableFuture<String> future : nameFutures) {
                    String name = future.getNow(null);
                    if (name != null) bannedNames.add(name);
                }
                if (bannedNames.isEmpty()) return;

                int maxNames = plugin.getConfigManager().getAltJoinAlertMaxNames();
                String joined;
                if (bannedNames.size() > maxNames && maxNames > 0) {
                    joined = String.join(", ", bannedNames.subList(0, maxNames))
                            + ", +" + (bannedNames.size() - maxNames) + " more";
                } else {
                    joined = String.join(", ", bannedNames);
                }

                final String bannedPlayers = joined;
                final int count = bannedNames.size();
                plugin.getSchedulerAdapter().runTask(() -> {
                    net.kyori.adventure.text.Component message = plugin.getLocaleManager().getMessage("alts.join-alert",
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", playerName),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("banned_players", bannedPlayers),
                            net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("count", String.valueOf(count)));

                    for (var online : plugin.getServer().getOnlinePlayers()) {
                        if (online.hasPermission("controlbans.alerts.receive")) {
                            online.sendMessage(message);
                        }
                    }
                });
            });
        });
    }
}

