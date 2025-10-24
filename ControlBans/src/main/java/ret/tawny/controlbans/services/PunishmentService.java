package ret.tawny.controlbans.services;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.PunishmentDao;
import ret.tawny.controlbans.util.IdUtil;
import ret.tawny.controlbans.util.SchedulerAdapter;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.net.InetSocketAddress;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class PunishmentService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final CacheService cacheService;
    private final PunishmentDao punishmentDao;
    private final SchedulerAdapter scheduler;
    private final ProxyService proxyService;
    private EscalationService escalationService;
    private AuditService auditService;
    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

    private record PunishmentCheckResult(boolean canPunish, boolean forceSilent, String reason) {
        static PunishmentCheckResult allow() { return new PunishmentCheckResult(true, false, null); }
        static PunishmentCheckResult deny(String reason) { return new PunishmentCheckResult(false, false, reason); }
        static PunishmentCheckResult allowAndForceSilent(String reason) { return new PunishmentCheckResult(true, true, reason); }
    }

    public PunishmentService(ControlBansPlugin plugin, DatabaseManager databaseManager, CacheService cacheService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.cacheService = cacheService;
        this.punishmentDao = new PunishmentDao();
        this.scheduler = plugin.getSchedulerAdapter();
        this.proxyService = plugin.getProxyService();
    }

    public void setEscalationService(EscalationService escalationService) {
        this.escalationService = escalationService;
    }

    public void setAuditService(AuditService auditService) {
        this.auditService = auditService;
    }


    private CompletableFuture<PunishmentCheckResult> prePunishmentCheck(CommandSender sender, UUID targetUuid) {
        if (sender instanceof Player player && player.getUniqueId().equals(targetUuid)) {
            return CompletableFuture.completedFuture(PunishmentCheckResult.deny(plugin.getLocaleManager().getRawMessage("errors.cannot-punish-self")));
        }

        return CompletableFuture.supplyAsync(() -> {
            Player targetPlayer = Bukkit.getPlayer(targetUuid);
            if (targetPlayer != null && targetPlayer.isOnline()) {
                if (targetPlayer.hasPermission("controlbans.exempt") || targetPlayer.isOp()) {
                    if (sender instanceof Player) {
                        return PunishmentCheckResult.deny(plugin.getLocaleManager().getRawMessage("errors.cannot-punish-exempt"));
                    } else {
                        return PunishmentCheckResult.allowAndForceSilent("Player is exempt. Issuing punishment silently.");
                    }
                }
            }
            return PunishmentCheckResult.allow();
        });
    }

    public CompletableFuture<Void> banPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }

                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(ipBan ? PunishmentType.IPBAN : PunishmentType.BAN)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultBanReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(-1)
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .ipBan(ipBan)
                        .build();

                return databaseManager.executeAsync(connection -> punishmentDao.insertBan(connection, punishment))
                        .thenRun(() -> onPunishmentSuccess(punishment));
            });
        });
    }

    public CompletableFuture<Void> tempBanPlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }
                long expiryTime = System.currentTimeMillis() + (duration * 1000);

                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(ipBan ? PunishmentType.IPBAN : PunishmentType.TEMPBAN)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultBanReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(expiryTime)
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .ipBan(ipBan)
                        .build();

                return databaseManager.executeAsync(connection -> punishmentDao.insertBan(connection, punishment))
                        .thenRun(() -> onPunishmentSuccess(punishment));
            });
        });
    }

    public CompletableFuture<Void> mutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }
                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(PunishmentType.MUTE)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .targetIp(getPlayerIp(targetUuid))
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(-1)
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .build();

                return databaseManager.executeAsync(connection -> {
                    punishmentDao.insertMute(connection, punishment);
                    recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
                }).thenRun(() -> onPunishmentSuccess(punishment));
            });
        });
    }

    public CompletableFuture<Void> tempMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }
                if (!hasAdminPermission(staffUuid) && duration > plugin.getConfigManager().getMaxTempMuteDuration()) {
                    return CompletableFuture.failedFuture(new IllegalArgumentException("Duration exceeds maximum allowed"));
                }

                long expiryTime = System.currentTimeMillis() + (duration * 1000);
                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(PunishmentType.TEMPMUTE)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .targetIp(getPlayerIp(targetUuid))
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(expiryTime)
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .build();

                return databaseManager.executeAsync(connection -> {
                    punishmentDao.insertMute(connection, punishment);
                    recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
                }).thenRun(() -> onPunishmentSuccess(punishment));
            });
        });
    }

    public CompletableFuture<Void> kickPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }

                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(PunishmentType.KICK)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .targetIp(getPlayerIp(targetUuid))
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultKickReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(System.currentTimeMillis())
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .build();

                return databaseManager.executeAsync(connection -> {
                    punishmentDao.insertKick(connection, punishment);
                    recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
                }).thenRun(() -> onPunishmentSuccess(punishment));
            });
        });
    }

    public CompletableFuture<Void> warnPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            CommandSender sender = staffUuid == null ? Bukkit.getConsoleSender() : Bukkit.getPlayer(staffUuid);
            return prePunishmentCheck(sender, targetUuid).thenCompose(checkResult -> {
                if (!checkResult.canPunish()) {
                    return CompletableFuture.failedFuture(new IllegalStateException(checkResult.reason()));
                }
                Punishment punishment = Punishment.builder()
                        .punishmentId(IdUtil.generatePunishmentId())
                        .type(PunishmentType.WARN)
                        .targetUuid(targetUuid)
                        .targetName(targetName)
                        .targetIp(getPlayerIp(targetUuid))
                        .reason(reason != null ? reason : plugin.getConfigManager().getDefaultWarnReason())
                        .staffUuid(staffUuid)
                        .staffName(staffName)
                        .createdTime(System.currentTimeMillis())
                        .expiryTime(-1)
                        .serverOrigin(getServerName())
                        .silent(silent || checkResult.forceSilent())
                        .build();

                return databaseManager.executeAsync(connection -> {
                    punishmentDao.insertWarning(connection, punishment);
                    recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
                }).thenRun(() -> {
                    scheduler.runTask(() -> {
                        Player player = Bukkit.getPlayer(targetUuid);
                        if (player != null && player.isOnline()) {
                            List<Component> warnMessage = getMuteMessageFor(punishment);
                            warnMessage.forEach(player::sendMessage);
                        }
                    });
                    onPunishmentSuccess(punishment);
                });
            });
        });
    }

    private void onPunishmentSuccess(Punishment punishment) {
        cacheService.invalidatePlayerPunishments(punishment.getTargetUuid());

        scheduler.runTask(() -> {
            if (punishment.getType().isBan() || punishment.getType() == PunishmentType.KICK) {
                Component kickMessageComponent = getKickMessageFor(punishment);
                Player targetPlayer = Bukkit.getPlayer(punishment.getTargetUuid());
                if (targetPlayer != null && targetPlayer.isOnline()) {
                    targetPlayer.kick(kickMessageComponent);
                }
                String kickMessageString = LegacyComponentSerializer.legacySection().serialize(kickMessageComponent);
                proxyService.sendKickPlayerMessage(punishment.getTargetName(), kickMessageString);
            }
            broadcastPunishment(punishment);
        });

        plugin.getIntegrationService().onPunishment(punishment);
        if (auditService != null) {
            auditService.record("PUNISH_" + punishment.getType().name(), punishment, punishment.getStaffUuid(), punishment.getStaffName(), punishment.getReason())
                    .exceptionally(error -> {
                        plugin.getLogger().log(Level.WARNING, "Failed to record audit entry", error);
                        return null;
                    });
        }
        if (escalationService != null) {
            escalationService.onPunishmentApplied(punishment).exceptionally(error -> {
                plugin.getLogger().log(Level.WARNING, "Failed to process escalation logic", error);
                return null;
            });
        }
        handleAltPunishment(punishment);
    }

    private void broadcastPunishment(Punishment punishment) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;
        boolean isEffectivelySilent = punishment.isSilent() ^ plugin.getConfigManager().isSilentByDefault();
        if (isEffectivelySilent) return;

        Component message = formatBroadcastMessage(punishment);
        String legacyMessage = LegacyComponentSerializer.legacySection().serialize(message);
        proxyService.sendBroadcastMessage(legacyMessage);
    }

    public CompletableFuture<Boolean> unbanPlayer(String targetName, UUID staffUuid, String staffName) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }

            return databaseManager.executeQueryAsync(connection -> {
                Optional<Punishment> activeBan = punishmentDao.getActiveBan(connection, targetUuid);
                activeBan.ifPresent(p -> punishmentDao.removeBan(connection, targetUuid, staffUuid, staffName));
                return activeBan;
            }).thenApply(optional -> {
                if (optional.isPresent()) {
                    Punishment removed = optional.get();
                    cacheService.invalidatePlayerPunishments(targetUuid);
                    scheduler.runTask(() -> broadcastUnban(targetName, staffName));
                    plugin.getIntegrationService().onUnban(targetName, staffName);
                    if (auditService != null) {
                        auditService.record("UNBAN", removed, staffUuid, staffName, "Manual unban")
                                .exceptionally(error -> {
                                    plugin.getLogger().log(Level.WARNING, "Failed to record unban audit entry", error);
                                    return null;
                                });
                    }
                    return true;
                }
                return false;
            });
        });
    }

    public CompletableFuture<Boolean> unmutePlayer(String targetName, UUID staffUuid, String staffName) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                String errorMessage = plugin.getLocaleManager().getRawMessage("errors.player-not-found").replace("<player>", targetName);
                return CompletableFuture.failedFuture(new IllegalArgumentException(errorMessage));
            }
            return databaseManager.executeQueryAsync(connection -> {
                Optional<Punishment> activeMute = punishmentDao.getActiveMute(connection, targetUuid);
                activeMute.ifPresent(p -> punishmentDao.removeMute(connection, targetUuid, staffUuid, staffName));
                return activeMute;
            }).thenApply(optional -> {
                if (optional.isPresent()) {
                    Punishment removed = optional.get();
                    cacheService.invalidatePlayerPunishments(targetUuid);
                    scheduler.runTask(() -> broadcastUnmute(targetName, staffName));
                    if (auditService != null) {
                        auditService.record("UNMUTE", removed, staffUuid, staffName, "Manual unmute")
                                .exceptionally(error -> {
                                    plugin.getLogger().log(Level.WARNING, "Failed to record unmute audit entry", error);
                                    return null;
                                });
                    }
                    return true;
                }
                return false;
            });
        });
    }

    public void recordPlayerLogin(Player player) {
        databaseManager.executeAsync(connection -> {
            recordPlayerHistory(connection, player.getUniqueId(), player.getName(), getPlayerIp(player.getUniqueId()));
        });
    }

    public CompletableFuture<Boolean> ipBanPlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) {
                return CompletableFuture.completedFuture(false);
            }
            long expiryTime = (duration == -1) ? -1 : System.currentTimeMillis() + (duration * 1000);

            Punishment punishment = Punishment.builder()
                    .punishmentId(IdUtil.generatePunishmentId())
                    .type(expiryTime == -1 ? PunishmentType.IPBAN : PunishmentType.TEMPBAN)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes()))
                    .targetName(ip)
                    .targetIp(ip)
                    .reason(reason)
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(expiryTime)
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .ipBan(true)
                    .build();

            return databaseManager.executeAsync(connection -> punishmentDao.insertBan(connection, punishment))
                    .thenRun(() -> onPunishmentSuccess(punishment))
                    .thenApply(v -> true);
        });
    }

    public CompletableFuture<Boolean> ipMutePlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) {
                return CompletableFuture.completedFuture(false);
            }
            long expiryTime = (duration == -1) ? -1 : System.currentTimeMillis() + (duration * 1000);
            Punishment punishment = Punishment.builder()
                    .punishmentId(IdUtil.generatePunishmentId())
                    .type(expiryTime == -1 ? PunishmentType.MUTE : PunishmentType.TEMPMUTE)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes()))
                    .targetName(ip)
                    .targetIp(ip)
                    .reason(reason)
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(expiryTime)
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .ipBan(true)
                    .build();
            return databaseManager.executeAsync(connection -> punishmentDao.insertMute(connection, punishment))
                    .thenRun(() -> onPunishmentSuccess(punishment))
                    .thenApply(v -> true);
        });
    }

    public CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid) {
        return cacheService.getOrCache("ban_" + uuid,
                () -> databaseManager.executeQueryAsync(connection -> punishmentDao.getActiveBan(connection, uuid)),
                plugin.getConfigManager().getPunishmentCheckTTL()
        );
    }

    public CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid) {
        return cacheService.getOrCache("mute_" + uuid,
                () -> databaseManager.executeQueryAsync(connection -> punishmentDao.getActiveMute(connection, uuid)),
                plugin.getConfigManager().getPunishmentCheckTTL()
        );
    }

    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getActiveIpBan(connection, ip));
    }

    public CompletableFuture<Void> executeScheduledPunishment(ScheduledPunishment schedule) {
        long created = System.currentTimeMillis();
        long expiry = schedule.getDurationSeconds() <= 0 ? -1 : created + (schedule.getDurationSeconds() * 1000L);
        Punishment punishment = Punishment.builder()
                .punishmentId(IdUtil.generatePunishmentId())
                .type(schedule.getType())
                .targetUuid(schedule.getTargetUuid())
                .targetName(schedule.getTargetName())
                .targetIp(getPlayerIp(schedule.getTargetUuid()))
                .reason(schedule.getReason())
                .staffUuid(schedule.getStaffUuid())
                .staffName(schedule.getStaffName())
                .createdTime(created)
                .expiryTime(expiry)
                .serverOrigin(getServerName())
                .silent(schedule.isSilent())
                .ipBan(schedule.isIpBan())
                .build();

        return databaseManager.executeAsync(connection -> {
            switch (schedule.getType()) {
                case BAN, TEMPBAN, IPBAN -> punishmentDao.insertBan(connection, punishment);
                case MUTE, TEMPMUTE -> {
                    punishmentDao.insertMute(connection, punishment);
                    recordPlayerHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
                }
                case WARN -> {
                    punishmentDao.insertWarning(connection, punishment);
                    recordPlayerHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
                }
                case KICK -> punishmentDao.insertKick(connection, punishment);
            }
        }).thenRun(() -> onPunishmentSuccess(punishment));
    }

    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getPunishmentHistory(connection, uuid, limit));
    }

    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getRecentPunishments(connection, limit));
    }

    public CompletableFuture<Optional<Punishment>> getPunishmentById(String id) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getPunishmentById(connection, id.toUpperCase()));
    }

    private CompletableFuture<String> getIpFromTarget(String target) {
        if (IP_PATTERN.matcher(target).matches()) {
            return CompletableFuture.completedFuture(target);
        }
        return getPlayerUuid(target).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture(null);
            return databaseManager.executeQueryAsync(connection -> punishmentDao.getLastIpForUuid(connection, uuid));
        });
    }

    private void broadcastUnban(String playerName, String staffName) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;
        Component message = plugin.getLocaleManager().getMessage("broadcasts.unban",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("staff", staffName)
        );
        String legacyMessage = LegacyComponentSerializer.legacySection().serialize(message);
        proxyService.sendBroadcastMessage(legacyMessage);
    }

    private void broadcastUnmute(String playerName, String staffName) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;
        Component message = plugin.getLocaleManager().getMessage("broadcasts.unmute",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("staff", staffName)
        );
        String legacyMessage = LegacyComponentSerializer.legacySection().serialize(message);
        proxyService.sendBroadcastMessage(legacyMessage);
    }

    private Component formatBroadcastMessage(Punishment punishment) {
        String typeKey = punishment.getType().name().toLowerCase();
        if (punishment.getType() == PunishmentType.IPBAN) {
            typeKey = "ipban";
        }
        String formatPath = "broadcasts." + typeKey;

        return plugin.getLocaleManager().getMessage(formatPath,
                Placeholder.unparsed("player", punishment.getTargetName()),
                Placeholder.unparsed("staff", punishment.getStaffName() != null ? punishment.getStaffName() : "Console"),
                Placeholder.unparsed("reason", punishment.getReason()),
                Placeholder.unparsed("duration", punishment.getType().isTemporary() ? TimeUtil.formatDuration(punishment.getRemainingTime() / 1000) : "Permanent")
        );
    }

    public Component getKickMessageFor(Punishment punishment) {
        String configPath;
        if (punishment.isIpBan()) {
            configPath = punishment.isPermanent() ? "screens.ip_ban" : "screens.ip_tempban";
        } else if (punishment.getType() == PunishmentType.KICK) {
            configPath = "screens.kick";
        } else {
            configPath = punishment.isPermanent() ? "screens.ban" : "screens.tempban";
        }

        List<Component> lines = formatPunishmentScreen(punishment, configPath);
        return Component.join(JoinConfiguration.newlines(), lines);
    }

    public List<Component> getMuteMessageFor(Punishment punishment) {
        String configPath;
        if (punishment.isIpBan()) {
            configPath = punishment.isPermanent() ? "screens.ip_mute" : "screens.ip_tempmute";
        } else if (punishment.getType() == PunishmentType.WARN) {
            configPath = "screens.warn";
        } else {
            configPath = punishment.isPermanent() ? "screens.mute" : "screens.tempmute";
        }
        return formatPunishmentScreen(punishment, configPath);
    }

    private List<Component> formatPunishmentScreen(Punishment punishment, String configPath) {
        String staff = punishment.getStaffName() != null ? punishment.getStaffName() : "Console";
        String reason = punishment.getReason() != null ? punishment.getReason() : "Unspecified";
        String id = punishment.getPunishmentId() != null ? punishment.getPunishmentId() : "N/A";
        String date = TimeUtil.formatDate(punishment.getCreatedTime());
        String duration = punishment.isPermanent() || punishment.getExpiryTime() <= 0 ? "Permanent" : TimeUtil.formatDuration(punishment.getRemainingTime() / 1000);

        String playerName = punishment.getTargetName();
        if (playerName == null) {
            playerName = Bukkit.getOfflinePlayer(punishment.getTargetUuid()).getName();
            if (playerName == null) {
                playerName = "Unknown";
            }
        }

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("player", playerName))
                .resolver(Placeholder.unparsed("staff", staff))
                .resolver(Placeholder.unparsed("reason", reason))
                .resolver(Placeholder.unparsed("id", id))
                .resolver(Placeholder.unparsed("date", date))
                .resolver(Placeholder.unparsed("duration", duration))
                .build();

        return plugin.getLocaleManager().getMessageListFor(punishment.getTargetUuid(), configPath, placeholders);
    }

    private String getPlayerIp(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            InetSocketAddress address = player.getAddress();
            if (address != null) {
                return address.getAddress().getHostAddress();
            }
        }
        return null;
    }

    private String getServerName() {
        return "global";
    }

    private boolean hasAdminPermission(UUID uuid) {
        if (uuid == null) return true; // Console
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.hasPermission("controlbans.admin");
    }

    private void handleAltPunishment(Punishment punishment) {
        if (!plugin.getConfigManager().isAltPunishEnabled() || !punishment.getType().isBan()) {
            return;
        }
        plugin.getAltService().punishAlts(punishment).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to punish alts for " + punishment.getTargetName(), throwable);
            return null;
        });
    }

    private CompletableFuture<UUID> getPlayerUuid(String playerName) {
        return cacheService.getOrCache("uuid_" + playerName.toLowerCase(),
                () -> UuidUtil.getUuid(playerName),
                plugin.getConfigManager().getPlayerLookupTTL()
        );
    }

    private void recordPlayerHistory(Connection connection, UUID uuid, String name, String ip) {
        if (ip == null || ip.isBlank()) return;

        String sql = getHistoryInsertSql();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, name);
            stmt.setString(3, uuid.toString());
            stmt.setString(4, ip);
            stmt.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record player history for " + name, e);
        }
    }

    private String getHistoryInsertSql() {
        return switch (plugin.getDatabaseManager().getDatabaseType()) {
            case "sqlite" -> "INSERT OR IGNORE INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)";
            case "postgresql" -> "INSERT INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?) ON CONFLICT (uuid, ip) DO NOTHING";
            default -> "INSERT IGNORE INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)";
        };
    }
}