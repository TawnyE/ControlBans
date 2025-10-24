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
}