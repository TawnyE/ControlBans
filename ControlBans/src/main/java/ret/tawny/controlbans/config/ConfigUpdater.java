package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.logging.Level;

public class ConfigUpdater {

    public static void update(ControlBansPlugin plugin) {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            return;
        }

        try (InputStream inputStream = plugin.getResource("config.yml")) {
            if (inputStream == null) return;

            BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8));
            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(reader);
            FileConfiguration userConfig = YamlConfiguration.loadConfiguration(configFile);

            Set<String> defaultKeys = defaultConfig.getKeys(true);
            Set<String> userKeys = userConfig.getKeys(true);

            if (userKeys.containsAll(defaultKeys)) {
                return;
            }

            plugin.getLogger().info("Updating configuration file with missing keys...");

            List<String> newLines = new ArrayList<>();
            

            try (BufferedReader lineReader = new BufferedReader(new InputStreamReader(plugin.getResource("config.yml"), StandardCharsets.UTF_8))) {
                String line;
                List<String> currentPath = new ArrayList<>();

                while ((line = lineReader.readLine()) != null) {
                    String trimmed = line.trim();
                    
                    if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                        newLines.add(line);
                        continue;
                    }

                    if (trimmed.contains(":")) {
                        int indent = getIndent(line);
                        String key = trimmed.split(":")[0];
                        
                        updatePath(currentPath, key, indent);
                        String fullKey = String.join(".", currentPath);

                        if (userConfig.contains(fullKey)) {
                            Object value = userConfig.get(fullKey);
                            if (!(value instanceof ConfigurationSection)) {
                                newLines.add(formatLine(line, key, value, indent));
                                continue;
                            }
                        }
                    }
                    newLines.add(line);
                }
            }

            try (BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(configFile), StandardCharsets.UTF_8))) {
                for (String newLine : newLines) {
                    writer.write(newLine);
                    writer.newLine();
                }
            }

            plugin.getLogger().info("Configuration file successfully updated.");

        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to update config.yml", e);
        }
    }

    private static int getIndent(String line) {
        int count = 0;
        while (count < line.length() && line.charAt(count) == ' ') {
            count++;
        }
        return count;
    }

    private static void updatePath(List<String> path, String key, int indent) {
        int level = indent / 2;
        while (path.size() > level) {
            path.remove(path.size() - 1);
        }
        if (path.size() == level) {
            path.add(key);
        } else {
            path.add(key);
        }
    }

    private static String formatLine(String originalLine, String key, Object value, int indent) {
        String prefix = " ".repeat(indent) + key + ": ";
        if (value instanceof String str) {
            if (needsQuoting(str)) {
                return prefix + escapeYamlString(str);
            }
            return prefix + str;
        } else if (value instanceof List<?> list) {
            return prefix + formatYamlList(list);
        }
        return prefix + value.toString();
    }

    private static boolean needsQuoting(String value) {
        if (value.isEmpty()) return true;
        char first = value.charAt(0);
        if (first == ':' || first == '#' || first == '-' || first == '[' || first == ']' || first == '{' || first == '}' || first == ',' || first == '&' || first == '*' || first == '?' || first == '|' || first == '!' || first == '%' || first == '@' || first == '`') return true;
        if (value.contains("\n") || value.contains("\r") || value.contains("'") || value.contains("\"")) return true;
        if (value.equalsIgnoreCase("true") || value.equalsIgnoreCase("false") || value.equalsIgnoreCase("null") || value.equalsIgnoreCase("yes") || value.equalsIgnoreCase("no") || value.equalsIgnoreCase("on") || value.equalsIgnoreCase("off")) return true;
        try { Double.parseDouble(value); return true; } catch (NumberFormatException ignored) {}
        return false;
    }

    private static String escapeYamlString(String value) {
        StringBuilder sb = new StringBuilder("\"");
        for (char c : value.toCharArray()) {
            switch (c) {
                case '\\' -> sb.append("\\\\");
                case '"' -> sb.append("\\\"");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    private static String formatYamlList(List<?> list) {
        if (list.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(", ");
            Object item = list.get(i);
            if (item instanceof String str) {
                sb.append(escapeYamlString(str));
            } else {
                sb.append(item.toString());
            }
        }
        sb.append("]");
        return sb.toString();
    }
}
