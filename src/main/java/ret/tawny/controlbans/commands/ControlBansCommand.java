package ret.tawny.controlbans.commands;

import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ControlBansCommand extends CommandBase {
    private final ControlBansPlugin plugin;

    public ControlBansCommand(ControlBansPlugin plugin) {
        super(plugin, "controlbans");
        this.plugin = plugin;
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " <reload|import>");
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "import" -> handleImport(sender, args);
            default -> sender.sendMessage(ChatColor.RED + "Unknown subcommand. Usage: /" + label + " <reload|import>");
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        plugin.reload();
        sender.sendMessage(ChatColor.GREEN + "ControlBans configuration reloaded.");
    }

    private void handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.import")) {
            sender.sendMessage(ChatColor.RED + "You do not have permission.");
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(ChatColor.RED + "Usage: /" + label + " import <vanilla>");
            return;
        }
        if ("vanilla".equalsIgnoreCase(args[1])) {
            plugin.getImportService().importFromVanilla(sender);
        } else {
            sender.sendMessage(ChatColor.RED + "Unknown import type. Available: vanilla");
        }
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Stream.of("reload", "import").filter(s -> s.startsWith(args[0].toLowerCase())).collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return Stream.of("vanilla").filter(s -> s.startsWith(args[1].toLowerCase())).collect(Collectors.toList());
        }
        return List.of();
    }
}