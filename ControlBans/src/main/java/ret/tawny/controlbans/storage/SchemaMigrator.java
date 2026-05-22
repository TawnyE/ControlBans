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

        dropUnusedColumns();

        createIndexes();
    }

    private void dropUnusedColumns() {
        try (Statement stmt = connection.createStatement()) {
            if ("sqlite".equals(databaseType)) {
                stmt.execute("ALTER TABLE controlbans_bans DROP COLUMN ipban_wildcard");
            } else {
                stmt.execute("ALTER TABLE controlbans_bans DROP COLUMN IF EXISTS ipban_wildcard");
            }
        } catch (SQLException ignored) {}
        try (Statement stmt = connection.createStatement()) {
            if ("sqlite".equals(databaseType)) {
                stmt.execute("ALTER TABLE controlbans_mutes DROP COLUMN ipban_wildcard");
            } else {
                stmt.execute("ALTER TABLE controlbans_mutes DROP COLUMN IF EXISTS ipban_wildcard");
            }
        } catch (SQLException ignored) {}
        try (Statement stmt = connection.createStatement()) {
            if ("sqlite".equals(databaseType)) {
                stmt.execute("ALTER TABLE controlbans_warnings DROP COLUMN ipban_wildcard");
            } else {
                stmt.execute("ALTER TABLE controlbans_warnings DROP COLUMN IF EXISTS ipban_wildcard");
            }
        } catch (SQLException ignored) {}
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
                        banned_by_name VARCHAR(16),
                        removed_by_uuid VARCHAR(36),
                        removed_by_name VARCHAR(16),
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
                    CREATE TABLE IF NOT EXISTS controlbans_history (
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

        String insertSql = "INSERT OR IGNORE INTO controlbans_config (version, build, date) VALUES (?, ?, ?)";
        if ("mysql".equals(databaseType) || "mariadb".equals(databaseType)) {
            insertSql = "INSERT IGNORE INTO controlbans_config (version, build, date) VALUES (?, ?, ?)";
        } else if ("postgresql".equals(databaseType)) {
            insertSql = "INSERT INTO controlbans_config (version, build, date) VALUES (?, ?, ?) ON CONFLICT DO NOTHING";
        }

        try (var stmt = connection.prepareStatement(insertSql)) {
            stmt.setInt(1, 1);
            stmt.setString(2, "ControlBans-4.6.0");
            stmt.setLong(3, System.currentTimeMillis());
            stmt.executeUpdate();
        }
    }

    private void createReportsTable() throws SQLException {
        String sql = """
                    CREATE TABLE IF NOT EXISTS controlbans_reports (
                        id VARCHAR(36) PRIMARY KEY,
                        reporter_uuid VARCHAR(36) NOT NULL,
                        reporter_name VARCHAR(16),
                        target_name VARCHAR(16) NOT NULL,
                        reason VARCHAR(2048),
                        time BIGINT NOT NULL,
                        status VARCHAR(16) NOT NULL DEFAULT 'PENDING'
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
                        staff_name VARCHAR(16),
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
        String[] indexes = {
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_bans_punishment_id ON controlbans_bans(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_bans_uuid ON controlbans_bans(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_bans_active ON controlbans_bans(active)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_mutes_punishment_id ON controlbans_mutes(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_mutes_uuid ON controlbans_mutes(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_mutes_active ON controlbans_mutes(active)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_warnings_punishment_id ON controlbans_warnings(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_warnings_uuid ON controlbans_warnings(uuid)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_kicks_punishment_id ON controlbans_kicks(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_kicks_uuid ON controlbans_kicks(uuid)",

                "CREATE INDEX IF NOT EXISTS idx_history_uuid ON controlbans_history(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_history_ip ON controlbans_history(ip)",
                "CREATE INDEX IF NOT EXISTS idx_history_name ON controlbans_history(name)",
                "CREATE UNIQUE INDEX IF NOT EXISTS idx_history_uuid_ip ON controlbans_history(uuid, ip)",

                "CREATE INDEX IF NOT EXISTS idx_appeals_target ON controlbans_appeals(target_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_appeals_created ON controlbans_appeals(created_at)",

                "CREATE UNIQUE INDEX IF NOT EXISTS idx_voicemutes_punishment_id ON controlbans_voicemutes(punishment_id)",
                "CREATE INDEX IF NOT EXISTS idx_voicemutes_uuid ON controlbans_voicemutes(uuid)",
                "CREATE INDEX IF NOT EXISTS idx_voicemutes_active ON controlbans_voicemutes(active)",

                "CREATE INDEX IF NOT EXISTS idx_reports_reporter ON controlbans_reports(reporter_uuid)",
                "CREATE INDEX IF NOT EXISTS idx_reports_target ON controlbans_reports(target_name)",
                "CREATE INDEX IF NOT EXISTS idx_notes_uuid ON controlbans_notes(uuid)"
        };

        try (Statement stmt = connection.createStatement()) {
            for (String index : indexes) {
                try {
                    stmt.execute(index);
                } catch (SQLException e) {
                }
            }
        }
    }

    private String getPrimaryKeyDefinition() {
        return switch (databaseType) {
            case "mysql", "mariadb" -> "PRIMARY KEY AUTO_INCREMENT";
            case "postgresql" -> "PRIMARY KEY";
            default -> "PRIMARY KEY AUTOINCREMENT";
        };
    }
}