package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class CheckCommand extends CommandBase {

    public CheckCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("check");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.check")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <player|id>")));
            return true;
        }

        String target = args[0];
        boolean possibleId = target.matches("^[a-zA-Z0-9]{6,8}$");

        plugin.getPunishmentService().getPlayerResolver().getPlayerUuid(target).thenCompose(uuid -> {
            if (uuid != null) {
                return checkPunishments(sender, uuid, target);
            }

            if (possibleId) {
                return punishmentService.getPunishmentById(target).thenCompose(punishmentOpt -> {
                    if (punishmentOpt.isPresent()) {
                        scheduler.runTask(() -> displayPunishmentInfo(sender, punishmentOpt.get()));
                    } else {
                        scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.id-not-found")));
                    }
                    return CompletableFuture.completedFuture(null);
                });
            }

            scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(target))));
            return CompletableFuture.completedFuture(null);
        }).exceptionally(throwable -> {
            plugin.getLogger().log(Level.WARNING, "Failed to run /check for " + target, throwable);
            scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.database-error")));
            return null;
        });

        return true;
    }

    private CompletableFuture<Void> checkPunishments(CommandSender sender, UUID uuid, String target) {
        sender.sendMessage(locale.getMessage("actions.checking-player", playerPlaceholder(target)));

        CompletableFuture<Optional<Punishment>> banFuture = punishmentService.getActiveBan(uuid);
        CompletableFuture<Optional<Punishment>> muteFuture = punishmentService.getActiveMute(uuid);

        return CompletableFuture.allOf(banFuture, muteFuture).thenCompose(ignored -> {
            Optional<Punishment> banOpt = banFuture.join();
            Optional<Punishment> muteOpt = muteFuture.join();

            scheduler.runTask(() -> {
                if (banOpt.isPresent()) {
                    sender.sendMessage(locale.getMessage("check.banned",
                            reasonPlaceholder(banOpt.get().getReason()),
                            idPlaceholder(banOpt.get().getPunishmentId())
                    ));
                } else {
                    sender.sendMessage(locale.getMessage("check.not-banned"));
                }

                if (muteOpt.isPresent()) {
                    sender.sendMessage(locale.getMessage("check.muted",
                            reasonPlaceholder(muteOpt.get().getReason()),
                            idPlaceholder(muteOpt.get().getPunishmentId())
                    ));
                } else {
                    sender.sendMessage(locale.getMessage("check.not-muted"));
                }
            });
            return CompletableFuture.completedFuture(null);
        });
    }

    private void displayPunishmentInfo(CommandSender sender, Punishment p) {
        String statusKey = p.isActive() && !p.isExpired() ? "check.status-active" : "check.status-inactive";
        String duration = p.isPermanent() ? "Permanent" : TimeUtil.formatDuration((p.getExpiryTime() - p.getCreatedTime()) / 1000);

        Component message = Component.join(JoinConfiguration.newlines(), List.of(
                locale.getMessage("check.info-header", idPlaceholder(p.getPunishmentId())),
                locale.getMessage("check.info-type", typePlaceholder(p.getType().getDisplayName())),
                locale.getMessage("check.info-player", playerPlaceholder(p.getTargetName())),
                locale.getMessage("check.info-staff", staffPlaceholder(p.getStaffName())),
                locale.getMessage("check.info-reason", reasonPlaceholder(p.getReason())),
                locale.getMessage("check.info-date", datePlaceholder(TimeUtil.formatDate(p.getCreatedTime()))),
                locale.getMessage("check.info-duration", durationPlaceholder(duration)),
                locale.getMessage("check.info-status", Placeholder.parsed("status", locale.getRawMessage(statusKey)))
        ));

        sender.sendMessage(message);
    }
}