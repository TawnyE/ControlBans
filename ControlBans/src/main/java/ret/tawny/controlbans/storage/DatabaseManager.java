package ret.tawny.controlbans.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.storage.dao.PunishmentDao;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class DatabaseManager implements StorageInterface {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private HikariDataSource dataSource;
    private ExecutorService asyncExecutor;
    private ExecutorService fallbackExecutor;
    private final PunishmentDao punishmentDao;
    private volatile boolean shutdown;
    private static final AtomicInteger EXECUTOR_THREAD_ID = new AtomicInteger();

    public DatabaseManager(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        this.punishmentDao = new PunishmentDao();
        initializeExecutor();
    }

    private void initializeExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ControlBans-DB-" + EXECUTOR_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.asyncExecutor = new java.util.concurrent.ThreadPoolExecutor(
            2,
            8,
            60L, TimeUnit.SECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(500),
            threadFactory,
            (runnable, executor) -> {
                if (plugin.getConfigManager().isDebugLoggingEnabled()) {
                    plugin.getLogger().fine("Database task queue full (500 tasks); diverting task to fallback executor to avoid data loss.");
                } else {
                    plugin.getLogger().log(Level.WARNING, "Database task queue full (500 tasks); diverting task to fallback executor to avoid data loss. Consider increasing database.pool.maximum-pool-size or reducing punishment burst.");
                }
                try {
                    fallbackExecutor.execute(runnable);
                } catch (java.util.concurrent.RejectedExecutionException e) {
                    plugin.getLogger().log(Level.SEVERE, "Fallback executor rejected DB task — possible data loss", e);
                }
            }
        );
        // Bounded fallback to avoid OOM under sustained burst (500 cap + CallerRuns-like backpressure)
        this.fallbackExecutor = new java.util.concurrent.ThreadPoolExecutor(
            1, 1,
            0L, TimeUnit.MILLISECONDS,
            new java.util.concurrent.LinkedBlockingQueue<>(500),
            runnable -> {
                Thread thread = new Thread(runnable, "ControlBans-DB-Fallback");
                thread.setDaemon(true);
                return thread;
            },
            (runnable, exec) -> {
                // Never run JDBC on the caller thread for the primary path, but as a
                // last resort (fallback also full) run on a dedicated overflow thread
                // rather than dropping the task and hanging its future forever.
                try {
                    if (!exec.getQueue().offer(runnable, 500, TimeUnit.MILLISECONDS)) {
                        plugin.getLogger().log(Level.SEVERE, "Fallback DB queue full after 500ms wait — running on overflow thread "
                                + runnable.getClass().getName() + ". Increase pool size or reduce burst.");
                        Thread overflow = new Thread(runnable, "ControlBans-DB-Overflow");
                        overflow.setDaemon(true);
                        overflow.start();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    plugin.getLogger().log(Level.SEVERE, "Interrupted while enqueueing DB task — running on overflow thread "
                            + runnable.getClass().getName(), e);
                    Thread overflow = new Thread(runnable, "ControlBans-DB-Overflow");
                    overflow.setDaemon(true);
                    overflow.start();
                }
            }
        );
    }

    @Override
    public void initialize() {
        try {
            setupDataSource();
            createTables();
            startJanitor();
            plugin.getLogger().info("SQL Database initialized successfully");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "");
            plugin.getLogger().log(Level.WARNING, "╔══════════════════════════════════════════════════════════════╗");
            plugin.getLogger().log(Level.WARNING, "║  ControlBans — Database Connection Failed                   ║");
            plugin.getLogger().log(Level.WARNING, "╠══════════════════════════════════════════════════════════════╣");
            plugin.getLogger().log(Level.WARNING, "║  Could not connect to the database. Check the following:   ║");
            plugin.getLogger().log(Level.WARNING, "║                                                              ║");
            plugin.getLogger().log(Level.WARNING, "║  1. Is your MySQL server running? (or SQLite file writable?)║");
            plugin.getLogger().log(Level.WARNING, "║  2. Are the host/port in config.yml correct?                ║");
            plugin.getLogger().log(Level.WARNING, "║  3. Does the database and user exist?                       ║");
            plugin.getLogger().log(Level.WARNING, "║  4. Is the database reachable from this server?             ║");
            plugin.getLogger().log(Level.WARNING, "║                                                              ║");
            plugin.getLogger().log(Level.WARNING, "║  Host: " + config.getDatabaseHost() + ":" + config.getDatabasePort() + "  Database: " + config.getDatabaseName() + "║");
            plugin.getLogger().log(Level.WARNING, "╚══════════════════════════════════════════════════════════════╝");
            plugin.getLogger().log(Level.WARNING, "");
            plugin.getLogger().log(Level.WARNING, "Database failure details: " + e.getMessage());
            throw new RuntimeException("ControlBans could not connect to the database. Verify your config.yml database settings and ensure the database server is running.", e);
        }
    }

    private void startJanitor() {
        plugin.getSchedulerAdapter().runTaskTimerAsync(() -> {
            if (shutdown || dataSource == null || dataSource.isClosed()) {
                return;
            }
            int banDays = config.getJanitorBanRetentionDays();
            int kickDays = config.getJanitorKickRetentionDays();
            int warnDays = config.getJanitorWarningRetentionDays();
            int reportDays = config.getJanitorReportRetentionDays();

            long now = System.currentTimeMillis();
            long banRetention, kickRetention, warningRetention, reportRetention;
            try {
                banRetention = now - Math.multiplyExact((long) banDays, 24L * 60 * 60 * 1000);
                kickRetention = now - Math.multiplyExact((long) kickDays, 24L * 60 * 60 * 1000);
                warningRetention = now - Math.multiplyExact((long) warnDays, 24L * 60 * 60 * 1000);
                reportRetention = now - Math.multiplyExact((long) reportDays, 24L * 60 * 60 * 1000);
            } catch (ArithmeticException e) {
                plugin.getLogger().warning("[Database Janitor] Retention overflow, skipping cycle: " + e.getMessage());
                return;
            }

            // Use PreparedStatement with parameters to avoid SQL concatenation and allow index use
            try (Connection conn = getConnection()) {
                int deletedBans = 0;
                for (String table : new String[]{"controlbans_bans", "controlbans_mutes", "controlbans_voicemutes"}) {
                    try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM " + table + " WHERE until > 0 AND until < ?")) {
                        ps.setLong(1, banRetention);
                        deletedBans += ps.executeUpdate();
                    }
                }
                if (deletedBans > 0) {
                    if (plugin.getConfigManager().isDebugLoggingEnabled()) {
                        plugin.getLogger().info("[Database Janitor] Purged " + deletedBans + " ancient temporary punishments.");
                    } else {
                        plugin.getLogger().fine("[Database Janitor] Purged " + deletedBans + " ancient temporary punishments.");
                    }
                }

                int deletedKicks;
                try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM controlbans_kicks WHERE time < ?")) {
                    ps.setLong(1, kickRetention);
                    deletedKicks = ps.executeUpdate();
                }
                if (deletedKicks > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedKicks + " old kicks.");

                int deletedWarnings;
                try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM controlbans_warnings WHERE (until > 0 AND until < ?) OR (active = FALSE AND time < ?)")) {
                    ps.setLong(1, warningRetention);
                    ps.setLong(2, warningRetention);
                    deletedWarnings = ps.executeUpdate();
                }
                if (deletedWarnings > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedWarnings + " old warnings.");

                int deletedReports;
                try (java.sql.PreparedStatement ps = conn.prepareStatement("DELETE FROM controlbans_reports WHERE (status = 'RESOLVED' OR status = 'REJECTED' OR status = 'DISMISSED') AND time < ?")) {
                    ps.setLong(1, reportRetention);
                    deletedReports = ps.executeUpdate();
                }
                if (deletedReports > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedReports + " old resolved/dismissed reports.");
            } catch (SQLException e) {
                if (!shutdown) {
                    plugin.getLogger().log(Level.WARNING, "[Database Janitor] Cleanup failed", e);
                }
            }
        }, 20L * 60 * 5, 20L * 60 * 60 * 24);
    }

    private void setupDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        HikariConfig hikariConfig = new HikariConfig();
        String databaseType = config.getDatabaseType().toLowerCase();
        boolean isSqlite = "sqlite".equals(databaseType);

        switch (databaseType) {
            case "mysql", "mariadb" -> setupMysqlDataSource(hikariConfig);
            case "sqlite" -> setupSqliteDataSource(hikariConfig);
            default -> {
                plugin.getLogger().warning("Invalid database type '" + databaseType + "', defaulting to SQLite. Valid options: sqlite, mysql, mariadb.");
                setupSqliteDataSource(hikariConfig);
                isSqlite = true;
            }
        }

        // Elite: don't override SQLite's single-connection setting (1 writer) — pooling 10 would cause SQLITE_BUSY
        if (!isSqlite) {
            int poolMaximumSize = config.getPoolMaximumSize();
            if (databaseType.equals("mysql") || databaseType.equals("mariadb")) {
                poolMaximumSize = Math.min(poolMaximumSize, 10);
            }
            hikariConfig.setMaximumPoolSize(poolMaximumSize);
            hikariConfig.setMinimumIdle(Math.min(config.getPoolMinimumIdle(), poolMaximumSize));
            hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
            hikariConfig.setIdleTimeout(config.getIdleTimeout());
            hikariConfig.setMaxLifetime(config.getMaxLifetime());
        } else {
            // SQLite: keep 1/1, but still apply validated timeouts
            hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
            hikariConfig.setIdleTimeout(config.getIdleTimeout());
            hikariConfig.setMaxLifetime(config.getMaxLifetime());
        }
        hikariConfig.setValidationTimeout(5000);

        dataSource = new HikariDataSource(hikariConfig);
        plugin.getLogger().info("Database connection pool initialized for " + databaseType.toUpperCase() + (isSqlite ? " (single connection, WAL)" : ""));
    }

    private void setupMysqlDataSource(HikariConfig hikariConfig) {
        hikariConfig.setDriverClassName(resolveDriverClass(
                "ret.tawny.controlbans.libs.mysql.cj.jdbc.Driver",
                "com.mysql.cj.jdbc.Driver"));
        hikariConfig.setJdbcUrl(
                String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC&useUnicode=true&characterEncoding=UTF-8",
                        config.getDatabaseHost(), config.getDatabasePort(), config.getDatabaseName()));
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    }

    private void setupSqliteDataSource(HikariConfig hikariConfig) {
        // SQLite is never relocated — org.sqlite core loads native libs and breaks if renamed.
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        String sqliteFile = config.getDatabaseSqliteFile();
        // Honor absolute paths as documented in config.yml instead of blindly
        // joining them onto the data folder (which produces garbage like
        // "<dataFolder>/C:\Users\...\file.db").
        java.io.File dbFile = new java.io.File(sqliteFile);
        if (!dbFile.isAbsolute()) {
            dbFile = new java.io.File(plugin.getDataFolder(), sqliteFile);
        }
        if (dbFile.getParentFile() != null) {
            dbFile.getParentFile().mkdirs();
        }
        String absolutePath = dbFile.getAbsolutePath();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + absolutePath);
        hikariConfig.setMaximumPoolSize(1);
        hikariConfig.setMinimumIdle(1);
        hikariConfig.setConnectionInitSql("PRAGMA journal_mode=WAL; PRAGMA busy_timeout=5000; PRAGMA synchronous=NORMAL;");
    }

    private String resolveDriverClass(String shaded, String unshaded) {
        try {
            Class.forName(shaded);
            return shaded;
        } catch (ClassNotFoundException ignored) {}
        return unshaded;
    }

    private void createTables() {
        try (Connection connection = getConnection()) {
            SchemaMigrator migrator = new SchemaMigrator(connection, getDatabaseType());
            migrator.createLiteBansCompatibleSchema();
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create database tables", e);
        }
    }

    public Connection getConnection() throws SQLException {
        return dataSource.getConnection();
    }

    @Override
    public CompletableFuture<Void> insertBan(Punishment punishment) {
        return executeAsync(conn -> punishmentDao.insertBan(conn, punishment));
    }

    @Override
    public CompletableFuture<Void> insertMute(Punishment punishment) {
        return executeAsync(conn -> punishmentDao.insertMute(conn, punishment));
    }

    @Override
    public CompletableFuture<Void> insertWarning(Punishment punishment) {
        return executeAsync(conn -> punishmentDao.insertWarning(conn, punishment));
    }

    @Override
    public CompletableFuture<Void> insertKick(Punishment punishment) {
        return executeAsync(conn -> punishmentDao.insertKick(conn, punishment));
    }

    @Override
    public CompletableFuture<Void> insertVoiceMute(Punishment punishment) {
        return executeAsync(conn -> punishmentDao.insertVoiceMute(conn, punishment));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getActiveBan(conn, uuid));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getActiveMute(conn, uuid));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveVoiceMute(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getActiveVoiceMute(conn, uuid));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveBanByName(String name) {
        return executeQueryAsync(conn -> punishmentDao.getActiveBanByName(conn, name));
    }

    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getActiveIpBan(conn, ip));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getActiveIpMute(conn, ip));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveIpBanById(String punishmentId) {
        return executeQueryAsync(conn -> punishmentDao.getActiveIpBanById(conn, punishmentId));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getPunishmentById(String id) {
        return executeQueryAsync(conn -> punishmentDao.getPunishmentById(conn, id));
    }

    @Override
    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) {
        return executeQueryAsync(conn -> punishmentDao.getPunishmentHistory(conn, uuid, limit));
    }

    @Override
    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) {
        return executeQueryAsync(conn -> punishmentDao.getRecentPunishments(conn, limit));
    }

    @Override
    public CompletableFuture<List<Punishment>> getAllPunishments() {
        return executeQueryAsync(punishmentDao::getAllPunishments);
    }

    @Override
    public CompletableFuture<Void> removeBan(UUID uuid, UUID removedBy, String removedByName) {
        return executeAsync(conn -> punishmentDao.removeBan(conn, uuid, removedBy, removedByName));
    }

    @Override
    public CompletableFuture<Void> removeMute(UUID uuid, UUID removedBy, String removedByName) {
        return executeAsync(conn -> punishmentDao.removeMute(conn, uuid, removedBy, removedByName));
    }

    @Override
    public CompletableFuture<Void> removeVoiceMute(UUID uuid, UUID removedBy, String removedByName) {
        return executeAsync(conn -> punishmentDao.removeVoiceMute(conn, uuid, removedBy, removedByName));
    }

    @Override
    public CompletableFuture<Void> removeIpBan(String ip, UUID removedBy, String removedByName) {
        return executeAsync(conn -> punishmentDao.removeIpBan(conn, ip, removedBy, removedByName));
    }

    @Override
    public CompletableFuture<Void> removeIpMute(String ip, UUID removedBy, String removedByName) {
        return executeAsync(conn -> punishmentDao.removeIpMute(conn, ip, removedBy, removedByName));
    }

    @Override
    public CompletableFuture<Void> recordHistory(UUID uuid, String name, String ip) {
        return executeAsync(conn -> punishmentDao.recordHistory(conn, uuid, name, ip));
    }

    @Override
    public CompletableFuture<Void> clearAllData() {
        return executeAsync(punishmentDao::clearAllData);
    }

    @Override
    public CompletableFuture<Void> clearPlayerData(UUID uuid) {
        return executeAsync(conn -> punishmentDao.clearPlayerData(conn, uuid));
    }

    @Override
    public CompletableFuture<String> getLastIpForUuid(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getLastIpForUuid(conn, uuid));
    }

    @Override
    public CompletableFuture<String> getLastKnownName(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getLastKnownName(conn, uuid));
    }

    @Override
    public CompletableFuture<UUID> getUuidByName(String name) {
        return executeQueryAsync(conn -> punishmentDao.getUuidByName(conn, name));
    }

    @Override
    public CompletableFuture<List<String>> getNamesStartingWith(String prefix) {
        return executeQueryAsync(conn -> punishmentDao.getNamesStartingWith(conn, prefix));
    }

    @Override
    public CompletableFuture<Set<String>> getIpsForUuid(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getIpsForUuid(conn, uuid));
    }

    @Override
    public CompletableFuture<Set<UUID>> getUuidsOnIp(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getUuidsOnIp(conn, ip));
    }

    @Override
    public CompletableFuture<Integer> getUserCountOnIp(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getUserCountOnIp(conn, ip));
    }

    @Override
    public CompletableFuture<Void> addAppeal(String punishmentId, UUID uuid, String message, long timestamp) {
        return executeAsync(conn -> punishmentDao.addAppeal(conn, punishmentId, uuid, message, timestamp));
    }

    @Override
    public CompletableFuture<Long> getLastAppealTime(UUID uuid) {
        return executeQueryAsync(conn -> punishmentDao.getLastAppealTime(conn, uuid));
    }

    @Override
    public CompletableFuture<Integer> getAppealCount(UUID uuid, long sinceTimestamp) {
        return executeQueryAsync(conn -> punishmentDao.getAppealCount(conn, uuid, sinceTimestamp));
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.AppealService.Appeal>> getAppeals() {
        return executeQueryAsync(punishmentDao::getAppeals);
    }

    @Override
    public CompletableFuture<Boolean> updateAppealStatus(int appealId, String status) {
        return executeQueryAsync(conn -> punishmentDao.updateAppealStatus(conn, appealId, status));
    }

    @Override
    public CompletableFuture<Void> importPunishment(Punishment punishment) {
        return executeAsync(conn -> {
            switch (punishment.getType()) {
                case BAN, TEMPBAN, IPBAN, TEMPIPBAN -> punishmentDao.insertBan(conn, punishment);
                case MUTE, TEMPMUTE, IPMUTE, TEMPIPMUTE -> punishmentDao.insertMute(conn, punishment);
                case KICK -> punishmentDao.insertKick(conn, punishment);
                case WARN -> punishmentDao.insertWarning(conn, punishment);
                case VOICEMUTE, TEMPVOICEMUTE -> punishmentDao.insertVoiceMute(conn, punishment);
            }
        });
    }

    @Override
    public CompletableFuture<Void> saveScheduledPunishment(ScheduledPunishment scheduled) {
        return executeAsync(conn -> punishmentDao.insertScheduled(conn, scheduled));
    }

    @Override
    public CompletableFuture<Optional<ScheduledPunishment>> getScheduledPunishment(String scheduleId) {
        return executeQueryAsync(conn -> punishmentDao.getScheduled(conn, scheduleId));
    }

    @Override
    public CompletableFuture<List<ScheduledPunishment>> getAllScheduledPunishments() {
        return executeQueryAsync(punishmentDao::getAllScheduled);
    }

    @Override
    public CompletableFuture<List<ScheduledPunishment>> getDueScheduledPunishments(long atOrBefore) {
        return executeQueryAsync(conn -> punishmentDao.getDueScheduled(conn, atOrBefore));
    }

    @Override
    public CompletableFuture<Void> removeScheduledPunishment(String scheduleId) {
        return executeAsync(conn -> punishmentDao.removeScheduled(conn, scheduleId));
    }

    @Override
    public CompletableFuture<Void> removeScheduledPunishmentsForTarget(UUID targetUuid) {
        return executeAsync(conn -> punishmentDao.removeScheduledForTarget(conn, targetUuid));
    }

    @Override
    public CompletableFuture<Void> insertReport(String id, UUID reporterUuid, String reporterName, String targetName, UUID targetUuid, String reason, String category, long timestamp, String status) {
        return executeAsync(conn -> punishmentDao.insertReport(conn, id, reporterUuid, reporterName, targetName, targetUuid, reason, category, timestamp, status));
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReports() {
        return executeQueryAsync(punishmentDao::getReports);
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReportsByReporter(UUID reporterUuid) {
        return executeQueryAsync(conn -> punishmentDao.getReportsByReporter(conn, reporterUuid));
    }

    @Override
    public CompletableFuture<java.util.Optional<ret.tawny.controlbans.services.ReportService.Report>> getReport(String id) {
        return executeQueryAsync(conn -> punishmentDao.getReport(conn, id));
    }

    @Override
    public CompletableFuture<Boolean> deleteReport(String id) {
        return executeQueryAsync(conn -> punishmentDao.deleteReport(conn, id));
    }

    @Override
    public CompletableFuture<Boolean> updateReportStatus(String id, String status) {
        return executeQueryAsync(conn -> punishmentDao.updateReportStatus(conn, id, status));
    }

    @Override
    public CompletableFuture<Void> addNote(UUID targetUuid, String staffName, String noteText, long timestamp) {
        return executeAsync(conn -> punishmentDao.addNote(conn, targetUuid, staffName, noteText, timestamp));
    }

    @Override
    public CompletableFuture<Boolean> removeNote(UUID targetUuid, int index) {
        return executeQueryAsync(conn -> punishmentDao.removeNote(conn, targetUuid, index));
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.NoteService.PlayerNote>> getNotes(UUID targetUuid) {
        return executeQueryAsync(conn -> punishmentDao.getNotes(conn, targetUuid));
    }

    public CompletableFuture<Void> executeAsync(DatabaseOperation operation) {
        return CompletableFuture.runAsync(() -> {
            if (shutdown) {
                throw new java.util.concurrent.CancellationException("Database shutting down — operation cancelled");
            }
            try (Connection connection = getConnection()) {
                operation.execute(connection);
            } catch (SQLException e) {
                throw new RuntimeException("Database operation failed", e);
            }
        }, asyncExecutor);
    }

    public <T> CompletableFuture<T> executeQueryAsync(DatabaseQuery<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            if (shutdown) {
                throw new java.util.concurrent.CancellationException("Database shutting down — query cancelled");
            }
            try (Connection connection = getConnection()) {
                return query.execute(connection);
            } catch (SQLException e) {
                throw new RuntimeException("Database query failed", e);
            }
        }, asyncExecutor);
    }

    public String getDatabaseType() {
        return config.getDatabaseType().toLowerCase();
    }

    @Override
    public void shutdown() {
        if (shutdown) {
            return;
        }
        shutdown = true;

        if (asyncExecutor != null) {
            asyncExecutor.shutdown();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    asyncExecutor.shutdownNow();
                    if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        plugin.getLogger().warning("Async database executor did not shut down cleanly.");
                    }
                }
            } catch (InterruptedException e) {
                asyncExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (fallbackExecutor != null) {
            fallbackExecutor.shutdown();
            try {
                if (!fallbackExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                    fallbackExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                fallbackExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shut down");
        }
    }

    @FunctionalInterface
    public interface DatabaseOperation {
        void execute(Connection connection) throws SQLException;
    }

    @FunctionalInterface
    public interface DatabaseQuery<T> {
        T execute(Connection connection) throws SQLException;
    }
}