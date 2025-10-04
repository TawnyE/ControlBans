package ret.tawny.controlbans.services;

import com.google.gson.JsonObject;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.util.logging.Level;

public class ProxyService {

    private final ControlBansPlugin plugin;
    private static final String CHANNEL = "controlbans:main";

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
        if (!Bukkit.getOnlinePlayers().isEmpty()) {
            Player sender = Bukkit.getOnlinePlayers().iterator().next();

            ByteArrayOutputStream b = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(b);

            try {
                out.writeUTF(message);
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write plugin message.", e);
                return;
            }

            sender.sendPluginMessage(plugin, CHANNEL, b.toByteArray());
        }
    }
}