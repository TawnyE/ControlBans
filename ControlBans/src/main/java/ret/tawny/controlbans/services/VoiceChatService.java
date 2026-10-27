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
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.geysermc.floodgate.api.FloodgateApi;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Single source of truth for enforcing voice mutes.
 *
 * <p>Commands ({@code /voicemute}, {@code /tempvoicemute}, {@code /voiceunmute})
 * only create or remove punishments through {@link PunishmentService}. This
 * service is the only component that decides whether a microphone packet is
 * blocked.</p>
 *
 * <h2>Reconnect safety</h2>
 * <ul>
 *   <li>The {@code mutedPlayers} cache is a <em>performance</em> cache only —
 *       it is NOT the source of truth. The database is.</li>
 *   <li>On quit, only UI state (boss bars + warning timers) is cleaned up via
 *       {@link #cleanupPlayer(UUID)}. The mute itself is preserved in the
 *       cache and in the database, so a reconnect sees the same state.</li>
 *   <li>On join, {@link #updateStatus(UUID)} reloads the player's mute state
 *       from the database. This also handles server restarts and plugin
 *       reloads, because the cache starts empty in both cases.</li>
 *   <li>In the microphone-packet path, if a player is not in the cache we
 *       treat them as <em>potentially muted</em>, trigger an async
 *       {@link #updateStatus(UUID)} lookup, and cancel the packet until the
 *       lookup resolves. Once the lookup completes, subsequent packets hit
 *       the cache normally.</li>
 * </ul>
 *
 * <h2>Permanent vs temp mutes</h2>
 * Both are stored in the same {@code controlbans_voicemutes} table; the only
 * difference is the {@code until} column (-1 for permanent). Both survive
 * reconnects, reloads, and restarts identically because the database is the
 * source of truth.
 */
public class VoiceChatService implements VoicechatPlugin, Listener {

    private final ControlBansPlugin plugin;

    /**
     * Sentinel value stored in {@link #mutedPlayers} to indicate that the
     * player has been confirmed <em>not</em> to have an active voice mute.
     * We need an explicit sentinel because {@link ConcurrentHashMap} does
     * not permit null values, and we need to distinguish "confirmed unmuted"
     * from "unknown / not yet looked up".
     * Long.MIN_VALUE is used (not 0) because expiry 0 means permanent in
     * legacy rows and must never read as unmuted.
     */
    private static final Long UNMUTED_SENTINEL = Long.MIN_VALUE;

    /**
     * Cache of voice-mute expiries, keyed by player UUID.
     * <ul>
     *   <li>absent                — player's status is unknown; the caller must call {@link #updateStatus(UUID)}.</li>
     *   <li>{@code Long.MIN_VALUE} (sentinel) — player is confirmed NOT muted.</li>
     *   <li>{@code -1L}           — player is permanently muted (0 is normalized to -1 on read).</li>
     *   <li>any other value       — player is muted until that epoch-millis timestamp.</li>
     * </ul>
     * The database is always the source of truth; this cache is a
     * performance optimization to avoid hitting storage on every microphone
     * packet.
     */
    private final Map<UUID, Long> mutedPlayers = new ConcurrentHashMap<>();

    /** Cooldown timestamp for the "you are muted" action-bar warning. */
    private final Map<UUID, Long> lastWarningTime = new ConcurrentHashMap<>();

    /** Active boss bars shown to muted players (temp mutes only). */
    private final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();

    /**
     * Per-player "lookup in flight" flag. While a database lookup for a
     * player is in progress, we conservatively block their microphone packets
     * rather than risk letting an un-cached mute leak through.
     */
    private final Map<UUID, Boolean> pendingLookups = new ConcurrentHashMap<>();

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
        // On voice-chat server (re)start, refresh mute state for every online
        // player. This handles plugin reloads and voice-chat reconnects.
        // Copy the collection first: this runs on the voice thread, not main.
        java.util.List<UUID> online;
        try {
            online = new java.util.ArrayList<>();
            for (Player p : Bukkit.getOnlinePlayers()) online.add(p.getUniqueId());
        } catch (Exception e) {
            return;
        }
        for (UUID uuid : online) updateStatus(uuid);
    }

    /**
     * Microphone-packet handler. This is the <strong>only</strong> place
     * voice packets are blocked.
     *
     * <p>If the player is not in the {@link #mutedPlayers} cache, we treat
     * them as "unknown" rather than "unmuted": we trigger an async
     * {@link #updateStatus(UUID)} lookup and cancel the packet until the
     * lookup resolves. This prevents the reconnect bypass where a fresh
     * cache would let muted players speak.</p>
     */
    private void onMicrophonePacket(MicrophonePacketEvent event) {
        VoicechatConnection connection = event.getSenderConnection();
        if (connection == null || connection.getPlayer() == null) return;

        Player player = (Player) connection.getPlayer().getPlayer();
        if (player == null) return;

        UUID uuid = player.getUniqueId();

        Long expiry = mutedPlayers.get(uuid);

        // Cache miss (absent from map) → trigger an async DB lookup. Until
        // the lookup completes, conservatively block the packet. The next
        // packet will hit the cache.
        if (expiry == null && !mutedPlayers.containsKey(uuid)) {
            if (pendingLookups.putIfAbsent(uuid, Boolean.TRUE) == null) {
                plugin.getLogger().info("[VoiceChat] UUID=" + uuid
                        + " cacheMiss=true — triggering updateStatus lookup and cancelling packet.");
                updateStatus(uuid).whenComplete((ignored, error) -> {
                    pendingLookups.remove(uuid);
                    if (error != null) {
                        plugin.getLogger().warning("[VoiceChat] UUID=" + uuid
                                + " updateStatus failed: " + error.getMessage());
                    }
                });
            }
            event.cancel();
            return;
        }

        // Cache hit. If the player is confirmed unmuted, let the packet through.
        if (UNMUTED_SENTINEL.equals(expiry)) {
            return;
        }

        // Temp mute expired → drop the mute and let the packet through.
        if (expiry != -1L && !UNMUTED_SENTINEL.equals(expiry) && System.currentTimeMillis() > expiry) {
            plugin.getLogger().info("[VoiceChat] UUID=" + uuid
                    + " tempVoiceMute expired (expiry=" + expiry
                    + ") — removing from cache.");
            mutedPlayers.remove(uuid);
            // cleanupPlayer touches Bukkit API — schedule on main.
            plugin.getSchedulerAdapter().runTask(() -> cleanupPlayer(uuid));
            return;
        }

        // Player is muted. Block the packet and show feedback.
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
            if (expiry == -1L) {
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

    /**
     * Public entry point used by {@link PunishmentService #postVoiceMuteActions}
     * after a new voice mute is issued. Forces a cache refresh so the new
     * mute takes effect immediately for online players.
     */
    public void mutePlayer(UUID uuid) {
        // Block immediately (sync) to close the grace window, then refresh from DB.
        mutedPlayers.put(uuid, -1L);
        updateStatus(uuid);
    }

    /**
     * Remove a player's voice mute from the cache and clean up UI state.
     * Used by {@link PunishmentService#unVoiceMutePlayer} after the
     * punishment has been deactivated in the database.
     *
     * <p>This is the only method that removes the player from
     * {@link #mutedPlayers} as a side effect of an actual unmute action.
     * The {@link #onQuit} handler does <strong>not</strong> call this —
     * see {@link #cleanupPlayer(UUID)} for why.</p>
     */
    public void unmutePlayer(UUID uuid) {
        mutedPlayers.put(uuid, UNMUTED_SENTINEL);
        plugin.getSchedulerAdapter().runTask(() -> cleanupPlayer(uuid));
    }

    /**
     * Drop only the UI state for a player (boss bars + warning timers),
     * without touching the {@link #mutedPlayers} cache.
     *
     * <p>Called from {@link #onQuit} so that a player who disconnects and
     * reconnects does not lose their mute. The mute state stays in the
     * cache (and survives in the database regardless), so on rejoin the
     * player is still muted. The boss bar and warning timer are UI
     * artifacts tied to the previous Player instance and must be cleared
     * to avoid leaking them.</p>
     */
    public void cleanupPlayer(UUID uuid) {
        lastWarningTime.remove(uuid);

        BossBar bar = activeBossBars.remove(uuid);
        if (bar != null) {
            try {
                Player player = Bukkit.getPlayer(uuid);
                if (player != null) player.hideBossBar(bar);
            } catch (Exception ignored) {}
        }
        pendingLookups.remove(uuid);
    }

    /**
     * Refresh the cached voice-mute state for a player from the database.
     *
     * <p>After this call returns, the player will be present in
     * {@link #mutedPlayers} with one of:
     * <ul>
     *   <li>{@code -1L}            — permanently muted.</li>
     *   <li>positive timestamp    — temporarily muted until that time.</li>
     *   <li>{@code 0L} (sentinel)  — confirmed NOT muted.</li>
     * </ul>
     * The sentinel value is what distinguishes "confirmed unmuted" from
     * "unknown" (absent from the map).
     */
    public CompletableFuture<Void> updateStatus(UUID uuid) {
        if (plugin.getConfigManager().isGeyserEnabled()) {
            try {
                if (FloodgateApi.getInstance().isFloodgatePlayer(uuid)) {
                    // Bedrock players can't use Simple Voice Chat, so treat
                    // them as definitively not muted.
                    mutedPlayers.put(uuid, UNMUTED_SENTINEL);
                    return CompletableFuture.completedFuture(null);
                }
            } catch (NoClassDefFoundError | Exception ignored) {}
        }

        return plugin.getPunishmentService().getActiveVoiceMute(uuid).thenAccept(opt -> {
            if (opt.isPresent() && !opt.get().isExpired()) {
                Punishment p = opt.get();
                long expiry = p.getExpiryTime() <= 0 ? -1L : p.getExpiryTime();
                mutedPlayers.put(uuid, expiry);
                plugin.getLogger().info("[VoiceChat] UUID=" + uuid
                        + " updateStatus=muted"
                        + " expiry=" + (expiry == -1L ? "permanent" : expiry));
            } else {
                mutedPlayers.put(uuid, UNMUTED_SENTINEL);
                plugin.getLogger().info("[VoiceChat] UUID=" + uuid
                        + " updateStatus=unmuted");
            }
        }).exceptionally(error -> {
            plugin.getLogger().warning("[VoiceChat] UUID=" + uuid
                    + " updateStatus failed: " + error.getMessage()
                    + " — leaving existing cache state untouched.");
            return null;
        });
    }

    public void invalidate(UUID uuid) {
        mutedPlayers.remove(uuid);
    }

    public void shutdown() {
        for (java.util.Map.Entry<UUID, BossBar> e : activeBossBars.entrySet()) {
            try {
                Player p = Bukkit.getPlayer(e.getKey());
                if (p != null) p.hideBossBar(e.getValue());
            } catch (Exception ignored) {}
        }
        activeBossBars.clear();
        mutedPlayers.clear();
        lastWarningTime.clear();
        pendingLookups.clear();
        try {
            org.bukkit.event.HandlerList.unregisterAll(this);
        } catch (Exception ignored) {}
    }

    /**
     * On quit, only clean up UI state. Do NOT clear the mute cache —
     * the player's mute must survive the disconnect/reconnect cycle.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        cleanupPlayer(event.getPlayer().getUniqueId());
    }

    /**
     * On join, refresh the mute state from the database. This is the
     * reconnect-safe path: even if the cache was cleared on a previous
     * server restart or plugin reload, the database still has the active
     * voice mute and we re-load it here.
     */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        updateStatus(event.getPlayer().getUniqueId());
    }
}
