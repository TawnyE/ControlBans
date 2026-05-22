package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.services.DataExportService;
import ret.tawny.controlbans.util.TimeUtil;

import java.io.File;
import java.util.List;
import java.util.UUID;
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
            sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " <reload|import|export|settings|database|rollback>")));
            return true;
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> handleReload(sender);
            case "import" -> handleImport(sender, args);
            case "export" -> handleExport(sender, args);
            case "database" -> handleDatabase(sender, args);
            case "rollback" -> handleRollback(sender, args);
            case "settings" -> {
                if (sender instanceof Player player) {
                    if (!player.hasPermission("controlbans.admin")) {
                        player.sendMessage(locale.getMessage("errors.no-permission"));
                        return true;
                    }
                    plugin.getSettingsGuiManager().openSettingsGui(player);
                } else {
                    sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
                }
            }
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " <reload|import|export|settings|database|rollback>")));
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

    private void handleRollback(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.admin.rollback")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 3) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " rollback <staff_name> <time>")));
            return;
        }

        String staffName = args[1];
        String timeStr = args[2];
        long durationMillis;
        try {
            durationMillis = TimeUtil.parseDuration(timeStr) * 1000L;
        } catch (IllegalArgumentException e) {
            sender.sendMessage(locale.getMessage("errors.invalid-duration"));
            return;
        }

        long cutoffTime = System.currentTimeMillis() - durationMillis;
        sender.sendMessage(locale.getMessage("admin.rollback.start",
                Placeholder.unparsed("staff", staffName),
                Placeholder.unparsed("duration", timeStr)));

        plugin.getPunishmentService().getAllPunishments().whenComplete((punishments, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("admin.rollback.error"));
                return;
            }

            List<Punishment> toRollback = punishments.stream()
                    .filter(p -> p.isActive() && p.getStaffName() != null && p.getStaffName().equalsIgnoreCase(staffName))
                    .filter(p -> p.getCreatedTime() >= cutoffTime)
                    .toList();

            if (toRollback.isEmpty()) {
                sender.sendMessage(locale.getMessage("admin.rollback.none-found"));
                return;
            }

            sender.sendMessage(locale.getMessage("admin.rollback.found",
                    Placeholder.unparsed("count", String.valueOf(toRollback.size()))));

            UUID senderUuid = getSenderUuid(sender);
            String senderName = sender.getName();

            for (Punishment punishment : toRollback) {
                if (punishment.getType().isBan()) {
                    if (punishment.isIpBan()) {
                        plugin.getPunishmentService().unbanIp(punishment.getTargetIp(), senderUuid, senderName);
                    } else {
                        plugin.getPunishmentService().unbanPlayer(punishment.getTargetName(), senderUuid, senderName);
                    }
                } else if (punishment.getType().isMute()) {
                    plugin.getPunishmentService().unmutePlayer(punishment.getTargetName(), senderUuid, senderName);
                } else if (punishment.getType() == PunishmentType.VOICEMUTE || punishment.getType() == PunishmentType.TEMPVOICEMUTE) {
                    plugin.getPunishmentService().unVoiceMutePlayer(punishment.getTargetName(), senderUuid, senderName);
                }
            }

            sender.sendMessage(locale.getMessage("admin.rollback.success",
                    Placeholder.unparsed("count", String.valueOf(toRollback.size()))));
        });
    }

    private void handleExport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.admin.export")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }

        DataExportService exportService = plugin.getDataExportService();
        if (exportService == null) {
            sender.sendMessage(locale.getMessage("admin.export.not-available"));
            return;
        }

        sender.sendMessage(locale.getMessage("admin.export.start"));

        exportService.exportAll().whenComplete((file, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("admin.export.failed", Placeholder.unparsed("error", throwable.getMessage())));
                return;
            }
            if (file != null) {
                sender.sendMessage(locale.getMessage("admin.export.success",
                        Placeholder.unparsed("file", file.getName()),
                        Placeholder.unparsed("path", "plugins/ControlBans/exports/")));
            } else {
                sender.sendMessage(locale.getMessage("admin.export.failed", Placeholder.unparsed("error", "File could not be created.")));
            }
        });
    }

    private void handleDatabase(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.database")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 2 || !args[1].equalsIgnoreCase("clear")) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " database clear [player]")));
            return;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
            return;
        }

        boolean clearAll = args.length == 2;
        String target = clearAll ? "ALL DATA" : args[2];

        player.sendMessage(locale.getMessage(clearAll ? "admin.database.confirm-clear-all" : "admin.database.confirm-clear",
                Placeholder.unparsed("target", target)));
        player.sendMessage(locale.getMessage("admin.database.instruction"));

        plugin.getChatInputListener().awaitInput(player, input -> {
            if (input.equalsIgnoreCase("yes")) {
                player.sendMessage(locale.getMessage("admin.database.clearing"));
                if (clearAll) {
                    punishmentService.clearAllData().whenComplete((v, t) -> {
                        if (t != null) {
                            player.sendMessage(locale.getMessage("admin.database.error"));
                            t.printStackTrace();
                        } else {
                            player.sendMessage(locale.getMessage("admin.database.success"));
                        }
                    });
                } else {
                    punishmentService.clearPlayerData(target).whenComplete((success, t) -> {
                        if (t != null) {
                            player.sendMessage(locale.getMessage("admin.database.error"));
                            t.printStackTrace();
                        } else if (success) {
                            player.sendMessage(locale.getMessage("admin.database.player-success", Placeholder.unparsed("player", target)));
                        } else {
                            player.sendMessage(locale.getMessage("admin.database.player-not-found"));
                        }
                    });
                }
            } else {
                player.sendMessage(locale.getMessage("admin.database.cancelled"));
            }
        });
    }

    private void handleImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.import")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }
        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " import <essentials|litebans|advancedban|file <filename>>")));
            return;
        }

        switch (args[1].toLowerCase()) {
            case "essentials" -> plugin.getImportService().importFromEssentials(sender);
            case "litebans" -> plugin.getImportService().importFromLiteBans(sender);
            case "advancedban" -> plugin.getImportService().importFromAdvancedBan(sender);
            case "file" -> handleFileImport(sender, args);
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " import <essentials|litebans|advancedban|file <filename>>")));
        }
    }

    private void handleFileImport(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.admin.import")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return;
        }

        DataExportService exportService = plugin.getDataExportService();
        if (exportService == null) {
            sender.sendMessage(locale.getMessage("admin.export.not-available"));
            return;
        }

        if (args.length < 3) {
            List<File> files = exportService.listExportFiles();
            if (files.isEmpty()) {
                sender.sendMessage(locale.getMessage("admin.import.no-files"));
            } else {
                sender.sendMessage(locale.getMessage("admin.import.available-files"));
                for (File file : files) {
                    sender.sendMessage(locale.getMessage("admin.import.file-entry", Placeholder.unparsed("file", file.getName())));
                }
                sender.sendMessage(locale.getMessage("admin.import.file-usage", usagePlaceholder("/" + label + " import file <filename>")));
            }
            return;
        }

        String filename = args[2];
        if (!filename.endsWith(".json")) {
            filename += ".json";
        }
        String sanitizedFilename = filename.replace("/", "").replace("\\", "");
        if (sanitizedFilename.contains("..")) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " import file <filename>")));
            return;
        }

        File importFile = new File(plugin.getDataFolder(), "exports/" + sanitizedFilename);
        if (!importFile.exists()) {
            sender.sendMessage(locale.getMessage("admin.import.file-not-found", Placeholder.unparsed("file", sanitizedFilename)));
            return;
        }

        sender.sendMessage(locale.getMessage("admin.import.start", Placeholder.unparsed("file", filename)));

        exportService.importFromFile(importFile).whenComplete((result, throwable) -> {
            if (throwable != null) {
                sender.sendMessage(locale.getMessage("admin.import.failed", Placeholder.unparsed("error", throwable.getMessage())));
                return;
            }
            if (result.success()) {
                sender.sendMessage(locale.getMessage("admin.import.success", Placeholder.unparsed("count", String.valueOf(result.imported()))));
                if (result.failed() > 0) {
                    sender.sendMessage(locale.getMessage("admin.import.failed-count", Placeholder.unparsed("count", String.valueOf(result.failed()))));
                }
            } else {
                sender.sendMessage(locale.getMessage("admin.import.failed", Placeholder.unparsed("error", result.message())));
            }
        });
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return Stream.of("reload", "import", "export", "settings", "database", "rollback")
                    .filter(s -> s.startsWith(args[0].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("import")) {
                return Stream.of("essentials", "litebans", "advancedban", "file")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("database")) {
                return Stream.of("clear")
                        .filter(s -> s.startsWith(args[1].toLowerCase()))
                        .collect(Collectors.toList());
            }
            if (args[0].equalsIgnoreCase("rollback")) {
                return getPlayerSuggestions(args[1]);
            }
        }
        if (args.length == 3) {
            if (args[0].equalsIgnoreCase("database") && args[1].equalsIgnoreCase("clear")) {
                return getPlayerSuggestions(args[2]);
            }
            if (args[0].equalsIgnoreCase("import") && args[1].equalsIgnoreCase("file")) {
                DataExportService exportService = plugin.getDataExportService();
                if (exportService != null) {
                    return exportService.listExportFiles().stream()
                            .map(File::getName)
                            .filter(name -> name.startsWith(args[2]))
                            .collect(Collectors.toList());
                }
            }
            if (args[0].equalsIgnoreCase("rollback")) {
                return getTimeSuggestions(args[2]);
            }
        }
        return List.of();
    }
}
