package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.services.ReportService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class StaffReportsGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;
    private static final int ITEMS_PER_PAGE = 45;

    public enum Filter {
        ALL, PENDING, RESOLVED, REJECTED;

        public Filter next() {
            return values()[(ordinal() + 1) % values().length];
        }

        public boolean matches(String status) {
            if (this == ALL) return true;
            return name().equalsIgnoreCase(ReportService.displayStatus(status));
        }
    }

    public StaffReportsGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    // ------------------------------------------------------------------
    // Holders
    // ------------------------------------------------------------------

    public static class StaffReportsHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final int page;
        private final Filter filter;
        private final Map<Integer, String> slotReportIds = new HashMap<>();

        public StaffReportsHolder(UUID viewerUuid, int page, Filter filter) {
            this.viewerUuid = viewerUuid;
            this.page = page;
            this.filter = filter;
        }

        public int getPage() { return page; }
        public Filter getFilter() { return filter; }
        public void setReportAt(int slot, String reportId) { slotReportIds.put(slot, reportId); }
        public String getReportIdAt(int slot) { return slotReportIds.get(slot); }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }
        @Override
        public MenuType getMenuType() { return MenuType.REPORTS_ADMIN_LIST; }
    }

    public static class ReportDetailHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final ReportService.Report report;
        private final Filter returnFilter;
        private final int returnPage;
        private long deleteArmedAt = -1L;

        public ReportDetailHolder(UUID viewerUuid, ReportService.Report report, Filter returnFilter, int returnPage) {
            this.viewerUuid = viewerUuid;
            this.report = report;
            this.returnFilter = returnFilter;
            this.returnPage = returnPage;
        }

        public ReportService.Report getReport() { return report; }
        public Filter getReturnFilter() { return returnFilter; }
        public int getReturnPage() { return returnPage; }
        public long getDeleteArmedAt() { return deleteArmedAt; }
        public void armDelete() { this.deleteArmedAt = System.currentTimeMillis(); }
        public void disarmDelete() { this.deleteArmedAt = -1L; }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }
        @Override
        public MenuType getMenuType() { return MenuType.REPORT_ADMIN_DETAIL; }
    }

    // ------------------------------------------------------------------
    // List view
    // ------------------------------------------------------------------

    public void openReportsGui(Player viewer, int page, Filter filter) {
        final Filter f = filter == null ? Filter.ALL : filter;
        plugin.getReportService().getReports().thenAccept(reports -> {
            List<ReportService.Report> filtered = new ArrayList<>();
            for (ReportService.Report r : reports) {
                if (f.matches(r.status())) filtered.add(r);
            }
            filtered.sort(Comparator
                    .comparingInt((ReportService.Report r) -> ReportService.STATUS_PENDING
                            .equalsIgnoreCase(ReportService.displayStatus(r.status())) ? 0 : 1)
                    .thenComparing(Comparator.comparingLong(ReportService.Report::timestamp).reversed()));

            int maxPage = Math.max(1, (int) Math.ceil((double) filtered.size() / ITEMS_PER_PAGE));
            int currentPage = Math.min(Math.max(1, page), maxPage);

            plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                Component title = locale.getMessage("gui.staff-reports.title",
                        Placeholder.unparsed("page", String.valueOf(currentPage)),
                        Placeholder.unparsed("maxpage", String.valueOf(maxPage)),
                        Placeholder.unparsed("filter", f.name()));

                StaffReportsHolder holder = new StaffReportsHolder(viewer.getUniqueId(), currentPage, f);
                Inventory inv = Bukkit.createInventory(holder, 54, title);

                int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

                for (int i = startIndex; i < endIndex; i++) {
                    int slot = i - startIndex;
                    inv.setItem(slot, createReportItem(filtered.get(i)));
                    holder.setReportAt(slot, filtered.get(i).id());
                }

                inv.setItem(45, currentPage > 1 ? createNavItem("gui.nav-previous") : createFiller());
                inv.setItem(49, createFilterItem(f));
                inv.setItem(53, currentPage < maxPage ? createNavItem("gui.nav-next") : createFiller());

                viewer.openInventory(inv);
            });
        });
    }

    private ItemStack createReportItem(ReportService.Report r) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                if (r.targetUuid() != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.targetUuid()));
                } else {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(r.targetName()));
                }
            } catch (Exception ignored) {}

            meta.displayName(locale.getMessage("gui.staff-reports.item-name",
                            Placeholder.unparsed("target", r.targetName()))
                    .decoration(TextDecoration.ITALIC, false));

            String status = ReportService.displayStatus(r.status());
            Component statusComponent = switch (status) {
                case "RESOLVED" -> locale.getMessage("gui.staff-reports.status-resolved");
                case "REJECTED" -> locale.getMessage("gui.staff-reports.status-rejected");
                default -> locale.getMessage("gui.staff-reports.status-pending");
            };

            String reason = r.reason() == null ? "" : r.reason();
            String shortReason = reason.length() > 40 ? reason.substring(0, 40) + "..." : reason;

            long elapsedSeconds = Math.max(0L, (System.currentTimeMillis() - r.timestamp()) / 1000L);

            TagResolver placeholders = TagResolver.builder()
                    .resolver(Placeholder.unparsed("reporter", r.reporterName() != null ? r.reporterName() : "Unknown"))
                    .resolver(Placeholder.unparsed("category", r.category()))
                    .resolver(Placeholder.component("status", statusComponent))
                    .resolver(Placeholder.unparsed("reason", shortReason))
                    .resolver(Placeholder.unparsed("time", TimeUtil.formatDuration(elapsedSeconds)))
                    .resolver(Placeholder.unparsed("date", TimeUtil.formatDate(r.timestamp())))
                    .resolver(Placeholder.unparsed("id", shortId(r.id())))
                    .build();

            meta.lore(locale.getMessageList("gui.staff-reports.item-lore", placeholders));
            meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            head.setItemMeta(meta);
        }
        return head;
    }

    // ------------------------------------------------------------------
    // Detail view
    // ------------------------------------------------------------------

    public void openReportDetail(Player viewer, ReportService.Report report, Filter returnFilter, int returnPage) {
        plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
            ReportDetailHolder holder = new ReportDetailHolder(viewer.getUniqueId(), report, returnFilter, returnPage);
            Component title = locale.getMessage("gui.staff-reports.detail-title",
                    Placeholder.unparsed("target", report.targetName()));
            Inventory inv = Bukkit.createInventory(holder, 36, title);

            boolean teleportEnabled = plugin.getConfigManager().isReportTeleportPlayers();

            inv.setItem(11, createPersonHead(report.targetUuid(), report.targetName(),
                    "gui.staff-reports.target-head-name",
                    teleportEnabled ? "gui.staff-reports.teleport-hint" : null));
            inv.setItem(13, createInfoBook(report));
            inv.setItem(15, createPersonHead(report.reporterUuid(), report.reporterName(),
                    "gui.staff-reports.reporter-head-name",
                    teleportEnabled ? "gui.staff-reports.teleport-hint" : null));

            inv.setItem(20, createActionItem(Material.LIME_DYE, "gui.staff-reports.action-resolve"));
            inv.setItem(22, createActionItem(Material.ORANGE_DYE, "gui.staff-reports.action-reopen"));
            inv.setItem(24, createActionItem(Material.RED_DYE, "gui.staff-reports.action-reject"));
            inv.setItem(26, createActionItem(Material.BARRIER, "gui.staff-reports.action-delete"));
            inv.setItem(18, createNavItem("gui.staff-reports.back"));

            viewer.openInventory(inv);
        });
    }

    private ItemStack createPersonHead(UUID uuid, String name, String nameKey, String hintKey) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                if (uuid != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(uuid));
                } else if (name != null) {
                    meta.setOwningPlayer(Bukkit.getOfflinePlayer(name));
                }
            } catch (Exception ignored) {}

            String display = name != null ? name : "Unknown";
            meta.displayName(locale.getMessage(nameKey, Placeholder.unparsed("player", display))
                    .decoration(TextDecoration.ITALIC, false));
            if (hintKey != null) {
                meta.lore(locale.getMessageList(hintKey, Placeholder.unparsed("player", display)));
            }
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createInfoBook(ReportService.Report report) {
        ItemStack book = new ItemStack(Material.BOOK);
        ItemMeta meta = book.getItemMeta();
        meta.displayName(locale.getMessage("gui.staff-reports.info-name").decoration(TextDecoration.ITALIC, false));

        String status = ReportService.displayStatus(report.status());

        List<Component> lore = new ArrayList<>();
        lore.add(locale.getMessage("gui.staff-reports.info-id",
                Placeholder.unparsed("id", shortId(report.id()))));

        String reason = report.reason() == null ? "" : report.reason();
        if (reason.isEmpty()) {
            lore.add(locale.getMessage("gui.staff-reports.info-reason-line", Placeholder.unparsed("reason", "-")));
        } else {
            for (String line : wrap(reason, 30)) {
                lore.add(locale.getMessage("gui.staff-reports.info-reason-line", Placeholder.unparsed("reason", line)));
            }
        }
        lore.add(locale.getMessage("gui.staff-reports.info-category",
                Placeholder.unparsed("category", report.category())));
        lore.add(locale.getMessage("gui.staff-reports.info-date",
                Placeholder.unparsed("date", TimeUtil.formatDate(report.timestamp()))));
        lore.add(locale.getMessage("gui.staff-reports.info-status",
                Placeholder.unparsed("status", status)));

        meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        book.setItemMeta(meta);
        return book;
    }

    private static List<String> wrap(String text, int width) {
        List<String> lines = new ArrayList<>();
        for (String paragraph : text.split("\n")) {
            String remaining = paragraph;
            while (remaining.length() > width) {
                int cut = remaining.lastIndexOf(' ', width);
                if (cut <= 0) cut = width;
                lines.add(remaining.substring(0, cut));
                remaining = remaining.substring(cut).trim();
            }
            if (!remaining.isEmpty() || lines.isEmpty()) lines.add(remaining);
        }
        return lines;
    }

    // ------------------------------------------------------------------
    // Click handlers (invoked from GuiListener)
    // ------------------------------------------------------------------

    public void handleListClick(Player player, StaffReportsHolder holder, int slot, ItemStack item) {
        if (item == null) return;
        if (slot == 45 && item.getType() == Material.ARROW) {
            openReportsGui(player, holder.getPage() - 1, holder.getFilter());
            return;
        }
        if (slot == 53 && item.getType() == Material.ARROW) {
            openReportsGui(player, holder.getPage() + 1, holder.getFilter());
            return;
        }
        if (slot == 49) {
            openReportsGui(player, 1, holder.getFilter().next());
            return;
        }
        if (slot >= 0 && slot < 45 && item.getType() == Material.PLAYER_HEAD) {
            String reportId = holder.getReportIdAt(slot);
            if (reportId == null) return;
            plugin.getReportService().getReport(reportId).thenAccept(opt ->
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                        if (opt.isEmpty()) {
                            player.sendMessage(locale.getMessage("gui.staff-reports.report-not-found"));
                            openReportsGui(player, holder.getPage(), holder.getFilter());
                            return;
                        }
                        openReportDetail(player, opt.get(), holder.getFilter(), holder.getPage());
                    }));
        }
    }

    public void handleDetailClick(Player player, ReportDetailHolder holder, int slot, ItemStack item) {
        if (item == null) return;
        ReportService.Report report = holder.getReport();

        switch (slot) {
            case 18 -> openReportsGui(player, holder.getReturnPage(), holder.getReturnFilter());
            case 11 -> tryTeleport(player, report.targetName());
            case 15 -> tryTeleport(player, report.reporterName());
            case 20 -> changeStatus(player, holder, ReportService.STATUS_RESOLVED);
            case 22 -> changeStatus(player, holder, ReportService.STATUS_PENDING);
            case 24 -> changeStatus(player, holder, ReportService.STATUS_REJECTED);
            case 26 -> handleDelete(player, holder);
            default -> { }
        }
    }

    private void changeStatus(Player player, ReportDetailHolder holder, String newStatus) {
        ReportService.Report report = holder.getReport();
        plugin.getReportService().updateReportStatus(report.id(), newStatus).thenAccept(success ->
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                    if (!success) {
                        player.sendMessage(locale.getMessage("gui.staff-reports.report-not-found"));
                        openReportsGui(player, holder.getReturnPage(), holder.getReturnFilter());
                        return;
                    }
                    player.sendMessage(locale.getMessage("reports.resolve-success",
                            Placeholder.unparsed("id", shortId(report.id())),
                            Placeholder.unparsed("type", newStatus)));
                    plugin.getReportService().getReport(report.id()).thenAccept(opt ->
                            plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                                    opt.ifPresent(updated ->
                                            openReportDetail(player, updated, holder.getReturnFilter(), holder.getReturnPage()))));
                }));
    }

    private void handleDelete(Player player, ReportDetailHolder holder) {
        long armedAt = holder.getDeleteArmedAt();
        if (armedAt < 0 || System.currentTimeMillis() - armedAt > 5000L) {
            holder.armDelete();
            Inventory inv = player.getOpenInventory().getTopInventory();
            inv.setItem(26, createActionItem(Material.REDSTONE_BLOCK, "gui.staff-reports.action-delete-confirm"));
            return;
        }

        String id = holder.getReport().id();
        int returnPage = holder.getReturnPage();
        Filter returnFilter = holder.getReturnFilter();
        plugin.getReportService().deleteReport(id).thenAccept(success ->
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                    if (success) {
                        player.sendMessage(locale.getMessage("gui.staff-reports.deleted",
                                Placeholder.unparsed("id", shortId(id))));
                    } else {
                        player.sendMessage(locale.getMessage("gui.staff-reports.report-not-found"));
                    }
                    openReportsGui(player, returnPage, returnFilter);
                }));
    }

    private void tryTeleport(Player staff, String targetName) {
        if (!plugin.getConfigManager().isReportTeleportPlayers()) return;
        if (targetName == null) return;
        Player target = Bukkit.getPlayer(targetName);
        if (target == null) {
            staff.sendMessage(locale.getMessage("gui.staff-reports.player-not-online",
                    Placeholder.unparsed("player", targetName)));
            return;
        }
        staff.teleportAsync(target.getLocation()).thenAccept(success ->
                plugin.getSchedulerAdapter().runTaskForPlayer(staff, () -> {
                    if (Boolean.TRUE.equals(success)) {
                        staff.sendMessage(locale.getMessage("gui.staff-reports.teleported",
                                Placeholder.unparsed("player", targetName)));
                    }
                }));
    }

    // ------------------------------------------------------------------
    // Shared item helpers
    // ------------------------------------------------------------------

    private ItemStack createActionItem(Material material, String nameKey) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFilterItem(Filter filter) {
        ItemStack item = new ItemStack(Material.HOPPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage("gui.staff-reports.filter-name").decoration(TextDecoration.ITALIC, false));
        meta.lore(locale.getMessageList("gui.staff-reports.filter-lore",
                Placeholder.unparsed("filter", filter.name())));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(String localeKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(localeKey).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }

    private static String shortId(String id) {
        return id != null ? id.substring(0, Math.min(id.length(), 8)) : "unknown";
    }
}
