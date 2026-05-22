package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.services.ReportService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class MyReportsGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;
    private static final int ITEMS_PER_PAGE = 45;

    public MyReportsGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    public static class MyReportsHolder extends ControlBansHolder {
        private final Player owner;
        private final int page;
        public MyReportsHolder(Player owner, int page) { this.owner = owner; this.page = page; }
        public Player getOwner() { return owner; }
        public int getPage() { return page; }
    }

    public void openMyReportsGui(Player viewer, int page) {
        int startPage = Math.max(1, page);
        plugin.getReportService().getReportsByReporter(viewer.getUniqueId()).thenAccept(reports -> {
            final int maxPage = Math.max(1, (int) Math.ceil((double) reports.size() / ITEMS_PER_PAGE));
            final int currentPage = startPage > maxPage ? maxPage : startPage;

            plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                Component title = locale.getMessage("gui.my-reports.title",
                        Placeholder.unparsed("page", String.valueOf(currentPage)),
                        Placeholder.unparsed("maxpage", String.valueOf(maxPage)));

                Inventory inv = Bukkit.createInventory(new MyReportsHolder(viewer, currentPage), 54, title);

                int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, reports.size());

                for (int i = startIndex; i < endIndex; i++) {
                    inv.setItem(i - startIndex, createReportItem(reports.get(i)));
                }

                inv.setItem(45, (currentPage > 1) ? createNavItem("gui.nav-previous") : createFillerGlass());
                inv.setItem(49, createSummaryItem(reports));
                inv.setItem(53, (currentPage < maxPage) ? createNavItem("gui.nav-next") : createFillerGlass());

                viewer.openInventory(inv);
            });
        });
    }

    private ItemStack createReportItem(ReportService.Report r) {
        String status = r.status().toUpperCase();
        Material material = Material.PAPER;
        if (status.equals("RESOLVED")) material = Material.EMERALD;
        else if (status.equals("REJECTED")) material = Material.REDSTONE;

        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        meta.displayName(locale.getMessage("gui.my-reports.item-name",
                Placeholder.unparsed("target", r.targetName())));

        Component statusComponent = switch (status) {
            case "RESOLVED" -> locale.getMessage("gui.my-reports.status-resolved");
            case "REJECTED" -> locale.getMessage("gui.my-reports.status-rejected");
            default -> locale.getMessage("gui.my-reports.status-pending");
        };

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("reason", r.reason()))
                .resolver(Placeholder.unparsed("date", TimeUtil.formatDate(r.timestamp())))
                .resolver(Placeholder.component("status", statusComponent))
                .resolver(Placeholder.unparsed("id", r.id() != null ? r.id().substring(0, Math.min(r.id().length(), 8)) : "unknown"))
                .build();

        meta.lore(locale.getMessageList("gui.my-reports.item-lore", placeholders));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createSummaryItem(List<ReportService.Report> history) {
        ItemStack item = new ItemStack(Material.BOOK);
        ItemMeta meta = item.getItemMeta();
        
        meta.displayName(locale.getMessage("gui.my-reports.summary-title"));
        
        long resolved = history.stream().filter(r -> r.status().equalsIgnoreCase("RESOLVED")).count();
        long pending = history.stream().filter(r -> r.status().equalsIgnoreCase("PENDING")).count();
        
        List<Component> lore = new ArrayList<>();
        lore.add(locale.getMessage("gui.my-reports.summary-total", Placeholder.unparsed("count", String.valueOf(history.size()))));
        lore.add(locale.getMessage("gui.my-reports.summary-resolved", Placeholder.unparsed("count", String.valueOf(resolved))));
        lore.add(locale.getMessage("gui.my-reports.summary-pending", Placeholder.unparsed("count", String.valueOf(pending))));
        
        meta.lore(lore);
        item.setItemMeta(meta);
        
        return item;
    }

    private ItemStack createNavItem(String localeKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(localeKey));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFillerGlass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }
}
