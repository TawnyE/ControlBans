package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class VoidJailCommand extends CommandBase {

    public VoidJailCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("voidjail");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.voidjail")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null || !target.isOnline()) {
            sender.sendMessage(locale.getMessage("errors.player-offline", playerPlaceholder(args[0])));
            return true;
        }

        plugin.getVoidJailService().jailPlayer(target);
        sender.sendMessage(locale.getMessage("voidjail.success-jail", playerPlaceholder(target.getName())));

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        return List.of();
    }
}