package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class UnmuteCommand extends CommandBase {

    public UnmuteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("unmute");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.unmute")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
            return true;
        }
        String targetName = args[0];
        sender.sendMessage(locale.getMessage("actions.unmuting", playerPlaceholder(targetName)));

        punishmentService.unmutePlayer(targetName, getSenderUuid(sender), sender.getName())
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        if (throwable.getCause() instanceof IllegalArgumentException) {
                            sender.sendMessage(locale.getMessage("errors.player-not-found-typo", playerPlaceholder(targetName)));
                        } else if (throwable.getCause() instanceof IllegalStateException) {
                            sender.sendMessage(locale.getMessage("errors.bedrock-player-not-found", playerPlaceholder(targetName)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                        }
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.unmute", playerPlaceholder(targetName)));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.player-not-muted", playerPlaceholder(targetName)));
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