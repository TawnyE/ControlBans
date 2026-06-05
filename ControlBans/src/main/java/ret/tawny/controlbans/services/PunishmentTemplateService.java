package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class PunishmentTemplateService {

    private final ControlBansPlugin plugin;

    public PunishmentTemplateService(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public static class TemplateRule {
        private final String key;
        private final String displayName;
        private final String description;
        private final String itemMaterial;
        private final String permission;
        private final String type;
        private final List<String> keywords;
        private final int windowDays;
        private final String durationLore;
        private final String reason;
        private final org.bukkit.configuration.ConfigurationSection levels;

        public TemplateRule(String key, String displayName, String description, String itemMaterial, String permission, String type, List<String> keywords, int windowDays, String durationLore, String reason, org.bukkit.configuration.ConfigurationSection levels) {
            this.key = key;
            this.displayName = displayName;
            this.description = description;
            this.itemMaterial = itemMaterial;
            this.permission = permission;
            this.type = type;
            this.keywords = keywords;
            this.windowDays = windowDays;
            this.durationLore = durationLore;
            this.reason = reason;
            this.levels = levels;
        }

        public String getKey() { return key; }
        public String getDisplayName() { return displayName; }
        public String getDescription() { return description; }
        public String getItemMaterial() { return itemMaterial; }
        public String getPermission() { return permission; }
        public String getType() { return type; }
        public List<String> getKeywords() { return keywords; }
        public int getWindowDays() { return windowDays; }
        public String getDurationLore() { return durationLore; }
        public String getReason() { return reason; }
        public org.bukkit.configuration.ConfigurationSection getLevels() { return levels; }
    }

    public static class TemplateResolution {
        private final PunishmentType type;
        private final long durationSeconds;
        private final String templateKey;

        public TemplateResolution(PunishmentType type, long durationSeconds) {
            this.type = type;
            this.durationSeconds = durationSeconds;
            this.templateKey = null;
        }

        public TemplateResolution(PunishmentType type, long durationSeconds, String templateKey) {
            this.type = type;
            this.durationSeconds = durationSeconds;
            this.templateKey = templateKey;
        }

        public PunishmentType type() { return type; }
        public long durationSeconds() { return durationSeconds; }
        public String templateKey() { return templateKey; }
    }

    public List<TemplateRule> getTemplates() {
        List<TemplateRule> list = new ArrayList<>();
        if (!plugin.getConfig().getBoolean("templates.enabled", false)) {
            plugin.getLogger().warning("Templates disabled via config (templates.enabled = false)");
            return list;
        }
        var rulesSection = plugin.getConfig().getConfigurationSection("templates.rules");
        if (rulesSection == null) {
            plugin.getLogger().warning("templates.rules section is null in config — check config.yml structure. Keys under templates: " + plugin.getConfig().getConfigurationSection("templates").getKeys(false));
            return list;
        }

        for (String key : rulesSection.getKeys(false)) {
            var section = rulesSection.getConfigurationSection(key);
            if (section == null) continue;

            String displayName = section.getString("display-name", "#" + key.substring(0, 1).toUpperCase() + key.substring(1));
            String description = section.getString("description", "");
            String type = section.getString("type", "any");

            String defaultItem = type.equalsIgnoreCase("mute") || type.equalsIgnoreCase("tempmute") || type.equalsIgnoreCase("voicemute") ? "ORANGE_CANDLE" : "RED_CANDLE";
            String itemMaterial = section.getString("item", defaultItem);

            String permission = section.getString("permission", null);
            List<String> keywords = section.getStringList("keywords");

            var levelsSection = section.getConfigurationSection("levels");
            int windowDays = 30;
            String computedDurationRange = "PERMANENT";
            if (levelsSection != null) {
                windowDays = levelsSection.getInt("window-days", 30);
                computedDurationRange = calculateDurationRange(levelsSection);
            }

            String durationLore = section.getString("duration-lore", computedDurationRange);

            String reason = section.getString("reason", null);

            list.add(new TemplateRule(key, displayName, description, itemMaterial, permission, type, keywords, windowDays, durationLore, reason, levelsSection));
        }
        return list;
    }

    public String extractHashtagKey(String reason) {
        if (reason == null || reason.isEmpty()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("#([a-zA-Z0-9_-]+)").matcher(reason);
        if (matcher.find()) {
            return matcher.group(1).toLowerCase().replaceAll("[^a-z0-9_-]", "");
        }
        return null;
    }

    public Optional<TemplateRule> findTemplate(String reason) {
        if (reason == null || reason.isEmpty()) return Optional.empty();
        String key = extractHashtagKey(reason);
        if (key == null) {
            String cleanReason = reason.trim().toLowerCase();
            for (TemplateRule rule : getTemplates()) {
                if (rule.getKey().equalsIgnoreCase(cleanReason) || normalizeKey(rule.getDisplayName()).equalsIgnoreCase(normalizeKey(cleanReason))) {
                    return Optional.of(rule);
                }
            }
            return Optional.empty();
        }

        for (TemplateRule rule : getTemplates()) {
            if (rule.getKey().equalsIgnoreCase(key) || normalizeKey(rule.getDisplayName()).equalsIgnoreCase(normalizeKey(key))) {
                return Optional.of(rule);
            }
        }
        return Optional.empty();
    }

    public String calculateDurationRange(org.bukkit.configuration.ConfigurationSection levelsSection) {
        if (levelsSection == null) return "PERMANENT";

        long minSecs = Long.MAX_VALUE;
        long maxSecs = -1;
        boolean hasPerm = false;
        boolean hasTemp = false;

        for (String key : levelsSection.getKeys(false)) {
            if (!key.matches("\\d+")) continue;
            String val = levelsSection.getString(key);
            if (val == null) continue;

            String[] parts = val.split(" ");
            String typeStr = parts[0].toUpperCase();

            if (typeStr.equals("BAN") || typeStr.equals("MUTE") || typeStr.equals("IPBAN") || typeStr.equals("IPMUTE") || typeStr.equals("VOICEMUTE") || typeStr.equals("permanent")) {
                hasPerm = true;
            } else if (parts.length > 1) {
                try {
                    long duration = TimeUtil.parseDuration(parts[1]);
                    minSecs = Math.min(minSecs, duration);
                    maxSecs = Math.max(maxSecs, duration);
                    hasTemp = true;
                } catch (Exception ignored) {}
            } else {
                hasPerm = true;
            }
        }

        if (hasTemp && hasPerm) {
            return TimeUtil.formatDuration(minSecs) + " - PERMANENT";
        } else if (hasTemp) {
            if (minSecs == maxSecs) {
                return TimeUtil.formatDuration(minSecs);
            } else {
                return TimeUtil.formatDuration(minSecs) + " - " + TimeUtil.formatDuration(maxSecs);
            }
        } else if (hasPerm) {
            return "PERMANENT";
        }
        return "PERMANENT";
    }

    private String normalizeKey(String input) {
        if (input == null) return "";
        return input.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public CompletableFuture<TemplateResolution> determinePunishmentType(PunishmentType originalType, String reason, UUID targetUuid) {
        Optional<TemplateRule> templateOpt = findTemplate(reason);
        if (templateOpt.isEmpty()) {
            return CompletableFuture.completedFuture(new TemplateResolution(originalType, -1));
        }

        TemplateRule template = templateOpt.get();
        if (template.getLevels() == null) {
            return CompletableFuture.completedFuture(new TemplateResolution(originalType, -1, template.getKey()));
        }

        return plugin.getStorage().getPunishmentHistory(targetUuid, 100).thenApply(history -> {
            long windowMs = template.getWindowDays() * 86_400_000L;
            long cutoff = System.currentTimeMillis() - windowMs;

            String templateKey = template.getKey();
            String cleanDisplayName = template.getDisplayName().replaceAll("<[^>]+>", "").toLowerCase();

            boolean useKeywords = plugin.getConfigManager().isTemplateKeywordMatchingEnabled();

            long offenseCount = history.stream()
                    .filter(p -> p.getCreatedTime() >= cutoff)
                    .filter(p -> {
                        String pReason = p.getReason() != null ? p.getReason().toLowerCase() : "";

                        if (pReason.contains("#" + templateKey)) return true;
                        if (pReason.contains(cleanDisplayName)) return true;

                        if (useKeywords && !pReason.contains("#")) {
                            for (String keyword : template.getKeywords()) {
                                if (pReason.contains(keyword.toLowerCase())) {
                                    return true;
                                }
                            }
                        }
                        return false;
                    })
                    .count();

            int level = (int) offenseCount + 1;
            String levelKey = String.valueOf(level);

            if (template.getLevels().isString(levelKey)) {
                String levelType = template.getLevels().getString(levelKey);
                try {
                    String[] parts = levelType.split(" ");
                    PunishmentType pt = PunishmentType.valueOf(parts[0].toUpperCase());
                    long duration = -1;
                    if (parts.length > 1) {
                        duration = TimeUtil.parseDuration(parts[1]);
                    }
                    return new TemplateResolution(pt, duration, template.getKey());
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid template level type: " + levelType);
                }
            }

            int maxConfiguredLevel = -1;
            for (String key : template.getLevels().getKeys(false)) {
                try {
                    int k = Integer.parseInt(key);
                    if (k > maxConfiguredLevel) maxConfiguredLevel = k;
                } catch (NumberFormatException ignored) {}
            }

            if (maxConfiguredLevel > 0 && template.getLevels().isString(String.valueOf(maxConfiguredLevel))) {
                String levelType = template.getLevels().getString(String.valueOf(maxConfiguredLevel));
                try {
                    String[] parts = levelType.split(" ");
                    PunishmentType pt = PunishmentType.valueOf(parts[0].toUpperCase());
                    long duration = -1;
                    if (parts.length > 1) {
                        duration = TimeUtil.parseDuration(parts[1]);
                    }
                    return new TemplateResolution(pt, duration, template.getKey());
                } catch (IllegalArgumentException ignored) {}
            }

            return new TemplateResolution(originalType, -1, template.getKey());
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve punishment template", e);
            return new TemplateResolution(originalType, -1);
        });
    }
}

