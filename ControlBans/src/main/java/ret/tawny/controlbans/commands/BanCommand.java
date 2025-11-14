package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;

public class BanCommand extends CommandBase {

    public BanCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("ban");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.ban")) {
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

        sender.sendMessage(locale.getMessage("actions.banning", playerPlaceholder(targetName)));

        punishmentService.banPlayer(targetName, reason, getSenderUuid(sender), sender.getName(), silent, false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        handlePunishmentError(throwable, sender, targetName);
                    } else {
                        sender.sendMessage(locale.getMessage("success.ban", playerPlaceholder(targetName)));
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