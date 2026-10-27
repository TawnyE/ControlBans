package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletionException;

public class KickCommand extends CommandBase {

    public KickCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("kick");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.kick")) {
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

        for (String target : targets) {
            punishmentService.kickPlayer(target, reason, staffUuid, staffName, silent)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, target);
                        } else {
                            sender.sendMessage(locale.getMessage("success.kick", playerPlaceholder(target)));
                        }
                    });
        }
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        int argIndex = args.length - 1;
        return getPlayerSuggestions(args[argIndex]);
    }
}