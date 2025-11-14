package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.StringJoiner;
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

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;

        if (args.length <= targetIndex + 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        String targetName = args[targetIndex];
        String durationStr = args[targetIndex + 1];
        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(locale.getMessage("errors.invalid-duration"));
            return true;
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 2; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(locale.getMessage("actions.temp-muting", playerPlaceholder(targetName)));
        punishmentService.tempMutePlayer(targetName, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        if (throwable instanceof CompletionException && throwable.getCause() instanceof IllegalArgumentException && "Player not found".equals(throwable.getCause().getMessage())) {
                            sender.sendMessage(locale.getMessage("errors.player-not-found-typo", playerPlaceholder(targetName)));
                        } else if (throwable instanceof CompletionException && throwable.getCause() instanceof IllegalStateException) {
                            sender.sendMessage(locale.getMessage("errors.bedrock-player-not-found", playerPlaceholder(targetName)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                            throwable.printStackTrace();
                        }
                    } else {
                        sender.sendMessage(locale.getMessage("success.tempmute",
                                playerPlaceholder(targetName),
                                durationPlaceholder(TimeUtil.formatDuration(duration))
                        ));
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
            return getTimeSuggestions(currentArg);
        }
        return List.of();
    }
}