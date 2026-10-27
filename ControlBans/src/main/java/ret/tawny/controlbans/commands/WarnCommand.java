package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

public class WarnCommand extends CommandBase {

    public WarnCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("warn");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.warn")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        boolean silent = detectSilentFlag(args);
        String[] cleanArgs = stripSilentFlag(args);
        int targetIndex = 0;

        if (cleanArgs.length <= targetIndex) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        MultiTargetResult parsed = parseMultiTargets(cleanArgs, targetIndex, false);
        List<String> targets = parsed.targets();
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = parsed.reasonStart(); i < cleanArgs.length; i++) {
            reasonJoiner.add(cleanArgs[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        if (targets.isEmpty()) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        UUID staffUuid = getSenderUuid(sender);
        String staffName = sender.getName();

        if (targets.size() == 1) {
            String targetName = targets.get(0);

            punishmentService.warnPlayer(targetName, reason, staffUuid, staffName, silent)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, targetName);
                        } else {
                            sender.sendMessage(locale.getMessage("success.warn", playerPlaceholder(targetName)));
                        }
                    });
            return true;
        }

        for (String target : targets) {
            punishmentService.warnPlayer(target, reason, staffUuid, staffName, silent)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, target);
                        } else {
                            sender.sendMessage(locale.getMessage("success.warn", playerPlaceholder(target)));
                        }
                    });
        }
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        boolean isSilent = args.length > 0 && args[0].equalsIgnoreCase("-s");
        int argIndex = args.length - 1;

        if (argIndex == 0 && !isSilent) {
            List<String> suggestions = new java.util.ArrayList<>(getPlayerSuggestions(args[0]));
            if ("-s".startsWith(args[0].toLowerCase())) suggestions.add("-s");
            return suggestions;
        }
        int targetIndex = isSilent ? 1 : 0;
        if (argIndex == targetIndex) {
            return getPlayerSuggestions(args[argIndex]);
        }
        return List.of();
    }
}