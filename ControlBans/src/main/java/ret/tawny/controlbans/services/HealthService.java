package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.storage.DatabaseManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;

public class HealthService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final IntegrationService integrationService;
    private final ProxyService proxyService;
    private final ScheduledPunishmentService scheduledPunishmentService;
    private final BenchmarkService benchmarkService;
    private volatile HealthReport cachedReport = HealthReport.unavailable();

    public HealthService(ControlBansPlugin plugin,
                         DatabaseManager databaseManager,
                         IntegrationService integrationService,
                         ProxyService proxyService,
                         ScheduledPunishmentService scheduledPunishmentService,
                         BenchmarkService benchmarkService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.integrationService = integrationService;
        this.proxyService = proxyService;
        this.scheduledPunishmentService = scheduledPunishmentService;
        this.benchmarkService = benchmarkService;
    }

    public CompletableFuture<HealthReport> refresh() {
        return databaseManager.executeQueryAsync(this::buildReportFromConnection)
                .whenComplete((report, throwable) -> {
                    if (throwable != null) {
                        plugin.getLogger().warning("Health check failed to contact database: " + throwable.getMessage());
                        cachedReport = HealthReport.unavailable();
                    } else {
                        cachedReport = report;
                    }
                });
    }

    private HealthReport buildReportFromConnection(Connection connection) throws SQLException {
        boolean databaseHealthy = connection.isValid(2);
        boolean discordReady = integrationService.isDiscordReady();
        boolean blacklistReady = integrationService.isMcBlacklistReady();
        int proxyQueue = proxyService.getPendingMessageCount();
        int scheduled = benchmarkService.snapshot().pendingSchedules();
        return new HealthReport(databaseHealthy, discordReady, blacklistReady, proxyQueue, scheduled);
    }

    public HealthReport getCachedReport() {
        return cachedReport;
    }

    public record HealthReport(boolean database,
                               boolean discord,
                               boolean mcblacklist,
                               int proxyQueue,
                               int pendingSchedules) {
        public static HealthReport unavailable() {
            return new HealthReport(false, false, false, 0, 0);
        }
    }
}
