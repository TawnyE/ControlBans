package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.IdUtil;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.logging.Level;

public class ScheduledService {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final PunishmentService punishmentService;
    private final SchedulerAdapter scheduler;
    private final ConcurrentMap<String, ScheduledPunishment> pending = new ConcurrentHashMap<>();

    private static final long CHECK_INTERVAL_TICKS = 20L;

    public ScheduledService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        this.punishmentService = plugin.getPunishmentService();
        this.scheduler = plugin.getSchedulerAdapter();
    }

    public void loadScheduledPunishments() {
        storage.getAllScheduledPunishments().thenAccept(list -> {
            for (ScheduledPunishment s : list) {
                pending.put(s.scheduleId(), s);
            }
            int count = list.size();
            if (count > 0) {
                plugin.getLogger().info("Loaded " + count + " scheduled punishment(s).");
            }
        }).exceptionally(ex -> {
            plugin.getLogger().log(Level.WARNING, "Failed to load scheduled punishments", ex);
            return null;
        });
    }

    public CompletableFuture<Void> schedulePunishment(ScheduledPunishment sp) {
        return storage.saveScheduledPunishment(sp).thenRun(() -> {
            pending.put(sp.scheduleId(), sp);
        });
    }

    public CompletableFuture<Optional<ScheduledPunishment>> cancelScheduled(String scheduleId) {
        ScheduledPunishment removed = pending.remove(scheduleId);
        if (removed == null) {
            return storage.getScheduledPunishment(scheduleId).thenCompose(opt -> {
                if (opt.isPresent()) {
                    return storage.removeScheduledPunishment(scheduleId).thenApply(v -> opt);
                }
                return CompletableFuture.completedFuture(Optional.empty());
            });
        }
        return storage.removeScheduledPunishment(scheduleId).thenApply(v -> Optional.of(removed));
    }

    public List<ScheduledPunishment> getPendingPunishments() {
        return List.copyOf(pending.values());
    }

    public Optional<ScheduledPunishment> getById(String scheduleId) {
        return Optional.ofNullable(pending.get(scheduleId));
    }

    public void startChecker() {
        scheduler.runTaskTimer(() -> {
            long now = System.currentTimeMillis();
            for (ScheduledPunishment sp : List.copyOf(pending.values())) {
                if (sp.executeAt() <= now) {
                    pending.remove(sp.scheduleId());
                    executeScheduled(sp);
                }
            }
        }, 2L, 20L);
    }

    private void executeScheduled(ScheduledPunishment sp) {
        UUID staffUuid = null;
        if (sp.staffUuid() != null) {
            try {
                staffUuid = UUID.fromString(sp.staffUuid());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().log(Level.WARNING, "Scheduled punishment " + sp.scheduleId()
                        + " has corrupt staff_uuid '" + sp.staffUuid() + "' — executing without staff attribution.", e);
            }
        }
        String reason = sp.reason() != null ? sp.reason() : "";
        boolean silent = sp.silent();

        CompletableFuture<?> exec = switch (sp.action()) {
            case BAN -> punishmentService.banPlayer(sp.targetName(), reason, staffUuid, sp.staffName(), silent, sp.type() == PunishmentType.IPBAN || sp.type() == PunishmentType.TEMPIPBAN);
            case TEMPBAN -> {
                long dur = sp.durationSeconds();
                if (dur <= 0) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled TEMPBAN requires duration > 0 (got " + dur + ")"));
                }
                yield punishmentService.tempBanPlayer(sp.targetName(), dur, reason, staffUuid, sp.staffName(), silent, false);
            }
            case MUTE -> punishmentService.mutePlayer(sp.targetName(), reason, staffUuid, sp.staffName(), silent);
            case TEMPMUTE -> {
                long dur = sp.durationSeconds();
                if (dur <= 0) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled TEMPMUTE requires duration > 0 (got " + dur + ")"));
                }
                yield punishmentService.tempMutePlayer(sp.targetName(), dur, reason, staffUuid, sp.staffName(), silent);
            }
            case KICK -> punishmentService.kickPlayer(sp.targetName(), reason, staffUuid, sp.staffName(), silent);
            case WARN -> punishmentService.warnPlayer(sp.targetName(), reason, staffUuid, sp.staffName(), silent);
            case VOICEMUTE -> punishmentService.voiceMutePlayer(sp.targetName(), reason, staffUuid, sp.staffName(), silent);
            case TEMPVOICEMUTE -> {
                long dur = sp.durationSeconds();
                if (dur <= 0) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled TEMPVOICEMUTE requires duration > 0 (got " + dur + ")"));
                }
                yield punishmentService.tempVoiceMutePlayer(sp.targetName(), dur, reason, staffUuid, sp.staffName(), silent);
            }
            case IPBAN -> punishmentService.ipBanPlayer(sp.targetName(), -1, reason, staffUuid, sp.staffName(), silent).thenApply(v -> null);
            case TEMPIPBAN -> {
                long dur = sp.durationSeconds();
                if (dur <= 0) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled TEMPIPBAN requires duration > 0 (got " + dur + ")"));
                }
                yield punishmentService.ipBanPlayer(sp.targetName(), dur, reason, staffUuid, sp.staffName(), silent).thenApply(v -> null);
            }
            case IPMUTE -> punishmentService.ipMutePlayer(sp.targetName(), -1, reason, staffUuid, sp.staffName(), silent).thenApply(v -> null);
            case TEMPIPMUTE -> {
                long dur = sp.durationSeconds();
                if (dur <= 0) {
                    yield CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled TEMPIPMUTE requires duration > 0 (got " + dur + ")"));
                }
                yield punishmentService.ipMutePlayer(sp.targetName(), dur, reason, staffUuid, sp.staffName(), silent).thenApply(v -> null);
            }
        };

        exec.whenComplete((v, ex) -> {
            if (ex != null) {
                plugin.getLogger().log(Level.WARNING, "Scheduled punishment " + sp.scheduleId() + " for " + sp.targetName() + " failed", ex);
                if (!isPermanentFailure(ex)) {
                    // Transient failure (DB hiccup, etc.) — keep the row so it can be retried.
                    pending.put(sp.scheduleId(), sp);
                    return;
                }
            }
            storage.removeScheduledPunishment(sp.scheduleId());
        });
    }

    private static boolean isPermanentFailure(Throwable ex) {
        for (Throwable t = ex; t != null; t = t.getCause()) {
            if (t instanceof IllegalArgumentException || t instanceof IllegalStateException) {
                // Player never existed, target already punished, no IP on record, etc. —
                // retrying would fail forever, so the row may be removed.
                return true;
            }
        }
        return false;
    }

    public CompletableFuture<Void> scheduleForAction(ScheduledPunishment.ScheduledAction action, PunishmentType type, String targetName, long durationSeconds, String reason, UUID staffUuid, String staffName, boolean silent, long executeAt) {
        String scheduleId = "SCHED-" + IdUtil.generatePunishmentId();
        String suuid = staffUuid != null ? staffUuid.toString() : null;
        ScheduledPunishment sp = new ScheduledPunishment(
                scheduleId, action, type, targetName, null, durationSeconds, reason,
                suuid, staffName, executeAt, System.currentTimeMillis(), silent, "global"
        );
        return schedulePunishment(sp);
    }
}
