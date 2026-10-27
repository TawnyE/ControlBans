package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.listeners.ChatInputListener;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BulkPunishmentCommand extends CommandBase {

    private final ChatInputListener chatInput;
    private final Map<UUID, BulkSession> sessions = new ConcurrentHashMap<>();

    private static final Set<String> VALID_ACTIONS = Set.of(
            "ban", "tempban", "mute", "tempmute", "kick", "warn",
            "voicemute", "tempvoicemute", "ipban", "tempipban", "ipmute", "tempipmute"
    );

    public BulkPunishmentCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("bulkpunishment");
        this.chatInput = plugin.getChatInputListener();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
            return true;
        }
        if (!sender.hasPermission("controlbans.bulkpunish")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        // Cancelling active session
        if (args.length == 1 && args[0].equalsIgnoreCase("cancel")) {
            BulkSession removed = sessions.remove(player.getUniqueId());
            if (removed != null) {
                player.sendMessage(locale.getMessage("bulkpunish.cancelled"));
            } else {
                player.sendMessage(locale.getMessage("bulkpunish.no-session"));
            }
            return true;
        }

        // Quick mode: /bulkpunish <action> [--schedule <time>] [--silent] <player1> <player2> ... / <reason>
        if (args.length >= 2 && VALID_ACTIONS.contains(args[0].toLowerCase())) {
            handleQuick(player, args);
            return true;
        }

        // Start guided wizard
        BulkSession session = new BulkSession();
        sessions.put(player.getUniqueId(), session);
        promptAction(player);
        return true;
    }

    private void handleQuick(Player player, String[] args) {
        String actionName = args[0].toLowerCase();
        ScheduledPunishment.ScheduledAction action;
        PunishmentType type;
        try {
            action = mapAction(actionName);
            type = mapActionToType(actionName);
        } catch (IllegalArgumentException e) {
            player.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " <action> [--schedule <time>] [--silent] <players...> / <reason>")));
            return;
        }

        // Check permission for this action — map to the real node, not a string strip.
        if (!player.hasPermission(mapActionToPermission(actionName))) {
            player.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }

        List<String> targetNames = new ArrayList<>();
        boolean silent = false;
        long scheduleAt = -1;
        long explicitDuration = -1;
        StringJoiner reasonBuilder = new StringJoiner(" ");
        boolean foundSlash = false;
        int reasonStart = 0;

        for (int i = 1; i < args.length; i++) {
            if (args[i].equals("/")) {
                foundSlash = true;
                reasonStart = i + 1;
                break;
            }
            if (args[i].equalsIgnoreCase("-s") || args[i].equalsIgnoreCase("--silent")) {
                silent = true;
                continue;
            }
            if (args[i].equalsIgnoreCase("--schedule") && i + 1 < args.length) {
                try {
                    scheduleAt = System.currentTimeMillis() + TimeUtil.parseDuration(args[i + 1]) * 1000;
                    i++;
                } catch (IllegalArgumentException e) {
                    player.sendMessage(locale.getMessage("errors.invalid-duration"));
                    return;
                }
                continue;
            }
            if (args[i].equalsIgnoreCase("--duration") && i + 1 < args.length) {
                try {
                    explicitDuration = TimeUtil.parseDuration(args[i + 1]);
                    i++;
                } catch (IllegalArgumentException e) {
                    player.sendMessage(locale.getMessage("errors.invalid-duration"));
                    return;
                }
                continue;
            }
            targetNames.add(args[i]);
        }

        if (foundSlash) {
            for (int i = reasonStart; i < args.length; i++) {
                reasonBuilder.add(args[i]);
            }
        }

        String reason = reasonBuilder.length() > 0 ? reasonBuilder.toString() : null;

        if (targetNames.isEmpty()) {
            player.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " " + actionName + " <players...> / [reason]")));
            return;
        }

        boolean hasDuration = actionName.contains("temp") || actionName.equals("tempban") || actionName.equals("tempmute") || actionName.equals("tempvoicemute") || actionName.equals("tempipban") || actionName.equals("tempipmute");

        // Punishment duration and schedule delay are separate concepts.
        // --duration sets how long a temp punishment lasts; --schedule sets when it fires.
        // Legacy quick mode without --duration keeps 1d for backward compat, but staff
        // should prefer explicit --duration.
        long effectiveDuration = 0;
        if (hasDuration) {
            effectiveDuration = explicitDuration > 0 ? explicitDuration : 86400;
            if (explicitDuration <= 0 && scheduleAt <= 0) {
                player.sendMessage(locale.getMessage("bulkpunish.confirm-duration",
                        Placeholder.unparsed("duration", TimeUtil.formatDuration(effectiveDuration))));
            }
        }

        player.sendMessage(locale.getMessage("bulkpunish.starting",
                Placeholder.unparsed("count", String.valueOf(targetNames.size()))));

        UUID staffUuid = player.getUniqueId();
        String staffName = player.getName();

        if (scheduleAt > 0) {
            scheduleBulk(player, action, type, targetNames, effectiveDuration, reason, staffUuid, staffName, silent, scheduleAt);
        } else {
            executeBulk(player, action, type, targetNames, effectiveDuration, reason, staffUuid, staffName, silent);
        }
    }

    private void executeBulk(Player player, ScheduledPunishment.ScheduledAction action, PunishmentType type,
                             List<String> targets, long durationSeconds, String reason,
                             UUID staffUuid, String staffName, boolean silent) {
        List<CompletableFuture<?>> futures = new ArrayList<>();
        for (String target : targets) {
            CompletableFuture<?> f = switch (action) {
                case BAN -> punishmentService.banPlayer(target, reason, staffUuid, staffName, silent, false);
                case TEMPBAN -> punishmentService.tempBanPlayer(target, durationSeconds, reason, staffUuid, staffName, silent, false);
                case MUTE -> punishmentService.mutePlayer(target, reason, staffUuid, staffName, silent);
                case TEMPMUTE -> punishmentService.tempMutePlayer(target, durationSeconds, reason, staffUuid, staffName, silent);
                case KICK -> punishmentService.kickPlayer(target, reason, staffUuid, staffName, silent);
                case WARN -> punishmentService.warnPlayer(target, reason, staffUuid, staffName, silent);
                case VOICEMUTE -> punishmentService.voiceMutePlayer(target, reason, staffUuid, staffName, silent);
                case TEMPVOICEMUTE -> punishmentService.tempVoiceMutePlayer(target, durationSeconds, reason, staffUuid, staffName, silent);
                case IPBAN -> punishmentService.ipBanPlayer(target, -1, reason, staffUuid, staffName, silent).thenApply(v -> null);
                case TEMPIPBAN -> punishmentService.ipBanPlayer(target, durationSeconds, reason, staffUuid, staffName, silent).thenApply(v -> null);
                case IPMUTE -> punishmentService.ipMutePlayer(target, -1, reason, staffUuid, staffName, silent).thenApply(v -> null);
                case TEMPIPMUTE -> punishmentService.ipMutePlayer(target, durationSeconds, reason, staffUuid, staffName, silent).thenApply(v -> null);
            };
            if (f != null) futures.add(f);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> {
                    int success = (int) futures.stream().filter(f -> {
                        try { f.get(); return true; } catch (Exception e) { return false; }
                    }).count();
                    int failed = targets.size() - success;
                    player.sendMessage(locale.getMessage("bulkpunish.complete",
                            Placeholder.unparsed("success", String.valueOf(success)),
                            Placeholder.unparsed("failed", String.valueOf(failed))));
                });
    }

    private void scheduleBulk(Player player, ScheduledPunishment.ScheduledAction action, PunishmentType type,
                              List<String> targets, long durationSeconds, String reason,
                              UUID staffUuid, String staffName, boolean silent, long executeAt) {
        player.sendMessage(locale.getMessage("bulkpunish.scheduling",
                Placeholder.unparsed("count", String.valueOf(targets.size()))));
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (String target : targets) {
            futures.add(plugin.getScheduledService().scheduleForAction(
                    action, type, target, durationSeconds, reason, staffUuid, staffName, silent, executeAt));
        }
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .whenComplete((v, ex) -> player.sendMessage(locale.getMessage("bulkpunish.scheduled",
                        Placeholder.unparsed("count", String.valueOf(targets.size())))));
    }

    private void promptAction(Player player) {
        player.sendMessage(locale.getMessage("bulkpunish.prompt-action"));
        chatInput.awaitInput(player, input -> {
            if (!VALID_ACTIONS.contains(input.toLowerCase())) {
                player.sendMessage(locale.getMessage("bulkpunish.invalid-action"));
                promptAction(player);
                return;
            }
            BulkSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            session.actionName = input.toLowerCase();
            session.action = mapAction(session.actionName);
            session.type = mapActionToType(session.actionName);
            promptTargets(player);
        });
    }

    private void promptTargets(Player player) {
        player.sendMessage(locale.getMessage("bulkpunish.prompt-targets"));
        chatInput.awaitInput(player, input -> {
            BulkSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            String[] names = input.split("\\s+");
            session.targetNames.addAll(Arrays.stream(names)
                    .filter(s -> !s.isEmpty())
                    .collect(Collectors.toList()));
            if (session.targetNames.isEmpty()) {
                player.sendMessage(locale.getMessage("bulkpunish.invalid-targets"));
                promptTargets(player);
                return;
            }
            boolean needsDuration = session.actionName.contains("temp");
            if (needsDuration) {
                promptDuration(player);
            } else {
                promptReason(player);
            }
        });
    }

    private void promptDuration(Player player) {
        player.sendMessage(locale.getMessage("bulkpunish.prompt-duration"));
        chatInput.awaitInput(player, input -> {
            BulkSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            try {
                session.durationSeconds = TimeUtil.parseDuration(input);
            } catch (IllegalArgumentException e) {
                player.sendMessage(locale.getMessage("errors.invalid-duration"));
                promptDuration(player);
                return;
            }
            promptReason(player);
        });
    }

    private void promptReason(Player player) {
        player.sendMessage(locale.getMessage("bulkpunish.prompt-reason"));
        chatInput.awaitInput(player, input -> {
            BulkSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            session.reason = input.equalsIgnoreCase("none") ? null : input;
            promptSchedule(player);
        });
    }

    private void promptSchedule(Player player) {
        player.sendMessage(locale.getMessage("bulkpunish.prompt-schedule"));
        chatInput.awaitInput(player, input -> {
            BulkSession session = sessions.get(player.getUniqueId());
            if (session == null) return;
            if (input.equalsIgnoreCase("now") || input.equalsIgnoreCase("skip")) {
                session.executeAt = -1;
            } else {
                try {
                    long durationSecs = TimeUtil.parseDuration(input);
                    session.executeAt = System.currentTimeMillis() + durationSecs * 1000;
                } catch (IllegalArgumentException e) {
                    player.sendMessage(locale.getMessage("errors.invalid-duration"));
                    promptSchedule(player);
                    return;
                }
            }
            promptConfirm(player);
        });
    }

    private void promptConfirm(Player player) {
        BulkSession session = sessions.get(player.getUniqueId());
        if (session == null) return;

        player.sendMessage(locale.getMessage("bulkpunish.confirm-header"));
        player.sendMessage(locale.getMessage("bulkpunish.confirm-action",
                Placeholder.unparsed("action", session.actionName)));
        player.sendMessage(locale.getMessage("bulkpunish.confirm-targets",
                Placeholder.unparsed("players", String.join(", ", session.targetNames))));
        if (session.durationSeconds > 0) {
            player.sendMessage(locale.getMessage("bulkpunish.confirm-duration",
                    Placeholder.unparsed("duration", TimeUtil.formatDuration(session.durationSeconds))));
        }
        if (session.reason != null) {
            player.sendMessage(locale.getMessage("bulkpunish.confirm-reason",
                    Placeholder.unparsed("reason", session.reason)));
        }
        if (session.executeAt > 0) {
            player.sendMessage(locale.getMessage("bulkpunish.confirm-schedule",
                    Placeholder.unparsed("time", TimeUtil.formatDate(session.executeAt))));
        } else {
            player.sendMessage(locale.getMessage("bulkpunish.confirm-immediate"));
        }

        player.sendMessage(locale.getMessage("bulkpunish.confirm-prompt"));
        chatInput.awaitInput(player, input -> {
            if (input.equalsIgnoreCase("yes") || input.equalsIgnoreCase("y") || input.equalsIgnoreCase("confirm")) {
                UUID staffUuid = player.getUniqueId();
                String staffName = player.getName();
                if (session.executeAt > 0) {
                    scheduleBulk(player, session.action, session.type, session.targetNames,
                            session.durationSeconds, session.reason, staffUuid, staffName, false, session.executeAt);
                } else {
                    executeBulk(player, session.action, session.type, session.targetNames,
                            session.durationSeconds, session.reason, staffUuid, staffName, false);
                }
                sessions.remove(player.getUniqueId());
            } else {
                player.sendMessage(locale.getMessage("bulkpunish.cancelled"));
                sessions.remove(player.getUniqueId());
            }
        });
    }

    private String mapActionToPermission(String name) {
        return switch (name) {
            case "ban" -> "controlbans.ban";
            case "tempban" -> "controlbans.tempban";
            case "mute" -> "controlbans.mute";
            case "tempmute" -> "controlbans.tempmute";
            case "kick" -> "controlbans.kick";
            case "warn" -> "controlbans.warn";
            case "voicemute" -> "controlbans.voicemute";
            case "tempvoicemute" -> "controlbans.tempvoicemute";
            case "ipban", "tempipban" -> "controlbans.ban.ip";
            case "ipmute", "tempipmute" -> "controlbans.mute.ip";
            default -> "controlbans.bulkpunish";
        };
    }

    private ScheduledPunishment.ScheduledAction mapAction(String name) {        return switch (name) {
            case "ban" -> ScheduledPunishment.ScheduledAction.BAN;
            case "tempban" -> ScheduledPunishment.ScheduledAction.TEMPBAN;
            case "mute" -> ScheduledPunishment.ScheduledAction.MUTE;
            case "tempmute" -> ScheduledPunishment.ScheduledAction.TEMPMUTE;
            case "kick" -> ScheduledPunishment.ScheduledAction.KICK;
            case "warn" -> ScheduledPunishment.ScheduledAction.WARN;
            case "voicemute" -> ScheduledPunishment.ScheduledAction.VOICEMUTE;
            case "tempvoicemute" -> ScheduledPunishment.ScheduledAction.TEMPVOICEMUTE;
            case "ipban" -> ScheduledPunishment.ScheduledAction.IPBAN;
            case "tempipban" -> ScheduledPunishment.ScheduledAction.TEMPIPBAN;
            case "ipmute" -> ScheduledPunishment.ScheduledAction.IPMUTE;
            case "tempipmute" -> ScheduledPunishment.ScheduledAction.TEMPIPMUTE;
            default -> throw new IllegalArgumentException("Unknown action: " + name);
        };
    }

    private PunishmentType mapActionToType(String name) {
        return switch (name) {
            case "ban" -> PunishmentType.BAN;
            case "tempban" -> PunishmentType.TEMPBAN;
            case "mute" -> PunishmentType.MUTE;
            case "tempmute" -> PunishmentType.TEMPMUTE;
            case "kick" -> PunishmentType.KICK;
            case "warn" -> PunishmentType.WARN;
            case "voicemute" -> PunishmentType.VOICEMUTE;
            case "tempvoicemute" -> PunishmentType.TEMPVOICEMUTE;
            case "ipban" -> PunishmentType.IPBAN;
            case "tempipban" -> PunishmentType.TEMPIPBAN;
            case "ipmute" -> PunishmentType.IPMUTE;
            case "tempipmute" -> PunishmentType.TEMPIPMUTE;
            default -> throw new IllegalArgumentException("Unknown action: " + name);
        };
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.concat(
                    VALID_ACTIONS.stream(),
                    Stream.of("cancel", "start")
            ).filter(s -> s.startsWith(prefix)).collect(Collectors.toList());
        }
        return List.of();
    }

    private static class BulkSession {
        String actionName;
        ScheduledPunishment.ScheduledAction action;
        PunishmentType type;
        final List<String> targetNames = new ArrayList<>();
        long durationSeconds;
        String reason;
        long executeAt = -1;
    }
}
