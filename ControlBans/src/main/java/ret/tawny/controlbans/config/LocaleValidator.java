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
        if (!localeDir.exists()) {
            plugin.getLogger().warning("Locales directory not found. Skipping validation.");
            return;
        }

        // Load or cache en.yml as reference
        if (enConfigCache == null) {
            enConfigCache = loadYamlFromResources("locales/en.yml");
            if (enConfigCache == null) {
                plugin.getLogger().warning("Could not load en.yml from resources. Skipping validation.");
                return;
            }
        }

        // Check if the configured locale exists
        File localeFile = new File(localeDir, language + ".yml");
        if (!localeFile.exists()) {
            plugin.getLogger().warning("Locale file " + language + ".yml not found in /locales/. Using en.yml as fallback.");
            return;
        }

        // Load the target locale file
        YamlConfiguration targetConfig = YamlConfiguration.loadConfiguration(localeFile);
        Set<String> missingKeys = findMissingKeys(enConfigCache, targetConfig);

        if (!missingKeys.isEmpty()) {
            String missingKeysStr = missingKeys.stream().sorted().collect(Collectors.joining(", "));
            plugin.getLogger().warning("Locale " + language + ".yml is missing keys: [" + missingKeysStr + "]");
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