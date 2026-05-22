package ret.tawny.controlbans.services;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.IdUtil;
import ret.tawny.controlbans.util.IpUtil;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class PunishmentService {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final CacheService cacheService;
    private final SchedulerAdapter scheduler;
    private final ProxyService proxyService;
    private final PunishmentTemplateService templateService;
    private final PlayerResolver playerResolver;
    private final EscalationService escalationService;
    private final NotificationService notificationService;

    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");
    private static final Pattern UNSAFE_IP_PATTERN = Pattern
            .compile("^(127\\.|0\\.|10\\.|192\\.168\\.|172\\.(1[6-9]|2[0-9]|3[0-1])\\.)");

    private record PunishmentCheckResult(boolean canPunish, boolean forceSilent, boolean staffIsAdmin, String failureMessage) {
        static PunishmentCheckResult allow(boolean staffIsAdmin) {
            return new PunishmentCheckResult(true, false, staffIsAdmin, null);
        }
        static PunishmentCheckResult allowAndForceSilent() {
            return new PunishmentCheckResult(true, true, true, null);
        }
        static PunishmentCheckResult deny(String message) {
            return new PunishmentCheckResult(false, false, false, message);
        }
    }

    public PunishmentService(ControlBansPlugin plugin, StorageInterface storage, CacheService cacheService) {
        this.plugin = plugin;
        this.storage = storage;
        this.cacheService = cacheService;
        this.scheduler = plugin.getSchedulerAdapter();
        this.proxyService = plugin.getProxyService();
        this.templateService = new PunishmentTemplateService(plugin);
        this.playerResolver = new PlayerResolver(plugin, storage, cacheService);
        this.escalationService = new EscalationService(plugin, this);
        this.notificationService = new NotificationService(plugin);
    }

    public PlayerResolver getPlayerResolver() { return playerResolver; }
    public EscalationService getEscalationService() { return escalationService; }
    public NotificationService getNotificationService() { return notificationService; }

    private CompletableFuture<PunishmentCheckResult> prePunishmentCheck(UUID staffUuid, UUID targetUuid) {
        if (staffUuid != null && staffUuid.equals(targetUuid)) {
            return CompletableFuture.completedFuture(
                    PunishmentCheckResult.deny(plugin.getLocaleManager().getRawMessage("errors.cannot-punish-self")));
        }

        return scheduler.callSync(() -> {
            OfflinePlayer offlineTarget = Bukkit.getOfflinePlayer(targetUuid);
            if (offlineTarget.isOp()) {
                if (staffUuid == null) return PunishmentCheckResult.allowAndForceSilent();
                return PunishmentCheckResult.deny(plugin.getLocaleManager().getRawMessage("errors.cannot-punish-exempt"));
            }

            Player staffPlayer = staffUuid != null ? Bukkit.getPlayer(staffUuid) : null;
            boolean staffIsAdmin = staffUuid == null || (staffPlayer != null && staffPlayer.hasPermission("controlbans.admin"));
            Player targetPlayer = Bukkit.getPlayer(targetUuid);

            if (targetPlayer != null && targetPlayer.isOnline() && targetPlayer.hasPermission("controlbans.exempt")) {
                if (staffUuid == null) return PunishmentCheckResult.allowAndForceSilent();
                return PunishmentCheckResult.deny(plugin.getLocaleManager().getRawMessage("errors.cannot-punish-exempt"));
            }
            return PunishmentCheckResult.allow(staffIsAdmin);
        });
    }

    public CompletableFuture<Void> banPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null)
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));

            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish())
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));

                String finalReason = reason != null ? reason : plugin.getConfigManager().getDefaultBanReason();
                return templateService.determinePunishmentType(PunishmentType.BAN, finalReason, targetUuid).thenCompose(resolved -> {
                    PunishmentType resolvedType = resolved.type();
                    PunishmentType actualType = ipBan ? (resolvedType.isTemporary() ? PunishmentType.TEMPIPBAN : PunishmentType.IPBAN) : resolvedType;

                    long expiry = -1;
                    if (actualType.isTemporary()) {
                        long durationSeconds = resolved.durationSeconds() > 0 ? resolved.durationSeconds() : plugin.getConfigManager().getMaxTempBanDuration();
                        expiry = System.currentTimeMillis() + (durationSeconds * 1000);
                    }

                    Punishment p = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(actualType)
                            .targetUuid(targetUuid).targetName(targetName)
                            .reason(finalReason).staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).ipBan(ipBan).build();

                    return storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p));
                });
            });
        });
    }

    public CompletableFuture<Void> tempBanPlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));

                return escalationService.calculateEscalation(targetUuid, reason, duration).thenCompose(finalDuration -> {
                    long expiry = (finalDuration == -1) ? -1 : System.currentTimeMillis() + (finalDuration * 1000);
                    Punishment p = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId())
                            .type(ipBan ? PunishmentType.IPBAN : (finalDuration == -1 ? PunishmentType.BAN : PunishmentType.TEMPBAN))
                            .targetUuid(targetUuid).targetName(targetName)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultBanReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).ipBan(ipBan).build();
                    return storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p));
                });
            });
        });
    }

    public CompletableFuture<Void> mutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = playerResolver.getPlayerIp(targetUuid);
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.MUTE)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(-1)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                }).thenCompose(p -> storage.insertMute(p).thenRun(() -> {
                    if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().cacheMuteState(targetUuid, p);
                    onPunishmentSuccess(p);
                }));
            });
        });
    }

    public CompletableFuture<Void> tempMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = playerResolver.getPlayerIp(targetUuid);
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.TEMPMUTE)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(System.currentTimeMillis() + (duration * 1000))
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                }).thenCompose(p -> storage.insertMute(p).thenRun(() -> {
                    if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().cacheMuteState(targetUuid, p);
                    onPunishmentSuccess(p);
                }));
            });
        });
    }

    public CompletableFuture<Void> kickPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = playerResolver.getPlayerIp(targetUuid);
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.KICK)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultKickReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(System.currentTimeMillis())
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                }).thenCompose(p -> storage.insertKick(p).thenRun(() -> onPunishmentSuccess(p)));
            });
        });
    }

    public CompletableFuture<Void> warnPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = playerResolver.getPlayerIp(targetUuid);
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.WARN)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultWarnReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(-1)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                }).thenCompose(p -> storage.insertWarning(p).thenRun(() -> {
                    scheduler.runTask(() -> {
                        Player player = Bukkit.getPlayer(targetUuid);
                        if (player != null && player.isOnline()) {
                            notificationService.formatMuteScreen(p).forEach(player::sendMessage);
                        }
                    });
                    onPunishmentSuccess(p);
                }));
            });
        });
    }

    public CompletableFuture<Void> voiceMutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                Punishment p = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.VOICEMUTE)
                        .targetUuid(targetUuid).targetName(targetName)
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                        .staffUuid(staffUuid).staffName(staffName)
                        .createdTime(System.currentTimeMillis()).expiryTime(-1)
                        .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                return storage.insertVoiceMute(p).thenRun(() -> {
                    postVoiceMuteActions(targetUuid, p);
                });
            });
        });
    }

    public CompletableFuture<Void> tempVoiceMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                long expiry = System.currentTimeMillis() + (duration * 1000);
                Punishment p = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.TEMPVOICEMUTE)
                        .targetUuid(targetUuid).targetName(targetName)
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                        .staffUuid(staffUuid).staffName(staffName)
                        .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                        .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).build();
                return storage.insertVoiceMute(p).thenRun(() -> {
                    postVoiceMuteActions(targetUuid, p);
                });
            });
        });
    }

    private void postVoiceMuteActions(UUID targetUuid, Punishment p) {
        cacheService.invalidatePlayerPunishments(targetUuid);
        proxyService.sendInvalidateCacheMessage(targetUuid);
        scheduler.runTask(() -> {
            Player player = Bukkit.getPlayer(targetUuid);
            if (player != null && player.isOnline()) notificationService.formatMuteScreen(p).forEach(player::sendMessage);
        });
        notificationService.broadcastPunishment(p);
        IntegrationService integrationService = plugin.getIntegrationService();
        if (integrationService != null) integrationService.onPunishment(p);
        if (plugin.getConfigManager().isVoiceChatIntegrationEnabled() && plugin.getVoiceChatService() != null) {
            plugin.getVoiceChatService().updateStatus(targetUuid);
        }
    }

    public CompletableFuture<Boolean> unbanPlayer(String targetName, UUID staffUuid, String staffName) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return storage.getActiveBan(targetUuid).thenCompose(opt -> {
                if (opt.isPresent()) return storage.removeBan(targetUuid, staffUuid, staffName).thenApply(v -> true);
                return CompletableFuture.completedFuture(false);
            }).thenApply(success -> {
                if (success) {
                    invalidatePlayer(targetUuid);
                    scheduler.runTask(() -> notificationService.broadcastUnban(targetName, staffName));
                    IntegrationService integrationService = plugin.getIntegrationService();
                    if (integrationService != null) integrationService.onUnban(targetName, staffName);
                }
                return success;
            });
        });
    }

    public CompletableFuture<Boolean> unmutePlayer(String targetName, UUID staffUuid, String staffName) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.completedFuture(false);
            return storage.getActiveMute(targetUuid).thenCompose(opt -> {
                if (opt.isPresent()) {
                    return storage.removeMute(targetUuid, staffUuid, staffName).thenApply(v -> {
                        if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().invalidateMuteCache(targetUuid);
                        return true;
                    });
                }
                return CompletableFuture.completedFuture(false);
            }).thenApply(success -> {
                if (success) {
                    invalidatePlayer(targetUuid);
                    scheduler.runTask(() -> notificationService.broadcastUnmute(targetName, staffName));
                }
                return success;
            });
        });
    }

    public CompletableFuture<Boolean> unVoiceMutePlayer(String targetName, UUID staffUuid, String staffName) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return storage.getActiveVoiceMute(targetUuid).thenCompose(opt -> {
                if (opt.isPresent()) return storage.removeVoiceMute(targetUuid, staffUuid, staffName).thenApply(v -> true);
                return CompletableFuture.completedFuture(false);
            }).thenApply(success -> {
                if (success) {
                    invalidatePlayer(targetUuid);
                    if (plugin.getConfigManager().isVoiceChatIntegrationEnabled() && plugin.getVoiceChatService() != null) {
                        plugin.getVoiceChatService().updateStatus(targetUuid);
                    }
                }
                return success;
            });
        });
    }

    public CompletableFuture<Boolean> unbanIp(String ip, UUID staffUuid, String staffName) {
        return storage.getActiveIpBan(ip).thenCompose(opt -> {
            if (opt.isPresent()) return storage.removeIpBan(ip, staffUuid, staffName).thenApply(v -> true);
            return CompletableFuture.completedFuture(false);
        }).thenApply(success -> {
            if (success) {
                String maskedIp = IpUtil.maskIp(ip);
                scheduler.runTask(() -> notificationService.sendStaffAlertForAction("unban", maskedIp, staffName));
                IntegrationService integrationService = plugin.getIntegrationService();
                if (integrationService != null) integrationService.onUnban(ip, staffName);
            }
            return success;
        });
    }

    public CompletableFuture<Boolean> unbanIpByPlayer(String playerNameOrIp, UUID staffUuid, String staffName) {
        if (IP_PATTERN.matcher(playerNameOrIp).matches()) {
            return unbanIp(playerNameOrIp, staffUuid, staffName);
        }
        return playerResolver.getPlayerUuid(playerNameOrIp).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return storage.getLastIpForUuid(uuid).thenCompose(ip -> {
                if (ip == null) return CompletableFuture.failedFuture(new IllegalStateException("no-ip-on-record"));
                return unbanIp(ip, staffUuid, staffName);
            });
        });
    }

    public CompletableFuture<Boolean> unmuteIp(String ip, UUID staffUuid, String staffName) {
        return storage.getActiveIpMute(ip).thenCompose(opt -> {
            if (opt.isPresent()) return storage.removeIpMute(ip, staffUuid, staffName).thenApply(v -> true);
            return CompletableFuture.completedFuture(false);
        }).thenApply(success -> {
            if (success) {
                String maskedIp = IpUtil.maskIp(ip);
                scheduler.runTask(() -> notificationService.sendStaffAlertForAction("unmute", maskedIp, staffName));
            }
            return success;
        });
    }

    public CompletableFuture<Boolean> unmuteIpByPlayer(String playerNameOrIp, UUID staffUuid, String staffName) {
        if (IP_PATTERN.matcher(playerNameOrIp).matches()) {
            return unmuteIp(playerNameOrIp, staffUuid, staffName);
        }
        return playerResolver.getPlayerUuid(playerNameOrIp).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return storage.getLastIpForUuid(uuid).thenCompose(ip -> {
                if (ip == null) return CompletableFuture.failedFuture(new IllegalStateException("no-ip-on-record"));
                return unmuteIp(ip, staffUuid, staffName);
            });
        });
    }

    public CompletableFuture<Boolean> ipBanPlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) return CompletableFuture.completedFuture(false);
            if (!isSafeIp(ip)) {
                plugin.getLogger().warning("Attempted to IP ban unsafe/local IP: " + ip + ". Blocked.");
                return CompletableFuture.completedFuture(false);
            }
            long expiry = duration == -1 ? -1 : System.currentTimeMillis() + (duration * 1000);
            Punishment p = Punishment.builder().punishmentId(IdUtil.generatePunishmentId())
                    .type(PunishmentType.IPBAN)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes())).targetName(ip).targetIp(ip).reason(reason)
                    .staffUuid(staffUuid).staffName(staffName)
                    .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                    .serverOrigin("global").silent(resolveSilent(silent)).ipBan(true).build();
            return storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p)).thenApply(v -> true);
        });
    }

    public CompletableFuture<Boolean> ipMutePlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return playerResolver.getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) return CompletableFuture.completedFuture(false);
            if (!isSafeIp(ip)) {
                plugin.getLogger().warning("Attempted to IP mute unsafe/local IP: " + ip + ". Blocked.");
                return CompletableFuture.completedFuture(false);
            }
            long expiry = duration == -1 ? -1 : System.currentTimeMillis() + (duration * 1000);
            Punishment p = Punishment.builder().punishmentId(IdUtil.generatePunishmentId())
                    .type(duration == -1 ? PunishmentType.IPMUTE : PunishmentType.TEMPIPMUTE)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes())).targetName(ip).targetIp(ip).reason(reason)
                    .staffUuid(staffUuid).staffName(staffName)
                    .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                    .serverOrigin("global").silent(resolveSilent(silent)).ipBan(true).build();
            return storage.insertMute(p).thenRun(() -> onPunishmentSuccess(p)).thenApply(v -> true);
        });
    }

    private void onPunishmentSuccess(Punishment punishment) {
        cacheService.invalidatePlayerPunishments(punishment.getTargetUuid());
        proxyService.sendInvalidateCacheMessage(punishment.getTargetUuid());
        notificationService.onPunishmentSuccess(punishment);
        if (plugin.getConfigManager().isAltPunishEnabled() && punishment.getType().isBan()) {
            plugin.getAltService().punishAlts(punishment);
        }
    }

    private void invalidatePlayer(UUID uuid) {
        cacheService.invalidatePlayerPunishments(uuid);
        proxyService.sendInvalidateCacheMessage(uuid);
    }

    public CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid) { return cacheService.getOrCache("ban_" + uuid, () -> storage.getActiveBan(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid) { return cacheService.getOrCache("mute_" + uuid, () -> storage.getActiveMute(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<Optional<Punishment>> getActiveVoiceMute(UUID uuid) { return cacheService.getOrCache("voicemute_" + uuid, () -> storage.getActiveVoiceMute(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) { return storage.getPunishmentHistory(uuid, limit); }
    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) { return storage.getRecentPunishments(limit); }
    public CompletableFuture<Optional<Punishment>> getPunishmentById(String id) { return storage.getPunishmentById(id.toUpperCase()); }
    public CompletableFuture<List<Punishment>> getAllPunishments() { return storage.getAllPunishments(); }
    public CompletableFuture<Void> importPunishment(Punishment punishment) { return storage.importPunishment(punishment); }

    private CompletableFuture<Optional<Punishment>> enrichPunishmentWithName(Optional<Punishment> punishment) {
        if (punishment.isPresent()) {
            Punishment p = punishment.get();
            if (p.getTargetName() == null || p.getTargetName().equalsIgnoreCase("unknown")) {
                return storage.getLastKnownName(p.getTargetUuid()).thenApply(name -> {
                    if (name != null) return Optional.of(p.toBuilder().targetName(name).build());
                    return punishment;
                });
            }
        }
        return CompletableFuture.completedFuture(punishment);
    }

    private boolean isSafeIp(String ip) {
        return !UNSAFE_IP_PATTERN.matcher(ip).find();
    }

    private boolean resolveSilent(boolean commandSilent) {
        return resolveSilent(commandSilent, false);
    }

    private boolean resolveSilent(boolean commandSilent, boolean forceSilent) {
        return forceSilent || commandSilent || plugin.getConfigManager().isSilentByDefault();
    }

    public void recordPlayerLogin(Player player) {
        scheduler.runTaskForPlayer(player, () -> {
            storage.recordHistory(player.getUniqueId(), player.getName(), playerResolver.getPlayerIp(player.getUniqueId()));
        });
    }

    public CompletableFuture<Void> clearAllData() { return storage.clearAllData().thenRun(cacheService::invalidateAll); }
    public CompletableFuture<Boolean> clearPlayerData(String targetName) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture(false);
            return storage.clearPlayerData(uuid).thenRun(() -> invalidatePlayer(uuid)).thenApply(v -> true);
        });
    }
    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) { return storage.getActiveIpBan(ip); }
    public CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip) { return storage.getActiveIpMute(ip); }
}
