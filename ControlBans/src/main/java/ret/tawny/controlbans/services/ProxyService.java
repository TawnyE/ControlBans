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
    private volatile boolean directDispatchSupported = true;

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

        if (attemptImmediateDelivery(payload, null)) {
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

    private Player findMessenger() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player != null && player.isOnline()) {
                return player;
            }
        }
        return null;
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

    private boolean attemptImmediateDelivery(byte[] payload, Player preferredMessenger) {
        if (sendDirectly(payload)) {
            return true;
        }

        if (preferredMessenger != null && sendThroughPlayer(preferredMessenger, payload)) {
            return true;
        }

        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player == preferredMessenger) {
                continue;
            }
            if (sendThroughPlayer(player, payload)) {
                return true;
            }
        }

        return false;
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
        Player initialMessenger = preferredMessenger != null && preferredMessenger.isOnline()
            ? preferredMessenger
            : findMessenger();

        byte[] payload;
        while ((payload = queuedMessages.peek()) != null) {
            if (!attemptImmediateDelivery(payload, initialMessenger)) {
                if (initialMessenger == null) {
                    plugin.getLogger().log(Level.FINE, "Deferred proxy message delivery; no players available.");
                } else {
                    plugin.getLogger().log(Level.FINE, "Failed to flush proxy message through available players; will retry later.");
                }
                return;
            }

            queuedMessages.poll();
            initialMessenger = null; // prefer freshly discovered messenger on subsequent iterations
        }
    }

    private boolean sendDirectly(byte[] payload) {
        if (!directDispatchSupported) {
            return false;
        }

        try {
            plugin.getServer().sendPluginMessage(plugin, CHANNEL, payload);
            return true;
        } catch (IllegalArgumentException | IllegalStateException exception) {
            directDispatchSupported = false;
            plugin.getLogger().log(Level.FINE, "Direct proxy dispatch is not supported on this server; falling back to player messenger.", exception);
            return false;
        } catch (Throwable throwable) {
            directDispatchSupported = false;
            plugin.getLogger().log(Level.WARNING, "Unexpected failure sending proxy message directly; falling back to player messenger.", throwable);
            return false;
        }
    }
}
