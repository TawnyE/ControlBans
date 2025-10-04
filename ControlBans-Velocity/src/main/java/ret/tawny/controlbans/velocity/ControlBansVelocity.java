package ret.tawny.controlbans.velocity;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;

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

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String action = json.get("action").getAsString();

            switch (action) {
                case "KICK_PLAYER" -> {
                    String playerName = json.get("playerName").getAsString();
                    String kickMessage = json.get("kickMessage").getAsString();
                    server.getPlayer(playerName).ifPresent(player -> player.disconnect(Component.text(kickMessage)));
                }
                case "BROADCAST" -> {
                    String broadcastMessage = json.get("message").getAsString();
                    server.sendMessage(Component.text(broadcastMessage));
                }
            }
        } catch (IOException e) {
            logger.warn("Failed to handle incoming plugin message: {}", e.getMessage());
        }
    }
}