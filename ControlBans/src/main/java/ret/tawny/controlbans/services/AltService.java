package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.DatabaseManager;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AltService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final CacheService cacheService;

    public AltService(ControlBansPlugin plugin, DatabaseManager databaseManager, CacheService cacheService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.cacheService = cacheService;
    }

    public CompletableFuture<List<UUID>> findAltAccounts(UUID uuid) {
        return cacheService.getOrCache("alts_" + uuid, () ->
                databaseManager.executeQueryAsync(connection -> {
                    try {
                        Set<UUID> alts = new HashSet<>();
                        Set<String> playerIps = getPlayerIps(connection, uuid);

                        if (playerIps.isEmpty()) {
                            return new ArrayList<>();
                        }

                        for (String ip : playerIps) {
                            alts.addAll(getPlayersForIp(connection, ip));
                        }

                        alts.remove(uuid); // Remove the original player from the alt list
                        return new ArrayList<>(alts);
                    } catch (SQLException e) {
                        throw new RuntimeException("Failed to find alt accounts", e);
                    }
                }), 600L // 10 minute cache
        );
    }

    public CompletableFuture<Set<String>> findSharedIps(UUID uuid) {
        return databaseManager.executeQueryAsync(connection -> {
            try {
                return getPlayerIps(connection, uuid);
            } catch (SQLException e) {
                throw new RuntimeException("Failed to find shared IPs", e);
            }
        });
    }

    public CompletableFuture<Void> punishAlts(Punishment originalPunishment) {
        return findAltAccounts(originalPunishment.getTargetUuid())
                .thenCompose(alts -> {
                    List<CompletableFuture<Void>> punishmentFutures = new ArrayList<>();
                    for (UUID altUuid : alts) {
                        punishmentFutures.add(createAltPunishment(altUuid, originalPunishment));
                    }
                    return CompletableFuture.allOf(punishmentFutures.toArray(new CompletableFuture[0]));
                });
    }

    private CompletableFuture<Void> createAltPunishment(UUID altUuid, Punishment originalPunishment) {
        return databaseManager.executeAsync(connection -> {
            String altReason = "Alt account of " + originalPunishment.getTargetName();
            String sql = """
                INSERT INTO litebans_bans (uuid, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
            try (PreparedStatement stmt = connection.prepareStatement(sql)) {
                stmt.setString(1, altUuid.toString());
                stmt.setString(2, altReason);
                stmt.setString(3, originalPunishment.getStaffUuid() != null ? originalPunishment.getStaffUuid().toString() : null);
                stmt.setString(4, "[ALT] " + originalPunishment.getStaffName());
                stmt.setLong(5, System.currentTimeMillis());
                stmt.setLong(6, originalPunishment.getExpiryTime());
                stmt.setString(7, originalPunishment.getServerOrigin());
                stmt.setBoolean(8, true); // Alt punishments are always silent
                stmt.setBoolean(9, false);
                stmt.setBoolean(10, true);
                stmt.executeUpdate();
            }
        });
    }

    private Set<String> getPlayerIps(Connection connection, UUID uuid) throws SQLException {
        Set<String> ips = new HashSet<>();
        String sql = "SELECT DISTINCT ip FROM litebans_history WHERE uuid = ? AND ip IS NOT NULL";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ips.add(rs.getString("ip"));
                }
            }
        }
        return ips;
    }

    private Set<UUID> getPlayersForIp(Connection connection, String ip) throws SQLException {
        Set<UUID> players = new HashSet<>();
        String sql = "SELECT DISTINCT uuid FROM litebans_history WHERE ip = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    players.add(UUID.fromString(rs.getString("uuid")));
                }
            }
        }
        return players;
    }
}