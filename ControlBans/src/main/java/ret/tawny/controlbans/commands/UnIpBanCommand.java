package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;

public class UnIpBanCommand extends CommandBase {

    public UnIpBanCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("unipban");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.unipban")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player|ip>")));
            return true;
        }

        String targetIp = args[0];

        punishmentService.unbanIpByPlayer(targetIp, getSenderUuid(sender), sender.getName())
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        if ((throwable.getMessage() != null && throwable.getMessage().contains("no-ip-on-record")) ||
                            (throwable.getCause() != null && throwable.getCause().getMessage() != null && throwable.getCause().getMessage().contains("no-ip-on-record"))) {
                            sender.sendMessage(locale.getMessage("errors.no-ip-on-record", playerPlaceholder(targetIp)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                        }
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.unban_ip", ipPlaceholder(targetIp)));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.ip-not-banned", ipPlaceholder(targetIp)));
                    }
                });
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
