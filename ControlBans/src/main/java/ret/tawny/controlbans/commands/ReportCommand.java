package ret.tawny.controlbans.commands;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class ReportCommand extends CommandBase {

    public ReportCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("report");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.report")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(locale.getMessage("errors.player-only"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("report.usage"));
            return true;
        }

        String targetName = args[0];

        if (sender.getName() != null && sender.getName().equalsIgnoreCase(targetName)) {
            sender.sendMessage(locale.getMessage("report.self-report"));
            return true;
        }

        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }

            if (args.length == 1) {
                scheduler.runTask(() -> plugin.getReportGuiManager().openReportMenu(player, target));
                return;
            }

            StringJoiner reasonJoiner = new StringJoiner(" ");
            for (int i = 1; i < args.length; i++) {
                reasonJoiner.add(args[i]);
            }
            String reason = reasonJoiner.toString();

            plugin.getReportService().submitReport(
                    player.getUniqueId(),
                    player.getName(),
                    targetName,
                    reason
            );
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
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        return List.of();
    }
}
