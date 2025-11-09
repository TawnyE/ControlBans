package ret.tawny.controlbans.listeners;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;
import org.jetbrains.annotations.NotNull;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

public class ProxyMessengerListener implements PluginMessageListener {

    private final ControlBansPlugin plugin;
    private static final String CHANNEL = "controlbans:main";

    public ProxyMessengerListener(ControlBansPlugin plugin) {
        this.plugin = plugin;
        plugin.getServer().getMessenger().registerIncomingPluginChannel(plugin, CHANNEL, this);
        plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, CHANNEL);
    }

    @Override
    public void onPluginMessageReceived(@NotNull String channel, @NotNull Player player, @NotNull byte[] messageBytes) {
        if (!channel.equalsIgnoreCase(CHANNEL)) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(messageBytes))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();

            if (!json.has("action")) {
                plugin.getLogger().warning("Received proxy message without an action field.");
                return;
            }

            String action = json.get("action").getAsString().toUpperCase(Locale.ROOT);

            if ("INVALIDATE_CACHE".equals(action)) {
                handleCacheInvalidation(json);
            }
            // Note: We don't need to handle KICK or BROADCAST here, as those are outbound from Bukkit.
            // This listener is for messages coming IN from the proxy.

        } catch (IOException | JsonParseException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to handle incoming plugin message from proxy.", e);
        }
    }

    private void handleCacheInvalidation(JsonObject json) {
        if (!json.has("playerUuid")) {
            plugin.getLogger().warning("Received malformed cache invalidation message from proxy.");
            return;
        }
        try {
            UUID playerUuid = UUID.fromString(json.get("playerUuid").getAsString());
            plugin.getCacheService().invalidatePlayerPunishments(playerUuid);
            plugin.getLogger().fine("Received instruction from proxy to invalidate cache for UUID: " + playerUuid);
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Received invalid UUID for cache invalidation from proxy.");
        }
    }
}