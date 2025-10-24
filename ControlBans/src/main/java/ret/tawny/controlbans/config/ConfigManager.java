package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
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
    public boolean isMCBlacklistEnabled() { return getCachedOrLoad("integrations.mcblacklist.enabled", Boolean.class, false); }
    public String getMCBlacklistUrl() { return getCachedOrLoad("integrations.mcblacklist.firebase-url", String.class, "https://mcblacklistdb-default-rtdb.firebaseio.com/players.json"); }
    public int getMCBlacklistCheckInterval() { return getCachedOrLoad("integrations.mcblacklist.check-interval", Integer.class, 60); }
    public String getMCBlacklistReason() { return getCachedOrLoad("integrations.mcblacklist.reason", String.class, "Player found on MCBlacklist database"); }

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

    // Punishment workflow enhancements
    public List<String> getSuggestedReasons() {
        List<String> suggestions = config.getStringList("punishments.suggested-reasons");
        return suggestions.isEmpty() ? List.of("Griefing", "Cheating", "Harassment") : suggestions;
    }

    public boolean isWarnDecayEnabled() { return getCachedOrLoad("punishments.warn-decay.enabled", Boolean.class, true); }
    public Duration getWarnDecayDuration() {
        long days = getCachedOrLoad("punishments.warn-decay.days", Long.class, 30L);
        return Duration.ofDays(Math.max(1, days));
    }

    public boolean isEscalationEnabled() { return getCachedOrLoad("punishments.escalation.enabled", Boolean.class, true); }
    public Duration getEscalationCooldown() {
        long hours = getCachedOrLoad("punishments.escalation.cooldown-hours", Long.class, 12L);
        return Duration.ofHours(Math.max(0, hours));
    }

    public Map<Integer, EscalationRule> getEscalationRules() {
        String path = "punishments.escalation.warn-thresholds";
        ConfigurationSection section = config.getConfigurationSection(path);
        if (section == null) {
            Map<Integer, EscalationRule> defaults = new LinkedHashMap<>();
            defaults.put(3, new EscalationRule("tempban", Duration.ofHours(24), false));
            defaults.put(5, new EscalationRule("ban", Duration.ofSeconds(-1), false));
            return defaults;
        }

        Map<Integer, EscalationRule> rules = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            try {
                int threshold = Integer.parseInt(key);
                String value = section.getString(key, "ban:permanent");
                rules.put(threshold, parseEscalationValue(value));
            } catch (NumberFormatException ignored) {
            }
        }
        if (rules.isEmpty()) {
            rules.put(3, new EscalationRule("tempban", Duration.ofHours(24), false));
        }
        return rules;
    }

    private EscalationRule parseEscalationValue(String value) {
        String normalized = Optional.ofNullable(value).orElse("ban:permanent").trim().toLowerCase(Locale.ROOT);
        boolean ip = normalized.contains("ipban");
        normalized = normalized.replace("ipban", "");
        String[] parts = normalized.split(":");
        String action = parts[0].isBlank() ? "ban" : parts[0];
        Duration duration = Duration.ofSeconds(-1);
        if (parts.length > 1) {
            duration = parseDuration(parts[1]);
        }
        return new EscalationRule(action, duration, ip);
    }

    private Duration parseDuration(String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase("permanent")) {
            return Duration.ofSeconds(-1);
        }
        try {
            if (token.endsWith("d")) {
                return Duration.ofDays(Long.parseLong(token.substring(0, token.length() - 1)));
            }
            if (token.endsWith("h")) {
                return Duration.ofHours(Long.parseLong(token.substring(0, token.length() - 1)));
            }
            if (token.endsWith("m")) {
                return Duration.ofMinutes(Long.parseLong(token.substring(0, token.length() - 1)));
            }
            long seconds = Long.parseLong(token);
            return Duration.ofSeconds(seconds);
        } catch (NumberFormatException ex) {
            return Duration.ofSeconds(-1);
        }
    }

    public String getDefaultCategory() { return getCachedOrLoad("punishments.categories.default", String.class, "general"); }

    public Map<String, CategoryDefinition> getCategoryDefinitions() {
        ConfigurationSection section = config.getConfigurationSection("punishments.categories.definitions");
        if (section == null) {
            Map<String, CategoryDefinition> defaults = new LinkedHashMap<>();
            defaults.put("general", new CategoryDefinition("general", List.of("general"), 1));
            defaults.put("chat", new CategoryDefinition("chat", List.of("chat", "caps", "spam"), 1));
            defaults.put("cheating", new CategoryDefinition("cheating", List.of("cheat", "hack"), 3));
            return defaults;
        }

        Map<String, CategoryDefinition> definitions = new LinkedHashMap<>();
        for (String key : section.getKeys(false)) {
            ConfigurationSection child = section.getConfigurationSection(key);
            if (child == null) continue;
            List<String> triggers = child.getStringList("triggers");
            int weight = child.getInt("weight", 1);
            definitions.put(key, new CategoryDefinition(key, triggers.isEmpty() ? List.of(key) : triggers, Math.max(1, weight)));
        }
        return definitions;
    }

    // Scheduling & reminders
    public boolean isSchedulingEnabled() { return getCachedOrLoad("punishments.scheduling.enabled", Boolean.class, true); }
    public long getSchedulingLookaheadDays() { return getCachedOrLoad("punishments.scheduling.max-future-days", Long.class, 30L); }
    public long getSchedulingCheckIntervalSeconds() { return getCachedOrLoad("punishments.scheduling.check-interval", Long.class, 60L); }
    public Duration getReminderLeadTime() {
        long minutes = getCachedOrLoad("punishments.reminders.lead-time-minutes", Long.class, 15L);
        return Duration.ofMinutes(Math.max(1, minutes));
    }

    // Appeals
    public Duration getAppealReminderInterval() {
        long days = getCachedOrLoad("appeals.remind-every-days", Long.class, 3L);
        return Duration.ofDays(Math.max(1, days));
    }
    public String getDefaultAppealStatus() { return getCachedOrLoad("appeals.default-status", String.class, "OPEN"); }
    public boolean isAppealNotificationsEnabled() { return getCachedOrLoad("appeals.notify-staff", Boolean.class, true); }

    // Analytics & monitoring
    public boolean isBenchmarkEnabled() { return getCachedOrLoad("monitoring.benchmark.enabled", Boolean.class, true); }
    public long getBenchmarkSampleIntervalSeconds() { return getCachedOrLoad("monitoring.benchmark.sample-interval", Long.class, 60L); }
    public long getBenchmarkWindowMinutes() { return getCachedOrLoad("monitoring.benchmark.window-minutes", Long.class, 15L); }
    public boolean isHealthcheckEnabled() { return getCachedOrLoad("monitoring.healthcheck.enabled", Boolean.class, true); }
    public boolean isHealthcheckTokenRequired() { return getCachedOrLoad("monitoring.healthcheck.require-token", Boolean.class, false); }
    public String getHealthcheckToken() { return getCachedOrLoad("monitoring.healthcheck.token", String.class, "controlbans-health"); }
    public int getAnalyticsRecentDays() { return getCachedOrLoad("monitoring.analytics.recent-days", Integer.class, 7); }

    public List<String> getRoadmapMarkers() {
        List<String> entries = config.getStringList("project.roadmap");
        if (!entries.isEmpty()) {
            return entries;
        }
        return List.of(
                "Automatic escalation",
                "Scheduled punishments",
                "Warn decay",
                "Punishment category system"
        );
    }

    public record EscalationRule(String action, Duration duration, boolean ipBan) { }
    public record CategoryDefinition(String name, List<String> triggers, int weight) { }

}