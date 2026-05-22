package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.scheduler.BukkitTask;
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
import java.util.logging.Level;

public class IntegrationService {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private final Gson gson = new Gson();
    private BukkitTask mcBlacklistTask;
    private final Set<String> mcBlacklist = new HashSet<>();

    public IntegrationService(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() {
        stopMcBlacklistTask();
        if (config.isMCBlacklistEnabled()) {
            startMcBlacklistTask();
        }

        if (config.isDiscordEnabled()) {
            plugin.getLogger().info("Discord integration active (Webhook-mode).");
        }
    }

    public void onPunishment(Punishment p) {
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
            try {
                HttpURLConnection conn = (HttpURLConnection) new URL(webhookUrl).openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(gson.toJson(payload).getBytes(StandardCharsets.UTF_8));
                }
                conn.getResponseCode();
                conn.disconnect();
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to send Discord webhook", e);
            }
        });
    }

    private void sendViaDiscordSRV(Punishment p, String configKey, Map<String, String> extraPlaceholders, ConfigurationSection msgConfig) {
        try {
            String channelId = msgConfig.getString("channel");
            if (channelId == null || channelId.isBlank()) return;

            Map<String, String> placeholders = (p != null) ? createPlaceholdersFromPunishment(p) : (extraPlaceholders != null ? extraPlaceholders : new HashMap<>());
            
            JsonObject embed = new JsonObject();
            embed.addProperty("title", replacePlaceholders(msgConfig.getString("title", ""), placeholders));
            embed.addProperty("description", replacePlaceholders(msgConfig.getString("description", ""), placeholders));
            
            Class<?> discordSrvClass = Class.forName("github.scarsz.discordsrv.DiscordSRV");
            Object srvPlugin = discordSrvClass.getMethod("getPlugin").invoke(null);
            Object textChannel = discordSrvClass.getMethod("getDestinationTextChannelForGameChannelName", String.class).invoke(srvPlugin, channelId);

            if (textChannel == null) {
                try {
                    Object jda = discordSrvClass.getMethod("getJda").invoke(srvPlugin);
                    if (jda != null) {
                        for (java.lang.reflect.Method m : jda.getClass().getMethods()) {
                            if (m.getName().equals("getTextChannelById") && m.getParameterCount() == 1 && m.getParameterTypes()[0] == String.class) {
                                textChannel = m.invoke(jda, channelId);
                                break;
                            }
                        }
                    }
                } catch (Exception ignored) {}
            }

            if (textChannel != null) {
                try {
                    Class<?> embedBuilderClass = Class.forName("github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder");
                    Object builder = embedBuilderClass.getDeclaredConstructor().newInstance();

                    String title = replacePlaceholders(msgConfig.getString("title", ""), placeholders);
                    if (!title.isEmpty()) embedBuilderClass.getMethod("setTitle", String.class).invoke(builder, title);
                    
                    String desc = replacePlaceholders(msgConfig.getString("description", ""), placeholders);
                    if (!desc.isEmpty()) embedBuilderClass.getMethod("setDescription", CharSequence.class).invoke(builder, desc);

                    String colorStr = msgConfig.getString("color", "#FFFFFF").replace("#", "");
                    try {
                        embedBuilderClass.getMethod("setColor", java.awt.Color.class).invoke(builder, new java.awt.Color(Integer.parseInt(colorStr, 16)));
                    } catch (Exception e1) {
                        try {
                            embedBuilderClass.getMethod("setColor", int.class).invoke(builder, Integer.parseInt(colorStr, 16));
                        } catch (Exception e2) {}
                    }

                    String footer = msgConfig.getString("footer");
                    if (footer != null && !footer.isEmpty()) {
                        footer = replacePlaceholders(footer, placeholders);
                        try {
                            embedBuilderClass.getMethod("setFooter", String.class, String.class).invoke(builder, footer, null);
                        } catch (Exception e) {
                            embedBuilderClass.getMethod("setFooter", CharSequence.class).invoke(builder, footer);
                        }
                    }

                    List<Map<?, ?>> fieldList = msgConfig.getMapList("fields");
                    for (Map<?, ?> fieldData : fieldList) {
                        String name = replacePlaceholders(String.valueOf(fieldData.get("name")), placeholders);
                        String value = replacePlaceholders(String.valueOf(fieldData.get("value")), placeholders);
                        boolean inline = fieldData.get("inline") instanceof Boolean && (Boolean) fieldData.get("inline");
                        embedBuilderClass.getMethod("addField", String.class, String.class, boolean.class)
                                         .invoke(builder, name, value, inline);
                    }

                    Object messageEmbed = embedBuilderClass.getMethod("build").invoke(builder);
                    boolean sent = false;

                    Class<?> discordUtil = Class.forName("github.scarsz.discordsrv.util.DiscordUtil");
                    for (java.lang.reflect.Method m : discordUtil.getMethods()) {
                        if ((m.getName().equals("queueMessage") || m.getName().equals("sendMessage")) && m.getParameterCount() == 2) {
                            if (m.getParameterTypes()[1].getSimpleName().equals("MessageEmbed") && m.getParameterTypes()[0].isInstance(textChannel)) {
                                m.invoke(null, textChannel, messageEmbed);
                                sent = true;
                                break;
                            }
                        }
                    }

                    if (!sent) {
                        for (java.lang.reflect.Method m : textChannel.getClass().getMethods()) {
                            if (m.getName().equals("sendMessageEmbeds")) {
                                Object action = null;
                                if (m.getParameterCount() == 1 && m.getParameterTypes()[0].isArray()) {
                                    Object array = java.lang.reflect.Array.newInstance(messageEmbed.getClass(), 1);
                                    java.lang.reflect.Array.set(array, 0, messageEmbed);
                                    action = m.invoke(textChannel, array);
                                } else if (m.getParameterCount() == 2 && m.getParameterTypes()[1].isArray()) {
                                    Object emptyArray = java.lang.reflect.Array.newInstance(messageEmbed.getClass(), 0);
                                    action = m.invoke(textChannel, messageEmbed, emptyArray);
                                } else {
                                    try { action = m.invoke(textChannel, messageEmbed); } catch (Exception ignored) {}
                                }
                                if (action != null) {
                                    action.getClass().getMethod("queue").invoke(action);
                                    sent = true;
                                    break;
                                }
                            } else if (m.getName().equals("sendMessage") && m.getParameterCount() == 1 && m.getParameterTypes()[0].getSimpleName().equals("MessageEmbed")) {
                                Object action = m.invoke(textChannel, messageEmbed);
                                action.getClass().getMethod("queue").invoke(action);
                                sent = true;
                                break;
                            }
                        }
                    }
                    
                    if (!sent) {
                        plugin.getLogger().warning("Could not find a method to send MessageEmbed to Discord channel.");
                    }
                } catch (Exception ex) {
                    plugin.getLogger().log(Level.WARNING, "Error building/sending DiscordSRV Embed", ex);
                }
            } else {
                plugin.getLogger().warning("DiscordSRV channel '" + channelId + "' not found. Make sure it's defined in DiscordSRV channels config or is a valid ID.");
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "DiscordSRV fallback failed (likely version mismatch)", e);
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

    private void fetchBlacklist() {
    }

    private void startMcBlacklistTask() {
        long intervalTicks = Math.max(1L, config.getMCBlacklistCheckInterval()) * 20L * 60L;
        mcBlacklistTask = Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, this::fetchBlacklist, 0L, intervalTicks);
    }

    private void stopMcBlacklistTask() {
        if (mcBlacklistTask != null) {
            mcBlacklistTask.cancel();
            mcBlacklistTask = null;
        }
    }

    public void reload() { initialize(); }
    public void shutdown() { stopMcBlacklistTask(); }
    public CompletableFuture<Boolean> checkMcBlacklist(UUID uuid) { return CompletableFuture.completedFuture(mcBlacklist.contains(uuid.toString())); }
}
