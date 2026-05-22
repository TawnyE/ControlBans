package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

public class FreezeCommand extends CommandBase {

    public FreezeCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("freeze");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.freeze")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("freeze.usage"));
            return true;
        }

        Player target = Bukkit.getPlayer(args[0]);
        if (target == null) {
            sender.sendMessage(locale.getMessage("errors.player-not-found", Placeholder.unparsed("player", args[0])));
            return true;
        }

        if (target.hasPermission("controlbans.exempt.freeze")) {
            sender.sendMessage(locale.getMessage("freeze.cannot-freeze"));
            return true;
        }

        UUID uuid = target.getUniqueId();
        plugin.getFreezeManager().toggleFreeze(uuid);

        boolean frozen = plugin.getFreezeManager().isFrozen(uuid);
        String status = frozen ? locale.getRawMessage("freeze.frozen") : locale.getRawMessage("freeze.unfrozen");

        sender.sendMessage(locale.getMessage("freeze.status", Placeholder.unparsed("player", target.getName()),
                Placeholder.unparsed("status", status)));

        String alertKey = frozen ? "freeze" : "unfreeze";
        plugin.getNotificationService().sendStaffAlertForAction(alertKey, target.getName(), sender.getName());

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        return Collections.emptyList();
    }
}