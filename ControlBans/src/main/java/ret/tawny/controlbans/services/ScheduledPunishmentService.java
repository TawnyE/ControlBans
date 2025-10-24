package ret.tawny.controlbans.services;

import org.bukkit.scheduler.BukkitTask;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.ScheduledPunishmentDao;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public class ScheduledPunishmentService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final PunishmentService punishmentService;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final BenchmarkService benchmarkService;
    private final ScheduledPunishmentDao dao = new ScheduledPunishmentDao();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private BukkitTask pollerTask;

    public ScheduledPunishmentService(ControlBansPlugin plugin,
                                      DatabaseManager databaseManager,
                                      PunishmentService punishmentService,
                                      BenchmarkService benchmarkService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.punishmentService = punishmentService;
        this.configManager = plugin.getConfigManager();
        this.scheduler = plugin.getSchedulerAdapter();
        this.benchmarkService = benchmarkService;
    }

    public void start() {
        if (!configManager.isSchedulingEnabled()) {
            plugin.getLogger().info("Scheduled punishments are disabled in configuration.");
            return;
        }
        if (running.getAndSet(true)) {
            return;
        }
        long intervalTicks = Math.max(20L, configManager.getSchedulingCheckIntervalSeconds() * 20L);
        pollerTask = scheduler.runTaskTimerAsynchronously(this::dispatchDue, intervalTicks, intervalTicks);
        refreshPendingCount();
        plugin.getLogger().info("Scheduled punishment service started.");
    }

    public void stop() {
        running.set(false);
        if (pollerTask != null) {
            pollerTask.cancel();
            pollerTask = null;
        }
    }

    public CompletableFuture<Void> schedule(ScheduledPunishment schedule) {
        long maxFuture = Duration.ofDays(configManager.getSchedulingLookaheadDays()).toMillis();
        if (schedule.getExecutionTime() - System.currentTimeMillis() > maxFuture) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Scheduled time exceeds configured lookahead window."));
        }
        return databaseManager.executeAsync(connection -> dao.insert(connection, schedule))
                .thenRun(this::refreshPendingCount);
    }

    public CompletableFuture<List<ScheduledPunishment>> getUpcoming(int limit) {
        return databaseManager.executeQueryAsync(connection -> dao.findUpcoming(connection, limit));
    }

    private void dispatchDue() {
        if (!running.get()) {
            return;
        }
        long now = System.currentTimeMillis();
        databaseManager.executeQueryAsync(connection -> dao.findDue(connection, now))
                .thenAccept(schedules -> {
                    if (schedules.isEmpty()) {
                        benchmarkService.updatePendingScheduleCount(0);
                        return;
                    }
                    schedules.forEach(schedule -> {
                        databaseManager.executeAsync(connection -> dao.delete(connection, schedule.getId()))
                                .thenRun(() -> execute(schedule))
                                .exceptionally(error -> {
                                    plugin.getLogger().log(Level.WARNING, "Failed to delete executed schedule", error);
                                    return null;
                                });
                    });
                    refreshPendingCount();
                })
                .exceptionally(error -> {
                    plugin.getLogger().log(Level.WARNING, "Failed to query scheduled punishments", error);
                    return null;
                });
    }

    private void execute(ScheduledPunishment schedule) {
        punishmentService.executeScheduledPunishment(schedule)
                .whenComplete((ignored, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().log(Level.WARNING, "Scheduled punishment failed for " + schedule.getTargetName(), throwable);
                    } else {
                        benchmarkService.recordScheduledExecution();
                    }
                });
    }

    private void refreshPendingCount() {
        databaseManager.executeQueryAsync(connection -> dao.findUpcoming(connection, 1000))
                .thenAccept(list -> benchmarkService.updatePendingScheduleCount(list.size()));
    }
}
