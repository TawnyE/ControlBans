package ret.tawny.controlbans.storage;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

public class DatabaseManager {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private HikariDataSource dataSource;
    private final ExecutorService asyncExecutor;
    private static final AtomicInteger EXECUTOR_THREAD_ID = new AtomicInteger();
    private volatile DatabaseMetricsCollector metricsCollector;

    public DatabaseManager(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ControlBans-DB-" + EXECUTOR_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.asyncExecutor = Executors.newCachedThreadPool(threadFactory);
    }

    public void initialize() {
        try {
            setupDataSource();
            createTables();
            plugin.getLogger().info("Database initialized successfully");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to initialize database", e);
            throw new RuntimeException("Database initialization failed", e);
        }
    }

    private void setupDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        String databaseType = config.getDatabaseType().toLowerCase();

        switch (databaseType) {
            case "mysql", "mariadb" -> setupMySQLDataSource(hikariConfig);
            case "postgresql" -> setupPostgreSQLDataSource(hikariConfig);
            case "sqlite" -> setupSQLiteDataSource(hikariConfig);
            default -> {
                plugin.getLogger().warning("Invalid database type '" + databaseType + "', defaulting to SQLite.");
                setupSQLiteDataSource(hikariConfig);
            }
        }

        // General connection pool configuration
        hikariConfig.setMaximumPoolSize(config.getPoolMaximumSize());
        hikariConfig.setMinimumIdle(config.getPoolMinimumIdle());
        hikariConfig.setConnectionTimeout(config.getConnectionTimeout());
        hikariConfig.setIdleTimeout(config.getIdleTimeout());
        hikariConfig.setMaxLifetime(config.getMaxLifetime());

        // **THE FIX:** If using SQLite, apply a sensible cap to the pool size *after* loading the user's config.
        if ("sqlite".equals(databaseType)) {
            int desiredPoolSize = config.getPoolMaximumSize();
            // Cap at 3, but ensure it's at least 1.
            hikariConfig.setMaximumPoolSize(Math.max(1, Math.min(desiredPoolSize, 3)));
        }

        // Connection validation
        hikariConfig.setConnectionTestQuery("SELECT 1");

        dataSource = new HikariDataSource(hikariConfig);
        plugin.getLogger().info("Database connection pool initialized for " + databaseType.toUpperCase());
    }

    private void setupMySQLDataSource(HikariConfig hikariConfig) {
        hikariConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
        hikariConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                config.getDatabaseHost(), config.getDatabasePort(), config.getDatabaseName()));
        hikariConfig.setUsername(config.getDatabaseUsername());
        hikariConfig.setPassword(config.getDatabasePassword());

        // MySQL specific properties
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

        // SQLite specific properties for performance
        hikariConfig.addDataSourceProperty("journal_mode", "WAL");
        hikariConfig.addDataSourceProperty("synchronous", "NORMAL");
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

    public CompletableFuture<Void> executeAsync(DatabaseOperation operation) {
        return CompletableFuture.runAsync(() -> {
            long start = System.nanoTime();
            boolean success = true;
            try (Connection connection = getConnection()) {
                operation.execute(connection);
            } catch (SQLException e) {
                success = false;
                throw new RuntimeException("Database operation failed", e);
            } finally {
                recordMetrics(start, success);
            }
        }, asyncExecutor);
    }

    public <T> CompletableFuture<T> executeQueryAsync(DatabaseQuery<T> query) {
        return CompletableFuture.supplyAsync(() -> {
            long start = System.nanoTime();
            boolean success = true;
            try (Connection connection = getConnection()) {
                return query.execute(connection);
            } catch (SQLException e) {
                success = false;
                throw new RuntimeException("Database query failed", e);
            } finally {
                recordMetrics(start, success);
            }
        }, asyncExecutor);
    }

    private void recordMetrics(long start, boolean success) {
        DatabaseMetricsCollector collector = this.metricsCollector;
        if (collector != null) {
            long duration = System.nanoTime() - start;
            collector.recordDatabaseOperation(duration, success);
        }
    }

    public void setMetricsCollector(DatabaseMetricsCollector collector) {
        this.metricsCollector = collector;
    }

    public String getDatabaseType() {
        return config.getDatabaseType().toLowerCase();
    }

    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            plugin.getLogger().info("Database connection pool shut down");
        }

        asyncExecutor.shutdownNow();
        try {
            if (!asyncExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                plugin.getLogger().warning("Async database executor did not shut down cleanly within 5 seconds.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            plugin.getLogger().log(Level.WARNING, "Interrupted while shutting down async database executor.", e);
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

    @FunctionalInterface
    public interface DatabaseMetricsCollector {
        void recordDatabaseOperation(long durationNanos, boolean success);
    }
}
