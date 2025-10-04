package ret.tawny.controlbans.bungee;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.md_5.bungee.api.ProxyServer;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.connection.ProxiedPlayer;
import net.md_5.bungee.api.event.PluginMessageEvent;
import net.md_5.bungee.api.plugin.Listener;
import net.md_5.bungee.api.plugin.Plugin;
import net.md_5.bungee.event.EventHandler;

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

        try (DataInputStream in = new DataInputStream(new ByteArrayInputStream(event.getData()))) {
            String message = in.readUTF();
            JsonObject json = JsonParser.parseString(message).getAsJsonObject();
            String action = json.get("action").getAsString();

            switch (action) {
                case "KICK_PLAYER" -> {
                    String playerName = json.get("playerName").getAsString();
                    String kickMessage = json.get("kickMessage").getAsString();
                    ProxiedPlayer player = ProxyServer.getInstance().getPlayer(playerName);
                    if (player != null) {
                        player.disconnect(new TextComponent(kickMessage));
                    }
                }
                case "BROADCAST" -> {
                    String broadcastMessage = json.get("message").getAsString();
                    ProxyServer.getInstance().broadcast(new TextComponent(broadcastMessage));
                }
            }
        } catch (IOException e) {
            getLogger().warning("Failed to handle incoming plugin message: " + e.getMessage());
        }
    }
}