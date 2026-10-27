package ret.tawny.controlbans.listeners;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
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
import ret.tawny.controlbans.commands.gui.*;
import ret.tawny.controlbans.menus.ControlBansHolder;

import java.util.UUID;

public class GuiListener implements Listener {

    private final ControlBansPlugin plugin;
    private final HistoryGuiManager historyGuiManager;
    private final AltsGuiManager altsGuiManager;
    private final SettingsGuiManager settingsGuiManager;
    private final PunishGuiManager punishGuiManager;
    private final ReportGuiManager reportGuiManager;
    private final MyReportsGuiManager myReportsGuiManager;
    private final MainMenuGuiManager mainMenuGuiManager;
    private final OnlinePlayersGuiManager onlinePlayersGuiManager;
    private final StaffHistoryGuiManager staffHistoryGuiManager;
    private final StaffReportsGuiManager staffReportsGuiManager;
    private final AppealsGuiManager appealsGuiManager;

    public GuiListener(
            HistoryGuiManager historyGuiManager,
            AltsGuiManager altsGuiManager,
            SettingsGuiManager settingsGuiManager,
            PunishGuiManager punishGuiManager,
            ReportGuiManager reportGuiManager,
            MyReportsGuiManager myReportsGuiManager,
            MainMenuGuiManager mainMenuGuiManager,
            OnlinePlayersGuiManager onlinePlayersGuiManager,
            StaffHistoryGuiManager staffHistoryGuiManager,
            StaffReportsGuiManager staffReportsGuiManager,
            AppealsGuiManager appealsGuiManager,
            ControlBansPlugin plugin
    ) {
        this.historyGuiManager = historyGuiManager;
        this.altsGuiManager = altsGuiManager;
        this.settingsGuiManager = settingsGuiManager;
        this.punishGuiManager = punishGuiManager;
        this.reportGuiManager = reportGuiManager;
        this.myReportsGuiManager = myReportsGuiManager;
        this.mainMenuGuiManager = mainMenuGuiManager;
        this.onlinePlayersGuiManager = onlinePlayersGuiManager;
        this.staffHistoryGuiManager = staffHistoryGuiManager;
        this.staffReportsGuiManager = staffReportsGuiManager;
        this.appealsGuiManager = appealsGuiManager;
        this.plugin = plugin;
    }

    private final java.util.Set<UUID> punishClickLock = java.util.concurrent.ConcurrentHashMap.newKeySet();

    @EventHandler
    public void onInventoryDrag(org.bukkit.event.inventory.InventoryDragEvent event) {
        if (!(event.getWhoClicked() instanceof Player)) return;
        if (event.getInventory() == null || !(event.getInventory().getHolder() instanceof ControlBansHolder)) return;
        event.setCancelled(true);
    }

    @EventHandler
    public void onInventoryClose(org.bukkit.event.inventory.InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;
        if (event.getInventory() == null || !(event.getInventory().getHolder() instanceof ControlBansHolder)) return;
        punishClickLock.remove(player.getUniqueId());
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }

        Inventory inventory = event.getInventory();
        InventoryHolder holder = inventory.getHolder();
        if (!(holder instanceof ControlBansHolder cbHolder)) {
            return;
        }

        event.setCancelled(true);

        // Defense-in-depth: only the viewer may click their GUI.
        try {
            UUID viewer = cbHolder.getViewerUuid();
            if (viewer != null && !viewer.equals(player.getUniqueId())) return;
        } catch (Exception ignored) {}

        // Clicks in the player's own inventory while a GUI is open are cancelled above, but never handled.
        if (event.getClickedInventory() == null || !event.getClickedInventory().equals(event.getView().getTopInventory())) {
            return;
        }

        ItemStack item = event.getCurrentItem();
        if (item == null || item.getType() == Material.AIR) {
            return;
        }

