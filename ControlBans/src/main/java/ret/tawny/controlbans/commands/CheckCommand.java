package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.concurrent.CompletableFuture;

public class CheckCommand extends CommandBase {
    private final PunishmentService punishmentService;

    public CheckCommand(ControlBansPlugin plugin, PunishmentService punishmentService) {
        super(plugin, "check");
        this.punishmentService = punishmentService;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.check")) {
            sender.sendMessage(ChatColor.RED + "You don't have permission.");
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <player|id>");
            return true;
        }

        String target = args[0];

        // Check if the target looks like a punishment ID (e.g., 6-8 chars, alphanumeric)
        if (target.matches("^[a-zA-Z0-9]{6,8}$")) {
            sender.sendMessage(ChatColor.YELLOW + "Checking punishment ID: " + target + "...");
            punishmentService.getPunishmentById(target).thenAccept(punishmentOpt -> {
                if (punishmentOpt.isPresent()) {
                    displayPunishmentInfo(sender, punishmentOpt.get());
                } else {
                    sender.sendMessage(ChatColor.RED + "No punishment found with that ID.");
                }
            });
        } else {
            // Otherwise, treat it as a player name
            sender.sendMessage(ChatColor.YELLOW + "Checking punishment status for " + target + "...");
            UuidUtil.getUuid(target).thenAccept(uuid -> {
                if (uuid == null) {
                    sender.sendMessage(ChatColor.RED + "Player not found.");
                    return;
                }

                CompletableFuture.allOf(
                        punishmentService.getActiveBan(uuid).thenAccept(banOpt -> {
                            if (banOpt.isPresent()) {
                                sender.sendMessage(ChatColor.RED + "BANNED: " + ChatColor.WHITE + banOpt.get().getReason() + " (ID: " + banOpt.get().getPunishmentId() + ")");
                            } else {
                                sender.sendMessage(ChatColor.GREEN + "Not banned.");
                            }
                        }),
                        punishmentService.getActiveMute(uuid).thenAccept(muteOpt -> {
                            if (muteOpt.isPresent()) {
                                sender.sendMessage(ChatColor.YELLOW + "MUTED: " + ChatColor.WHITE + muteOpt.get().getReason() + " (ID: " + muteOpt.get().getPunishmentId() + ")");
                            } else {
                                sender.sendMessage(ChatColor.GREEN + "Not muted.");
                            }
                        })
                ).join(); // Wait for both checks to complete
            });
        }
        return true;
    }

    private void displayPunishmentInfo(CommandSender sender, Punishment p) {
        String status = p.isActive() && !p.isExpired() ? "&aActive" : "&cInactive";
        String duration = p.isPermanent() ? "Permanent" : TimeUtil.formatDuration((p.getExpiryTime() - p.getCreatedTime()) / 1000);

        TextComponent message = Component.text(ChatColor.translateAlternateColorCodes('&', "&6&lPunishment Info &r&7(&e" + p.getPunishmentId() + "&7)"))
                .hoverEvent(HoverEvent.showText(Component.text(ChatColor.GOLD + "Hover for details")))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Type: " + ChatColor.WHITE + p.getType().getDisplayName()))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Player: " + ChatColor.WHITE + p.getTargetName()))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Staff: " + ChatColor.WHITE + p.getStaffName()))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Reason: " + ChatColor.WHITE + p.getReason()))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Date: " + ChatColor.WHITE + TimeUtil.formatDate(p.getCreatedTime())))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Duration: " + ChatColor.WHITE + duration))
                .append(Component.newline())
                .append(Component.text(ChatColor.GRAY + "  » " + ChatColor.AQUA + "Status: " + ChatColor.translateAlternateColorCodes('&', status)));

        sender.sendMessage(message);
    }
}