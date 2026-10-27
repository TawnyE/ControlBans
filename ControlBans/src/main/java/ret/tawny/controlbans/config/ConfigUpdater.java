package ret.tawny.controlbans.config;

import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

/**
 * Keeps the user's config.yml up to date with the bundled default config.
 * <p>
 * Philosophy: <b>insert only what's missing, preserve everything else</b>.
 * The user's file is never rewritten — existing lines (values, comments,
 * blank lines, ordering, inline comments) stay byte-identical. Keys that
 * exist in the bundled default but not in the user's file are copied from
 * the default file's raw text (including their preceding comment block) and
 * inserted at the text level under the correct section. Keys the user has
 * that the default no longer ships are left alone — nothing is ever removed.
 * <p>
 * The merge core is the pure, plugin-independent static method
 * {@link #mergeDefaultKeys(String, String)}; {@link #update(ControlBansPlugin)}
 * is only IO and safety glue around it:
 * <ul>
 *   <li>an unparseable user config is left untouched (warning logged),</li>
 *   <li>the current file is copied to {@code config.yml.backup} before writing,</li>
 *   <li>the new content goes through {@code config.yml.tmp} and is then moved
 *       over the original.</li>
 * </ul>
 */
public final class ConfigUpdater {

    private ConfigUpdater() {
    }

    // ------------------------------------------------------------------
    // IO / plugin glue
    // ------------------------------------------------------------------

    public static void update(ControlBansPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }

