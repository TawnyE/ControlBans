package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class UnbanSkinCommand extends CommandBase {

    public UnbanSkinCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("unbanskin");
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

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!plugin.getSkinBanService().isSkinBanned(target.getUniqueId())) {
            sender.sendMessage(locale.getMessage("skinban.not-banned", playerPlaceholder(target.getName())));
            return true;
        }

        plugin.getSkinBanService().unbanSkin(target);
        sender.sendMessage(locale.getMessage("skinban.success-unban", playerPlaceholder(target.getName())));

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        // We do not suggest online players as the target may be offline.
        // A future improvement could be to suggest from a list of skin-banned players.
        return List.of();
    }
}