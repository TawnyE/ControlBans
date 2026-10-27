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
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.menus.ControlBansHolder;

import java.util.List;
import java.util.UUID;

public class MainMenuGuiManager {

    private static final Component TITLE = MiniMessage.miniMessage().deserialize("<gold><bold>ControlBans Hub</bold></gold>");

    private final ControlBansPlugin plugin;

    public MainMenuGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public static class MainMenuHolder extends ControlBansHolder {
        private final UUID viewerUuid;

        public MainMenuHolder(UUID viewerUuid) {
            this.viewerUuid = viewerUuid;
        }

        @Override
        public UUID getViewerUuid() { return viewerUuid; }

        @Override
        public MenuType getMenuType() { return MenuType.MAIN_HUB; }
    }

    public void openMainMenu(Player player) {
        Inventory inv = Bukkit.createInventory(new MainMenuHolder(player.getUniqueId()), 27, TITLE);

        inv.setItem(11, createNavItem(Material.PLAYER_HEAD, "<green><bold>Online Players</bold></green>",
                "<gray>View and punish online players</gray>", "<yellow>Click to open</yellow>"));

        inv.setItem(12, createNavItem(Material.BOOK, "<blue><bold>Player History</bold></blue>",
                "<gray>Lookup punishment history for any player</gray>", "<yellow>Click to search</yellow>"));

        inv.setItem(14, createNavItem(Material.SHIELD, "<light_purple><bold>Staff History</bold></light_purple>",
                "<gray>Review staff punishment logs</gray>", "<yellow>Click to open</yellow>"));

        inv.setItem(15, createNavItem(Material.PAPER, "<red><bold>Global Reports</bold></red>",
                "<gray>Manage the report queue</gray>", "<yellow>Click to open</yellow>"));

        inv.setItem(22, createNavItem(Material.COMPARATOR, "<aqua><bold>Admin Settings</bold></aqua>",
                "<gray>Edit live configuration</gray>", "<yellow>Click to open</yellow>"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        player.openInventory(inv);
    }

    private ItemStack createNavItem(Material material, String displayName, String description, String action) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(displayName).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.empty(),
                MiniMessage.miniMessage().deserialize(description).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                MiniMessage.miniMessage().deserialize(action).decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createFiller() {
        ItemStack item = new ItemStack(Material.BLACK_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(Component.text(" "));
        item.setItemMeta(meta);
        return item;
    }
}