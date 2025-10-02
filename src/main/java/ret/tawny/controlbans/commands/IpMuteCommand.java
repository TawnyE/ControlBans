package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.StringJoiner;

public class IpMuteCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public IpMuteCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "ipmute");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.mute.ip")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission to use this command.");
            return true;
        }

        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [-s] <player|ip> <time> [reason]");
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int argOffset = silent ? 1 : 0;

        if (args.length <= argOffset + 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " [-s] <player|ip> <time> [reason]");
            return true;
        }

        String target = args[argOffset];
        String durationStr = args[argOffset + 1];
        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            if (durationStr.equalsIgnoreCase("0") || durationStr.equalsIgnoreCase("perm") || durationStr.equalsIgnoreCase("permanent")) {
                duration = -1;
            } else {
                sender.sendMessage(ChatColor.RED + "Invalid time format. Use 'perm' for permanent, or a duration like 1d2h.");
                return true;
            }
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = argOffset + 2; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? "IP Mute" : reasonJoiner.toString();

        sender.sendMessage(ChatColor.YELLOW + "Applying IP mute for target: " + target);
        punishmentService.ipMutePlayer(target, duration, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not apply IP mute: " + throwable.getMessage());
                    } else if (success) {
                        sender.sendMessage(ChatColor.GREEN + "Successfully applied IP mute.");
                    } else {
                        sender.sendMessage(ChatColor.RED + "Could not find a player or valid IP to mute.");
                    }
                });
        return true;
    }
}