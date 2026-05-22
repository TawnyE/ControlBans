package ret.tawny.controlbans.storage.dao;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class PunishmentDao {

    private static final java.util.logging.Logger LOGGER = java.util.logging.Logger.getLogger("ControlBans-DAO");

    public void insertBan(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_bans (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.executeUpdate();
        }
    }

    public void insertMute(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_mutes (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.executeUpdate();
        }
    }

    public void insertWarning(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_warnings (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active, warned) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.setBoolean(13, true);
            stmt.executeUpdate();
        }
    }

    public void insertKick(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_kicks (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, server_origin, silent) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getTargetIp());
            stmt.setString(4, punishment.getReason());
            stmt.setString(5, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(6, punishment.getStaffName());
            stmt.setLong(7, punishment.getCreatedTime());
            stmt.setString(8, punishment.getServerOrigin());
            stmt.setBoolean(9, punishment.isSilent());
            stmt.executeUpdate();
        }
    }

    public void insertVoiceMute(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), null);
        String sql = "INSERT INTO controlbans_voicemutes (punishment_id, uuid, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, active) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishment.getPunishmentId());
            stmt.setString(2, punishment.getTargetUuid().toString());
            stmt.setString(3, punishment.getReason());
            stmt.setString(4, punishment.getStaffUuid() != null ? punishment.getStaffUuid().toString() : null);
            stmt.setString(5, punishment.getStaffName());
            stmt.setLong(6, punishment.getCreatedTime());
            stmt.setLong(7, punishment.getExpiryTime());
            stmt.setString(8, punishment.getServerOrigin());
            stmt.setBoolean(9, punishment.isSilent());
            stmt.setBoolean(10, punishment.isActive());
            stmt.executeUpdate();
        }
    }

    public void recordHistory(Connection connection, UUID uuid, String name, String ip) {
        if (uuid == null || name == null || name.equalsIgnoreCase("unknown")) return;

        String targetIp = ip != null ? ip : "0.0.0.0";
        String uuidStr = uuid.toString();
        long now = System.currentTimeMillis();

        String updateSql = "UPDATE controlbans_history SET date = ?, name = ? WHERE uuid = ? AND ip = ?";
        try (PreparedStatement update = connection.prepareStatement(updateSql)) {
            update.setLong(1, now);
            update.setString(2, name);
            update.setString(3, uuidStr);
            update.setString(4, targetIp);
            int rows = update.executeUpdate();

            if (rows == 0) {
                String insertSql = "INSERT INTO controlbans_history (date, name, uuid, ip) VALUES (?, ?, ?, ?)";
                try (PreparedStatement insert = connection.prepareStatement(insertSql)) {
                    insert.setLong(1, now);
                    insert.setString(2, name);
                    insert.setString(3, uuidStr);
                    insert.setString(4, targetIp);
                    insert.executeUpdate();
                }
            }
        } catch (SQLException ignored) {
        }
    }

    public List<String> getNamesStartingWith(Connection connection, String prefix) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "SELECT DISTINCT name FROM controlbans_history WHERE name LIKE ? ORDER BY date DESC LIMIT 10";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, prefix + "%");
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    names.add(rs.getString("name"));
                }
            }
        }
        return names;
    }

    private void setPunishmentParams(PreparedStatement stmt, Punishment p) throws SQLException {
        stmt.setString(1, p.getPunishmentId());
        stmt.setString(2, p.getTargetUuid().toString());
        stmt.setString(3, p.getTargetIp());
        stmt.setString(4, p.getReason());
        stmt.setString(5, p.getStaffUuid() != null ? p.getStaffUuid().toString() : null);
        stmt.setString(6, p.getStaffName());
        stmt.setLong(7, p.getCreatedTime());
        stmt.setLong(8, p.getExpiryTime());
        stmt.setString(9, p.getServerOrigin());
        stmt.setBoolean(10, p.isSilent());
        stmt.setBoolean(11, p.isIpBan());
        stmt.setBoolean(12, p.isActive());
    }

    public List<Punishment> getRecentPunishments(Connection connection, int limit) throws SQLException {
        List<Punishment> punishments = new ArrayList<>();
        String commonCols = "t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, t.ipban, t.active";

        String sql = 
            "SELECT " + commonCols + ", h.name as target_name, 'BAN' as type_ref FROM controlbans_bans t LEFT JOIN controlbans_history h ON t.uuid = h.uuid " +
            "UNION ALL " +
            "SELECT " + commonCols + ", h.name as target_name, 'MUTE' as type_ref FROM controlbans_mutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid " +
            "UNION ALL " +
            "SELECT " + commonCols + ", h.name as target_name, 'WARN' as type_ref FROM controlbans_warnings t LEFT JOIN controlbans_history h ON t.uuid = h.uuid " +
            "UNION ALL " +
            "SELECT t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, null, null, 0, t.time, -1, t.server_origin, t.silent, false, false, h.name as target_name, 'KICK' as type_ref FROM controlbans_kicks t LEFT JOIN controlbans_history h ON t.uuid = h.uuid " +
            "UNION ALL " +
            "SELECT t.id, t.punishment_id, t.uuid, null, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, false, t.active, h.name as target_name, 'VOICEMUTE' as type_ref FROM controlbans_voicemutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid " +
            "ORDER BY time DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = switch (typeStr) {
                        case "BAN" -> PunishmentType.BAN;
                        case "MUTE" -> PunishmentType.MUTE;
                        default -> PunishmentType.valueOf(typeStr);
                    };
                    if (typeStr.equals("KICK") || typeStr.equals("WARN") || typeStr.equals("VOICEMUTE")) {
                        punishments.add(createPunishmentFromResultSet(rs, type));
                    } else {
                        punishments.add(parsePunishment(rs, type));
                    }
                }
            }
        }
        return punishments;
    }

    public List<Punishment> getPunishmentHistory(Connection connection, UUID uuid, int limit) throws SQLException {
        List<Punishment> punishments = new ArrayList<>();
        String uuidStr = uuid.toString();
        String commonCols = "t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, t.ipban, t.active";

        String sql = 
            "SELECT " + commonCols + ", h.name as target_name, 'BAN' as type_ref FROM controlbans_bans t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.uuid = ? " +
            "UNION ALL " +
            "SELECT " + commonCols + ", h.name as target_name, 'MUTE' as type_ref FROM controlbans_mutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.uuid = ? " +
            "UNION ALL " +
            "SELECT " + commonCols + ", h.name as target_name, 'WARN' as type_ref FROM controlbans_warnings t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.uuid = ? " +
            "UNION ALL " +
            "SELECT t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, null, null, 0, t.time, -1, t.server_origin, t.silent, false, false, h.name as target_name, 'KICK' as type_ref FROM controlbans_kicks t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.uuid = ? " +
            "UNION ALL " +
            "SELECT t.id, t.punishment_id, t.uuid, null, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, false, t.active, h.name as target_name, 'VOICEMUTE' as type_ref FROM controlbans_voicemutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.uuid = ? " +
            "ORDER BY time DESC LIMIT ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuidStr);
            stmt.setString(2, uuidStr);
            stmt.setString(3, uuidStr);
            stmt.setString(4, uuidStr);
            stmt.setString(5, uuidStr);
            stmt.setInt(6, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = switch (typeStr) {
                        case "BAN" -> PunishmentType.BAN;
                        case "MUTE" -> PunishmentType.MUTE;
                        default -> PunishmentType.valueOf(typeStr);
                    };
                    if (typeStr.equals("KICK") || typeStr.equals("WARN") || typeStr.equals("VOICEMUTE")) {
                        punishments.add(createPunishmentFromResultSet(rs, type));
                    } else {
                        punishments.add(parsePunishment(rs, type));
                    }
                }
            }
        }
        return punishments;
    }

    public Optional<Punishment> getPunishmentById(Connection connection, String punishmentId) throws SQLException {
        String commonCols = "t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, t.ipban, t.active";

        String sql =
                "SELECT " + commonCols + ", h.name as target_name, 'BAN' as type_ref FROM controlbans_bans t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.punishment_id = ? " +
                        "UNION ALL " +
                        "SELECT " + commonCols + ", h.name as target_name, 'MUTE' as type_ref FROM controlbans_mutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.punishment_id = ? " +
                        "UNION ALL " +
                        "SELECT " + commonCols + ", h.name as target_name, 'WARN' as type_ref FROM controlbans_warnings t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.punishment_id = ? " +
                        "UNION ALL " +
                        "SELECT t.id, t.punishment_id, t.uuid, t.ip, t.reason, t.banned_by_uuid, t.banned_by_name, null, null, 0, t.time, -1, t.server_origin, t.silent, false, false, h.name as target_name, 'KICK' as type_ref FROM controlbans_kicks t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.punishment_id = ? " +
                        "UNION ALL " +
                        "SELECT t.id, t.punishment_id, t.uuid, null, t.reason, t.banned_by_uuid, t.banned_by_name, t.removed_by_uuid, t.removed_by_name, t.removed_by_date, t.time, t.until, t.server_origin, t.silent, false, t.active, h.name as target_name, 'VOICEMUTE' as type_ref FROM controlbans_voicemutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid WHERE t.punishment_id = ?";

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishmentId);
            stmt.setString(2, punishmentId);
            stmt.setString(3, punishmentId);
            stmt.setString(4, punishmentId);
            stmt.setString(5, punishmentId);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = switch (typeStr) {
                        case "BAN" -> {
                            long until = rs.getLong("until");
                            boolean ipban = rs.getBoolean("ipban");
                            if (ipban) yield (until != -1 && until != 0) ? PunishmentType.TEMPIPBAN : PunishmentType.IPBAN;
                            yield (until != -1 && until != 0) ? PunishmentType.TEMPBAN : PunishmentType.BAN;
                        }
                        case "MUTE" -> {
                            long until = rs.getLong("until");
                            boolean ipban = rs.getBoolean("ipban");
                            if (ipban) yield (until != -1 && until != 0) ? PunishmentType.TEMPIPMUTE : PunishmentType.IPMUTE;
                            yield (until != -1 && until != 0) ? PunishmentType.TEMPMUTE : PunishmentType.MUTE;
                        }
                        default -> PunishmentType.valueOf(typeStr);
                    };
                    return Optional.of(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return Optional.empty();
    }

    private Punishment parsePunishment(ResultSet rs, PunishmentType tableType) throws SQLException {
        long until = hasColumn(rs, "until") ? rs.getLong("until") : 0;
        boolean ipban = hasColumn(rs, "ipban") && rs.getBoolean("ipban");
        boolean isTemp = until != -1 && until != 0;

        PunishmentType type = tableType;
        if (tableType == PunishmentType.BAN || tableType == PunishmentType.TEMPBAN || tableType == PunishmentType.IPBAN || tableType == PunishmentType.TEMPIPBAN) {
            if (ipban) type = isTemp ? PunishmentType.TEMPIPBAN : PunishmentType.IPBAN;
            else type = isTemp ? PunishmentType.TEMPBAN : PunishmentType.BAN;
        } else if (tableType == PunishmentType.MUTE || tableType == PunishmentType.TEMPMUTE || tableType == PunishmentType.IPMUTE || tableType == PunishmentType.TEMPIPMUTE) {
            if (ipban) type = isTemp ? PunishmentType.TEMPIPMUTE : PunishmentType.IPMUTE;
            else type = isTemp ? PunishmentType.TEMPMUTE : PunishmentType.MUTE;
        }

        return createPunishmentFromResultSet(rs, type);
    }

    public Optional<Punishment> getActiveBan(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_bans WHERE uuid = ? AND active = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(parsePunishment(rs, PunishmentType.BAN));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveMute(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_mutes WHERE uuid = ? AND active = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(parsePunishment(rs, PunishmentType.MUTE));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveVoiceMute(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_voicemutes WHERE uuid = ? AND active = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.VOICEMUTE));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpBan(Connection connection, String ip) throws SQLException {
        String sql = "SELECT * FROM controlbans_bans WHERE ip = ? AND active = TRUE AND ipban = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.IPBAN));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpMute(Connection connection, String ip) throws SQLException {
        String sql = "SELECT * FROM controlbans_mutes WHERE ip = ? AND active = TRUE AND ipban = TRUE AND (until = -1 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(parsePunishment(rs, PunishmentType.IPMUTE));
            }
        }
        return Optional.empty();
    }

    public void removeBan(Connection connection, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        updateActiveState(connection, "controlbans_bans", uuid, removedBy, removedByName);
    }
    public void removeMute(Connection connection, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        updateActiveState(connection, "controlbans_mutes", uuid, removedBy, removedByName);
    }
    public void removeVoiceMute(Connection connection, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        updateActiveState(connection, "controlbans_voicemutes", uuid, removedBy, removedByName);
    }

    public void removeIpBan(Connection connection, String ip, UUID removedBy, String removedByName) throws SQLException {
        String sql = "UPDATE controlbans_bans SET active = FALSE, removed_by_uuid = ?, removed_by_name = ?, removed_by_date = ? WHERE ip = ? AND active = TRUE AND ipban = TRUE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, removedBy != null ? removedBy.toString() : null);
            stmt.setString(2, removedByName);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, ip);
            stmt.executeUpdate();
        }
    }

    public void removeIpMute(Connection connection, String ip, UUID removedBy, String removedByName) throws SQLException {
        String sql = "UPDATE controlbans_mutes SET active = FALSE, removed_by_uuid = ?, removed_by_name = ?, removed_by_date = ? WHERE ip = ? AND active = TRUE AND ipban = TRUE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, removedBy != null ? removedBy.toString() : null);
            stmt.setString(2, removedByName);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, ip);
            stmt.executeUpdate();
        }
    }

    private void updateActiveState(Connection connection, String table, UUID uuid, UUID removedBy, String removedByName) throws SQLException {
        String sql = "UPDATE " + table + " SET active = FALSE, removed_by_uuid = ?, removed_by_name = ?, removed_by_date = ? WHERE uuid = ? AND active = TRUE";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, removedBy != null ? removedBy.toString() : null);
            stmt.setString(2, removedByName);
            stmt.setLong(3, System.currentTimeMillis());
            stmt.setString(4, uuid.toString());
            stmt.executeUpdate();
        }
    }

    public String getLastIpForUuid(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT ip FROM controlbans_history WHERE uuid = ? AND ip IS NOT NULL ORDER BY date DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("ip");
            }
        }
        return null;
    }

    public String getLastKnownName(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT name FROM controlbans_history WHERE uuid = ? ORDER BY date DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getString("name");
            }
        }
        return null;
    }

    public UUID getUuidByName(Connection connection, String name) throws SQLException {
        String sql = "SELECT uuid FROM controlbans_history WHERE name = ? ORDER BY date DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return UUID.fromString(rs.getString("uuid"));
            }
        }
        return null;
    }

    public void clearAllData(Connection connection) throws SQLException {
        try (java.sql.Statement stmt = connection.createStatement()) {
            stmt.addBatch("DELETE FROM controlbans_bans");
            stmt.addBatch("DELETE FROM controlbans_mutes");
            stmt.addBatch("DELETE FROM controlbans_warnings");
            stmt.addBatch("DELETE FROM controlbans_kicks");
            stmt.addBatch("DELETE FROM controlbans_history");
            stmt.addBatch("DELETE FROM controlbans_voicemutes");
            stmt.addBatch("DELETE FROM controlbans_appeals");
            stmt.executeBatch();
        }
    }

    public void clearPlayerData(Connection connection, UUID uuid) throws SQLException {
        String uuidStr = uuid.toString();
        String[] tables = { "controlbans_bans", "controlbans_mutes", "controlbans_warnings", "controlbans_kicks", "controlbans_voicemutes", "controlbans_history" };
        for (String table : tables) {
            try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM " + table + " WHERE uuid = ?")) {
                stmt.setString(1, uuidStr);
                stmt.executeUpdate();
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM controlbans_appeals WHERE target_uuid = ?")) {
            stmt.setString(1, uuidStr);
            stmt.executeUpdate();
        }
    }

    public Set<String> getIpsForUuid(Connection connection, UUID uuid) throws SQLException {
        Set<String> ips = new HashSet<>();
        String sql = "SELECT DISTINCT ip FROM controlbans_history WHERE uuid = ? AND ip IS NOT NULL";
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

    public Set<UUID> getUuidsOnIp(Connection connection, String ip) throws SQLException {
        Set<UUID> uuids = new HashSet<>();
        String sql = "SELECT DISTINCT uuid FROM controlbans_history WHERE ip = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    uuids.add(UUID.fromString(rs.getString("uuid")));
                }
            }
        }
        return uuids;
    }

    public int getUserCountOnIp(Connection connection, String ip) throws SQLException {
        String sql = "SELECT COUNT(DISTINCT uuid) FROM controlbans_history WHERE ip = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    public void addAppeal(Connection connection, String punishmentId, UUID uuid, String message, long timestamp) throws SQLException {
        String sql = "INSERT INTO controlbans_appeals (target_uuid, punishment_id, message, created_at) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setString(2, punishmentId);
            stmt.setString(3, message);
            stmt.setLong(4, timestamp);
            stmt.executeUpdate();
        }
    }

    public long getLastAppealTime(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT created_at FROM controlbans_appeals WHERE target_uuid = ? ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getLong("created_at");
            }
        }
        return 0L;
    }

    public int getAppealCount(Connection connection, UUID uuid, long sinceTimestamp) throws SQLException {
        String sql = "SELECT COUNT(*) FROM controlbans_appeals WHERE target_uuid = ? AND created_at >= ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, sinceTimestamp);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
        }
        return 0;
    }

    private Punishment createPunishmentFromResultSet(ResultSet rs, PunishmentType type) throws SQLException {
        String staffUuidStr = rs.getString("banned_by_uuid");
        String uuidStr = rs.getString("uuid");
        UUID targetUuid = uuidStr != null ? UUID.fromString(uuidStr) : UUID.nameUUIDFromBytes("unknown".getBytes());

        Punishment.Builder builder = Punishment.builder()
                .id(rs.getInt("id"))
                .punishmentId(rs.getString("punishment_id"))
                .type(type)
                .targetUuid(targetUuid)
                .reason(rs.getString("reason"))
                .staffUuid(staffUuidStr != null ? UUID.fromString(staffUuidStr) : null)
                .staffName(rs.getString("banned_by_name"))
                .createdTime(rs.getLong("time"))
                .serverOrigin(rs.getString("server_origin"))
                .silent(rs.getBoolean("silent"));

        if (hasColumn(rs, "target_name")) builder.targetName(rs.getString("target_name") != null ? rs.getString("target_name") : "Unknown");
        else builder.targetName("Unknown");

        if (hasColumn(rs, "ip")) builder.targetIp(rs.getString("ip"));
        if (hasColumn(rs, "active")) builder.active(rs.getBoolean("active"));
        if (hasColumn(rs, "ipban")) builder.ipBan(rs.getBoolean("ipban"));
        long until = hasColumn(rs, "until") ? rs.getLong("until") : -1;
        builder.expiryTime(until);
        return builder.build();
    }

    private boolean hasColumn(ResultSet rs, String columnName) {
        try { rs.findColumn(columnName); return true; } catch (SQLException e) { return false; }
    }

    public List<Punishment> getAllPunishments(Connection connection) throws SQLException {
        List<Punishment> punishments = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_bans t LEFT JOIN controlbans_history h ON t.uuid = h.uuid ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long until = rs.getLong("until");
                    PunishmentType type = (until != -1 && until != 0) ? PunishmentType.TEMPBAN : PunishmentType.BAN;
                    if (rs.getBoolean("ipban")) type = PunishmentType.IPBAN;
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_mutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long until = rs.getLong("until");
                    PunishmentType type = (until != -1 && until != 0) ? PunishmentType.TEMPMUTE : PunishmentType.MUTE;
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_warnings t LEFT JOIN controlbans_history h ON t.uuid = h.uuid ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) punishments.add(createPunishmentFromResultSet(rs, PunishmentType.WARN));
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_kicks t LEFT JOIN controlbans_history h ON t.uuid = h.uuid ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) punishments.add(createPunishmentFromResultSet(rs, PunishmentType.KICK));
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_voicemutes t LEFT JOIN controlbans_history h ON t.uuid = h.uuid ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) punishments.add(createPunishmentFromResultSet(rs, PunishmentType.VOICEMUTE));
            }
        }
        return punishments;
    }

    public void insertReport(Connection connection, String id, UUID reporterUuid, String reporterName, String targetName, String reason, long timestamp, String status) throws SQLException {
        String sql = "INSERT INTO controlbans_reports (id, reporter_uuid, reporter_name, target_name, reason, time, status) VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, reporterUuid.toString());
            stmt.setString(3, reporterName);
            stmt.setString(4, targetName);
            stmt.setString(5, reason);
            stmt.setLong(6, timestamp);
            stmt.setString(7, status);
            stmt.executeUpdate();
        }
    }

    public List<ret.tawny.controlbans.services.ReportService.Report> getReports(Connection connection) throws SQLException {
        List<ret.tawny.controlbans.services.ReportService.Report> reports = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_reports ORDER BY time DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    reports.add(new ret.tawny.controlbans.services.ReportService.Report(
                            rs.getString("id"),
                            UUID.fromString(rs.getString("reporter_uuid")),
                            rs.getString("reporter_name"),
                            rs.getString("target_name"),
                            rs.getString("reason"),
                            rs.getLong("time"),
                            rs.getString("status")
                    ));
                }
            }
        }
        return reports;
    }

    public List<ret.tawny.controlbans.services.ReportService.Report> getReportsByReporter(Connection connection, UUID reporterUuid) throws SQLException {
        List<ret.tawny.controlbans.services.ReportService.Report> reports = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_reports WHERE reporter_uuid = ? ORDER BY time DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, reporterUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    reports.add(new ret.tawny.controlbans.services.ReportService.Report(
                            rs.getString("id"),
                            UUID.fromString(rs.getString("reporter_uuid")),
                            rs.getString("reporter_name"),
                            rs.getString("target_name"),
                            rs.getString("reason"),
                            rs.getLong("time"),
                            rs.getString("status")
                    ));
                }
            }
        }
        return reports;
    }

    public boolean updateReportStatus(Connection connection, String id, String status) throws SQLException {
        String sql = "UPDATE controlbans_reports SET status = ? WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setString(2, id);
            return stmt.executeUpdate() > 0;
        }
    }

    public void addNote(Connection connection, UUID targetUuid, String staffName, String noteText, long timestamp) throws SQLException {
        String sql = "INSERT INTO controlbans_notes (uuid, staff_name, note_text, time) VALUES (?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            stmt.setString(2, staffName);
            stmt.setString(3, noteText);
            stmt.setLong(4, timestamp);
            stmt.executeUpdate();
        }
    }

    public boolean removeNote(Connection connection, UUID targetUuid, int index) throws SQLException {
        String findSql = "SELECT id FROM controlbans_notes WHERE uuid = ? ORDER BY time ASC LIMIT 1 OFFSET ?";
        try (PreparedStatement stmt = connection.prepareStatement(findSql)) {
            stmt.setString(1, targetUuid.toString());
            stmt.setInt(2, index - 1);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    int id = rs.getInt(1);
                    String deleteSql = "DELETE FROM controlbans_notes WHERE id = ?";
                    try (PreparedStatement delStmt = connection.prepareStatement(deleteSql)) {
                        delStmt.setInt(1, id);
                        return delStmt.executeUpdate() > 0;
                    }
                }
            }
        }
        return false;
    }

    public List<ret.tawny.controlbans.services.NoteService.PlayerNote> getNotes(Connection connection, UUID targetUuid) throws SQLException {
        List<ret.tawny.controlbans.services.NoteService.PlayerNote> notes = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_notes WHERE uuid = ? ORDER BY time ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    notes.add(new ret.tawny.controlbans.services.NoteService.PlayerNote(
                            rs.getString("staff_name"),
                            rs.getString("note_text"),
                            rs.getLong("time")
                    ));
                }
            }
        }
        return notes;
    }
}