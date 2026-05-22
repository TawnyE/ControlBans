package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;
import ret.tawny.controlbans.util.ChatUtil;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class PunishCommand extends CommandBase {

    private final PunishGuiManager guiManager;

    public PunishCommand(ControlBansPlugin plugin, PunishGuiManager guiManager) {
        super(plugin);
        setCommand("punish");
        this.guiManager = guiManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(ChatUtil.colorize("&cOnly players can use the GUI."));
            return true;
        }

        if (!player.hasPermission("controlbans.punish")) {
            player.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(ChatUtil.colorize("&cUsage: /punish <player>"));
            return true;
        }

        String targetName = args[0];
        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> player.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }
            scheduler.runTask(() -> guiManager.openPunishMenu(player, target));
        });
        return true;
    }

    private CompletableFuture<OfflinePlayer> resolveTarget(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(online);

        return plugin.getStorage().getUuidByName(name).thenCompose(uuid -> {
            if (uuid != null) return CompletableFuture.completedFuture(Bukkit.getOfflinePlayer(uuid));
            return CompletableFuture.supplyAsync(() -> {
                UUID mojangUuid = ret.tawny.controlbans.util.UuidUtil.lookupUuid(name);
                return mojangUuid != null ? Bukkit.getOfflinePlayer(mojangUuid) : null;
            });
        });
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) return getPlayerSuggestions(args[0]);
        return List.of();
    }
}