package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.AppealService;
import ret.tawny.controlbans.services.AppealService.AppealSubmissionResult;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
public class AppealCommand extends CommandBase {

    public AppealCommand(ControlBansPlugin plugin) {
        super(plugin);
        setCommand("appeal");
    }

    @Override
    public boolean execute(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(locale.getMessage("errors.command-from-console-error"));
            return true;
        }

        AppealService appealService = plugin.getAppealService();
        if (appealService == null) {
            player.sendMessage(locale.getMessage("appeals.unavailable"));
            return true;
        }

        if (args.length == 0) {
            player.sendMessage(locale.getMessage("appeals.message-required"));
            return true;
        }

        String message = String.join(" ", args).trim();
        if (message.isEmpty()) {
            player.sendMessage(locale.getMessage("appeals.message-required"));
            return true;
        }

        punishmentService.getActiveMute(player.getUniqueId()).whenComplete((muteOpt, throwable) -> {
            if (throwable != null) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                        player.sendMessage(locale.getMessage("appeals.error")));
                return;
            }

            if (muteOpt.isEmpty()) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                        player.sendMessage(locale.getMessage("appeals.not-muted")));
                return;
            }

            Punishment mute = muteOpt.get();
            appealService.submitAppeal(mute, message).whenComplete((result, submitError) -> {
                if (submitError != null || result == null) {
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                            player.sendMessage(locale.getMessage("appeals.error")));
                    return;
                }

                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> handleResult(player, mute, result));
            });
        }).exceptionally(throwable -> {
            plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                    player.sendMessage(locale.getMessage("appeals.error")));
            return null;
        });

        return true;
    }

    private void handleResult(Player player, Punishment mute, AppealSubmissionResult result) {
        switch (result.status()) {
            case ACCEPTED -> {
                String remaining = result.remainingSubmissions() < 0
                        ? "∞"
                        : Integer.toString(result.remainingSubmissions());
                TagResolver[] resolvers = new TagResolver[]{
                        idPlaceholder(mute.getPunishmentId()),
                        Placeholder.unparsed("remaining", remaining)
                };
                player.sendMessage(locale.getMessage("appeals.submitted", resolvers));
            }
            case COOLDOWN -> player.sendMessage(locale.getMessage("appeals.cooldown", durationPlaceholder(result.nextAllowedAt())));
            case LIMIT_REACHED -> player.sendMessage(locale.getMessage("appeals.limit", durationPlaceholder(result.nextAllowedAt())));
            case ALREADY_OPEN -> player.sendMessage(locale.getMessage("appeals.already-open"));
            default -> player.sendMessage(locale.getMessage("appeals.not-muted"));
        }
    }

    private TagResolver durationPlaceholder(long nextAllowedAt) {
        long millis = nextAllowedAt - System.currentTimeMillis();
        long seconds = Math.max(1L, (millis + 999L) / 1000L);
        return Placeholder.unparsed("duration", TimeUtil.formatDuration(seconds));
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        return List.of();
    }
}
