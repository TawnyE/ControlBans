package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;

import java.util.List;

public class AltsCommand extends CommandBase {
    private final AltsGuiManager guiManager;

    public AltsCommand(ControlBansPlugin plugin, AltsGuiManager guiManager) {
        super(plugin);
        setCommand("alts");
        this.guiManager = guiManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
            return true;
        }
        if (!viewer.hasPermission("controlbans.alts")) {
            viewer.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            viewer.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
            return true;
        }

        String targetName = args[0];

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        if (!target.hasPlayedBefore() && !target.isOnline()) {
            viewer.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName)));
            return true;
        }

        guiManager.openAltsGui(viewer, target, 1);
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