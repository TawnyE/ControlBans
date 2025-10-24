package ret.tawny.controlbans.services;

import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.AuditLogDao;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AuditService {

    private final DatabaseManager databaseManager;
    private final AuditLogDao auditLogDao = new AuditLogDao();

    public AuditService(DatabaseManager databaseManager) {
        this.databaseManager = databaseManager;
    }

    public CompletableFuture<Void> record(String action, Punishment punishment, UUID actorUuid, String actorName, String context) {
        AuditEntry entry = new AuditEntry(
                action,
                punishment != null ? punishment.getPunishmentId() : null,
                actorUuid != null ? actorUuid.toString() : null,
                actorName,
                punishment != null && punishment.getTargetUuid() != null ? punishment.getTargetUuid().toString() : null,
                punishment != null ? punishment.getTargetName() : null,
                System.currentTimeMillis(),
                context
        );
        return databaseManager.executeAsync(connection -> auditLogDao.insert(connection, entry));
    }

    public CompletableFuture<List<AuditEntry>> fetchRecent(int limit) {
        return databaseManager.executeQueryAsync(connection -> auditLogDao.fetchRecent(connection, limit));
    }

    public record AuditEntry(String action,
                             String punishmentId,
                             String actorUuid,
                             String actorName,
                             String targetUuid,
                             String targetName,
                             long createdAt,
                             String context) { }
}
