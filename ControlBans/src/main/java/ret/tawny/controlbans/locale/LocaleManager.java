package ret.tawny.controlbans.locale;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class LocaleManager {

    private final ControlBansPlugin plugin;
    private FileConfiguration localeConfig;
    private FileConfiguration fallbackConfig;
    private final MiniMessage miniMessage;
    private final Map<String, FileConfiguration> localeCache = new ConcurrentHashMap<>();
    private final Map<UUID, String> playerLocales = new ConcurrentHashMap<>();

    public LocaleManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.miniMessage = MiniMessage.miniMessage();
        loadLocales();
    }

    public void loadLocales() {
        String lang = plugin.getConfigManager().getLanguage();
        File localeFile = new File(plugin.getDataFolder(), "locales/" + lang + ".yml");
        File defaultLocaleFile = new File(plugin.getDataFolder(), "locales/en.yml");

        // Save all default locales if the folder doesn't exist
        if (!localeFile.getParentFile().exists()) {
            plugin.getLogger().info("Locales folder not found, creating defaults...");
            for (String defaultLocale : List.of("en", "de", "es", "fr", "ru", "tr")) {
                plugin.saveResource("locales/" + defaultLocale + ".yml", false);
            }
        }

        if (!localeFile.exists()) {
            plugin.getLogger().warning("Locale file '" + lang + ".yml' not found. Defaulting to 'en.yml'.");
            localeFile = defaultLocaleFile;
        }

        this.localeConfig = YamlConfiguration.loadConfiguration(localeFile);
        localeCache.clear();

        // Load fallback English configuration from JAR
        try (InputStream fallbackStream = plugin.getResource("locales/en.yml")) {
            if (fallbackStream != null) {
                this.fallbackConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(fallbackStream));
            } else {
                throw new IllegalStateException("Default English locale file is missing from the plugin JAR.");
            }
        } catch (Exception e) {
            plugin.getLogger().severe("Could not load fallback English locale from JAR.");
            e.printStackTrace();
        }
    }

    public String getRawMessage(String key) {
        String message = localeConfig.getString(key);
        if (message == null) {
            message = fallbackConfig.getString(key, "<red>Missing translation key: " + key);
        }
        return message;
    }

    public Component getMessage(String key, TagResolver... resolvers) {
        String message = getRawMessage(key);
        return miniMessage.deserialize(message, resolvers);
    }

    public List<Component> getMessageList(String key, TagResolver... resolvers) {
        List<String> messages = localeConfig.getStringList(key);
        if (messages.isEmpty()) {
            messages = fallbackConfig.getStringList(key);
        }

        return messages.stream()
                .map(line -> miniMessage.deserialize(line, resolvers))
                .collect(Collectors.toList());
    }

    public void reload() {
        loadLocales();
    }

    public void registerPlayerLocale(UUID uuid, String locale) {
        if (locale == null) return;
        playerLocales.put(uuid, normalizeLocale(locale));
    }

    private String normalizeLocale(String locale) {
        if (locale == null || locale.isBlank()) {
            return plugin.getConfigManager().getLanguage();
        }
        String lower = locale.toLowerCase(Locale.ROOT);
        if (lower.contains("_")) {
            lower = lower.substring(0, lower.indexOf('_'));
        }
        return lower;
    }

    private FileConfiguration getConfigurationForLocale(String locale) {
        String normalized = normalizeLocale(locale);
        if (normalized.equalsIgnoreCase(plugin.getConfigManager().getLanguage())) {
            return localeConfig;
        }
        return localeCache.computeIfAbsent(normalized, lang -> {
            File localeFile = new File(plugin.getDataFolder(), "locales/" + lang + ".yml");
            if (!localeFile.exists()) {
                try (InputStream resource = plugin.getResource("locales/" + lang + ".yml")) {
                    if (resource != null) {
                        plugin.saveResource("locales/" + lang + ".yml", false);
                    } else {
                        return localeConfig;
                    }
                } catch (IllegalArgumentException ignored) {
                    return localeConfig;
                }
            }
            return YamlConfiguration.loadConfiguration(localeFile);
        });
    }

    public Component getMessageFor(UUID uuid, String key, TagResolver... resolvers) {
        String locale = playerLocales.get(uuid);
        if (locale == null) {
            return getMessage(key, resolvers);
        }
        FileConfiguration configForLocale = getConfigurationForLocale(locale);
        String message = configForLocale.getString(key);
        if (message == null) {
            message = fallbackConfig.getString(key, "<red>Missing translation key: " + key);
        }
        return miniMessage.deserialize(message, resolvers);
    }

    public List<Component> getMessageListFor(UUID uuid, String key, TagResolver... resolvers) {
        String locale = playerLocales.get(uuid);
        if (locale == null) {
            return getMessageList(key, resolvers);
        }
        FileConfiguration configForLocale = getConfigurationForLocale(locale);
        List<String> messages = configForLocale.getStringList(key);
        if (messages.isEmpty()) {
            messages = fallbackConfig.getStringList(key);
        }
        return messages.stream()
                .map(line -> miniMessage.deserialize(line, resolvers))
                .collect(Collectors.toList());
    }
}