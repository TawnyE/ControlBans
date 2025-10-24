package ret.tawny.controlbans.services;

import org.bukkit.scheduler.BukkitTask;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Collects lightweight runtime metrics that feed the "Code Control Benchmark" report.
 */
public class BenchmarkService implements DatabaseManager.DatabaseMetricsCollector {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private final SchedulerAdapter scheduler;
    private final Deque<Sample> databaseSamples = new ConcurrentLinkedDeque<>();
    private final Deque<WebSample> webSamples = new ConcurrentLinkedDeque<>();
    private final AtomicLong databaseFailures = new AtomicLong();
    private final AtomicLong discordSuccess = new AtomicLong();
    private final AtomicLong discordFailures = new AtomicLong();
    private final AtomicLong scheduledExecutions = new AtomicLong();
    private final AtomicInteger pendingSchedules = new AtomicInteger();
    private final AtomicInteger proxyQueueSize = new AtomicInteger();
    private final Duration window;
    private final long sampleIntervalTicks;

    private BukkitTask samplerTask;
    private ProxyService proxyService;

    public BenchmarkService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfigManager();
        this.scheduler = plugin.getSchedulerAdapter();
        this.window = Duration.ofMinutes(Math.max(1, config.getBenchmarkWindowMinutes()));
        long seconds = Math.max(5L, config.getBenchmarkSampleIntervalSeconds());
        this.sampleIntervalTicks = seconds * 20L;
    }

    public void start() {
        if (!config.isBenchmarkEnabled()) {
            return;
        }
        stop();
        samplerTask = scheduler.runTaskTimerAsynchronously(() -> {
            prune(window);
            if (proxyService != null) {
                proxyQueueSize.set(proxyService.getPendingMessageCount());
            }
        }, sampleIntervalTicks, sampleIntervalTicks);
        plugin.getLogger().info("Benchmark service initialised with a " + window.toMinutes() + "m window.");
    }

    public void stop() {
        if (samplerTask != null) {
            samplerTask.cancel();
            samplerTask = null;
        }
        prune(window);
    }

    public void bindProxyService(ProxyService proxyService) {
        this.proxyService = proxyService;
    }

    public void recordDiscordDispatch(boolean success) {
        if (success) {
            discordSuccess.incrementAndGet();
        } else {
            discordFailures.incrementAndGet();
        }
    }

    public void recordWebRequest(String endpoint, long durationNanos, int statusCode) {
        if (!config.isBenchmarkEnabled()) return;
        webSamples.add(new WebSample(System.currentTimeMillis(), durationNanos, endpoint, statusCode));
    }

    public void recordScheduledExecution() {
        scheduledExecutions.incrementAndGet();
    }

    public void updatePendingScheduleCount(int pending) {
        pendingSchedules.set(Math.max(pending, 0));
    }

    @Override
    public void recordDatabaseOperation(long durationNanos, boolean success) {
        if (!config.isBenchmarkEnabled()) return;
        databaseSamples.add(new Sample(System.currentTimeMillis(), durationNanos));
        if (!success) {
            databaseFailures.incrementAndGet();
        }
    }

    public BenchmarkSnapshot snapshot() {
        prune(window);

        long now = System.currentTimeMillis();
        List<Sample> db = new ArrayList<>(databaseSamples);
        List<WebSample> web = new ArrayList<>(webSamples);

        double avgDbMillis = db.stream()
                .mapToDouble(sample -> sample.durationNanos / 1_000_000.0)
                .average()
                .orElse(0.0);
        double avgWebMillis = web.stream()
                .mapToDouble(sample -> sample.durationNanos / 1_000_000.0)
                .average()
                .orElse(0.0);

        return new BenchmarkSnapshot(
                now,
                db.size(),
                avgDbMillis,
                databaseFailures.get(),
                web.size(),
                avgWebMillis,
                discordSuccess.get(),
                discordFailures.get(),
                proxyQueueSize.get(),
                scheduledExecutions.get(),
                pendingSchedules.get()
        );
    }

    public List<String> formatSnapshotLines(BenchmarkSnapshot snapshot) {
        List<String> lines = new ArrayList<>();
        lines.add("§6Code Control Benchmark §7(Last " + window.toMinutes() + "m)");
        lines.add(String.format(Locale.ROOT, "§eDB Ops: §f%d §7(avg %.2fms, §c%s failures§7)",
                snapshot.databaseOperations,
                snapshot.averageDatabaseMillis,
                snapshot.databaseFailures));
        lines.add(String.format(Locale.ROOT, "§eWeb Requests: §f%d §7(avg %.2fms)",
                snapshot.webRequests,
                snapshot.averageWebMillis));
        lines.add(String.format(Locale.ROOT, "§eDiscord Dispatch: §a%d success §7/ §c%d failed",
                snapshot.discordSuccess,
                snapshot.discordFailures));
        lines.add(String.format(Locale.ROOT, "§eProxy Queue: §f%d pending messages", snapshot.proxyQueueDepth));
        lines.add(String.format(Locale.ROOT, "§eScheduled Tasks: §f%d executed, §f%d pending",
                snapshot.scheduledExecutions,
                snapshot.pendingSchedules));
        lines.add("§7Generated at " + Instant.ofEpochMilli(snapshot.generatedAt));
        return lines;
    }

    private void prune(Duration window) {
        long cutoff = System.currentTimeMillis() - window.toMillis();
        while (!databaseSamples.isEmpty() && databaseSamples.peek().timestamp < cutoff) {
            databaseSamples.poll();
        }
        while (!webSamples.isEmpty() && webSamples.peek().timestamp < cutoff) {
            webSamples.poll();
        }
    }

    private record Sample(long timestamp, long durationNanos) { }
    private record WebSample(long timestamp, long durationNanos, String endpoint, int statusCode) { }

    public record BenchmarkSnapshot(long generatedAt,
                                    long databaseOperations,
                                    double averageDatabaseMillis,
                                    long databaseFailures,
                                    long webRequests,
                                    double averageWebMillis,
                                    long discordSuccess,
                                    long discordFailures,
                                    int proxyQueueDepth,
                                    long scheduledExecutions,
                                    int pendingSchedules) { }
}
