package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class UnbanCommand extends CommandBase {

    public UnbanCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("unban");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.unban")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(locale.getMessage("actions.unbanning", playerPlaceholder(targetName)));

        punishmentService.unbanPlayer(targetName, getSenderUuid(sender), sender.getName())
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(locale.getMessage("errors.database-error"));
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.unban", playerPlaceholder(targetName)));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.player-not-banned", playerPlaceholder(targetName)));
                    }
                });
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        return List.of();
    }
}