package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class BanSkinCommand extends CommandBase {

    public BanSkinCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("banskin");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.banskin")) {
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

        boolean success = plugin.getSkinBanService().banSkin(target);

        if (success) {
            sender.sendMessage(locale.getMessage("skinban.success-ban", playerPlaceholder(target.getName())));
            target.sendMessage(locale.getMessage("skinban.player-notification"));
        } else {
            sender.sendMessage(locale.getMessage("skinban.already-banned", playerPlaceholder(target.getName())));
        }

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