package ret.tawny.controlbans.commands;

import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.List;
import java.util.StringJoiner;
import java.util.concurrent.CompletionException;

public class WarnCommand extends CommandBase {

    public WarnCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("warn");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.warn")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        boolean silent = args[0].equalsIgnoreCase("-s");
        int targetIndex = silent ? 1 : 0;

        if (args.length <= targetIndex) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [-s] <player> [reason]")));
            return true;
        }

        String targetName = args[targetIndex];
        StringJoiner reasonJoiner = new StringJoiner(" ");
        for (int i = targetIndex + 1; i < args.length; i++) {
            reasonJoiner.add(args[i]);
        }
        String reason = reasonJoiner.toString().isEmpty() ? null : reasonJoiner.toString();

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

        punishmentService.warnPlayer(targetName, reason, getSenderUuid(sender), sender.getName(), silent)
                .whenComplete((unused, throwable) -> {
                    if (throwable != null) {
                        handlePunishmentError(throwable, sender, targetName);
                    } else {
                        sender.sendMessage(locale.getMessage("success.warn", playerPlaceholder(targetName)));
                    }
                });
        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        boolean isSilent = args.length > 0 && args[0].equalsIgnoreCase("-s");
        int targetIndex = isSilent ? 1 : 0;
        int argIndex = args.length - 1;

        if (argIndex == targetIndex) {
            return getPlayerSuggestions(args[argIndex]);
        }
        if (argIndex == targetIndex + 1) {
            return getTemplateSuggestions(args[argIndex]);
        }
        return List.of();
    }
}