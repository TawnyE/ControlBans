package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.MessageBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.awt.Color;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class IntegrationService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private boolean discordSrvEnabled = false;
    private final Set<String> mcBlacklist = new HashSet<>();

    // The punishmentService parameter was unused and has been removed.
    public IntegrationService(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() {
        if (config.isDiscordEnabled() && Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            this.discordSrvEnabled = true;
            plugin.getLogger().info("DiscordSRV integration enabled.");
        }

        if (config.isMCBlacklistEnabled()) {
            plugin.getLogger().info("MCBlacklist integration enabled. Scheduling check...");
            long interval = config.getMCBlacklistCheckInterval() * 20L * 60L; // Ticks
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchBlacklist, 0L, interval);
        }
    }

    public void onPunishment(Punishment p) {
        if (discordSrvEnabled) {
            String typeKey = p.getType().name().toLowerCase();
            sendDiscordEmbed(p, typeKey, null);
        }
    }

    // The unused UUID parameters have been removed.
    public void onUnban(String targetName, String staffName) {
        if (discordSrvEnabled) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{player}", targetName);
            placeholders.put("{staff}", staffName);
            sendDiscordEmbed(null, "unban", placeholders);
        }
    }

    private void sendDiscordEmbed(Punishment p, String configKey, Map<String, String> unbanPlaceholders) {
        ConfigurationSection msgConfig = config.getDiscordMessageConfig(configKey);
        if (msgConfig == null || !msgConfig.getBoolean("enabled", false)) {
            return;
        }

        String channelId = msgConfig.getString("channel");
        if (channelId == null || channelId.isBlank()) {
            plugin.getLogger().warning("Discord message type '" + configKey + "' is enabled but has no channel ID set.");
            return;
        }

        TextChannel textChannel = DiscordUtil.getTextChannelById(channelId);
        if (textChannel == null) {
            plugin.getLogger().warning("Invalid Discord channel ID specified for '" + configKey + "': " + channelId);
            return;
        }

        EmbedBuilder embedBuilder = new EmbedBuilder();

        // Build placeholders
        Map<String, String> placeholders;
        if (p != null) {
            placeholders = createPlaceholdersFromPunishment(p);
        } else {
            placeholders = unbanPlaceholders;
        }

        // Set color
        try {
            embedBuilder.setColor(Color.decode(msgConfig.getString("color", "#FFFFFF")));
        } catch (NumberFormatException e) {
            plugin.getLogger().warning("Invalid color format for Discord message '" + configKey + "'. Using default.");
            embedBuilder.setColor(Color.WHITE);
        }

        // Set content
        embedBuilder.setTitle(replacePlaceholders(msgConfig.getString("title", ""), placeholders));
        embedBuilder.setDescription(replacePlaceholders(msgConfig.getString("description", ""), placeholders));

        String footer = msgConfig.getString("footer");
        if (footer != null && !footer.isEmpty()) {
            embedBuilder.setFooter(replacePlaceholders(footer, placeholders));
        }

        // Add fields
        List<Map<?, ?>> fields = msgConfig.getMapList("fields");
        for (Map<?, ?> field : fields) {
            try {
                String name = replacePlaceholders(field.get("name").toString(), placeholders);
                String value = replacePlaceholders(field.get("value").toString(), placeholders);

                // **THE FIX:** Correctly and safely get the boolean value from the map.
                Object inlineObj = field.get("inline");
                boolean inline = false;
                if (inlineObj instanceof Boolean) {
                    inline = (Boolean) inlineObj;
                }

                if (!name.isBlank() && !value.isBlank()) {
                    embedBuilder.addField(name, value, inline);
                }
            } catch (Exception e) {
                plugin.getLogger().warning("Could not parse a field for Discord message '" + configKey + "'. Please check its format.");
            }
        }

        Message message = new MessageBuilder().setEmbeds(embedBuilder.build()).build();
        DiscordUtil.queueMessage(textChannel, message);
    }

    private String replacePlaceholders(String text, Map<String, String> placeholders) {
        if (text == null || text.isEmpty()) return "";
        for (Map.Entry<String, String> entry : placeholders.entrySet()) {
            text = text.replace(entry.getKey(), entry.getValue());
        }
        return text;
    }

    private Map<String, String> createPlaceholdersFromPunishment(Punishment p) {
        Map<String, String> placeholders = new HashMap<>();
        placeholders.put("{player}", p.getTargetName() != null ? p.getTargetName() : "N/A");
        placeholders.put("{staff}", p.getStaffName() != null ? p.getStaffName() : "Console");
        placeholders.put("{reason}", p.getReason() != null ? p.getReason() : "Unspecified");
        placeholders.put("{id}", p.getPunishmentId() != null ? p.getPunishmentId() : "N/A");
        placeholders.put("{server}", p.getServerOrigin() != null ? p.getServerOrigin() : "Global");

        String duration = "Permanent";
        if (p.getType().isTemporary()) {
            duration = TimeUtil.formatDuration(p.getRemainingTime() / 1000);
        }
        placeholders.put("{duration}", duration);

        return placeholders;
    }

    private void fetchBlacklist() {
        try {
            URL url = new URL(config.getMCBlacklistUrl());
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            Map<String, Object> map = new Gson().fromJson(new InputStreamReader(connection.getInputStream()), type);

            mcBlacklist.clear();
            mcBlacklist.addAll(map.keySet());
            plugin.getLogger().info("Successfully fetched " + mcBlacklist.size() + " entries from MCBlacklist.");
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to fetch from MCBlacklist", e);
        }
    }

    public CompletableFuture<Boolean> checkMcBlacklist(UUID uuid) {
        if (!config.isMCBlacklistEnabled()) {
            return CompletableFuture.completedFuture(false);
        }
        return CompletableFuture.completedFuture(mcBlacklist.contains(uuid.toString()));
    }

    public void reload() {
        initialize();
    }

    public void shutdown() {
        // Clean up tasks if needed
    }
}