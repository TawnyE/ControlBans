package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ControlBansCommand extends CommandBase {

    public ControlBansCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("controlbans");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <reload|import>")));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "import" -> handleImport(sender, args);
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <reload|import>")));
        }
        return true;
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("controlbans.admin")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        plugin.reload();
        sender.sendMessage(locale.getMessage("success.reload"));
    }

    private void handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.import")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " import <essentials|litebans>")));
            return;
        }
        switch(args[1].toLowerCase()) {
            case "essentials" -> plugin.getImportService().importFromEssentials(sender);
            case "litebans" -> plugin.getImportService().importFromLiteBans(sender);
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " import <essentials|litebans>")));
        }
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Stream.of("reload", "import")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("import")) {
            return Stream.of("essentials", "litebans")
                    .filter(s -> s.startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}