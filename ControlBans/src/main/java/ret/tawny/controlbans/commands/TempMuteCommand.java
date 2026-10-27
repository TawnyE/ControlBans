package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public class TempMuteCommand extends CommandBase {

    public TempMuteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("tempmute");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.tempmute")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        boolean silent = detectSilentFlag(args);
        String[] cleanArgs = stripSilentFlag(args);
        int targetIndex = 0;

        if (cleanArgs.length <= targetIndex + 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        MultiTargetResult parsed = parseMultiTargets(cleanArgs, targetIndex, true);
        List<String> targets = parsed.targets();
        int reasonStart = parsed.reasonStart();

        if (targets.isEmpty()) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        if (reasonStart < 1 || reasonStart > cleanArgs.length) {
            sender.sendMessage(locale.getMessage("errors.invalid-duration"));
            return true;
        }
        String durationStr = cleanArgs[reasonStart - 1];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = reasonStart; i < cleanArgs.length; i++) {
            reasonJoiner.add(cleanArgs[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        if (targets.size() == 1) {
            String targetName = targets.get(0);

            long duration;
            try {
                duration = TimeUtil.parseDuration(durationStr);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(locale.getMessage("errors.invalid-duration"));
                return true;
            }

            punishmentService.tempMutePlayer(targetName, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                    .whenComplete((appliedDuration, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, targetName);
                        } else {
                            long shown = appliedDuration != null ? appliedDuration : duration;
                            String shownStr = shown == -1 ? "permanent" : TimeUtil.formatDuration(shown);
                            sender.sendMessage(locale.getMessage("success.tempmute",
                                    playerPlaceholder(targetName),
                                    durationPlaceholder(shownStr)
                            ));
                        }
                    });
            return true;
        }

        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(locale.getMessage("errors.invalid-duration"));
            return true;
        }

        UUID staffUuid = getSenderUuid(sender);
        String staffName = sender.getName();

        for (String target : targets) {
            final long requestedDuration = duration;
            punishmentService.tempMutePlayer(target, requestedDuration, reason, staffUuid, staffName, silent)
                    .whenComplete((appliedDuration, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, target);
                        } else {
                            long shown = appliedDuration != null ? appliedDuration : requestedDuration;
                            String shownStr = shown == -1 ? "permanent" : TimeUtil.formatDuration(shown);
                            sender.sendMessage(locale.getMessage("success.tempmute",
                                    playerPlaceholder(target),
                                    durationPlaceholder(shownStr)
                            ));
                        }
                    });
        }
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        boolean isSilent = args.length > 0 && args[0].equalsIgnoreCase("-s");
        int argIndex = args.length - 1;
        String currentArg = args[argIndex];

        int targetIndex = isSilent ? 1 : 0;
        if (argIndex == targetIndex) {
            return getPlayerSuggestions(currentArg);
        }

        int timeIndex = isSilent ? 2 : 1;
        if (argIndex == timeIndex) {
            return getTimeSuggestions(currentArg);
        }
        return List.of();
    }
}