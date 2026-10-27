package ret.tawny.controlbans.commands;

import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.EscalationService;

import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;
import java.util.concurrent.CompletableFuture;

public class PunishCommand extends CommandBase {

    public PunishCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("punish");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        Player player = sender instanceof Player pl ? pl : null;
        // The preset shortcut (/punish <player> <presetId> [notes...]) also works
        // from console/RCON. Opening the interactive UI needs a player.
        if (player == null && args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.player-only"));
            return true;
        }

        if (!sender.hasPermission("controlbans.punish")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            if (player != null) plugin.getMainMenuGuiManager().openMainMenu(player);
            else sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player> <presetId> [notes]")));
            return true;
        }

        String targetName = args[0];

        // Optional shortcut: /punish <player> <presetId> [notes...] applies the
        // preset directly with the BOTH category (all of its actions).
        if (args.length >= 2) {
            Optional<EscalationService.Preset> presetOpt = plugin.getEscalationService().byId(args[1]);
            if (presetOpt.isPresent()) {
                EscalationService.Preset preset = presetOpt.get();
                String notes = null;
                if (args.length >= 3) {
                    StringJoiner sj = new StringJoiner(" ");
                    for (int i = 2; i < args.length; i++) {
                        sj.add(args[i]);
                    }
                    String joined = sj.toString().trim();
                    if (!joined.isEmpty()) notes = joined;
                }
                final String finalNotes = notes;
                final CommandSender staff = sender;
                resolveTarget(targetName).thenAccept(target -> {
                    if (target == null) {
                        scheduler.runTask(() -> staff.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                        return;
                    }
                    plugin.getEscalationService().applyPreset(staff, targetName, preset, EscalationService.Category.BOTH, finalNotes, false)
                            .whenComplete((unused, throwable) -> {
                                if (throwable != null) {
                                    Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                                    scheduler.runTask(() -> handlePunishmentError(cause, staff, targetName));
                                }
                            });
                });
                return true;
            }
        }

        if (player == null) {
            // Console without a preset id — no UI to open.
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player> <presetId> [notes]")));
            return true;
        }

        resolveTarget(targetName).thenAccept(target -> {
            if (target == null) {
                scheduler.runTask(() -> player.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }
            scheduler.runTask(() -> plugin.getPunishUiManager().open(player, target));
        });
        return true;
    }

    private CompletableFuture<OfflinePlayer> resolveTarget(String name) {
        return plugin.getPunishmentService().getPlayerResolver().getOfflineTarget(name);
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) return getPlayerSuggestions(args[0]);
        if (args.length == 2) {
            String query = args[1].toLowerCase();
            return plugin.getEscalationService().getPresets().stream()
                    .map(EscalationService.Preset::id)
                    .filter(id -> id.startsWith(query))
                    .collect(java.util.stream.Collectors.toList());
        }
        return List.of();
    }
}