        try {
            String userText = Files.readString(configFile.toPath(), StandardCharsets.UTF_8);

            // YamlConfiguration.loadConfiguration swallows parse errors and
            // returns an empty configuration, so the old try/catch here was
            // dead code: a broken file looked like "all keys missing" and the
            // merge stomped it. Check strictly before parsing anything.
            String healthError = YamlHealth.validate(userText);
            if (healthError != null) {
                plugin.getLogger().warning("config.yml looks broken (usually a spacing or indentation mistake): "
                        + healthError);
                plugin.getLogger().warning("Skipping the config update so nothing gets overwritten.");
                return;
            }

            try (InputStream in = plugin.getResource("config.yml")) {
                if (in == null) {
                    return;
                }
                String defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);

                List<String> before = findMissingKeys(userText, defaultText);
                if (before.isEmpty()) {
                    return;
                }

                String merged = mergeDefaultKeys(userText, defaultText);
                if (merged == null) {
                    return;
                }

                File backupFile = new File(plugin.getDataFolder(), "config.yml.backup");
                Files.write(backupFile.toPath(), userText.getBytes(StandardCharsets.UTF_8));

                File tmpFile = new File(plugin.getDataFolder(), "config.yml.tmp");
                Files.write(tmpFile.toPath(), merged.getBytes(StandardCharsets.UTF_8));
                Files.move(tmpFile.toPath(), configFile.toPath(), StandardCopyOption.REPLACE_EXISTING);

                List<String> remaining = findMissingKeys(merged, defaultText);
                List<String> added = new ArrayList<>(before);
                added.removeAll(remaining);

                List<String> listedKeys = added.size() <= 5
                        ? added
                        : new ArrayList<>(added.subList(0, 5));
                StringBuilder summary = new StringBuilder("Config updated: added ")
                        .append(added.size()).append(" missing key")
                        .append(added.size() == 1 ? "" : "s")
                        .append(" (").append(String.join(", ", listedKeys));
                if (added.size() > 5) {
                    summary.append(", and ").append(added.size() - 5).append(" more");
                }
                summary.append(')');
                plugin.getLogger().info(summary.toString());
            }
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update config.yml", e);
        }
    }

    // ------------------------------------------------------------------
    // Pure merge core (text level, testable without a plugin instance)
    // ------------------------------------------------------------------

    /**
     * Inserts any keys present in {@code defaultYamlText} but missing from
     * {@code userYamlText} into the user's text, copying the missing keys'
     * lines (plus their directly-preceding comment block) verbatim from the
     * default text. Everything already in the user's text is left untouched.
     *
     * @return the merged text, or {@code null} if nothing was missing
     */
    public static String mergeDefaultKeys(String userYamlText, String defaultYamlText) {
        String merged = userYamlText;
        Set<String> uninsertable = new LinkedHashSet<>();
        while (true) {
            List<String> missing = findMissingKeys(merged, defaultYamlText);
            missing.removeAll(uninsertable);
            if (missing.isEmpty()) {
                break;
            }
            String key = missing.get(0);
            String next = insertKey(merged, defaultYamlText, key);
            if (next == null || next.equals(merged)) {
                uninsertable.add(key);
                continue;
            }
            merged = next;
        }
        return merged.equals(userYamlText) ? null : merged;
    }

    /**
     * Computes the minimal set of keys present in the default text but
     * missing from the user text. "Minimal" means that if an entire section
     * is missing only the section's root key is reported — inserting it
     * covers all of its children. YAML lists are treated as leaf values and
     * are never descended into.
     */
    static List<String> findMissingKeys(String userYamlText, String defaultYamlText) {
        YamlConfiguration defaults = YamlConfiguration.loadConfiguration(new StringReader(defaultYamlText));
        YamlConfiguration user = YamlConfiguration.loadConfiguration(new StringReader(userYamlText));
        Set<String> userKeys = user.getKeys(true);

        List<String> missing = new ArrayList<>();
        for (String key : defaults.getKeys(true)) {
            if (userKeys.contains(key)) {
                continue;
            }
            String[] parts = key.split("\\.");
            StringBuilder prefix = new StringBuilder();
            boolean coveredByMissingAncestor = false;
            for (int i = 0; i < parts.length - 1; i++) {
                if (i > 0) {
                    prefix.append('.');
                }
                prefix.append(parts[i]);
                if (!userKeys.contains(prefix.toString())) {
                    coveredByMissingAncestor = true;
                    break;
                }
            }
            if (!coveredByMissingAncestor) {
                missing.add(key);
            }
        }
        return missing;
    }

    /**
     * Inserts a single (minimal) missing key from the default text into the
     * user text. Returns {@code null} if the key cannot be located in the
     * default text or its parent in the user text is a scalar value.
     */
    private static String insertKey(String userText, String defaultText, String key) {
        String newline = detectNewline(userText);
        List<String> defaultLines = splitLines(defaultText);
        List<String> userLines = splitLines(userText);
        boolean trailingNewline = userText.endsWith("\n") || userLines.isEmpty();

        int keyIndex = findKeyLine(defaultLines, key);
        if (keyIndex < 0) {
            return null;
        }

        // Block in the default file: contiguous comment lines directly above
        // the key (plus one blank separator line above those) and the key's
        // whole subtree, with trailing blank/comment lines trimmed off.
        int blockStart = keyIndex;
        while (blockStart - 1 >= 0 && isComment(defaultLines.get(blockStart - 1))) {
            blockStart--;
        }
        if (blockStart < keyIndex && blockStart - 1 >= 0 && defaultLines.get(blockStart - 1).isBlank()) {
            blockStart--;
        }
        int blockEnd = sectionEnd(defaultLines, keyIndex);
        List<String> block = new ArrayList<>(defaultLines.subList(blockStart, blockEnd));

        String parentPath = parentPathOf(key);
        if (parentPath.isEmpty()) {
            // Whole top-level section missing: append at end, preceded by a blank line.
            while (!block.isEmpty() && block.get(0).isBlank()) {
                block.remove(0);
            }
            if (!userLines.isEmpty() && !userLines.get(userLines.size() - 1).isBlank()) {
                userLines.add("");
            }
            userLines.addAll(block);
        } else {
            int parentIndex = findKeyLine(userLines, parentPath);
            if (parentIndex < 0) {
                return null;
            }
            // Never graft content onto a scalar value (e.g. "report: true").
            String valuePart = valuePart(userLines.get(parentIndex));
            if (valuePart != null && !valuePart.isEmpty() && !valuePart.startsWith("#")) {
                return null;
            }
            int parentIndent = indentOf(userLines.get(parentIndex));

            // Section ends at the first following key line with indent <= the
            // parent's (list items, comments and blank lines never end it).
            int insertAt = parentIndex + 1;
            while (insertAt < userLines.size()) {
                String line = userLines.get(insertAt);
                if (isKeyLine(line) && indentOf(line) <= parentIndent) {
                    break;
                }
                insertAt++;
            }
            // ...but place the new content before any trailing blank/comment
            // run, which visually belongs to the next section.
            while (insertAt - 1 > parentIndex
                    && (userLines.get(insertAt - 1).isBlank() || isComment(userLines.get(insertAt - 1)))) {
                insertAt--;
            }
            // Match the user's own child indentation so siblings stay aligned.
            int baseIndent = parentIndent + 2;
            for (int i = parentIndex + 1; i < userLines.size(); i++) {
                String line = userLines.get(i);
                if (isKeyLine(line) && indentOf(line) > parentIndent) {
                    baseIndent = indentOf(line);
                    break;
                }
                if (isKeyLine(line) && indentOf(line) <= parentIndent) {
                    break;
                }
            }
            shiftIndent(block, baseIndent - indentOf(defaultLines.get(keyIndex)));
            userLines.addAll(insertAt, block);
        }

        StringBuilder result = new StringBuilder(String.join(newline, userLines));
        if (trailingNewline) {
            result.append(newline);
        }
        return result.toString();
    }

    // ------------------------------------------------------------------
    // Line-level YAML helpers
    // ------------------------------------------------------------------

    /**
     * Locates the line defining the given dotted key path by tracking
     * indentation. Returns -1 when not found.
     */
    private static int findKeyLine(List<String> lines, String path) {
        List<String> pathKeys = new ArrayList<>();
        List<Integer> pathIndents = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (!isKeyLine(line)) {
                continue;
            }
            int indent = indentOf(line);
            while (!pathIndents.isEmpty() && pathIndents.get(pathIndents.size() - 1) >= indent) {
                pathIndents.remove(pathIndents.size() - 1);
                pathKeys.remove(pathKeys.size() - 1);
            }
            pathKeys.add(keyOf(line));
            pathIndents.add(indent);
            if (String.join(".", pathKeys).equals(path)) {
                return i;
            }
        }
        return -1;
    }

    /**
     * End (exclusive) of the section rooted at {@code keyIndex}: the first
     * later key line with indent less than or equal to the key's own indent,
     * with any trailing blank/comment run trimmed off.
     */
    private static int sectionEnd(List<String> lines, int keyIndex) {
        int keyIndent = indentOf(lines.get(keyIndex));
        int end = keyIndex + 1;
        while (end < lines.size()) {
            String line = lines.get(end);
            if (isKeyLine(line) && indentOf(line) <= keyIndent) {
                break;
            }
            end++;
        }
        while (end - 1 > keyIndex && (lines.get(end - 1).isBlank() || isComment(lines.get(end - 1)))) {
            end--;
        }
        return end;
    }

    /**
     * True when the line defines a YAML mapping key: not blank, not a
     * comment, not a list item (trimmed starts with {@code -}), and contains
     * a ':' followed by a space or end-of-line.
     */
    private static boolean isKeyLine(String line) {
        String trimmed = line.strip();
        if (trimmed.isEmpty() || trimmed.startsWith("#") || trimmed.equals("-") || trimmed.startsWith("- ")) {
            return false;
        }
        return keyOf(line) != null;
    }

    /**
     * Extracts the key of a mapping line: everything before the first ':'
     * that is followed by a space or end-of-line. Returns {@code null} when
     * the line has no such key separator.
     */
    private static String keyOf(String line) {
        int start = 0;
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        for (int i = start; i < line.length(); i++) {
            if (line.charAt(i) == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) {
                return line.substring(start, i);
            }
        }
        return null;
    }

    /**
     * Everything after the key's ':' on a mapping line, trimmed; or
     * {@code null} when the line is not a mapping line.
     */
    private static String valuePart(String line) {
        int start = 0;
        while (start < line.length() && line.charAt(start) == ' ') {
            start++;
        }
        for (int i = start; i < line.length(); i++) {
            if (line.charAt(i) == ':' && (i + 1 == line.length() || line.charAt(i + 1) == ' ')) {
                return line.substring(i + 1).strip();
            }
        }
        return null;
    }

    private static boolean isComment(String line) {
        return line.stripLeading().startsWith("#");
    }

    private static int indentOf(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    /**
     * Shifts the indentation of every non-blank line by {@code delta} spaces.
     * Lines that cannot be dedented far enough (when delta is negative) are
     * left untouched.
     */
    private static void shiftIndent(List<String> lines, int delta) {
        if (delta == 0) {
            return;
        }
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            if (line.isBlank()) {
                continue;
            }
            if (delta > 0) {
                lines.set(i, " ".repeat(delta) + line);
            } else if (indentOf(line) >= -delta) {
                lines.set(i, line.substring(-delta));
            }
        }
    }

    private static String parentPathOf(String key) {
        int dot = key.lastIndexOf('.');
        return dot < 0 ? "" : key.substring(0, dot);
    }

    private static List<String> splitLines(String text) {
        String[] parts = text.split("\r?\n", -1);
        int length = parts.length;
        if (text.endsWith("\n")) {
            length--; // trailing newline produces a synthetic empty final element
        }
        List<String> lines = new ArrayList<>(Math.max(length, 1));
        for (int i = 0; i < length; i++) {
            lines.add(parts[i]);
        }
        return lines;
    }

    private static String detectNewline(String text) {
        int idx = text.indexOf('\n');
        return (idx > 0 && text.charAt(idx - 1) == '\r') ? "\r\n" : "\n";
    }
}
