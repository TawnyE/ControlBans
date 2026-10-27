package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

public class StaffHistoryGuiManager {

    private static final Component TITLE = MiniMessage.miniMessage().deserialize("<light_purple><bold>Staff History</bold></light_purple>");
    private static final int ITEMS_PER_PAGE = 36;

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    public StaffHistoryGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
    }

    public static class StaffHistoryHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final String staffName;
        private final UUID staffUuid;
        private final int page;
        private final Map<String, Long> stats;
        private final Map<Integer, UUID> slotTargets = new HashMap<>();

        public StaffHistoryHolder(UUID viewerUuid, String staffName, UUID staffUuid, int page, Map<String, Long> stats) {
            this.viewerUuid = viewerUuid;
            this.staffName = staffName;
            this.staffUuid = staffUuid;
            this.page = page;
            this.stats = stats;
        }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }

        @Override
        public MenuType getMenuType() { return MenuType.STAFF_HISTORY; }

        public String getStaffName() { return staffName; }
        public UUID getStaffUuid() { return staffUuid; }
        public int getPage() { return page; }
        public Map<String, Long> getStats() { return stats; }

        public void mapSlotToStaff(int slot, UUID staffMemberUuid) {
            slotTargets.put(slot, staffMemberUuid);
        }

        public UUID getStaffUuidAtSlot(int slot) {
            return slotTargets.get(slot);
        }
    }

    public void openStaffSelector(Player viewer) {
        StaffHistoryHolder holder = new StaffHistoryHolder(viewer.getUniqueId(), null, null, 1, Map.of());
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE);

        Player[] onlinePlayers = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        int slot = 0;
        for (Player player : onlinePlayers) {
            if (slot >= 45) break;
            if (player.hasPermission("controlbans.staff") || player.hasPermission("controlbans.punish")) {
                inv.setItem(slot, createStaffHeadItem(player));
                holder.mapSlotToStaff(slot, player.getUniqueId());
                slot++;
            }
        }

        inv.setItem(49, createInfoItem("<gold>Select a staff member</gold>", "<gray>Click to view their punishment history</gray>"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        viewer.openInventory(inv);
    }

    public void openStaffHistory(Player viewer, String staffName, int page) {
        punishmentService.getPlayerResolver().getPlayerUuid(staffName)
                .thenAccept(staffUuid -> openStaffHistory(viewer, staffName, staffUuid, page));
    }

    public void openStaffHistory(Player viewer, UUID staffUuid, int page) {
        OfflinePlayer offline = Bukkit.getOfflinePlayer(staffUuid);
        String staffName = offline.getName();
        if (staffName != null) {
            openStaffHistory(viewer, staffName, staffUuid, page);
            return;
        }
        plugin.getStorage().getLastKnownName(staffUuid).thenAccept(name ->
                openStaffHistory(viewer, name != null ? name : staffUuid.toString(), staffUuid, page));
    }

    private void openStaffHistory(Player viewer, String staffName, UUID staffUuid, int page) {
        punishmentService.getAllPunishments().thenAccept(allPunishments -> {
            List<Punishment> staffPunishments = allPunishments.stream()
                    .filter(p -> p.getStaffName() != null && p.getStaffName().equalsIgnoreCase(staffName))
                    .sorted(Comparator.comparingLong(Punishment::getCreatedTime).reversed())
                    .toList();

            long totalBans = staffPunishments.stream().filter(p -> p.getType().isBan()).count();
            long totalMutes = staffPunishments.stream().filter(p -> p.getType().isMute()).count();
            long totalWarns = staffPunishments.stream().filter(p -> p.getType() == PunishmentType.WARN).count();
            long totalKicks = staffPunishments.stream().filter(p -> p.getType() == PunishmentType.KICK).count();
            long totalVoiceMutes = staffPunishments.stream().filter(p -> p.getType() == PunishmentType.VOICEMUTE || p.getType() == PunishmentType.TEMPVOICEMUTE).count();
            long totalIssued = staffPunishments.size();

            Map<String, Long> stats = new LinkedHashMap<>();
            stats.put("Total", totalIssued);
            stats.put("Bans", totalBans);
            stats.put("Mutes", totalMutes);
            stats.put("Warns", totalWarns);
            stats.put("Kicks", totalKicks);
            stats.put("Voice Mutes", totalVoiceMutes);

            int maxPage = Math.max(1, (int) Math.ceil((double) staffPunishments.size() / ITEMS_PER_PAGE));
            int currentPage = Math.max(1, Math.min(page, maxPage));

            plugin.getSchedulerAdapter().runTaskForPlayer(viewer, () -> {
                Inventory inv = Bukkit.createInventory(new StaffHistoryHolder(viewer.getUniqueId(), staffName, staffUuid, currentPage, stats), 54, TITLE);

                int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
                int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, staffPunishments.size());

                for (int i = startIndex; i < endIndex; i++) {
                    inv.setItem(i - startIndex, createPunishmentItem(staffPunishments.get(i)));
                }

                inv.setItem(45, currentPage > 1 ? createNavItem("<yellow>Previous Page</yellow>") : createFiller());
                inv.setItem(49, createStatsItem(staffName, staffUuid, stats));
                inv.setItem(53, currentPage < maxPage ? createNavItem("<yellow>Next Page</yellow>") : createFiller());

                ItemStack filler = createFiller();
                for (int i = 0; i < 54; i++) {
                    if (inv.getItem(i) == null) inv.setItem(i, filler);
                }

                viewer.openInventory(inv);
            });
        });
    }

    private ItemStack createStaffHeadItem(Player player) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try { meta.setOwningPlayer(player); } catch (Exception ignored) {}
        meta.displayName(MiniMessage.miniMessage().deserialize("<white><bold>" + MiniMessage.miniMessage().escapeTags(player.getName()) + "</bold></white>")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Click to view punishment history</gray>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
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
        meta.displayName(MiniMessage.miniMessage().deserialize("<white>" + p.getType().getDisplayName() + "</white>")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Target: <white>" + MiniMessage.miniMessage().escapeTags(p.getTargetName() != null ? p.getTargetName() : "Unknown") + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Reason: <white>" + MiniMessage.miniMessage().escapeTags(p.getReason() != null ? p.getReason() : "Unspecified") + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Date: <white>" + TimeUtil.formatDate(p.getCreatedTime()) + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>ID: <white>" + MiniMessage.miniMessage().escapeTags(p.getPunishmentId()) + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        boolean isActive = p.isActive() && !p.isExpired();
        lore.add(MiniMessage.miniMessage().deserialize(isActive ? "<green>Active</green>" : "<red>Expired/Revoked</red>")
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createStatsItem(String staffName, UUID staffUuid, Map<String, Long> stats) {
        ItemStack item = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) item.getItemMeta();
        if (staffUuid != null) {
            try { meta.setOwningPlayer(Bukkit.getOfflinePlayer(staffUuid)); } catch (Exception ignored) {}
        }
        meta.displayName(MiniMessage.miniMessage().deserialize("<gold><bold>" + MiniMessage.miniMessage().escapeTags(staffName) + "</bold></gold>")
                .decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray>Staff Statistics</dark_gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        for (Map.Entry<String, Long> entry : stats.entrySet()) {
            lore.add(MiniMessage.miniMessage().deserialize("<gray>" + MiniMessage.miniMessage().escapeTags(entry.getKey()) + ": <white>" + entry.getValue() + "</white></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }
        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createNavItem(String text) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createInfoItem(String title, String description) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(title).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(MiniMessage.miniMessage().deserialize(description).decoration(TextDecoration.ITALIC, false)));
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
}