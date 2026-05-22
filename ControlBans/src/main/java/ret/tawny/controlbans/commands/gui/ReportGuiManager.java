package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
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

import java.util.ArrayList;
import java.util.List;

public class ReportGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;

    public ReportGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    public static class ReportHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        public ReportHolder(OfflinePlayer target) { this.target = target; }
        public OfflinePlayer getTarget() { return target; }
    }

    public void openReportMenu(Player reporter, OfflinePlayer target) {
        Component title = locale.getMessage("gui.report.title",
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : "Unknown"));

        Inventory inv = Bukkit.createInventory(new ReportHolder(target), 27, title);

        inv.setItem(4, createHeadInfo(target));

        inv.setItem(10, createActionItem("hacking", Material.DIAMOND_SWORD));
        inv.setItem(12, createActionItem("chat", Material.PAPER));
        inv.setItem(14, createActionItem("griefing", Material.TNT));
        inv.setItem(16, createActionItem("other", Material.MAP));

        ItemStack filler = createFiller();
        for (int i = 0; i < 27; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        reporter.openInventory(inv);
    }

    private ItemStack createActionItem(String actionType, Material mat) {
        String nameKey = "gui.report.items." + actionType + ".name";
        String loreKey = "gui.report.items." + actionType + ".lore";

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>(locale.getMessageList(loreKey));
        lore.add(Component.empty());
        lore.addAll(locale.getMessageList("gui.report.click-to-select"));
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        meta.lore(lore);
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
