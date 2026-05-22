package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.IdUtil;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class AltService {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final CacheService cacheService;

    public AltService(ControlBansPlugin plugin, StorageInterface storage, CacheService cacheService) {
        this.plugin = plugin;
        this.storage = storage;
        this.cacheService = cacheService;
    }

    private boolean isInvalidOrLocalIp(String ip) {
        if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0") || ip.equals("127.0.0.1") || ip.equals("localhost")) {
            return true;
        }
        return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
    }

    public CompletableFuture<List<UUID>> findAltAccounts(UUID uuid) {
        return cacheService.getOrCache("alts_" + uuid, () -> storage.getIpsForUuid(uuid).thenCompose(playerIps -> {
            if (playerIps == null || playerIps.isEmpty()) {
                return CompletableFuture.completedFuture(new ArrayList<>());
            }

            Set<UUID> alts = ConcurrentHashMap.newKeySet();
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            int ipLimit = plugin.getConfigManager().getAltIpAccountLimit();

            for (String ip : playerIps) {
                if (isInvalidOrLocalIp(ip)) {
                    continue;
                }

                CompletableFuture<Void> ipFuture = storage.getUserCountOnIp(ip).thenCompose(count -> {
                    if (count > ipLimit) {
                        plugin.getLogger().log(Level.FINE, "Skipping shared IP " + ip + " (Count: " + count + ")");
                        return CompletableFuture.completedFuture(null);
                    }
                    return storage.getUuidsOnIp(ip).thenAccept(alts::addAll);
                });
                futures.add(ipFuture);
            }

            return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .thenApply(v -> {
                        alts.remove(uuid);
                        return new ArrayList<>(alts);
                    });
        }), 600L);
    }

    public CompletableFuture<Set<String>> findSharedIps(UUID uuid) {
        return storage.getIpsForUuid(uuid).thenApply(ips -> {
            if (ips == null) return new HashSet<>();

            ips.removeIf(this::isInvalidOrLocalIp);

            return ips;
        });
    }

    public CompletableFuture<Void> punishAlts(Punishment originalPunishment) {
        if (!plugin.getConfigManager().isAltPunishEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        return findAltAccounts(originalPunishment.getTargetUuid())
                .thenCompose(alts -> {
                    List<CompletableFuture<Void>> punishmentFutures = new ArrayList<>();
                    int maxPunishments = plugin.getConfigManager().getAltMaxPunishments();

                    int count = 0;
                    for (UUID altUuid : alts) {
                        if (count >= maxPunishments) break;
                        punishmentFutures.add(createAltPunishment(altUuid, originalPunishment));
                        count++;
                    }
                    return CompletableFuture.allOf(punishmentFutures.toArray(new CompletableFuture[0]));
                });
    }

    private CompletableFuture<Void> createAltPunishment(UUID altUuid, Punishment originalPunishment) {
        String altReason = "Alt account of " + originalPunishment.getTargetName();

        return storage.getLastKnownName(altUuid).thenCompose(altName -> {
            Punishment altPunishment = Punishment.builder()
                    .punishmentId(IdUtil.generatePunishmentId())
                    .type(PunishmentType.BAN)
                    .targetUuid(altUuid)
                    .targetName(altName)
                    .reason(altReason)
                    .staffUuid(originalPunishment.getStaffUuid())
                    .staffName("[ALT] " + originalPunishment.getStaffName())
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(originalPunishment.getExpiryTime())
                    .serverOrigin(originalPunishment.getServerOrigin())
                    .silent(true)
                    .ipBan(false)
                    .active(true)
                    .build();

            return storage.insertBan(altPunishment).thenRun(() -> {
                cacheService.invalidatePlayerPunishments(altUuid);

                plugin.getSchedulerAdapter().runTask(() -> {
                    org.bukkit.entity.Player altPlayer = org.bukkit.Bukkit.getPlayer(altUuid);
                    if (altPlayer != null && altPlayer.isOnline()) {
                        net.kyori.adventure.text.Component kickMessage = plugin.getNotificationService()
                                .formatKickScreen(altPunishment);
                        altPlayer.kick(kickMessage);
                    }
                });
            });
        });
    }
}