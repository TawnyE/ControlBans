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
    private final Queue<byte[]> pendingMessages = new ConcurrentLinkedQueue<>();

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
        Runnable dispatcher = () -> dispatchDirectly(message);

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
        byte[] payload;
        try {
            payload = encodeMessage(message);
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Failed to encode proxy message.", e);
            return;
        }

        Optional<Player> player = Bukkit.getOnlinePlayers().stream().findFirst();
        if (player.isEmpty()) {
            pendingMessages.add(payload);
            plugin.getLogger().info("Queued proxy message because no players are online to relay it.");
            return;
        }

        if (!pendingMessages.isEmpty()) {
            pendingMessages.add(payload);
            flushQueuedMessages(player.get());
            return;
        }
    }

    private void dispatchDirectly(String message) {
        byte[] payload = encodePayload(message);
        plugin.getServer().sendPluginMessage(plugin, CHANNEL, payload);
    }

    public void flushQueuedMessages() {
        // No queued messages are maintained in the direct-dispatch implementation.
    }

    public void flushQueuedMessages(Player player) {
        // No queued messages are maintained in the direct-dispatch implementation.
        player.get().sendPluginMessage(plugin, CHANNEL, payload);
    }

    public void flushQueuedMessages(Player player) {
        byte[] data;
        while ((data = pendingMessages.poll()) != null) {
            player.sendPluginMessage(plugin, CHANNEL, data);
        }
    }

    public void flushQueuedMessagesIfPossible() {
        Optional<Player> relay = Bukkit.getOnlinePlayers().stream().findFirst();
        relay.ifPresent(this::flushQueuedMessages);
    }

    private byte[] encodeMessage(String message) throws IOException {
        ByteArrayOutputStream byteStream = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(byteStream)) {
            out.writeUTF(message);
            out.flush();
            return byteStream.toByteArray();
        }
    }
}
