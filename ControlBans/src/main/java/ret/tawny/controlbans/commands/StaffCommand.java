package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class StaffCommand extends CommandBase {

    public StaffCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("staff");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.staff")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        List<Player> onlineStaff = new ArrayList<>();
        for (Player p : plugin.getServer().getOnlinePlayers()) {
            if (p.hasPermission("controlbans.ban") || p.hasPermission("controlbans.admin")) {
                onlineStaff.add(p);
            }
        }

        onlineStaff.sort(Comparator.comparing(Player::getName));

        List<Component> lines = new ArrayList<>();
        lines.add(locale.getMessage("staff.header"));

        if (onlineStaff.isEmpty()) {
            lines.add(locale.getMessage("staff.empty"));
        } else {
            for (Player p : onlineStaff) {
                String rank = getRank(p);
                lines.add(locale.getMessage("staff.entry",
                        Placeholder.unparsed("player", p.getName()),
                        Placeholder.unparsed("rank", rank)));
            }
        }

        lines.forEach(sender::sendMessage);
        return true;
    }

    private String getRank(Player player) {
        if (player.hasPermission("controlbans.admin")) return "Admin";
        if (player.hasPermission("controlbans.ban")) return "Moderator";
        if (player.hasPermission("controlbans.warn")) return "Helper";
        return "Staff";
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        return List.of();
    }
}
