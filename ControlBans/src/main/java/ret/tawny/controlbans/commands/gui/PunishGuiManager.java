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
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.menus.ControlBansHolder;
import ret.tawny.controlbans.services.PunishmentTemplateService.TemplateRule;

import java.util.ArrayList;
import java.util.List;

public class PunishGuiManager {

    private final ControlBansPlugin plugin;

    public PunishGuiManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public enum PunishMode {
        BANS, MUTES, WARNS
    }

    public static class PunishHolder extends ControlBansHolder {
        private final OfflinePlayer target;
        private final PunishMode mode;

        public PunishHolder(OfflinePlayer target, PunishMode mode) {
            this.target = target;
            this.mode = mode;
        }

        public OfflinePlayer getTarget() { return target; }
        public PunishMode getMode() { return mode; }
    }

    public void openPunishMenu(Player staff, OfflinePlayer target) {
        openPunishMenu(staff, target, PunishMode.MUTES);
    }

    public void openPunishMenu(Player staff, OfflinePlayer target, PunishMode mode) {
        String targetName = target.getName() != null ? target.getName() : "Unknown";

        String colorTag = switch (mode) {
            case BANS -> "<red>";
            case MUTES -> "<gold>";
            case WARNS -> "<orange>";
        };
        String modeLabel = switch (mode) {
            case BANS -> "Bans";
            case MUTES -> "Mutes";
            case WARNS -> "Warns";
        };

        Component title = MiniMessage.miniMessage().deserialize(
                "<gold>Punishment</gold> <gray>»</gray> <white>" + targetName + "</white> <gray>»</gray> " +
                colorTag + modeLabel
        );

        Inventory inv = Bukkit.createInventory(new PunishHolder(target, mode), 45, title);

        inv.setItem(1, createPlayerInfoItem(target, mode));
        inv.setItem(7, createHistoryShortcutItem(mode));

        List<TemplateRule> templates = plugin.getPunishmentService().getTemplateService().getTemplates();
        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};
        int templateIdx = 0;

        for (TemplateRule rule : templates) {
            if (templateIdx >= slots.length) break;

            boolean isBanType = rule.getType().equalsIgnoreCase("ban") ||
                                rule.getType().equalsIgnoreCase("tempban") ||
                                rule.getType().equalsIgnoreCase("ipban");

            boolean shouldShow = switch (mode) {
                case BANS -> isBanType || rule.getType().equalsIgnoreCase("any");
                case MUTES -> rule.getType().equalsIgnoreCase("mute") ||
                              rule.getType().equalsIgnoreCase("tempmute") ||
                              rule.getType().equalsIgnoreCase("voicemute");
                case WARNS -> rule.getType().equalsIgnoreCase("warn") ||
                              rule.getType().equalsIgnoreCase("kick");
            };

            if (!shouldShow) continue;

            inv.setItem(slots[templateIdx++], createTemplateItem(staff, rule, mode));
        }

        inv.setItem(36, createCloseItem(mode));

        inv.setItem(44, createSwitchPageItem(mode));

        ItemStack filler = createFiller(mode);
        for (int i = 0; i < 45; i++) {
            if (inv.getItem(i) == null) inv.setItem(i, filler);
        }

