package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
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
        String targetName = args[0];

        punishmentService.getPlayerResolver().getPlayerUuid(targetName).whenComplete((uuid, throwable) ->
                plugin.getSchedulerAdapter().runTask(() -> {
                    if (throwable != null || uuid == null) {
                        sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName)));
                        return;
                    }
                    if (plugin.getSkinBanService().isSkinBanned(uuid)) {
                        sender.sendMessage(locale.getMessage("skins.already-banned", playerPlaceholder(targetName)));
                        return;
                    }
                    plugin.getSkinBanService().banSkin(uuid);
                    sender.sendMessage(locale.getMessage("skins.banned", playerPlaceholder(targetName)));
                }));
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
