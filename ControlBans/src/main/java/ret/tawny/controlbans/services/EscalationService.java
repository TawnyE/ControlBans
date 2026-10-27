package ret.tawny.controlbans.services;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigUpdater;
import ret.tawny.controlbans.config.YamlHealth;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.util.TimeUtil;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Escalation presets for /punish: one preset is an "offense package" that can
 * apply multiple punishments at once (e.g. preset "racism" applies a permanent
 * mute AND a permanent voice mute). Staff pick a chat category (TEXT / VOICE /
 * BOTH) in the UI, then a preset; the preset's actions filtered by that
 * category are executed through the regular {@link PunishmentService} punish
 * methods (so dedup/cache/notifications all behave like normal commands).
 *
 * <p>Every applied punishment persists the preset id in the {@code template}
 * DB column via the {@code templateKey} field, keeping history tracking intact.
 */
public class EscalationService {

    public enum Category { TEXT, VOICE, BOTH }

    public record ActionSpec(PunishmentType type, long durationSeconds, boolean permanent) {}

    public record Preset(
            String id,
            String displayName,
            String description,
            Material icon,
            String permission,
            String reason,
            Set<Category> targets,
            List<ActionSpec> actions
    ) {}

    private static final String RESOURCE_NAME = "escalation.yml";

    private final ControlBansPlugin plugin;
    private volatile List<Preset> cachedPresets = List.of();

