package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class IntegrationService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private final Gson gson = new Gson();
    private final ExecutorService executor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "ControlBans-Integration");
        t.setDaemon(true);
        return t;
    });

    public IntegrationService(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() {
        if (config.isDiscordEnabled()) {
            plugin.getLogger().info("Discord integration active (Webhook-mode).");
        }
    }

    public void onPunishment(Punishment p) {
        if (p != null && p.isSilent()) return;
        if (config.isDiscordEnabled()) {
            String typeKey = p.getType().name().toLowerCase();
            sendDiscordEmbed(p, typeKey, null);
        }
    }

    public void onUnban(String targetName, String staffName) {
        if (config.isDiscordEnabled()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{player}", targetName);
            placeholders.put("{staff}", staffName);
            sendDiscordEmbed(null, "unban", placeholders);
        }
    }

    public void onReport(ReportService.Report r) {
        if (config.isDiscordEnabled()) {
            Map<String, String> placeholders = new HashMap<>();
            placeholders.put("{reporter}", r.reporterName() != null ? r.reporterName() : "Unknown");
            placeholders.put("{target}", r.targetName() != null ? r.targetName() : "Unknown");
            placeholders.put("{reason}", r.reason() != null ? r.reason() : "No reason provided");
            sendDiscordEmbed(null, "report", placeholders);
        }
    }

    private void sendDiscordEmbed(Punishment p, String configKey, Map<String, String> extraPlaceholders) {
        ConfigurationSection msgConfig = config.getDiscordMessageConfig(configKey);
        if (msgConfig == null || !msgConfig.getBoolean("enabled", false)) {
            return;
        }

        String webhookUrl = msgConfig.getString("webhook-url", config.getDiscordWebhookUrl());
        if (webhookUrl == null || webhookUrl.isBlank() || webhookUrl.equals("YOUR_WEBHOOK_HERE")) {
            if (Bukkit.getPluginManager().isPluginEnabled("DiscordSRV")) {
                sendViaDiscordSRV(p, configKey, extraPlaceholders, msgConfig);
            } else {
                plugin.getLogger().warning("Discord message '" + configKey + "' enabled but no Webhook URL or DiscordSRV found.");
            }
            return;
        }

        Map<String, String> placeholders = (p != null) ? createPlaceholdersFromPunishment(p) : (extraPlaceholders != null ? extraPlaceholders : new HashMap<>());
        
        JsonObject payload = new JsonObject();
        JsonArray embeds = new JsonArray();
        JsonObject embed = new JsonObject();

        embed.addProperty("title", replacePlaceholders(msgConfig.getString("title", ""), placeholders));
        embed.addProperty("description", replacePlaceholders(msgConfig.getString("description", ""), placeholders));
        
        String colorStr = msgConfig.getString("color", "#FFFFFF").replace("#", "");
        try {
            embed.addProperty("color", Integer.parseInt(colorStr, 16));
        } catch (Exception ignored) {}

        String footer = msgConfig.getString("footer");
        if (footer != null && !footer.isEmpty()) {
            JsonObject footerObj = new JsonObject();
            footerObj.addProperty("text", replacePlaceholders(footer, placeholders));
            embed.add("footer", footerObj);
        }

        JsonArray fields = new JsonArray();
        List<Map<?, ?>> fieldList = msgConfig.getMapList("fields");
        for (Map<?, ?> fieldData : fieldList) {
            JsonObject field = new JsonObject();
            field.addProperty("name", replacePlaceholders(String.valueOf(fieldData.get("name")), placeholders));
            field.addProperty("value", replacePlaceholders(String.valueOf(fieldData.get("value")), placeholders));
            field.addProperty("inline", fieldData.get("inline") instanceof Boolean && (Boolean) fieldData.get("inline"));
            fields.add(field);
        }
        embed.add("fields", fields);
        embeds.add(embed);
        payload.add("embeds", embeds);

        CompletableFuture.runAsync(() -> {
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(5000);
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
                }
                int responseCode = conn.getResponseCode();
                if (responseCode >= 400) {
                    try (java.io.InputStream err = conn.getErrorStream()) {
                        if (err != null) err.readAllBytes();
                    }
                    plugin.getLogger().warning("Discord webhook returned HTTP " + responseCode);
                } else {
                    try (java.io.InputStream ok = conn.getInputStream()) {
                        ok.readAllBytes();
                    }
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send Discord webhook", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        }, executor);
    }

    private void sendViaDiscordSRV(Punishment p, String configKey, Map<String, String> extraPlaceholders, ConfigurationSection msgConfig) {
        try {
            String channelId = msgConfig.getString("channel");
            if (channelId == null || channelId.isBlank()) return;

            Map<String, String> placeholders = (p != null) ? createPlaceholdersFromPunishment(p) : (extraPlaceholders != null ? extraPlaceholders : new HashMap<>());

            String title = replacePlaceholders(msgConfig.getString("title", ""), placeholders);
            String desc = replacePlaceholders(msgConfig.getString("description", ""), placeholders);
            String color = msgConfig.getString("color", "#FFFFFF");
            String footer = replacePlaceholders(msgConfig.getString("footer"), placeholders);

            List<String[]> fields = new ArrayList<>();
            for (Map<?, ?> fieldData : msgConfig.getMapList("fields")) {
                fields.add(new String[]{
                        replacePlaceholders(String.valueOf(fieldData.get("name")), placeholders),
                        replacePlaceholders(String.valueOf(fieldData.get("value")), placeholders),
                        String.valueOf(fieldData.get("inline") instanceof Boolean && (Boolean) fieldData.get("inline"))
                });
            }

            // Direct DiscordSRV API call — DiscordSRVIntegration is only loaded when DiscordSRV is present
            DiscordSRVIntegration.sendChannelEmbed(channelId, title, desc, color, footer, fields);
        } catch (NoClassDefFoundError | Exception e) {
            plugin.getLogger().log(Level.WARNING, "DiscordSRV message send failed (is DiscordSRV up to date?)", e);
        }
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

    public void reload() { initialize(); }
    public void shutdown() {
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException e) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
