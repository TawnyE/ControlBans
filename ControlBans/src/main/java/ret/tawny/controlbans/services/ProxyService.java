package ret.tawny.controlbans.services;

import com.google.gson.JsonObject;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class ProxyService {

    private final ControlBansPlugin plugin;
    private final SchedulerAdapter scheduler;
    private static final String CHANNEL = "controlbans:main";
    private static final int MAX_QUEUE_SIZE = 1024;

    private final Queue<byte[]> queuedMessages = new ConcurrentLinkedQueue<>();
    private volatile boolean directDispatchSupported = true;

    public ProxyService(ControlBansPlugin plugin, SchedulerAdapter scheduler) {
        this.plugin = plugin;
        this.scheduler = scheduler;
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
        scheduler.runTask(() -> dispatch(message));
    }

    private void dispatch(String message) {
        byte[] payload = encodePayload(message);

        if (tryDispatch(payload, null)) {
            flushQueuedMessagesInternal(null);
            return;
        }

        if (queuedMessages.size() >= MAX_QUEUE_SIZE) {
            queuedMessages.poll();
            plugin.getLogger().log(Level.FINE, "Dropped oldest proxy plugin message because the queue is full.");
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

    private boolean tryDispatch(byte[] payload, Player preferredMessenger) {
        if (sendDirectly(payload)) {
            return true;
        }

        if (preferredMessenger != null && sendThroughPlayer(preferredMessenger, payload)) {
            return true;
        }

        return sendThroughAnyPlayer(payload, preferredMessenger);
    }

    private boolean sendThroughAnyPlayer(byte[] payload, Player preferredMessenger) {
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
        scheduler.runTask(this::flushQueuedMessagesInternal);
    }

    public void flushQueuedMessages(Player player) {
        if (player == null) {
            flushQueuedMessages();
            return;
        }

        scheduler.runTaskForPlayer(player, () -> flushQueuedMessagesInternal(player));
    }

    private void flushQueuedMessagesInternal() {
        flushQueuedMessagesInternal(null);
    }

    private void flushQueuedMessagesInternal(Player preferredMessenger) {
        byte[] payload;
        while ((payload = queuedMessages.peek()) != null) {
            Player messenger = preferredMessenger;
            if (messenger != null && !messenger.isOnline()) {
                messenger = null;
            }

            if (tryDispatch(payload, messenger)) {
                queuedMessages.poll();
                continue;
            }

            plugin.getLogger().log(Level.FINE, "Deferred proxy message delivery; no players available.");
            return;
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
