package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.ScheduledPunishment;
import ret.tawny.controlbans.services.ScheduledService;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class ScheduledPunishmentCommand extends CommandBase {

    private final ScheduledService scheduledService;

    public ScheduledPunishmentCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("scheduledpunishment");
        this.scheduledService = plugin.getScheduledService();
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.scheduled")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " <list|cancel|check> [args]")));
            return true;
        }

        String sub = args[0].toLowerCase();
        switch (sub) {
            case "list" -> handleList(sender);
            case "cancel" -> {
                if (args.length < 2) {
                    sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                            usagePlaceholder("/" + label + " cancel <id>")));
                    return true;
                }
                handleCancel(sender, args[1]);
            }
            case "check" -> {
                if (args.length < 2) {
                    sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                            usagePlaceholder("/" + label + " check <id>")));
                    return true;
                }
                handleCheck(sender, args[1]);
            }
            default -> sender.sendMessage(locale.getMessage("errors.invalid-arguments",
                    usagePlaceholder("/" + label + " <list|cancel|check> [args]")));
        }
        return true;
    }

    private void handleList(CommandSender sender) {
        List<ScheduledPunishment> list = scheduledService.getPendingPunishments();
        if (list.isEmpty()) {
            sender.sendMessage(locale.getMessage("scheduled.list-empty"));
            return;
        }
        sender.sendMessage(locale.getMessage("scheduled.list-header",
                Placeholder.unparsed("count", String.valueOf(list.size()))));
        for (ScheduledPunishment sp : list) {
            sender.sendMessage(locale.getMessage("scheduled.list-entry",
                    Placeholder.unparsed("id", sp.scheduleId()),
                    Placeholder.unparsed("type", sp.action().name()),
                    Placeholder.unparsed("target", sp.targetName()),
                    Placeholder.unparsed("time", TimeUtil.formatDate(sp.executeAt()))));
        }
    }

    private void handleCancel(CommandSender sender, String id) {
        scheduledService.cancelScheduled(id.toUpperCase()).thenAccept(opt -> {
            if (opt.isPresent()) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("scheduled.cancelled",
                        Placeholder.unparsed("id", id.toUpperCase()))));
            } else {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("scheduled.not-found",
                        Placeholder.unparsed("id", id.toUpperCase()))));
            }
        });
    }

    private void handleCheck(CommandSender sender, String id) {
        Optional<ScheduledPunishment> opt = scheduledService.getById(id.toUpperCase());
        if (opt.isEmpty()) {
            sender.sendMessage(locale.getMessage("scheduled.not-found",
                    Placeholder.unparsed("id", id.toUpperCase())));
            return;
        }
        ScheduledPunishment sp = opt.get();
        sender.sendMessage(locale.getMessage("scheduled.check-header",
                Placeholder.unparsed("id", sp.scheduleId())));
        sender.sendMessage(locale.getMessage("scheduled.check-type",
                Placeholder.unparsed("type", sp.action().name())));
        sender.sendMessage(locale.getMessage("scheduled.check-target",
                Placeholder.unparsed("target", sp.targetName())));
        if (sp.reason() != null) {
            sender.sendMessage(locale.getMessage("scheduled.check-reason",
                    Placeholder.unparsed("reason", sp.reason())));
        }
        sender.sendMessage(locale.getMessage("scheduled.check-time",
                Placeholder.unparsed("time", TimeUtil.formatDate(sp.executeAt()))));
        sender.sendMessage(locale.getMessage("scheduled.check-staff",
                Placeholder.unparsed("staff", sp.staffName())));
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            String prefix = args[0].toLowerCase();
            return Stream.of("list", "cancel", "check")
                    .filter(s -> s.startsWith(prefix))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("cancel")) {
            return scheduledService.getPendingPunishments().stream()
                    .map(ScheduledPunishment::scheduleId)
                    .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("check")) {
            return scheduledService.getPendingPunishments().stream()
                    .map(ScheduledPunishment::scheduleId)
                    .filter(id -> id.toLowerCase().startsWith(args[1].toLowerCase()))
                    .collect(Collectors.toList());
        }
        return List.of();
    }
}
