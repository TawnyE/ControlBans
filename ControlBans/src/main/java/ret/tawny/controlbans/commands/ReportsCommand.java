package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class ReportsCommand extends CommandBase {

    public ReportsCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("reports");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                sender.sendMessage("This command is for players only. Console can only use /reports resolve <id> <status>");
                return true;
            }
        } else if (args.length == 0) {
            plugin.getMyReportsGuiManager().openMyReportsGui(player, 1);
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("resolve")) {
            if (!sender.hasPermission("controlbans.report.resolve")) {
                sender.sendMessage(locale.getMessage("errors.no-permission"));
                return true;
            }

            String id = args[1];
            String newStatus = args[2].toUpperCase();
            
            if (!newStatus.equals("PENDING") && !newStatus.equals("RESOLVED") && !newStatus.equals("REJECTED")) {
                sender.sendMessage(locale.getRawMessage("errors.invalid-arguments").replace("<usage>", "/reports resolve <id> <PENDING|RESOLVED|REJECTED>"));
                return true;
            }
            
            plugin.getReportService().updateReportStatus(id, newStatus).thenAccept(success -> {
                scheduler.runTask(() -> {
                    if (success) {
                        sender.sendMessage("Report " + id + " has been marked as " + newStatus);
                    } else {
                        sender.sendMessage("Report with that ID was not found.");
                    }
                });
            });
            return true;
        }

        sender.sendMessage("Usage: /reports [resolve <id> <status>]");
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1 && sender.hasPermission("controlbans.report.resolve")) {
            return List.of("resolve");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("resolve")) {
            return List.of("PENDING", "RESOLVED", "REJECTED");
        }
        return List.of();
    }
}
