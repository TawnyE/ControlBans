package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;

public class ShadowMuteCommand extends CommandBase {

    public ShadowMuteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("shadowmute");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.shadowmute")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player> [reason]")));
            return true;
        }

        String targetName = args[0];

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }

        String baseReason = reasonJoiner.toString().isEmpty() ? "Unspecified" : reasonJoiner.toString();
        String finalReason = "[SHADOW] " + baseReason;



        punishmentService.mutePlayer(targetName, finalReason, getSenderUuid(sender), sender.getName(), true)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        handlePunishmentError(throwable, sender, targetName);
                    } else {
                        sender.sendMessage(locale.getMessage("success.mute", playerPlaceholder(targetName)));
                        sender.sendMessage(locale.getMessage("success.shadowmute-secret"));
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
