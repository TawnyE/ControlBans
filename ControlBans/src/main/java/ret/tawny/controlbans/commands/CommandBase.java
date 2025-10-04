package ret.tawny.controlbans.commands;

import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

public abstract class CommandBase implements CommandExecutor, TabCompleter {

    protected final JavaPlugin plugin;
    protected final String commandName;
    protected String label;

    public CommandBase(JavaPlugin plugin, String commandName) {
        this.plugin = plugin;
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
        return List.of(); // Default to no suggestions
    }
}