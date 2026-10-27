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
        if (!sender.hasPermission("controlbans.report")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            if (args.length == 0) {
                sender.sendMessage(locale.getMessage("errors.player-only"));
                return true;
            }
        } else if (args.length == 0) {
            if (player.hasPermission("controlbans.report.view")) {
                plugin.getStaffReportsGuiManager().openReportsGui(player, 1,
                        ret.tawny.controlbans.commands.gui.StaffReportsGuiManager.Filter.ALL);
            } else {
                plugin.getMyReportsGuiManager().openMyReportsGui(player, 1);
            }
            return true;
        } else if (args[0].equalsIgnoreCase("admin")) {
            if (!player.hasPermission("controlbans.report.view")) {
                sender.sendMessage(locale.getMessage("errors.no-permission"));
                return true;
            }
            ret.tawny.controlbans.commands.gui.StaffReportsGuiManager.Filter filter =
                    ret.tawny.controlbans.commands.gui.StaffReportsGuiManager.Filter.ALL;
            if (args.length >= 2) {
                try {
                    filter = ret.tawny.controlbans.commands.gui.StaffReportsGuiManager.Filter
                            .valueOf(args[1].toUpperCase());
                } catch (IllegalArgumentException e) {
                    sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/reports admin [ALL|PENDING|RESOLVED|REJECTED]")));
                    return true;
                }
            }
            plugin.getStaffReportsGuiManager().openReportsGui(player, 1, filter);
            return true;
        }

        if (args.length >= 3 && args[0].equalsIgnoreCase("resolve")) {
            if (!sender.hasPermission("controlbans.report.resolve")) {
                sender.sendMessage(locale.getMessage("errors.no-permission"));
                return true;
            }

            String id = args[1];
            String newStatus = args[2].toUpperCase();

            if (!newStatus.equals("PENDING") && !newStatus.equals("RESOLVED") && !newStatus.equals("REJECTED") && !newStatus.equals("DISMISSED")) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/reports resolve <id> <PENDING|RESOLVED|REJECTED|DISMISSED>")));
                return true;
            }
            
            plugin.getReportService().updateReportStatus(id, newStatus).thenAccept(success -> {
                scheduler.runTask(() -> {
                    if (success) {
                        sender.sendMessage(locale.getMessage("reports.resolve-success",
                                idPlaceholder(id),
                                typePlaceholder(newStatus)));
                    } else {
                        sender.sendMessage(locale.getMessage("reports.resolve-not-found", idPlaceholder(id)));
                    }
                });
            });
            return true;
        }

        sender.sendMessage(locale.getMessage("reports.usage", usagePlaceholder("/reports [resolve <id> <status>]")));
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            java.util.List<String> options = new java.util.ArrayList<>();
            if (sender.hasPermission("controlbans.report.resolve")) options.add("resolve");
            if (sender.hasPermission("controlbans.report.view")) options.add("admin");
            return options;
        } else if (args.length == 2 && args[0].equalsIgnoreCase("admin") && sender.hasPermission("controlbans.report.view")) {
            return List.of("ALL", "PENDING", "RESOLVED", "REJECTED");
        } else if (args.length == 3 && args[0].equalsIgnoreCase("resolve")) {
            return List.of("PENDING", "RESOLVED", "REJECTED", "DISMISSED");
        }
        return List.of();
    }
}
