package ret.tawny.controlbans.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
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
        quarantineBrokenConfig();
        plugin.saveDefaultConfig();
        plugin.reloadConfig();
        config = plugin.getConfig();
        syncConfigWithDefaults();
        cachedValues.clear();
        // Keep timestamps consistent across the network — respect configured timezone/date-format
        try {
            ret.tawny.controlbans.util.TimeUtil.configure(getDateFormat(), getTimezoneId());
        } catch (Exception ignored) {}
        plugin.getLogger().info("Configuration loaded");
    }

    /**
     * Quarantines an unparseable config.yml instead of silently running on
     * defaults: Bukkit's {@code reloadConfig()} swallows YAML errors and loads
     * an EMPTY configuration, which would otherwise look like "everything is
     * missing" with no explanation. The broken file is renamed (never
     * deleted), a fresh default is generated in its place, and the user is
     * told how to re-apply their settings.
     */
    private void quarantineBrokenConfig() {
        File configFile = new File(plugin.getDataFolder(), "config.yml");
        if (!configFile.isFile()) {
            return;
        }
        String healthError = YamlHealth.validate(configFile);
        if (healthError == null) {
            return;
        }
        plugin.getLogger().warning("------------------------------------------------------------");
        plugin.getLogger().warning("config.yml could not be read (" + healthError + ").");
        plugin.getLogger().warning("This is usually a spacing or indentation mistake in the YAML.");
        File backup = YamlHealth.quarantine(configFile);
        if (backup != null) {
            plugin.getLogger().warning("Your old file was kept as " + backup.getName() + ".");
            try {
                plugin.saveResource("config.yml", true);
                plugin.getLogger().warning("A fresh default config.yml was created — re-apply your settings from the backup.");
            } catch (IllegalArgumentException extractFailure) {
                plugin.getLogger().severe("Could not extract a default config.yml from the plugin JAR: "
                        + extractFailure.getMessage());
            }
        } else {
            plugin.getLogger().warning("The file could not be renamed automatically — please fix or remove it by hand, then restart.");
        }
        plugin.getLogger().warning("------------------------------------------------------------");
    }

    public String getTimezoneId() { return getCachedOrLoad("time-format.timezone", String.class, "UTC"); }
    public String getDateFormat() { return getCachedOrLoad("time-format.date-format", String.class, "yyyy-MM-dd HH:mm:ss"); }
    public boolean isDebugLoggingEnabled() { return getCachedOrLoad("performance.debug-logging", Boolean.class, false); }
    public boolean isAsyncDatabaseEnabled() { return getCachedOrLoad("performance.async-database", Boolean.class, true); }

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
            if (type == Boolean.class) {
                if (raw instanceof Boolean) {
                    return raw;
                }
                plugin.getLogger().warning("Config key '" + k + "' must be a Boolean (true/false) but is "
                        + describeType(raw) + " — using default " + defaultValue);
                return defaultValue;
            }
            if (type == String.class) {
                if (raw instanceof String) {
                    return raw;
                }
                // Lenient coercion for simple scalars (e.g. an unquoted number), hard-fail otherwise
                if (raw instanceof Number || raw instanceof Boolean) {
                    return String.valueOf(raw);
                }
                plugin.getLogger().warning("Config key '" + k + "' must be a String but is "
                        + describeType(raw) + " — using default " + defaultValue);
                return defaultValue;
            }
            return raw;
        });
        return type.cast(value);
    }

    private static String describeType(Object raw) {
        return raw == null ? "null" : raw.getClass().getSimpleName() + " (" + raw + ")";
    }

    public String getLanguage() { return getCachedOrLoad("language", String.class, "en"); }

    public String getDatabaseType() { return getCachedOrLoad("database.type", String.class, "sqlite"); }
    public String getDatabaseHost() { return getCachedOrLoad("database.host", String.class, "localhost"); }
    public int getDatabasePort() { return getCachedOrLoad("database.port", Integer.class, 3306); }
    public String getDatabaseName() { return getCachedOrLoad("database.name", String.class, "controlbans"); }
    public String getDatabaseUsername() { return getCachedOrLoad("database.username", String.class, "root"); }
    public String getDatabasePassword() { return getCachedOrLoad("database.password", String.class, "password"); }
    public String getDatabaseSqliteFile() { return getCachedOrLoad("database.sqlite.file", String.class, "controlbans.db"); }

    public int getPoolMaximumSize() { return getCachedOrLoad("database.pool.maximum-pool-size", Integer.class, 10); }
    public int getPoolMinimumIdle() { return getCachedOrLoad("database.pool.minimum-idle", Integer.class, 5); }
    public long getConnectionTimeout() { return getCachedOrLoad("database.pool.connection-timeout", Long.class, 30000L); }
    public long getIdleTimeout() { return getCachedOrLoad("database.pool.idle-timeout", Long.class, 300000L); }
    public long getMaxLifetime() { return getCachedOrLoad("database.pool.max-lifetime", Long.class, 1800000L); }

    public boolean isStealthModeEnabled() { return getCachedOrLoad("stealth-mode.enabled", Boolean.class, false); }
    public String getStealthDummyIp() { return getCachedOrLoad("stealth-mode.dummy-ip", String.class, "0.0.0.0"); }
    public boolean isStealthBypassAltDetection() { return getCachedOrLoad("stealth-mode.bypass-alt-detection", Boolean.class, true); }
    public boolean isStealthSuppressAltAlerts() { return getCachedOrLoad("stealth-mode.suppress-alt-alerts", Boolean.class, true); }

    public boolean isAltPunishEnabled() { return getCachedOrLoad("alts-punish.enabled", Boolean.class, false); }
    public double getAltMinConfidence() { return getCachedOrLoad("alts-punish.safety.min-confidence", Double.class, 0.8); }
    public int getAltMaxPunishments() { return getCachedOrLoad("alts-punish.safety.max-alts", Integer.class, 5); }
    public long getAltCooldown() { return getCachedOrLoad("alts-punish.safety.cooldown", Long.class, 300L); }
    public int getAltIpAccountLimit() { return getCachedOrLoad("alts-punish.safety.ip-account-limit", Integer.class, 15); }
    public boolean isAltJoinAlertEnabled() { return getCachedOrLoad("alts-punish.join-alert.enabled", Boolean.class, true); }
    public int getAltJoinAlertMaxNames() { return getCachedOrLoad("alts-punish.join-alert.max-names", Integer.class, 5); }
  public boolean isAltBypassEnabled() { return getCachedOrLoad("alts-punish.bypass.enabled", Boolean.class, true); }
  public String getAltBypassPermission() { return getCachedOrLoad("alts-punish.bypass.permission", String.class, "controlbans.bypass.alts"); }

    public boolean isDiscordEnabled() { return getCachedOrLoad("integrations.discord.enabled", Boolean.class, false); }
    public String getDiscordWebhookUrl() { return getCachedOrLoad("integrations.discord.webhook-url", String.class, ""); }
    public ConfigurationSection getDiscordMessageConfig(String type) { return config.getConfigurationSection("integrations.discord.messages." + type); }
    public boolean isGeyserEnabled() { return getCachedOrLoad("integrations.geyser.enabled", Boolean.class, false); }
    public String getBedrockPrefix() { return getCachedOrLoad("integrations.geyser.bedrock-prefix", String.class, "."); }
    public boolean isVoiceChatIntegrationEnabled() { return getCachedOrLoad("integrations.voicechat.enabled", Boolean.class, false); }

    public boolean isCacheEnabled() { return getCachedOrLoad("cache.enabled", Boolean.class, true); }
    public long getPlayerLookupTTL() { return getCachedOrLoad("cache.ttl.player-lookup", Long.class, 300L); }
    public long getPunishmentCheckTTL() { return getCachedOrLoad("cache.ttl.punishment-check", Long.class, 60L); }
    public long getAltLookupTTL() { return getCachedOrLoad("cache.ttl.alt-lookup", Long.class, 600L); }

    public long getOfflineSuggestionsTTL() { return getCachedOrLoad("cache.ttl.offline-suggestions", Long.class, 30L); }
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

    public boolean isMutesBlockSigns() { return getCachedOrLoad("punishments.mutes.block-signs", Boolean.class, true); }
    public String getMutesSignReplacementText() { return getCachedOrLoad("punishments.mutes.sign-replacement-text", String.class, "[MUTED]"); }
    public List<String> getMutesBlockedCommands() { return config.getStringList("punishments.mutes.blocked-commands"); }

    public boolean isStaffAlertsEnabled() { return getCachedOrLoad("staff-alerts.enabled", Boolean.class, true); }
    public boolean isStaffAlertSoundEnabled() { return getCachedOrLoad("staff-alerts.sound.enabled", Boolean.class, true); }
    public String getStaffAlertSound() { return getCachedOrLoad("staff-alerts.sound.type", String.class, "BLOCK_NOTE_BLOCK_PLING"); }
    public float getStaffAlertVolume() { return getCachedOrLoad("staff-alerts.sound.volume", Double.class, 0.7).floatValue(); }
    public float getStaffAlertPitch() { return getCachedOrLoad("staff-alerts.sound.pitch", Double.class, 1.5).floatValue(); }

    public String getAppealLink() { return getCachedOrLoad("appeals.appeal-link", String.class, "discord.gg/yourserver"); }
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

    // /punish UI + escalation presets (escalation.yml)
    public boolean isEscalationEnabled() { return getCachedOrLoad("punish.escalation-enabled", Boolean.class, true); }
    public String getPunishInterface() { return getCachedOrLoad("punish.interface", String.class, "auto"); }
    public String getFreezeDiscord() { return getCachedOrLoad("freeze.discord", String.class, "discord.gg/yourserver"); }
    public String getReportNotifyPermission() { return getCachedOrLoad("report.notify-staff-permission", String.class, "controlbans.alerts.receive"); }
    public boolean isReportEnabled() { return getCachedOrLoad("report.enabled", Boolean.class, true); }
    public long getReportCooldownSeconds() { return getCachedOrLoad("report.cooldown-seconds", Long.class, 60L); }
    public long getReportDuplicateWindowSeconds() { return getCachedOrLoad("report.duplicate-window-seconds", Long.class, 300L); }
    public boolean isReportCustomReasonAllowed() { return getCachedOrLoad("report.allow-custom-reason", Boolean.class, true); }
    public boolean isReportTeleportPlayers() { return getCachedOrLoad("report.teleport-to-players", Boolean.class, true); }

    public List<ReportCategory> getReportCategories() {
        List<ReportCategory> categories = new ArrayList<>();
        List<Map<?, ?>> rawList = config.getMapList("report.categories");
        for (Map<?, ?> raw : rawList) {
            if (raw == null) continue;
            Object idObj = raw.get("id");
            String id = idObj != null ? String.valueOf(idObj) : "other";
            Object iconObj = raw.get("icon");
            org.bukkit.Material material = iconObj != null
                    ? org.bukkit.Material.matchMaterial(String.valueOf(iconObj))
                    : null;
            if (material == null) material = org.bukkit.Material.BARRIER;
            Object nameObj = raw.get("name");
            String name = nameObj != null ? String.valueOf(nameObj) : id;
            List<String> lore = new ArrayList<>();
            Object loreObj = raw.get("lore");
            if (loreObj instanceof List<?> loreList) {
                for (Object line : loreList) {
                    if (line != null) lore.add(String.valueOf(line));
                }
            }
            categories.add(new ReportCategory(id, material, name, lore));
        }
        if (categories.isEmpty()) {
            categories.add(new ReportCategory("other", org.bukkit.Material.BOOK, "<aqua>Other</aqua>", List.of("<gray>Something else.")));
        }
        return categories;
    }
    public int getJanitorBanRetentionDays() { return getCachedOrLoad("database.janitor.ban-retention-days", Integer.class, 30); }
    public int getJanitorKickRetentionDays() { return getCachedOrLoad("database.janitor.kick-retention-days", Integer.class, 90); }
    public int getJanitorWarningRetentionDays() { return getCachedOrLoad("database.janitor.warning-retention-days", Integer.class, 90); }
    public int getJanitorReportRetentionDays() { return getCachedOrLoad("database.janitor.report-retention-days", Integer.class, 60); }

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