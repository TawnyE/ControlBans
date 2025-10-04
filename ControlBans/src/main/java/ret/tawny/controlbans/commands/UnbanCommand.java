package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;

public class UnbanCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public UnbanCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "unban");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.unban")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.YELLOW + "Unbanning " + targetName + "...");

        punishmentService.unbanPlayer(targetName, getSenderUuid(sender), sender.getName(), false)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not unban player: " + throwable.getMessage());
                    } else if (success) {
                        sender.sendMessage(ChatColor.GREEN + "Successfully unbanned " + targetName + ".");
                    } else {
                        sender.sendMessage(ChatColor.RED + targetName + " was not banned.");
                    }
                });
        return true;
    }
}