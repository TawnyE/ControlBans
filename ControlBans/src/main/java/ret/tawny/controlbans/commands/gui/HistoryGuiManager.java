package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
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
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class HistoryGuiManager {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final LocaleManager locale;
    private final Map<UUID, Integer> openInventories = new ConcurrentHashMap<>();
    private static final int ITEMS_PER_PAGE = 45;

    public HistoryGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
        this.locale = plugin.getLocaleManager();
    }

    public void openHistoryGui(Player viewer, OfflinePlayer target, int page) {
        punishmentService.getPunishmentHistory(target.getUniqueId(), 200).thenAccept(punishments -> {
            punishments.sort(Comparator.comparingLong(Punishment::getCreatedTime).reversed());

            plugin.getSchedulerAdapter().runTask(() -> {
                int maxPage = (int) Math.ceil((double) punishments.size() / ITEMS_PER_PAGE);
                if (maxPage == 0) maxPage = 1;

                Component title = locale.getMessage("gui.history.title",
                        Placeholder.unparsed("player", Objects.requireNonNull(target.getName())),
                        Placeholder.unparsed("page", String.valueOf(page)),
                        Placeholder.unparsed("maxpage", String.valueOf(maxPage))
                );
                Inventory inv = Bukkit.createInventory(null, 54, title);

                int startIndex = (page - 1) * ITEMS_PER_PAGE;
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, punishments.size());
                for (int i = startIndex; i < endIndex; i++) {
                    inv.setItem(i - startIndex, createPunishmentItem(punishments.get(i)));
                }

                inv.setItem(45, (page > 1) ? createNavItem("gui.nav-previous") : createFillerGlass());
                inv.setItem(49, createPlayerHeadItem(target, punishments.size()));
                inv.setItem(53, (page < maxPage) ? createNavItem("gui.nav-next") : createFillerGlass());

                viewer.openInventory(inv);
                openInventories.put(viewer.getUniqueId(), page);
            });
        }).exceptionally(ex -> {
            viewer.sendMessage(locale.getMessage("errors.database-error"));
            plugin.getLogger().warning("Failed to fetch history for GUI: " + ex.getMessage());
            return null;
        });
    }

    private ItemStack createPunishmentItem(Punishment p) {
        Material material = switch (p.getType()) {
            case BAN, TEMPBAN, IPBAN -> Material.RED_WOOL;
            case MUTE, TEMPMUTE -> Material.BLUE_WOOL;
            case WARN -> Material.YELLOW_WOOL;
            case KICK -> Material.ORANGE_WOOL;
        };
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();

        boolean isActive = p.isActive() && !p.isExpired();

        meta.displayName(locale.getMessage("gui.history.item-name",
                Placeholder.unparsed("type", p.getType().getDisplayName()))
        );

        String statusKey = isActive ? "check.status-active" : "check.status-inactive";
        String statusString = locale.getRawMessage(statusKey);

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("reason", p.getReason()))
                .resolver(Placeholder.unparsed("staff", p.getStaffName()))
                .resolver(Placeholder.unparsed("date", TimeUtil.formatDate(p.getCreatedTime())))
                .resolver(Placeholder.unparsed("duration", p.isPermanent() ? "Permanent" : TimeUtil.formatDuration((p.getExpiryTime() - p.getCreatedTime()) / 1000)))
                // **THE FIX:** Changed from .unparsed to .parsed to process MiniMessage tags in the status string.
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

    private ItemStack createPlayerHeadItem(OfflinePlayer target, int totalPunishments) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        if (meta != null) {
            meta.setOwningPlayer(target);
            meta.displayName(locale.getMessage("gui.history.player-head-name",
                    Placeholder.unparsed("player", Objects.requireNonNull(target.getName())))
            );
            meta.lore(Collections.singletonList(locale.getMessage("gui.history.player-head-lore",
                    Placeholder.unparsed("count", String.valueOf(totalPunishments))))
            );
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

    public Map<UUID, Integer> getOpenInventories() {
        return openInventories;
    }
}