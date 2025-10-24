package ret.tawny.controlbans.bungee;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.util.Locale;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

public class ControlBansBungee extends Plugin implements Listener {

    private static final String CHANNEL = "controlbans:main";

    @Override
    public void onEnable() {
        getLogger().info("ControlBans-Bungee bridge has been enabled.");
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equalsIgnoreCase(CHANNEL)) {
            return;
        }

        event.setCancelled(true);

        if (!(event.getSender() instanceof net.md_5.bungee.api.connection.Server)) {
            return;
        }

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("action")) {
                getLogger().warning("Received proxy message without an action field.");
                return;
            }

            String action = json.get("action").getAsString().toUpperCase(Locale.ROOT);

            switch (action) {
                case "KICK_PLAYER" -> handleKick(json);
                case "BROADCAST" -> handleBroadcast(json);
                default -> getLogger().warning("Unknown proxy message action: " + action);
            }
        } catch (IOException | JsonParseException e) {
            getLogger().warning("Failed to handle incoming plugin message: " + e.getMessage());
        }
    }

    private void handleKick(JsonObject json) {
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : null;
        String kickMessage = json.has("kickMessage") ? json.get("kickMessage").getAsString() : null;
        if (playerName == null || kickMessage == null) {
            getLogger().warning("Received malformed kick message from Bukkit instance.");
            return;
        }

        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerName);
        if (player != null) {
            player.disconnect(new TextComponent(kickMessage));
        }
    }

    private void handleBroadcast(JsonObject json) {
        String broadcastMessage = json.has("message") ? json.get("message").getAsString() : null;
        if (broadcastMessage == null) {
            getLogger().warning("Received malformed broadcast message from Bukkit instance.");
            return;
        }

        ProxyServer.getInstance().broadcast(new TextComponent(broadcastMessage));
    }
}