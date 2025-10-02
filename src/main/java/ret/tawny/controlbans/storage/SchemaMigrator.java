package ret.tawny.controlbans.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

public class SchemaMigrator {
    
    private final Connection connection;
    private final String databaseType;
    
    public SchemaMigrator(Connection connection, String databaseType) {
        this.connection = connection;
        this.databaseType = databaseType;
    }
    
    public void createLiteBansCompatibleSchema() throws SQLException {
        // Create tables in order of dependencies
        createBansTable();
        createMutesTable();
        createKicksTable();
        createWarningsTable();
        createHistoryTable();
        createConfigTable();
        
        // Create indexes for performance
        createIndexes();
    }
    
    private void createBansTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_bans (
                id INTEGER %s,
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45),
                reason VARCHAR(2048),
                banned_by_uuid VARCHAR(36),
                banned_by_name VARCHAR(16),
                removed_by_uuid VARCHAR(36),
                removed_by_name VARCHAR(16),
                removed_by_date BIGINT,
                time BIGINT NOT NULL,
                until BIGINT NOT NULL,
                template VARCHAR(128),
                server_scope VARCHAR(32),
                server_origin VARCHAR(32),
                silent BOOLEAN NOT NULL DEFAULT FALSE,
                ipban BOOLEAN NOT NULL DEFAULT FALSE,
                ipban_wildcard BOOLEAN NOT NULL DEFAULT FALSE,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void createMutesTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_mutes (
                id INTEGER %s,
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45),
                reason VARCHAR(2048),
                banned_by_uuid VARCHAR(36),
                banned_by_name VARCHAR(16),
                removed_by_uuid VARCHAR(36),
                removed_by_name VARCHAR(16),
                removed_by_date BIGINT,
                time BIGINT NOT NULL,
                until BIGINT NOT NULL,
                template VARCHAR(128),
                server_scope VARCHAR(32),
                server_origin VARCHAR(32),
                silent BOOLEAN NOT NULL DEFAULT FALSE,
                ipban BOOLEAN NOT NULL DEFAULT FALSE,
                ipban_wildcard BOOLEAN NOT NULL DEFAULT FALSE,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void createWarningsTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_warnings (
                id INTEGER %s,
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45),
                reason VARCHAR(2048),
                banned_by_uuid VARCHAR(36),
                banned_by_name VARCHAR(16),
                removed_by_uuid VARCHAR(36),
                removed_by_name VARCHAR(16),
                removed_by_date BIGINT,
                time BIGINT NOT NULL,
                until BIGINT NOT NULL,
                template VARCHAR(128),
                server_scope VARCHAR(32),
                server_origin VARCHAR(32),
                silent BOOLEAN NOT NULL DEFAULT FALSE,
                ipban BOOLEAN NOT NULL DEFAULT FALSE,
                ipban_wildcard BOOLEAN NOT NULL DEFAULT FALSE,
                active BOOLEAN NOT NULL DEFAULT TRUE,
                warned BOOLEAN NOT NULL DEFAULT FALSE
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void createKicksTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_kicks (
                id INTEGER %s,
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45),
                reason VARCHAR(2048),
                banned_by_uuid VARCHAR(36),
                banned_by_name VARCHAR(16),
                time BIGINT NOT NULL,
                until BIGINT NOT NULL,
                template VARCHAR(128),
                server_scope VARCHAR(32),
                server_origin VARCHAR(32),
                silent BOOLEAN NOT NULL DEFAULT FALSE,
                ipban BOOLEAN NOT NULL DEFAULT FALSE,
                ipban_wildcard BOOLEAN NOT NULL DEFAULT FALSE,
                active BOOLEAN NOT NULL DEFAULT TRUE
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void createHistoryTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_history (
                id INTEGER %s,
                date BIGINT NOT NULL,
                name VARCHAR(16),
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45)
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }
    
    private void createConfigTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_config (
                id INTEGER %s,
                version INTEGER NOT NULL,
                build VARCHAR(64),
                date BIGINT NOT NULL
            )
        """.formatted(getPrimaryKeyDefinition());
        
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
        
        // Insert initial config
        String insertSql = "INSERT OR IGNORE INTO litebans_config (version, build, date) VALUES (?, ?, ?)";
        if ("mysql".equals(databaseType) || "mariadb".equals(databaseType) || "postgresql".equals(databaseType)) {
            insertSql = "INSERT IGNORE INTO litebans_config (version, build, date) VALUES (?, ?, ?)";
        }
        
        try (var stmt = connection.prepareStatement(insertSql)) {
            stmt.setInt(1, 1);
            stmt.setString(2, "ControlBans-1.0.0");
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }
    
    private void createIndexes() throws SQLException {
        String[] indexes = {
            "CREATE INDEX IF NOT EXISTS idx_bans_uuid ON litebans_bans(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_bans_ip ON litebans_bans(ip)",
            "CREATE INDEX IF NOT EXISTS idx_bans_active ON litebans_bans(active)",
            "CREATE INDEX IF NOT EXISTS idx_bans_time ON litebans_bans(time)",
            "CREATE INDEX IF NOT EXISTS idx_bans_until ON litebans_bans(until)",
            
            "CREATE INDEX IF NOT EXISTS idx_mutes_uuid ON litebans_mutes(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_mutes_ip ON litebans_mutes(ip)",
            "CREATE INDEX IF NOT EXISTS idx_mutes_active ON litebans_mutes(active)",
            "CREATE INDEX IF NOT EXISTS idx_mutes_time ON litebans_mutes(time)",
            "CREATE INDEX IF NOT EXISTS idx_mutes_until ON litebans_mutes(until)",
            
            "CREATE INDEX IF NOT EXISTS idx_warnings_uuid ON litebans_warnings(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_warnings_active ON litebans_warnings(active)",
            "CREATE INDEX IF NOT EXISTS idx_warnings_time ON litebans_warnings(time)",
            
            "CREATE INDEX IF NOT EXISTS idx_kicks_uuid ON litebans_kicks(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_kicks_time ON litebans_kicks(time)",
            
            "CREATE INDEX IF NOT EXISTS idx_history_uuid ON litebans_history(uuid)",
            "CREATE INDEX IF NOT EXISTS idx_history_ip ON litebans_history(ip)",
            "CREATE INDEX IF NOT EXISTS idx_history_name ON litebans_history(name)",
            "CREATE INDEX IF NOT EXISTS idx_history_date ON litebans_history(date)"
        };
        
        try (Statement stmt = connection.createStatement()) {
            for (String index : indexes) {
                stmt.execute(index);
            }
        }
    }
    
    private String getPrimaryKeyDefinition() {
        return switch (databaseType) {
            case "mysql", "mariadb" -> "PRIMARY KEY AUTO_INCREMENT";
            case "postgresql" -> "PRIMARY KEY GENERATED ALWAYS AS IDENTITY";
            default -> "PRIMARY KEY AUTOINCREMENT"; // SQLite
        };
    }
}