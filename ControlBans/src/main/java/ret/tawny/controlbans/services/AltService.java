package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.IdUtil;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.util.ArrayList;
import java.util.Collections;
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
private static final String ALT_BYPASS_PERMISSION_DEFAULT = "controlbans.bypass.alts";

    public AltService(ControlBansPlugin plugin, StorageInterface storage, CacheService cacheService) {
        this.plugin = plugin;
        this.storage = storage;
        this.cacheService = cacheService;
    }

    private boolean isStealthBypassed(UUID targetUuid) {
        if (!plugin.getConfigManager().isStealthModeEnabled() || !plugin.getConfigManager().isStealthBypassAltDetection()) {
            return false;
        }
        org.bukkit.entity.Player target = org.bukkit.Bukkit.getPlayer(targetUuid);
        return target != null && target.hasPermission("controlbans.stealth");
    }

private boolean isInvalidOrLocalIp(String ip) {
if (ip == null || ip.isEmpty() || ip.equals("0.0.0.0") || ip.equals("127.0.0.1") || ip.equals("localhost")) {
return true;
}
return ip.startsWith("192.168.") || ip.startsWith("10.") || ip.matches("^172\\.(1[6-9]|2[0-9]|3[0-1])\\..*");
}

    private CompletableFuture<Boolean> resolveBypassStatus(String ip) {
        if (!plugin.getConfigManager().isAltBypassEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        String bypassPerm = plugin.getConfigManager().getAltBypassPermission();
        if (bypassPerm == null || bypassPerm.isBlank()) bypassPerm = ALT_BYPASS_PERMISSION_DEFAULT;
        final String perm = bypassPerm;
        return storage.getUuidsOnIp(ip).thenCompose(uuids -> {
            if (uuids.isEmpty()) {
                return CompletableFuture.completedFuture(false);
            }
            // Single sync hop: check all UUIDs' permissions in one main-thread task
            // instead of one Bukkit round trip per alt.
            return plugin.getSchedulerAdapter().callSync(() -> {
                for (UUID u : uuids) {
                    org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(u);
                    // Use configured permission, not hard-coded. Note: offline players can't have perms checked — treat as not bypassed.
                    if (player != null && player.hasPermission(perm)) {
                        return true;
                    }
                }
                return false;
            });
        });
    }

    public CompletableFuture<List<UUID>> findAltAccounts(UUID uuid) {
        if (isStealthBypassed(uuid)) {
            return CompletableFuture.completedFuture(new java.util.ArrayList<>());
        }
        if (!plugin.getConfigManager().isAltBypassEnabled()) {
            return findAltAccountsUnfiltered(uuid);
        }
        CompletableFuture<Set<String>> filteredIpsFuture = storage.getIpsForUuid(uuid).thenCompose(playerIps -> {
            if (playerIps == null || playerIps.isEmpty()) {
                return CompletableFuture.completedFuture(Collections.emptySet());
            }
            List<CompletableFuture<String>> bypassChecks = new ArrayList<>();
            for (String ip : playerIps) {
                if (isInvalidOrLocalIp(ip)) continue;
                String ipCopy = ip;
                bypassChecks.add(resolveBypassStatus(ip).thenApply(bypassed -> bypassed ? null : ipCopy));
            }
            return CompletableFuture.allOf(bypassChecks.toArray(new CompletableFuture[0])).thenApply(v -> {
                Set<String> nonBypassedIps = new HashSet<>();
                for (CompletableFuture<String> check : bypassChecks) {
                    String ip = check.join();
                    if (ip != null) nonBypassedIps.add(ip);
                }
                return nonBypassedIps;
            });
        });
        return filteredIpsFuture.thenCompose(nonBypassedIps -> {
            if (nonBypassedIps.isEmpty()) {
                return CompletableFuture.completedFuture(new ArrayList<>());
            }
            return findAltAccountsForIps(uuid, nonBypassedIps);
        });
    }

    private CompletableFuture<List<UUID>> findAltAccountsForIps(UUID uuid, Set<String> ips) {
        Set<UUID> alts = ConcurrentHashMap.newKeySet();
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        int ipLimit = plugin.getConfigManager().getAltIpAccountLimit();

        for (String ip : ips) {
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
    }

private CompletableFuture<List<UUID>> findAltAccountsUnfiltered(UUID uuid) {
if (isStealthBypassed(uuid)) {
return CompletableFuture.completedFuture(new ArrayList<>());
}
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
return storage.getIpsForUuid(uuid).thenCompose(ipsRaw -> {
if (ipsRaw == null) return CompletableFuture.completedFuture(new HashSet<>());
Set<String> ips = new java.util.HashSet<>();
List<CompletableFuture<Void>> checks = new ArrayList<>();
for (String ip : ipsRaw) {
if (isInvalidOrLocalIp(ip)) continue;
checks.add(resolveBypassStatus(ip).thenAccept(bypassed -> {
if (!bypassed) ips.add(ip);
}));
}
return CompletableFuture.allOf(checks.toArray(new CompletableFuture[0])).thenApply(v -> ips);
});
}

    public void invalidateAltCache(UUID uuid) {
        cacheService.invalidate("alts_" + uuid);
    }

    public CompletableFuture<Void> punishAlts(Punishment originalPunishment) {
        if (!plugin.getConfigManager().isAltPunishEnabled()) {
            return CompletableFuture.completedFuture(null);
        }

        if (isStealthBypassed(originalPunishment.getTargetUuid())) {
            if (plugin.getConfigManager().isStealthSuppressAltAlerts()) {
                plugin.getLogger().info("[Alt] Skipping alt punishment for " + originalPunishment.getTargetName()
                        + " — target is in stealth mode.");
            }
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
            // Skip alts that already have an active (non-expired) ban.
            return storage.getActiveBan(altUuid).thenCompose(existing -> {
                if (existing.isPresent() && !existing.get().isExpired()) return CompletableFuture.completedFuture(null);
                String safeStaff = originalPunishment.getStaffName() != null ? originalPunishment.getStaffName() : "Console";
                if (safeStaff.length() > 26) safeStaff = safeStaff.substring(0, 26);
                Punishment altPunishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(PunishmentType.BAN)
                        .targetUuid(altUuid)
                        .targetName(altName)
                        .reason(altReason)
                        .staffUuid(originalPunishment.getStaffUuid())
                        .staffName("[ALT] " + safeStaff)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(originalPunishment.getExpiryTime())
                        .serverOrigin(originalPunishment.getServerOrigin())
                        .silent(true)
                        .ipBan(false)
                        .active(true)
                        .build();

                return storage.insertBan(altPunishment).thenRun(() -> {
                    cacheService.invalidatePlayerPunishments(altUuid);
                    try {
                        plugin.getProxyService().sendInvalidateCacheMessage(altUuid);
                    } catch (Exception ignored) {}

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
        });
    }
}