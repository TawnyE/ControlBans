package ret.tawny.controlbans.menus;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

public abstract class ControlBansHolder implements InventoryHolder {

    private Inventory inventory;

    @Override
    public Inventory getInventory() {
        return inventory;
    }

    public void setInventory(Inventory inventory) {
        this.inventory = inventory;
    }

    public enum MenuType {
        MAIN_HUB,
        ONLINE_PLAYERS,
        PUNISH,
        HISTORY,
        STAFF_HISTORY,
        REPORTS,
        REPORTS_ADMIN_LIST,
        REPORT_ADMIN_DETAIL,
        APPEALS_LIST,
        APPEAL_DETAIL,
        SETTINGS,
        ALTS
    }

    public abstract UUID getViewerUuid();

    public abstract MenuType getMenuType();
}