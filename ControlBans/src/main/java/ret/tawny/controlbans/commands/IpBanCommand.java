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

        boolean silent = detectSilentFlag(args);
        String[] cleanArgs = stripSilentFlag(args);

        if (cleanArgs.length <= 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player|ip> <time> [reason]")));
            return true;
        }

        String target = cleanArgs[0];
        String durationStr = cleanArgs[1];
        long duration;
        if (durationStr.equalsIgnoreCase("0") || durationStr.equalsIgnoreCase("0s")
                || durationStr.equalsIgnoreCase("perm") || durationStr.equalsIgnoreCase("permanent")) {
            duration = -1;
        } else {
            try {
                duration = TimeUtil.parseDuration(durationStr);
            } catch (IllegalArgumentException e) {
                sender.sendMessage(locale.getMessage("errors.invalid-duration"));
                return true;
            }
        }
        if (duration != -1) {
            long max = plugin.getConfigManager().getMaxTempBanDuration();
            if (duration > max) duration = max;
            if (duration <= 0) {
                sender.sendMessage(locale.getMessage("errors.invalid-duration"));
                return true;
            }
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = 2; i < cleanArgs.length; i++) {
            reasonJoiner.add(cleanArgs[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? "IP Ban" : reasonJoiner.toString();

        punishmentService.ipBanPlayer(target, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        String message = throwable.getMessage() != null ? throwable.getMessage()
                                : throwable.getCause() != null && throwable.getCause().getMessage() != null ? throwable.getCause().getMessage() : "";
                        if (message.contains("no-ip-on-record")) {
                            sender.sendMessage(locale.getMessage("errors.no-ip-on-record", playerAndTargetPlaceholders(target)));
                        } else if (message.contains("unsafe-ip")) {
                            sender.sendMessage(locale.getMessage("errors.unsafe-ip"));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                        }
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.ipban"));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.player-not-found", playerAndTargetPlaceholders(target)));
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