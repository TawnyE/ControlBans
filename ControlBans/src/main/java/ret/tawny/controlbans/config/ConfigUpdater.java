package ret.tawny.controlbans.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class ConfigUpdater {

    /**
     * Updates the user's config.yml with new keys from the default config, preserving comments.
     * @param plugin The instance of the main plugin.
     * @throws IOException If there is an error reading or writing the config files.
     */
    public static void update(ControlBansPlugin plugin) throws IOException {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        FileConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);

        // Load the default config from the JAR
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8))) {
            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(reader);

            Set<String> userKeys = userConfig.getKeys(true);
            Set<String> defaultKeys = defaultConfig.getKeys(true);

            // Check if there are any new keys to add
            if (userKeys.containsAll(defaultKeys)) {
                plugin.getLogger().info("Configuration file is up to date.");
                return;
            }

            plugin.getLogger().info("Updating configuration file with new settings...");

            // Get the raw lines from the default config to preserve comments
            List<String> defaultLines = new BufferedReader(new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8)).lines().toList();
            StringBuilder newConfigContent = new StringBuilder();

            for (String line : defaultLines) {
                String trimmedLine = line.trim();
                if (trimmedLine.isEmpty() || trimmedLine.startsWith("#")) {
                    // Always add comments and empty lines
                    newConfigContent.append(line).append("\n");
                } else {
                    // It's a key-value line
                    String key = getKeyFromLine(line);
                    if (key != null) {
                        if (userConfig.contains(key)) {
                            // If the user's config has this key, use their line (preserves their value)
                            newConfigContent.append(createLineFromKeyValue(key, userConfig.get(key), line)).append("\n");
                        } else {
                            // If the user's config is missing this key, add it from the default
                            newConfigContent.append(line).append("\n");
                        }
                    } else {
                        newConfigContent.append(line).append("\n");
                    }
                }
            }

            // Write the new content to the config file
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(configFile))) {
                writer.write(newConfigContent.toString());
            }

            plugin.getLogger().info("Configuration file successfully updated.");

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Could not update configuration file!", e);
        }
    }

    /**
     * Extracts the key from a YAML configuration line.
     * Example: "  host: localhost" -> "database.host" (assuming it's under 'database')
     */
    private static String getKeyFromLine(String line) {
        if (!line.contains(":")) {
            return null;
        }
        return line.split(":")[0].trim();
    }

    /**
     * Reconstructs a config line with the user's value but the default's formatting.
     * This is a simplified approach; a more complex one would be needed for nested lists/maps.
     * For this config, it handles simple key-value pairs correctly.
     */
    private static String createLineFromKeyValue(String key, Object value, String defaultLine) {
        int indent = 0;
        while (defaultLine.charAt(indent) == ' ') {
            indent++;
        }

        String indentString = " ".repeat(indent);

        if (value instanceof String) {
            return indentString + key + ": \"" + value + "\"";
        } else {
            return indentString + key + ": " + value;
        }
    }
}