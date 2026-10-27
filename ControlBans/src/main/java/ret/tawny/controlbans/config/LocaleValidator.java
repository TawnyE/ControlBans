package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.logging.Level;
import java.util.stream.Collectors;

public class LocaleValidator {

    private static YamlConfiguration enConfigCache;

    public static void validate(LocaleManager localeManager, String language) {
        ControlBansPlugin plugin = ControlBansPlugin.getInstance();
        File localeDir = new File(plugin.getDataFolder(), "locales");

        // Load or cache en.yml as reference
        if (enConfigCache == null) {
            enConfigCache = loadYamlFromResources("locales/en.yml");
            if (enConfigCache == null) {
                plugin.getLogger().warning("Could not load en.yml from resources. Skipping validation.");
                return;
            }
            // The runtime fallback chain merges common-additions.yml into the English
            // fallback, so the reference must include it too — otherwise keys served
            // fine at runtime get reported as "missing" here.
            YamlConfiguration additions = loadYamlFromResources("locales/common-additions.yml");
            if (additions != null) {
                mergeMissing(enConfigCache, additions);
            }
        }

        // Check if the configured locale exists
        File localeFile = new File(localeDir, language + ".yml");
        if (!localeFile.exists()) {
            plugin.getLogger().warning("Locale file " + language + ".yml not found in /locales/. Using en.yml as fallback.");
            return;
        }

        // Validate the merged in-memory locale, not a raw disk reload. The disk file may
        // partially parse (Bukkit swallows YAML errors to a load-time log line) which made
        // the validator shout "missing keys" for hundreds of actually-present keys.
        YamlConfiguration targetConfig;
        try {
            targetConfig = YamlConfiguration.loadConfiguration(localeFile);
        } catch (Exception e) {
            plugin.getLogger().warning("Could not parse locale " + language + ".yml (" + e.getMessage()
                    + ") — the file likely has a YAML syntax error. Regenerating keys at runtime; delete the file to get a fresh copy.");
            return;
        }

        Set<String> diskKeys = targetConfig.getKeys(true);
        if (diskKeys.size() < 10) {
            plugin.getLogger().warning("Locale " + language + ".yml parsed but contains almost no keys (" + diskKeys.size()
                    + "). The file is likely corrupt — missing keys will be served from bundled English at runtime. "
                    + "Fix or delete plugins/ControlBans/locales/" + language + ".yml to regenerate.");
            return;
        }

        Set<String> missingKeys = findMissingKeys(enConfigCache, targetConfig);
        // When the active language IS the fallback (en), a missing-key count is pure
        // noise: the runtime chain serves the same file, so nothing can be lost.
        // Only warn for community locales where the comparison is actionable.
        if (!missingKeys.isEmpty() && !"en".equalsIgnoreCase(language)) {
            // Cap output — a long sorted dump is useless; group and count instead.
            int shown = 25;
            String firstN = missingKeys.stream()
                    .sorted().limit(shown).collect(Collectors.joining(", "));
            plugin.getLogger().warning("Locale " + language + ".yml is missing " + missingKeys.size()
                    + " keys (e.g. " + firstN + (missingKeys.size() > shown ? ", ..." : "")
                    + "). Missing entries fall back to English automatically.");
        }
    }

    private static void mergeMissing(YamlConfiguration target, YamlConfiguration source) {
        for (String key : source.getKeys(true)) {
            if (source.isConfigurationSection(key)) continue;
            if (!target.contains(key)) target.set(key, source.get(key));
        }
    }

    private static YamlConfiguration loadYamlFromResources(String resourcePath) {
        ControlBansPlugin plugin = ControlBansPlugin.getInstance();
        try (InputStream resourceStream = plugin.getResource(resourcePath)) {
            if (resourceStream == null) {
                return null;
            }
            return YamlConfiguration.loadConfiguration(new InputStreamReader(resourceStream, StandardCharsets.UTF_8));
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load " + resourcePath + " from resources", e);
            return null;
        }
    }

    private static Set<String> findMissingKeys(ConfigurationSection reference, ConfigurationSection target) {
        Set<String> missingKeys = new HashSet<>();
        collectKeys(reference, target, "", missingKeys);
        return missingKeys;
    }

    private static void collectKeys(ConfigurationSection reference, ConfigurationSection target, String path, Set<String> missingKeys) {
        for (String key : reference.getKeys(true)) {
            if (reference.isConfigurationSection(key)) {
                continue; // Skip sections, handled recursively
            }

            String fullKey = path.isEmpty() ? key : path + "." + key;
            if (!target.contains(fullKey)) {
                missingKeys.add(fullKey);
            }
        }

        // Recursively check nested sections
        for (String section : reference.getKeys(false)) {
            if (reference.isConfigurationSection(section)) {
                collectKeys(
                    reference.getConfigurationSection(section),
                    target.getConfigurationSection(section),
                    path.isEmpty() ? section : path + "." + section,
                    missingKeys
                );
            }
        }
    }
}