package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;

public class TempBanCommand extends CommandBase {

    public TempBanCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("tempban");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.tempban")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 2) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;

        if (args.length <= targetIndex + 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> <time> [reason]")));
            return true;
        }

        String targetName = args[targetIndex];
        String durationStr = args[targetIndex + 1];

        // Case 1: Duration string is a template trigger (starts with #)
        if (durationStr.startsWith("#")) {
            var templateService = punishmentService.getTemplateService();
            StringJoiner reasonJoiner = new StringJoiner(" ");
            for (int i = targetIndex + 1; i < args.length; i++) {
                reasonJoiner.add(args[i]);
            }
            String reason = reasonJoiner.toString();

            var templateOpt = templateService.findTemplate(reason);
            if (templateOpt.isPresent()) {
                var template = templateOpt.get();
                if (template.getPermission() != null && !sender.hasPermission(template.getPermission())) {
                    sender.sendMessage(locale.getMessage("errors.no-permission"));
                    return true;
                }
                String customReason = reason.replace("#" + template.getKey(), "").trim();
                if (customReason.equalsIgnoreCase(reason)) {
                    customReason = reason.replaceAll("(?i)#" + template.getKey(), "").trim();
                }
                final String notes = customReason.isEmpty() ? null : customReason;
                punishmentService.applyTemplatePunishment(targetName, template, notes, getSenderUuid(sender), sender.getName(), silent)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, targetName);
                        } else {
                            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<green>Applied template </green>" + template.getDisplayName() + "<green> to " + targetName + "</green>"));
                        }
                    });
                return true;
            }
        }

        long duration;
        try {
            duration = TimeUtil.parseDuration(durationStr);
        } catch (IllegalArgumentException e) {
            sender.sendMessage(locale.getMessage("errors.invalid-duration"));
            return true;
        }

        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 2; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

        // Case 2: Reason is a template trigger
        if (reason != null) {
            var templateOpt = punishmentService.getTemplateService().findTemplate(reason);
            if (templateOpt.isPresent()) {
                var template = templateOpt.get();
                if (template.getPermission() != null && !sender.hasPermission(template.getPermission())) {
                    sender.sendMessage(locale.getMessage("errors.no-permission"));
                    return true;
                }
                String customReason = reason.replace("#" + template.getKey(), "").trim();
                if (customReason.equalsIgnoreCase(reason)) {
                    customReason = reason.replaceAll("(?i)#" + template.getKey(), "").trim();
                }
                final String notes = customReason.isEmpty() ? null : customReason;
                punishmentService.applyTemplatePunishment(targetName, template, notes, getSenderUuid(sender), sender.getName(), silent)
                    .whenComplete((unused, throwable) -> {
                        if (throwable != null) {
                            handlePunishmentError(throwable, sender, targetName);
                        } else {
                            sender.sendMessage(net.kyori.adventure.text.minimessage.MiniMessage.miniMessage().deserialize("<green>Applied template </green>" + template.getDisplayName() + "<green> to " + targetName + "</green>"));
                        }
                    });
                return true;
            }
        }

        punishmentService.tempBanPlayer(targetName, duration, reason, getSenderUuid(sender), sender.getName(), silent, false)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        handlePunishmentError(throwable, sender, targetName);
                    } else {
                        sender.sendMessage(locale.getMessage("success.tempban",
                                playerPlaceholder(targetName),
                                durationPlaceholder(TimeUtil.formatDuration(duration))
                        ));
                    }
                });
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        boolean isSilent = args.length > 0 && args[0].equalsIgnoreCase("-s");
        int argIndex = args.length - 1;
        String currentArg = args[argIndex];

        int targetIndex = isSilent ? 1 : 0;
        if (argIndex == targetIndex) {
            return getPlayerSuggestions(currentArg);
        }

        int timeIndex = isSilent ? 2 : 1;
        if (argIndex == timeIndex) {
            if (currentArg.startsWith("#")) {
                return getTemplateSuggestions(currentArg);
            }
            List<String> list = new java.util.ArrayList<>(getTimeSuggestions(currentArg));
            if ("#".startsWith(currentArg)) {
                list.add("#");
            }
            return list;
        }

        if (argIndex == timeIndex + 1) {
            return getTemplateSuggestions(currentArg);
        }
        return List.of();
    }
}