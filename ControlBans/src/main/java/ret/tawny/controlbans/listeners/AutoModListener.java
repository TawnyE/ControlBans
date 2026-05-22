package ret.tawny.controlbans.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.SignChangeEvent;
import org.bukkit.event.player.PlayerEditBookEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.services.AutoModService;

public class AutoModListener implements Listener {

    private final ControlBansPlugin plugin;
    private final AutoModService autoModService;

    public AutoModListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.autoModService = plugin.getAutoModService();
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        String plainMessage = PlainTextComponentSerializer.plainText().serialize(event.message());

        if (autoModService.isSpamming(player, plainMessage)) {
            event.setCancelled(true);
            player.sendMessage(plugin.getLocaleManager().getMessage("automod.spam-warning"));
            return;
        }

        AutoModService.FilterRule rule = autoModService.checkContent(plainMessage);
        if (rule != null) {
            if (rule.action() == AutoModService.Action.CANCEL) {
                event.setCancelled(true);
            } else if (rule.action() == AutoModService.Action.SHADOW) {
                event.viewers().removeIf(v ->
                    v instanceof Player p &&
                    !p.equals(player) &&
                    !p.hasPermission("controlbans.shadowmute.see")
                );
            }
            autoModService.handleViolation(player, rule, "Chat");

            if (event.isCancelled()) {
                return;
            }
        }

        if (!player.hasPermission("controlbans.bypass.caps")) {
            String fixedMessage = autoModService.fixCaps(plainMessage);
            if (!fixedMessage.equals(plainMessage)) {
                event.message(net.kyori.adventure.text.Component.text(fixedMessage));
            }
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onSignChange(SignChangeEvent event) {
        StringBuilder signContent = new StringBuilder();
        for (String line : event.getLines()) {
            signContent.append(line).append(" ");
        }

        AutoModService.FilterRule rule = autoModService.checkContent(signContent.toString());
        if (rule != null) {
            if (rule.action() == AutoModService.Action.CANCEL) {
                event.setCancelled(true);
            }
            autoModService.handleViolation(event.getPlayer(), rule, "Sign");
        }
    }

    @EventHandler(priority = EventPriority.NORMAL, ignoreCancelled = true)
    public void onBookEdit(PlayerEditBookEvent event) {
        StringBuilder bookContent = new StringBuilder();
        for (String page : event.getNewBookMeta().getPages()) {
            bookContent.append(page).append(" ");
        }

        AutoModService.FilterRule rule = autoModService.checkContent(bookContent.toString());
        if (rule != null) {
            if (rule.action() == AutoModService.Action.CANCEL) {
                event.setCancelled(true);
            }
            autoModService.handleViolation(event.getPlayer(), rule, "Book");
        }
    }
}
