package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.regex.Pattern;

public class UnIpBanCommand extends CommandBase {

    private static final Pattern PUNISHMENT_ID_PATTERN = Pattern.compile("^[A-Z0-9]{8}$");

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
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player|ip|#id>")));
            return true;
        }

        String target = args[0];
        String id = target.startsWith("#") ? target.substring(1) : null;
        if (id != null && PUNISHMENT_ID_PATTERN.matcher(id).matches()) {
            punishmentService.unbanIpById(id, getSenderUuid(sender), sender.getName())
                    .whenComplete((success, throwable) -> {
                        if (throwable != null) {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                        } else if (success) {
                            sender.sendMessage(locale.getMessage("success.unban_ip", ipPlaceholder("#" + id)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.ip-not-banned", ipPlaceholder("#" + id)));
                        }
                    });
            return true;
        }

        punishmentService.unbanIpByPlayer(target, getSenderUuid(sender), sender.getName())
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        if ((throwable.getMessage() != null && throwable.getMessage().contains("no-ip-on-record")) ||
                            (throwable.getCause() != null && throwable.getCause().getMessage() != null && throwable.getCause().getMessage().contains("no-ip-on-record"))) {
                            sender.sendMessage(locale.getMessage("errors.no-ip-on-record", playerAndTargetPlaceholders(target)));
                        } else {
                            sender.sendMessage(locale.getMessage("errors.database-error"));
                        }
                    } else if (success) {
                        sender.sendMessage(locale.getMessage("success.unban_ip", ipPlaceholder(target)));
                    } else {
                        sender.sendMessage(locale.getMessage("errors.ip-not-banned", ipPlaceholder(target)));
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
