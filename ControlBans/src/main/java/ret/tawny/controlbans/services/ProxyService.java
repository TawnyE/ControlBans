package ret.tawny.controlbans.services;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class ProxyService {

    private final ControlBansPlugin plugin;
    private static final String CHANNEL = "controlbans:main";
    private final Queue<byte[]> queuedMessages = new ConcurrentLinkedQueue<>();

    public ProxyService(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public void sendKickPlayerMessage(String playerName, String kickMessage) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "KICK_PLAYER");
        json.addProperty("playerName", playerName);
        json.addProperty("kickMessage", kickMessage);
        sendPluginMessage(json.toString());
    }

    public void sendBroadcastMessage(String message) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "BROADCAST");
        json.addProperty("message", message);
        sendPluginMessage(json.toString());
    }

    private void sendPluginMessage(String message) {
        byte[] payload = encodePayload(message);

        Runnable dispatcher = () -> dispatchOrQueue(payload);

        if (Bukkit.isPrimaryThread()) {
            dispatcher.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatcher);
        }
    }

    private byte[] encodePayload(String message) {
        try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
             DataOutputStream dataOutputStream = new DataOutputStream(outputStream)) {
            dataOutputStream.writeUTF(message);
            return outputStream.toByteArray();
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Failed to encode proxy plugin message", exception);
            return message.getBytes(StandardCharsets.UTF_8);
        }
    }

    private void dispatchOrQueue(byte[] payload) {
        Optional<Player> messenger = findAvailableMessenger();
        if (messenger.isPresent()) {
            Player player = messenger.get();
            flushQueuedMessages(player);
            player.sendPluginMessage(plugin, CHANNEL, payload);
            return;
        }

        queuedMessages.offer(payload);
        plugin.getLogger().fine("Queued proxy message because no players are online.");

        // Attempt the legacy server-level dispatch as a best-effort fallback for environments
        // that might support it. The queued payload ensures delivery once a player joins even
        // if this direct send is ignored by the proxy implementation.
        sendDirectly(payload);
    }

    private void sendDirectly(byte[] payload) {
        // This mirrors the original behaviour that wrote UTF strings into the payload and
        // immediately dispatched them via the server messenger. Some proxy bridges are happy
        // to consume these messages even when no player transport is available.
        plugin.getServer().sendPluginMessage(plugin, CHANNEL, payload);
    }

    private Optional<Player> findAvailableMessenger() {
        return plugin.getServer().getOnlinePlayers().stream()
                .filter(Player::isOnline)
                .findAny()
                .map(Player.class::cast);
    }

    public void flushQueuedMessages() {
        findAvailableMessenger().ifPresent(this::flushQueuedMessages);
    }

    public void flushQueuedMessages(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!queuedMessages.isEmpty()) {
            plugin.getLogger().fine("Flushing " + queuedMessages.size() + " queued proxy message(s).");
        }

        byte[] payload;
        while ((payload = queuedMessages.poll()) != null) {
            player.sendPluginMessage(plugin, CHANNEL, payload);
        }
    }
}
