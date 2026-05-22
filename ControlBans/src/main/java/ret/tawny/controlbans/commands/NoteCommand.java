package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.NoteService.PlayerNote;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.StringJoiner;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class NoteCommand extends CommandBase {

    public NoteCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("note");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!sender.hasPermission("controlbans.note")) {
            sender.sendMessage(locale.getMessage("errors.no-permission"));
            return true;
        }

        if (args.length < 1) {
            sender.sendMessage(locale.getMessage("notes.usage-view"));
            return true;
        }

        String targetName = args[0];
        resolveUuid(targetName).thenAccept(targetUuid -> {
            if (targetUuid == null) {
                scheduler.runTask(() -> sender.sendMessage(locale.getMessage("errors.player-not-found", playerPlaceholder(targetName))));
                return;
            }

            if (args.length >= 2 && args[1].equalsIgnoreCase("add")) {
                if (args.length < 3) {
                    scheduler.runTask(() -> sender.sendMessage(locale.getMessage("notes.usage-add")));
                    return;
                }
                StringJoiner noteJoiner = new StringJoiner(" ");
                for (int i = 2; i < args.length; i++) {
                    noteJoiner.add(args[i]);
                }
                String noteText = noteJoiner.toString();
                plugin.getNoteService().addNote(targetUuid, sender.getName(), noteText).thenRun(() ->
                    scheduler.runTask(() -> sender.sendMessage(locale.getMessage("notes.added",
                            Placeholder.unparsed("player", targetName))))
                );
                return;
            }

            if (args.length >= 3 && args[1].equalsIgnoreCase("remove")) {
                int index;
                try {
                    index = Integer.parseInt(args[2]);
                } catch (NumberFormatException e) {
                    scheduler.runTask(() -> sender.sendMessage(locale.getMessage("notes.usage-remove")));
                    return;
                }
                plugin.getNoteService().removeNote(targetUuid, index).thenAccept(removed ->
                    scheduler.runTask(() -> {
                        if (removed) {
                            sender.sendMessage(locale.getMessage("notes.removed",
                                    Placeholder.unparsed("player", targetName),
                                    Placeholder.unparsed("index", String.valueOf(index))));
                        } else {
                            sender.sendMessage(locale.getMessage("notes.invalid-index"));
                        }
                    })
                );
                return;
            }

            plugin.getNoteService().getNotes(targetUuid).thenAccept(playerNotes ->
                scheduler.runTask(() -> {
                    if (playerNotes.isEmpty()) {
                        sender.sendMessage(locale.getMessage("notes.no-notes",
                                Placeholder.unparsed("player", targetName)));
                        return;
                    }

                    List<Component> lines = new ArrayList<>();
                    lines.add(locale.getMessage("notes.header",
                            Placeholder.unparsed("player", targetName)));
                    lines.add(Component.empty());

                    for (int i = 0; i < playerNotes.size(); i++) {
                        PlayerNote note = playerNotes.get(i);
                        String date = TimeUtil.formatDate(note.timestamp());
                        lines.add(locale.getMessage("notes.entry",
                                Placeholder.unparsed("index", String.valueOf(i + 1)),
                                Placeholder.unparsed("note", note.noteText()),
                                Placeholder.unparsed("staff", note.staffName()),
                                Placeholder.unparsed("date", date)));
                    }

                    lines.forEach(sender::sendMessage);
                })
            );
        });

        return true;
    }

    private CompletableFuture<UUID> resolveUuid(String name) {
        Player online = Bukkit.getPlayerExact(name);
        if (online != null) return CompletableFuture.completedFuture(online.getUniqueId());

        return plugin.getStorage().getUuidByName(name).thenCompose(uuid -> {
            if (uuid != null) return CompletableFuture.completedFuture(uuid);
            return CompletableFuture.supplyAsync(() -> ret.tawny.controlbans.util.UuidUtil.lookupUuid(name));
        });
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        if (args.length == 1) {
            return getPlayerSuggestions(args[0]);
        }
        if (args.length == 2) {
            return List.of("add", "remove");
        }
        return List.of();
    }
}
