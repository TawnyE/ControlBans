package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class AppealsCommand extends CommandBase {

    public AppealsCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("appeals");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
            return true;
        }
        if (!sender.hasPermission("controlbans.appeal.review")) {
            player.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (plugin.getAppealService() == null || !plugin.getConfigManager().isAppealsEnabled()) {
            player.sendMessage(locale.getMessage("appeals.disabled"));
            return true;
        }
        plugin.getAppealsGuiManager().openAppealsGui(player, 1);
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        return List.of();
    }
}
