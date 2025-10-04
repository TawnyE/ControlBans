package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.StringJoiner;

public class WarnCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public WarnCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "warn");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.warn")) {
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

        sender.sendMessage(ChatColor.YELLOW + "Warning " + targetName + "...");
        punishmentService.warnPlayer(targetName, reason, getSenderUuid(sender), sender.getName(), false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not warn player: " + throwable.getMessage());
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Successfully warned " + targetName + ".");
                    }
                });
        return true;
    }
}