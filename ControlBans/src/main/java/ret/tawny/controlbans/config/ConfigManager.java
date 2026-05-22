package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
        syncConfigWithDefaults();
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

    @SuppressWarnings("unchecked")
    private <T> T getCachedOrLoad(String key, Class<T> type, T defaultValue) {
        Object value = cachedValues.computeIfAbsent(key, k -> {
            if (defaultValue instanceof Number) {
                return getNumber(k, (Number) defaultValue);
            }
            Object raw = config.get(k, defaultValue);
            if (type == String.class && raw != null && !(raw instanceof String)) {
                return String.valueOf(raw);
            }
            return raw;
        });
        return type.cast(value);
    }

    public String getLanguage() { return getCachedOrLoad("language", String.class, "en"); }

    public String getDatabaseType() { return getCachedOrLoad("database.type", String.class, "sqlite"); }
    public String getDatabaseHost() { return getCachedOrLoad("database.host", String.class, "localhost"); }
    public int getDatabasePort() { return getCachedOrLoad("database.port", Integer.class, 3306); }
    public String getDatabaseName() { return getCachedOrLoad("database.name", String.class, "controlbans"); }
    public String getDatabaseUsername() { return getCachedOrLoad("database.username", String.class, "root"); }
    public String getDatabasePassword() { return getCachedOrLoad("database.password", String.class, "password"); }
    public String getSqliteFile() { return getCachedOrLoad("database.sqlite-file", String.class, "punishments.db"); }
    public String getH2File() { return getCachedOrLoad("database.h2-file", String.class, "punishments"); }

    public String getMongoConnectionString() { return getCachedOrLoad("database.mongodb.connection-string", String.class, "mongodb://localhost:27017"); }
    public String getMongoDatabase() { return getCachedOrLoad("database.mongodb.database", String.class, "controlbans"); }

    public boolean isRedisEnabled() { return getCachedOrLoad("redis.enabled", Boolean.class, false); }
    public String getRedisHost() { return getCachedOrLoad("redis.host", String.class, "localhost"); }
    public int getRedisPort() { return getCachedOrLoad("redis.port", Integer.class, 6379); }
    public String getRedisPassword() { return getCachedOrLoad("redis.password", String.class, ""); }
    public boolean isRedisPubSubEnabled() { return getCachedOrLoad("redis.pubsub", Boolean.class, true); }
    public int getRedisCacheTTL() { return getCachedOrLoad("redis.cache-ttl", Integer.class, 300); }

    public int getPoolMaximumSize() { return getCachedOrLoad("database.pool.maximum-pool-size", Integer.class, 10); }
    public int getPoolMinimumIdle() { return getCachedOrLoad("database.pool.minimum-idle", Integer.class, 5); }
    public long getConnectionTimeout() { return getCachedOrLoad("database.pool.connection-timeout", Long.class, 30000L); }
    public long getIdleTimeout() { return getCachedOrLoad("database.pool.idle-timeout", Long.class, 300000L); }
    public long getMaxLifetime() { return getCachedOrLoad("database.pool.max-lifetime", Long.class, 1800000L); }

    public boolean isAltPunishEnabled() { return getCachedOrLoad("alts-punish.enabled", Boolean.class, false); }
    public double getAltMinConfidence() { return getCachedOrLoad("alts-punish.safety.min-confidence", Double.class, 0.8); }
    public int getAltMaxPunishments() { return getCachedOrLoad("alts-punish.safety.max-alts", Integer.class, 5); }
    public long getAltCooldown() { return getCachedOrLoad("alts-punish.safety.cooldown", Long.class, 300L); }
    public int getAltIpAccountLimit() { return getCachedOrLoad("alts-punish.safety.ip-account-limit", Integer.class, 15); }

    public boolean isDiscordEnabled() { return getCachedOrLoad("integrations.discord.enabled", Boolean.class, false); }
    public String getDiscordWebhookUrl() { return getCachedOrLoad("integrations.discord.webhook-url", String.class, ""); }
    public ConfigurationSection getDiscordMessageConfig(String type) { return config.getConfigurationSection("integrations.discord.messages." + type); }
    public boolean isGeyserEnabled() { return getCachedOrLoad("integrations.geyser.enabled", Boolean.class, false); }
    public String getBedrockPrefix() { return getCachedOrLoad("integrations.geyser.bedrock-prefix", String.class, "."); }
    public boolean isMCBlacklistEnabled() { return getCachedOrLoad("integrations.mcblacklist.enabled", Boolean.class, false); }
    public String getMCBlacklistUrl() { return getCachedOrLoad("integrations.mcblacklist.firebase-url", String.class, "https://mcblacklistdb-default-rtdb.firebaseio.com/players.json"); }
    public int getMCBlacklistCheckInterval() { return getCachedOrLoad("integrations.mcblacklist.check-interval", Integer.class, 60); }
    public String getMCBlacklistReason() { return getCachedOrLoad("integrations.mcblacklist.reason", String.class, "Player found on MCBlacklist database"); }
    public boolean isVoiceChatIntegrationEnabled() { return getCachedOrLoad("integrations.voicechat.enabled", Boolean.class, false); }

    public boolean isCacheEnabled() { return getCachedOrLoad("cache.enabled", Boolean.class, true); }
    public long getPlayerLookupTTL() { return getCachedOrLoad("cache.ttl.player-lookup", Long.class, 300L); }
    public long getPunishmentCheckTTL() { return getCachedOrLoad("cache.ttl.punishment-check", Long.class, 60L); }
    public long getAltLookupTTL() { return getCachedOrLoad("cache.ttl.alt-lookup", Long.class, 600L); }
    public int getCacheMaxSize() { return getCachedOrLoad("cache.max-size", Integer.class, 10000); }

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

    public boolean isStaffAlertsEnabled() { return getCachedOrLoad("staff-alerts.enabled", Boolean.class, true); }
    public boolean isStaffAlertSoundEnabled() { return getCachedOrLoad("staff-alerts.sound.enabled", Boolean.class, true); }
    public String getStaffAlertSound() { return getCachedOrLoad("staff-alerts.sound.type", String.class, "BLOCK_NOTE_BLOCK_PLING"); }
    public float getStaffAlertVolume() { return getCachedOrLoad("staff-alerts.sound.volume", Double.class, 0.7).floatValue(); }
    public float getStaffAlertPitch() { return getCachedOrLoad("staff-alerts.sound.pitch", Double.class, 1.5).floatValue(); }

    public boolean isAppealsEnabled() { return getCachedOrLoad("appeals.enabled", Boolean.class, true); }
    public Duration getAppealCooldown() {
        long hours = getCachedOrLoad("appeals.cooldown-hours", Long.class, 24L);
        return Duration.ofHours(Math.max(0L, hours));
    }
    public int getAppealMaxSubmissions() { return Math.max(0, getCachedOrLoad("appeals.max-submissions", Integer.class, 3)); }
    public Duration getAppealWindowDuration() {
        long days = getCachedOrLoad("appeals.window-days", Long.class, 7L);
        if (days <= 0) return Duration.ZERO;
        return Duration.ofDays(days);
    }

    public String getJailWorld() { return getCachedOrLoad("void-jail.location.world", String.class, "world"); }
    public double getJailX() { return getCachedOrLoad("void-jail.location.x", Double.class, 0.5); }
    public double getJailY() { return getCachedOrLoad("void-jail.location.y", Double.class, -1000.0); }
    public double getJailZ() { return getCachedOrLoad("void-jail.location.z", Double.class, 0.5); }
    public List<String> getJailAllowedCommands() { return config.getStringList("void-jail.allowed-commands"); }

    public boolean isEscalationEnabled() { return getCachedOrLoad("escalation.enabled", Boolean.class, false); }
    public boolean isEscalationReasonFiltered() { return getCachedOrLoad("escalation.filter-by-reason", Boolean.class, true); }
    public int getEscalationHistoryLimit() { return Math.max(1, getCachedOrLoad("escalation.history-limit", Integer.class, 100)); }
    public long getEscalationWindowDays() { return Math.max(0L, getCachedOrLoad("escalation.window-days", Long.class, 90L)); }
    public boolean isEscalationBanIncluded() { return getCachedOrLoad("escalation.include.bans", Boolean.class, true); }
    public boolean isEscalationTempBanIncluded() { return getCachedOrLoad("escalation.include.tempbans", Boolean.class, true); }
    public boolean isEscalationIpBanIncluded() { return getCachedOrLoad("escalation.include.ipbans", Boolean.class, true); }

    public String resolveEscalationTrack(String reason) {
        if (!isEscalationReasonFiltered()) {
            return "default";
        }

        String normalizedReason = normalizeEscalationKey(reason);
        if (normalizedReason.isEmpty()) {
            return "default";
        }

        ConfigurationSection levelsSection = config.getConfigurationSection("escalation.levels");
        if (levelsSection != null) {
            for (String track : levelsSection.getKeys(false)) {
                if (normalizeEscalationKey(track).equals(normalizedReason)) {
                    return track;
                }
            }
        }

        ConfigurationSection aliasesSection = config.getConfigurationSection("escalation.aliases");
        if (aliasesSection != null) {
            for (String canonicalTrack : aliasesSection.getKeys(false)) {
                if (normalizeEscalationKey(canonicalTrack).equals(normalizedReason)) {
                    return canonicalTrack;
                }

                for (String alias : aliasesSection.getStringList(canonicalTrack)) {
                    if (normalizeEscalationKey(alias).equals(normalizedReason)) {
                        return canonicalTrack;
                    }
                }
            }
        }

        return "default";
    }

    public Map<Integer, String> getEscalationLevels(String reason) {
        String track = resolveEscalationTrack(reason);
        if (config.isConfigurationSection("escalation.levels." + track)) {
            return getLevels("escalation.levels." + track);
        }
        if (config.isConfigurationSection("escalation.levels.default")) {
            return getLevels("escalation.levels.default");
        }
        return Collections.emptyMap();
    }

    private Map<Integer, String> getLevels(String path) {
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) return Collections.emptyMap();

        return section.getKeys(false).stream()
                .filter(key -> key.matches("\\d+"))
                .collect(Collectors.toMap(Integer::parseInt, section::getString));
    }

    private String normalizeEscalationKey(String input) {
        if (input == null) {
            return "";
        }

        String cleaned = input.toLowerCase()
                .replaceAll("<[^>]+>", "")
                .replaceAll("&[0-9a-fk-or]", "")
                .replaceAll("[^a-z0-9]+", "");
        return cleaned.trim();
    }

    private void syncConfigWithDefaults() {
        try (InputStream defaultConfigStream = plugin.getResource("config.yml")) {
            if (defaultConfigStream == null) {
                plugin.getLogger().warning("Default config.yml is missing from the plugin JAR.");
                return;
            }

            FileConfiguration defaultConfig = YamlConfiguration.loadConfiguration(new InputStreamReader(defaultConfigStream, StandardCharsets.UTF_8));
            if (mergeMissingValues(defaultConfig, config, "")) {
                plugin.saveConfig();
                plugin.getLogger().info("Backfilled missing config.yml keys from bundled defaults.");
            }
        } catch (Exception exception) {
            plugin.getLogger().warning("Failed to sync config.yml defaults: " + exception.getMessage());
        }
    }

    private boolean mergeMissingValues(FileConfiguration source, FileConfiguration target, String path) {
        boolean changed = false;
        ConfigurationSection sourceSection = path.isEmpty() ? source : source.getConfigurationSection(path);
        if (sourceSection == null) {
            return false;
        }

        Set<String> keys = sourceSection.getKeys(false);
        for (String key : keys) {
            String childPath = path.isEmpty() ? key : path + "." + key;
            if (source.isConfigurationSection(childPath)) {
                if (!target.isConfigurationSection(childPath) && !target.contains(childPath)) {
                    target.createSection(childPath);
                    changed = true;
                }
                changed |= mergeMissingValues(source, target, childPath);
                continue;
            }

            if (!target.contains(childPath)) {
                target.set(childPath, source.get(childPath));
                changed = true;
            }
        }
        return changed;
    }
}
