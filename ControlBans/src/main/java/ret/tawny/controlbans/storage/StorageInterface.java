package ret.tawny.controlbans.storage;

import ret.tawny.controlbans.model.Punishment;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface StorageInterface {

    void initialize();
    void shutdown();

    CompletableFuture<Void> insertBan(Punishment punishment);
    CompletableFuture<Void> insertMute(Punishment punishment);
    CompletableFuture<Void> insertWarning(Punishment punishment);
    CompletableFuture<Void> insertKick(Punishment punishment);
    CompletableFuture<Void> insertVoiceMute(Punishment punishment);

    CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid);
    CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid);
    CompletableFuture<Optional<Punishment>> getActiveVoiceMute(UUID uuid);
    CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip);
    CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip);
    CompletableFuture<Optional<Punishment>> getPunishmentById(String id);

    CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit);
    CompletableFuture<List<Punishment>> getRecentPunishments(int limit);
    CompletableFuture<List<Punishment>> getAllPunishments();

    CompletableFuture<Void> removeBan(UUID uuid, UUID removedBy, String removedByName);
    CompletableFuture<Void> removeMute(UUID uuid, UUID removedBy, String removedByName);
    CompletableFuture<Void> removeVoiceMute(UUID uuid, UUID removedBy, String removedByName);
    CompletableFuture<Void> removeIpBan(String ip, UUID removedBy, String removedByName);
    CompletableFuture<Void> removeIpMute(String ip, UUID removedBy, String removedByName);

    CompletableFuture<Void> recordHistory(UUID uuid, String name, String ip);
    CompletableFuture<Void> clearAllData();
    CompletableFuture<Void> clearPlayerData(UUID uuid);

    CompletableFuture<String> getLastIpForUuid(UUID uuid);
    CompletableFuture<String> getLastKnownName(UUID uuid);
    CompletableFuture<UUID> getUuidByName(String name);

    CompletableFuture<List<String>> getNamesStartingWith(String prefix);

    CompletableFuture<Set<String>> getIpsForUuid(UUID uuid);
    CompletableFuture<Set<UUID>> getUuidsOnIp(String ip);
    CompletableFuture<Integer> getUserCountOnIp(String ip);

    CompletableFuture<Void> addAppeal(String punishmentId, UUID uuid, String message, long timestamp);
    CompletableFuture<Long> getLastAppealTime(UUID uuid);
    CompletableFuture<Integer> getAppealCount(UUID uuid, long sinceTimestamp);

    CompletableFuture<Void> insertReport(String id, UUID reporterUuid, String reporterName, String targetName, String reason, long timestamp, String status);
    CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReports();
    CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReportsByReporter(UUID reporterUuid);
    CompletableFuture<Boolean> updateReportStatus(String id, String status);

    CompletableFuture<Void> addNote(UUID targetUuid, String staffName, String noteText, long timestamp);
    CompletableFuture<Boolean> removeNote(UUID targetUuid, int index);
    CompletableFuture<List<ret.tawny.controlbans.services.NoteService.PlayerNote>> getNotes(UUID targetUuid);

    CompletableFuture<Void> importPunishment(Punishment punishment);
}