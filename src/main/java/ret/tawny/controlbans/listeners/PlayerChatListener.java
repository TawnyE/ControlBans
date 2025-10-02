package ret.tawny.controlbans.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.List;

public class PlayerChatListener implements Listener {

    private final PunishmentService punishmentService;
    private final ControlBansPlugin plugin;

    public PlayerChatListener(PunishmentService punishmentService) {
        this.punishmentService = punishmentService;
        this.plugin = ControlBansPlugin.getInstance();
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        punishmentService.getActiveMute(player.getUniqueId()).thenAccept(muteOptional -> {
            if (muteOptional.isPresent() && !muteOptional.get().isExpired()) {
                event.setCancelled(true);

                Punishment mute = muteOptional.get();
                String configPath = mute.isPermanent() ? "screens.mute" : "screens.tempmute";
                List<String> messageLines = plugin.getConfigManager().getMessageList(configPath);
                String message = punishmentService.formatPunishmentScreen(mute, messageLines);

                // Send mute message to the player on the main thread
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    player.sendMessage(message);
                });
            }
        });
    }
}