package ret.tawny.controlbans.ui;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.services.EscalationService;

/**
 * Abstraction over the /punish UI. Two implementations exist:
 * {@link ret.tawny.controlbans.commands.gui.PunishGuiManager} (inventory,
 * works everywhere) and {@code ret.tawny.controlbans.ui.dialog.DialogPunishUi}
 * (Paper 1.21.6+ dialogs, loaded only when the dialog API is present).
 */
public interface PunishUi {

    /** Screen 1: pick the chat category (In-Game Chat / Voice Chat / Both). */
    void openCategorySelect(Player staff, OfflinePlayer target);

    /** Screen 2: pick the escalation preset for the chosen category. */
    void openPresetSelect(Player staff, OfflinePlayer target, EscalationService.Category category);
}
