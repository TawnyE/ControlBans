package ret.tawny.controlbans.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class PunishmentTest {

    @Test
    void testBuilderSetsTemplateKey() {
        Punishment p = Punishment.builder()
                .punishmentId("TEST001")
                .type(PunishmentType.TEMPBAN)
                .targetUuid(java.util.UUID.randomUUID())
                .reason("test")
                .createdTime(System.currentTimeMillis())
                .expiryTime(System.currentTimeMillis() + 86400000)
                .serverOrigin("global")
                .templateKey("hacking")
                .build();

        assertEquals("hacking", p.getTemplateKey());
    }

    @Test
    void testToBuilderPreservesTemplateKey() {
        Punishment p = Punishment.builder()
                .punishmentId("TEST002")
                .type(PunishmentType.TEMPMUTE)
                .targetUuid(java.util.UUID.randomUUID())
                .reason("test")
                .createdTime(System.currentTimeMillis())
                .expiryTime(System.currentTimeMillis() + 86400000)
                .serverOrigin("global")
                .templateKey("spam")
                .build();

        Punishment copy = p.toBuilder().build();
        assertEquals("spam", copy.getTemplateKey());
    }

    @Test
    void testTemplateKeyDefaultsToNull() {
        Punishment p = Punishment.builder()
                .punishmentId("TEST003")
                .type(PunishmentType.BAN)
                .targetUuid(java.util.UUID.randomUUID())
                .reason("test")
                .createdTime(System.currentTimeMillis())
                .expiryTime(-1)
                .serverOrigin("global")
                .build();

        assertNull(p.getTemplateKey());
    }
}
