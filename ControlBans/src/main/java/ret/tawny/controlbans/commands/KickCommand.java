package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.StringJoiner;

public class KickCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public KickCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "kick");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.kick")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player> [reason]");
            return true;
        }

        String targetName = args[0];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(ChatColor.YELLOW + "Kicking " + targetName + "...");
        punishmentService.kickPlayer(targetName, reason, getSenderUuid(sender), sender.getName(), false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not kick player: " + throwable.getMessage());
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Successfully kicked " + targetName + ".");
                    }
                });
        return true;
    }
}