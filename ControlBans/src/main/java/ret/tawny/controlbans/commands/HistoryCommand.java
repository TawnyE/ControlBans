package ret.tawny.controlbans.commands;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;
public class HistoryCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public HistoryCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "history");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.history")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.YELLOW + "Fetching punishment history for " + targetName + "...");

        UuidUtil.getUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return null;
            }
            return punishmentService.getPunishmentHistory(uuid, 10);
        }).thenAccept(history -> {
            if (history == null) return;
            if (history.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "No punishment history found.");
            } else {
                sender.sendMessage(ChatColor.GOLD + "--- Punishment History ---");
                for (Punishment p : history) {
                    String status = p.isActive() ? ChatColor.GREEN + "(Active)" : ChatColor.RED + "(Inactive)";
                    sender.sendMessage(String.format("%s %s: %s - %s", status, p.getType().getDisplayName(), p.getReason(), TimeUtil.formatDate(p.getCreatedTime())));
                }
            }
        });
        return true;
    }
}