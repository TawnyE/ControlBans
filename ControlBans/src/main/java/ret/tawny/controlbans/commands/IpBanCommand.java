package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.StringJoiner;

public class IpBanCommand extends CommandBase {

    public IpBanCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("ipban");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.ban.ip")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player|ip> <time> [reason]")));
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int argOffset = silent ? 1 : 0;

        if (args.length <= argOffset + 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player|ip> <time> [reason]")));
            return true;
        }

        String target = args[argOffset];
        String durationStr = args[argOffset + 1];
        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            if (durationStr.equalsIgnoreCase("0") || durationStr.equalsIgnoreCase("perm") || durationStr.equalsIgnoreCase("permanent")) {
                duration = -1; // -1 signifies a permanent punishment
            } else {
                sender.sendMessage(locale.getMessage("errors.invalid-duration"));
                return true;
            }
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = argOffset + 2; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? "IP Ban" : reasonJoiner.toString();

        sender.sendMessage(locale.getMessage("actions.ip-banning", playerPlaceholder(target)));
        // Fixed: Corrected method name typo
        punishmentService.ipBanPlayer(target, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(locale.getMessage("errors.database-error"));
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.ipban"));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(target)));
                    }
                });
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
            return getIpTimeSuggestions(currentArg);
        }

        return List.of();
    }
}