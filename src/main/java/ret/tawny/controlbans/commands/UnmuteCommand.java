package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;

public class UnmuteCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public UnmuteCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "unmute");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.unmute")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.YELLOW + "Unmuting " + targetName + "...");

        punishmentService.unmutePlayer(targetName, getSenderUuid(sender), sender.getName(), false)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not unmute player: " + throwable.getMessage());
                    } else if (success) {
                        sender.sendMessage(ChatColor.GREEN + "Successfully unmuted " + targetName + ".");
                    } else {
                        sender.sendMessage(ChatColor.RED + targetName + " was not muted.");
                    }
                });
        return true;
    }
}