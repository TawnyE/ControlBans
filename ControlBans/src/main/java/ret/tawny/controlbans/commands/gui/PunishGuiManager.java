package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.services.EscalationService;
import ret.tawny.controlbans.ui.PunishUi;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Inventory fallback for the /punish flow (also used on servers without the
 * Paper 1.21.6+ dialog API). Two screens: category select, then a paginated
 * preset list (45 presets per page).
 */
public class PunishGuiManager implements PunishUi {

    public enum Screen { CATEGORY, PRESETS }

    public static class PunishHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        private final Screen screen;
        private final EscalationService.Category category;
        private final int page;
        private final String[] presetIds; // slot -> preset id (PRESETS screen only)
        private final UUID viewerUuid;

        public PunishHolder(OfflinePlayer target, Screen screen, EscalationService.Category category, int page, String[] presetIds, UUID viewerUuid) {
            this.target = target;
            this.screen = screen;
            this.category = category;
            this.page = page;
            this.presetIds = presetIds;
            this.viewerUuid = viewerUuid;
        }

        public OfflinePlayer getTarget() { return target; }
        public Screen getScreen() { return screen; }
        public EscalationService.Category getCategory() { return category; }
        public int getPage() { return page; }
        public String getPresetIdAt(int slot) { return presetIds != null && slot >= 0 && slot < presetIds.length ? presetIds[slot] : null; }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }

        @Override
        public MenuType getMenuType() { return MenuType.PUNISH; }
    }

    public static final int PRESETS_PER_PAGE = 45;
    public static final int SLOT_PREV = 45;
    public static final int SLOT_BACK = 48;
    public static final int SLOT_CLOSE = 49;
    public static final int SLOT_NEXT = 53;

    private final ControlBansPlugin plugin;

    public PunishGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void openCategorySelect(Player staff, OfflinePlayer target) {
        String targetName = target.getName() != null ? target.getName() : "Unknown";
        Component title = plugin.getLocaleManager().getMessage("gui.punish-dialog.category-title",
                Placeholder.unparsed("player", escapeTags(targetName)));

        plugin.getSchedulerAdapter().runTaskForPlayer(staff, () -> {
            Inventory inv = Bukkit.createInventory(
                    new PunishHolder(target, Screen.CATEGORY, null, 1, null, staff.getUniqueId()), 27, title);

            inv.setItem(11, createCategoryItem(target, "category-text", Material.BOOK));
            inv.setItem(13, createCategoryItem(target, "category-both", Material.COMPASS));
            inv.setItem(15, createCategoryItem(target, "category-voice", Material.NOTE_BLOCK));
            inv.setItem(22, createCloseItem());
            fill(inv, 27, Material.BLACK_STAINED_GLASS_PANE);

            staff.openInventory(inv);
        });
    }

    @Override
    public void openPresetSelect(Player staff, OfflinePlayer target, EscalationService.Category category) {
        openPresetSelect(staff, target, category, 1);
    }

    public void openPresetSelect(Player staff, OfflinePlayer target, EscalationService.Category category, int page) {
        String targetName = target.getName() != null ? target.getName() : "Unknown";
        Component title = plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-title",
                Placeholder.unparsed("player", escapeTags(targetName)));

        List<EscalationService.Preset> presets = plugin.getEscalationService().getPresets().stream()
                .filter(preset -> preset.targets().contains(category))
                .toList();

        if (presets.isEmpty()) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("gui.punish-dialog.no-presets"));
            return;
        }

        int maxPage = (presets.size() + PRESETS_PER_PAGE - 1) / PRESETS_PER_PAGE;
        final int safePage = Math.max(1, Math.min(page, maxPage));
        int start = (safePage - 1) * PRESETS_PER_PAGE;

        // Async-safe: compute pure item data first, open the inventory on the right thread.
        String[] presetIds = new String[54];
        List<ItemStack> presetItems = new ArrayList<>();
        for (int i = 0; i < PRESETS_PER_PAGE && start + i < presets.size(); i++) {
            EscalationService.Preset preset = presets.get(start + i);
            presetIds[i] = preset.id();
            presetItems.add(createPresetItem(staff, preset));
        }

        final boolean hasPrev = safePage > 1;
        final boolean hasNext = safePage < maxPage;

        plugin.getSchedulerAdapter().runTaskForPlayer(staff, () -> {
            Inventory inv = Bukkit.createInventory(
                    new PunishHolder(target, Screen.PRESETS, category, safePage, presetIds, staff.getUniqueId()), 54, title);

            for (int i = 0; i < presetItems.size(); i++) {
                inv.setItem(i, presetItems.get(i));
            }
            if (hasPrev) inv.setItem(SLOT_PREV, createNavArrow("<yellow>◀ Previous Page"));
            if (hasNext) inv.setItem(SLOT_NEXT, createNavArrow("<yellow>Next Page ▶"));
            inv.setItem(SLOT_BACK, createNavArrow("<aqua>◀ Back to Categories"));
            inv.setItem(SLOT_CLOSE, createCloseItem());
            fill(inv, 54, Material.BLACK_STAINED_GLASS_PANE);

            staff.openInventory(inv);
        });
    }

    private ItemStack createCategoryItem(OfflinePlayer target, String key, Material material) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(plugin.getLocaleManager().getMessage("gui.punish-dialog." + key).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(plugin.getLocaleManager().getMessage("gui.punish-dialog." + key + "-tooltip").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-click-hint").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createPresetItem(Player staff, EscalationService.Preset preset) {
        boolean hasPerm = preset.permission() == null || preset.permission().isEmpty() || staff.hasPermission(preset.permission());
        if (!hasPerm) {
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta meta = barrier.getItemMeta();
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><bold>" + preset.displayName() + "</bold></red>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<dark_gray><bold>LOCKED PRESET</bold></dark_gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<gray>\u2022 " + escapeTags(preset.description()) + "</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<red><bold>\u274C Requires permission:</bold></red>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<red>  " + preset.permission() + "</red>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            barrier.setItemMeta(meta);
            return barrier;
        }

        Material mat = preset.icon() != null ? preset.icon() : Material.PAPER;
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("<gold>" + preset.displayName() + "</gold>").decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        if (preset.description() != null && !preset.description().isBlank()) {
            lore.add(MiniMessage.miniMessage().deserialize("<gray>\u2022 " + escapeTags(preset.description()) + "</gray>").decoration(TextDecoration.ITALIC, false));
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (EscalationService.ActionSpec action : preset.actions()) {
            joiner.add(EscalationService.describeAction(action));
        }
        lore.add(Component.empty());
        lore.add(plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-actions-list",
                Placeholder.unparsed("actions", joiner.toString())).decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-click-hint").decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavArrow(String nameTag) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(nameTag).decoration(TextDecoration.ITALIC, false));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCloseItem() {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><bold>Close Menu</bold>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Click to exit this menu.</gray>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private void fill(Inventory inv, int size, Material glass) {
        ItemStack filler = new ItemStack(glass);
        ItemMeta meta = filler.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            filler.setItemMeta(meta);
        }
        for (int i = 0; i < size; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }
    }

    private static String escapeTags(String input) {
        if (input == null) return "Unknown";
        return input.replace("<", "\\<");
    }
}
