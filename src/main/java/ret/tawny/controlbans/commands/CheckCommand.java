package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.concurrent.CompletableFuture;

public class CheckCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public CheckCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "check");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.check")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.YELLOW + "Checking punishment status for " + targetName + "...");

        UuidUtil.getUuid(targetName).thenAccept(uuid -> {
            if (uuid == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return;
            }

            CompletableFuture.allOf(
                    punishmentService.getActiveBan(uuid).thenAccept(banOpt -> {
                        if (banOpt.isPresent()) {
                            sender.sendMessage(ChatColor.RED + "BANNED: " + ChatColor.WHITE + banOpt.get().getReason());
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "Not banned.");
                        }
                    }),
                    punishmentService.getActiveMute(uuid).thenAccept(muteOpt -> {
                        if (muteOpt.isPresent()) {
                            sender.sendMessage(ChatColor.YELLOW + "MUTED: " + ChatColor.WHITE + muteOpt.get().getReason());
                        } else {
                            sender.sendMessage(ChatColor.GREEN + "Not muted.");
                        }
                    })
            ).join(); // Wait for both checks to complete
        });
        return true;
    }
}