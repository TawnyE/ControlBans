package ret.tawny.controlbans.commands.gui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.listeners.ChatInputListener;
import ret.tawny.controlbans.menus.ControlBansHolder;

import java.util.List;
import java.util.logging.Level;

public class SettingsGuiManager {

    private final ControlBansPlugin plugin;
    private final ChatInputListener chatListener;

    public SettingsGuiManager(ControlBansPlugin plugin, ChatInputListener chatListener) {
        this.plugin = plugin;
        this.chatListener = chatListener;
    }

    public static class SettingsHolder extends ControlBansHolder {
    }

    public void openSettingsGui(Player player) {
        Inventory inv = Bukkit.createInventory(new SettingsHolder(), 27, plugin.getLocaleManager().getMessage("gui.settings.title"));

        inv.setItem(10, createBooleanItem(Material.REDSTONE_TORCH, "gui.settings.items.silent-bans.name",
                plugin.getConfigManager().isSilentByDefault()));

        inv.setItem(12, createBooleanItem(Material.PAPER, "gui.settings.items.broadcast-bans.name",
                plugin.getConfigManager().isBroadcastEnabled()));

        inv.setItem(14, createTextItem(Material.NAME_TAG, "gui.settings.items.bedrock-prefix.name",
                plugin.getConfigManager().getBedrockPrefix()));

        inv.setItem(16, createBooleanItem(Material.ENDER_EYE, "gui.settings.items.alts-punishment.name",
                plugin.getConfigManager().isAltPunishEnabled()));

        player.openInventory(inv);
    }

    public void handleClick(Player player, ItemStack item) {
        if (item == null || !item.hasItemMeta()) {
            return;
        }

        Material type = item.getType();

        if (type == Material.NAME_TAG) {
            player.closeInventory();
            player.sendMessage(plugin.getLocaleManager().getMessage("gui.settings.prompt-bedrock-prefix"));
            chatListener.awaitInput(player, "integrations.geyser.bedrock-prefix");
        } else if (type == Material.REDSTONE_TORCH) {
            toggleBoolean(player, "punishments.broadcast.silent-by-default");
        } else if (type == Material.PAPER) {
            toggleBoolean(player, "punishments.broadcast.enabled");
        } else if (type == Material.ENDER_EYE) {
            toggleBoolean(player, "alts-punish.enabled");
        }
    }

    private void toggleBoolean(Player player, String configPath) {
        player.closeInventory();
        player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.updating"));

        plugin.getSchedulerAdapter().runTaskAsynchronously(() -> {
            try {
                boolean current = plugin.getConfig().getBoolean(configPath);
                boolean newValue = !current;

                plugin.getConfig().set(configPath, newValue);
                plugin.saveConfig();
                plugin.reload();

                if (player.isOnline()) {
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                        player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.updated",
                                Placeholder.unparsed("path", configPath),
                                Placeholder.unparsed("value", String.valueOf(newValue))));
                        openSettingsGui(player);
                    });
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to save settings", e);
                if (player.isOnline()) {
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> {
                        player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.save-failed",
                                Placeholder.unparsed("error", e.getMessage())));
                    });
                }
            }
        });
    }

    private ItemStack createBooleanItem(Material material, String nameKey, boolean value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.getLocaleManager().getMessage(nameKey).decoration(TextDecoration.ITALIC, false));

        meta.lore(List.of(
                plugin.getLocaleManager().getMessage("gui.settings.value-label",
                        Placeholder.unparsed("value", String.valueOf(value))).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                plugin.getLocaleManager().getMessage("gui.settings.click-toggle").decoration(TextDecoration.ITALIC, false)
        ));

        item.setItemMeta(meta);
        return item;
    }

    private ItemStack createTextItem(Material material, String nameKey, String value) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        meta.displayName(plugin.getLocaleManager().getMessage(nameKey).decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                plugin.getLocaleManager().getMessage("gui.settings.current-label",
                        Placeholder.unparsed("value", value)).decoration(TextDecoration.ITALIC, false),
                Component.empty(),
                plugin.getLocaleManager().getMessage("gui.settings.click-edit").decoration(TextDecoration.ITALIC, false)
        ));
        item.setItemMeta(meta);
        return item;
    }
}