    public EscalationService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        reload();
    }

    public List<Preset> getPresets() {
        if (!plugin.getConfigManager().isEscalationEnabled()) return List.of();
        List<Preset> presets = cachedPresets;
        return presets != null ? presets : List.of();
    }

    public Optional<Preset> byId(String id) {
        if (id == null || id.isBlank()) return Optional.empty();
        String wanted = id.trim().toLowerCase(Locale.ROOT);
        for (Preset preset : getPresets()) {
            if (preset.id().equals(wanted)) return Optional.of(preset);
        }
        return Optional.empty();
    }

    /** Re-reads escalation.yml (called on boot and /cbreload). */
    public void invalidateCache() {
        reload();
    }

    private synchronized void reload() {
        File file = new File(plugin.getDataFolder(), RESOURCE_NAME);
        if (!file.isFile()) {
            try {
                plugin.saveResource(RESOURCE_NAME, false);
            } catch (IllegalArgumentException e) {
                plugin.getLogger().severe("escalation.yml is missing from the plugin JAR: " + e.getMessage());
                cachedPresets = List.of();
                return;
            }
        }

        try {
            String userText = Files.readString(file.toPath(), StandardCharsets.UTF_8);

            String healthError = YamlHealth.validate(userText);
            if (healthError != null) {
                plugin.getLogger().warning("escalation.yml looks broken (usually a spacing or indentation mistake): " + healthError);
                plugin.getLogger().warning("No escalation presets will be available until the file is fixed.");
                cachedPresets = List.of();
                return;
            }

            // Merge missing preset keys from the bundled default without touching
            // the user's existing content (same text-level merge as config.yml).
            try (InputStream in = plugin.getResource(RESOURCE_NAME)) {
                if (in != null) {
                    String defaultText = new String(in.readAllBytes(), StandardCharsets.UTF_8);
                    String merged = ConfigUpdater.mergeDefaultKeys(userText, defaultText);
                    if (merged != null) {
                        Files.writeString(file.toPath(), merged, StandardCharsets.UTF_8);
                        plugin.getLogger().info("escalation.yml updated with missing preset keys from the bundled default.");
                        userText = merged;
                    }
                }
            }

            YamlConfiguration yaml = YamlConfiguration.loadConfiguration(new StringReader(userText));
            List<String> warnings = new ArrayList<>();
            List<Preset> parsed = parsePresets(yaml.getConfigurationSection("presets"), warnings::add);
            for (String warning : warnings) {
                plugin.getLogger().warning(warning);
            }
            cachedPresets = parsed;
            plugin.getLogger().info("Loaded " + parsed.size() + " escalation preset(s).");
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to load escalation.yml — escalation presets are unavailable.", e);
            cachedPresets = List.of();
        }
    }

    // ------------------------------------------------------------------
    // Parsing (static + package-private so it is unit-testable)
    // ------------------------------------------------------------------

    static List<Preset> parsePresets(ConfigurationSection presetsSection, Consumer<String> warn) {
        List<Preset> presets = new ArrayList<>();
        if (presetsSection == null) {
            warn.accept("[Escalation] escalation.yml is missing the 'presets' section — no presets will be available.");
            return List.of();
        }
        for (String id : presetsSection.getKeys(false)) {
            ConfigurationSection section = presetsSection.getConfigurationSection(id);
            if (section == null) {
                warn.accept("[Escalation] Ignoring junk entry '" + id + "' under presets (expected a preset section).");
                continue;
            }

            String displayName = section.getString("display-name", capitalize(id));
            String description = section.getString("description", "");
            String iconName = section.getString("icon", "PAPER");
            Material icon = iconName == null ? null : Material.matchMaterial(iconName.trim());
            if (icon == null) {
                warn.accept("[Escalation] Preset '" + id + "': unknown icon material '" + iconName
                        + "' — using PAPER. Use a valid Bukkit Material name.");
                icon = Material.PAPER;
            }
            String permission = section.getString("permission", "");
            String reason = section.getString("reason", "");

            Set<Category> targets = EnumSet.noneOf(Category.class);
            for (String rawTarget : section.getStringList("targets")) {
                if (rawTarget == null || rawTarget.isBlank()) continue;
                String target = rawTarget.trim().toLowerCase(Locale.ROOT);
                switch (target) {
                    case "text" -> targets.add(Category.TEXT);
                    case "voice" -> targets.add(Category.VOICE);
                    case "both" -> { targets.add(Category.TEXT); targets.add(Category.VOICE); }
                    default -> warn.accept("[Escalation] Preset '" + id + "': unknown target '" + rawTarget
                            + "' — skipped. Use 'text', 'voice' or 'both'.");
                }
            }
            if (targets.isEmpty()) {
                warn.accept("[Escalation] Preset '" + id + "': no valid targets configured — preset will never show in the UI. Add 'targets: [text]' and/or '[voice]'.");
            }

            List<String> rawActions = section.getStringList("actions");
            if (rawActions.isEmpty()) {
                warn.accept("[Escalation] Preset '" + id + "': has no 'actions' — preset skipped. Add at least one action, e.g. '- \"MUTE\"'.");
                continue;
            }
            List<ActionSpec> actions = new ArrayList<>();
            for (String rawAction : rawActions) {
                ActionSpec spec = parseAction(id, rawAction, warn);
                if (spec != null) actions.add(spec);
            }
            if (actions.isEmpty()) {
                warn.accept("[Escalation] Preset '" + id + "': every action was invalid — preset skipped.");
                continue;
            }

            presets.add(new Preset(
                    id.trim().toLowerCase(Locale.ROOT),
                    displayName != null ? displayName : id,
                    description != null ? description : "",
                    icon,
                    permission == null ? "" : permission,
                    reason == null ? "" : reason,
                    Collections.unmodifiableSet(targets),
                    List.copyOf(actions)
            ));
        }
        return List.copyOf(presets);
    }

    static ActionSpec parseAction(String presetId, String raw, Consumer<String> warn) {
        if (raw == null || raw.isBlank()) {
            warn.accept("[Escalation] Preset '" + presetId + "': found a blank action — skipped.");
            return null;
        }
        String trimmed = raw.trim();
        String[] parts = trimmed.split("\\s+", 2);
        PunishmentType type;
        try {
            type = PunishmentType.valueOf(parts[0].trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            warn.accept("[Escalation] Preset '" + presetId + "': unknown punishment type '" + parts[0]
                    + "' in action '" + trimmed + "' — action skipped. Valid types: BAN, TEMPBAN, IPBAN, MUTE, TEMPMUTE, IPMUTE, WARN, KICK, VOICEMUTE, TEMPVOICEMUTE.");
            return null;
        }

        boolean permanentType = !type.isTemporary();
        if (parts.length == 1) {
            if (permanentType) {
                return new ActionSpec(type, -1L, true);
            }
            warn.accept("[Escalation] Preset '" + presetId + "': action '" + trimmed
                    + "' is a temporary type without a duration — action skipped. Example fix: '" + parts[0] + " 7d'.");
            return null;
        }

        String durationPart = parts[1].trim();
        if (permanentType) {
            warn.accept("[Escalation] Preset '" + presetId + "': permanent type '" + parts[0]
                    + "' must not have a duration ('" + trimmed + "') — action skipped. Use just '" + parts[0] + "'.");
            return null;
        }
        long seconds;
        try {
            seconds = TimeUtil.parseDuration(durationPart);
        } catch (Exception e) {
            warn.accept("[Escalation] Preset '" + presetId + "': could not parse duration '" + durationPart
                    + "' in action '" + trimmed + "' — action skipped. Use formats like 30m, 1h, 7d, 30d.");
            return null;
        }
        return new ActionSpec(type, seconds, false);
    }

    /** VOICE category runs only voice actions; TEXT runs everything else; BOTH runs all. */
    static boolean matchesCategory(ActionSpec action, Category category) {
        boolean voiceAction = action.type() == PunishmentType.VOICEMUTE || action.type() == PunishmentType.TEMPVOICEMUTE;
        return switch (category) {
            case VOICE -> voiceAction;
            case TEXT -> !voiceAction;
            case BOTH -> true;
        };
    }

    private static String capitalize(String input) {
        if (input == null || input.isEmpty()) return input;
        return input.substring(0, 1).toUpperCase(Locale.ROOT) + input.substring(1).toLowerCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Applying presets
    // ------------------------------------------------------------------

    /**
     * Applies every action of the preset whose type matches the chosen
     * category. Actions run sequentially in configured order through the
     * regular {@link PunishmentService} punish methods; a failing action is
     * logged and skipped (e.g. duplicate-guard rejections from same-family
     * actions) without aborting the remaining ones. Each applied punishment
     * stores the preset id in the {@code template} DB column.
     */
    public CompletableFuture<Void> applyPreset(CommandSender staff, String targetName, Preset preset, Category category, String customNotes, boolean silent) {
        if (!plugin.getConfigManager().isEscalationEnabled()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    plugin.getLocaleManager().getRawMessage("gui.punish-dialog.no-presets")));
        }
        if (preset.permission() != null && !preset.permission().isEmpty() && !staff.hasPermission(preset.permission())) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    plugin.getLocaleManager().getRawMessage("gui.punish.no-permission-action")));
        }
        if (targetName == null || targetName.isBlank()) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Player not found"));
        }

        UUID staffUuid = staff instanceof Player p ? p.getUniqueId() : null;
        String staffName = staff.getName();
        String reason = buildReason(preset, customNotes);

        List<ActionSpec> actions = preset.actions().stream()
                .filter(action -> matchesCategory(action, category))
                .toList();
        if (actions.isEmpty()) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "Preset '" + preset.id() + "' has no actions for this chat category"));
        }

        AtomicInteger applied = new AtomicInteger();
        List<String> appliedLabels = Collections.synchronizedList(new ArrayList<>());
        final String[] lastError = { null };

        CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
        for (ActionSpec action : actions) {
            chain = chain.thenCompose(v -> executeAction(targetName, action, reason, staffUuid, staffName, silent, preset.id())
                    .whenComplete((result, throwable) -> {
                        if (throwable == null) {
                            applied.incrementAndGet();
                            appliedLabels.add(describeAction(action));
                        } else {
                            Throwable cause = throwable instanceof java.util.concurrent.CompletionException && throwable.getCause() != null
                                    ? throwable.getCause() : throwable;
                            lastError[0] = cause.getMessage();
                            plugin.getLogger().warning("[Escalation] Preset '" + preset.id() + "': action "
                                    + describeAction(action) + " for " + targetName + " was skipped: " + cause.getMessage());
                        }
                    }).thenApply(x -> null));
        }

        return chain.thenRun(() -> {
            int count = applied.get();
            String presetLabel = preset.displayName() == null || preset.displayName().isEmpty()
                    ? preset.id() : preset.displayName().replaceAll("<[^>]+>", "").replaceAll("&[0-9a-fk-or]", "");
            String actionsSummary = String.join(", ", appliedLabels);
            plugin.getSchedulerAdapter().runTask(() -> {
                boolean online = !(staff instanceof Player p) || p.isOnline();
                if (online) {
                    if (count == 0) {
                        String err = lastError[0] != null ? lastError[0] : "no action could be applied";
                        staff.sendMessage(plugin.getLocaleManager().getMessage("escalation.error",
                                Placeholder.unparsed("error", err)));
                        return;
                    }
                    staff.sendMessage(plugin.getLocaleManager().getMessage("escalation.applied",
                            Placeholder.unparsed("player", targetName),
                            Placeholder.unparsed("preset", presetLabel),
                            Placeholder.unparsed("count", String.valueOf(count))));
                    if (!actionsSummary.isEmpty()) {
                        staff.sendMessage(plugin.getLocaleManager().getMessage("escalation.applied-actions",
                                Placeholder.unparsed("actions", actionsSummary)));
                    }
                }
            });
            plugin.getLogger().info("[Escalation] " + staffName + " applied preset '" + preset.id()
                    + "' (" + category + ") to " + targetName + ": " + count + "/" + actions.size() + " action(s) applied"
                    + (actionsSummary.isEmpty() ? "" : " [" + actionsSummary + "]"));
        });
    }

    private String buildReason(Preset preset, String customNotes) {
        String prefix = capitalize(preset.id());
        String reason = preset.reason() == null ? "" : preset.reason().trim();
        String notes = customNotes == null ? "" : customNotes.trim();
        if (!reason.isEmpty() && !notes.isEmpty()) return prefix + ": " + reason + " | " + notes;
        if (!reason.isEmpty()) return prefix + ": " + reason;
        if (!notes.isEmpty()) return prefix + ": " + notes;
        return prefix;
    }

    private CompletableFuture<?> executeAction(String targetName, ActionSpec action, String reason, UUID staffUuid, String staffName, boolean silent, String presetId) {
        PunishmentService punishmentService = plugin.getPunishmentService();
        long duration = action.permanent() ? -1L : action.durationSeconds();
        return switch (action.type()) {
            case BAN -> punishmentService.banPlayer(targetName, reason, staffUuid, staffName, silent, false, presetId);
            case TEMPBAN -> punishmentService.tempBanPlayer(targetName, duration, reason, staffUuid, staffName, silent, false, presetId);
            case IPBAN -> punishmentService.ipBanPlayer(targetName, -1L, reason, staffUuid, staffName, silent, presetId);
            case TEMPIPBAN -> punishmentService.ipBanPlayer(targetName, duration, reason, staffUuid, staffName, silent, presetId);
            case MUTE -> punishmentService.mutePlayer(targetName, reason, staffUuid, staffName, silent, presetId);
            case TEMPMUTE -> punishmentService.tempMutePlayer(targetName, duration, reason, staffUuid, staffName, silent, presetId);
            case IPMUTE -> punishmentService.ipMutePlayer(targetName, -1L, reason, staffUuid, staffName, silent, presetId);
            case TEMPIPMUTE -> punishmentService.ipMutePlayer(targetName, duration, reason, staffUuid, staffName, silent, presetId);
            case WARN -> punishmentService.warnPlayer(targetName, reason, staffUuid, staffName, silent, presetId);
            case KICK -> punishmentService.kickPlayer(targetName, reason, staffUuid, staffName, silent, presetId);
            case VOICEMUTE -> punishmentService.voiceMutePlayer(targetName, reason, staffUuid, staffName, silent, presetId);
            case TEMPVOICEMUTE -> punishmentService.tempVoiceMutePlayer(targetName, duration, reason, staffUuid, staffName, silent, presetId);
        };
    }

    public static String describeAction(ActionSpec action) {
        if (action.permanent()) {
            return action.type().getDisplayName();
        }
        return action.type().getDisplayName() + " (" + TimeUtil.formatDuration(action.durationSeconds()) + ")";
    }
}
