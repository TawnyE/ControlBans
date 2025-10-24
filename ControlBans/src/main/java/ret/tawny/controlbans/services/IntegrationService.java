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
import org.bukkit.scheduler.BukkitTask;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.awt.Color;
import java.io.IOException;
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
import java.nio.charset.StandardCharsets;

public class IntegrationService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private boolean discordSrvEnabled = false;
    private final Set<String> mcBlacklist = new HashSet<>();
    private BukkitTask mcBlacklistTask;
    private final Gson gson = new Gson();

    // The punishmentService parameter was unused and has been removed.
    public IntegrationService(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() {
        discordSrvEnabled = false;

        if (!config.isDiscordEnabled()) {
            plugin.getLogger().info("DiscordSRV integration disabled in configuration.");
        } else if (!Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
            plugin.getLogger().warning("DiscordSRV integration enabled in config, but the DiscordSRV plugin was not found.");
        } else {
            this.discordSrvEnabled = true;
            plugin.getLogger().info("DiscordSRV integration enabled.");
        }

        stopMcBlacklistTask();
        if (config.isMCBlacklistEnabled()) {
            startMcBlacklistTask();
        } else {
            mcBlacklist.clear();
            plugin.getLogger().info("MCBlacklist integration disabled.");
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
        Map<String, String> placeholders = (p != null)
                ? createPlaceholdersFromPunishment(p)
                : (unbanPlaceholders != null ? unbanPlaceholders : new HashMap<>());

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
        if (text == null || text.isEmpty() || placeholders == null || placeholders.isEmpty()) return text != null ? text : "";
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
        HttpURLConnection connection = null;
        try {
            URL url = new URL(config.getMCBlacklistUrl());
            connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(5000);
            connection.setReadTimeout(5000);

            int responseCode = connection.getResponseCode();
            if (responseCode != HttpURLConnection.HTTP_OK) {
                plugin.getLogger().warning("MCBlacklist request failed with status " + responseCode + ".");
                return;
            }

            Type type = new TypeToken<Map<String, Object>>() {}.getType();
            try (InputStreamReader reader = new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8)) {
                Map<String, Object> map = gson.fromJson(reader, type);
                if (map == null) {
                    plugin.getLogger().warning("MCBlacklist returned an empty payload.");
                    return;
                }
                mcBlacklist.clear();
                mcBlacklist.addAll(map.keySet());
                int size = mcBlacklist.size();
                String noun = size == 1 ? "entry" : "entries";
                plugin.getLogger().info(String.format("Fetched %d MCBlacklist %s.", size, noun));
            }
        } catch (IOException ex) {
            plugin.getLogger().log(Level.WARNING, "Failed to read MCBlacklist response.", ex);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to fetch from MCBlacklist", e);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
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
        stopMcBlacklistTask();
        mcBlacklist.clear();
    }

    private void startMcBlacklistTask() {
        long intervalTicks = Math.max(1L, config.getMCBlacklistCheckInterval()) * 20L * 60L;
        mcBlacklistTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchBlacklist, 0L, intervalTicks);
        plugin.getLogger().info("MCBlacklist integration enabled. Checking every " + config.getMCBlacklistCheckInterval() + " minute(s).");
    }

    private void stopMcBlacklistTask() {
        if (mcBlacklistTask != null) {
            mcBlacklistTask.cancel();
            mcBlacklistTask = null;
        }
    }
}
