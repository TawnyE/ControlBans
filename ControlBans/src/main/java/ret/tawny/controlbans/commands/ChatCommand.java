package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class ChatCommand extends CommandBase {

    public ChatCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("chat");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.chat")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("chat.usage"));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "lock":
                boolean current = plugin.getChatManager().isLocked();
                plugin.getChatManager().setLocked(!current);
                Component lockMessage = !current ? locale.getMessage("chat.locked", Placeholder.unparsed("player", sender.getName())) : locale.getMessage("chat.unlocked", Placeholder.unparsed("player", sender.getName()));
                Bukkit.broadcast(lockMessage);
                break;
            case "slow":
                if (args.length < 2) {
                    sender.sendMessage(locale.getMessage("chat.slow-usage"));
                    return true;
                }

                long seconds;
                String timeArg = args[1].toLowerCase();

                if (timeArg.equals("off") || timeArg.equals("false")) {
                    seconds = 0;
                } else {
                    try {
                        seconds = Long.parseLong(timeArg);
                    } catch (NumberFormatException e) {
                        sender.sendMessage(locale.getMessage("chat.invalid-number"));
                        return true;
                    }
                }

                plugin.getChatManager().setSlowmodeDelay(seconds);
                if (seconds > 0) {
                    Bukkit.broadcast(locale.getMessage("chat.slowed", Placeholder.unparsed("seconds", String.valueOf(seconds)), Placeholder.unparsed("player", sender.getName())));
                } else {
                    Bukkit.broadcast(locale.getMessage("chat.slow-disabled", Placeholder.unparsed("player", sender.getName())));
                }
                break;
            case "clear":
                for (int i = 0; i < 100; i++) {
                    Bukkit.broadcast(Component.text(" "));
                }
                Bukkit.broadcast(locale.getMessage("chat.cleared", Placeholder.unparsed("player", sender.getName())));
                break;
            default:
                sender.sendMessage(locale.getMessage("chat.unknown-subcommand"));
                break;
        }
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Arrays.asList("lock", "slow", "clear");
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("slow")) {
            return Arrays.asList("off", "5", "10", "30", "60");
        }
        return Collections.emptyList();
    }
}