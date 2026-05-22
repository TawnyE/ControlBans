package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

public class MutelistCommand extends CommandBase {

    private static final int ENTRIES_PER_PAGE = 10;

    public MutelistCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("mutelist");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.mutelist")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        int page = 1;
        if (args.length > 0) {
            try {
                page = Integer.parseInt(args[0]);
                if (page < 1) page = 1;
            } catch (NumberFormatException ignored) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " [page]")));
                return true;
            }
        }

        final int finalPage = page;

        punishmentService.getAllPunishments().thenAccept(allPunishments -> {
            List<Punishment> activeMutes = allPunishments.stream()
                    .filter(p -> p.getType().isMute() && p.isActive() && !p.isExpired())
                    .sorted((a, b) -> Long.compare(b.getCreatedTime(), a.getCreatedTime()))
                    .toList();

            int maxPage = Math.max(1, (int) Math.ceil((double) activeMutes.size() / ENTRIES_PER_PAGE));
            int currentPage = finalPage > maxPage ? maxPage : finalPage;

            int startIndex = (currentPage - 1) * ENTRIES_PER_PAGE;
            int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, activeMutes.size());

            List<Component> lines = new ArrayList<>();
            lines.add(locale.getMessage("mutelist.header",
                    Placeholder.unparsed("count", String.valueOf(activeMutes.size()))));
            lines.add(Component.empty());

            if (activeMutes.isEmpty()) {
                lines.add(locale.getMessage("mutelist.empty"));
            } else {
                for (int i = startIndex; i < endIndex; i++) {
                    Punishment p = activeMutes.get(i);
                    String reason = p.getReason() != null ? p.getReason() : "Unspecified";
                    String staff = p.getStaffName() != null ? p.getStaffName() : "Console";
                    lines.add(locale.getMessage("mutelist.entry",
                            Placeholder.unparsed("player", p.getTargetName() != null ? p.getTargetName() : "Unknown"),
                            Placeholder.unparsed("reason", reason),
                            Placeholder.unparsed("staff", staff)));
                }
            }

            lines.add(Component.empty());
            lines.add(locale.getMessage("mutelist.page-info",
                    Placeholder.unparsed("page", String.valueOf(currentPage)),
                    Placeholder.unparsed("maxpage", String.valueOf(maxPage))));

            scheduler.runTask(() -> lines.forEach(sender::sendMessage));
        });

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return List.of("1", "2", "3");
        }
        return List.of();
    }
}
