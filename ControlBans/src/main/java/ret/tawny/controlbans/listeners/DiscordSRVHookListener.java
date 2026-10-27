package ret.tawny.controlbans.listeners;

import github.scarsz.discordsrv.api.Subscribe;
import github.scarsz.discordsrv.api.events.GameChatMessagePreProcessEvent;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

public class DiscordSRVHookListener {

    private final ControlBansPlugin plugin;

    public DiscordSRVHookListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    @Subscribe
    public void onGameChatMessagePreProcess(GameChatMessagePreProcessEvent event) {
        Player player = event.getPlayer();
        if (player == null) return;

        if (plugin.getPlayerChatListener() != null && plugin.getPlayerChatListener().isMuted(player.getUniqueId())) {
            event.setCancelled(true);
            return;
        }
        // Cache-miss fallback: direct (cached) DB check so Discord can't bypass a mute
        // for players who never chatted in-game. Short timeout, fail-open on error
        // (in-game enforcement remains authoritative).
        try {
            boolean muted = plugin.getPunishmentService().getActiveMute(player.getUniqueId())
                    .get(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                    .filter(p -> !p.isExpired()).isPresent();
            if (muted) {
                event.setCancelled(true);
                return;
            }
            String ip = player.getAddress() != null && player.getAddress().getAddress() != null
                    ? player.getAddress().getAddress().getHostAddress() : null;
            if (ip != null) {
                boolean ipMuted = plugin.getPunishmentService().getActiveIpMute(ip)
                        .get(1500, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .filter(p -> !p.isExpired()).isPresent();
                if (ipMuted) event.setCancelled(true);
            }
        } catch (Exception ignored) {}
    }
}
