package ret.tawny.controlbans.services;

import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.DatabaseManager;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class AppealService {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;

    public AppealService(DatabaseManager databaseManager, ConfigManager configManager) {
        this.databaseManager = databaseManager;
        this.configManager = configManager;
    }

    public CompletableFuture<AppealResult> submitAppeal(Punishment punishment, String rawMessage) {
        if (punishment == null || !punishment.getType().isMute()) {
            return CompletableFuture.completedFuture(AppealResult.notMuted());
        }

        if (!configManager.isAppealsEnabled()) {
            return CompletableFuture.completedFuture(AppealResult.disabled());
        }

        String message = sanitizeMessage(rawMessage, punishment.getReason());
        String targetUuid = punishment.getTargetUuid().toString();
        long now = System.currentTimeMillis();
        Duration cooldown = configManager.getAppealCooldown();
        Duration window = configManager.getAppealWindowDuration();
        int maxSubmissions = configManager.getAppealMaxSubmissions();

        return databaseManager.executeQueryAsync(connection ->
                handleSubmission(connection, punishment.getPunishmentId(), targetUuid, message, now, cooldown, window, maxSubmissions)
        );
    }

    private AppealResult handleSubmission(Connection connection,
                                          String punishmentId,
                                          String targetUuid,
                                          String message,
                                          long now,
                                          Duration cooldown,
                                          Duration window,
                                          int maxSubmissions) throws SQLException {
        boolean previousAutoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            long lastCreated = getLastAppealTimestamp(connection, targetUuid);
            long cooldownMillis = cooldown.toMillis();
            if (cooldownMillis > 0 && lastCreated > 0 && now - lastCreated < cooldownMillis) {
                connection.rollback();
                return AppealResult.onCooldown(lastCreated + cooldownMillis);
            }

            if (maxSubmissions > 0 && !window.isZero()) {
                long windowMillis = window.toMillis();
                long windowStart = now - windowMillis;
                int count = countAppealsInWindow(connection, targetUuid, windowStart);
                if (count >= maxSubmissions) {
                    long earliest = earliestAppealInWindow(connection, targetUuid, windowStart);
                    connection.rollback();
                    long nextAllowed = earliest <= 0 ? now + cooldownMillis : earliest + windowMillis;
                    return AppealResult.limitReached(nextAllowed);
                }
            }

            insertAppeal(connection, punishmentId, targetUuid, message, now);
            connection.commit();
            return AppealResult.accepted();
        } catch (SQLException ex) {
            connection.rollback();
            throw ex;
        } finally {
            connection.setAutoCommit(previousAutoCommit);
        }
    }

    private long getLastAppealTimestamp(Connection connection, String targetUuid) throws SQLException {
        String sql = "SELECT created_at FROM controlbans_appeals WHERE target_uuid = ? ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("created_at");
                }
            }
        }
        return 0L;
    }

    private int countAppealsInWindow(Connection connection, String targetUuid, long windowStart) throws SQLException {
        String sql = "SELECT COUNT(*) FROM controlbans_appeals WHERE target_uuid = ? AND created_at >= ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid);
            stmt.setLong(2, windowStart);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }

    private long earliestAppealInWindow(Connection connection, String targetUuid, long windowStart) throws SQLException {
        String sql = "SELECT MIN(created_at) FROM controlbans_appeals WHERE target_uuid = ? AND created_at >= ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid);
            stmt.setLong(2, windowStart);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        }
        return 0L;
    }

    private void insertAppeal(Connection connection,
                              String punishmentId,
                              String targetUuid,
                              String message,
                              long now) throws SQLException {
        String sql = "INSERT INTO controlbans_appeals (target_uuid, punishment_id, message, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid);
            stmt.setString(2, punishmentId);
            stmt.setString(3, message);
            stmt.setLong(4, now);
            stmt.executeUpdate();
        }
    }

    private String sanitizeMessage(String rawMessage, String fallbackReason) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            message = fallbackReason == null ? "No reason provided" : fallbackReason;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return message;
    }

    public enum AppealStatus {
        ACCEPTED,
        NOT_MUTED,
        DISABLED,
        ON_COOLDOWN,
        LIMIT_REACHED
    }

    public record AppealResult(AppealStatus status, long nextAllowedAt) {
        private static final AppealResult ACCEPTED = new AppealResult(AppealStatus.ACCEPTED, 0L);
        private static final AppealResult NOT_MUTED = new AppealResult(AppealStatus.NOT_MUTED, 0L);
        private static final AppealResult DISABLED = new AppealResult(AppealStatus.DISABLED, 0L);

        public static AppealResult accepted() { return ACCEPTED; }
        public static AppealResult notMuted() { return NOT_MUTED; }
        public static AppealResult disabled() { return DISABLED; }
        public static AppealResult onCooldown(long nextAllowedAt) { return new AppealResult(AppealStatus.ON_COOLDOWN, nextAllowedAt); }
        public static AppealResult limitReached(long nextAllowedAt) { return new AppealResult(AppealStatus.LIMIT_REACHED, nextAllowedAt); }
    }
}
