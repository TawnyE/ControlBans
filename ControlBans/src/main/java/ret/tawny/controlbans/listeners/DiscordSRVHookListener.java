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
        }
    }
}
