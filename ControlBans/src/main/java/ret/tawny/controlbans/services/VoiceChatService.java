package ret.tawny.controlbans.services;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatApi;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class VoiceChatService implements VoicechatPlugin {

    private final ControlBansPlugin plugin;

    public VoiceChatService(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            service.registerPlugin(this);
            plugin.getLogger().info("Successfully integrated with Simple Voice Chat.");
        } else {
            plugin.getLogger().warning("Simple Voice Chat integration enabled, but the plugin is not installed.");
        }
    }

    @Override
    public String getPluginId() {
        return "controlbans";
    }

    @Override
    public void registerEvents(de.maxhenkel.voicechat.api.events.EventRegistration registration) {
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        if (event.getSenderConnection() == null) {
            return;
        }
        UUID playerUuid = event.getSenderConnection().getPlayer().getUuid();

        // Ignore Bedrock players
        if (plugin.getConfigManager().isGeyserEnabled() && FloodgateApi.getInstance().isFloodgatePlayer(playerUuid)) {
            return;
        }

        CompletableFuture<Boolean> isMutedFuture = plugin.getPunishmentService()
                .getActiveVoiceMute(playerUuid)
                .thenApply(maybePunishment -> maybePunishment.isPresent());

        isMutedFuture.thenAccept(isMuted -> {
            if (isMuted) {
                event.cancel();
            }
        });
    }
}
