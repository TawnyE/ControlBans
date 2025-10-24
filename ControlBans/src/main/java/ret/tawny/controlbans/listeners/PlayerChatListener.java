package ret.tawny.controlbans.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.services.PunishmentService;

import java.util.List;
import java.util.Optional;

public class PlayerChatListener implements Listener {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    public PlayerChatListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.punishmentService = plugin.getPunishmentService();
    }

    // Using modern Paper AsyncChatEvent
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPlayerChat(AsyncChatEvent event) {
        Player player = event.getPlayer();

        // Block this async thread until the check completes.
        Optional<Punishment> muteOptional = punishmentService.getActiveMute(player.getUniqueId()).join();

        if (muteOptional.isPresent() && !muteOptional.get().isExpired()) {
            event.setCancelled(true);

            Punishment mute = muteOptional.get();
            List<Component> messageLines = punishmentService.getMuteMessageFor(mute);

            // Messages must be sent on the main thread
            plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> messageLines.forEach(player::sendMessage));
        }
    }
}