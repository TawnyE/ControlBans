package ret.tawny.controlbans.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerChatListener implements Listener {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    private record MuteState(boolean muted, boolean shadow, long expiry) {
        private static final MuteState NOT_MUTED = new MuteState(false, false, -1);

        private static MuteState fromPunishment(Punishment punishment) {
            if (punishment == null || punishment.isExpired()) {
                return NOT_MUTED;
            }
            String reason = punishment.getReason();
            boolean shadow = reason != null && reason.startsWith("[SHADOW]");
            return new MuteState(true, shadow, punishment.getExpiryTime());
        }
    }

    private final Map<UUID, MuteState> muteStates = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastNotification = new ConcurrentHashMap<>();

    public PlayerChatListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerChat(AsyncChatEvent event) {
        handleChat(event.getPlayer(), event);
    }

    private void handleChat(Player player, AsyncChatEvent modernEvent) {
        UUID uuid = player.getUniqueId();

        if (!plugin.getChatManager().canChat(player)) {
            cancelEvent(modernEvent);
            return;
        }

        MuteState cachedState = muteStates.get(uuid);
        if (cachedState != null) {
            if (cachedState.muted() && cachedState.expiry() > 0 && System.currentTimeMillis() > cachedState.expiry()) {
                muteStates.remove(uuid);
            } else {
                enforceMuteState(player, cachedState, modernEvent);
                return;
            }
        }

        cancelEvent(modernEvent);
        String ip = getPlayerIp(player);
        preloadMuteStateBlocking(uuid, ip).thenAccept(state -> {
            muteStates.put(uuid, state);
            if (!state.muted()) {
                plugin.getSchedulerAdapter().runTask(() -> {
                    if (player.isOnline()) {
                        player.sendMessage(player.getName() + ": " + getOriginalMessage(modernEvent));
                    }
                });
            } else {
                sendMuteMessage(player);
            }
        });
    }

    private String getOriginalMessage(AsyncChatEvent event) {
        return net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(event.originalMessage());
    }

    private CompletableFuture<MuteState> preloadMuteStateBlocking(UUID uuid, String ip) {
        return punishmentService.getActiveMute(uuid).thenCompose(opt -> {
            if (opt.isPresent() && !opt.get().isExpired()) {
                return java.util.concurrent.CompletableFuture.completedFuture(MuteState.fromPunishment(opt.get()));
            }
            if (ip == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(MuteState.NOT_MUTED);
            }
            return punishmentService.getActiveIpMute(ip)
                    .thenApply(ipOpt -> ipOpt.filter(punishment -> !punishment.isExpired())
                            .map(MuteState::fromPunishment)
                            .orElse(MuteState.NOT_MUTED));
        });
    }

    private void enforceMuteState(Player player, MuteState state, AsyncChatEvent modernEvent) {
        if (!state.muted()) {
            return;
        }

        if (state.shadow()) {
            if (modernEvent != null) {
                modernEvent.viewers().removeIf(viewer ->
                        viewer instanceof Player && !viewer.equals(player) && !((Player) viewer).hasPermission("controlbans.shadowmute.see")
                );
            }
            return;
        }

        cancelEvent(modernEvent);
        sendMuteMessage(player);
    }

    public void preloadMuteState(UUID uuid, String ip) {
        punishmentService.getActiveMute(uuid).thenCompose(opt -> {
            if (opt.isPresent() && !opt.get().isExpired()) {
                return java.util.concurrent.CompletableFuture.completedFuture(MuteState.fromPunishment(opt.get()));
            }
            if (ip == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(MuteState.NOT_MUTED);
            }
            return punishmentService.getActiveIpMute(ip)
                    .thenApply(ipOpt -> ipOpt.filter(punishment -> !punishment.isExpired())
                            .map(MuteState::fromPunishment)
                            .orElse(MuteState.NOT_MUTED));
        }).thenAccept(state -> muteStates.put(uuid, state));
    }

    private void preloadMuteState(Player player) {
        preloadMuteState(player.getUniqueId(), getPlayerIp(player));
    }

    private void sendMuteMessage(Player player) {
        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (now - lastNotification.getOrDefault(uuid, 0L) < 2000)
            return;
        lastNotification.put(uuid, now);

        punishmentService.getActiveMute(uuid).thenCompose(opt -> {
            if (opt.isPresent()) {
                return java.util.concurrent.CompletableFuture.completedFuture(opt);
            }

            String ip = getPlayerIp(player);
            if (ip == null) {
                return java.util.concurrent.CompletableFuture.completedFuture(opt);
            }

            return punishmentService.getActiveIpMute(ip);
        }).thenAccept(opt -> {
            if (opt.isEmpty() || opt.get().isExpired()) return;

            List<Component> messageLines = plugin.getNotificationService().formatMuteScreen(opt.get());
            plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                for (Component line : messageLines) {
                    player.sendMessage(line);
                }
            });
        });
    }

    private void cancelEvent(AsyncChatEvent modernEvent) {
        if (modernEvent != null) {
            modernEvent.setCancelled(true);
            modernEvent.viewers().clear();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        preloadMuteState(event.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        UUID uuid = event.getPlayer().getUniqueId();
        muteStates.remove(uuid);
        lastNotification.remove(uuid);
        if (plugin.getReportService() != null) {
            plugin.getReportService().cleanupPlayer(uuid);
        }
    }

    public void cacheMuteState(UUID uuid, Punishment punishment) {
        muteStates.put(uuid, MuteState.fromPunishment(punishment));
    }

    public void invalidateMuteCache(UUID uuid) {
        muteStates.remove(uuid);
    }

    private String getPlayerIp(Player player) {
        if (player.getAddress() == null || player.getAddress().getAddress() == null) {
            return null;
        }
        return player.getAddress().getAddress().getHostAddress();
    }
}
