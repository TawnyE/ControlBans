package ret.tawny.controlbans.ui.dialog;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.dialog.DialogResponseView;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.audience.Audience;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.EscalationService;
import ret.tawny.controlbans.ui.PunishSession;
import ret.tawny.controlbans.ui.PunishUi;
import ret.tawny.controlbans.ui.PunishUiManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.StringJoiner;

/**
 * The /punish UI implemented with Paper 1.21.6+ dialogs.
 *
 * <p><b>This class must never be class-loaded on servers without the dialog
 * API</b> — it is instantiated reflectively by {@link PunishUiManager} only
 * after the {@code io.papermc.paper.dialog.Dialog} probe succeeds.</p>
 */
public class DialogPunishUi implements PunishUi {

    private static final int BUTTON_WIDTH = 250;
    private static final String NOTES_INPUT_KEY = "cb_notes";

    private final ControlBansPlugin plugin;

    public DialogPunishUi(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void openCategorySelect(Player staff, OfflinePlayer target) {
        Component title = plugin.getLocaleManager().getMessage("gui.punish-dialog.category-title",
                Placeholder.unparsed("player", targetName(target)));
        Component body = plugin.getLocaleManager().getMessage("gui.punish-dialog.category-body");

        ActionButton textButton = ActionButton.builder(buttonLabel("category-text"))
                .tooltip(plugin.getLocaleManager().getMessage("gui.punish-dialog.category-text-tooltip"))
                .width(BUTTON_WIDTH)
                .action(customClick((view, audience) -> chooseCategory(audience, target, EscalationService.Category.TEXT)))
                .build();
        ActionButton voiceButton = ActionButton.builder(buttonLabel("category-voice"))
                .tooltip(plugin.getLocaleManager().getMessage("gui.punish-dialog.category-voice-tooltip"))
                .width(BUTTON_WIDTH)
                .action(customClick((view, audience) -> chooseCategory(audience, target, EscalationService.Category.VOICE)))
                .build();
        ActionButton bothButton = ActionButton.builder(buttonLabel("category-both"))
                .tooltip(plugin.getLocaleManager().getMessage("gui.punish-dialog.category-both-tooltip"))
                .width(BUTTON_WIDTH)
                .action(customClick((view, audience) -> chooseCategory(audience, target, EscalationService.Category.BOTH)))
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.item(warningIcon()).build(), DialogBody.plainMessage(body)))
                        .build())
                .type(DialogType.multiAction(List.of(textButton, voiceButton, bothButton))
                        .columns(2)
                        .build()));

        staff.showDialog(dialog);
    }

    @Override
    public void openPresetSelect(Player staff, OfflinePlayer target, EscalationService.Category category) {
        List<EscalationService.Preset> presets = plugin.getEscalationService().getPresets().stream()
                .filter(preset -> preset.targets().contains(category))
                .toList();
        if (presets.isEmpty()) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("gui.punish-dialog.no-presets"));
            return;
        }

        Component title = plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-title",
                Placeholder.unparsed("player", targetName(target)),
                Placeholder.parsed("category", plugin.getLocaleManager()
                        .getRawMessage("gui.punish-dialog.category-" + category.name().toLowerCase(java.util.Locale.ROOT))));
        Component body = plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-body");

        List<ActionButton> buttons = new ArrayList<>();
        for (EscalationService.Preset preset : presets) {
            if (preset.permission() != null && !preset.permission().isEmpty() && !staff.hasPermission(preset.permission())) {
                continue; // permission-locked presets are hidden in the dialog flow
            }
            Component tooltip = presetTooltip(preset);
            buttons.add(ActionButton.builder(MiniMessage.miniMessage().deserialize(preset.displayName()))
                    .tooltip(tooltip)
                    .width(BUTTON_WIDTH)
                    .action(customClick((view, audience) -> applyPreset(audience, preset, view)))
                    .build());
        }
        if (buttons.isEmpty()) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("gui.punish-dialog.no-presets"));
            return;
        }

        DialogInput notesInput = DialogInput.text(NOTES_INPUT_KEY,
                plugin.getLocaleManager().getMessage("gui.punish-dialog.notes-prompt"))
                .maxLength(256)
                .build();

        Dialog dialog = Dialog.create(factory -> factory.empty()
                .base(DialogBase.builder(title)
                        .canCloseWithEscape(true)
                        .body(List.of(DialogBody.item(warningIcon()).build(), DialogBody.plainMessage(body)))
                        .inputs(List.of(notesInput))
                        .build())
                .type(DialogType.multiAction(buttons)
                        .columns(2)
                        .build()));

        staff.showDialog(dialog);
    }

    /** The small warning icon shown at the top of the punishment dialogs. */
    private org.bukkit.inventory.ItemStack warningIcon() {
        org.bukkit.inventory.ItemStack stack = new org.bukkit.inventory.ItemStack(org.bukkit.Material.YELLOW_DYE);
        org.bukkit.inventory.meta.ItemMeta meta = stack.getItemMeta();
        if (meta != null) {
            meta.displayName(MiniMessage.miniMessage().deserialize(
                    plugin.getLocaleManager().getRawMessage("gui.punish-dialog.warning-icon")));
            stack.setItemMeta(meta);
        }
        return stack;
    }

    private void chooseCategory(Audience audience, OfflinePlayer target, EscalationService.Category category) {
        if (!(audience instanceof Player staff) || !staff.isOnline()) return;
        if (!staff.hasPermission("controlbans.punish")) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
            return;
        }
        plugin.getPunishUiManager().putSession(staff.getUniqueId(), new PunishSession(
                target.getUniqueId(), targetName(target), category, System.currentTimeMillis()));
        openPresetSelect(staff, target, category);
    }

    private void applyPreset(Audience audience, EscalationService.Preset preset, DialogResponseView view) {
        if (!(audience instanceof Player staff) || !staff.isOnline()) return;
        if (!staff.hasPermission("controlbans.punish")) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("errors.no-permission"));
            return;
        }
        Optional<PunishSession> sessionOpt = plugin.getPunishUiManager().getSession(staff.getUniqueId());
        if (sessionOpt.isEmpty()) {
            staff.sendMessage(plugin.getLocaleManager().getMessage("gui.punish-dialog.session-expired"));
            return;
        }
        PunishSession session = sessionOpt.get();

        String notes = null;
        if (view != null) {
            try {
                String raw = view.getText(NOTES_INPUT_KEY);
                notes = sanitizeNotes(raw);
            } catch (Exception ignored) {
            }
        }

        plugin.getEscalationService().applyPreset(staff, session.targetName(), preset, session.category(), notes, false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        Throwable cause = throwable.getCause() != null ? throwable.getCause() : throwable;
                        String message = cause.getMessage() != null ? cause.getMessage() : "Unknown error";
                        plugin.getSchedulerAdapter().runTask(() -> {
                            if (staff.isOnline()) {
                                if (ret.tawny.controlbans.commands.CommandBase.containsMiniMessageTags(message)) {
                                    staff.sendMessage(MiniMessage.miniMessage().deserialize(message));
                                } else {
                                    staff.sendMessage(plugin.getLocaleManager().getMessage("escalation.error",
                                            Placeholder.unparsed("error", message)));
                                }
                            }
                        });
                    }
                });
    }

    /** Treats the configured skip keyword as "no notes". */
    private String sanitizeNotes(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        String skip = plugin.getLocaleManager().getRawMessage("gui.punish-dialog.notes-skip");
        if (skip != null && !skip.isBlank() && trimmed.equalsIgnoreCase(skip.trim())) return null;
        return trimmed;
    }

    private Component presetTooltip(EscalationService.Preset preset) {
        List<Component> lines = new ArrayList<>();
        if (preset.description() != null && !preset.description().isBlank()) {
            lines.add(MiniMessage.miniMessage().deserialize("<gray>" + escapeTags(preset.description()) + "</gray>"));
        }
        StringJoiner joiner = new StringJoiner(", ");
        for (EscalationService.ActionSpec action : preset.actions()) {
            joiner.add(EscalationService.describeAction(action));
        }
        lines.add(plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-actions-list",
                Placeholder.unparsed("actions", joiner.toString())));
        lines.add(plugin.getLocaleManager().getMessage("gui.punish-dialog.preset-click-hint"));
        Component joined = Component.empty();
        for (int i = 0; i < lines.size(); i++) {
            if (i > 0) joined = joined.appendNewline();
            joined = joined.append(lines.get(i));
        }
        return joined;
    }

    private Component buttonLabel(String key) {
        return plugin.getLocaleManager().getMessage("gui.punish-dialog." + key);
    }

    private static DialogAction customClick(java.util.function.BiConsumer<DialogResponseView, Audience> handler) {
        return DialogAction.customClick(
                (view, audience) -> handler.accept(view, audience),
                ClickCallback.Options.builder().uses(1).build());
    }

    private static String targetName(OfflinePlayer target) {
        return target.getName() != null ? target.getName() : "Unknown";
    }

    private static String escapeTags(String input) {
        if (input == null) return "";
        return input.replace("<", "\\<");
    }
}
