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
        createAppealsTable();

        // Create indexes for performance
        createIndexes();
    }

    private void createBansTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_bans (
                id INTEGER %s,
                punishment_id VARCHAR(8),
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
                punishment_id VARCHAR(8),
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
                punishment_id VARCHAR(8),
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
        // **THE FIX:** The schema for kicks is now correct and simplified.
        String sql = """
            CREATE TABLE IF NOT EXISTS litebans_kicks (
                id INTEGER %s,
                punishment_id VARCHAR(8),
                uuid VARCHAR(36) NOT NULL,
                ip VARCHAR(45),
                reason VARCHAR(2048),
                banned_by_uuid VARCHAR(36),
                banned_by_name VARCHAR(16),
                time BIGINT NOT NULL,
                server_origin VARCHAR(32),
                silent BOOLEAN NOT NULL DEFAULT FALSE
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

    private void createAppealsTable() throws SQLException {
        String sql = """
            CREATE TABLE IF NOT EXISTS controlbans_appeals (
                id INTEGER %s,
                target_uuid VARCHAR(36) NOT NULL,
                punishment_id VARCHAR(8) NOT NULL,
                message TEXT NOT NULL,
                created_at BIGINT NOT NULL
            )
        """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createIndexes() throws SQLException {
        String[] indexes = {
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_bans_punishment_id ON litebans_bans(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_bans_uuid ON litebans_bans(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_bans_active ON litebans_bans(active)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_mutes_punishment_id ON litebans_mutes(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_mutes_uuid ON litebans_mutes(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_mutes_active ON litebans_mutes(active)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_warnings_punishment_id ON litebans_warnings(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_warnings_uuid ON litebans_warnings(uuid)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_kicks_punishment_id ON litebans_kicks(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_kicks_uuid ON litebans_kicks(uuid)",

                "CREATE INDEX IF NOT EXISTS idx_history_uuid ON litebans_history(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_history_ip ON litebans_history(ip)",

                "CREATE INDEX IF NOT EXISTS idx_appeals_target ON controlbans_appeals(target_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_appeals_created ON controlbans_appeals(created_at)"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String index : indexes) {
                // Ignore errors for existing indexes, simple approach
                try {
                    stmt.execute(index);
                } catch (SQLException e) {
                    // This can happen if the index already exists, which is fine.
                }
            }
        }
    }

    private String getPrimaryKeyDefinition() {
        return switch (databaseType) {
            case "mysql", "mariadb" -> "PRIMARY KEY AUTO_INCREMENT";
            case "postgresql" -> "PRIMARY KEY"; // Use SERIAL or IDENTITY in the column def
            default -> "PRIMARY KEY AUTOINCREMENT"; // SQLite
        };
    }
}