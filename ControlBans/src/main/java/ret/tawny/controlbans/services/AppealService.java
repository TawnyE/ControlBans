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
import java.util.Objects;
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

    public CompletableFuture<AppealSubmissionResult> submitAppeal(Punishment punishment, String message) {
        if (punishment == null || !punishment.getType().isMute()) {
            return CompletableFuture.completedFuture(AppealSubmissionResult.notEligible());
        }

        String targetUuid = punishment.getTargetUuid().toString();
        String sanitizedMessage = sanitizeMessage(message, punishment.getReason());
        String openStatus = configManager.getDefaultAppealStatus();

        return databaseManager.executeQueryAsync(connection ->
                submitAppeal(connection, punishment, targetUuid, sanitizedMessage, openStatus)
        ).thenApply(result -> {
            if (result.status() == AppealSubmissionResult.Status.ACCEPTED && integrationService != null) {
                integrationService.onAppealUpdate(
                        punishment.getPunishmentId(),
                        openStatus,
                        null,
                        sanitizedMessage,
                        targetUuid
                );
            }
            return result;
        });
    }

    public CompletableFuture<List<AppealRecord>> listOpenAppeals() {
        return databaseManager.executeQueryAsync(connection ->
                fetchOpenAppeals(connection, configManager.getDefaultAppealStatus()));
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
                if (reviewer == null) {
                    stmt.setNull(2, java.sql.Types.VARCHAR);
                } else {
                    stmt.setString(2, reviewer);
                }
                stmt.setString(3, notes);
                stmt.setLong(4, System.currentTimeMillis());
                stmt.setString(5, punishmentId);
                stmt.executeUpdate();
            }
            return targetUuid;
        }).thenAccept(targetUuid -> integrationService.onAppealUpdate(punishmentId, status, reviewer, notes, targetUuid));
    }

    private AppealSubmissionResult submitAppeal(Connection connection,
                                               Punishment punishment,
                                               String targetUuid,
                                               String message,
                                               String openStatus) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long now = System.currentTimeMillis();
            long cooldownMillis = Math.max(0L, configManager.getAppealCooldown().toMillis());
            long windowMillis = Math.max(0L, configManager.getAppealWindowDuration().toMillis());
            int maxSubmissions = Math.max(0, configManager.getAppealMaxSubmissions());

            AppealLimit limit = loadAppealLimit(connection, targetUuid);

            if (cooldownMillis > 0 && (now - limit.lastSubmittedAt()) < cooldownMillis) {
                connection.rollback();
                long nextAllowed = limit.lastSubmittedAt() + cooldownMillis;
                int remaining = maxSubmissions > 0 ? Math.max(0, maxSubmissions - limit.submissionCount()) : -1;
                return new AppealSubmissionResult(AppealSubmissionResult.Status.COOLDOWN, nextAllowed, remaining);
            }

            long windowStart = limit.windowStart();
            int windowCount = limit.submissionCount();
            if (!limit.exists()) {
                windowStart = now;
            }
            if (windowMillis > 0 && (now - windowStart) >= windowMillis) {
                windowStart = now;
                windowCount = 0;
            }

            if (maxSubmissions > 0 && windowCount >= maxSubmissions) {
                connection.rollback();
                long nextAllowed = windowStart + windowMillis;
                return new AppealSubmissionResult(AppealSubmissionResult.Status.LIMIT_REACHED, nextAllowed, 0);
            }

            AppealRow existing = findAppealRow(connection, punishment.getPunishmentId());
            if (existing != null && existing.submissionCount() > 0 && openStatus.equalsIgnoreCase(existing.status())) {
                connection.rollback();
                int remaining = maxSubmissions > 0 ? Math.max(0, maxSubmissions - windowCount) : -1;
                return new AppealSubmissionResult(AppealSubmissionResult.Status.ALREADY_OPEN, now, remaining);
            }

            long submittedAt = now;
            int submissionCount = existing != null ? existing.submissionCount() + 1 : 1;
            if (existing != null) {
                try (PreparedStatement update = connection.prepareStatement(
                        "UPDATE controlbans_appeals SET status = ?, submitted_at = ?, updated_at = ?, reviewer = NULL, notes = ?, " +
                                "submission_count = ?, last_submitted_at = ? WHERE punishment_id = ?")) {
                    update.setString(1, openStatus);
                    update.setLong(2, submittedAt);
                    update.setLong(3, now);
                    update.setString(4, message);
                    update.setInt(5, submissionCount);
                    update.setLong(6, now);
                    update.setString(7, punishment.getPunishmentId());
                    update.executeUpdate();
                }
            } else {
                try (PreparedStatement insert = connection.prepareStatement(
                        "INSERT INTO controlbans_appeals (punishment_id, target_uuid, status, submitted_at, updated_at, reviewer, notes, submission_count, last_submitted_at) VALUES (?, ?, ?, ?, ?, NULL, ?, ?, ?)")) {
                    insert.setString(1, punishment.getPunishmentId());
                    insert.setString(2, targetUuid);
                    insert.setString(3, openStatus);
                    insert.setLong(4, submittedAt);
                    insert.setLong(5, now);
                    insert.setString(6, message);
                    insert.setInt(7, submissionCount);
                    insert.setLong(8, now);
                    insert.executeUpdate();
                }
            }

            int updatedCount = windowCount + 1;
            saveAppealLimit(connection, targetUuid, windowStart, updatedCount, now, limit.exists());

            connection.commit();
            int remaining = maxSubmissions > 0 ? Math.max(0, maxSubmissions - updatedCount) : -1;
            return new AppealSubmissionResult(AppealSubmissionResult.Status.ACCEPTED, now, remaining);
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private AppealLimit loadAppealLimit(Connection connection, String targetUuid) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT window_start, submission_count, last_submitted_at FROM controlbans_appeal_limits WHERE target_uuid = ?")) {
            stmt.setString(1, targetUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new AppealLimit(true,
                            rs.getLong("window_start"),
                            rs.getInt("submission_count"),
                            rs.getLong("last_submitted_at"));
                }
            }
        }
        return new AppealLimit(false, 0L, 0, 0L);
    }

    private void saveAppealLimit(Connection connection,
                                 String targetUuid,
                                 long windowStart,
                                 int submissionCount,
                                 long lastSubmittedAt,
                                 boolean exists) throws SQLException {
        if (exists) {
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE controlbans_appeal_limits SET window_start = ?, submission_count = ?, last_submitted_at = ? WHERE target_uuid = ?")) {
                update.setLong(1, windowStart);
                update.setInt(2, submissionCount);
                update.setLong(3, lastSubmittedAt);
                update.setString(4, targetUuid);
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO controlbans_appeal_limits (target_uuid, window_start, submission_count, last_submitted_at) VALUES (?, ?, ?, ?)")) {
                insert.setString(1, targetUuid);
                insert.setLong(2, windowStart);
                insert.setInt(3, submissionCount);
                insert.setLong(4, lastSubmittedAt);
                insert.executeUpdate();
            }
        }
    }

    private AppealRow findAppealRow(Connection connection, String punishmentId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT status, submitted_at, submission_count, last_submitted_at FROM controlbans_appeals WHERE punishment_id = ?")) {
            stmt.setString(1, punishmentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return new AppealRow(
                            rs.getString("status"),
                            rs.getLong("submitted_at"),
                            rs.getInt("submission_count"),
                            rs.getLong("last_submitted_at"));
                }
            }
        }
        return null;
    }

    private List<AppealRecord> fetchOpenAppeals(Connection connection, String status) throws SQLException {
        String sql = "SELECT punishment_id, target_uuid, status, submitted_at, updated_at, reviewer, notes, submission_count, last_submitted_at " +
                "FROM controlbans_appeals WHERE status = ? AND submission_count > 0 ORDER BY submitted_at ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
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
                            rs.getString("notes"),
                            rs.getInt("submission_count"),
                            rs.getLong("last_submitted_at")));
                }
                return appeals;
            }
        }
    }

    private List<AppealRecord> fetchAppeals(Connection connection, String whereClause) throws SQLException {
        String sql = "SELECT punishment_id, target_uuid, status, submitted_at, updated_at, reviewer, notes, submission_count, last_submitted_at " +
                "FROM controlbans_appeals " + whereClause + " ORDER BY submitted_at ASC";
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
                            rs.getString("notes"),
                            rs.getInt("submission_count"),
                            rs.getLong("last_submitted_at")));
                }
                return appeals;
            }
        }
    }

    private String sanitizeMessage(String message, String fallback) {
        String trimmed = message == null ? "" : message.trim();
        if (trimmed.isEmpty()) {
            trimmed = Objects.requireNonNullElse(fallback, "No appeal reason provided");
        }
        if (trimmed.length() > 2000) {
            return trimmed.substring(0, 2000);
        }
        return trimmed;
    }

    public record AppealRecord(String punishmentId,
                               String targetUuid,
                               String status,
                               Instant submittedAt,
                               Instant updatedAt,
                               String reviewer,
                               String notes,
                               int submissionCount,
                               long lastSubmittedAt) { }

    private record AppealLimit(boolean exists, long windowStart, int submissionCount, long lastSubmittedAt) { }

    private record AppealRow(String status, long submittedAt, int submissionCount, long lastSubmittedAt) { }

    public record AppealSubmissionResult(Status status, long nextAllowedAt, int remainingSubmissions) {
        public static AppealSubmissionResult notEligible() {
            return new AppealSubmissionResult(Status.NOT_ELIGIBLE, 0L, 0);
        }

        public enum Status {
            ACCEPTED,
            COOLDOWN,
            LIMIT_REACHED,
            ALREADY_OPEN,
            NOT_ELIGIBLE
        }
    }
}
