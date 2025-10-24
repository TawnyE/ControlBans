package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.services.BenchmarkService;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ControlBansCommand extends CommandBase {

    public ControlBansCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("controlbans");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <reload|import|benchmark|analytics|health|roadmap|appeals|schedule|audit>")));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "import" -> handleImport(sender, args);
            case "benchmark" -> handleBenchmark(sender);
            case "analytics" -> handleAnalytics(sender);
            case "health" -> handleHealth(sender);
            case "roadmap" -> handleRoadmap(sender);
            case "appeals" -> handleAppeals(sender);
            case "schedule" -> handleSchedule(sender, args);
            case "audit" -> handleAudit(sender);
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <reload|import|benchmark|analytics|health|roadmap|appeals|schedule|audit>")));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.reload();
        sender.sendMessage(locale.getMessage("success.reload"));
    }

    private void handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.import")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " import <essentials|litebans>")));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "essentials" -> plugin.getImportService().importFromEssentials(sender);
            case "litebans" -> plugin.getImportService().importFromLiteBans(sender);
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " import<essentials|litebans>")));
        }
    }

    private void handleBenchmark(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        BenchmarkService.BenchmarkSnapshot snapshot = plugin.getBenchmarkService().snapshot();
        for (String line : plugin.getBenchmarkService().formatSnapshotLines(snapshot)) {
            sender.sendMessage(line);
        }
    }

    private void handleAnalytics(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.getAnalyticsService().getDashboardSnapshot().whenComplete((snapshot, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to compute analytics snapshot", throwable);
                sender.sendMessage(locale.getMessage("errors.operation-failed"));
                return;
            }
            plugin.getSchedulerAdapter().runTask(() -> {
                sender.sendMessage("§6Active bans: §f" + snapshot.activeBans());
                sender.sendMessage("§6Active mutes: §f" + snapshot.activeMutes());
                sender.sendMessage("§6Warnings (recent): §f" + snapshot.warningsRecent());
                sender.sendMessage("§6Open appeals: §f" + snapshot.appealsOpen());
                sender.sendMessage("§6Top categories:");
                snapshot.categoryBreakdown().forEach((category, count) -> sender.sendMessage(" §7- §e" + category + ": §f" + count));
                sender.sendMessage("§6Top reasons:");
                snapshot.topReasons().forEach((reason, count) -> sender.sendMessage(" §7- §e" + reason + ": §f" + count));
            });
        });
    }

    private void handleHealth(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.getHealthService().refresh().whenComplete((report, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("errors.operation-failed"));
                return;
            }
            plugin.getSchedulerAdapter().runTask(() -> {
                sender.sendMessage("§6Database: " + formatHealth(report.database()));
                sender.sendMessage("§6DiscordSRV: " + formatHealth(report.discord()));
                sender.sendMessage("§6MCBlacklist: " + formatHealth(report.mcblacklist()));
                sender.sendMessage("§6Proxy queue: §f" + report.proxyQueue());
                sender.sendMessage("§6Pending schedules: §f" + report.pendingSchedules());
            });
        });
    }

    private String formatHealth(boolean value) {
        return value ? "§aOK" : "§cDOWN";
    }

    private void handleRoadmap(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        sender.sendMessage("§6Roadmap markers:");
        plugin.getConfigManager().getRoadmapMarkers().forEach(entry -> sender.sendMessage(" §7- §f" + entry));
    }

    private void handleAppeals(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.getAppealService().listOpenAppeals().whenComplete((appeals, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("errors.operation-failed"));
                return;
            }
            plugin.getSchedulerAdapter().runTask(() -> {
                if (appeals.isEmpty()) {
                    sender.sendMessage("§7No open appeals.");
                    return;
                }
                sender.sendMessage("§6Open appeals:");
                appeals.stream().limit(10).forEach(appeal -> sender.sendMessage(" §7- §e" + appeal.punishmentId() + " §7status §f" + appeal.status()));
            });
        });
    }

    private void handleAudit(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.getAuditService().fetchRecent(10).whenComplete((entries, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("errors.operation-failed"));
                return;
            }
            plugin.getSchedulerAdapter().runTask(() -> {
                sender.sendMessage("§6Recent moderator actions:");
                entries.forEach(entry -> sender.sendMessage(String.format(" §7[%s] %s §7→ §f%s", entry.action(), entry.actorName(), entry.targetName())));
            });
        });
    }

    private void handleSchedule(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 4) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " schedule <player> <type> <delay> [duration] [reason]")));
            return;
        }

        String targetName = args[1];
        String typeArg = args[2].toLowerCase();
        long delaySeconds = TimeUtil.parseDuration(args[3]);
        if (delaySeconds <= 0) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("Delay must be greater than zero")));
            return;
        }

        PunishmentType type;
        boolean temp = false;
        switch (typeArg) {
            case "ban" -> type = PunishmentType.BAN;
            case "tempban" -> { type = PunishmentType.TEMPBAN; temp = true; }
            case "mute" -> type = PunishmentType.MUTE;
            case "tempmute" -> { type = PunishmentType.TEMPMUTE; temp = true; }
            case "warn" -> type = PunishmentType.WARN;
            case "kick" -> type = PunishmentType.KICK;
            default -> {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("Unsupported type: " + typeArg)));
                return;
            }
        }

        int reasonIndex = temp ? 5 : 4;
        long durationSeconds = -1;
        if (temp) {
            if (args.length < 5) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " schedule <player> " + typeArg + " <delay> <duration> [reason]")));
                return;
            }
            durationSeconds = TimeUtil.parseDuration(args[4]);
            if (durationSeconds <= 0) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("Duration must be greater than zero")));
                return;
            }
        }

        String reason = (args.length > reasonIndex)
                ? String.join(" ", Arrays.copyOfRange(args, reasonIndex, args.length))
                : switch (type) {
                    case MUTE, TEMPMUTE -> plugin.getConfigManager().getDefaultMuteReason();
                    case WARN -> plugin.getConfigManager().getDefaultWarnReason();
                    default -> plugin.getConfigManager().getDefaultBanReason();
                };

        UuidUtil.getUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                sender.sendMessage(locale.getMessage("errors.player-not-found", usagePlaceholder(targetName)));
                return CompletableFuture.completedFuture(null);
            }

            ScheduledPunishment schedule = ScheduledPunishment.builder()
                    .type(type)
                    .targetUuid(uuid)
                    .targetName(targetName)
                    .reason(reason)
                    .staffUuid(getSenderUuid(sender))
                    .staffName(sender.getName())
                    .executionTime(System.currentTimeMillis() + delaySeconds * 1000L)
                    .durationSeconds(durationSeconds)
                    .silent(false)
                    .ipBan(type == PunishmentType.IPBAN)
                    .category(plugin.getEscalationService().resolveCategory(reason))
                    .build();

            return plugin.getScheduledPunishmentService().schedule(schedule).thenApply(unused -> schedule);
        }).whenComplete((schedule, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().log(Level.WARNING, "Failed to schedule punishment", throwable);
                sender.sendMessage(locale.getMessage("errors.operation-failed"));
                return;
            }
            if (schedule != null) {
                plugin.getSchedulerAdapter().runTask(() -> sender.sendMessage(locale.getMessage("success.scheduled",
                        playerPlaceholder(schedule.getTargetName()),
                        typePlaceholder(schedule.getType().name().toLowerCase()),
                        durationPlaceholder(TimeUtil.formatDuration(delaySeconds)),
                        reasonPlaceholder(reason))));
            }
        });
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Stream.of("reload", "import", "benchmark", "analytics", "health", "roadmap", "appeals", "schedule", "audit")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return Stream.of("essentials", "litebans")
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args[0].equalsIgnoreCase("schedule")) {
            if (args.length == 2) {
                return getPlayerSuggestions(args[1]);
            }
            if (args.length == 3) {
                return Stream.of("ban", "tempban", "mute", "tempmute", "warn", "kick")
                        .filter(s -> s.startsWith(args[2].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 4) {
                return getTimeSuggestions(args[3]);
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("tempban")) {
                return getTimeSuggestions(args[4]);
            }
            if (args.length == 5 && args[2].equalsIgnoreCase("tempmute")) {
                return getTimeSuggestions(args[4]);
            }
            if (args.length == 5 && !args[2].toLowerCase().startsWith("temp")) {
                return plugin.getConfigManager().getSuggestedReasons().stream()
                        .filter(reason -> reason.toLowerCase().startsWith(args[4].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args.length == 6 && args[2].toLowerCase().startsWith("temp")) {
                return plugin.getConfigManager().getSuggestedReasons().stream()
                        .filter(reason -> reason.toLowerCase().startsWith(args[5].toLowerCase()))
                        .collect(Collectors.toList());
            }
        }
        return List.of();
    }
}
