package ret.tawny.controlbans.listeners;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;

public class GuiListener implements Listener {

    private final HistoryGuiManager historyGuiManager;
    private final AltsGuiManager altsGuiManager;

    public GuiListener(HistoryGuiManager historyGuiManager, AltsGuiManager altsGuiManager) {
        this.historyGuiManager = historyGuiManager;
        this.altsGuiManager = altsGuiManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        String title = event.getView().getTitle();
        if (title.startsWith("History: ")) {
            handleHistoryClick(event, player, title);
        } else if (title.startsWith("Alts: ")) {
            handleAltsClick(event, player, title);
        }
    }

    private void handleHistoryClick(InventoryClickEvent event, Player player, String title) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        // Extract player name and current page from title "History: Notch (1/3)"
        String targetName = title.substring(title.indexOf(":") + 2, title.lastIndexOf(" ("));
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        int currentPage = historyGuiManager.getOpenInventories().getOrDefault(player.getUniqueId(), 1);

        // Handle navigation clicks
        if (event.getSlot() == 45 && event.getCurrentItem().getType() == Material.ARROW) { // Previous Page
            historyGuiManager.openHistoryGui(player, target, currentPage - 1);
        } else if (event.getSlot() == 53 && event.getCurrentItem().getType() == Material.ARROW) { // Next Page
            historyGuiManager.openHistoryGui(player, target, currentPage + 1);
        }
    }

    private void handleAltsClick(InventoryClickEvent event, Player player, String title) {
        event.setCancelled(true);
        if (event.getCurrentItem() == null || event.getCurrentItem().getType() == Material.AIR) return;

        // Extract player name and current page from title "Alts: Notch (1/3)"
        String targetName = title.substring(title.indexOf(":") + 2, title.lastIndexOf(" ("));
        OfflinePlayer target = Bukkit.getOfflinePlayer(targetName);

        int currentPage = altsGuiManager.getOpenInventories().getOrDefault(player.getUniqueId(), 1);

        // Handle navigation clicks
        if (event.getSlot() == 45 && event.getCurrentItem().getType() == Material.ARROW) { // Previous Page
            altsGuiManager.openAltsGui(player, target, currentPage - 1);
        } else if (event.getSlot() == 53 && event.getCurrentItem().getType() == Material.ARROW) { // Next Page
            altsGuiManager.openAltsGui(player, target, currentPage + 1);
        }
    }

    @EventHandler
    public void onInventoryClose(InventoryCloseEvent event) {
        String title = event.getView().getTitle();
        if (title.startsWith("History: ")) {
            historyGuiManager.getOpenInventories().remove(event.getPlayer().getUniqueId());
        } else if (title.startsWith("Alts: ")) {
            altsGuiManager.getOpenInventories().remove(event.getPlayer().getUniqueId());
        }
    }
}