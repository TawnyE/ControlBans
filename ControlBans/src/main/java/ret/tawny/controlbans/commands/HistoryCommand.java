package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class HistoryCommand extends CommandBase {
    private final HistoryGuiManager guiManager;

    public HistoryCommand(ControlBansPlugin plugin, HistoryGuiManager guiManager) {
        super(plugin);
        setCommand("history");
        this.guiManager = guiManager;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player viewer)) {
            // Console/RCON: plain text output instead of the GUI.
            if (!sender.hasPermission("controlbans.history")) {
                sender.sendMessage(locale.getMessage("errors.no-permission"));
                return true;
            }
            if (args.length < 1) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player>")));
                return true;
            }
            runConsoleHistory(sender, args[0]);
            return true;
        }

        if (!viewer.hasPermission("controlbans.history")) {
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
            scheduler.runTask(() -> guiManager.openHistoryGui(viewer, target, 1));
        });
        return true;
    }

    private void runConsoleHistory(CommandSender sender, String targetName) {
        plugin.getPunishmentService().getPlayerResolver().getPlayerUuid(targetName).thenCompose(uuid -> {
            if (uuid == null) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return CompletableFuture.completedFuture(null);
            }
            return plugin.getPunishmentService().getPunishmentHistory(uuid, 20).thenAccept(history -> {
                scheduler.runTask(() -> {
                    if (history.isEmpty()) {
                        sender.sendMessage(locale.getMessage("history.console-empty", playerPlaceholder(targetName)));
                        return;
                    }
                    sender.sendMessage(locale.getMessage("history.console-header",
                            playerPlaceholder(targetName),
                            Placeholder.unparsed("count", String.valueOf(history.size()))));
                    for (Punishment p : history) {
                        String statusKey = p.isActive() && !p.isExpired() ? "check.status-active" : "check.status-inactive";
                        String status = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText()
                                .serialize(locale.getMessage(statusKey));
                        sender.sendMessage(locale.getMessage("history.console-entry",
                                idPlaceholder(p.getPunishmentId()),
                                Placeholder.unparsed("type", p.getType().name()),
                                reasonPlaceholder(p.getReason()),
                                Placeholder.unparsed("staff", p.getStaffName() != null ? p.getStaffName() : "Console"),
                                Placeholder.unparsed("date", TimeUtil.formatDate(p.getCreatedTime())),
                                Placeholder.unparsed("status", status)));
                    }
                });
            });
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to run /history for " + targetName, throwable);
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