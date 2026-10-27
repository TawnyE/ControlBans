package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
            // Console/RCON: plain text list instead of the GUI.
            if (!sender.hasPermission("controlbans.alts")) {
                sender.sendMessage(locale.getMessage("errors.no-permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
                return true;
            }
            runConsoleAlts(sender, args[0]);
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
        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> viewer.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }
            scheduler.runTask(() -> guiManager.openAltsGui(viewer, target, 1));
        });
        return true;
    }

    private void runConsoleAlts(CommandSender sender, String targetName) {
        plugin.getPunishmentService().getPlayerResolver().getPlayerUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return CompletableFuture.completedFuture(null);
            }
            return plugin.getAltService().findAltAccounts(uuid).thenAccept(alts -> {
                if (alts.isEmpty()) {
                    scheduler.runTask(() -> sender.sendMessage(locale.getMessage("alts.no-alts", playerPlaceholder(targetName))));
                    return;
                }
                List<CompletableFuture<String>> labelFutures = alts.stream().map(altUuid ->
                        plugin.getStorage().getLastKnownName(altUuid).thenCompose(name ->
                                plugin.getPunishmentService().getActiveBan(altUuid).thenApply(ban ->
                                        (name != null ? name : altUuid.toString()) + (ban.isPresent() && !ban.get().isExpired() ? " [BANNED]" : ""))
                        )).toList();
                CompletableFuture.allOf(labelFutures.toArray(new CompletableFuture[0])).thenAccept(v -> {
                    String joined = String.join(", ", labelFutures.stream().map(CompletableFuture::join).toList());
                    scheduler.runTask(() -> sender.sendMessage(locale.getMessage("alts.console-list",
                            playerPlaceholder(targetName),
                            Placeholder.unparsed("count", String.valueOf(alts.size())),
                            Placeholder.unparsed("alts", joined))));
                });
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(java.util.logging.Level.WARNING, "Failed to run /alts for " + targetName, throwable);
            scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.database-error")));
            return null;
        });
    }

    private CompletableFuture<OfflinePlayer> resolveTarget(String name) {
        return plugin.getPunishmentService().getPlayerResolver().getOfflineTarget(name);
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        return List.of();
    }
}