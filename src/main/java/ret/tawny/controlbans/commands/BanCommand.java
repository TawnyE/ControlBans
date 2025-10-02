package ret.tawny.controlbans.commands;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;
import java.util.Arrays;
import java.util.StringJoiner;
public class BanCommand extends CommandBase {
    private final PunishmentService punishmentService;


    public BanCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "ban");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.ban")) {
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
            sender.sendMessage(ChatColor.RED + "You must specify a player to ban.");
            return true;
        }

        String targetName = args[targetIndex];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        sender.sendMessage(ChatColor.YELLOW + "Banning " + targetName + "...");
        punishmentService.banPlayer(targetName, reason, getSenderUuid(sender), sender.getName(), silent, false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        sender.sendMessage(ChatColor.RED + "Could not ban player: " + throwable.getMessage());
                    } else {
                        sender.sendMessage(ChatColor.GREEN + "Successfully banned " + targetName + ".");
                    }
                });
        return true;
    }
}