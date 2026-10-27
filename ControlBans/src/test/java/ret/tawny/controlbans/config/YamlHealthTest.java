package ret.tawny.controlbans.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlHealthTest {

    @TempDir
    Path tempDir;

    @Test
    void validateAcceptsWellFormedYaml() {
        String yaml = """
                language: "en"

                report:
                  enabled: true
                  cooldown-seconds: 60

                  categories:
                    - id: hacking
                      icon: DIAMOND_SWORD
                """;
        assertNull(YamlHealth.validate(yaml));
    }

    @Test
    void validateRejectsBrokenIndentationWithReadableError() {
        String broken = """
                report:
                  enabled: true
                   cooldown-seconds: 60
                """;
        String error = YamlHealth.validate(broken);
        assertNotNull(error, "over-indented key must be flagged");
        assertFalse(error.isBlank(), "error should be human-readable, not empty");
    }

    @Test
    void validateRejectsTabIndentation() {
        String broken = "punishments:\n\tban: true\n";
        assertNotNull(YamlHealth.validate(broken), "tab indentation must be flagged");
    }

    @Test
    void validateTreatsEmptyAndBlankTextAsOk() {
        assertNull(YamlHealth.validate((String) null));
        assertNull(YamlHealth.validate(""));
        assertNull(YamlHealth.validate("\n   \n\t\n"));
    }

    @Test
    void validateTreatsCommentsOnlyFileAsOk() {
        // Bukkit casts a null document to Map successfully: empty config, not an error.
        assertNull(YamlHealth.validate("# nothing but comments\n\n"));
    }

    @Test
    void validateRejectsNonMappingDocuments() {
        // Bukkit's loadFromString throws InvalidConfigurationException for
        // documents whose top level is not a Map — so must the strict check.
        assertNotNull(YamlHealth.validate("just a scalar\n"));
        assertNotNull(YamlHealth.validate("- a\n- list\n- not a map\n"));
    }

    @Test
    void bundledConfigAndLocalesPassStrictValidation() throws Exception {
        // The shipped files must never trip the strict parser, or the
        // quarantine path would fire on every boot for stock installations.
        for (String resource : List.of("/config.yml", "/locales/en.yml", "/locales/common-additions.yml")) {
            try (InputStream in = getClass().getResourceAsStream(resource)) {
                assertNotNull(in, resource + " not on test classpath");
                String text = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                assertNull(YamlHealth.validate(text), resource + " should pass strict validation");
            }
        }
    }

    @Test
    void validateFileReportsParseErrorsAndAbsentFilesAsOk() throws Exception {
        File broken = tempDir.resolve("config.yml").toFile();
        Files.writeString(broken.toPath(), "a: [unclosed\n", StandardCharsets.UTF_8);
        assertNotNull(YamlHealth.validate(broken));

        File absent = tempDir.resolve("missing.yml").toFile();
        assertNull(YamlHealth.validate(absent));
    }

    @Test
    void quarantineRenamesPreservesContentAndNeverDeletes() throws Exception {
        File broken = tempDir.resolve("config.yml").toFile();
        String content = "a: [unclosed\n";
        Files.writeString(broken.toPath(), content, StandardCharsets.UTF_8);

        File backup = YamlHealth.quarantine(broken);

        assertNotNull(backup, "quarantine should succeed");
        assertFalse(broken.exists(), "the original broken file must be renamed away");
        assertTrue(backup.getName().matches("config\\.yml\\.broken-\\d{8}-\\d{6}(-\\d+)?\\.yml"),
                "backup name must carry a timestamp: " + backup.getName());
        assertTrue(backup.isFile());
        assertEquals(content, Files.readString(backup.toPath(), StandardCharsets.UTF_8),
                "backup must preserve the user's bytes exactly");
    }
}
