package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.AltService;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public class AltsCommand extends CommandBase {
    private final AltService altService;

    public AltsCommand(ControlBansPlugin plugin, AltService altService) {
        super(plugin, "alts");
        this.altService = altService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.alts")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player>");
            return true;
        }

        String targetName = args[0];
        sender.sendMessage(ChatColor.YELLOW + "Finding alts for " + targetName + "...");

        UuidUtil.getUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                sender.sendMessage(ChatColor.RED + "Player not found.");
                return null;
            }
            return altService.findAltAccounts(uuid);
        }).thenAccept(alts -> {
            if (alts == null) return;
            if (alts.isEmpty()) {
                sender.sendMessage(ChatColor.GREEN + "No alternate accounts found for " + targetName + ".");
            } else {
                String altNames = alts.stream().map(UUID::toString).collect(Collectors.joining(", "));
                sender.sendMessage(ChatColor.GOLD + "Alts: " + ChatColor.WHITE + altNames);
            }
        });

        return true;
    }
}