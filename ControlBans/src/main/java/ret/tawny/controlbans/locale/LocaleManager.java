package ret.tawny.controlbans.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

public class LocaleManager {

    private final ControlBansPlugin plugin;
    private FileConfiguration localeConfig;
    private FileConfiguration fallbackConfig;
    private final MiniMessage miniMessage;

    public LocaleManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadLocales();
    }

    public void loadLocales() {
        String lang = plugin.getConfigManager().getLanguage();
        File localeFile = new File(plugin.getDataFolder(), "locales/" + lang + ".yml");
        File defaultLocaleFile = new File(plugin.getDataFolder(), "locales/en.yml");
        File localesFolder = localeFile.getParentFile();

        ensureDefaultLocales(localesFolder);

        if (!localeFile.exists()) {
            plugin.getLogger().warning("Locale file '" + lang + ".yml' not found. Defaulting to 'en.yml'.");
            localeFile = defaultLocaleFile;
        }

        this.localeConfig = YamlConfiguration.loadConfiguration(localeFile);

        try (InputStream fallbackStream = plugin.getResource("locales/en.yml")) {
            if (fallbackStream != null) {
                this.fallbackConfig = YamlConfiguration.loadConfiguration(
                        new InputStreamReader(fallbackStream, StandardCharsets.UTF_8));
                mergeBundledLocale(fallbackConfig, "locales/common-additions.yml");
                syncLocaleFiles(localesFolder);
            } else {
                throw new IllegalStateException("Default English locale file is missing from the plugin JAR.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load fallback English locale from JAR.");
            e.printStackTrace();
        }

        mergeBundledLocale(localeConfig, "locales/common-additions.yml");
    }

    public String getRawMessage(String key) {
        String message = localeConfig.getString(key);
        if (message == null) {
            message = fallbackConfig.getString(key, "<red>Missing translation key: " + key);
        }
        return repairMojibakeIfNeeded(message);
    }

    public Component getMessage(String key, TagResolver... resolvers) {
        return miniMessage.deserialize(getRawMessage(key), resolvers);
    }

    public List<Component> getMessageList(String key, TagResolver... resolvers) {
        List<String> messages = localeConfig.getStringList(key);
        if (messages.isEmpty()) {
            messages = fallbackConfig.getStringList(key);
        }

        return messages.stream()
                .map(this::repairMojibakeIfNeeded)
                .map(line -> miniMessage.deserialize(line, resolvers))
                .collect(Collectors.toList());
    }

    public void reload() {
        loadLocales();
    }

    private void ensureDefaultLocales(File localesFolder) {
        if (localesFolder != null && !localesFolder.exists() && !localesFolder.mkdirs()) {
            plugin.getLogger().warning("Could not create locales folder at " + localesFolder.getAbsolutePath());
        }

        for (String defaultLocale : List.of("en", "de", "es", "fr", "it", "ja", "nl", "pt", "ru", "tr")) {
            File targetFile = new File(plugin.getDataFolder(), "locales/" + defaultLocale + ".yml");
            if (!targetFile.exists()) {
                plugin.saveResource("locales/" + defaultLocale + ".yml", false);
            }
        }
    }

    private void mergeBundledLocale(FileConfiguration targetConfig, String resourcePath) {
        try (InputStream resourceStream = plugin.getResource(resourcePath)) {
            if (resourceStream == null) {
                return;
            }

            FileConfiguration bundledConfig = YamlConfiguration.loadConfiguration(
                    new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
            mergeMissingValues(bundledConfig, targetConfig, "");
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to merge bundled locale resource " + resourcePath + ": "
                    + exception.getMessage());
        }
    }

    private void syncLocaleFiles(File localesFolder) {
        if (fallbackConfig == null || localesFolder == null || !localesFolder.exists()) {
            return;
        }

        File[] localeFiles = localesFolder.listFiles((dir, name) -> name.endsWith(".yml"));
        if (localeFiles == null) {
            return;
        }

        for (File file : localeFiles) {
            YamlConfiguration targetConfig = YamlConfiguration.loadConfiguration(file);
            if (mergeMissingValues(fallbackConfig, targetConfig, "")) {
                try {
                    targetConfig.save(file);
                    plugin.getLogger().info("Backfilled missing locale keys in " + file.getName() + ".");
                } catch (IOException exception) {
                    plugin.getLogger().warning("Failed to save updated locale file " + file.getName() + ": "
                            + exception.getMessage());
                }
            }
        }
    }

    private boolean mergeMissingValues(FileConfiguration source, FileConfiguration target, String path) {
        boolean changed = false;
        ConfigurationSection sourceSection = path.isEmpty() ? source : source.getConfigurationSection(path);
        if (sourceSection == null) {
            return false;
        }

        Set<String> keys = sourceSection.getKeys(false);
        for (String key : keys) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (source.isConfigurationSection(childPath)) {
                if (!target.isConfigurationSection(childPath) && !target.contains(childPath)) {
                    target.createSection(childPath);
                    changed = true;
                }
                changed |= mergeMissingValues(source, target, childPath);
                continue;
            }

            if (!target.contains(childPath)) {
                target.set(childPath, source.get(childPath));
                changed = true;
            }
        }
        return changed;
    }

    private String repairMojibakeIfNeeded(String text) {
        if (text == null || !looksLikeMojibake(text)) {
            return text;
        }

        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length() * 2);
        for (char ch : text.toCharArray()) {
            if (!appendCp1252Byte(out, ch)) {
                out.writeBytes(String.valueOf(ch).getBytes(StandardCharsets.UTF_8));
            }
        }

        String repaired = new String(out.toByteArray(), StandardCharsets.UTF_8);
        if (scoreMojibake(repaired) > scoreMojibake(text)
                && countReplacementChars(repaired) > countReplacementChars(text)) {
            return text;
        }
        return repaired;
    }

    private boolean looksLikeMojibake(String text) {
        return text.contains("\u00C3")
                || text.contains("\u00C2")
                || text.contains("\u00E2")
                || text.contains("\u00F0")
                || text.contains("\u00D0")
                || text.contains("\u00D1")
                || text.contains("\u00E1\u00B4")
                || text.contains("\u00EA\u0153")
                || text.contains("\u00EF\u00BF\u00BD");
    }

    private int scoreMojibake(String text) {
        int score = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '\u00C3' || ch == '\u00C2' || ch == '\u00E2' || ch == '\u00F0' || ch == '\u00D0'
                    || ch == '\u00D1') {
                score++;
            }
        }
        score += countOccurrences(text, "\u00E1\u00B4");
        score += countOccurrences(text, "\u00EA\u0153");
        score += countOccurrences(text, "\u00EF\u00BF\u00BD");
        return score;
    }

    private int countReplacementChars(String text) {
        int count = 0;
        for (char ch : text.toCharArray()) {
            if (ch == '\uFFFD') {
                count++;
            }
        }
        return count;
    }

    private int countOccurrences(String text, String needle) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(needle, index)) != -1) {
            count++;
            index += needle.length();
        }
        return count;
    }

    private boolean appendCp1252Byte(ByteArrayOutputStream out, char ch) {
        if (ch <= 0x00FF) {
            out.write((byte) ch);
            return true;
        }

        int cp1252Byte = switch (ch) {
            case '\u20AC' -> 0x80;
            case '\u201A' -> 0x82;
            case '\u0192' -> 0x83;
            case '\u201E' -> 0x84;
            case '\u2026' -> 0x85;
            case '\u2020' -> 0x86;
            case '\u2021' -> 0x87;
            case '\u02C6' -> 0x88;
            case '\u2030' -> 0x89;
            case '\u0160' -> 0x8A;
            case '\u2039' -> 0x8B;
            case '\u0152' -> 0x8C;
            case '\u017D' -> 0x8E;
            case '\u2018' -> 0x91;
            case '\u2019' -> 0x92;
            case '\u201C' -> 0x93;
            case '\u201D' -> 0x94;
            case '\u2022' -> 0x95;
            case '\u2013' -> 0x96;
            case '\u2014' -> 0x97;
            case '\u02DC' -> 0x98;
            case '\u2122' -> 0x99;
            case '\u0161' -> 0x9A;
            case '\u203A' -> 0x9B;
            case '\u0153' -> 0x9C;
            case '\u017E' -> 0x9E;
            case '\u0178' -> 0x9F;
            default -> -1;
        };

        if (cp1252Byte == -1) {
            return false;
        }

        out.write(cp1252Byte);
        return true;
    }
}
