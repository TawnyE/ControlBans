package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.MessageBuilder;
import github.scarsz.discordsrv.dependencies.jda.api.entities.Message;
import github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel;
import github.scarsz.discordsrv.util.DiscordUtil;
import org.bukkit.Bukkit;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;

import java.awt.Color;
import java.io.InputStreamReader;
import java.lang.reflect.Type;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashSet;
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

    public IntegrationService(ControlBansPlugin plugin, ConfigManager config, PunishmentService punishmentService) {
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
            sendDiscordPunishment(p);
        }
    }

    public void onUnban(UUID targetUuid, String targetName, UUID staffUuid, String staffName) {
        if (discordSrvEnabled) {
            sendDiscordUnban(targetName, staffName);
        }
    }

    private void sendDiscordPunishment(Punishment p) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Player " + p.getType().getVerb());
        embedBuilder.addField("Player", p.getTargetName(), true);
        embedBuilder.addField("Staff", p.getStaffName(), true);
        embedBuilder.addField("Reason", p.getReason(), false);

        String channelId;
        switch (p.getType()) {
            case BAN, TEMPBAN, IPBAN -> {
                channelId = config.getDiscordBanChannel();
                embedBuilder.setColor(Color.RED);
                if (p.getType().isTemporary()) {
                    embedBuilder.addField("Duration", ret.tawny.controlbans.util.TimeUtil.formatDuration(p.getRemainingTime() / 1000), true);
                }
            }
            case MUTE, TEMPMUTE -> {
                channelId = config.getDiscordMuteChannel();
                embedBuilder.setColor(Color.ORANGE);
                if (p.getType().isTemporary()) {
                    embedBuilder.addField("Duration", ret.tawny.controlbans.util.TimeUtil.formatDuration(p.getRemainingTime() / 1000), true);
                }
            }
            case KICK -> {
                channelId = config.getDiscordKickChannel();
                embedBuilder.setColor(Color.YELLOW);
            }
            case WARN -> {
                channelId = config.getDiscordWarnChannel();
                embedBuilder.setColor(Color.CYAN);
            }
            default -> channelId = null;
        }

        if (channelId != null && !channelId.isBlank()) {
            TextChannel textChannel = DiscordUtil.getTextChannelById(channelId);
            if (textChannel != null) {
                // **THE FINAL FIX:** Create a Message object from the embed and queue it.
                Message message = new MessageBuilder().setEmbeds(embedBuilder.build()).build();
                DiscordUtil.queueMessage(textChannel, message);
            } else {
                plugin.getLogger().warning("Invalid Discord channel ID specified in config: " + channelId);
            }
        }
    }

    private void sendDiscordUnban(String targetName, String staffName) {
        EmbedBuilder embedBuilder = new EmbedBuilder();
        embedBuilder.setTitle("Player Unbanned");
        embedBuilder.setColor(Color.GREEN);
        embedBuilder.addField("Player", targetName, true);
        embedBuilder.addField("Unbanned by", staffName, true);

        String channelId = config.getDiscordUnbanChannel();
        if (channelId != null && !channelId.isBlank()) {
            TextChannel textChannel = DiscordUtil.getTextChannelById(channelId);
            if (textChannel != null) {
                // **THE FINAL FIX:** Create a Message object from the embed and queue it.
                Message message = new MessageBuilder().setEmbeds(embedBuilder.build()).build();
                DiscordUtil.queueMessage(textChannel, message);
            } else {
                plugin.getLogger().warning("Invalid Discord channel ID specified for unbans in config: " + channelId);
            }
        }
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