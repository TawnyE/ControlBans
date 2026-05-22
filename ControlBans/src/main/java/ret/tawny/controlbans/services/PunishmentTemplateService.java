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

    public Optional<TemplateResult> resolveTemplate(PunishmentType type, String reason) {
        if (!plugin.getConfig().getBoolean("templates.enabled", false)) {
            return Optional.empty();
        }

        String configPath = resolveTemplatePath(type, reason);
        if (configPath == null) return Optional.empty();

        var levels = plugin.getConfig().getConfigurationSection(configPath);
        if (levels == null) return Optional.empty();

        return Optional.of(new TemplateResult(configPath, levels));
    }

    private String resolveTemplatePath(PunishmentType type, String reason) {
        String normalizedReason = normalizeKey(reason);

        var templatesSection = plugin.getConfig().getConfigurationSection("templates.rules");
        if (templatesSection == null) return null;

        for (String ruleKey : templatesSection.getKeys(false)) {
            var ruleSection = templatesSection.getConfigurationSection(ruleKey);
            if (ruleSection == null) continue;

            String ruleType = ruleSection.getString("type", "");
            List<String> keywords = ruleSection.getStringList("keywords");

            boolean typeMatches = ruleType.isEmpty() || typeMatches(type, ruleType);
            boolean keywordMatches = keywords.isEmpty() || keywords.stream().anyMatch(k ->
                    normalizedReason.contains(normalizeKey(k)));

            if (typeMatches && keywordMatches) {
                return "templates.rules." + ruleKey + ".levels";
            }
        }
        return null;
    }

    private boolean typeMatches(PunishmentType actual, String configured) {
        return configured.equalsIgnoreCase(actual.name()) ||
                configured.equalsIgnoreCase("any");
    }

    private String normalizeKey(String input) {
        if (input == null) return "";
        return input.toLowerCase().replaceAll("[^a-z0-9]", "");
    }

    public record TemplateResolution(PunishmentType type, long durationSeconds) {}

    public CompletableFuture<TemplateResolution> determinePunishmentType(PunishmentType originalType, String reason, UUID targetUuid) {
        var templateOpt = resolveTemplate(originalType, reason);
        if (templateOpt.isEmpty()) {
            return CompletableFuture.completedFuture(new TemplateResolution(originalType, -1));
        }

        TemplateResult template = templateOpt.get();

        return plugin.getStorage().getPunishmentHistory(targetUuid, 100).thenApply(history -> {
            long windowDays = template.levels.getLong("window-days", 30L);
            long windowMs = windowDays * 86_400_000L;
            long cutoff = System.currentTimeMillis() - windowMs;

            String normalizedReason = normalizeKey(reason);
            long offenseCount = history.stream()
                    .filter(p -> p.getCreatedTime() >= cutoff)
                    .filter(p -> {
                        if (!typeMatches(p.getType(), template.levels.getString("type", ""))) return true;
                        String pReason = p.getReason() != null ? normalizeKey(p.getReason()) : "";
                        return pReason.contains(normalizedReason);
                    })
                    .count();

            int level = (int) offenseCount + 1;
            String levelKey = String.valueOf(level);

            if (template.levels.isString(levelKey)) {
                String levelType = template.levels.getString(levelKey);
                try {
                    String[] parts = levelType.split(" ");
                    PunishmentType pt = PunishmentType.valueOf(parts[0].toUpperCase());
                    long duration = -1;
                    if (parts.length > 1) {
                        duration = TimeUtil.parseDuration(parts[1]);
                    }
                    return new TemplateResolution(pt, duration);
                } catch (IllegalArgumentException e) {
                    plugin.getLogger().warning("Invalid template level type: " + levelType);
                }
            }

            return new TemplateResolution(originalType, -1);
        }).exceptionally(e -> {
            plugin.getLogger().log(Level.WARNING, "Failed to resolve punishment template", e);
            return new TemplateResolution(originalType, -1);
        });
    }

    public record TemplateResult(String path, org.bukkit.configuration.ConfigurationSection levels) {}
}
