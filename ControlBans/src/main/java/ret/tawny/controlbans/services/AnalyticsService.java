package ret.tawny.controlbans.services;

import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.storage.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

public class AnalyticsService {

    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;

    public AnalyticsService(DatabaseManager databaseManager, ConfigManager configManager) {
        this.databaseManager = databaseManager;
        this.configManager = configManager;
    }

    public CompletableFuture<AnalyticsSnapshot> getDashboardSnapshot() {
        int recentDays = Math.max(1, configManager.getAnalyticsRecentDays());
        long cutoff = Instant.now().minusSeconds(recentDays * 86400L).toEpochMilli();
        return databaseManager.executeQueryAsync(connection -> buildSnapshot(connection, cutoff));
    }

    private AnalyticsSnapshot buildSnapshot(Connection connection, long warningCutoff) throws SQLException {
        int activeBans = countByQuery(connection,
                "SELECT COUNT(*) FROM litebans_bans WHERE active = TRUE AND (until = -1 OR until > ?)",
                System.currentTimeMillis());
        int activeMutes = countByQuery(connection,
                "SELECT COUNT(*) FROM litebans_mutes WHERE active = TRUE AND (until = -1 OR until > ?)",
                System.currentTimeMillis());
        int warningsRecent = countByQuery(connection,
                "SELECT COUNT(*) FROM litebans_warnings WHERE time >= ?",
                warningCutoff);
        int appealsOpen;
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM controlbans_appeals WHERE status = ? AND submission_count > 0")) {
            stmt.setString(1, configManager.getDefaultAppealStatus());
            try (ResultSet rs = stmt.executeQuery()) {
                appealsOpen = rs.next() ? rs.getInt(1) : 0;
            }
        }

        Map<String, Integer> categories = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT category, COUNT(*) AS total FROM controlbans_punishment_meta GROUP BY category ORDER BY total DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    categories.put(rs.getString("category"), rs.getInt("total"));
                }
            }
        }

        Map<String, Integer> topReasons = new LinkedHashMap<>();
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT reason, COUNT(*) AS total FROM (" +
                        "SELECT reason FROM litebans_bans UNION ALL " +
                        "SELECT reason FROM litebans_mutes UNION ALL " +
                        "SELECT reason FROM litebans_warnings) " +
                        "GROUP BY reason ORDER BY total DESC LIMIT 5")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    topReasons.put(rs.getString("reason"), rs.getInt("total"));
                }
            }
        }

        return new AnalyticsSnapshot(activeBans, activeMutes, warningsRecent, appealsOpen, categories, topReasons);
    }

    private int countByQuery(Connection connection, String sql, Long param) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            if (param != null) {
                stmt.setLong(1, param);
            }
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    public record AnalyticsSnapshot(int activeBans,
                                    int activeMutes,
                                    int warningsRecent,
                                    int appealsOpen,
                                    Map<String, Integer> categoryBreakdown,
                                    Map<String, Integer> topReasons) { }
}
