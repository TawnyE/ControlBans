package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
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
import ret.tawny.controlbans.config.ReportCategory;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.menus.ControlBansHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ReportGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    /** Inventory slot reserved for the "Write your own reason" item. Sentinel category id. */
    public static final String CUSTOM_REASON_ID = "__custom__";

    public ReportGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    public static class ReportHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        private final UUID viewerUuid;
        private final Map<Integer, String> slotCategories = new HashMap<>();
        public ReportHolder(UUID viewerUuid, OfflinePlayer target) { this.target = target; this.viewerUuid = viewerUuid; }
        public OfflinePlayer getTarget() { return target; }
        public void setSlotCategory(int slot, String categoryId) { slotCategories.put(slot, categoryId); }
        public String getCategoryAt(int slot) { return slotCategories.get(slot); }
        @Override
        public UUID getViewerUuid() { return viewerUuid; }
        @Override
        public MenuType getMenuType() { return MenuType.REPORTS; }
    }

    public void openReportMenu(Player reporter, OfflinePlayer target) {
        plugin.getSchedulerAdapter().runTaskForPlayer(reporter, () -> {
            Component title = locale.getMessage("gui.report-creator.title",
                    Placeholder.unparsed("player", target.getName() != null ? target.getName() : "Unknown"));

            ReportHolder holder = new ReportHolder(reporter.getUniqueId(), target);
            Inventory inv = Bukkit.createInventory(holder, 54, title);

            inv.setItem(4, createHeadInfo(target));

            List<ReportCategory> categories = plugin.getConfigManager().getReportCategories();
            int slot = 19;
            for (ReportCategory category : categories) {
                if (slot > 43) break;
                inv.setItem(slot, createCategoryItem(category));
                holder.setSlotCategory(slot, category.id());
                slot++;
            }

            if (plugin.getConfigManager().isReportCustomReasonAllowed()) {
                inv.setItem(49, createCustomReasonItem());
                holder.setSlotCategory(49, CUSTOM_REASON_ID);
            }

            ItemStack filler = createFiller();
            for (int i = 0; i < 54; i++) {
                if (inv.getItem(i) == null) inv.setItem(i, filler);
            }

            reporter.openInventory(inv);
        });
    }

    /**
     * Called by GuiListener when a category item is clicked.
     */
    public void handleCategoryClick(Player reporter, ReportHolder holder, int slot) {
        String categoryId = holder.getCategoryAt(slot);
        if (categoryId == null) return;

        OfflinePlayer target = holder.getTarget();
        if (target == null) return;

        if (target.getUniqueId().equals(reporter.getUniqueId())) {
            reporter.closeInventory();
            reporter.sendMessage(locale.getMessage("report.self-report"));
            return;
        }

        String targetName = target.getName() != null ? target.getName() : "Unknown";
        UUID targetUuid = target.getUniqueId();

        if (CUSTOM_REASON_ID.equals(categoryId)) {
            // Free-text reason, stored under the "custom" category.
            reporter.closeInventory();
            promptCustomReason(reporter, targetName, targetUuid, "custom");
            return;
        }

        ReportCategory category = findCategory(categoryId);
        if (category == null) {
            return;
        }

        if (plugin.getConfigManager().isReportCustomReasonAllowed() && "other".equalsIgnoreCase(category.id())) {
            // "Other" always prompts for a free-text description.
            reporter.closeInventory();
            promptCustomReason(reporter, targetName, targetUuid, category.id());
            return;
        }

        reporter.closeInventory();
        String reason = plainText(category.name());
        submit(reporter, targetName, targetUuid, reason, category.id());
    }

    private void promptCustomReason(Player reporter, String targetName, UUID targetUuid, String categoryId) {
        reporter.sendMessage(locale.getMessage("gui.report-creator.prompt-chat-message",
                Placeholder.unparsed("player", targetName)));
        plugin.getChatInputListener().awaitInput(reporter, input -> {
            // Note: async chat thread.
            if (input == null || input.isBlank() || input.equalsIgnoreCase("cancel")) {
                reporter.sendMessage(locale.getMessage("gui.report-creator.cancelled"));
                return;
            }
            submit(reporter, targetName, targetUuid, input.trim(), categoryId);
        });
    }

    private void submit(Player reporter, String targetName, UUID targetUuid, String reason, String categoryId) {
        plugin.getReportService().submitReport(
                reporter.getUniqueId(),
                reporter.getName(),
                targetName,
                targetUuid,
                reason,
                categoryId
        ).whenComplete((submitted, throwable) -> {
            if (throwable != null) {
                plugin.getLogger().warning("Failed to submit report: " + throwable.getMessage());
            }
        });
    }

    private ReportCategory findCategory(String id) {
        for (ReportCategory c : plugin.getConfigManager().getReportCategories()) {
            if (c.id().equalsIgnoreCase(id)) return c;
        }
        return null;
    }

    private String plainText(String input) {
        try {
            return PlainTextComponentSerializer.plainText().serialize(miniMessage.deserialize(input));
        } catch (Exception e) {
            return input.replaceAll("<[^>]+>", "");
        }
    }

    private ItemStack createCategoryItem(ReportCategory category) {
        ItemStack item = new ItemStack(category.icon());
        ItemMeta meta = item.getItemMeta();
        meta.displayName(miniMessage.deserialize(category.name()).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        for (String line : category.lore()) {
            lore.add(miniMessage.deserialize(line).decoration(TextDecoration.ITALIC, false));
        }
        lore.add(Component.empty());
        lore.addAll(locale.getMessageList("gui.report-creator.click-to-select"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createCustomReasonItem() {
        ItemStack item = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage("gui.report-creator.custom-reason-name").decoration(TextDecoration.ITALIC, false));
        meta.lore(locale.getMessageList("gui.report-creator.custom-reason-lore"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createHeadInfo(OfflinePlayer target) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try {
                meta.setOwningPlayer(target);
            } catch (Exception ignored) {}

            meta.displayName(locale.getMessage("gui.report.head.name",
                            Placeholder.unparsed("player", target.getName() != null ? target.getName() : "Unknown"))
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(locale.getMessageList("gui.report.head.lore"));
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }
}
