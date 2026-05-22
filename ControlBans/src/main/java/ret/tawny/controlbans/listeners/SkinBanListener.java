package ret.tawny.controlbans.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.SkinBanService;

public class SkinBanListener implements Listener {

    private final SkinBanService skinBanService;

    public SkinBanListener(ControlBansPlugin plugin) {
        this.skinBanService = plugin.getSkinBanService();
    }

    @EventHandler(priority = EventPriority.LOW)
    public void onPlayerJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        skinBanService.handlePlayerJoin(player);
    }
}