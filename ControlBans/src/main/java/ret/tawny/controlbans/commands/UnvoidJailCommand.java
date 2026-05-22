package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.VoidJailService;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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

        String targetName = args[0];
        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }

            if (!voidJailService.isJailed(target.getUniqueId())) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("voidjail.not-jailed", playerPlaceholder(target.getName() != null ? target.getName() : targetName))));
                return;
            }

            voidJailService.unjailPlayer(target);
            scheduler.runTask(() -> sender.sendMessage(locale.getMessage("voidjail.success-unjail", playerPlaceholder(target.getName() != null ? target.getName() : targetName))));
        });
        return true;
    }

    private CompletableFuture<OfflinePlayer> resolveTarget(String name) {
        org.bukkit.entity.Player online = Bukkit.getPlayerExact(name);
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
        return List.of();
    }
}