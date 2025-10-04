package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.StringJoiner;

public class TempMuteCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public TempMuteCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "tempmute");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.tempmute")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [-s] <player> <time> [reason]");
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;

        if (args.length <= targetIndex + 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [-s] <player> <time> [reason]");
            return true;
        }

        String targetName = args[targetIndex];
        String durationStr = args[targetIndex + 1];
        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(ChatColor.RED + "Invalid time format. Use: 1y1mo1w1d1h1m1s");
            return true;
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 2; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(ChatColor.YELLOW + "Temporarily muting " + targetName + "...");
        punishmentService.tempMutePlayer(targetName, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not temp-mute player: " + throwable.getMessage());
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Successfully temporarily muted " + targetName + ".");
                    }
                });
        return true;
    }
}