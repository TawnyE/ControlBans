package ret.tawny.controlbans.services;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.PunishmentDao;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.sql.Connection;
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
    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

    public PunishmentService(ControlBansPlugin plugin, DatabaseManager databaseManager, CacheService cacheService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.cacheService = cacheService;
        this.punishmentDao = new PunishmentDao();
    }

    public CompletableFuture<Void> banPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            String targetIp = getPlayerIp(targetUuid);

            Punishment punishment = Punishment.builder()
                    .type(ipBan ? PunishmentType.IPBAN : PunishmentType.BAN)
                    .targetUuid(targetUuid)
                    .targetName(targetName)
                    .targetIp(targetIp)
                    .reason(reason != null ? reason : plugin.getConfigManager().getDefaultBanReason())
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(-1) // Permanent
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .ipBan(ipBan)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertBan(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, targetIp);
            }).thenRun(() -> {
                cacheService.invalidatePlayerPunishments(targetUuid);
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(formatBanMessage(punishment))));
                }
                handleAltPunishment(punishment);
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Void> tempBanPlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent, boolean ipBan) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            if (!hasAdminPermission(staffUuid) && duration > plugin.getConfigManager().getMaxTempBanDuration()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Duration exceeds maximum allowed"));
            }

            String targetIp = getPlayerIp(targetUuid);
            long expiryTime = System.currentTimeMillis() + (duration * 1000);

            Punishment punishment = Punishment.builder()
                    .type(PunishmentType.TEMPBAN)
                    .targetUuid(targetUuid)
                    .targetName(targetName)
                    .targetIp(targetIp)
                    .reason(reason != null ? reason : plugin.getConfigManager().getDefaultBanReason())
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(expiryTime)
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .ipBan(ipBan)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertBan(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, targetIp);
            }).thenRun(() -> {
                cacheService.invalidatePlayerPunishments(targetUuid);
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(formatTempBanMessage(punishment))));
                }
                handleAltPunishment(punishment);
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Boolean> unbanPlayer(String targetName, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            return databaseManager.executeQueryAsync(connection -> {
                Optional<Punishment> activeBan = punishmentDao.getActiveBan(connection, targetUuid);
                if (activeBan.isPresent()) {
                    punishmentDao.removeBan(connection, targetUuid, staffUuid, staffName);
                    return true;
                }
                return false;
            }).thenApply(success -> {
                if (success) {
                    cacheService.invalidatePlayerPunishments(targetUuid);
                    broadcastUnban(targetName, staffName);
                    plugin.getIntegrationService().onUnban(targetUuid, targetName, staffUuid, staffName);
                }
                return success;
            });
        });
    }

    public CompletableFuture<Void> mutePlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            Punishment punishment = Punishment.builder()
                    .type(PunishmentType.MUTE)
                    .targetUuid(targetUuid)
                    .targetName(targetName)
                    .targetIp(getPlayerIp(targetUuid))
                    .reason(reason != null ? reason : plugin.getConfigManager().getDefaultMuteReason())
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(-1) // Permanent
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertMute(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
            }).thenRun(() -> {
                cacheService.invalidatePlayerPunishments(targetUuid);
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Void> tempMutePlayer(String targetName, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }
            if (!hasAdminPermission(staffUuid) && duration > plugin.getConfigManager().getMaxTempMuteDuration()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Duration exceeds maximum allowed"));
            }

            long expiryTime = System.currentTimeMillis() + (duration * 1000);
            Punishment punishment = Punishment.builder()
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
                    .silent(silent)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertMute(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
            }).thenRun(() -> {
                cacheService.invalidatePlayerPunishments(targetUuid);
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Boolean> unmutePlayer(String targetName, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }
            return databaseManager.executeQueryAsync(connection -> {
                Optional<Punishment> activeMute = punishmentDao.getActiveMute(connection, targetUuid);
                if (activeMute.isPresent()) {
                    punishmentDao.removeMute(connection, targetUuid, staffUuid, staffName);
                    return true;
                }
                return false;
            }).thenApply(success -> {
                if (success) {
                    cacheService.invalidatePlayerPunishments(targetUuid);
                    broadcastUnmute(targetName, staffName);
                }
                return success;
            });
        });
    }

    public CompletableFuture<Void> warnPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            Punishment punishment = Punishment.builder()
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
                    .silent(silent)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertWarning(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
            }).thenRun(() -> {
                Player player = Bukkit.getPlayer(targetUuid);
                if (player != null && player.isOnline()) {
                    Bukkit.getScheduler().runTask(plugin, () -> player.sendMessage(Component.text(formatWarningMessage(punishment))));
                }
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Void> kickPlayer(String targetName, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getPlayerUuid(targetName).thenCompose(targetUuid -> {
            if (targetUuid == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found: " + targetName));
            }

            Player player = Bukkit.getPlayer(targetUuid);
            if (player == null || !player.isOnline()) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("Player is not online"));
            }

            Punishment punishment = Punishment.builder()
                    .type(PunishmentType.KICK)
                    .targetUuid(targetUuid)
                    .targetName(targetName)
                    .targetIp(player.getAddress().getAddress().getHostAddress())
                    .reason(reason != null ? reason : plugin.getConfigManager().getDefaultKickReason())
                    .staffUuid(staffUuid)
                    .staffName(staffName)
                    .createdTime(System.currentTimeMillis())
                    .expiryTime(System.currentTimeMillis())
                    .serverOrigin(getServerName())
                    .silent(silent)
                    .build();

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertKick(connection, punishment);
                recordPlayerHistory(connection, targetUuid, targetName, getPlayerIp(targetUuid));
            }).thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, () -> player.kick(Component.text(formatKickMessage(punishment))));
                broadcastPunishment(punishment);
                plugin.getIntegrationService().onPunishment(punishment);
            });
        });
    }

    public CompletableFuture<Boolean> ipBanPlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) {
                return CompletableFuture.completedFuture(false);
            }
            long expiryTime = (duration == -1) ? -1 : System.currentTimeMillis() + (duration * 1000);

            Punishment punishment = Punishment.builder()
                    .type(expiryTime == -1 ? PunishmentType.IPBAN : PunishmentType.TEMPBAN)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes())) // Placeholder UUID for the IP
                    .targetName(ip) // Use IP as the name for broadcast messages
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

            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertBan(connection, punishment); // IP bans are stored in the main bans table
            }).thenRun(() -> {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (player.getAddress() != null && ip.equals(player.getAddress().getAddress().getHostAddress())) {
                            String kickMessage = expiryTime == -1 ? formatBanMessage(punishment) : formatTempBanMessage(punishment);
                            player.kick(Component.text(kickMessage));
                        }
                    }
                });
                broadcastPunishment(punishment);
            }).thenApply(v -> true);
        });
    }

    public CompletableFuture<Boolean> ipMutePlayer(String target, long duration, String reason, UUID staffUuid, String staffName, boolean silent) {
        return getIpFromTarget(target).thenCompose(ip -> {
            if (ip == null) {
                return CompletableFuture.completedFuture(false);
            }
            long expiryTime = (duration == -1) ? -1 : System.currentTimeMillis() + (duration * 1000);
            Punishment punishment = Punishment.builder()
                    .type(expiryTime == -1 ? PunishmentType.MUTE : PunishmentType.TEMPMUTE)
                    .targetUuid(UUID.nameUUIDFromBytes(ip.getBytes())) // Placeholder
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
            return databaseManager.executeAsync(connection -> {
                punishmentDao.insertMute(connection, punishment);
            }).thenRun(() -> broadcastPunishment(punishment)).thenApply(v -> true);
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

    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getPunishmentHistory(connection, uuid, limit));
    }

    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) {
        return databaseManager.executeQueryAsync(connection -> punishmentDao.getRecentPunishments(connection, limit));
    }

    private CompletableFuture<String> getIpFromTarget(String target) {
        if (IP_PATTERN.matcher(target).matches()) {
            return CompletableFuture.completedFuture(target);
        }
        return UuidUtil.getUuid(target).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture(null);
            return databaseManager.executeQueryAsync(connection -> punishmentDao.getLastIpForUuid(connection, uuid));
        });
    }

    private CompletableFuture<UUID> getPlayerUuid(String playerName) {
        return cacheService.getOrCache("uuid_" + playerName.toLowerCase(),
                () -> UuidUtil.getUuid(playerName),
                plugin.getConfigManager().getPlayerLookupTTL()
        );
    }

    private String getPlayerIp(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return (player != null && player.getAddress() != null) ? player.getAddress().getAddress().getHostAddress() : null;
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

    private void broadcastPunishment(Punishment punishment) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;

        boolean isEffectivelySilent = punishment.isSilent() ^ plugin.getConfigManager().isSilentByDefault();
        final String message = formatMessage(formatBroadcastMessage(punishment));

        if (isEffectivelySilent) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Bukkit.getConsoleSender().sendMessage(Component.text(message));
                for (Player player : Bukkit.getOnlinePlayers()) {
                    if (player.hasPermission("controlbans.notify.silent")) {
                        player.sendMessage(Component.text(message));
                    }
                }
            });
        } else {
            if (plugin.getConfigManager().isBroadcastConsole()) {
                Bukkit.getConsoleSender().sendMessage(Component.text(message));
            }
            if (plugin.getConfigManager().isBroadcastPlayers()) {
                Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(Component.text(message)));
            }
        }
    }

    private void broadcastUnban(String playerName, String staffName) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;
        final String message = formatMessage(plugin.getConfigManager().getMessage("punishments.broadcast.format.unban").replace("%player%", playerName).replace("%staff%", staffName));
        if (plugin.getConfigManager().isBroadcastConsole()) Bukkit.getConsoleSender().sendMessage(Component.text(message));
        if (plugin.getConfigManager().isBroadcastPlayers()) Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(Component.text(message)));
    }

    private void broadcastUnmute(String playerName, String staffName) {
        if (!plugin.getConfigManager().isBroadcastEnabled()) return;
        final String message = formatMessage(plugin.getConfigManager().getMessage("punishments.broadcast.format.unmute").replace("%player%", playerName).replace("%staff%", staffName));
        if (plugin.getConfigManager().isBroadcastConsole()) Bukkit.getConsoleSender().sendMessage(Component.text(message));
        if (plugin.getConfigManager().isBroadcastPlayers()) Bukkit.getScheduler().runTask(plugin, () -> Bukkit.broadcast(Component.text(message)));
    }

    private String formatBroadcastMessage(Punishment punishment) {
        String typeKey = punishment.getType().name().toLowerCase().replace("tempban", "tempban").replace("tempmute", "tempmute");
        String formatPath = "punishments.broadcast.format." + typeKey;
        String format = plugin.getConfigManager().getMessage(formatPath);
        return format.replace("%player%", punishment.getTargetName()).replace("%staff%", punishment.getStaffName()).replace("%reason%", punishment.getReason()).replace("%duration%", punishment.getType().isTemporary() ? TimeUtil.formatDuration(punishment.getRemainingTime() / 1000) : "permanent");
    }

    private String formatBanMessage(Punishment punishment) {
        return formatMessage("§cYou have been banned from this server.\n\n" + "§7Reason: §f" + punishment.getReason() + "\n" + "§7Banned by: §f" + punishment.getStaffName() + "\n" + "§7Duration: §fPermanent\n\n" + "§7Appeal at: §fyour-server.com/appeal");
    }

    private String formatTempBanMessage(Punishment punishment) {
        return formatMessage("§cYou have been temporarily banned from this server.\n\n" + "§7Reason: §f" + punishment.getReason() + "\n" + "§7Banned by: §f" + punishment.getStaffName() + "\n" + "§7Expires: §f" + TimeUtil.formatDuration(punishment.getRemainingTime() / 1000) + "\n\n" + "§7Appeal at: §fyour-server.com/appeal");
    }

    private String formatWarningMessage(Punishment punishment) {
        return formatMessage("§e§lWARNING\n\n" + "§7You have been warned for: §f" + punishment.getReason() + "\n" + "§7Warned by: §f" + punishment.getStaffName());
    }

    private String formatKickMessage(Punishment punishment) {
        return formatMessage("§cYou were kicked from the server.\n\n" + "§7Reason: §f" + punishment.getReason() + "\n" + "§7Kicked by: §f" + punishment.getStaffName());
    }

    private String formatMessage(String message) {
        return message.replace("&", "§");
    }

    private void recordPlayerHistory(Connection connection, UUID uuid, String name, String ip) {
        if (ip == null || ip.isBlank()) return;
        String dbType = plugin.getDatabaseManager().getDatabaseType();
        String sql = "postgresql".equals(dbType) ? "INSERT INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?) ON CONFLICT (uuid, ip) DO NOTHING" : "INSERT IGNORE INTO litebans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)";
        try (var stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setString(2, name);
            stmt.setString(3, uuid.toString());
            stmt.setString(4, ip);
            stmt.executeUpdate();
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to record player history for " + name, e);
        }
    }
}