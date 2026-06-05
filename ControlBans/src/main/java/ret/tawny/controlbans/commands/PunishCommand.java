package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;
import ret.tawny.controlbans.util.ChatUtil;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;

import java.util.List;
import java.util.UUID;
import java.util.StringJoiner;
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
        String reason = null;
        if (args.length >= 2) {
            StringJoiner sj = new StringJoiner(" ");
            for (int i = 1; i < args.length; i++) {
                sj.add(args[i]);
            }
            reason = sj.toString();
        }

        final String finalReason = reason;
        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> player.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }

            if (finalReason != null) {
                var templateService = plugin.getPunishmentService().getTemplateService();
                var templateOpt = templateService.findTemplate(finalReason);
                if (templateOpt.isPresent()) {
                    var template = templateOpt.get();

                    if (template.getPermission() != null && !player.hasPermission(template.getPermission())) {
                        scheduler.runTask(() -> player.sendMessage(locale.getMessage("errors.no-permission")));
                        return;
                    }

                    String customReason = finalReason.replace("#" + template.getKey(), "").trim();
                    if (customReason.equalsIgnoreCase(finalReason)) {
                        customReason = finalReason.replaceAll("(?i)#" + template.getKey(), "").trim();
                    }
                    final String notes = customReason.isEmpty() ? null : customReason;

                    plugin.getPunishmentService().applyTemplatePunishment(targetName, template, notes, player.getUniqueId(), player.getName(), false)
                        .whenComplete((unused, throwable) -> {
                            if (throwable != null) {
                                scheduler.runTask(() -> player.sendMessage(Component.text("Error applying punishment: " + throwable.getMessage(), NamedTextColor.RED)));
                            } else {
                                scheduler.runTask(() -> player.sendMessage(MiniMessage.miniMessage().deserialize("<green>Applied template </green>" + template.getDisplayName() + "<green> to " + targetName + "</green>")));
                            }
                        });
                    return;
                }
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
        if (args.length == 2) return getTemplateSuggestions(args[1]);
        return List.of();
    }
}