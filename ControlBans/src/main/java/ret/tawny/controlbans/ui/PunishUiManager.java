package ret.tawny.controlbans.ui;

import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;

import java.lang.reflect.Constructor;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Chooses which /punish UI implementation to use and routes calls to it.
 *
 * <p>Fallback chain:</p>
 * <ol>
 *   <li><b>Probe</b> — {@code io.papermc.paper.dialog.Dialog} is probed once at
 *       construction (same pattern as {@code SchedulerAdapter.isFolia}). On
 *       servers without the API the dialog UI is never class-loaded.</li>
 *   <li><b>Config</b> — {@code punish.interface}: {@code auto} (default) uses
 *       dialogs when the probe succeeded, {@code dialog} forces dialogs,
 *       {@code gui} always uses the inventory UI.</li>
 *   <li><b>Exception fallback</b> — if showing the dialog throws (old client
 *       via ViaVersion that cannot render dialogs, etc.) the invocation falls
 *       back to the inventory UI and the failure is logged once at FINE.</li>
 * </ol>
 */
public class PunishUiManager {

    private static final String DIALOG_API_CLASS = "io.papermc.paper.dialog.Dialog";
    private static final String DIALOG_UI_CLASS = "ret.tawny.controlbans.ui.dialog.DialogPunishUi";

    private final ControlBansPlugin plugin;
    private final PunishGuiManager inventoryUi;
    private final boolean dialogApiPresent;
    private volatile PunishUi dialogUi;
    private volatile boolean fallbackLogged;
    private final Map<UUID, PunishSession> sessions = new ConcurrentHashMap<>();

    public PunishUiManager(ControlBansPlugin plugin, PunishGuiManager inventoryUi) {
        this.plugin = plugin;
        this.inventoryUi = inventoryUi;
        this.dialogApiPresent = probeDialogApi();
        if (plugin.getConfigManager().isDebugLoggingEnabled()) {
            plugin.getLogger().info("[PunishUI] Dialog API " + (dialogApiPresent ? "detected" : "not present")
                    + " — interface mode: " + plugin.getConfigManager().getPunishInterface());
        }
    }

    private static boolean probeDialogApi() {
        try {
            Class.forName(DIALOG_API_CLASS);
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    private Optional<PunishUi> dialogUi() {
        if (!dialogApiPresent) return Optional.empty();
        PunishUi ui = dialogUi;
        if (ui == null) {
            synchronized (this) {
                if (dialogUi == null) {
                    try {
                        Class<?> clazz = Class.forName(DIALOG_UI_CLASS);
                        Constructor<?> constructor = clazz.getConstructor(ControlBansPlugin.class);
                        dialogUi = (PunishUi) constructor.newInstance(plugin);
                    } catch (Throwable t) {
                        plugin.getLogger().warning("[PunishUI] Could not initialize the dialog UI: " + t);
                        return Optional.empty();
                    }
                }
                ui = dialogUi;
            }
        }
        return Optional.of(ui);
    }

    private boolean useDialogs() {
        String mode = plugin.getConfigManager().getPunishInterface();
        if (mode == null) mode = "auto";
        return switch (mode.trim().toLowerCase(java.util.Locale.ROOT)) {
            case "gui" -> false;
            case "dialog" -> dialogApiPresent;
            default -> dialogApiPresent; // auto
        };
    }

    /** Entry point used by commands and GUIs: opens screen 1 (category select). */
    public void open(Player staff, OfflinePlayer target) {
        openCategorySelect(staff, target);
    }

    public void openCategorySelect(Player staff, OfflinePlayer target) {
        if (useDialogs()) {
            Optional<PunishUi> dialog = dialogUi();
            if (dialog.isPresent()) {
                try {
                    dialog.get().openCategorySelect(staff, target);
                    return;
                } catch (Throwable t) {
                    logFallbackOnce(t);
                }
            }
        }
        inventoryUi.openCategorySelect(staff, target);
    }

    public void openPresetSelect(Player staff, OfflinePlayer target, ret.tawny.controlbans.services.EscalationService.Category category) {
        if (useDialogs()) {
            Optional<PunishUi> dialog = dialogUi();
            if (dialog.isPresent()) {
                try {
                    dialog.get().openPresetSelect(staff, target, category);
                    return;
                } catch (Throwable t) {
                    logFallbackOnce(t);
                }
            }
        }
        inventoryUi.openPresetSelect(staff, target, category);
    }

    private void logFallbackOnce(Throwable t) {
        if (!fallbackLogged) {
            fallbackLogged = true;
            plugin.getLogger().info("[PunishUI] Dialog UI unavailable for this invocation ("
                    + t.getClass().getSimpleName() + (t.getMessage() != null ? ": " + t.getMessage() : "")
                    + ") — falling back to the inventory GUI. Further failures are logged at FINE.");
        } else {
            plugin.getLogger().fine("[PunishUI] Dialog UI failed, inventory fallback used: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Session tracking (used by the dialog callbacks to recover context)
    // ------------------------------------------------------------------

    public void putSession(UUID staffId, PunishSession session) {
        sweepExpired();
        sessions.put(staffId, session);
    }

    public Optional<PunishSession> getSession(UUID staffId) {
        sweepExpired();
        PunishSession session = sessions.get(staffId);
        if (session == null || session.isExpired(System.currentTimeMillis())) {
            sessions.remove(staffId);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public void clearSession(UUID staffId) {
        sessions.remove(staffId);
    }

    public void handleQuit(UUID playerId) {
        sessions.remove(playerId);
    }

    private void sweepExpired() {
        if (sessions.isEmpty()) return;
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PunishSession>> it = sessions.entrySet().iterator();
        while (it.hasNext()) {
            if (it.next().getValue().isExpired(now)) it.remove();
        }
    }
}
