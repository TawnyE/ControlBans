package ret.tawny.controlbans.storage.dao;

import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ScheduledPunishmentDao {

    public void insert(Connection connection, ScheduledPunishment schedule) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO controlbans_scheduled_punishments (type, target_uuid, target_name, reason, staff_uuid, staff_name, execution_time, duration_seconds, silent, ipban, category, escalation_level, created_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)", PreparedStatement.RETURN_GENERATED_KEYS)) {
            stmt.setString(1, schedule.getType().name());
            stmt.setString(2, schedule.getTargetUuid().toString());
            stmt.setString(3, schedule.getTargetName());
            stmt.setString(4, schedule.getReason());
            stmt.setString(5, schedule.getStaffUuid() != null ? schedule.getStaffUuid().toString() : null);
            stmt.setString(6, schedule.getStaffName());
            stmt.setLong(7, schedule.getExecutionTime());
            stmt.setLong(8, schedule.getDurationSeconds());
            stmt.setBoolean(9, schedule.isSilent());
            stmt.setBoolean(10, schedule.isIpBan());
            stmt.setString(11, schedule.getCategory());
            stmt.setInt(12, schedule.getEscalationLevel());
            stmt.setLong(13, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    public List<ScheduledPunishment> findDue(Connection connection, long now) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM controlbans_scheduled_punishments WHERE execution_time <= ? ORDER BY execution_time ASC")) {
            stmt.setLong(1, now);
            try (ResultSet rs = stmt.executeQuery()) {
                return collect(rs);
            }
        }
    }

    public List<ScheduledPunishment> findUpcoming(Connection connection, int limit) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT * FROM controlbans_scheduled_punishments WHERE execution_time > ? ORDER BY execution_time ASC LIMIT ?")) {
            stmt.setLong(1, System.currentTimeMillis());
            stmt.setInt(2, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                return collect(rs);
            }
        }
    }

    public void delete(Connection connection, int id) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM controlbans_scheduled_punishments WHERE id = ?")) {
            stmt.setInt(1, id);
            stmt.executeUpdate();
        }
    }

    private List<ScheduledPunishment> collect(ResultSet rs) throws SQLException {
        List<ScheduledPunishment> schedules = new ArrayList<>();
        while (rs.next()) {
            schedules.add(ScheduledPunishment.builder()
                    .id(rs.getInt("id"))
                    .type(PunishmentType.valueOf(rs.getString("type")))
                    .targetUuid(UUID.fromString(rs.getString("target_uuid")))
                    .targetName(rs.getString("target_name"))
                    .reason(rs.getString("reason"))
                    .staffUuid(rs.getString("staff_uuid") != null ? UUID.fromString(rs.getString("staff_uuid")) : null)
                    .staffName(rs.getString("staff_name"))
                    .executionTime(rs.getLong("execution_time"))
                    .durationSeconds(rs.getLong("duration_seconds"))
                    .silent(rs.getBoolean("silent"))
                    .ipBan(rs.getBoolean("ipban"))
                    .category(rs.getString("category"))
                    .escalationLevel(rs.getInt("escalation_level"))
                    .build());
        }
        return schedules;
    }
}
