package ret.tawny.controlbans.commands;

import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.AppealService;
import ret.tawny.controlbans.services.AppealService.AppealResult;
import ret.tawny.controlbans.services.AppealService.AppealStatus;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.Optional;

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
        if (appealService == null || !plugin.getConfigManager().isAppealsEnabled()) {
            player.sendMessage(locale.getMessage("appeals.disabled"));
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

            Optional<Punishment> mute = muteOpt;
            if (mute.isEmpty()) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                        player.sendMessage(locale.getMessage("appeals.not-muted")));
                return;
            }

            appealService.submitAppeal(mute.get(), message).whenComplete((result, error) -> {
                if (error != null || result == null) {
                    plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                            player.sendMessage(locale.getMessage("appeals.error")));
                    return;
                }

                plugin.getSchedulerAdapter().runTaskForPlayer(player, () ->
                        handleResult(player, mute.get(), result));
            });
        });

        return true;
    }

    private void handleResult(Player player, Punishment punishment, AppealResult result) {
        AppealStatus status = result.status();
        switch (status) {
            case ACCEPTED -> player.sendMessage(locale.getMessage("appeals.submitted",
                    idPlaceholder(punishment.getPunishmentId())));
            case NOT_MUTED -> player.sendMessage(locale.getMessage("appeals.not-muted"));
            case DISABLED -> player.sendMessage(locale.getMessage("appeals.disabled"));
            case ON_COOLDOWN -> player.sendMessage(locale.getMessage("appeals.cooldown",
                    durationPlaceholder(result.nextAllowedAt())));
            case LIMIT_REACHED -> player.sendMessage(locale.getMessage("appeals.limit",
                    durationPlaceholder(result.nextAllowedAt())));
        }
    }

    private TagResolver durationPlaceholder(long targetTimeMillis) {
        long remainingMillis = Math.max(0L, targetTimeMillis - System.currentTimeMillis());
        long seconds = (remainingMillis + 999L) / 1000L;
        String formatted = TimeUtil.formatDuration(seconds);
        return Placeholder.unparsed("duration", formatted);
    }

    @Override
    public List<String> onTab(CommandSender sender, String[] args) {
        return List.of();
    }
}
