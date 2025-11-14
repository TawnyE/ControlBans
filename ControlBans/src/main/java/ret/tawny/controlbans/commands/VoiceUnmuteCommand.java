package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class VoiceUnmuteCommand extends CommandBase {

    public VoiceUnmuteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("voiceunmute");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.voiceunmute")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
            return true;
        }

        String targetName = args[0];

        sender.sendMessage(locale.getMessage("actions.voice-unmuting", playerPlaceholder(targetName)));
        punishmentService.unVoiceMutePlayer(targetName, getSenderUuid(sender), sender.getName())
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        handlePunishmentError(throwable, sender, targetName);
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.voice-unmute", playerPlaceholder(targetName)));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.player-not-voice-muted", playerPlaceholder(targetName)));
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
