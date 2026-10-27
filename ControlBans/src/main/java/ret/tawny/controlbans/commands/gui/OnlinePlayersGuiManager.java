package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.menus.ControlBansHolder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class OnlinePlayersGuiManager {

    private static final Component TITLE = MiniMessage.miniMessage().deserialize("<green><bold>Online Players</bold></green>");
    private static final Component REPORT_TITLE = MiniMessage.miniMessage().deserialize("<green><bold>Select Player to Report</bold></green>");
    private static final int ITEMS_PER_PAGE = 36;

    private final ControlBansPlugin plugin;

    public OnlinePlayersGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public static class OnlinePlayersHolder extends ControlBansHolder {
        private final UUID viewerUuid;
        private final int page;
        private final boolean reporting;
        private final Map<Integer, UUID> slotTargets = new HashMap<>();

        public OnlinePlayersHolder(UUID viewerUuid, int page) {
            this(viewerUuid, page, false);
        }

        public OnlinePlayersHolder(UUID viewerUuid, int page, boolean reporting) {
            this.viewerUuid = viewerUuid;
            this.page = page;
            this.reporting = reporting;
        }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }

        @Override
        public MenuType getMenuType() { return MenuType.ONLINE_PLAYERS; }

        public int getPage() { return page; }

        public boolean isReporting() { return reporting; }

        public void mapSlotToTarget(int slot, UUID targetUuid) {
            slotTargets.put(slot, targetUuid);
        }

        public UUID getTargetUuid(int slot) {
            return slotTargets.get(slot);
        }
    }

    public void openOnlinePlayers(Player viewer) {
        openOnlinePlayers(viewer, 1, false);
    }

    public void openOnlinePlayers(Player viewer, int page) {
        openOnlinePlayers(viewer, page, false);
    }

    public void openReportPicker(Player viewer, int page) {
        openOnlinePlayers(viewer, page, true);
    }

    private void openOnlinePlayers(Player viewer, int page, boolean reporting) {
        Player[] onlinePlayers = Bukkit.getOnlinePlayers().toArray(new Player[0]);
        int maxPage = Math.max(1, (int) Math.ceil((double) onlinePlayers.length / ITEMS_PER_PAGE));
        int currentPage = Math.max(1, Math.min(page, maxPage));

        OnlinePlayersHolder holder = new OnlinePlayersHolder(viewer.getUniqueId(), currentPage, reporting);
        Inventory inv = Bukkit.createInventory(holder, reporting ? 54 : 54, reporting ? REPORT_TITLE : TITLE);

        int startIndex = (currentPage - 1) * ITEMS_PER_PAGE;
        int endIndex = Math.min(startIndex + ITEMS_PER_PAGE, onlinePlayers.length);

        for (int i = startIndex; i < endIndex; i++) {
            int slot = i - startIndex;
            inv.setItem(slot, createPlayerItem(onlinePlayers[i], reporting));
            holder.mapSlotToTarget(slot, onlinePlayers[i].getUniqueId());
        }

        inv.setItem(45, currentPage > 1 ? createNavItem("<yellow>Previous Page</yellow>") : createFiller());
        inv.setItem(49, createPageInfoItem(currentPage, maxPage));
        inv.setItem(53, currentPage < maxPage ? createNavItem("<yellow>Next Page</yellow>") : createFiller());

        ItemStack filler = createFiller();
        for (int i = 0; i < 54; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        viewer.openInventory(inv);
    }

    private ItemStack createPlayerItem(Player player, boolean reporting) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        SkullMeta meta = (SkullMeta) head.getItemMeta();
        try { meta.setOwningPlayer(player); } catch (Exception ignored) {}

        String safeName = player.getName() == null ? "Unknown" : player.getName().replace("<", "\\<");
        String safeWorld = player.getWorld() == null || player.getWorld().getName() == null
                ? "Unknown" : player.getWorld().getName().replace("<", "\\<");

        meta.displayName(MiniMessage.miniMessage().deserialize("<white><bold>" + safeName + "</bold></white>")
                .decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<gray>Real Name: <white>" + safeName + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        try {
            int ping = player.getPing();
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Ping: <white>" + ping + "ms</white></gray>")
                    .decoration(TextDecoration.ITALIC, false));
        } catch (Exception ignored) {}
        lore.add(MiniMessage.miniMessage().deserialize("<gray>World: <white>" + safeWorld + "</white></gray>")
                .decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        if (reporting) {
            lore.add(MiniMessage.miniMessage().deserialize("<green>Left-click</green> <gray>to report this player</gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<aqua>Right-click</aqua> <gray>to view history</gray>")
                    .decoration(TextDecoration.ITALIC, false));
        } else {
            lore.add(MiniMessage.miniMessage().deserialize("<green>Left-click</green> <gray>to punish</gray>")
                    .decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<aqua>Right-click</aqua> <gray>to view history</gray>")
                    .decoration(TextDecoration.ITALIC, false));
        }

        meta.lore(lore);
        head.setItemMeta(meta);
        return head;
    }

    private ItemStack createNavItem(String text) {
        ItemStack item = new ItemStack(Material.ARROW);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(text).decoration(TextDecoration.ITALIC, false));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPageInfoItem(int current, int max) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize("<gold>Page " + current + " of " + max + "</gold>")
                .decoration(TextDecoration.ITALIC, false));
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