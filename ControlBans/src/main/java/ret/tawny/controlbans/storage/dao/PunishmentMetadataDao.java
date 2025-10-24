package ret.tawny.controlbans.storage.dao;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PunishmentMetadataDao {

    public void upsert(Connection connection, String punishmentId, String category, int escalationLevel, long warnDecayAt) throws SQLException {
        try (PreparedStatement delete = connection.prepareStatement(
                "DELETE FROM controlbans_punishment_meta WHERE punishment_id = ?")) {
            delete.setString(1, punishmentId);
            delete.executeUpdate();
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO controlbans_punishment_meta (punishment_id, category, escalation_level, warn_decay_at, reminder_sent) VALUES (?, ?, ?, ?, FALSE)")) {
            insert.setString(1, punishmentId);
            insert.setString(2, category);
            insert.setInt(3, escalationLevel);
            insert.setLong(4, warnDecayAt);
            insert.executeUpdate();
        }
    }

    public List<String> findDecayedWarningIds(Connection connection, long now) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT punishment_id FROM controlbans_punishment_meta WHERE warn_decay_at > 0 AND warn_decay_at <= ?")) {
            stmt.setLong(1, now);
            try (ResultSet rs = stmt.executeQuery()) {
                List<String> ids = new ArrayList<>();
                while (rs.next()) {
                    ids.add(rs.getString("punishment_id"));
                }
                return ids;
            }
        }
    }

    public void delete(Connection connection, String punishmentId) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "DELETE FROM controlbans_punishment_meta WHERE punishment_id = ?")) {
            stmt.setString(1, punishmentId);
            stmt.executeUpdate();
        }
    }

    public int countActiveWarnings(Connection connection, UUID targetUuid) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT COUNT(*) FROM litebans_warnings WHERE uuid = ? AND active = TRUE")) {
            stmt.setString(1, targetUuid.toString());
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        }
        return 0;
    }
}
