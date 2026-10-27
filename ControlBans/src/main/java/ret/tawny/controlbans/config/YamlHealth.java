package ret.tawny.controlbans.config;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Strict YAML pre-parse checks and quarantine helpers.
 * <p>
 * Bukkit's {@code YamlConfiguration.loadConfiguration} catches every parse
 * error internally and silently returns an EMPTY configuration, so downstream
 * code cannot tell a broken file from an empty one. Consequence: a config with
 * a spacing mistake looks like "all keys missing", which makes text-merges
 * stomp the file and the plugin run on hard-coded defaults without a word.
 * <p>
 * {@link #validate(String)} parses the raw text with snakeyaml directly (the
 * same library Bukkit uses, configured with the same loader limits, so
 * anything Bukkit can parse passes here too) and reports the exact failure.
 * Callers quarantine the broken file via {@link #quarantine(File)} before any
 * merge or regeneration can touch it.
 */
public final class YamlHealth {

    /** Timestamp format for quarantined backups, e.g. {@code 20260102-030405}. */
    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");

    /** Fallback uniquifier when two files break within the same second. */
    private static final AtomicLong SEQUENCE = new AtomicLong();

    private YamlHealth() {
    }

    /**
     * A Yaml configured exactly like the one Bukkit's {@code YamlConfiguration}
     * uses for loading (same limits, same defaults) — so anything Bukkit can
     * parse passes the strict check too, and anything it chokes on fails here.
     */
    private static Yaml newBukkitLikeYaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        // Values lifted from YamlConfiguration's own LoaderOptions setup.
        loaderOptions.setMaxAliasesForCollections(Integer.MAX_VALUE);
        loaderOptions.setCodePointLimit(Integer.MAX_VALUE);
        loaderOptions.setNestingDepthLimit(100);
        return new Yaml(loaderOptions);
    }

    /**
     * Strictly parses the given YAML text.
     *
     * @param yamlText raw file content (may be {@code null})
     * @return {@code null} when the text parses cleanly — empty and blank
     *         text count as OK; otherwise a short, human-readable error
     *         (including snakeyaml's line/column information)
     */
    public static String validate(String yamlText) {
        if (yamlText == null || yamlText.isBlank()) {
            return null;
        }
        try {
            Object parsed = newBukkitLikeYaml().load(yamlText);
            // Mirror Bukkit's loadFromString: it casts the document to Map and
            // fails on anything else (scalars, lists). A null document (file
            // with only comments) casts fine, i.e. is an empty config — OK.
            if (parsed != null && !(parsed instanceof Map)) {
                return "top level is not a mapping (key: value)";
            }
            return null;
        } catch (RuntimeException parseError) {
            String message = parseError.getMessage();
            return (message == null || message.isBlank())
                    ? parseError.getClass().getSimpleName()
                    : message;
        }
    }

    /**
     * Convenience overload: reads the file as UTF-8 and validates it.
     *
     * @return {@code null} when the file is absent or parses cleanly;
     *         otherwise the parse error
     */
    public static String validate(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        try {
            return validate(Files.readString(file.toPath(), StandardCharsets.UTF_8));
        } catch (IOException readError) {
            return "could not read file: " + readError.getMessage();
        }
    }

    /**
     * Renames {@code file} to {@code <name>.broken-<timestamp>.yml} next to
     * the original. The original bytes are never modified or deleted — the
     * backup keeps the user's content for hand-repair.
     *
     * @param file the broken file (must still exist on disk)
     * @return the backup file, or {@code null} when the rename failed
     */
    public static File quarantine(File file) {
        if (file == null || !file.isFile()) {
            return null;
        }
        Path source = file.toPath();
        String backupName = file.getName() + ".broken-" + BACKUP_STAMP.format(LocalDateTime.now()) + ".yml";
        Path backup = source.resolveSibling(backupName);
        while (Files.exists(backup)) {
            // Same-second collision (e.g. config + locale broken on one boot):
            // append a sequence number instead of overwriting the first backup.
            backup = source.resolveSibling(stripYml(backupName) + "-" + SEQUENCE.incrementAndGet() + ".yml");
        }
        try {
            Files.move(source, backup, StandardCopyOption.ATOMIC_MOVE);
            return backup.toFile();
        } catch (IOException moveFailure) {
            try {
                Files.move(source, backup);
                return backup.toFile();
            } catch (IOException secondFailure) {
                return null;
            }
        }
    }

    private static String stripYml(String backupName) {
        return backupName.endsWith(".yml")
                ? backupName.substring(0, backupName.length() - 4)
                : backupName;
    }
}
