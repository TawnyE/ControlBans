package ret.tawny.controlbans;

import ret.tawny.controlbans.api.ApiPunishment;
import ret.tawny.controlbans.api.ApiReport;
import ret.tawny.controlbans.api.ControlBans;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.services.ReportService;
import ret.tawny.controlbans.storage.StorageInterface;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Package-private bridge between the public {@link ControlBans} interface and the
 * internal services. All values returned are immutable snapshots; internal model
 * objects are never exposed.
 */
final class ApiProvider implements ControlBans {

    private static final int HISTORY_LIMIT = 1000;

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final ReportService reportService;
    private final StorageInterface storage;

    ApiProvider(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
        this.reportService = plugin.getReportService();
        this.storage = plugin.getStorage();
    }

    @Override
    public CompletableFuture<Optional<ApiPunishment>> getActiveBan(UUID uuid) {
        return punishmentService.getActiveBan(uuid).thenApply(opt -> opt.map(this::toApi));
    }

    @Override
    public CompletableFuture<Optional<ApiPunishment>> getActiveMute(UUID uuid) {
        return punishmentService.getActiveMute(uuid).thenApply(opt -> opt.map(this::toApi));
    }

    @Override
    public CompletableFuture<Boolean> isBanned(UUID uuid) {
        return getActiveBan(uuid).thenApply(Optional::isPresent);
    }

    @Override
    public CompletableFuture<Boolean> isMuted(UUID uuid) {
        return getActiveMute(uuid).thenApply(Optional::isPresent);
    }

    @Override
    public CompletableFuture<List<ApiPunishment>> getHistory(UUID uuid) {
        return punishmentService.getPunishmentHistory(uuid, HISTORY_LIMIT).thenApply(list -> {
            List<ApiPunishment> out = new ArrayList<>(list.size());
            for (Punishment p : list) out.add(toApi(p));
            return out;
        });
    }

    @Override
    public CompletableFuture<ApiPunishment> ban(UUID uuid, String targetName, String reason,
                                                Duration duration, String operator) {
        String staffName = operator != null ? operator : "Console";
        CompletableFuture<?> issued = duration == null
                ? punishmentService.banPlayer(targetName, reason, null, staffName, false, false)
                : punishmentService.tempBanPlayer(targetName, duration.getSeconds(), reason, null, staffName, false, false);
        return issued.thenCompose(v -> punishmentService.getActiveBan(uuid).thenApply(opt ->
                opt.map(this::toApi).orElseThrow(() -> new IllegalStateException("Ban applied but could not be re-read"))));
    }

    @Override
    public CompletableFuture<ApiPunishment> mute(UUID uuid, String targetName, String reason,
                                                 Duration duration, String operator) {
        String staffName = operator != null ? operator : "Console";
        CompletableFuture<?> issued = duration == null
                ? punishmentService.mutePlayer(targetName, reason, null, staffName, false)
                : punishmentService.tempMutePlayer(targetName, duration.getSeconds(), reason, null, staffName, false);
        return issued.thenCompose(v -> punishmentService.getActiveMute(uuid).thenApply(opt ->
                opt.map(this::toApi).orElseThrow(() -> new IllegalStateException("Mute applied but could not be re-read"))));
    }

    @Override
    public CompletableFuture<ApiPunishment> kick(UUID uuid, String targetName, String reason, String operator) {
        String staffName = operator != null ? operator : "Console";
        return punishmentService.kickPlayer(targetName, reason, null, staffName, false)
                .thenCompose(v -> latestOfType(uuid, PunishmentType.KICK));
    }

    @Override
    public CompletableFuture<ApiPunishment> warn(UUID uuid, String targetName, String reason, String operator) {
        String staffName = operator != null ? operator : "Console";
        return punishmentService.warnPlayer(targetName, reason, null, staffName, false)
                .thenCompose(v -> latestOfType(uuid, PunishmentType.WARN));
    }

    @Override
    public CompletableFuture<Boolean> unban(UUID uuid) {
        return storage.getLastKnownName(uuid).thenCompose(name -> {
            if (name == null) return CompletableFuture.completedFuture(false);
            return punishmentService.unbanPlayer(name, null, "Console");
        });
    }

    @Override
    public CompletableFuture<Boolean> unmute(UUID uuid) {
        return storage.getLastKnownName(uuid).thenCompose(name -> {
            if (name == null) return CompletableFuture.completedFuture(false);
            return punishmentService.unmutePlayer(name, null, "Console");
        });
    }

    @Override
    public CompletableFuture<List<ApiReport>> getPendingReports() {
        return reportService.getReports().thenApply(reports -> {
            List<ApiReport> out = new ArrayList<>();
            for (ReportService.Report r : reports) {
                if (ReportService.STATUS_PENDING.equalsIgnoreCase(ReportService.displayStatus(r.status()))) {
                    out.add(new ApiReport(r.id(), r.reporterUuid(), r.reporterName(), r.targetName(),
                            r.reason(), r.category(), r.timestamp(), ReportService.displayStatus(r.status())));
                }
            }
            out.sort(Comparator.comparingLong(ApiReport::timestamp).reversed());
            return out;
        });
    }

    private CompletableFuture<ApiPunishment> latestOfType(UUID uuid, PunishmentType type) {
        return punishmentService.getPunishmentHistory(uuid, 50).thenApply(list -> {
            long newest = -1L;
            Punishment best = null;
            for (Punishment p : list) {
                if (type.equals(p.getType()) && p.getCreatedTime() > newest) {
                    newest = p.getCreatedTime();
                    best = p;
                }
            }
            if (best == null) {
                throw new IllegalStateException("Punishment applied but could not be re-read");
            }
            return toApi(best);
        });
    }

    private ApiPunishment toApi(Punishment p) {
        return new ApiPunishment(
                p.getPunishmentId(),
                p.getType(),
                p.getTargetUuid(),
                p.getTargetName(),
                p.getReason(),
                p.getStaffName() != null ? p.getStaffName() : "Console",
                p.getCreatedTime(),
                p.getExpiryTime(),
                p.isSilent(),
                p.isActive());
    }
}
