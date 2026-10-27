package ret.tawny.controlbans.storage.dao;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.storage.SchemaMigrator;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PunishmentDaoTest {

    private Connection conn;
    private PunishmentDao dao;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection("jdbc:sqlite::memory:");
        new SchemaMigrator(conn, "sqlite").createLiteBansCompatibleSchema();
        dao = new PunishmentDao();
    }

    @AfterEach
    void tearDown() throws SQLException {
        conn.close();
    }

    private Punishment ban(UUID uuid, String id) {
        return Punishment.builder()
                .punishmentId(id)
                .type(PunishmentType.BAN)
                .targetUuid(uuid)
                .reason("test")
                .staffUuid(UUID.randomUUID())
                .staffName("Staff")
                .createdTime(System.currentTimeMillis())
                .expiryTime(-1)
                .serverOrigin("global")
                .active(true)
                .build();
    }

    @Test
    void getPunishmentHistoryDoesNotMultiplyRowsAcrossHistoryEntries() throws SQLException {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            dao.recordHistory(conn, uuid, "Player" + i, "1.2.3.4");
        }
        dao.insertBan(conn, ban(uuid, "BAN001"));

        List<Punishment> history = dao.getPunishmentHistory(conn, uuid, 200);

        assertEquals(1, history.size());
        assertEquals("BAN001", history.get(0).getPunishmentId());
    }

    @Test
    void getRecentPunishmentsDoesNotMultiplyRowsWithHistoryEntries() throws SQLException {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            dao.recordHistory(conn, uuid, "Player" + i, "1.2.3.4");
        }
        dao.insertBan(conn, ban(uuid, "BAN002"));

        List<Punishment> recent = dao.getRecentPunishments(conn, 200);

        assertEquals(1, recent.size());
    }

    @Test
    void getAllPunishmentsDoesNotMultiplyRowsWithHistoryEntries() throws SQLException {
        UUID uuid = UUID.randomUUID();
        for (int i = 0; i < 10; i++) {
            dao.recordHistory(conn, uuid, "Player" + i, "1.2.3.4");
        }
        dao.insertBan(conn, ban(uuid, "BAN003"));

        List<Punishment> all = dao.getAllPunishments(conn);

        assertEquals(1, all.size());
        assertEquals("BAN003", all.get(0).getPunishmentId());
    }

    @Test
    void removeScheduledForTargetDeletesTargetedNotStaffIssued() throws SQLException {
        UUID targetUuid = UUID.randomUUID();
        dao.recordHistory(conn, targetUuid, "TargetPlayer", "5.6.7.8");

        dao.insertScheduled(conn, new ScheduledPunishment(
                "SCHED-1", ScheduledPunishment.ScheduledAction.BAN, PunishmentType.BAN,
                "TargetPlayer", null, -1, "reason",
                UUID.randomUUID().toString(), "StaffA",
                System.currentTimeMillis() + 10000, System.currentTimeMillis(), false, "global"));

        // Created BY the target uuid as staff, but targeting someone else — must survive removal.
        dao.insertScheduled(conn, new ScheduledPunishment(
                "SCHED-2", ScheduledPunishment.ScheduledAction.MUTE, PunishmentType.MUTE,
                "SomeoneElse", null, -1, "reason",
                targetUuid.toString(), "TargetPlayer",
                System.currentTimeMillis() + 10000, System.currentTimeMillis(), false, "global"));

        dao.removeScheduledForTarget(conn, targetUuid);

        List<ScheduledPunishment> remaining = dao.getAllScheduled(conn);
        assertEquals(1, remaining.size());
        assertEquals("SCHED-2", remaining.get(0).scheduleId());
    }
}