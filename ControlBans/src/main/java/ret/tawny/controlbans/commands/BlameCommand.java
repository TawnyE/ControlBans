package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;

public class BlameCommand extends CommandBase {

    private static final int ENTRIES_PER_PAGE = 10;

    public BlameCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("blame");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.blame")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }
        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("blame.usage"));
            return true;
        }

        String staffName = args[0];

        int page = 1;
        if (args.length > 1) {
            try {
                page = Integer.parseInt(args[1]);
                if (page < 1) page = 1;
            } catch (NumberFormatException ignored) {
                sender.sendMessage(locale.getMessage("errors.invalid-arguments", usagePlaceholder("/" + label + " <staff> [page]")));
                return true;
            }
        }

        final int finalPage = page;

        punishmentService.getAllPunishments().thenAccept(allPunishments -> {
            List<Punishment> staffPunishments = allPunishments.stream()
                    .filter(p -> p.getStaffName() != null && p.getStaffName().equalsIgnoreCase(staffName))
                    .sorted((a, b) -> Long.compare(b.getCreatedTime(), a.getCreatedTime()))
                    .toList();

            int maxPage = Math.max(1, (int) Math.ceil((double) staffPunishments.size() / ENTRIES_PER_PAGE));
            int currentPage = finalPage > maxPage ? maxPage : finalPage;

            int startIndex = (currentPage - 1) * ENTRIES_PER_PAGE;
            int endIndex = Math.min(startIndex + ENTRIES_PER_PAGE, staffPunishments.size());

            List<Component> lines = new ArrayList<>();
            lines.add(locale.getMessage("blame.header",
                    Placeholder.unparsed("staff", staffName),
                    Placeholder.unparsed("count", String.valueOf(staffPunishments.size()))));
            lines.add(Component.empty());

            if (staffPunishments.isEmpty()) {
                lines.add(locale.getMessage("blame.empty",
                        Placeholder.unparsed("staff", staffName)));
            } else {
                for (int i = startIndex; i < endIndex; i++) {
                    Punishment p = staffPunishments.get(i);
                    String reason = p.getReason() != null ? p.getReason() : "Unspecified";
                    String date = TimeUtil.formatDate(p.getCreatedTime());
                    lines.add(locale.getMessage("blame.entry",
                            Placeholder.unparsed("type", p.getType().getDisplayName()),
                            Placeholder.unparsed("player", p.getTargetName() != null ? p.getTargetName() : "Unknown"),
                            Placeholder.unparsed("reason", reason),
                            Placeholder.unparsed("date", date)));
                }
            }

            lines.add(Component.empty());
            lines.add(locale.getMessage("banlist.page-info",
                    Placeholder.unparsed("page", String.valueOf(currentPage)),
                    Placeholder.unparsed("maxpage", String.valueOf(maxPage))));

            scheduler.runTask(() -> lines.forEach(sender::sendMessage));
        });

        return true;
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        if (args.length == 2) {
            return List.of("1", "2", "3");
        }
        return List.of();
    }
}
