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
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.logging.Level;

public class ProxyService {

    private final ControlBansPlugin plugin;
    private final SchedulerAdapter scheduler;
    private static final String CHANNEL = "controlbans:main";
    private static final int MAX_QUEUE_SIZE = 1024;

    private final Queue<byte[]> queuedMessages = new ConcurrentLinkedQueue<>();
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

    public void sendStaffAlertMessage(String message, String permission) {
        JsonObject json = new JsonObject();
        json.addProperty("action", "STAFF_ALERT");
        json.addProperty("message", message);
        json.addProperty("permission", permission);
        sendPluginMessage(json.toString());
    }

    public void sendInvalidateCacheMessage(UUID playerUuid) {
        if (playerUuid == null) return;

        JsonObject json = new JsonObject();
        json.addProperty("action", "INVALIDATE_CACHE");
        json.addProperty("playerUuid", playerUuid.toString());
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
        // NOTE: CraftServer#sendPluginMessage silently does nothing when the server
        // has no online players, and CraftPlayer#sendPluginMessage silently drops
        // the message when the player has not registered the channel. Neither throws,
        // so "direct dispatch" could never report failure — messages vanished.
        // Every send therefore goes through a verified player carrier, or queues.
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

        // A player that has not registered the channel would silently swallow
        // the message (CraftPlayer drops it before it reaches the wire), so
        // treat them as an unusable carrier instead of reporting success.
        if (!player.getListeningPluginChannels().contains(CHANNEL)) {
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

    /**
     * Hook for per-player channel setup on join. The modern Paper API exposes no
     * way to register an outgoing channel server-side (the old Player#addChannel
     * is gone): CraftPlayer only forwards a plugin message when the CLIENT has
     * registered the channel. On proxy networks Velocity relays channel
     * registrations for proxy-registered channels, so this hook is intentionally
     * a no-op placeholder; it exists so the join path has a single, documented
     * place that explains the constraint.
     */
    public void registerPlayerChannels(Player player) {
        // intentionally empty — see javadoc
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

    public void clearQueue() {
        int dropped = queuedMessages.size();
        queuedMessages.clear();
        if (dropped > 0) {
            plugin.getLogger().fine("Cleared " + dropped + " queued proxy message(s) on reload.");
        }
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

}
