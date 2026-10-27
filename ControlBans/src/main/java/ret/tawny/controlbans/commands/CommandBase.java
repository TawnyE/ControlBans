package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.SchedulerAdapter;
import ret.tawny.controlbans.util.TimeUtil;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public abstract class CommandBase implements CommandExecutor, TabCompleter {

    protected final ControlBansPlugin plugin;
    protected final LocaleManager locale;
    protected final PunishmentService punishmentService;
    protected final SchedulerAdapter scheduler;
    protected String commandName;
    protected String label;

    public CommandBase(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.locale = plugin.getLocaleManager();
        this.punishmentService = plugin.getPunishmentService();
        this.scheduler = plugin.getSchedulerAdapter();
    }

    protected void setCommand(String commandName) {
        this.commandName = commandName;
    }

    public void register() {
        PluginCommand command = Objects.requireNonNull(plugin.getCommand(commandName),
                "Command '" + commandName + "' not found in plugin.yml!");
        command.setExecutor(this);
        command.setTabCompleter(this);
    }

    protected UUID getSenderUuid(CommandSender sender) {
        return (sender instanceof Player) ? ((Player) sender).getUniqueId() : null;
    }

    @Override
    public final boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        this.label = label;
        return execute(sender, args);
    }

    public abstract boolean execute(CommandSender sender, String[] args);

    @Override
    public final List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        return onTab(sender, args);
    }

    public List<String> onTab(CommandSender sender, String[] args) {
        return List.of();
    }

    protected TagResolver playerPlaceholder(String name) {
        return Placeholder.unparsed("player", name);
    }

    protected TagResolver targetPlaceholder(String name) {
        return Placeholder.unparsed("target", name);
    }

    protected TagResolver[] playerAndTargetPlaceholders(String name) {
        return new TagResolver[]{playerPlaceholder(name), targetPlaceholder(name), ipPlaceholder(name)};
    }

    protected TagResolver ipPlaceholder(String ip) {
        return Placeholder.unparsed("ip", ip);
    }

    protected TagResolver staffPlaceholder(String name) {
        return Placeholder.unparsed("staff", name);
    }

    protected TagResolver reasonPlaceholder(String reason) {
        return Placeholder.unparsed("reason", reason != null ? reason : "N/A");
    }

    protected TagResolver durationPlaceholder(String duration) {
        return Placeholder.unparsed("duration", duration);
    }

    protected TagResolver idPlaceholder(String id) {
        return Placeholder.unparsed("id", id);
    }

    protected TagResolver usagePlaceholder(String usage) {
        return Placeholder.unparsed("usage", usage);
    }

    protected TagResolver errorPlaceholder(String error) {
        return Placeholder.unparsed("error", error != null ? error : "Unknown error");
    }

    protected TagResolver typePlaceholder(String type) {
        return Placeholder.unparsed("type", type);
    }

    protected TagResolver datePlaceholder(String date) {
        return Placeholder.unparsed("date", date);
    }

    protected List<String> getPlayerSuggestions(String arg) {
        if (arg == null || arg.isEmpty()) {
            return Bukkit.getOnlinePlayers().stream()
                    .map(Player::getName)
                    .collect(Collectors.toList());
        }

        String query = arg.toLowerCase();

        List<String> suggestions = Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(query))
                .collect(Collectors.toCollection(ArrayList::new));

        List<String> cachedOffline = plugin.getCacheService().getOfflineSuggestions(query);
        if (cachedOffline != null) {
            for (String name : cachedOffline) {
                if (!suggestions.contains(name)) suggestions.add(name);
            }
        } else {
            plugin.getStorage().getNamesStartingWith(query).thenAccept(names -> {
                plugin.getCacheService().cacheOfflineSuggestions(query, names);
            });
        }

        return suggestions;
    }

    protected List<String> filterSuggestions(List<String> options, String arg) {
        String query = arg == null ? "" : arg.toLowerCase();
        return options.stream()
                .filter(s -> s.toLowerCase().startsWith(query))
                .collect(Collectors.toList());
    }

    protected List<String> getTimeSuggestions(String arg) {
        return Stream.of("30m", "1h", "12h", "1d", "7d", "30d")
                .filter(s -> s.toLowerCase().startsWith(arg.toLowerCase()))
                .collect(Collectors.toList());
    }

    protected List<String> getIpTimeSuggestions(String arg) {
        return Stream.of("perm", "30m", "1h", "12h", "1d", "7d", "30d")
                .filter(s -> s.toLowerCase().startsWith(arg.toLowerCase()))
                .collect(Collectors.toList());
    }

    /**
     * Detect silent flag anywhere in first two positions for user-friendliness.
     * Supports both "/ban -s player" and "/ban player -s" style was requested,
     * but we keep canonical "-s as first arg" while also detecting second position
     * to avoid silent punishments being issued non-silently by mistake.
     */
    protected boolean detectSilentFlag(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("-s")) return true;
        if (args.length > 1 && args[1].equalsIgnoreCase("-s")) return true;
        return false;
    }

    protected int resolveTargetIndex(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("-s")) return 1;
        // "/ban player -s" — treat -s as silent but not as target
        return 0;
    }

    protected String[] stripSilentFlag(String[] args) {
        if (args.length > 0 && args[0].equalsIgnoreCase("-s")) {
            String[] out = new String[args.length - 1];
            System.arraycopy(args, 1, out, 0, out.length);
            return out;
        }
        if (args.length > 1 && args[1].equalsIgnoreCase("-s")) {
            List<String> list = new ArrayList<>(List.of(args));
            list.remove(1);
            return list.toArray(new String[0]);
        }
        return args;
    }

    protected void handlePunishmentError(Throwable throwable, CommandSender sender, String targetName) {
        if (throwable instanceof CompletionException) {
            throwable = throwable.getCause();
        }

        if (throwable instanceof IllegalArgumentException && "Player not found".equals(throwable.getMessage())) {
            sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName)));
            return;
        }

        String errorMsg = throwable != null ? throwable.getMessage() : "Unknown error";

        if (errorMsg != null && containsMiniMessageTags(errorMsg)) {
            Component parsedMessage = MiniMessage.miniMessage().deserialize(errorMsg);
            sender.sendMessage(parsedMessage);
        } else {
            sender.sendMessage(Component.text("Error: " + errorMsg, NamedTextColor.RED));
        }

        if (throwable != null) {
            if (!(throwable instanceof IllegalArgumentException) && !(throwable instanceof IllegalStateException)) {
                plugin.getLogger().warning("Unexpected error during command execution: " + throwable.getMessage());
                plugin.getLogger().log(java.util.logging.Level.FINE, "Stack trace for: " + commandName, throwable);
            }
        }
    }

    protected record MultiTargetResult(List<String> targets, int reasonStart) {}

    protected MultiTargetResult parseMultiTargets(String[] args, int targetIndex, boolean hasDuration) {
        List<String> targets = new ArrayList<>();
        int reasonStart = args.length;

        if (hasDuration) {
            // Fast path: single-target fixed positions "/tempban Steve 1m [reason]".
            // This avoids misclassifying reason words when the common case applies.
            if (args.length > targetIndex + 1) {
                try {
                    TimeUtil.parseDuration(args[targetIndex + 1]);
                    targets.add(args[targetIndex]);
                    reasonStart = targetIndex + 2;
                    return new MultiTargetResult(targets, reasonStart);
                } catch (IllegalArgumentException ignored) {
                    // Not single-target with valid duration — fall through to multi scan.
                }
            }
            // Multi-target scan: first parseable token ends targets.
            // Format: <player...> <time> [reason]. Reason-before-duration is
            // invalid usage and is not supported.
            for (int i = targetIndex; i < args.length; i++) {
                String token = args[i];
                try {
                    TimeUtil.parseDuration(token);
                    reasonStart = i + 1;
                    break;
                } catch (IllegalArgumentException e) {
                    targets.add(token);
                }
            }
            if (reasonStart > args.length || targets.isEmpty() && reasonStart <= targetIndex + 1) {
                // No duration found — single-target fallback so callers surface
                // invalid-duration (durationStr = last token attempt).
                targets.clear();
                if (targetIndex < args.length) targets.add(args[targetIndex]);
                reasonStart = targetIndex + 1;
                if (args.length > targetIndex + 1) {
                    // durationStr will be args[targetIndex+1] which failed parse;
                    // reason starts after it to preserve notes for the error path.
                    reasonStart = targetIndex + 2;
                }
            }
        } else {
            // Non-duration commands: "<player> [reason]" or comma-separated multi-target.
            if (targetIndex < args.length) {
                String first = args[targetIndex];
                // Comma-separated multi-target: "/ban a,b,c reason..."
                if (first.contains(",")) {
                    for (String part : first.split(",")) {
                        String t = part.trim();
                        if (!t.isEmpty()) targets.add(t);
                    }
                    reasonStart = targetIndex + 1;
                } else {
                    // Single-target default: first arg is target, rest is reason.
                    // (Multi-target without commas is ambiguous and not supported —
                    // use BulkPunishment instead.)
                    targets.add(first);
                    reasonStart = targetIndex + 1;
                }
            }
        }
        return new MultiTargetResult(targets, reasonStart);
    }

    public static boolean containsMiniMessageTags(String text) {
        if (text == null) return false;
        return text.contains("<#") || text.contains("<red>") || text.contains("<green>") ||
                text.contains("<blue>") || text.contains("<yellow>") || text.contains("<gray>") ||
                text.contains("<gradient:") || text.contains("<bold>") || text.contains("<italic>");
    }
}