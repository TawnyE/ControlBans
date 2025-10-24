package ret.tawny.controlbans.listeners;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.ProxyService;

/**
 * Flushes any queued proxy plugin messages as soon as a player capable of relaying them joins.
 */
public class ProxyMessengerListener implements Listener {

    private final ProxyService proxyService;

    public ProxyMessengerListener(ControlBansPlugin plugin) {
        this.proxyService = plugin.getProxyService();
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPlayerJoin(PlayerJoinEvent event) {
        proxyService.flushQueuedMessages(event.getPlayer());
    }
}
