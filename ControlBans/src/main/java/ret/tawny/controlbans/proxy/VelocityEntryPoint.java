package ret.tawny.controlbans.proxy;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.slf4j.Logger;

import com.google.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;

@Plugin(
        id = "controlbans",
        name = "ControlBans",
        version = "5.0",
        authors = {"Tawny"}
)
public class VelocityEntryPoint {

    private final ProxyServer server;
    private final Logger logger;
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("controlbans", "main");

    @Inject
    public VelocityEntryPoint(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("ControlBans Velocity extension enabled (Internal).");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        ServerConnection sourceConnection = (ServerConnection) event.getSource();

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
                case "INVALIDATE_CACHE" -> handleCacheInvalidation(json, sourceConnection, event.getData());
            }
        } catch (IOException | JsonParseException ignored) {}
    }

    private void handleKick(JsonObject json) {
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : null;
        String kickMessage = json.has("kickMessage") ? json.get("kickMessage").getAsString() : null;
        if (playerName == null || kickMessage == null) return;

        server.getPlayer(playerName).ifPresent(player ->
                player.disconnect(LegacyComponentSerializer.legacySection().deserialize(kickMessage)));
    }

    private void handleBroadcast(JsonObject json) {
        String broadcastMessage = json.has("message") ? json.get("message").getAsString() : null;
        if (broadcastMessage == null) return;

        Component component = LegacyComponentSerializer.legacySection().deserialize(broadcastMessage);
        server.getAllPlayers().forEach(player -> player.sendMessage(component));
    }

    private void handleStaffAlert(JsonObject json) {
        String message = json.has("message") ? json.get("message").getAsString() : null;
        String permission = json.has("permission") ? json.get("permission").getAsString() : null;
        if (message == null || permission == null || permission.isBlank()) return;

        Component component = LegacyComponentSerializer.legacySection().deserialize(message);
        server.getAllPlayers().stream()
                .filter(player -> player.hasPermission(permission))
                .forEach(player -> player.sendMessage(component));
    }

    private void handleCacheInvalidation(JsonObject json, ServerConnection sourceConnection, byte[] originalData) {
        if (!json.has("playerUuid")) return;

        for (RegisteredServer registeredServer : server.getAllServers()) {
            if (registeredServer.getServerInfo().equals(sourceConnection.getServerInfo())) {
                continue;
            }
            registeredServer.sendPluginMessage(CHANNEL, originalData);
        }
    }
}
