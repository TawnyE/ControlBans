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
    private final PlayerResolver playerResolver;
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
        this.playerResolver = new PlayerResolver(plugin, storage, cacheService);
        this.notificationService = new NotificationService(plugin);
    }

    public PlayerResolver getPlayerResolver() { return playerResolver; }
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
        return banPlayer(targetName, reason, staffUuid, staffName, silent, ipBan, null);
    }

    public CompletableFuture<Void> banPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null)
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));

            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish())
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));

                String finalReason = reason != null ? reason : plugin.getConfigManager().getDefaultBanReason();

                long now = System.currentTimeMillis();
                Punishment p = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId()).type(ipBan ? PunishmentType.IPBAN : PunishmentType.BAN)
                        .targetUuid(targetUuid).targetName(targetName)
                        .reason(finalReason).staffUuid(staffUuid).staffName(staffName)
                        .createdTime(now).expiryTime(-1)
                        .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).ipBan(ipBan)
                        .templateKey(templateKey).build();

                // Prevent duplicate active punishment of the same family before issuing;
                // stripe-serialized per target on this node.
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.BAN)
                        .thenCompose(ignored -> storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p))));
            });
        });
    }

    public CompletableFuture<Long> tempBanPlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return tempBanPlayer(targetName, duration, reason, staffUuid, staffName, silent, ipBan, null);
    }

    public CompletableFuture<Long> tempBanPlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));

                long clampedFinal = duration > 0 ? Math.min(duration, plugin.getConfigManager().getMaxTempBanDuration()) : duration;
                String finalReason = reason != null ? reason : plugin.getConfigManager().getDefaultBanReason();
                long now = System.currentTimeMillis();
                long expiry = (clampedFinal == -1) ? -1 : now + (clampedFinal * 1000L);
                Punishment p = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(ipBan ? (clampedFinal == -1 ? PunishmentType.IPBAN : PunishmentType.TEMPIPBAN) : (clampedFinal == -1 ? PunishmentType.BAN : PunishmentType.TEMPBAN))
                        .targetUuid(targetUuid).targetName(targetName)
                        .reason(finalReason)
                        .staffUuid(staffUuid).staffName(staffName)
                        .createdTime(now).expiryTime(expiry)
                        .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent())).ipBan(ipBan)
                        .templateKey(templateKey)
                        .build();
                // Prevent duplicate active ban before issuing (stripe-serialized per target).
                // Return the applied duration so callers can message the real value, not the requested one.
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.BAN)
                        .thenCompose(ignored -> storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p)).thenApply(v -> clampedFinal)));
            });
        });
    }

    // Note: permanent mutes/warns are duration-less punishment paths; they count
    // toward prior history but carry no automatic escalation wiring here.
    public CompletableFuture<Void> mutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return mutePlayer(targetName, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Void> mutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.MUTE).thenCompose(ignored -> scheduler.callSync(() -> {
                    String targetIp = resolveStealthIp(targetUuid, playerResolver.getPlayerIp(targetUuid));
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.MUTE)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(-1)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey).build();
                })).thenCompose(p -> storage.insertMute(p).thenRun(() -> {
                    if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().cacheMuteState(targetUuid, p);
                    onPunishmentSuccess(p);
                })));
            });
        });
    }

    public CompletableFuture<Long> tempMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return tempMutePlayer(targetName, duration, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Long> tempMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                long clampedFinal = duration > 0 ? Math.min(duration, plugin.getConfigManager().getMaxTempMuteDuration()) : duration;
                String finalReason = reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason();
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.MUTE).thenCompose(ignored -> scheduler.callSync(() -> {
                    String targetIp = resolveStealthIp(targetUuid, playerResolver.getPlayerIp(targetUuid));
                    long now = System.currentTimeMillis();
                    long expiry = clampedFinal == -1 ? -1 : now + (clampedFinal * 1000L);
                    PunishmentType type = clampedFinal == -1 ? PunishmentType.MUTE : PunishmentType.TEMPMUTE;
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(type)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(finalReason)
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(now).expiryTime(expiry)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey)
                            .build();
                })).thenCompose(p -> storage.insertMute(p).thenRun(() -> {
                    if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().cacheMuteState(targetUuid, p);
                    onPunishmentSuccess(p);
                }).thenApply(v -> clampedFinal)));
            });
        });
    }

    public CompletableFuture<Void> kickPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return kickPlayer(targetName, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Void> kickPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = resolveStealthIp(targetUuid, playerResolver.getPlayerIp(targetUuid));
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.KICK)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultKickReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(System.currentTimeMillis())
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey).build();
                }).thenCompose(p -> storage.insertKick(p).thenRun(() -> onPunishmentSuccess(p)));
            });
        });
    }

    // Note: warns are duration-less and structurally cannot take an escalated duration;
    // they only count toward priors for later punishments.
    public CompletableFuture<Void> warnPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return warnPlayer(targetName, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Void> warnPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                return scheduler.callSync(() -> {
                    String targetIp = resolveStealthIp(targetUuid, playerResolver.getPlayerIp(targetUuid));
                    return Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.WARN)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultWarnReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(-1)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey).build();
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

    // Note: permanent voice mutes are duration-less; they count toward prior
    // history for later punishments â€” no escalation wiring here.
    public CompletableFuture<Void> voiceMutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return voiceMutePlayer(targetName, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Void> voiceMutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                // Prevent duplicate active voice mute before issuing.
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.VOICEMUTE).thenCompose(ignored -> {
                    Punishment p = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(PunishmentType.VOICEMUTE)
                            .targetUuid(targetUuid).targetName(targetName)
                            .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(System.currentTimeMillis()).expiryTime(-1)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey).build();
                    return storage.insertVoiceMute(p).thenRun(() -> {
                        postVoiceMuteActions(targetUuid, p);
                    });
                }));
            });
        });
    }

    public CompletableFuture<Long> tempVoiceMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return tempVoiceMutePlayer(targetName, duration, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Long> tempVoiceMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
            return prePunishmentCheck(staffUuid, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) return CompletableFuture.failedFuture(new IllegalStateException(checkResult.failureMessage()));
                long clampedFinal = duration > 0 ? Math.min(duration, plugin.getConfigManager().getMaxTempMuteDuration()) : duration;
                String finalReason = reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason();
                return withStripe(targetUuid, () -> ensureNoActivePunishment(targetUuid, targetName, PunishmentKind.VOICEMUTE).thenCompose(ignored -> {
                    long now = System.currentTimeMillis();
                    long expiry = clampedFinal == -1 ? -1 : now + (clampedFinal * 1000L);
                    PunishmentType type = clampedFinal == -1 ? PunishmentType.VOICEMUTE : PunishmentType.TEMPVOICEMUTE;
                    String targetIp = resolveStealthIp(targetUuid, playerResolver.getPlayerIp(targetUuid));
                    Punishment p = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()).type(type)
                            .targetUuid(targetUuid).targetName(targetName).targetIp(targetIp)
                            .reason(finalReason)
                            .staffUuid(staffUuid).staffName(staffName)
                            .createdTime(now).expiryTime(expiry)
                            .serverOrigin("global").silent(resolveSilent(silent, checkResult.forceSilent()))
                            .templateKey(templateKey)
                            .build();
                    return storage.insertVoiceMute(p).thenRun(() -> {
                        postVoiceMuteActions(targetUuid, p);
                    }).thenApply(v -> clampedFinal);
                }));
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
                invalidateIp(ip);
                String maskedIp = IpUtil.maskIp(ip);
                scheduler.runTask(() -> notificationService.sendStaffAlertForAction("unban", maskedIp, staffName));
                IntegrationService integrationService = plugin.getIntegrationService();
                if (integrationService != null) integrationService.onUnban(ip, staffName);
            }
            return success;
        });
    }

    public CompletableFuture<Boolean> unbanIpById(String id, UUID staffUuid, String staffName) {
        return storage.getActiveIpBanById(id).thenCompose(opt -> {
            if (opt.isPresent()) {
                String ip = opt.get().getTargetIp();
                return storage.removeIpBan(ip, staffUuid, staffName).thenApply(v -> ip);
            }
            return CompletableFuture.completedFuture(null);
        }).thenApply(ip -> {
            if (ip != null) {
                invalidateIp(ip);
                String maskedIp = IpUtil.maskIp(ip);
                scheduler.runTask(() -> notificationService.sendStaffAlertForAction("unban", maskedIp, staffName));
                IntegrationService integrationService = plugin.getIntegrationService();
                if (integrationService != null) integrationService.onUnban(ip, staffName);
            }
            return ip != null;
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
                invalidateIp(ip);
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
        return ipBanPlayer(target, duration, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Boolean> ipBanPlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        if (duration != -1 && duration <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Duration must be greater than 0"));
        }
        return playerResolver.getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) return CompletableFuture.completedFuture(false);
            if (!isSafeIp(ip)) {
                plugin.getLogger().warning("Attempted to IP ban unsafe/local IP: " + ip + ". Blocked.");
                return CompletableFuture.failedFuture(new IllegalStateException("unsafe-ip"));
            }
            // Prevent duplicate active IP ban before issuing (IP-serialized).
            return withIpStripe(ip, () -> storage.getActiveIpBan(ip).thenCompose(existing -> {
                if (existing.isPresent() && !existing.get().isExpired()) {
                    plugin.getLogger().info("[Punishment] Skipping duplicate IP ban for " + ip + " â€” active ban already exists.");
                    return CompletableFuture.completedFuture(false);
                }
                long expiry;
                try {
                    expiry = duration == -1 ? -1 : Math.addExact(System.currentTimeMillis(), Math.multiplyExact(duration, 1000L));
                } catch (ArithmeticException e) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Duration overflow"));
                }
                Punishment p = Punishment.builder().punishmentId(IdUtil.generatePunishmentId())
                        .type(duration == -1 ? PunishmentType.IPBAN : PunishmentType.TEMPIPBAN)
                        .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8))).targetName(ip).targetIp(ip).reason(reason)
                        .staffUuid(staffUuid).staffName(staffName)
                        .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                        .serverOrigin("global").silent(resolveSilent(silent)).ipBan(true)
                        .templateKey(templateKey).build();
                return storage.insertBan(p).thenRun(() -> onPunishmentSuccess(p)).thenApply(v -> true);
            }));
        });
    }

    public CompletableFuture<Boolean> ipMutePlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return ipMutePlayer(target, duration, reason, staffUuid, staffName, silent, null);
    }

    public CompletableFuture<Boolean> ipMutePlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent, String templateKey) {
        if (duration != -1 && duration <= 0) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Duration must be greater than 0"));
        }
        return playerResolver.getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) return CompletableFuture.completedFuture(false);
            if (!isSafeIp(ip)) {
                plugin.getLogger().warning("Attempted to IP mute unsafe/local IP: " + ip + ". Blocked.");
                return CompletableFuture.failedFuture(new IllegalStateException("unsafe-ip"));
            }
            // Prevent duplicate active IP mute before issuing (IP-serialized).
            return withIpStripe(ip, () -> storage.getActiveIpMute(ip).thenCompose(existing -> {
                if (existing.isPresent() && !existing.get().isExpired()) {
                    plugin.getLogger().info("[Punishment] Skipping duplicate IP mute for " + ip + " â€” active mute already exists.");
                    return CompletableFuture.completedFuture(false);
                }
                long expiry;
                try {
                    expiry = duration == -1 ? -1 : Math.addExact(System.currentTimeMillis(), Math.multiplyExact(duration, 1000L));
                } catch (ArithmeticException e) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Duration overflow"));
                }
                Punishment p = Punishment.builder().punishmentId(IdUtil.generatePunishmentId())
                        .type(duration == -1 ? PunishmentType.IPMUTE : PunishmentType.TEMPIPMUTE)
                        .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8))).targetName(ip).targetIp(ip).reason(reason)
                        .staffUuid(staffUuid).staffName(staffName)
                        .createdTime(System.currentTimeMillis()).expiryTime(expiry)
                        .serverOrigin("global").silent(resolveSilent(silent)).ipBan(true)
                        .templateKey(templateKey).build();
                return storage.insertMute(p).thenRun(() -> onPunishmentSuccess(p)).thenApply(v -> true);
            }));
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

    /**
     * Verify that no active punishment exists for the target before issuing a new one.
     * Fails the future with an IllegalStateException if a duplicate would be created.
     * This is serialized per target UUID on this JVM (see {@link #stripes}) so that the
     * subsequent INSERT cannot interleave with another check for the same player.
     * Uses uncached storage reads and ignores naturally-expired rows (which may linger
     * cached or with active=TRUE until the sweeper clears them).
     */
    private CompletableFuture<Void> ensureNoActivePunishment(UUID targetUuid, String targetName, PunishmentKind kind) {
        return kind.activeLookup.apply(storage, targetUuid).thenCompose(existing -> {
            if (existing != null && existing.isPresent() && !existing.get().isExpired()) {
                if (plugin.getConfigManager().isDebugLoggingEnabled()) {
                    plugin.getLogger().info("[Punishment] Skipping duplicate " + kind.logName + " for " + targetUuid
                            + " â€” active " + kind.logName + " already exists (id=" + existing.get().getPunishmentId() + ").");
                } else {
                    plugin.getLogger().fine("[Punishment] Duplicate " + kind.logName + " blocked for " + targetUuid);
                }
                String denyMessage = plugin.getLocaleManager().getRawMessage(kind.localeKey)
                        .replace("<player>", targetName != null ? targetName : "Unknown");
                return CompletableFuture.failedFuture(new IllegalStateException(denyMessage));
            }
            return CompletableFuture.completedFuture(null);
        });
    }

    private enum PunishmentKind {
        BAN("ban", "errors.already-banned", StorageInterface::getActiveBan),
        MUTE("mute", "errors.already-muted", StorageInterface::getActiveMute),
        VOICEMUTE("voice mute", "errors.already-voice-muted", StorageInterface::getActiveVoiceMute);

        final String logName;
        final String localeKey;
        final java.util.function.BiFunction<StorageInterface, UUID, CompletableFuture<Optional<Punishment>>> activeLookup;

        PunishmentKind(String logName, String localeKey,
                       java.util.function.BiFunction<StorageInterface, UUID, CompletableFuture<Optional<Punishment>>> activeLookup) {
            this.logName = logName;
            this.localeKey = localeKey;
            this.activeLookup = activeLookup;
        }
    }

    // Per-UUID stripes to serialize the duplicate-check + INSERT window per target on this node.
    // Implemented as a future chain (not synchronized): each action is appended to the tail
    // for its UUID so check-then-insert cannot interleave with another action for the same player.
    private final java.util.concurrent.ConcurrentHashMap<UUID, CompletableFuture<?>> stripes = new java.util.concurrent.ConcurrentHashMap<>();
    private final java.util.concurrent.ConcurrentHashMap<String, CompletableFuture<?>> ipStripes = new java.util.concurrent.ConcurrentHashMap<>();

    private <T> CompletableFuture<T> withStripe(UUID uuid, java.util.function.Supplier<CompletableFuture<T>> action) {
        CompletableFuture<T> result = new CompletableFuture<>();
        stripes.compute(uuid, (key, tail) -> {
            CompletableFuture<?> prev = tail != null ? tail : CompletableFuture.completedFuture(null);
            CompletableFuture<T> next = prev.handle((v, e) -> null).thenCompose(ignored -> {
                CompletableFuture<T> inner;
                try {
                    inner = action.get();
                } catch (Throwable t) {
                    CompletableFuture<T> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return failed;
                }
                return inner;
            });
            next.whenComplete((v, t) -> {
                if (t != null) result.completeExceptionally(t);
                else result.complete(v);
            });
            // Swallow errors in the stored tail so the chain never breaks for later actions.
            return next.handle((v, e) -> null).thenApply(x -> null);
        });
        // Best-effort cleanup to avoid unbounded growth; the chain entry is replaced, not removed,
        // so removal races are harmless â€” only remove if our tail is still the current value.
        result.whenComplete((v, t) -> stripes.computeIfPresent(uuid, (key, cur) -> cur.isDone() ? null : cur));
        return result;
    }

    private <T> CompletableFuture<T> withIpStripe(String ip, java.util.function.Supplier<CompletableFuture<T>> action) {
        String key = ip == null ? "null" : ip.toLowerCase(java.util.Locale.ROOT);
        CompletableFuture<T> result = new CompletableFuture<>();
        ipStripes.compute(key, (k, tail) -> {
            CompletableFuture<?> prev = tail != null ? tail : CompletableFuture.completedFuture(null);
            CompletableFuture<T> next = prev.handle((v, e) -> null).thenCompose(ignored -> {
                CompletableFuture<T> inner;
                try {
                    inner = action.get();
                } catch (Throwable t) {
                    CompletableFuture<T> failed = new CompletableFuture<>();
                    failed.completeExceptionally(t);
                    return failed;
                }
                return inner;
            });
            next.whenComplete((v, t) -> {
                if (t != null) result.completeExceptionally(t);
                else result.complete(v);
            });
            return next.handle((v, e) -> null).thenApply(x -> null);
        });
        result.whenComplete((v, t) -> ipStripes.computeIfPresent(key, (k, cur) -> cur.isDone() ? null : cur));
        return result;
    }

    private void invalidatePlayer(UUID uuid) {
        cacheService.invalidatePlayerPunishments(uuid);
        proxyService.sendInvalidateCacheMessage(uuid);
        if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().invalidateMuteCache(uuid);
    }

    private void invalidateIp(String ip) {
        // IP rows use UUID.nameUUIDFromBytes(ip) as targetUuid â€” evict that too,
        // plus every online player currently on the IP, plus a proxy broadcast.
        // Caffeine/proxy work is thread-safe and runs now; Bukkit API work runs on main.
        try {
            UUID ipUuid = UUID.nameUUIDFromBytes(ip.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            cacheService.invalidatePlayerPunishments(ipUuid);
            proxyService.sendInvalidateCacheMessage(ipUuid);
        } catch (Exception ignored) {}
        scheduler.runTask(() -> {
            try {
                for (org.bukkit.entity.Player online : org.bukkit.Bukkit.getOnlinePlayers()) {
                    try {
                        String onlineIp = online.getAddress() != null && online.getAddress().getAddress() != null
                                ? online.getAddress().getAddress().getHostAddress() : null;
                        if (ip.equals(onlineIp)) {
                            cacheService.invalidatePlayerPunishments(online.getUniqueId());
                            proxyService.sendInvalidateCacheMessage(online.getUniqueId());
                            if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().invalidateMuteCache(online.getUniqueId());
                            if (plugin.getVoiceChatService() != null) {
                                try {
                                    plugin.getVoiceChatService().updateStatus(online.getUniqueId());
                                } catch (Exception ignored) {}
                            }
                        }
                    } catch (Exception ignored) {}
                }
            } catch (Exception ignored) {}
        });
    }

    public CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid) { return cacheService.getOrCache("ban_" + uuid, () -> storage.getActiveBan(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }

    /**
     * Login-time ban check with an identity fallback: if no active ban exists
     * for the join UUID, retry by the stored target name. This closes the gap
     * where a punishment was recorded under a different UUID than the one the
     * player joins with (offline vs Mojang identity mixes, proxy UUID
     * algorithms, database identity drift).
     *
     * This deliberately bypasses the punishment cache: cross-server cache
     * invalidation travels through plugin messages that need an online player
     * on the source server, so a cached result can be stale after an unban on
     * another node. One fresh SELECT per login is cheap; login security is not
     * the place to optimize a database round trip away.
     */
    public CompletableFuture<Optional<Punishment>> getActiveBanForLogin(UUID uuid, String name) {
        return storage.getActiveBan(uuid).thenCompose(this::enrichPunishmentWithName).thenCompose(opt -> {
            if (opt.isPresent() || name == null || name.isEmpty()) {
                return CompletableFuture.completedFuture(opt);
            }
            return storage.getActiveBanByName(name).thenApply(nameOpt -> {
                if (nameOpt.isPresent()) {
                    Punishment p = nameOpt.get();
                    if (p.getTargetName() == null || p.getTargetName().isEmpty() || "Unknown".equalsIgnoreCase(p.getTargetName())) {
                        return Optional.of(p.toBuilder().targetName(name).build());
                    }
                }
                return nameOpt;
            });
        });
    }
    public CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid) { return cacheService.getOrCache("mute_" + uuid, () -> storage.getActiveMute(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<Optional<Punishment>> getActiveVoiceMute(UUID uuid) { return cacheService.getOrCache("voicemute_" + uuid, () -> storage.getActiveVoiceMute(uuid).thenCompose(this::enrichPunishmentWithName), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) { return cacheService.getOrCache("history_" + uuid + "_" + limit, () -> storage.getPunishmentHistory(uuid, limit), plugin.getConfigManager().getPunishmentCheckTTL()); }
    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) { return storage.getRecentPunishments(limit); }
    public CompletableFuture<Optional<Punishment>> getPunishmentById(String id) {
        if (id == null) return CompletableFuture.completedFuture(Optional.empty());
        return storage.getPunishmentById(id.toUpperCase(java.util.Locale.ROOT));
    }
    public CompletableFuture<List<Punishment>> getAllPunishments() { return storage.getAllPunishments(); }
    public CompletableFuture<Void> importPunishment(Punishment punishment) {
        return storage.importPunishment(punishment).thenRun(() -> {
            if (punishment != null && punishment.getTargetUuid() != null) invalidatePlayer(punishment.getTargetUuid());
        });
    }

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
        // Warm the name->UUID cache immediately: a punishment issued seconds after
        // the target's first join must resolve the identity from the live session,
        // not from a DB write that may still be in flight (or a Mojang fallback).
        cacheService.getOrCache("uuid_" + player.getName().toLowerCase(java.util.Locale.ROOT),
                () -> CompletableFuture.completedFuture(player.getUniqueId()),
                plugin.getConfigManager().getPlayerLookupTTL());
        scheduler.runTaskForPlayer(player, () -> {
            String ip = resolveIpForStorage(player.getUniqueId(), playerResolver.getPlayerIp(player.getUniqueId()));
            storage.recordHistory(player.getUniqueId(), player.getName(), ip);
        });
    }

    private String resolveIpForStorage(UUID playerUuid, String realIp) {
        if (plugin.getConfigManager().isStealthModeEnabled()) {
            Player player = Bukkit.getPlayer(playerUuid);
            if (player != null && player.hasPermission("controlbans.stealth")) {
                return plugin.getConfigManager().getStealthDummyIp();
            }
        }
        return realIp;
    }

    private String resolveStealthIp(UUID targetUuid, String realIp) {
        if (plugin.getConfigManager().isStealthModeEnabled()) {
            Player target = Bukkit.getPlayer(targetUuid);
            if (target != null && target.hasPermission("controlbans.stealth")) {
                return plugin.getConfigManager().getStealthDummyIp();
            }
        }
        return realIp;
    }

    public boolean isStealthTarget(UUID targetUuid) {
        if (!plugin.getConfigManager().isStealthModeEnabled()) return false;
        Player target = Bukkit.getPlayer(targetUuid);
        return target != null && target.hasPermission("controlbans.stealth");
    }

    public CompletableFuture<Void> clearAllData() {
        CompletableFuture<Void> f = storage.clearAllData().thenRun(cacheService::invalidateAll);
        f.thenRun(() -> {
            try {
                if (plugin.getPlayerChatListener() != null) plugin.getPlayerChatListener().clearAllMuteStates();
            } catch (Exception ignored) {}
        });
        return f;
    }
    public CompletableFuture<Boolean> clearPlayerData(String targetName) {
        return playerResolver.getPlayerUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture(false);
            return storage.clearPlayerData(uuid).thenRun(() -> invalidatePlayer(uuid)).thenApply(v -> true);
        });
    }
    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) { return storage.getActiveIpBan(ip); }
    public CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip) { return storage.getActiveIpMute(ip); }
}
