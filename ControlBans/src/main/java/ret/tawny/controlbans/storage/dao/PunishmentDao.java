package ret.tawny.controlbans.storage.dao;

import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public class PunishmentDao {

    public void insertBan(Connection connection, Punishment punishment) throws SQLException {
        String sql = """
            INSERT INTO litebans_bans
            (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getTargetIp());
            stmt.setString(4, punishment.getReason());
            stmt.setString(5, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(6, punishment.getStaffName());
            stmt.setLong(7, punishment.getCreatedTime());
            stmt.setLong(8, punishment.getExpiryTime());
            stmt.setString(9, punishment.getServerOrigin());
            stmt.setBoolean(10, punishment.isSilent());
            stmt.setBoolean(11, punishment.isIpBan());
            stmt.setBoolean(12, punishment.isActive());
            stmt.executeUpdate();
        }
    }

    public void insertMute(Connection connection, Punishment punishment) throws SQLException {
        String sql = """
            INSERT INTO litebans_mutes
            (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getTargetIp());
            stmt.setString(4, punishment.getReason());
            stmt.setString(5, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(6, punishment.getStaffName());
            stmt.setLong(7, punishment.getCreatedTime());
            stmt.setLong(8, punishment.getExpiryTime());
            stmt.setString(9, punishment.getServerOrigin());
            stmt.setBoolean(10, punishment.isSilent());
            stmt.setBoolean(11, punishment.isIpBan());
            stmt.setBoolean(12, punishment.isActive());
            stmt.executeUpdate();
        }
    }

    public void insertWarning(Connection connection, Punishment punishment) throws SQLException {
        String sql = """
            INSERT INTO litebans_warnings
            (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active, warned)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getTargetIp());
            stmt.setString(4, punishment.getReason());
            stmt.setString(5, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(6, punishment.getStaffName());
            stmt.setLong(7, punishment.getCreatedTime());
            stmt.setLong(8, -1);
            stmt.setString(9, punishment.getServerOrigin());
            stmt.setBoolean(10, punishment.isSilent());
            stmt.setBoolean(11, false);
            stmt.setBoolean(12, punishment.isActive());
            stmt.setBoolean(13, true);
            stmt.executeUpdate();
        }
    }

    public void insertKick(Connection connection, Punishment punishment) throws SQLException {
        String sql = """
            INSERT INTO litebans_kicks
            (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getTargetIp());
            stmt.setString(4, punishment.getReason());
            stmt.setString(5, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(6, punishment.getStaffName());
            stmt.setLong(7, punishment.getCreatedTime());
            stmt.setLong(8, -1);
            stmt.setString(9, punishment.getServerOrigin());
            stmt.setBoolean(10, punishment.isSilent());
            stmt.setBoolean(11, false);
            stmt.setBoolean(12, false);
            stmt.executeUpdate();
        }
    }

    public Optional<Punishment> getActiveBan(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM litebans_bans WHERE uuid = ? AND active = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.BAN));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveMute(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM litebans_mutes WHERE uuid = ? AND active = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.MUTE));
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpBan(Connection connection, String ip) throws SQLException {
        String sql = "SELECT * FROM litebans_bans WHERE ip = ? AND active = TRUE AND ipban = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.IPBAN));
                }
            }
        }
        return Optional.empty();
    }

    public List<Punishment> getPunishmentHistory(Connection connection, UUID uuid, int limit) throws SQLException {
        String sql = """
            (SELECT *, 'BAN' as type FROM litebans_bans WHERE uuid = ?)
            UNION ALL
            (SELECT *, 'MUTE' as type FROM litebans_mutes WHERE uuid = ?)
            UNION ALL
            (SELECT *, 'WARN' as type FROM litebans_warnings WHERE uuid = ?)
            UNION ALL
            (SELECT *, 'KICK' as type FROM litebans_kicks WHERE uuid = ?)
            ORDER BY time DESC LIMIT ?
            """;
        List<Punishment> history = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, uuid.toString());
            stmt.setString(3, uuid.toString());
            stmt.setString(4, uuid.toString());
            stmt.setInt(5, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                    history.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return history;
    }

    public Optional<Punishment> getPunishmentById(Connection connection, String punishmentId) throws SQLException {
        String sql = """
            (SELECT *, 'BAN' as type FROM litebans_bans WHERE punishment_id = ?)
            UNION ALL
            (SELECT *, 'MUTE' as type FROM litebans_mutes WHERE punishment_id = ?)
            UNION ALL
            (SELECT *, 'WARN' as type FROM litebans_warnings WHERE punishment_id = ?)
            UNION ALL
            (SELECT *, 'KICK' as type FROM litebans_kicks WHERE punishment_id = ?)
            LIMIT 1
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishmentId);
            stmt.setString(2, punishmentId);
            stmt.setString(3, punishmentId);
            stmt.setString(4, punishmentId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                    return Optional.of(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return Optional.empty();
    }


    public List<Punishment> getRecentPunishments(Connection connection, int limit) throws SQLException {
        String sql = """
            (SELECT *, 'BAN' as type FROM litebans_bans)
            UNION ALL
            (SELECT *, 'MUTE' as type FROM litebans_mutes)
            UNION ALL
            (SELECT *, 'WARN' as type FROM litebans_warnings)
            UNION ALL
            (SELECT *, 'KICK' as type FROM litebans_kicks)
            ORDER BY time DESC LIMIT ?
            """;
        List<Punishment> punishments = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    PunishmentType type = PunishmentType.valueOf(rs.getString("type"));
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return punishments;
    }

    public void removeBan(Connection connection, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        String sql = "UPDATE litebans_bans SET active = FALSE, removed_by_uuid = ?, removed_by_name = ?, removed_by_date = ? WHERE uuid = ? AND active = TRUE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, removedBy != null ? removedBy.toString() : null);
            stmt.setString(2, removedByName);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public void removeMute(Connection connection, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        String sql = "UPDATE litebans_mutes SET active = FALSE, removed_by_uuid = ?, removed_by_name = ?, removed_by_date = ? WHERE uuid = ? AND active = TRUE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, removedBy != null ? removedBy.toString() : null);
            stmt.setString(2, removedByName);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public String getLastIpForUuid(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT ip FROM litebans_history WHERE uuid = ? AND ip IS NOT NULL ORDER BY date DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("ip");
                }
            }
        }
        return null;
    }

    private Punishment createPunishmentFromResultSet(ResultSet rs, PunishmentType type) throws SQLException {
        String staffUuidStr = rs.getString("banned_by_uuid");
        return Punishment.builder()
                .id(rs.getInt("id"))
                .punishmentId(rs.getString("punishment_id"))
                .type(type)
                .targetUuid(UUID.fromString(rs.getString("uuid")))
                .targetIp(rs.getString("ip"))
                .reason(rs.getString("reason"))
                .staffUuid(staffUuidStr != null ? UUID.fromString(staffUuidStr) : null)
                .staffName(rs.getString("banned_by_name"))
                .createdTime(rs.getLong("time"))
                .expiryTime(rs.getLong("until"))
                .serverOrigin(rs.getString("server_origin"))
                .silent(rs.getBoolean("silent"))
                .ipBan(rs.getBoolean("ipban"))
                .active(rs.getBoolean("active"))
                .build();
    }
}