        staff.openInventory(inv);
    }

    private ItemStack createTemplateItem(Player staff, TemplateRule rule, PunishMode mode) {
        String matName = rule.getItemMaterial();
        Material mat = Material.matchMaterial(matName);
        if (mat == null) {
            mat = switch (mode) {
                case BANS -> Material.RED_CANDLE;
                case MUTES -> Material.ORANGE_CANDLE;
                case WARNS -> Material.YELLOW_CANDLE;
            };
        }

        String accentColor = switch (mode) {
            case BANS -> "<red>";
            case MUTES -> "<gold>";
            case WARNS -> "<orange>";
        };

        boolean hasPerm = rule.getPermission() == null || staff.hasPermission(rule.getPermission());
        if (!hasPerm) {
            ItemStack barrier = new ItemStack(Material.BARRIER);
            ItemMeta meta = barrier.getItemMeta();
            meta.displayName(MiniMessage.miniMessage().deserialize("<red><bold>" + rule.getDisplayName() + "</bold></red>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<dark_gray><bold>LOCKED TEMPLATE</bold></dark_gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<gray>• " + rule.getDescription() + "</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<red><bold>❌ Requires permission:</bold></red>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<red>  " + rule.getPermission() + "</red>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            barrier.setItemMeta(meta);
            return barrier;
        }

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(MiniMessage.miniMessage().deserialize(accentColor + rule.getDisplayName()).decoration(TextDecoration.ITALIC, false));

        List<Component> lore = new ArrayList<>();
        lore.add(MiniMessage.miniMessage().deserialize("<dark_gray><bold>TEMPLATE</bold></dark_gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(MiniMessage.miniMessage().deserialize("<gray>• " + rule.getDescription() + "</gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(MiniMessage.miniMessage().deserialize(accentColor + "<bold>✜ DURATION</bold>").decoration(TextDecoration.ITALIC, false));
        lore.add(MiniMessage.miniMessage().deserialize("<gray>| " + rule.getDurationLore() + "</gray>").decoration(TextDecoration.ITALIC, false));
        lore.add(Component.empty());
        lore.add(MiniMessage.miniMessage().deserialize("<yellow><bold>➡ Click to Apply</bold></yellow>").decoration(TextDecoration.ITALIC, false));

        meta.lore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createPlayerInfoItem(OfflinePlayer target, PunishMode mode) {
        Material mat = switch (mode) {
            case BANS -> Material.BLUE_CANDLE;
            case MUTES -> Material.BLUE_CANDLE;
            case WARNS -> Material.YELLOW_CANDLE;
        };
        String accent = switch (mode) {
            case BANS -> "<blue>";
            case MUTES -> "<blue>";
            case WARNS -> "<orange>";
        };
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(accent + "<bold>👤 Player Information</bold>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Target: <white>" + (target.getName() != null ? target.getName() : "Unknown") + "</white></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<gray>UUID: <white>" + target.getUniqueId() + "</white></gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Status: " + (target.isOnline() ? "<green>Online</green>" : "<red>Offline</red>") + "</gray>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createHistoryShortcutItem(PunishMode mode) {
        Material mat = switch (mode) {
            case BANS -> Material.GREEN_CANDLE;
            case MUTES -> Material.GREEN_CANDLE;
            case WARNS -> Material.ORANGE_CANDLE;
        };
        String accent = switch (mode) {
            case BANS -> "<green>";
            case MUTES -> "<green>";
            case WARNS -> "<orange>";
        };
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(accent + "<bold>📜 Punishment History</bold>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Click to view all past punishments</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<gray>and active warnings for this player.</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<yellow><bold>➡ Click to View</bold></yellow>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createCloseItem(PunishMode mode) {
        ItemStack item = new ItemStack(Material.BARRIER);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            String accent = switch (mode) {
                case BANS -> "<red>";
                case MUTES -> "<red>";
                case WARNS -> "<orange>";
            };
            meta.displayName(MiniMessage.miniMessage().deserialize(accent + "<bold>Close Menu</bold>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<gray>Click to exit this menu.</gray>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createSwitchPageItem(PunishMode mode) {
        PunishMode nextMode = switch (mode) {
            case BANS -> PunishMode.MUTES;
            case MUTES -> PunishMode.WARNS;
            case WARNS -> PunishMode.BANS;
        };
        String nextLabel = switch (nextMode) {
            case BANS -> "Bans";
            case MUTES -> "Mutes";
            case WARNS -> "Warns";
        };
        String nextColor = switch (nextMode) {
            case BANS -> "<red>";
            case MUTES -> "<gold>";
            case WARNS -> "<orange>";
        };
        String currentLabel = switch (mode) {
            case BANS -> "ban";
            case MUTES -> "mute";
            case WARNS -> "warn";
        };
        String currentColor = switch (mode) {
            case BANS -> "<red>";
            case MUTES -> "<gold>";
            case WARNS -> "<orange>";
        };
        Material mat = switch (nextMode) {
            case BANS -> Material.RED_CANDLE;
            case MUTES -> Material.ORANGE_CANDLE;
            case WARNS -> Material.YELLOW_CANDLE;
        };

        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(nextColor + "<bold>Switch to " + nextLabel + "</bold>").decoration(TextDecoration.ITALIC, false));
            List<Component> lore = new ArrayList<>();
            lore.add(MiniMessage.miniMessage().deserialize("<dark_gray><bold>PAGE</bold></dark_gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<gray>• Viewing </gray>" + currentColor + currentLabel + "<gray> templates</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(MiniMessage.miniMessage().deserialize("<gray>• Switch to </gray>" + nextColor + nextLabel + "<gray> page</gray>").decoration(TextDecoration.ITALIC, false));
            lore.add(Component.empty());
            lore.add(MiniMessage.miniMessage().deserialize("<yellow><bold>➡ Click to Switch</bold></yellow>").decoration(TextDecoration.ITALIC, false));
            meta.lore(lore);
            item.setItemMeta(meta);
        }
        return item;
    }

    private ItemStack createFiller(PunishMode mode) {
        Material glass = switch (mode) {
            case BANS -> Material.BLACK_STAINED_GLASS_PANE;
            case MUTES -> Material.BLACK_STAINED_GLASS_PANE;
            case WARNS -> Material.ORANGE_STAINED_GLASS_PANE;
        };
        ItemStack item = new ItemStack(glass);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(Component.text(" "));
            item.setItemMeta(meta);
        }
        return item;
    }
}

