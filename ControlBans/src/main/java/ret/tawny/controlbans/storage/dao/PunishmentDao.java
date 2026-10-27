package ret.tawny.controlbans.storage.dao;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;

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
        String sql = "INSERT INTO controlbans_bans (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active, template, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.setString(13, punishment.getTemplateKey());
            stmt.setString(14, punishment.getTargetName());
            stmt.executeUpdate();
        }
    }

    public void insertMute(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_mutes (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active, template, name) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.setString(13, punishment.getTemplateKey());
            stmt.setString(14, punishment.getTargetName());
            stmt.executeUpdate();
        }
    }

    public void insertWarning(Connection connection, Punishment punishment) throws SQLException {
        recordHistory(connection, punishment.getTargetUuid(), punishment.getTargetName(), punishment.getTargetIp());
        String sql = "INSERT INTO controlbans_warnings (punishment_id, uuid, ip, reason, banned_by_uuid, banned_by_name, time, until, server_origin, silent, ipban, active, warned, template) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            setPunishmentParams(stmt, punishment);
            stmt.setBoolean(13, true);
            stmt.setString(14, punishment.getTemplateKey());
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
        // Never store placeholder IPs — they poison alt graphs and getLastIp lookups.
        if (ip == null || ip.isBlank() || ip.equals("0.0.0.0")) return;
        // IP-ban rows store name=<ip>; don't pollute the name index with IPs.
        if (name.matches("^([0-9]{1,3}\\.){3}[0-9]{1,3}$")) return;

        String targetIp = ip;
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
                } catch (SQLException inner) {
                    // Race: another thread inserted same uuid+ip between UPDATE and INSERT -> duplicate unique index.
                    // Try one more UPDATE to refresh timestamp, otherwise log at fine.
                    try (PreparedStatement retryUpdate = connection.prepareStatement(updateSql)) {
                        retryUpdate.setLong(1, now);
                        retryUpdate.setString(2, name);
                        retryUpdate.setString(3, uuidStr);
                        retryUpdate.setString(4, targetIp);
                        retryUpdate.executeUpdate();
                    } catch (SQLException retryEx) {
                        LOGGER.log(Level.FINE, "Failed to record history for " + uuidStr + "/" + targetIp, inner);
                    }
                }
            }
        } catch (SQLException e) {
            LOGGER.log(Level.WARNING, "Failed to record history for uuid=" + uuidStr + " name=" + name, e);
        }
    }

    public List<String> getNamesStartingWith(Connection connection, String prefix) throws SQLException {
        List<String> names = new ArrayList<>();
        String sql = "SELECT DISTINCT name FROM controlbans_history WHERE name LIKE ? ESCAPE '\\' ORDER BY date DESC LIMIT 10";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            String escaped = prefix.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
            stmt.setString(1, escaped + "%");
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
        String sql = PunishmentQueryBuilder.buildUnionQuery(null, "time DESC", true);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                while (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = resolveTypeFromString(typeStr);
                    if (typeStr.equals("KICK") || typeStr.equals("WARN")) {
                        punishments.add(createPunishmentFromResultSet(rs, type));
                    } else {
                        punishments.add(parsePunishment(rs, columns, type));
                    }
                }
            }
        }
        return punishments;
    }

    public List<Punishment> getPunishmentHistory(Connection connection, UUID uuid, int limit) throws SQLException {
        List<Punishment> punishments = new ArrayList<>();
        String uuidStr = uuid.toString();
        String sql = PunishmentQueryBuilder.buildUnionQuery("t.uuid = ?", "time DESC", true);

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                stmt.setString(i, uuidStr);
            }
            stmt.setInt(6, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                while (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = resolveTypeFromString(typeStr);
                    if (typeStr.equals("KICK") || typeStr.equals("WARN")) {
                        punishments.add(createPunishmentFromResultSet(rs, type));
                    } else {
                        punishments.add(parsePunishment(rs, columns, type));
                    }
                }
            }
        }
        return punishments;
    }

    public Optional<Punishment> getPunishmentById(Connection connection, String punishmentId) throws SQLException {
        String sql = PunishmentQueryBuilder.buildPunishmentByIdQuery();

        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            for (int i = 1; i <= 5; i++) {
                stmt.setString(i, punishmentId);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    String typeStr = rs.getString("type_ref");
                    PunishmentType type = resolveTypeFromString(typeStr);
                    return Optional.of(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return Optional.empty();
    }

    private Punishment parsePunishment(ResultSet rs, Set<String> columns, PunishmentType tableType) throws SQLException {
        long until = columns.contains("until") ? rs.getLong("until") : 0;
        if (columns.contains("until") && rs.wasNull()) until = -1;
        boolean ipban = columns.contains("ipban") && rs.getBoolean("ipban");
        boolean isTemp = until > 0;

        PunishmentType type = tableType;
        if (tableType == PunishmentType.BAN || tableType == PunishmentType.TEMPBAN || tableType == PunishmentType.IPBAN || tableType == PunishmentType.TEMPIPBAN) {
            if (ipban) type = isTemp ? PunishmentType.TEMPIPBAN : PunishmentType.IPBAN;
            else type = isTemp ? PunishmentType.TEMPBAN : PunishmentType.BAN;
        } else if (tableType == PunishmentType.MUTE || tableType == PunishmentType.TEMPMUTE || tableType == PunishmentType.IPMUTE || tableType == PunishmentType.TEMPIPMUTE) {
            if (ipban) type = isTemp ? PunishmentType.TEMPIPMUTE : PunishmentType.IPMUTE;
            else type = isTemp ? PunishmentType.TEMPMUTE : PunishmentType.MUTE;
        } else if (tableType == PunishmentType.VOICEMUTE || tableType == PunishmentType.TEMPVOICEMUTE) {
            type = isTemp ? PunishmentType.TEMPVOICEMUTE : PunishmentType.VOICEMUTE;
        }

        return createPunishmentFromResultSet(rs, type);
    }

    private PunishmentType resolveTypeFromString(String typeStr) {
        if (typeStr == null) return PunishmentType.BAN;
        try {
            return PunishmentType.valueOf(typeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            LOGGER.log(Level.WARNING, "Unknown type_ref discriminator '" + typeStr + "' â€” defaulting to BAN.", e);
            return PunishmentType.BAN;
        }
    }

    // Fix: handle permanent sentinel both -1 and 0 (isPermanent checks both), and use <=0 for permanent to avoid missing 0-rows
    public Optional<Punishment> getActiveBan(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_bans WHERE uuid = ? AND active = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                if (rs.next()) return Optional.of(parsePunishment(rs, columns, PunishmentType.BAN));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveMute(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_mutes WHERE uuid = ? AND active = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                if (rs.next()) return Optional.of(parsePunishment(rs, columns, PunishmentType.MUTE));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveVoiceMute(Connection connection, UUID uuid) throws SQLException {
        String sql = "SELECT * FROM controlbans_voicemutes WHERE uuid = ? AND active = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, uuid.toString());
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                if (rs.next()) return Optional.of(parsePunishment(rs, columns, PunishmentType.VOICEMUTE));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpBan(Connection connection, String ip) throws SQLException {
        String sql = "SELECT * FROM controlbans_bans WHERE ip = ? AND active = TRUE AND ipban = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.IPBAN));
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpBanById(Connection connection, String punishmentId) throws SQLException {
        String sql = "SELECT * FROM controlbans_bans WHERE punishment_id = ? AND active = TRUE AND ipban = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, punishmentId);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(createPunishmentFromResultSet(rs, PunishmentType.IPBAN));
            }
        }
        return Optional.empty();
    }

    /**
     * Identity fallback for the login check: find an active ban by the stored
     * target NAME when the join UUID and the punishment UUID disagree (offline
     * vs Mojang identity mixes, proxy UUID algorithms). Returns the ban with
     * the raw row type (BAN or TEMPBAN).
     */
    public Optional<Punishment> getActiveBanByName(Connection connection, String name) throws SQLException {
        if (name == null || name.isEmpty()) return Optional.empty();
        String sql = "SELECT * FROM controlbans_bans WHERE LOWER(name) = LOWER(?) AND active = TRUE AND ipban = FALSE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, name);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long until = rs.getLong("until");
                    Punishment p = createPunishmentFromResultSet(rs, until <= 0 ? PunishmentType.BAN : PunishmentType.TEMPBAN);
                    String storedName = null;
                    try { storedName = rs.getString("name"); } catch (SQLException ignored) {}
                    if (p.getTargetName() == null || "Unknown".equalsIgnoreCase(p.getTargetName())) {
                        p = p.toBuilder().targetName(storedName != null ? storedName : name).build();
                    }
                    return Optional.of(p);
                }
            }
        }
        return Optional.empty();
    }

    public Optional<Punishment> getActiveIpMute(Connection connection, String ip) throws SQLException {
        String sql = "SELECT * FROM controlbans_mutes WHERE ip = ? AND active = TRUE AND ipban = TRUE AND (until <= 0 OR until > ?) ORDER BY time DESC LIMIT 1";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, ip);
            stmt.setLong(2, System.currentTimeMillis());
            try (ResultSet rs = stmt.executeQuery()) {
                Set<String> columns = PunishmentQueryBuilder.columnNames(rs);
                if (rs.next()) return Optional.of(parsePunishment(rs, columns, PunishmentType.IPMUTE));
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
        boolean oldAuto = connection.getAutoCommit();
        try {
            connection.setAutoCommit(false);
            try (java.sql.Statement stmt = connection.createStatement()) {
                stmt.addBatch("DELETE FROM controlbans_bans");
                stmt.addBatch("DELETE FROM controlbans_mutes");
                stmt.addBatch("DELETE FROM controlbans_warnings");
                stmt.addBatch("DELETE FROM controlbans_kicks");
                stmt.addBatch("DELETE FROM controlbans_history");
                stmt.addBatch("DELETE FROM controlbans_voicemutes");
                stmt.addBatch("DELETE FROM controlbans_appeals");
                stmt.addBatch("DELETE FROM controlbans_scheduled");
                stmt.addBatch("DELETE FROM controlbans_reports");
                stmt.addBatch("DELETE FROM controlbans_notes");
                stmt.executeBatch();
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            try { connection.setAutoCommit(oldAuto); } catch (SQLException ignored) {}
        }
    }

    public void clearPlayerData(Connection connection, UUID uuid) throws SQLException {
        String uuidStr = uuid.toString();
        boolean oldAuto = connection.getAutoCommit();
        // Resolve the last known name BEFORE deleting history (needed for report cleanup).
        String lastName = null;
        try (PreparedStatement stmt = connection.prepareStatement("SELECT name FROM controlbans_history WHERE uuid = ? ORDER BY date DESC LIMIT 1")) {
            stmt.setString(1, uuidStr);
            try (java.sql.ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) lastName = rs.getString(1);
            }
        } catch (SQLException ignored) {}
        if (lastName == null) {
            try (PreparedStatement stmt = connection.prepareStatement("SELECT target_name FROM controlbans_bans WHERE uuid = ? ORDER BY time DESC LIMIT 1")) {
                stmt.setString(1, uuidStr);
                try (java.sql.ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) lastName = rs.getString(1);
                }
            } catch (SQLException ignored) {}
        }
        try {
            connection.setAutoCommit(false);
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
            try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM controlbans_notes WHERE uuid = ?")) {
                stmt.setString(1, uuidStr);
                stmt.executeUpdate();
            }
            // Reports reference by name, not UUID — remove both reporter and target rows.
            if (lastName != null) {
                try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM controlbans_reports WHERE reporter_uuid = ? OR target_name = ?")) {
                    stmt.setString(1, uuidStr);
                    stmt.setString(2, lastName);
                    stmt.executeUpdate();
                } catch (SQLException ignored) {}
            } else {
                try (PreparedStatement stmt = connection.prepareStatement("DELETE FROM controlbans_reports WHERE reporter_uuid = ?")) {
                    stmt.setString(1, uuidStr);
                    stmt.executeUpdate();
                } catch (SQLException ignored) {}
            }
            connection.commit();
        } catch (SQLException e) {
            try { connection.rollback(); } catch (SQLException ignored) {}
            throw e;
        } finally {
            try { connection.setAutoCommit(oldAuto); } catch (SQLException ignored) {}
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

    public List<ret.tawny.controlbans.services.AppealService.Appeal> getAppeals(Connection connection) throws SQLException {
        List<ret.tawny.controlbans.services.AppealService.Appeal> appeals = new ArrayList<>();
        // The status column is added via ensureColumn migration; SELECT * keeps this portable.
        String sql = "SELECT * FROM controlbans_appeals ORDER BY created_at DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    appeals.add(mapAppeal(rs));
                }
            }
        }
        return appeals;
    }

    public boolean updateAppealStatus(Connection connection, int appealId, String status) throws SQLException {
        String sql = "UPDATE controlbans_appeals SET status = ? WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, status);
            stmt.setInt(2, appealId);
            return stmt.executeUpdate() > 0;
        }
    }

    private ret.tawny.controlbans.services.AppealService.Appeal mapAppeal(ResultSet rs) throws SQLException {
        String status = ret.tawny.controlbans.services.AppealService.STATUS_PENDING;
        try {
            String raw = rs.getString("status");
            if (raw != null && !raw.isBlank()) status = raw;
        } catch (SQLException ignored) {}
        String uuidStr = rs.getString("target_uuid");
        UUID targetUuid = null;
        try {
            if (uuidStr != null) targetUuid = UUID.fromString(uuidStr);
        } catch (IllegalArgumentException ignored) {}
        return new ret.tawny.controlbans.services.AppealService.Appeal(
                rs.getInt("id"),
                rs.getString("punishment_id"),
                targetUuid,
                rs.getString("message"),
                rs.getLong("created_at"),
                status);
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
        UUID staffUuid = null;
        try {
            if (staffUuidStr != null) staffUuid = UUID.fromString(staffUuidStr);
        } catch (IllegalArgumentException ignored) {}
        String uuidStr = rs.getString("uuid");
        UUID targetUuid;
        try {
            targetUuid = uuidStr != null ? UUID.fromString(uuidStr) : UUID.nameUUIDFromBytes("unknown".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            targetUuid = UUID.nameUUIDFromBytes(uuidStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }

        Punishment.Builder builder = Punishment.builder()
                .id(rs.getInt("id"))
                .punishmentId(rs.getString("punishment_id"))
                .type(type)
                .targetUuid(targetUuid)
                .reason(rs.getString("reason"))
                .staffUuid(staffUuid)
                .staffName(rs.getString("banned_by_name"))
                .createdTime(rs.getLong("time"))
                .serverOrigin(rs.getString("server_origin"))
                .silent(rs.getBoolean("silent"));

        // Elite: union queries always project these columns via COMMON_COLS/KICK_COLS/VOICEMUTE_COLS,
        // so direct column access avoids per-row hasColumn metadata scans (3000+ scans per history fetch)
        try { String tn = rs.getString("target_name"); builder.targetName(tn != null ? tn : "Unknown"); } catch (SQLException e) { builder.targetName("Unknown"); }
        try { builder.targetIp(rs.getString("ip")); } catch (SQLException ignored) {}
        try { builder.active(rs.getBoolean("active")); } catch (SQLException ignored) { builder.active(true); }
        try { builder.ipBan(rs.getBoolean("ipban")); } catch (SQLException ignored) {}
        try { builder.templateKey(rs.getString("template")); } catch (SQLException ignored) {}
        long until;
        try { until = rs.getLong("until"); if (rs.wasNull()) until = -1; } catch (SQLException e) { until = -1; }
        builder.expiryTime(until);
        return builder.build();
    }

    // Column sets are computed per query execution via PunishmentQueryBuilder.columnNames()
    // and threaded through the row mappers.

    private static final String LATEST_HISTORY_JOIN_SQL =
            " LEFT JOIN (SELECT uuid, MAX(id) AS id FROM controlbans_history GROUP BY uuid) hh ON hh.uuid = t.uuid" +
            " LEFT JOIN controlbans_history h ON h.id = hh.id";

    public List<Punishment> getAllPunishments(Connection connection) throws SQLException {
        List<Punishment> punishments = new ArrayList<>();
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_bans t" + LATEST_HISTORY_JOIN_SQL + " ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long until = rs.getLong("until");
                    if (rs.wasNull()) until = -1;
                    boolean isTemp = (until > 0);
                    boolean ipban = rs.getBoolean("ipban");
                    PunishmentType type;
                    if (ipban) {
                        type = isTemp ? PunishmentType.TEMPIPBAN : PunishmentType.IPBAN;
                    } else {
                        type = isTemp ? PunishmentType.TEMPBAN : PunishmentType.BAN;
                    }
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_mutes t" + LATEST_HISTORY_JOIN_SQL + " ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long until = rs.getLong("until");
                    if (rs.wasNull()) until = -1;
                    boolean isTemp = (until > 0);
                    boolean ipban = rs.getBoolean("ipban");
                    PunishmentType type;
                    if (ipban) {
                        type = isTemp ? PunishmentType.TEMPIPMUTE : PunishmentType.IPMUTE;
                    } else {
                        type = isTemp ? PunishmentType.TEMPMUTE : PunishmentType.MUTE;
                    }
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_warnings t" + LATEST_HISTORY_JOIN_SQL + " ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) punishments.add(createPunishmentFromResultSet(rs, PunishmentType.WARN));
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_kicks t" + LATEST_HISTORY_JOIN_SQL + " ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) punishments.add(createPunishmentFromResultSet(rs, PunishmentType.KICK));
            }
        }
        try (PreparedStatement stmt = connection.prepareStatement("SELECT t.*, h.name as target_name FROM controlbans_voicemutes t" + LATEST_HISTORY_JOIN_SQL + " ORDER BY t.time DESC")) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long until = rs.getLong("until");
                    if (rs.wasNull()) until = -1;
                    PunishmentType type = (until > 0) ? PunishmentType.TEMPVOICEMUTE : PunishmentType.VOICEMUTE;
                    punishments.add(createPunishmentFromResultSet(rs, type));
                }
            }
        }
        return punishments;
    }

    public void insertReport(Connection connection, String id, UUID reporterUuid, String reporterName, String targetName, UUID targetUuid, String reason, String category, long timestamp, String status) throws SQLException {
        String sql = "INSERT INTO controlbans_reports (id, reporter_uuid, reporter_name, target_name, target_uuid, reason, category, time, status) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            stmt.setString(2, reporterUuid.toString());
            stmt.setString(3, reporterName);
            stmt.setString(4, targetName);
            stmt.setString(5, targetUuid != null ? targetUuid.toString() : null);
            stmt.setString(6, reason);
            stmt.setString(7, category);
            stmt.setLong(8, timestamp);
            stmt.setString(9, status);
            stmt.executeUpdate();
        }
    }

    private ret.tawny.controlbans.services.ReportService.Report mapReport(ResultSet rs) throws SQLException {
        String targetUuidStr = rs.getString("target_uuid");
        UUID targetUuid = null;
        if (targetUuidStr != null && !targetUuidStr.isBlank()) {
            try { targetUuid = UUID.fromString(targetUuidStr); } catch (IllegalArgumentException ignored) {}
        }
        return new ret.tawny.controlbans.services.ReportService.Report(
                rs.getString("id"),
                UUID.fromString(rs.getString("reporter_uuid")),
                rs.getString("reporter_name"),
                rs.getString("target_name"),
                targetUuid,
                rs.getString("reason"),
                rs.getString("category"),
                rs.getLong("time"),
                rs.getString("status")
        );
    }

    public List<ret.tawny.controlbans.services.ReportService.Report> getReports(Connection connection) throws SQLException {
        List<ret.tawny.controlbans.services.ReportService.Report> reports = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_reports ORDER BY time DESC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    reports.add(mapReport(rs));
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
                    reports.add(mapReport(rs));
                }
            }
        }
        return reports;
    }

    public java.util.Optional<ret.tawny.controlbans.services.ReportService.Report> getReport(Connection connection, String id) throws SQLException {
        String sql = "SELECT * FROM controlbans_reports WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return java.util.Optional.of(mapReport(rs));
                }
            }
        }
        return java.util.Optional.empty();
    }

    public boolean deleteReport(Connection connection, String id) throws SQLException {
        String sql = "DELETE FROM controlbans_reports WHERE id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, id);
            return stmt.executeUpdate() > 0;
        }
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
        if (index < 1) return false;
        String findSql = "SELECT id FROM controlbans_notes WHERE uuid = ? ORDER BY time ASC, id ASC LIMIT 1 OFFSET ?";
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

    public void insertScheduled(Connection connection, ScheduledPunishment s) throws SQLException {
        String sql = "INSERT INTO controlbans_scheduled (schedule_id, action, type, target_name, target_ip, duration_seconds, reason, staff_uuid, staff_name, execute_at, created_at, silent, server_origin) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, s.scheduleId());
            stmt.setString(2, s.action().name());
            stmt.setString(3, s.type().name());
            stmt.setString(4, s.targetName());
            stmt.setString(5, s.targetIp());
            stmt.setLong(6, s.durationSeconds());
            stmt.setString(7, s.reason());
            stmt.setString(8, s.staffUuid());
            stmt.setString(9, s.staffName());
            stmt.setLong(10, s.executeAt());
            stmt.setLong(11, s.createdAt());
            stmt.setBoolean(12, s.silent());
            stmt.setString(13, s.serverOrigin());
            stmt.executeUpdate();
        }
    }

    public Optional<ScheduledPunishment> getScheduled(Connection connection, String scheduleId) throws SQLException {
        String sql = "SELECT * FROM controlbans_scheduled WHERE schedule_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, scheduleId);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) return Optional.of(rsToScheduled(rs));
            }
        }
        return Optional.empty();
    }

    public List<ScheduledPunishment> getAllScheduled(Connection connection) throws SQLException {
        List<ScheduledPunishment> result = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_scheduled ORDER BY execute_at ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rsToScheduled(rs));
                }
            }
        }
        return result;
    }

    public List<ScheduledPunishment> getDueScheduled(Connection connection, long atOrBefore) throws SQLException {
        List<ScheduledPunishment> result = new ArrayList<>();
        String sql = "SELECT * FROM controlbans_scheduled WHERE execute_at <= ? ORDER BY execute_at ASC";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setLong(1, atOrBefore);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    result.add(rsToScheduled(rs));
                }
            }
        }
        return result;
    }

    public void removeScheduled(Connection connection, String scheduleId) throws SQLException {
        String sql = "DELETE FROM controlbans_scheduled WHERE schedule_id = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, scheduleId);
            stmt.executeUpdate();
        }
    }

    public void removeScheduledForTarget(Connection connection, UUID targetUuid) throws SQLException {
        // Schema note: controlbans_scheduled has no target_uuid column and no uuid-based
        // deletion method exists, so deletion resolves the target's last known name and
        // removes by name. Adding a uuid column would be a schema migration — intentionally
        // not done here (minimal-touch fix).
        String targetName = getLastKnownName(connection, targetUuid);
        if (targetName == null || targetName.isEmpty()) {
            return;
        }
        String sql = "DELETE FROM controlbans_scheduled WHERE target_name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, targetName);
            stmt.executeUpdate();
        }
    }

    private ScheduledPunishment rsToScheduled(ResultSet rs) throws SQLException {
        return new ScheduledPunishment(
                rs.getString("schedule_id"),
                ScheduledPunishment.ScheduledAction.valueOf(rs.getString("action")),
                PunishmentType.valueOf(rs.getString("type")),
                rs.getString("target_name"),
                rs.getString("target_ip"),
                rs.getLong("duration_seconds"),
                rs.getString("reason"),
                rs.getString("staff_uuid"),
                rs.getString("staff_name"),
                rs.getLong("execute_at"),
                rs.getLong("created_at"),
                rs.getBoolean("silent"),
                rs.getString("server_origin")
        );
    }
}