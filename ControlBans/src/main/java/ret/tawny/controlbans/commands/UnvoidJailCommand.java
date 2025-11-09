package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.VoidJailService;

import java.util.List;

public class UnvoidJailCommand extends CommandBase {

    private final VoidJailService voidJailService;

    public UnvoidJailCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("unvoidjail");
        this.voidJailService = plugin.getVoidJailService();
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

        @SuppressWarnings("deprecation")
        OfflinePlayer target = Bukkit.getOfflinePlayer(args[0]);

        if (!voidJailService.isJailed(target.getUniqueId())) {
            sender.sendMessage(locale.getMessage("voidjail.not-jailed", playerPlaceholder(target.getName())));
            return true;
        }

        voidJailService.unjailPlayer(target);
        sender.sendMessage(locale.getMessage("voidjail.success-unjail", playerPlaceholder(target.getName())));

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        // We don't suggest online players here because the player might be offline.
        // A future improvement could be to suggest from a list of jailed players.
        return List.of();
    }
}