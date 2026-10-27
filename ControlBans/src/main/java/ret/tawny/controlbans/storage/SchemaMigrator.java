package ret.tawny.controlbans.storage;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.logging.Logger;

public class SchemaMigrator {

    private static final Logger LOGGER = Logger.getLogger("ControlBans-Schema");

    private final Connection connection;
    private final String databaseType;

    public SchemaMigrator(Connection connection, String databaseType) {
        this.connection = connection;
        this.databaseType = databaseType;
    }

    public void createLiteBansCompatibleSchema() throws SQLException {
        createBansTable();
        createMutesTable();
        createKicksTable();
        createWarningsTable();
        createHistoryTable();
        createConfigTable();
        createAppealsTable();
        createVoiceMutesTable();
        createReportsTable();
        createNotesTable();
        createScheduledPunishmentsTable();

        // Idempotent ALTER migrations for DBs created by older versions.
        ensureColumns();

        createIndexes();
    }

    private void ensureColumns() {
        ensureColumn("controlbans_bans", "name", columnDef("VARCHAR(32)", "VARCHAR(32)"));
        ensureColumn("controlbans_bans", "template", columnDef("VARCHAR(64)", "VARCHAR(64)"));
        ensureColumn("controlbans_bans", "server_origin", columnDef("VARCHAR(32)", "VARCHAR(32)"));
        ensureColumn("controlbans_bans", "ipban", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_bans", "silent", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_mutes", "name", columnDef("VARCHAR(32)", "VARCHAR(32)"));
        ensureColumn("controlbans_mutes", "template", columnDef("VARCHAR(64)", "VARCHAR(64)"));
        ensureColumn("controlbans_mutes", "server_origin", columnDef("VARCHAR(32)", "VARCHAR(32)"));
        ensureColumn("controlbans_mutes", "ipban", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_mutes", "silent", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_voicemutes", "silent", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_warnings", "silent", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_kicks", "silent", columnDef("BOOLEAN DEFAULT FALSE", "BOOLEAN DEFAULT FALSE"));
        ensureColumn("controlbans_reports", "category", columnDef("VARCHAR(64)", "VARCHAR(64)"));
        ensureColumn("controlbans_reports", "target_uuid", columnDef("VARCHAR(36)", "VARCHAR(36)"));
        ensureColumn("controlbans_appeals", "status", columnDef("VARCHAR(16) DEFAULT 'PENDING'", "VARCHAR(16) DEFAULT 'PENDING'"));
    }

    private String columnDef(String sqliteDef, String mysqlDef) {
        return ("mysql".equals(databaseType) || "mariadb".equals(databaseType)) ? mysqlDef : sqliteDef;
    }

    private void ensureColumn(String table, String column, String definition) {
        try {
            java.sql.DatabaseMetaData meta = connection.getMetaData();
            try (java.sql.ResultSet rs = meta.getColumns(null, null, table, column)) {
                if (rs.next()) return;
            }
            // Fallback case-insensitive scan for drivers with case quirks.
            try (java.sql.ResultSet rs = meta.getColumns(null, null, table, null)) {
                while (rs.next()) {
                    String found = rs.getString("COLUMN_NAME");
                    if (found != null && found.equalsIgnoreCase(column)) return;
                }
            }
            try (Statement stmt = connection.createStatement()) {
                stmt.execute("ALTER TABLE " + table + " ADD COLUMN " + column + " " + definition);
                LOGGER.info("[Schema] Added missing column " + table + "." + column);
            }
        } catch (SQLException e) {
            LOGGER.fine("[Schema] ensureColumn " + table + "." + column + " skipped: " + e.getMessage());
        }
    }

    private void createScheduledPunishmentsTable() throws SQLException {
    String sql = """
                CREATE TABLE IF NOT EXISTS controlbans_scheduled (
                    id INTEGER %s,
                    schedule_id VARCHAR(64) NOT NULL,
                    action VARCHAR(20),
                    type VARCHAR(20),
                    target_name VARCHAR(32),
                    target_ip VARCHAR(45),
                    duration_seconds BIGINT,
                    reason VARCHAR(2048),
                    staff_uuid VARCHAR(36),
                    staff_name VARCHAR(32),
                    execute_at BIGINT NOT NULL,
                    created_at BIGINT NOT NULL,
                    silent BOOLEAN NOT NULL DEFAULT FALSE,
                    server_origin VARCHAR(32)
                )
            """.formatted(getPrimaryKeyDefinition());

    try (Statement stmt = connection.createStatement()) {
        stmt.execute(sql);
    }

    boolean ifNotExists = "sqlite".equals(databaseType);
    String uniquePrefix = ifNotExists ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE UNIQUE INDEX ";
    String prefix = ifNotExists ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ";

    try (Statement stmt = connection.createStatement()) {
        try { stmt.execute(uniquePrefix + "idx_scheduled_schedule_id ON controlbans_scheduled(schedule_id)"); } catch (SQLException e) { LOGGER.fine("Index not created: idx_scheduled_schedule_id ON controlbans_scheduled(schedule_id) â€” " + e.getMessage()); }
        try { stmt.execute(prefix + "idx_scheduled_execute_at ON controlbans_scheduled(execute_at)"); } catch (SQLException e) { LOGGER.fine("Index not created: idx_scheduled_execute_at ON controlbans_scheduled(execute_at) â€” " + e.getMessage()); }
    }
}

