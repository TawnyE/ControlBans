package ret.tawny.controlbans.listeners;

import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;
import ret.tawny.controlbans.commands.gui.SettingsGuiManager;
import ret.tawny.controlbans.commands.gui.ReportGuiManager;
import ret.tawny.controlbans.commands.gui.MyReportsGuiManager;
import ret.tawny.controlbans.menus.ControlBansHolder;

public class GuiListener implements Listener {

    private final ControlBansPlugin plugin;
    private final HistoryGuiManager historyGuiManager;
    private final AltsGuiManager altsGuiManager;
    private final SettingsGuiManager settingsGuiManager;
    private final PunishGuiManager punishGuiManager;
    private final ReportGuiManager reportGuiManager;
    private final MyReportsGuiManager myReportsGuiManager;

    public GuiListener(HistoryGuiManager historyGuiManager, AltsGuiManager altsGuiManager, SettingsGuiManager settingsGuiManager, PunishGuiManager punishGuiManager, ReportGuiManager reportGuiManager, MyReportsGuiManager myReportsGuiManager, ControlBansPlugin plugin) {
        this.historyGuiManager = historyGuiManager;
        this.altsGuiManager = altsGuiManager;
        this.settingsGuiManager = settingsGuiManager;
        this.punishGuiManager = punishGuiManager;
        this.reportGuiManager = reportGuiManager;
        this.myReportsGuiManager = myReportsGuiManager;
        this.plugin = plugin;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof ControlBansHolder)) {
            return;
        }

        event.setCancelled(true);
        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        if (holder instanceof HistoryGuiManager.HistoryHolder historyHolder) {
            handleHistoryClick(event, player, historyHolder);
        } else if (holder instanceof AltsGuiManager.AltsHolder altsHolder) {
            handleAltsClick(event, player, altsHolder);
        } else if (holder instanceof PunishGuiManager.PunishHolder punishHolder) {
            handlePunishClick(player, punishHolder, item, event.getSlot());
        } else if (holder instanceof ReportGuiManager.ReportHolder reportHolder) {
            handleReportClick(player, reportHolder, item);
        } else if (holder instanceof MyReportsGuiManager.MyReportsHolder myReportsHolder) {
            handleMyReportsClick(event, player, myReportsHolder);
        } else if (holder instanceof SettingsGuiManager.SettingsHolder) {
            settingsGuiManager.handleClick(player, item);
        }
    }

    private void handleReportClick(Player player, ReportGuiManager.ReportHolder holder, ItemStack item) {
        if (item.getType() == Material.BLACK_STAINED_GLASS_PANE || item.getType() == Material.PLAYER_HEAD) {
            return;
        }

        OfflinePlayer target = holder.getTarget();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        String reason = switch (item.getType()) {
            case DIAMOND_SWORD -> "Hacking";
            case PAPER -> "Chat Abuse";
            case TNT -> "Griefing";
            case MAP -> "Other";
            default -> null;
        };

        if (reason == null) {
            return;
        }

        player.closeInventory();
        
        boolean submitted = plugin.getReportService().submitReport(
                player.getUniqueId(),
                player.getName(),
                targetName,
                reason
        );

        if (!submitted) {
            plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                player.sendMessage(plugin.getLocaleManager().getMessage("report.cooldown",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("duration", "60s")))
            );
        }
    }

    private void handlePunishClick(Player player, PunishGuiManager.PunishHolder holder, ItemStack item, int slot) {
        if (item.getType() == Material.BLACK_STAINED_GLASS_PANE || item.getType() == Material.ORANGE_STAINED_GLASS_PANE || item.getType() == Material.BLUE_CANDLE || item.getType() == Material.YELLOW_CANDLE) {
            return;
        }

        if (item.getType() == Material.BARRIER && slot == 36) {
            player.closeInventory();
            return;
        }

        OfflinePlayer target = holder.getTarget();
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        boolean isHistoryShortcut;
        if (item.getType() == Material.GREEN_CANDLE && slot == 7) {
            isHistoryShortcut = true;
        } else if (item.getType() == Material.ORANGE_CANDLE && slot == 7) {
            isHistoryShortcut = true;
        } else {
            isHistoryShortcut = false;
        }
        if (isHistoryShortcut) {
            player.closeInventory();
            player.performCommand("history " + targetName);
            return;
        }

        if (slot == 44) {
            PunishGuiManager.PunishMode currentMode = holder.getMode();
            PunishGuiManager.PunishMode nextMode = switch (currentMode) {
                case BANS -> PunishGuiManager.PunishMode.MUTES;
                case MUTES -> PunishGuiManager.PunishMode.WARNS;
                case WARNS -> PunishGuiManager.PunishMode.BANS;
            };
            punishGuiManager.openPunishMenu(player, target, nextMode);
            return;
        }

        if (item.getItemMeta() == null || item.getItemMeta().displayName() == null) return;
        
        String plainName = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName());
        
        var templateService = plugin.getPunishmentService().getTemplateService();
        var templateOpt = templateService.findTemplate(plainName);
        
        if (templateOpt.isEmpty()) {
            return;
        }
        
        var template = templateOpt.get();
        
        if (template.getPermission() != null && !player.hasPermission(template.getPermission())) {
            player.sendMessage(plugin.getLocaleManager().getMessage("gui.punish.no-permission-action"));
            return;
        }

        player.closeInventory();
        
        plugin.getPunishmentService().applyTemplatePunishment(targetName, template, null, player.getUniqueId(), player.getName(), false)
            .whenComplete((unused, throwable) -> {
                if (throwable != null) {
                    plugin.getSchedulerAdapter().runTask(() -> {
                        player.sendMessage(net.kyori.adventure.text.Component.text("Error applying punishment: " + throwable.getMessage(), net.kyori.adventure.text.format.NamedTextColor.RED));
                    });
                } else {
                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<green>Applied template </green>" + template.getDisplayName() + "<green> to " + targetName + "</green>"));
                }
            });
    }

    private void handleHistoryClick(InventoryClickEvent event, Player player, HistoryGuiManager.HistoryHolder holder) {
        int currentPage = holder.getPage();
        OfflinePlayer target = holder.getTarget();

        if (event.getSlot() == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            historyGuiManager.openHistoryGui(player, target, currentPage - 1);
        } else if (event.getSlot() == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            historyGuiManager.openHistoryGui(player, target, currentPage + 1);
        }
    }

    private void handleAltsClick(InventoryClickEvent event, Player player, AltsGuiManager.AltsHolder holder) {
        int currentPage = holder.getPage();
        OfflinePlayer target = holder.getTarget();

        if (event.getSlot() == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            altsGuiManager.openAltsGui(player, target, currentPage - 1);
        } else if (event.getSlot() == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            altsGuiManager.openAltsGui(player, target, currentPage + 1);
        }
    }

    private void handleMyReportsClick(InventoryClickEvent event, Player player, MyReportsGuiManager.MyReportsHolder holder) {
        int currentPage = holder.getPage();

        if (event.getSlot() == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            myReportsGuiManager.openMyReportsGui(player, currentPage - 1);
        } else if (event.getSlot() == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            myReportsGuiManager.openMyReportsGui(player, currentPage + 1);
        }
    }
}
