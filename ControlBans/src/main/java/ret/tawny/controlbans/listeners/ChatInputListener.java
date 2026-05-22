package ret.tawny.controlbans.listeners;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.logging.Level;

public class ChatInputListener implements Listener {

    private final ControlBansPlugin plugin;
    private final Map<UUID, Consumer<String>> awaitingInput = new ConcurrentHashMap<>();

    public ChatInputListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public void awaitInput(Player player, String configPath) {
        awaitingInput.put(player.getUniqueId(), input -> {
            if (input.equalsIgnoreCase("cancel")) {
                player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.cancelled"));
                return;
            }

            player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.updating"));

            plugin.getSchedulerAdapter().runTask(() -> {
                try {
                    plugin.getConfig().set(configPath, input);
                    plugin.saveConfig();

                    plugin.reload();
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.updated",
                                Placeholder.unparsed("path", configPath),
                                Placeholder.unparsed("value", input)));
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to save configuration", e);
                    if (player.isOnline()) {
                        player.sendMessage(plugin.getLocaleManager().getMessage("config-editor.save-failed",
                                Placeholder.unparsed("error", e.getMessage())));
                    }
                }
            });
        });
    }

    public void awaitInput(Player player, Consumer<String> callback) {
        awaitingInput.put(player.getUniqueId(), callback);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(AsyncChatEvent event) {
        Player player = event.getPlayer();
        if (!awaitingInput.containsKey(player.getUniqueId()))
            return;

        event.setCancelled(true);
        String input = PlainTextComponentSerializer.plainText().serialize(event.message());
        Consumer<String> callback = awaitingInput.remove(player.getUniqueId());

        if (callback != null) {
            callback.accept(input);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        awaitingInput.remove(event.getPlayer().getUniqueId());
    }
}
