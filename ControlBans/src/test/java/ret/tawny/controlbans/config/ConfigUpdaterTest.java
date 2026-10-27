package ret.tawny.controlbans.config;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigUpdaterTest {

    @Test
    void insertsMissingScalarIntoExistingSection() {
        String user = """
                language: "en"

                report:
                  enabled: false
                  # my note
                  cooldown-seconds: 30

                notes:
                  enabled: true
                """;
        String defaults = """
                report:
                  enabled: true
                  cooldown-seconds: 60
                  duplicate-window-seconds: 300
                notes:
                  enabled: true
                """;

        String merged = ConfigUpdater.mergeDefaultKeys(user, defaults);

        String expected = """
                language: "en"

                report:
                  enabled: false
                  # my note
                  cooldown-seconds: 30
                  duplicate-window-seconds: 300

                notes:
                  enabled: true
                """;
        assertEquals(expected, merged);
    }

    @Test
    void copiesDefaultCommentBlockForMissingKey() {
        String user = """
                report:
                  enabled: true
                """;
        String defaults = """
                report:
                  enabled: true
                  # How often a player can report.
                  # Set to 0 to disable the cooldown.
                  cooldown-seconds: 60
                """;

        String merged = ConfigUpdater.mergeDefaultKeys(user, defaults);

        assertNotNull(merged);
        assertTrue(merged.contains("  # How often a player can report.\n"
                + "  # Set to 0 to disable the cooldown.\n"
                + "  cooldown-seconds: 60\n"));
    }

    @Test
    void appendsEntirelyMissingSectionAtEnd() {
        String user = """
                language: "en"
                """;
        String defaults = """
                language: "en"

                # ---------------
                # REPORT SYSTEM
                # ---------------
                report:
                  enabled: true
                  cooldown-seconds: 60
                """;

        String merged = ConfigUpdater.mergeDefaultKeys(user, defaults);

        String expected = """
                language: "en"

                # ---------------
                # REPORT SYSTEM
                # ---------------
                report:
                  enabled: true
                  cooldown-seconds: 60
                """;
        assertEquals(expected, merged);
    }

    @Test
    void preservesUserValuesCommentsBlankLinesAndOrdering() {
        String user = """
                # my custom header

                report:
                    cooldown-seconds: 999   # inline comment stays
                    enabled: false

                # a lonely comment
                extra-user-key: "keep me"
                """;
        String defaults = """
                report:
                  enabled: true
                  cooldown-seconds: 60
                  duplicate-window-seconds: 300
                """;

        String merged = ConfigUpdater.mergeDefaultKeys(user, defaults);

        assertNotNull(merged);
        // Every original line survives, in order.
        for (String line : user.split("\n")) {
            assertTrue(merged.contains(line), "lost line: " + line);
        }
        assertTrue(merged.startsWith("# my custom header\n\n"));
        assertTrue(merged.contains("# a lonely comment\nextra-user-key: \"keep me\""));
        assertTrue(merged.indexOf("cooldown-seconds: 999") < merged.indexOf("duplicate-window-seconds: 300"));
        // Nothing was deleted even though the default lacks the user's extra key.
        assertTrue(merged.contains("extra-user-key: \"keep me\""));
        // The inner key was still added despite the user's custom 4-space indent.
        assertTrue(merged.contains("duplicate-window-seconds: 300"));
    }

    @Test
    void insertsListOfMapsSectionWholeWhenMissing() {
        String user = """
                report:
                  enabled: true
                """;
        String defaults = """
                report:
                  enabled: true
                  # Categories shown in the GUI.
                  categories:
                    - id: hacking
                      icon: DIAMOND_SWORD
                    - id: chat
                      icon: PAPER
                """;

        String merged = ConfigUpdater.mergeDefaultKeys(user, defaults);

        String expected = """
                report:
                  enabled: true
                  # Categories shown in the GUI.
                  categories:
                    - id: hacking
                      icon: DIAMOND_SWORD
                    - id: chat
                      icon: PAPER
                """;
        assertEquals(expected, merged);
    }

    @Test
    void leavesExistingListOfMapsUntouched() {
        String user = """
                report:
                  enabled: true
                  categories:
                    - id: custom
                      icon: BARRIER
                """;
        String defaults = """
                report:
                  enabled: true
                  categories:
                    - id: hacking
                      icon: DIAMOND_SWORD
                    - id: chat
                      icon: PAPER
                """;

        // 'categories' is a list value: it exists in the user file, so it is
        // never descended into or merged — the merge must be a no-op.
        assertNull(ConfigUpdater.mergeDefaultKeys(user, defaults));
    }

    @Test
    void mergeIsIdempotent() {
        String user = """
                report:
                  enabled: false
                notes:
                  enabled: true
                """;
        String defaults = """
                report:
                  enabled: true
                  cooldown-seconds: 60
                notes:
                  enabled: true
                  max-notes-per-player: 50
                """;

        String first = ConfigUpdater.mergeDefaultKeys(user, defaults);
        assertNotNull(first);
        // Running again on the result must be a no-op.
        assertNull(ConfigUpdater.mergeDefaultKeys(first, defaults));
    }

    @Test
    void returnsNullWhenAlreadyUpToDate() {
        String yaml = """
                report:
                  enabled: false
                  categories:
                    - id: custom
                      icon: BARRIER
                """;
        String defaults = """
                report:
                  enabled: true
                  categories:
                    - id: hacking
                      icon: DIAMOND_SWORD
                """;

        assertNull(ConfigUpdater.mergeDefaultKeys(yaml, defaults));
    }

    @Test
    void keysRemovedFromDefaultAreLeftAlone() {
        String user = """
                report:
                  enabled: true
                  legacy-option: "still mine"
                """;
        String defaults = """
                report:
                  enabled: true
                """;

        assertNull(ConfigUpdater.mergeDefaultKeys(user, defaults));
    }

    @Test
    void bundledDefaultConfigMergesWithItselfUnchanged() throws Exception {
        String bundled;
        try (InputStream in = getClass().getResourceAsStream("/config.yml")) {
            assertNotNull(in, "bundled config.yml not on test classpath");
            bundled = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        // The shipped default config must be a fixed point of the merge.
        assertNull(ConfigUpdater.mergeDefaultKeys(bundled, bundled));
        // And a copy with one key's value changed must also be a no-op —
        // changed values are user choices, not missing keys.
        String tweaked = bundled.replace("duplicate-window-seconds: 300",
                "duplicate-window-seconds: 1");
        assertFalse(tweaked.equals(bundled));
        assertNull(ConfigUpdater.mergeDefaultKeys(tweaked, bundled));
    }
}
