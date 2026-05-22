package ret.tawny.controlbans.services;

import de.maxhenkel.voicechat.api.BukkitVoicechatService;
import de.maxhenkel.voicechat.api.VoicechatConnection;
import de.maxhenkel.voicechat.api.VoicechatPlugin;
import de.maxhenkel.voicechat.api.events.EventRegistration;
import de.maxhenkel.voicechat.api.events.MicrophonePacketEvent;
import de.maxhenkel.voicechat.api.events.VoicechatServerStartedEvent;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.geysermc.floodgate.api.FloodgateApi;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class VoiceChatService implements VoicechatPlugin, Listener {

    private final ControlBansPlugin plugin;

    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();

    private final Map<UUID, Long> lastWarningTime = new ConcurrentHashMap<>();
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();

    private static final long WARNING_COOLDOWN_MS = 1000;

    public VoiceChatService(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public void initialize() {
        BukkitVoicechatService service = plugin.getServer().getServicesManager().load(BukkitVoicechatService.class);
        if (service != null) {
            service.registerPlugin(this);
            plugin.getServer().getPluginManager().registerEvents(this, plugin);
            plugin.getLogger().info("Simple Voice Chat integration registered.");
        } else {
            plugin.getLogger().warning("Simple Voice Chat plugin not found! Voice moderation disabled.");
        }
    }

    @Override
    public String getPluginId() {
        return "controlbans";
    }

    @Override
    public void registerEvents(EventRegistration registration) {
        registration.registerEvent(VoicechatServerStartedEvent.class, this::onServerStarted);
        registration.registerEvent(MicrophonePacketEvent.class, this::onMicrophonePacket);
    }

    private void onServerStarted(VoicechatServerStartedEvent event) {
        Bukkit.getOnlinePlayers().forEach(p -> updateStatus(p.getUniqueId()));
    }

    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || connection.getPlayer() == null) return;

        Player player = (Player) connection.getPlayer().getPlayer();
        if (player == null) return;

        UUID uuid = player.getUniqueId();

        if (!mutedPlayers.containsKey(uuid)) return;

        Long expiry = mutedPlayers.get(uuid);
        if (expiry != -1 && System.currentTimeMillis() > expiry) {
            unmutePlayer(uuid);
            return;
        }

        event.cancel();
        handleFeedback(player, expiry);
    }

    private void handleFeedback(Player player, Long expiry) {
        long now = System.currentTimeMillis();
        if (now - lastWarningTime.getOrDefault(player.getUniqueId(), 0L) < WARNING_COOLDOWN_MS) {
            return;
        }
        lastWarningTime.put(player.getUniqueId(), now);

        plugin.getSchedulerAdapter().runTask(() -> {
            if (expiry == -1) {
                player.sendActionBar(plugin.getLocaleManager().getMessage("actionbar.voice-muted"));
            } else {
                showBossBar(player, expiry);
            }
        });
    }

    private void showBossBar(Player player, long expiryTime) {
        final UUID uuid = player.getUniqueId();
        long remainingMillis = expiryTime - System.currentTimeMillis();
        String timeString = TimeUtil.formatDuration(remainingMillis / 1000);

        final Component title = plugin.getLocaleManager().getMessage("bossbar.voice-mute.title",
                Placeholder.unparsed("time_remaining", timeString));

        BossBar.Color color;
        try {
            color = BossBar.Color.valueOf(plugin.getConfig().getString("bossbar.voice-mute.color", "RED").toUpperCase());
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid bossbar color config, defaulting to RED: " + e.getMessage());
            color = BossBar.Color.RED;
        }

        BossBar.Overlay style;
        try {
            style = BossBar.Overlay.valueOf(plugin.getConfig().getString("bossbar.voice-mute.style", "PROGRESS").toUpperCase());
        } catch (Exception e) {
            plugin.getLogger().warning("Invalid bossbar style config, defaulting to PROGRESS: " + e.getMessage());
            style = BossBar.Overlay.PROGRESS;
        }

        final BossBar.Color finalColor = color;
        final BossBar.Overlay finalStyle = style;

        BossBar bar = activeBossBars.computeIfAbsent(uuid, k -> {
            BossBar b = BossBar.bossBar(title, 1.0f, finalColor, finalStyle);
            player.showBossBar(b);
            return b;
        });

        bar.name(title);

        plugin.getSchedulerAdapter().runTaskLater(() -> {
            if (System.currentTimeMillis() - lastWarningTime.getOrDefault(uuid, 0L) >= 1500) {
                BossBar existing = activeBossBars.remove(uuid);
                if (existing != null) {
                    player.hideBossBar(existing);
                }
            }
        }, 40L);
    }

    public void mutePlayer(UUID uuid) {
        updateStatus(uuid);
    }

    public void unmutePlayer(UUID uuid) {
        mutedPlayers.remove(uuid);
        lastWarningTime.remove(uuid);

        Player player = Bukkit.getPlayer(uuid);
        if (player != null) {
            BossBar bar = activeBossBars.remove(uuid);
            if (bar != null) player.hideBossBar(bar);
        }
    }

    public void updateStatus(UUID uuid) {
        if (plugin.getConfigManager().isGeyserEnabled()) {
            try {
                if (FloodgateApi.getInstance().isFloodgatePlayer(uuid)) return;
            } catch (NoClassDefFoundError | Exception ignored) {}
        }

        plugin.getPunishmentService().getActiveVoiceMute(uuid).thenAccept(opt -> {
            if (opt.isPresent()) {
                Punishment p = opt.get();
                mutedPlayers.put(uuid, p.getExpiryTime());
            } else {
                unmutePlayer(uuid);
            }
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        unmutePlayer(event.getPlayer().getUniqueId());
    }
}