    private void createBansTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_bans (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45),
                        reason VARCHAR(2048),
                        banned_by_uuid VARCHAR(36),
                        banned_by_name VARCHAR(32),
                        removed_by_uuid VARCHAR(36),
                        removed_by_name VARCHAR(32),
                        removed_by_date BIGINT,
                        time BIGINT NOT NULL,
                        until BIGINT NOT NULL,
                        template VARCHAR(128),
                        server_scope VARCHAR(32),
                        server_origin VARCHAR(32),
                        silent BOOLEAN NOT NULL DEFAULT FALSE,
                        ipban BOOLEAN NOT NULL DEFAULT FALSE,
                        active BOOLEAN NOT NULL DEFAULT TRUE
                    )
                """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createVoiceMutesTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_voicemutes (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        uuid VARCHAR(36) NOT NULL,
                        reason VARCHAR(2048),
                        banned_by_uuid VARCHAR(36),
                        banned_by_name VARCHAR(32),
                        removed_by_uuid VARCHAR(36),
                        removed_by_name VARCHAR(32),
                        removed_by_date BIGINT,
                        time BIGINT NOT NULL,
                        until BIGINT NOT NULL,
                        server_origin VARCHAR(32),
                        silent BOOLEAN NOT NULL DEFAULT FALSE,
                        active BOOLEAN NOT NULL DEFAULT TRUE
                    )
                """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createMutesTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_mutes (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45),
                        reason VARCHAR(2048),
                        banned_by_uuid VARCHAR(36),
                        banned_by_name VARCHAR(32),
                        removed_by_uuid VARCHAR(36),
                        removed_by_name VARCHAR(32),
                        removed_by_date BIGINT,
                        time BIGINT NOT NULL,
                        until BIGINT NOT NULL,
                        template VARCHAR(128),
                        server_scope VARCHAR(32),
                        server_origin VARCHAR(32),
                        silent BOOLEAN NOT NULL DEFAULT FALSE,
                        ipban BOOLEAN NOT NULL DEFAULT FALSE,
                        active BOOLEAN NOT NULL DEFAULT TRUE
                    )
                """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createWarningsTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_warnings (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45),
                        reason VARCHAR(2048),
                        banned_by_uuid VARCHAR(36),
                        banned_by_name VARCHAR(32),
                        removed_by_uuid VARCHAR(36),
                        removed_by_name VARCHAR(32),
                        removed_by_date BIGINT,
                        time BIGINT NOT NULL,
                        until BIGINT NOT NULL,
                        template VARCHAR(128),
                        server_scope VARCHAR(32),
                        server_origin VARCHAR(32),
                        silent BOOLEAN NOT NULL DEFAULT FALSE,
                        ipban BOOLEAN NOT NULL DEFAULT FALSE,
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
                    CREATE TABLE IF NOT EXISTS controlbans_kicks (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        uuid VARCHAR(36) NOT NULL,
                        ip VARCHAR(45),
                        reason VARCHAR(2048),
                        banned_by_uuid VARCHAR(36),
                        banned_by_name VARCHAR(32),
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
                    CREATE TABLE IF NOT EXISTS controlbans_history (
                        id INTEGER %s,
                        date BIGINT NOT NULL,
                        name VARCHAR(32),
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
                    CREATE TABLE IF NOT EXISTS controlbans_config (
                        id INTEGER %s,
                        version INTEGER NOT NULL,
                        build VARCHAR(64),
                        date BIGINT NOT NULL
                    )
                """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }

        String insertSql;
        if ("mysql".equals(databaseType) || "mariadb".equals(databaseType)) {
            insertSql = "INSERT IGNORE INTO controlbans_config (version, build, date) VALUES (?, ?, ?)";
        } else {
            insertSql = "INSERT INTO controlbans_config (version, build, date) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        }

        try (var stmt = connection.prepareStatement(insertSql)) {
            stmt.setInt(1, 1);
            stmt.setString(2, "ControlBans-6.3.1");
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private void createReportsTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_reports (
                        id VARCHAR(36) PRIMARY KEY,
                        reporter_uuid VARCHAR(36) NOT NULL,
                        reporter_name VARCHAR(32),
                        target_name VARCHAR(32) NOT NULL,
                        reason VARCHAR(2048),
                        category VARCHAR(64),
                        target_uuid VARCHAR(36),
                        time BIGINT NOT NULL,
                        status VARCHAR(32) NOT NULL DEFAULT 'PENDING'
                    )
                """;
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createNotesTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_notes (
                        id INTEGER %s,
                        uuid VARCHAR(36) NOT NULL,
                        staff_name VARCHAR(32),
                        note_text TEXT NOT NULL,
                        time BIGINT NOT NULL
                    )
                """.formatted(getPrimaryKeyDefinition());
        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createAppealsTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_appeals (
                        id INTEGER %s,
                        punishment_id VARCHAR(8),
                        target_uuid VARCHAR(36) NOT NULL,
                        message TEXT NOT NULL,
                        created_at BIGINT NOT NULL
                    )
                """.formatted(getPrimaryKeyDefinition());

        try (Statement stmt = connection.createStatement()) {
            stmt.execute(sql);
        }
    }

    private void createIndexes() throws SQLException {
        boolean ifNotExists = "sqlite".equals(databaseType);
        String prefix = ifNotExists ? "CREATE INDEX IF NOT EXISTS " : "CREATE INDEX ";
        String uniquePrefix = ifNotExists ? "CREATE UNIQUE INDEX IF NOT EXISTS " : "CREATE UNIQUE INDEX ";

        String[] indexes = {
                uniquePrefix + "idx_bans_punishment_id ON controlbans_bans(punishment_id)",
                prefix + "idx_bans_uuid ON controlbans_bans(uuid)",
                prefix + "idx_bans_active ON controlbans_bans(active)",
                // Elite: index on until for janitor purge & active-ban expiry check (fixes full table scan)
                prefix + "idx_bans_until ON controlbans_bans(until)",
                prefix + "idx_bans_ip ON controlbans_bans(ip)",

                uniquePrefix + "idx_mutes_punishment_id ON controlbans_mutes(punishment_id)",
                prefix + "idx_mutes_uuid ON controlbans_mutes(uuid)",
                prefix + "idx_mutes_active ON controlbans_mutes(active)",
                prefix + "idx_mutes_until ON controlbans_mutes(until)",
                prefix + "idx_mutes_ip ON controlbans_mutes(ip)",

                uniquePrefix + "idx_warnings_punishment_id ON controlbans_warnings(punishment_id)",
                prefix + "idx_warnings_uuid ON controlbans_warnings(uuid)",
                prefix + "idx_warnings_until ON controlbans_warnings(until)",

                uniquePrefix + "idx_kicks_punishment_id ON controlbans_kicks(punishment_id)",
                prefix + "idx_kicks_uuid ON controlbans_kicks(uuid)",
                prefix + "idx_kicks_time ON controlbans_kicks(time)",

                prefix + "idx_history_uuid ON controlbans_history(uuid)",
                prefix + "idx_history_ip ON controlbans_history(ip)",
                prefix + "idx_history_name ON controlbans_history(name)",
                uniquePrefix + "idx_history_uuid_ip ON controlbans_history(uuid, ip)",

                prefix + "idx_appeals_target ON controlbans_appeals(target_uuid)",
                prefix + "idx_appeals_created ON controlbans_appeals(created_at)",

                uniquePrefix + "idx_voicemutes_punishment_id ON controlbans_voicemutes(punishment_id)",
                prefix + "idx_voicemutes_uuid ON controlbans_voicemutes(uuid)",
                prefix + "idx_voicemutes_active ON controlbans_voicemutes(active)",
                prefix + "idx_voicemutes_until ON controlbans_voicemutes(until)",

                prefix + "idx_reports_reporter ON controlbans_reports(reporter_uuid)",
                prefix + "idx_reports_target ON controlbans_reports(target_name)",
                prefix + "idx_reports_status_time ON controlbans_reports(status, time)",
                prefix + "idx_notes_uuid ON controlbans_notes(uuid)"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String index : indexes) {
                try {
                    stmt.execute(index);
                } catch (SQLException e) {
                    // Failures are usually "index already exists" on MySQL (no IF NOT EXISTS) â€” not fatal
                    LOGGER.fine("Index statement skipped/failed: " + index + " â€” " + e.getMessage());
                }
            }
        }
    }

    private String getPrimaryKeyDefinition() {
        return switch (databaseType) {
            case "mysql", "mariadb" -> "PRIMARY KEY AUTO_INCREMENT";
            case "sqlite" -> "PRIMARY KEY AUTOINCREMENT";
            default -> "PRIMARY KEY AUTO_INCREMENT";
        };
    }
}