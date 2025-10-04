package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.StringJoiner;

public class MuteCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public MuteCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "mute");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.mute")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [-s] <player> [reason]");
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;
        if (args.length <= targetIndex) {
            sender.sendMessage(ChatColor.RED + "You must specify a player to mute.");
            return true;
        }

        String targetName = args[targetIndex];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(ChatColor.YELLOW + "Muting " + targetName + "...");
        punishmentService.mutePlayer(targetName, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not mute player: " + throwable.getMessage());
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Successfully muted " + targetName + ".");
                    }
                });
        return true;
    }
}