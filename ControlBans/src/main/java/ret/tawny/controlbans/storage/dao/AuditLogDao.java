package ret.tawny.controlbans.storage.dao;

import ret.tawny.controlbans.services.AuditService;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

public class AuditLogDao {

    public void insert(Connection connection, AuditService.AuditEntry entry) throws SQLException {
        try (PreparedStatement stmt = connection.prepareStatement(
                "INSERT INTO controlbans_audit_log (action, punishment_id, actor_uuid, actor_name, target_uuid, target_name, created_at, context) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?, ?)")) {
            stmt.setString(1, entry.action());
            stmt.setString(2, entry.punishmentId());
            stmt.setString(3, entry.actorUuid());
            stmt.setString(4, entry.actorName());
            stmt.setString(5, entry.targetUuid());
            stmt.setString(6, entry.targetName());
            stmt.setLong(7, entry.createdAt());
            stmt.setString(8, entry.context());
            stmt.executeUpdate();
        }
    }

    public List<AuditService.AuditEntry> fetchRecent(Connection connection, int limit) throws SQLException {
        String sql = "SELECT action, punishment_id, actor_uuid, actor_name, target_uuid, target_name, created_at, context FROM controlbans_audit_log ORDER BY created_at DESC LIMIT ?";
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setInt(1, limit);
            try (ResultSet rs = stmt.executeQuery()) {
                List<AuditService.AuditEntry> entries = new ArrayList<>();
                while (rs.next()) {
                    entries.add(new AuditService.AuditEntry(
                            rs.getString("action"),
                            rs.getString("punishment_id"),
                            rs.getString("actor_uuid"),
                            rs.getString("actor_name"),
                            rs.getString("target_uuid"),
                            rs.getString("target_name"),
                            rs.getLong("created_at"),
                            rs.getString("context")
                    ));
                }
                return entries;
            }
        }
    }
}
