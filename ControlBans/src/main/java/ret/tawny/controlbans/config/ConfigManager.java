package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public class ConfigManager {

    private final ControlBansPlugin plugin;
    private FileConfiguration config;
    private final Map<String, Object> cachedValues = new ConcurrentHashMap<>();

    public ConfigManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public void loadConfig() {
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        cachedValues.clear();
        plugin.getLogger().info("Configuration loaded");
    }

    private <T extends Number> T getNumber(String key, T defaultValue) {
        Object value = config.get(key, defaultValue);
        if (value instanceof Number num) {
            if (defaultValue instanceof Long) return (T) Long.valueOf(num.longValue());
            if (defaultValue instanceof Integer) return (T) Integer.valueOf(num.intValue());
            if (defaultValue instanceof Double) return (T) Double.valueOf(num.doubleValue());
        }
        return defaultValue;
    }

    private <T> T getCachedOrLoad(String key, Class<T> type, T defaultValue) {
        return type.cast(cachedValues.computeIfAbsent(key, k -> {
            if (defaultValue instanceof Number) {
                return getNumber(k, (Number) defaultValue);
            }
            return config.get(k, defaultValue);
        }));
    }

    // Language Configuration
    public String getLanguage() { return getCachedOrLoad("language", String.class, "en"); }

    // Database Configuration
    public String getDatabaseType() { return getCachedOrLoad("database.type", String.class, "sqlite"); }
    public String getDatabaseHost() { return getCachedOrLoad("database.host", String.class, "localhost"); }
    public int getDatabasePort() { return getCachedOrLoad("database.port", Integer.class, 3306); }
    public String getDatabaseName() { return getCachedOrLoad("database.database", String.class, "controlbans"); }
    public String getDatabaseUsername() { return getCachedOrLoad("database.username", String.class, "root"); }
    public String getDatabasePassword() { return getCachedOrLoad("database.password", String.class, "password"); }
    public String getSqliteFile() { return getCachedOrLoad("database.sqlite-file", String.class, "punishments.db"); }
    public int getPoolMaximumSize() { return getCachedOrLoad("database.pool.maximum-pool-size", Integer.class, 10); }
    public int getPoolMinimumIdle() { return getCachedOrLoad("database.pool.minimum-idle", Integer.class, 5); }
    public long getConnectionTimeout() { return getCachedOrLoad("database.pool.connection-timeout", Long.class, 30000L); }
    public long getIdleTimeout() { return getCachedOrLoad("database.pool.idle-timeout", Long.class, 300000L); }
    public long getMaxLifetime() { return getCachedOrLoad("database.pool.max-lifetime", Long.class, 1800000L); }

    // Alt Punishment Configuration
    public boolean isAltPunishEnabled() { return getCachedOrLoad("alts-punish.enabled", Boolean.class, false); }
    public double getAltMinConfidence() { return getCachedOrLoad("alts-punish.safety.min-confidence", Double.class, 0.8); }
    public int getAltMaxPunishments() { return getCachedOrLoad("alts-punish.safety.max-alts", Integer.class, 5); }
    public long getAltCooldown() { return getCachedOrLoad("alts-punish.safety.cooldown", Long.class, 300L); }

    // Web Configuration
    public boolean isWebEnabled() { return getCachedOrLoad("web.enabled", Boolean.class, false); }
    public String getWebHost() { return getCachedOrLoad("web.host", String.class, "0.0.0.0"); }
    public int getWebPort() { return getCachedOrLoad("web.port", Integer.class, 8080); }
    public String getWebAdminToken() { return getCachedOrLoad("web.admin-token", String.class, "change-me-please"); }
    public List<String> getWebAllowedHosts() { return config.getStringList("web.allowed-hosts"); }
    public int getWebRecordsPerPage() { return getCachedOrLoad("web.records-per-page", Integer.class, 50); }

    // Integration Configuration
    public boolean isDiscordEnabled() { return getCachedOrLoad("integrations.discord.enabled", Boolean.class, false); }
    public ConfigurationSection getDiscordMessageConfig(String type) {
        return config.getConfigurationSection("integrations.discord.messages." + type);
    }
    public boolean isGeyserEnabled() { return getCachedOrLoad("integrations.geyser.enabled", Boolean.class, false); }
    public String getBedrockPrefix() { return getCachedOrLoad("integrations.geyser.bedrock-prefix", String.class, "."); }
    public boolean isMCBlacklistEnabled() { return getCachedOrLoad("integrations.mcblacklist.enabled", Boolean.class, false); }
    public String getMCBlacklistUrl() { return getCachedOrLoad("integrations.mcblacklist.firebase-url", String.class, "https://mcblacklistdb-default-rtdb.firebaseio.com/players.json"); }
    public int getMCBlacklistCheckInterval() { return getCachedOrLoad("integrations.mcblacklist.check-interval", Integer.class, 60); }
    public String getMCBlacklistReason() { return getCachedOrLoad("integrations.mcblacklist.reason", String.class, "Player found on MCBlacklist database"); }
    public boolean isVoiceChatIntegrationEnabled() { return getCachedOrLoad("integrations.voicechat.enabled", Boolean.class, false); }

    // Cache Configuration
    public boolean isCacheEnabled() { return getCachedOrLoad("cache.enabled", Boolean.class, true); }
    public long getPlayerLookupTTL() { return getCachedOrLoad("cache.ttl.player-lookup", Long.class, 300L); }
    public long getPunishmentCheckTTL() { return getCachedOrLoad("cache.ttl.punishment-check", Long.class, 60L); }
    public long getAltLookupTTL() { return getCachedOrLoad("cache.ttl.alt-lookup", Long.class, 600L); }
    public int getCacheMaxSize() { return getCachedOrLoad("cache.max-size", Integer.class, 10000); }

    // Punishment Configuration
    public String getDefaultBanReason() { return getCachedOrLoad("punishments.default-reasons.ban", String.class, "Unspecified"); }
    public String getDefaultMuteReason() { return getCachedOrLoad("punishments.default-reasons.mute", String.class, "Unspecified"); }
    public String getDefaultWarnReason() { return getCachedOrLoad("punishments.default-reasons.warn", String.class, "Unspecified"); }
    public String getDefaultKickReason() { return getCachedOrLoad("punishments.default-reasons.kick", String.class, "Disconnected"); }
    public long getMaxTempBanDuration() { return getCachedOrLoad("punishments.max-duration.tempban", Long.class, 2592000L); }
    public long getMaxTempMuteDuration() { return getCachedOrLoad("punishments.max-duration.tempmute", Long.class, 604800L); }
    public boolean isBroadcastEnabled() { return getCachedOrLoad("punishments.broadcast.enabled", Boolean.class, true); }
    public boolean isBroadcastConsole() { return getCachedOrLoad("punishments.broadcast.console", Boolean.class, true); }
    public boolean isBroadcastPlayers() { return getCachedOrLoad("punishments.broadcast.players", Boolean.class, true); }
    public boolean isSilentByDefault() { return getCachedOrLoad("punishments.broadcast.silent-by-default", Boolean.class, false); }

    // Appeal Configuration
    public boolean isAppealsEnabled() { return getCachedOrLoad("appeals.enabled", Boolean.class, true); }
    public Duration getAppealCooldown() {
        long hours = getCachedOrLoad("appeals.cooldown-hours", Long.class, 24L);
        return Duration.ofHours(Math.max(0L, hours));
    }
    public int getAppealMaxSubmissions() {
        return Math.max(0, getCachedOrLoad("appeals.max-submissions", Integer.class, 3));
    }
    public Duration getAppealWindowDuration() {
        long days = getCachedOrLoad("appeals.window-days", Long.class, 7L);
        if (days <= 0) {
            return Duration.ZERO;
        }
        return Duration.ofDays(days);
    }

    // Void Jail Configuration
    public String getJailWorld() { return getCachedOrLoad("void-jail.location.world", String.class, "world"); }
    public double getJailX() { return getCachedOrLoad("void-jail.location.x", Double.class, 0.5); }
    public double getJailY() { return getCachedOrLoad("void-jail.location.y", Double.class, -1000.0); }
    public double getJailZ() { return getCachedOrLoad("void-jail.location.z", Double.class, 0.5); }
    public List<String> getJailAllowedCommands() { return config.getStringList("void-jail.allowed-commands"); }
}