package ret.tawny.controlbans.services;

import org.bukkit.scheduler.BukkitTask;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.config.ConfigManager.CategoryDefinition;
import ret.tawny.controlbans.config.ConfigManager.EscalationRule;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.PunishmentDao;
import ret.tawny.controlbans.storage.dao.PunishmentMetadataDao;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class EscalationService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final ConfigManager configManager;
    private final SchedulerAdapter scheduler;
    private final ScheduledPunishmentService scheduledPunishmentService;
    private final PunishmentMetadataDao metadataDao = new PunishmentMetadataDao();
    private final PunishmentDao punishmentDao = new PunishmentDao();
    private BukkitTask decayTask;

    public EscalationService(ControlBansPlugin plugin,
                             DatabaseManager databaseManager,
                             ScheduledPunishmentService scheduledPunishmentService) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.configManager = plugin.getConfigManager();
        this.scheduler = plugin.getSchedulerAdapter();
        this.scheduledPunishmentService = scheduledPunishmentService;
    }

    public void start() {
        if (configManager.isWarnDecayEnabled()) {
            long intervalTicks = Duration.ofMinutes(5).toSeconds() * 20L;
            decayTask = scheduler.runTaskTimerAsynchronously(this::expireWarns, intervalTicks, intervalTicks);
        }
    }

    public void stop() {
        if (decayTask != null) {
            decayTask.cancel();
            decayTask = null;
        }
    }

    public CompletableFuture<Void> onPunishmentApplied(Punishment punishment) {
        String category = resolveCategory(punishment.getReason());
        long decayAt = -1;
        if (punishment.getType() == PunishmentType.WARN && configManager.isWarnDecayEnabled()) {
            decayAt = punishment.getCreatedTime() + configManager.getWarnDecayDuration().toMillis();
        }
        return databaseManager.executeAsync(connection ->
                metadataDao.upsert(connection, punishment.getPunishmentId(), category, 0, decayAt))
                .thenCompose(ignored -> {
                    if (punishment.getType() == PunishmentType.WARN && configManager.isEscalationEnabled()) {
                        return evaluateEscalation(punishment, category);
                    }
                    return CompletableFuture.completedFuture(null);
                });
    }

    private CompletableFuture<Void> evaluateEscalation(Punishment punishment, String category) {
        Map<Integer, EscalationRule> rules = configManager.getEscalationRules();
        if (rules.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        return databaseManager.executeQueryAsync(connection -> metadataDao.countActiveWarnings(connection, punishment.getTargetUuid()))
                .thenCompose(count -> {
                    Optional<Map.Entry<Integer, EscalationRule>> maybeRule = rules.entrySet().stream()
                            .sorted(Comparator.comparingInt(Map.Entry::getKey))
                            .filter(entry -> count == entry.getKey())
                            .findFirst();
                    if (maybeRule.isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    EscalationRule rule = maybeRule.get().getValue();
                    return triggerEscalation(punishment, category, rule);
                });
    }

    private CompletableFuture<Void> triggerEscalation(Punishment punishment, String category, EscalationRule rule) {
        PunishmentType type = resolveEscalatedType(rule);
        long durationSeconds = rule.duration().isNegative() ? -1 : rule.duration().toSeconds();
        long executeAt = System.currentTimeMillis() + configManager.getEscalationCooldown().toMillis();
        ScheduledPunishment scheduled = ScheduledPunishment.builder()
                .type(type)
                .targetUuid(punishment.getTargetUuid())
                .targetName(punishment.getTargetName())
                .reason("Automatic escalation: " + punishment.getReason())
                .staffUuid((UUID) null)
                .staffName("ControlBans")
                .executionTime(executeAt)
                .durationSeconds(durationSeconds)
                .silent(true)
                .ipBan(rule.ipBan())
                .category(category)
                .escalationLevel(1)
                .build();
        plugin.getLogger().info("Scheduling automatic escalation for " + punishment.getTargetName() + " in category " + category);
        return scheduledPunishmentService.schedule(scheduled);
    }

    private PunishmentType resolveEscalatedType(EscalationRule rule) {
        String action = rule.action().toLowerCase(Locale.ROOT);
        return switch (action) {
            case "tempban" -> rule.ipBan() ? PunishmentType.IPBAN : PunishmentType.TEMPBAN;
            case "ban", "ipban" -> rule.ipBan() ? PunishmentType.IPBAN : PunishmentType.BAN;
            case "tempmute" -> PunishmentType.TEMPMUTE;
            case "mute" -> PunishmentType.MUTE;
            default -> PunishmentType.TEMPBAN;
        };
    }

    private void expireWarns() {
        long now = System.currentTimeMillis();
        databaseManager.executeQueryAsync(connection -> metadataDao.findDecayedWarningIds(connection, now))
                .thenAccept(ids -> {
                    if (ids.isEmpty()) {
                        return;
                    }
                    databaseManager.executeAsync(connection -> {
                        for (String punishmentId : ids) {
                            punishmentDao.deactivateWarningById(connection, punishmentId);
                            metadataDao.delete(connection, punishmentId);
                        }
                    }).exceptionally(error -> {
                        plugin.getLogger().log(Level.WARNING, "Failed to mark warnings as decayed", error);
                        return null;
                    });
                });
    }

    public String resolveCategory(String reason) {
        if (reason == null || reason.isBlank()) {
            return configManager.getDefaultCategory();
        }
        String lowered = reason.toLowerCase(Locale.ROOT);
        for (CategoryDefinition definition : configManager.getCategoryDefinitions().values()) {
            for (String trigger : definition.triggers()) {
                if (lowered.contains(trigger.toLowerCase(Locale.ROOT))) {
                    return definition.name();
                }
            }
        }
        return configManager.getDefaultCategory();
    }
}
