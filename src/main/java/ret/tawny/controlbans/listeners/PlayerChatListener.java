package ret.tawny.controlbans.listeners;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import ret.tawny.controlbans.services.PunishmentService;
import ret.tawny.controlbans.util.TimeUtil;

public class PlayerChatListener implements Listener {

    private final PunishmentService punishmentService;

    public PlayerChatListener(PunishmentService punishmentService) {
        this.punishmentService = punishmentService;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncPlayerChatEvent event) {
        Player player = event.getPlayer();

        punishmentService.getActiveMute(player.getUniqueId()).thenAccept(muteOptional -> {
            if (muteOptional.isPresent() && !muteOptional.get().isExpired()) {
                event.setCancelled(true);

                // Send mute message to the player
                String message = "§cYou are currently muted.\n" +
                        "§7Reason: §f" + muteOptional.get().getReason() + "\n" +
                        (muteOptional.get().isPermanent() ? "§7Duration: §fPermanent" :
                                "§7Expires in: §f" + TimeUtil.formatDuration(muteOptional.get().getRemainingTime() / 1000));
                player.sendMessage(message);
            }
        });
    }
}