package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
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
        PluginCommand command = Objects.requireNonNull(plugin.getCommand(commandName), "Command '" + commandName + "' not found in plugin.yml!");
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

    // Placeholder helpers
    protected TagResolver playerPlaceholder(String name) { return Placeholder.unparsed("player", name); }
    protected TagResolver staffPlaceholder(String name) { return Placeholder.unparsed("staff", name); }
    protected TagResolver reasonPlaceholder(String reason) { return Placeholder.unparsed("reason", reason != null ? reason : "N/A"); }
    protected TagResolver durationPlaceholder(String duration) { return Placeholder.unparsed("duration", duration); }
    protected TagResolver idPlaceholder(String id) { return Placeholder.unparsed("id", id); }
    protected TagResolver usagePlaceholder(String usage) { return Placeholder.unparsed("usage", usage); }
    protected TagResolver typePlaceholder(String type) { return Placeholder.unparsed("type", type); }
    protected TagResolver datePlaceholder(String date) { return Placeholder.unparsed("date", date); }


    protected List<String> getPlayerSuggestions(String arg) {
        return Bukkit.getOnlinePlayers().stream()
                .map(Player::getName)
                .filter(name -> name.toLowerCase().startsWith(arg.toLowerCase()))
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
}