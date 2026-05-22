package ret.tawny.controlbans.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.config.ServerInfo;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.connection.Server;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;

public class BungeeEntryPoint extends Plugin implements Listener {

    private static final String CHANNEL = "controlbans:main";

    @Override
    public void onEnable() {
        getLogger().info("ControlBans Bungee extension enabled (Internal).");
        getProxy().registerChannel(CHANNEL);
        getProxy().getPluginManager().registerListener(this, this);
    }

    @EventHandler
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getTag().equalsIgnoreCase(CHANNEL)) {
            return;
        }

        if (!(event.getSender() instanceof Server)) {
            return;
        }

        final Server sourceServer = (Server) event.getSender();

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("action")) {
                return;
            }

            String action = json.get("action").getAsString().toUpperCase(Locale.ROOT);

            switch (action) {
                case "KICK_PLAYER" -> handleKick(json);
                case "BROADCAST" -> handleBroadcast(json);
                case "STAFF_ALERT" -> handleStaffAlert(json);
                case "INVALIDATE_CACHE" -> handleCacheInvalidation(json, sourceServer, event.getData());
            }
        } catch (IOException | JsonParseException ignored) {}
    }

    private void handleKick(JsonObject json) {
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : null;
        String kickMessage = json.has("kickMessage") ? json.get("kickMessage").getAsString() : null;
        if (playerName == null || kickMessage == null) return;

        ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerName);
        if (player != null) {
            player.disconnect(TextComponent.fromLegacyText(kickMessage));
        }
    }

    private void handleBroadcast(JsonObject json) {
        String broadcastMessage = json.has("message") ? json.get("message").getAsString() : null;
        if (broadcastMessage == null) return;

        ProxyServer.getInstance().broadcast(TextComponent.fromLegacyText(broadcastMessage));
    }

    private void handleStaffAlert(JsonObject json) {
        String message = json.has("message") ? json.get("message").getAsString() : null;
        String permission = json.has("permission") ? json.get("permission").getAsString() : null;
        if (message == null || permission == null || permission.isBlank()) return;

        for (ProxiedPlayer player : ProxyServer.getInstance().getPlayers()) {
            if (player.hasPermission(permission)) {
                player.sendMessage(TextComponent.fromLegacyText(message));
            }
        }
    }

    private void handleCacheInvalidation(JsonObject json, Server sourceServer, byte[] originalData) {
        if (!json.has("playerUuid")) return;

        for (Map.Entry<String, ServerInfo> entry : getProxy().getServers().entrySet()) {
            ServerInfo serverInfo = entry.getValue();
            if (serverInfo.equals(sourceServer.getInfo())) continue;
            serverInfo.sendData(CHANNEL, originalData);
        }
    }
}
