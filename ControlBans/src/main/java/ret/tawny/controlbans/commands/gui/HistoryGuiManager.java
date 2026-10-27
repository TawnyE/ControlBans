package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

public class HistoryGuiManager {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final LocaleManager locale;
    private static final int ITEMS_PER_PAGE = 36;

    public HistoryGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
        this.locale = plugin.getLocaleManager();
    }

    public static class HistoryHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        private final int page;
        private final Filter filter;
        private final UUID viewerUuid;

        public HistoryHolder(OfflinePlayer target, int page, Filter filter, UUID viewerUuid) {
            this.target = target;
            this.page = page;
            this.filter = filter;
            this.viewerUuid = viewerUuid;
        }

        public OfflinePlayer getTarget() { return target; }
        public int getPage() { return page; }
        public Filter getFilter() { return filter; }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }

        @Override
        public MenuType getMenuType() { return MenuType.HISTORY; }
    }

    public record Filter(String name, String key, Material icon) {
        public static final Filter ALL = new Filter("All", "all", Material.COMPASS);
        public static final Filter ACTIVE = new Filter("Active Only", "active", Material.REDSTONE_TORCH);
        public static final Filter BANS = new Filter("Bans", "bans", Material.RED_WOOL);
        public static final Filter MUTES = new Filter("Mutes", "mutes", Material.BLUE_WOOL);
        public static final Filter WARNINGS = new Filter("Warnings", "warnings", Material.YELLOW_WOOL);

        public static final List<Filter> ALL_FILTERS = List.of(ALL, ACTIVE, BANS, MUTES, WARNINGS);
    }

    public void openHistoryGui(Player viewer, OfflinePlayer target) {
        openHistoryGui(viewer, target, 1, Filter.ALL);
    }

    public void openHistoryGui(Player viewer, OfflinePlayer target, int page) {
        openHistoryGui(viewer, target, page, Filter.ALL);
    }

    public void openHistoryGui(Player viewer, OfflinePlayer target, int page, Filter filter) {
        int startPage = Math.max(1, page);
        punishmentService.getPunishmentHistory(target.getUniqueId(), 200).thenAccept(punishments -> {
            punishments.sort(Comparator.comparingLong(Punishment::getCreatedTime).reversed());

            Predicate<Punishment> filterPredicate = switch (filter.key()) {
                case "active" -> p -> p.isActive() && !p.isExpired();
                case "bans" -> p -> p.getType().isBan();
                case "mutes" -> p -> p.getType().isMute();
                case "warnings" -> p -> p.getType() == PunishmentType.WARN;
                default -> p -> true;
            };

            List<Punishment> filtered = punishments.stream()
                    .filter(filterPredicate)
                    .toList();

            int maxPage = Math.max(1, (int) Math.ceil((double) filtered.size() / ITEMS_PER_PAGE));
            int currentPage = startPage > maxPage ? maxPage : startPage;

            List<ItemStack> items = new ArrayList<>();
            int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
            int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, filtered.size());

            for (int i = startIndex; i < endIndex; i++) {
                items.add(createPunishmentItem(filtered.get(i)));
            }

            ItemStack headItem = createPlayerHeadItem(target, filtered);

            plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                Component title = locale.getMessage("gui.history.title",
                        Placeholder.unparsed("player", target.getName() != null ? target.getName() : locale.getRawMessage("gui.history.unknown-player")),
                        Placeholder.unparsed("page", String.valueOf(currentPage)),
                        Placeholder.unparsed("maxpage", String.valueOf(maxPage)));

                Inventory inv = Bukkit.createInventory(new HistoryHolder(target, currentPage, filter, viewer.getUniqueId()), 54, title);

                for (int i = 0; i < items.size(); i++) {
                    inv.setItem(i, items.get(i));
                }

                int[] filterSlots = {46, 47, 48, 50, 51};
                for (int i = 0; i < Filter.ALL_FILTERS.size(); i++) {
                    Filter f = Filter.ALL_FILTERS.get(i);
                    boolean isSelected = f.key().equals(filter.key());
                    inv.setItem(filterSlots[i], createFilterItem(f, isSelected));
                }

                inv.setItem(45, (currentPage > 1) ? createNavItem("gui.nav-previous") : createFillerGlass());
                inv.setItem(49, headItem);
                inv.setItem(53, (currentPage < maxPage) ? createNavItem("gui.nav-next") : createFillerGlass());

                ItemStack filler = createFillerGlass();
                for (int i = 0; i < 54; i++) {
                    if (inv.getItem(i) == null) inv.setItem(i, filler);
                }

                viewer.openInventory(inv);
            });
        }).exceptionally(ex -> {
            viewer.sendMessage(locale.getMessage("errors.database-error"));
            plugin.getLogger().warning("Failed to fetch history for GUI: " + ex.getMessage());
            return null;
        });
    }

    private ItemStack createFilterItem(Filter filter, boolean selected) {
        ItemStack item = new ItemStack(filter.icon());
        ItemMeta meta = item.getItemMeta();
        if (selected) {
            meta.displayName(MiniMessage.miniMessage().deserialize("<green><bold>\u2713 " + filter.name() + "</bold></green>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            meta.displayName(MiniMessage.miniMessage().deserialize("<gray>" + filter.name() + "</gray>")
                    .decoration(TextDecoration.ITALIC, false));
            meta.lore(List.of(MiniMessage.miniMessage().deserialize("<yellow>Click to filter</yellow>")
                    .decoration(TextDecoration.ITALIC, false)));
        }
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPunishmentItem(Punishment p) {
        Material material = switch (p.getType()) {
            case BAN, TEMPBAN, IPBAN, TEMPIPBAN -> Material.RED_WOOL;
            case MUTE, TEMPMUTE, IPMUTE, TEMPIPMUTE -> Material.BLUE_WOOL;
            case VOICEMUTE, TEMPVOICEMUTE -> Material.CYAN_WOOL;
            case WARN -> Material.YELLOW_WOOL;
            case KICK -> Material.ORANGE_WOOL;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        boolean isActive = p.isActive() && !p.isExpired();

        meta.displayName(locale.getMessage("gui.history.item-name",
                Placeholder.unparsed("type", p.getType().getDisplayName())));

        String statusKey = isActive ? "check.status-active" : "check.status-inactive";
        String statusString = locale.getRawMessage(statusKey);

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("reason", p.getReason() != null ? p.getReason() : "Unspecified"))
                .resolver(Placeholder.unparsed("staff", p.getStaffName() != null ? p.getStaffName() : "Console"))
                .resolver(Placeholder.unparsed("date", TimeUtil.formatDate(p.getCreatedTime())))
                .resolver(Placeholder.unparsed("duration",
                        p.isPermanent() ? "Permanent"
                                : TimeUtil.formatDuration((p.getExpiryTime() - p.getCreatedTime()) / 1000)))
                .resolver(Placeholder.parsed("status", statusString))
                .resolver(Placeholder.unparsed("id", p.getPunishmentId()))
                .build();

        meta.lore(locale.getMessageList("gui.history.item-lore", placeholders));

        if (isActive) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPlayerHeadItem(OfflinePlayer target, List<Punishment> history) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            try { meta.setOwningPlayer(target); } catch (Exception ignored) {}

            meta.displayName(locale.getMessage("gui.history.player-head-name",
                    Placeholder.unparsed("player", target.getName() != null ? target.getName() : locale.getRawMessage("gui.history.unknown-player"))));

            long bans = history.stream().filter(p -> p.getType().isBan()).count();
            long mutes = history.stream().filter(p -> p.getType().isMute()).count();
            long warns = history.stream().filter(p -> p.getType() == PunishmentType.WARN).count();
            long kicks = history.stream().filter(p -> p.getType() == PunishmentType.KICK).count();
            long voiceMutes = history.stream().filter(p -> p.getType() == PunishmentType.VOICEMUTE || p.getType() == PunishmentType.TEMPVOICEMUTE).count();

            List<Component> lore = new ArrayList<>();
            lore.add(locale.getMessage("gui.history.summary-total", Placeholder.unparsed("count", String.valueOf(history.size()))));
            lore.add(Component.empty());
            lore.add(locale.getMessage("gui.history.summary-bans", Placeholder.unparsed("count", String.valueOf(bans))));
            lore.add(locale.getMessage("gui.history.summary-mutes", Placeholder.unparsed("count", String.valueOf(mutes))));
            lore.add(locale.getMessage("gui.history.summary-voice-mutes", Placeholder.unparsed("count", String.valueOf(voiceMutes))));
            lore.add(locale.getMessage("gui.history.summary-warnings", Placeholder.unparsed("count", String.valueOf(warns))));
            lore.add(locale.getMessage("gui.history.summary-kicks", Placeholder.unparsed("count", String.valueOf(kicks))));

            meta.lore(lore);
            head.setItemMeta(meta);
        }
        return head;
    }

    private ItemStack createNavItem(String localeKey) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(locale.getMessage(localeKey));
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFillerGlass() {
        ItemStack item = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }
}