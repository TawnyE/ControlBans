package ret.tawny.controlbans.services;

import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class AppealService {

    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final IntegrationService integrationService;

    public AppealService(DatabaseManager databaseManager, ConfigManager configManager, IntegrationService integrationService) {
        this.databaseManager = databaseManager;
        this.configManager = configManager;
        this.integrationService = integrationService;
    }

    public CompletableFuture<Void> ensureAppealRecord(Punishment punishment) {
        return databaseManager.executeAsync(connection -> {
            try (PreparedStatement stmt = connection.prepareStatement(
                    "INSERT INTO controlbans_appeals (punishment_id, target_uuid, status, submitted_at, updated_at, notes) " +
                            "VALUES (?, ?, ?, ?, ?, ?) " +
                            "ON CONFLICT(punishment_id) DO NOTHING")) {
                stmt.setString(1, punishment.getPunishmentId());
                stmt.setString(2, punishment.getTargetUuid().toString());
                stmt.setString(3, configManager.getDefaultAppealStatus());
                long now = System.currentTimeMillis();
                stmt.setLong(4, now);
                stmt.setLong(5, now);
                stmt.setString(6, punishment.getReason());
                stmt.executeUpdate();
            }
        });
    }

    public CompletableFuture<List<AppealRecord>> listOpenAppeals() {
        return databaseManager.executeQueryAsync(connection -> fetchAppeals(connection, "WHERE status = 'OPEN'"));
    }

    public CompletableFuture<List<AppealRecord>> listAllAppeals() {
        return databaseManager.executeQueryAsync(connection -> fetchAppeals(connection, ""));
    }

    public CompletableFuture<Void> updateAppealStatus(String punishmentId, String status, String reviewer, String notes) {
        return databaseManager.executeQueryAsync(connection -> {
            String targetUuid = null;
            try (PreparedStatement select = connection.prepareStatement(
                    "SELECT target_uuid FROM controlbans_appeals WHERE punishment_id = ?")) {
                select.setString(1, punishmentId);
                try (ResultSet rs = select.executeQuery()) {
                    if (rs.next()) {
                        targetUuid = rs.getString("target_uuid");
                    }
                }
            }
            try (PreparedStatement stmt = connection.prepareStatement(
                    "UPDATE controlbans_appeals SET status = ?, reviewer = ?, notes = ?, updated_at = ? WHERE punishment_id = ?")) {
                stmt.setString(1, status);
                stmt.setString(2, reviewer);
                stmt.setString(3, notes);
                stmt.setLong(4, System.currentTimeMillis());
                stmt.setString(5, punishmentId);
                stmt.executeUpdate();
            }
            return targetUuid;
        }).thenAccept(targetUuid -> integrationService.onAppealUpdate(punishmentId, status, reviewer, notes, targetUuid));
    }

    private List<AppealRecord> fetchAppeals(Connection connection, String whereClause) throws SQLException {
        String sql = "SELECT punishment_id, target_uuid, status, submitted_at, updated_at, reviewer, notes FROM controlbans_appeals " + whereClause + " ORDER BY submitted_at ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                List<AppealRecord> appeals = new ArrayList<>();
                while (rs.next()) {
                    appeals.add(new AppealRecord(
                            rs.getString("punishment_id"),
                            rs.getString("target_uuid"),
                            rs.getString("status"),
                            Instant.ofEpochMilli(rs.getLong("submitted_at")),
                            Instant.ofEpochMilli(rs.getLong("updated_at")),
                            rs.getString("reviewer"),
                            rs.getString("notes")
                    ));
                }
                return appeals;
            }
        }
    }

    public record AppealRecord(String punishmentId,
                               String targetUuid,
                               String status,
                               Instant submittedAt,
                               Instant updatedAt,
                               String reviewer,
                               String notes) { }
}
