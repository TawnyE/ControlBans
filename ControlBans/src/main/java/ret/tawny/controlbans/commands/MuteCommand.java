package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;

public class MuteCommand extends CommandBase {

    public MuteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("mute");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.mute")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;
        if (args.length <= targetIndex) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        String targetName = args[targetIndex];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(locale.getMessage("actions.muting", playerPlaceholder(targetName)));
        punishmentService.mutePlayer(targetName, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        if (throwable instanceof CompletionException && throwable.getCause() instanceof IllegalArgumentException && "Player not found".equals(throwable.getCause().getMessage())) {
                            sender.sendMessage(locale.getMessage("errors.player-not-found-typo", playerPlaceholder(targetName)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                            throwable.printStackTrace();
                        }
                    } else {
                        sender.sendMessage(locale.getMessage("success.mute", playerPlaceholder(targetName)));
                    }
                });
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1 || (args.length == 2 && args[0].equalsIgnoreCase("-s"))) {
            return getPlayerSuggestions(args[args.length - 1]);
        }
        return List.of();
    }
}