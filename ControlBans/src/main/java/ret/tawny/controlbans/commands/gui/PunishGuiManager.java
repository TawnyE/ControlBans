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

public class PunishGuiManager {

    private final ControlBansPlugin plugin;
    private final LocaleManager locale;

    public PunishGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
    }

    public static class PunishHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        public PunishHolder(OfflinePlayer target) { this.target = target; }
        public OfflinePlayer getTarget() { return target; }
    }

    public void openPunishMenu(Player staff, OfflinePlayer target) {
        Component title = locale.getMessage("gui.punish.title",
                Placeholder.unparsed("player", target.getName() != null ? target.getName() : locale.getRawMessage("gui.punish.unknown-player")));

        Inventory inv = Bukkit.createInventory(new PunishHolder(target), 45, title);

        inv.setItem(4, createHeadInfo(target));

        inv.setItem(19, createActionItem(staff, "controlbans.ban", Material.NETHERITE_AXE, "ban"));
        inv.setItem(20, createActionItem(staff, "controlbans.tempban", Material.IRON_AXE, "tempban"));
        inv.setItem(21, createActionItem(staff, "controlbans.ban.ip", Material.BEDROCK, "ipban"));

        inv.setItem(28, createActionItem(staff, "controlbans.mute", Material.PAPER, "mute"));
        inv.setItem(29, createActionItem(staff, "controlbans.tempmute", Material.MAP, "tempmute"));
        inv.setItem(30, createActionItem(staff, "controlbans.voicemute", Material.JUKEBOX, "voicemute"));

        inv.setItem(39, createActionItem(staff, "controlbans.kick", Material.LEATHER_BOOTS, "kick"));
        inv.setItem(40, createActionItem(staff, "controlbans.warn", Material.WRITABLE_BOOK, "warn"));
        inv.setItem(41, createActionItem(staff, "controlbans.freeze", Material.PACKED_ICE, "freeze"));

        ItemStack filler = createFiller();
        for (int i = 0; i < 45; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        staff.openInventory(inv);
    }

    private ItemStack createActionItem(Player staff, String permission, Material mat, String actionType) {
        String nameKey = "gui.punish.items." + actionType + ".name";
        String loreKey = "gui.punish.items." + actionType + ".lore";

        if (!staff.hasPermission(permission)) {
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta meta = barrier.getItemMeta();
            meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>(locale.getMessageList("gui.punish.no-permission"));
            lore.add(Component.empty());
            lore.add(locale.getMessage("gui.punish.permission-node",
                    Placeholder.unparsed("node", permission)).decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            barrier.setItemMeta(meta);
            return barrier;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(locale.getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        List<Component> lore = new ArrayList<>(locale.getMessageList(loreKey));
        lore.add(Component.empty());
        lore.addAll(locale.getMessageList("gui.punish.click-to-select"));
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

            meta.displayName(locale.getMessage("gui.punish.head.name",
                            Placeholder.unparsed("player", target.getName() != null ? target.getName() : locale.getRawMessage("gui.punish.unknown-player")))
                    .decoration(TextDecoration.ITALIC, false));

            meta.lore(locale.getMessageList("gui.punish.head.lore"));
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
