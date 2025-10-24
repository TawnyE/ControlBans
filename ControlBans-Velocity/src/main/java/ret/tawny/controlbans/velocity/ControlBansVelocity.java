package ret.tawny.controlbans.velocity;

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
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.Locale;

@Plugin(
        id = "controlbans-velocity",
        name = "ControlBans-Velocity",
        version = "1.8",
        authors = {"Tawny"}
)
public class ControlBansVelocity {

    private final ProxyServer server;
    private final Logger logger;
    private static final MinecraftChannelIdentifier CHANNEL = MinecraftChannelIdentifier.create("controlbans", "main");

    @Inject
    public ControlBansVelocity(ProxyServer server, Logger logger) {
        this.server = server;
        this.logger = logger;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) {
        server.getChannelRegistrar().register(CHANNEL);
        logger.info("ControlBans-Velocity bridge has been enabled.");
    }

    @Subscribe
    public void onPluginMessage(PluginMessageEvent event) {
        if (!event.getIdentifier().equals(CHANNEL)) {
            return;
        }

        if (!(event.getSource() instanceof ServerConnection)) {
            return;
        }

        event.setResult(PluginMessageEvent.ForwardResult.handled());

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            if (!json.has("action")) {
                logger.warn("Received proxy message without an action field.");
                return;
            }

            String action = json.get("action").getAsString().toUpperCase(Locale.ROOT);

            switch (action) {
                case "KICK_PLAYER" -> handleKick(json);
                case "BROADCAST" -> handleBroadcast(json);
                default -> logger.warn("Unknown proxy message action: {}", action);
            }
        } catch (IOException | JsonParseException e) {
            logger.warn("Failed to handle incoming plugin message", e);
        }
    }

    private void handleKick(JsonObject json) {
        String playerName = json.has("playerName") ? json.get("playerName").getAsString() : null;
        String kickMessage = json.has("kickMessage") ? json.get("kickMessage").getAsString() : null;
        if (playerName == null || kickMessage == null) {
            logger.warn("Received malformed kick message from Bukkit instance.");
            return;
        }

        server.getPlayer(playerName).ifPresent(player -> player.disconnect(Component.text(kickMessage)));
    }

    private void handleBroadcast(JsonObject json) {
        String broadcastMessage = json.has("message") ? json.get("message").getAsString() : null;
        if (broadcastMessage == null) {
            logger.warn("Received malformed broadcast message from Bukkit instance.");
            return;
        }

        server.sendMessage(Component.text(broadcastMessage));
    }
}