        switch (((ControlBansHolder) holder).getMenuType()) {
            case MAIN_HUB -> handleMainHubClick(player, item, event.getRawSlot());
            case ONLINE_PLAYERS -> handleOnlinePlayersClick(event, player, (OnlinePlayersGuiManager.OnlinePlayersHolder) holder);
            case PUNISH -> handlePunishClick(event, player, (PunishGuiManager.PunishHolder) holder);
            case HISTORY -> handleHistoryClick(event, player, (HistoryGuiManager.HistoryHolder) holder);
            case STAFF_HISTORY -> handleStaffHistoryClick(event, player, (StaffHistoryGuiManager.StaffHistoryHolder) holder);
            case SETTINGS -> handleSettingsClick(player, (SettingsGuiManager.SettingsHolder) holder, item);
            case REPORTS -> {
                if (holder instanceof ReportGuiManager.ReportHolder reportHolder) {
                    handleReportClick(event, player, reportHolder, item);
                } else if (holder instanceof MyReportsGuiManager.MyReportsHolder myReportsHolder) {
                    handleMyReportsClick(event, player, myReportsHolder);
                }
            }
            case REPORTS_ADMIN_LIST -> staffReportsGuiManager.handleListClick(player, (StaffReportsGuiManager.StaffReportsHolder) holder, event.getRawSlot(), item);
            case REPORT_ADMIN_DETAIL -> staffReportsGuiManager.handleDetailClick(player, (StaffReportsGuiManager.ReportDetailHolder) holder, event.getRawSlot(), item);
            case APPEALS_LIST -> appealsGuiManager.handleListClick(player, (AppealsGuiManager.AppealsListHolder) holder, event.getRawSlot(), item);
            case APPEAL_DETAIL -> appealsGuiManager.handleDetailClick(player, (AppealsGuiManager.AppealDetailHolder) holder, event.getRawSlot(), item);
            case ALTS -> handleAltsClick(event, player, (AltsGuiManager.AltsHolder) holder);
        }
    }

    private void handleMyReportsClick(InventoryClickEvent event, Player player, MyReportsGuiManager.MyReportsHolder holder) {
        int slot = event.getRawSlot();
        if (slot == 45 && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW && holder.getPage() > 1) {
            myReportsGuiManager.openMyReportsGui(player, holder.getPage() - 1);
        } else if (slot == 53 && event.getCurrentItem() != null && event.getCurrentItem().getType() == Material.ARROW) {
            myReportsGuiManager.openMyReportsGui(player, holder.getPage() + 1);
        }
    }

    private void handleAltsClick(InventoryClickEvent event, Player player, AltsGuiManager.AltsHolder holder) {
        int slot = event.getRawSlot();
        if (slot == 45 && holder.getPage() > 1) {
            altsGuiManager.openAltsGui(player, holder.getTarget(), holder.getPage() - 1);
        } else if (slot == 53 && holder.getPage() < holder.getMaxPage()) {
            altsGuiManager.openAltsGui(player, holder.getTarget(), holder.getPage() + 1);
        }
    }

    private void handleMainHubClick(Player player, ItemStack item, int slot) {
        switch (slot) {
            case 11 -> {
                if (player.hasPermission("controlbans.punish")) {
                    onlinePlayersGuiManager.openOnlinePlayers(player);
                } else {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                }
            }
            case 12 -> {
                if (!player.hasPermission("controlbans.history")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                    return;
                }
                player.closeInventory();
                player.sendMessage(plugin.getLocaleManager().getMessage("gui.history.prompt-name"));
                plugin.getChatInputListener().awaitInput(player, input -> {
                    plugin.getPunishmentService().getPlayerResolver().getOfflineTarget(input).whenComplete((target, ex) -> {
                        plugin.getSchedulerAdapter().runTask(() -> {
                            if (target == null) {
                                player.sendMessage(plugin.getLocaleManager().getMessage("errors.player-not-found"));
                                return;
                            }
                            historyGuiManager.openHistoryGui(player, target);
                        });
                    });
                });
            }
            case 14 -> {
                if (!player.hasPermission("controlbans.blame") && !player.hasPermission("controlbans.history") && !player.hasPermission("controlbans.staff")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                    return;
                }
                staffHistoryGuiManager.openStaffSelector(player);
            }
            case 15 -> {
                // Global reports / report management: same node used by /reports for managing reports.
                if (player.hasPermission("controlbans.report.resolve")) {
                    onlinePlayersGuiManager.openReportPicker(player, 1);
                } else {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                }
            }
            case 22 -> {
                if (player.hasPermission("controlbans.admin")) {
                    settingsGuiManager.openSettingsGui(player);
                } else {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                }
            }
        }
    }

    private void handleOnlinePlayersClick(InventoryClickEvent event, Player player, OnlinePlayersGuiManager.OnlinePlayersHolder holder) {
        int slot = event.getRawSlot();
        if (slot == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            onlinePlayersGuiManager.openOnlinePlayers(player, holder.getPage() - 1);
            return;
        }
        if (slot == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            onlinePlayersGuiManager.openOnlinePlayers(player, holder.getPage() + 1);
            return;
        }

        if (slot >= 0 && slot < 36 && event.getCurrentItem().getType() == Material.PLAYER_HEAD) {
            UUID targetUuid = holder.getTargetUuid(slot);
            if (targetUuid == null) {
                return;
            }
            OfflinePlayer target = Bukkit.getOfflinePlayer(targetUuid);

            if (holder.isReporting()) {
                if (event.isLeftClick()) {
                    reportGuiManager.openReportMenu(player, target);
                } else if (event.isRightClick()) {
                    if (!player.hasPermission("controlbans.history")) {
                        player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                        return;
                    }
                    historyGuiManager.openHistoryGui(player, target);
                }
                return;
            }

            if (event.isLeftClick()) {
                if (!player.hasPermission("controlbans.punish")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                    return;
                }
                plugin.getPunishUiManager().open(player, target);
            } else if (event.isRightClick()) {
                if (!player.hasPermission("controlbans.history")) {
                    player.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
                    return;
                }
                historyGuiManager.openHistoryGui(player, target);
            }
        }
    }

    private void handlePunishClick(InventoryClickEvent event, Player player, PunishGuiManager.PunishHolder holder) {
        ItemStack item = event.getCurrentItem();
        int slot = event.getRawSlot();

        if (item == null) return;

        // Generic filler check: any stained glass with blank name is filler
        if (item.getType().name().endsWith("_STAINED_GLASS_PANE")) {
            if (item.getItemMeta() != null && item.getItemMeta().displayName() != null) {
                String plain = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(item.getItemMeta().displayName()).trim();
                if (plain.isEmpty()) return;
            }
            return;
        }

        if (item.getType() == Material.BARRIER) {
            if (slot == 22 || slot == 49) {
                player.closeInventory();
            }
            // BARRIER on a preset slot = permission-locked preset
            return;
        }

        switch (holder.getScreen()) {
            case CATEGORY -> handleCategoryClick(player, holder, slot);
            case PRESETS -> handlePresetListClick(event, player, holder, item, slot);
        }
    }

    private void handleCategoryClick(Player player, PunishGuiManager.PunishHolder holder, int slot) {
        OfflinePlayer target = holder.getTarget();
        switch (slot) {
            case 11 -> punishGuiManager.openPresetSelect(player, target, ret.tawny.controlbans.services.EscalationService.Category.TEXT);
            case 13 -> punishGuiManager.openPresetSelect(player, target, ret.tawny.controlbans.services.EscalationService.Category.BOTH);
            case 15 -> punishGuiManager.openPresetSelect(player, target, ret.tawny.controlbans.services.EscalationService.Category.VOICE);
            default -> {}
        }
    }

    private void handlePresetListClick(InventoryClickEvent event, Player player, PunishGuiManager.PunishHolder holder, ItemStack item, int slot) {
        OfflinePlayer target = holder.getTarget();

        if (slot == 45 && item.getType() == Material.ARROW && holder.getPage() > 1) {
            punishGuiManager.openPresetSelect(player, target, holder.getCategory(), holder.getPage() - 1);
            return;
        }
        if (slot == 53 && item.getType() == Material.ARROW) {
            punishGuiManager.openPresetSelect(player, target, holder.getCategory(), holder.getPage() + 1);
            return;
        }
        if (slot == 48 && item.getType() == Material.ARROW) {
            punishGuiManager.openCategorySelect(player, target);
            return;
        }
        if (slot >= PunishGuiManager.PRESETS_PER_PAGE) {
            return;
        }

        String presetId = holder.getPresetIdAt(slot);
        if (presetId == null) return;

        var presetOpt = plugin.getEscalationService().byId(presetId);
        if (presetOpt.isEmpty()) return;
        var preset = presetOpt.get();

        if (preset.permission() != null && !preset.permission().isEmpty() && !player.hasPermission(preset.permission())) {
            player.sendMessage(plugin.getLocaleManager().getMessage("gui.punish.no-permission-action"));
            return;
        }

        // Debounce double-clicks: the client can send two click events before closeInventory arrives.
        if (!punishClickLock.add(player.getUniqueId())) return;
        player.closeInventory();

        String targetName = target.getName() != null ? target.getName() : "Unknown";
        player.sendMessage(plugin.getLocaleManager().getMessage("gui.punish-dialog.notes-prompt"));

        String skipWord = plugin.getLocaleManager().getRawMessage("gui.punish-dialog.notes-skip");
        plugin.getChatInputListener().awaitInput(player, input -> {
            punishClickLock.remove(player.getUniqueId());
            String notes = null;
            if (input != null && !input.isBlank()) {
                String trimmed = input.trim();
                if (!trimmed.equalsIgnoreCase("cancel")
                        && (skipWord == null || skipWord.isBlank() || !trimmed.equalsIgnoreCase(skipWord.trim()))) {
                    notes = trimmed;
                }
            }
            plugin.getEscalationService().applyPreset(player, targetName, preset, holder.getCategory(), notes, false)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            plugin.getSchedulerAdapter().runTask(() -> {
                                Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                                String err = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
                                if (err != null && ret.tawny.controlbans.commands.CommandBase.containsMiniMessageTags(err)) {
                                    player.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize(err));
                                } else {
                                    player.sendMessage(plugin.getLocaleManager().getMessage("escalation.error", net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("error", err != null ? err : "Unknown")));
                                }
                            });
                        }
                    });
        });
    }

    private void handleHistoryClick(InventoryClickEvent event, Player player, HistoryGuiManager.HistoryHolder holder) {
        int currentPage = holder.getPage();
        OfflinePlayer target = holder.getTarget();
        HistoryGuiManager.Filter currentFilter = holder.getFilter();

        int slot = event.getRawSlot();

        if (slot == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            historyGuiManager.openHistoryGui(player, target, currentPage - 1, currentFilter);
            return;
        }
        if (slot == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            historyGuiManager.openHistoryGui(player, target, currentPage + 1, currentFilter);
            return;
        }

        int filterIndex = switch (slot) {
            case 46 -> 0;
            case 47 -> 1;
            case 48 -> 2;
            case 50 -> 3;
            case 51 -> 4;
            default -> -1;
        };
        if (filterIndex >= 0 && filterIndex < HistoryGuiManager.Filter.ALL_FILTERS.size()) {
            HistoryGuiManager.Filter newFilter = HistoryGuiManager.Filter.ALL_FILTERS.get(filterIndex);
            historyGuiManager.openHistoryGui(player, target, 1, newFilter);
        }
    }

    private void handleStaffHistoryClick(InventoryClickEvent event, Player player, StaffHistoryGuiManager.StaffHistoryHolder holder) {
        int slot = event.getRawSlot();
        String staffName = holder.getStaffName();

        if (staffName == null) {
            if (slot >= 0 && slot < 45 && event.getCurrentItem().getType() == Material.PLAYER_HEAD) {
                UUID staffUuid = holder.getStaffUuidAtSlot(slot);
                if (staffUuid != null) {
                    staffHistoryGuiManager.openStaffHistory(player, staffUuid, 1);
                }
            }
            return;
        }

        if (slot == 45 && event.getCurrentItem().getType() == Material.ARROW) {
            staffHistoryGuiManager.openStaffHistory(player, staffName, holder.getPage() - 1);
        } else if (slot == 53 && event.getCurrentItem().getType() == Material.ARROW) {
            staffHistoryGuiManager.openStaffHistory(player, staffName, holder.getPage() + 1);
        }
    }

    private void handleReportClick(InventoryClickEvent event, Player player, ReportGuiManager.ReportHolder holder, ItemStack item) {
        if (item.getType() == Material.BLACK_STAINED_GLASS_PANE || item.getType() == Material.PLAYER_HEAD) {
            return;
        }
        // Debounce: chat prompts / submissions are triggered from here.
        if (!punishClickLock.add(player.getUniqueId())) return;
        try {
            reportGuiManager.handleCategoryClick(player, holder, event.getRawSlot());
        } finally {
            punishClickLock.remove(player.getUniqueId());
        }
    }

    private void handleSettingsClick(Player player, SettingsGuiManager.SettingsHolder holder, ItemStack item) {
        settingsGuiManager.handleClick(player, item);
    }
}