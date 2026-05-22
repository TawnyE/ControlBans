package ret.tawny.controlbans.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.dao.PunishmentDao;

import java.io.File;
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
    private final PunishmentDao punishmentDao;
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
                plugin.getLogger().log(Level.WARNING, "Database task queue full (500 tasks). Task discarded to prevent main-thread freeze. Consider increasing pool size or optimizing queries.");
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
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void startJanitor() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            int banDays = plugin.getConfig().getInt("database.janitor.ban-retention-days", 30);
            int kickDays = plugin.getConfig().getInt("database.janitor.kick-retention-days", 90);
            int warnDays = plugin.getConfig().getInt("database.janitor.warning-retention-days", 90);
            int reportDays = plugin.getConfig().getInt("database.janitor.report-retention-days", 60);

            long banRetention = System.currentTimeMillis() - (banDays * 24L * 60 * 60 * 1000);
            long kickRetention = System.currentTimeMillis() - (kickDays * 24L * 60 * 60 * 1000);
            long warningRetention = System.currentTimeMillis() - (warnDays * 24L * 60 * 60 * 1000);
            long reportRetention = System.currentTimeMillis() - (reportDays * 24L * 60 * 60 * 1000);

            try (Connection conn = getConnection(); Statement stmt = conn.createStatement()) {
                int deletedBans = 0;
                for (String table : new String[]{"controlbans_bans", "controlbans_mutes", "controlbans_voicemutes"}) {
                    deletedBans += stmt.executeUpdate("DELETE FROM " + table + " WHERE until > 0 AND until < " + banRetention);
                }
                if (deletedBans > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedBans + " ancient temporary punishments.");

                int deletedKicks = stmt.executeUpdate("DELETE FROM controlbans_kicks WHERE time < " + kickRetention);
                if (deletedKicks > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedKicks + " old kicks.");

                int deletedWarnings = stmt.executeUpdate("DELETE FROM controlbans_warnings WHERE (until > 0 AND until < " + warningRetention + ") OR (active = FALSE AND time < " + warningRetention + ")");
                if (deletedWarnings > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedWarnings + " old warnings.");

                int deletedReports = stmt.executeUpdate("DELETE FROM controlbans_reports WHERE (status = 'RESOLVED' OR status = 'DISMISSED') AND time < " + reportRetention);
                if (deletedReports > 0) plugin.getLogger().info("[Database Janitor] Purged " + deletedReports + " old resolved/dismissed reports.");
            } catch (SQLException e) {
                plugin.getLogger().warning("[Database Janitor] Cleanup failed: " + e.getMessage());
            }
        }, 20L * 60 * 5, 20L * 60 * 60 * 24);
    }

    private void setupDataSource() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }

        HikariConfig hikariConfig = new HikariConfig();
        String databaseType = config.getDatabaseType().toLowerCase();

        switch (databaseType) {
            case "mysql", "mariadb" -> setupMySQLDataSource(hikariConfig);
            case "postgresql" -> setupPostgreSQLDataSource(hikariConfig);
            case "sqlite" -> setupSQLiteDataSource(hikariConfig);
            case "h2" -> setupH2DataSource(hikariConfig);
            default -> {
                plugin.getLogger().warning("Invalid database type '" + databaseType + "', defaulting to SQLite.");
                setupSQLiteDataSource(hikariConfig);
            }
        }

        hikariConfig.setMaximumPoolSize(config.getPoolMaximumSize());
        hikariConfig.setMinimumIdle(config.getPoolMinimumIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());
        hikariConfig.setValidationTimeout(5000);

        if ("sqlite".equals(databaseType)) {
            int desiredPoolSize = config.getPoolMaximumSize();
            hikariConfig.setMaximumPoolSize(Math.max(1, Math.min(desiredPoolSize, 3)));
        }

        dataSource = new HikariDataSource(hikariConfig);
        plugin.getLogger().info("Database connection pool initialized for " + databaseType.toUpperCase());
    }

    private void setupMySQLDataSource(HikariConfig hikariConfig) {
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setJdbcUrl(
                String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        config.getDatabaseHost(), config.getDatabasePort(), config.getDatabaseName()));
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
        hikariConfig.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
    }

    private void setupPostgreSQLDataSource(HikariConfig hikariConfig) {
        hikariConfig.setDriverClassName("org.postgresql.Driver");
        hikariConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                config.getDatabaseHost(), config.getDatabasePort(), config.getDatabaseName()));
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());
    }

    private void setupSQLiteDataSource(HikariConfig hikariConfig) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        String dbPath = new File(dataFolder, config.getSqliteFile()).getAbsolutePath();
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + dbPath);
        hikariConfig.addDataSourceProperty("journal_mode", "WAL");
        hikariConfig.addDataSourceProperty("synchronous", "NORMAL");
    }

    private void setupH2DataSource(HikariConfig hikariConfig) {
        File dataFolder = plugin.getDataFolder();
        if (!dataFolder.exists()) {
            dataFolder.mkdirs();
        }
        String dbPath = new File(dataFolder, config.getH2File()).getAbsolutePath();
        if (dbPath.endsWith(".h2.db")) {
            dbPath = dbPath.substring(0, dbPath.length() - 6);
        }
        hikariConfig.setDriverClassName("org.h2.Driver");
        hikariConfig.setJdbcUrl("jdbc:h2:" + dbPath + ";MODE=MySQL;DATABASE_TO_LOWER=TRUE");
        hikariConfig.setUsername("sa");
        hikariConfig.setPassword("");
        hikariConfig.addDataSourceProperty("cachePrepStmts", "true");
        hikariConfig.addDataSourceProperty("prepStmtCacheSize", "250");
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
    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getActiveIpBan(conn, ip));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip) {
        return executeQueryAsync(conn -> punishmentDao.getActiveIpMute(conn, ip));
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
    public CompletableFuture<Void> importPunishment(Punishment punishment) {
        return executeAsync(conn -> {
            switch (punishment.getType()) {
                case BAN, TEMPBAN, IPBAN -> punishmentDao.insertBan(conn, punishment);
                case MUTE, TEMPMUTE -> punishmentDao.insertMute(conn, punishment);
                case KICK -> punishmentDao.insertKick(conn, punishment);
                case WARN -> punishmentDao.insertWarning(conn, punishment);
                case VOICEMUTE, TEMPVOICEMUTE -> punishmentDao.insertVoiceMute(conn, punishment);
            }
        });
    }

    @Override
    public CompletableFuture<Void> insertReport(String id, UUID reporterUuid, String reporterName, String targetName, String reason, long timestamp, String status) {
        return executeAsync(conn -> punishmentDao.insertReport(conn, id, reporterUuid, reporterName, targetName, reason, timestamp, status));
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
            try (Connection connection = getConnection()) {
                operation.execute(connection);
            } catch (SQLException e) {
                throw new RuntimeException("Database operation failed", e);
            }
        }, asyncExecutor);
    }

    public <T> CompletableFuture<T> executeQueryAsync(DatabaseQuery<T> query) {
        return CompletableFuture.supplyAsync(() -> {
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
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shut down");
        }

        if (asyncExecutor != null) {
            asyncExecutor.shutdownNow();
            try {
                if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    plugin.getLogger().warning("Async database executor did not shut down cleanly.");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
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