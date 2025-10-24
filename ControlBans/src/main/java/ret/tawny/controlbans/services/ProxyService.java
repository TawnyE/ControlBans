package ret.tawny.controlbans.services;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
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
        Runnable dispatcher = () -> dispatch(message);

        if (Bukkit.isPrimaryThread()) {
            dispatcher.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, dispatcher);
        }
    }

    private void dispatch(String message) {
        byte[] payload = encodePayload(message);

        if (sendThroughAnyPlayer(payload)) {
            flushQueuedMessagesInternal(null);
            return;
        }

        queuedMessages.add(payload);
        plugin.getLogger().log(Level.FINE, "Queued proxy plugin message; no messenger available.");
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

    private boolean sendThroughAnyPlayer(byte[] payload) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (sendThroughPlayer(player, payload)) {
                return true;
            }
        }
        return false;
    }

    private boolean sendThroughPlayer(Player player, byte[] payload) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        try {
            player.sendPluginMessage(plugin, CHANNEL, payload);
            return true;
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Failed to relay proxy plugin message through player " + player.getName(), exception);
            return false;
        }
    }

    public void flushQueuedMessages() {
        Runnable flusher = () -> flushQueuedMessagesInternal(null);

        if (Bukkit.isPrimaryThread()) {
            flusher.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, flusher);
        }
    }

    public void flushQueuedMessages(Player player) {
        if (player == null) {
            flushQueuedMessages();
            return;
        }

        Runnable flusher = () -> flushQueuedMessagesInternal(player);

        if (Bukkit.isPrimaryThread()) {
            flusher.run();
        } else {
            Bukkit.getScheduler().runTask(plugin, flusher);
        }
    }

    private void flushQueuedMessagesInternal(Player preferredMessenger) {
        if (queuedMessages.isEmpty()) {
            return;
        }

        Player messenger = (preferredMessenger != null && preferredMessenger.isOnline())
            ? preferredMessenger
            : findFallbackMessenger(preferredMessenger);

        if (messenger == null) {
            plugin.getLogger().log(Level.FINE, "Deferred proxy message delivery; no players available.");
            return;
        }

        byte[] payload;
        while ((payload = queuedMessages.peek()) != null) {
            if (!sendThroughPlayer(messenger, payload)) {
                messenger = findFallbackMessenger(messenger);
                if (messenger == null) {
                    plugin.getLogger().log(Level.FINE, "Failed to flush proxy message through available players; will retry later.");
                    return;
                }
                continue;
            }

            queuedMessages.poll();
        }
    }

    private Player findFallbackMessenger(Player excluded) {
        for (Player candidate : plugin.getServer().getOnlinePlayers()) {
            if (candidate == null || !candidate.isOnline() || candidate == excluded) {
                continue;
            }
            return candidate;
        }
        return null;
    }
}
