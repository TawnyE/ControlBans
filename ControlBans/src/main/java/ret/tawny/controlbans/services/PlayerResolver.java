package ret.tawny.controlbans.services;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public class PlayerResolver {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final CacheService cacheService;

    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

    // Short negative cache for unknown names to avoid hammering DB + Mojang on every tab-complete.
    private final java.util.concurrent.ConcurrentHashMap<String, Long> missCache = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long MISS_TTL_MS = 60_000L;

    // Dedicated daemon pool for blocking Mojang lookups (never the common pool)
    private static final ExecutorService MOJANG_LOOKUP = Executors.newCachedThreadPool(runnable -> {
        Thread t = new Thread(runnable, "ControlBans-MojangLookup");
        t.setDaemon(true);
        return t;
    });

    public static void shutdown() {
        MOJANG_LOOKUP.shutdownNow();
    }

    private static CompletableFuture<UUID> lookupMojangUuid(String playerName) {
        return CompletableFuture.supplyAsync(() -> UuidUtil.lookupUuid(playerName), MOJANG_LOOKUP)
                .orTimeout(5, TimeUnit.SECONDS)
                .exceptionally(ex -> null);
    }

    public PlayerResolver(ControlBansPlugin plugin, StorageInterface storage, CacheService cacheService) {
        this.plugin = plugin;
        this.storage = storage;
        this.cacheService = cacheService;
    }

    public CompletableFuture<UUID> getPlayerUuid(String playerName) {
        if (playerName == null) return CompletableFuture.completedFuture(null);
        String key = playerName.toLowerCase(java.util.Locale.ROOT);
        Long missAt = missCache.get(key);
        if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) {
            return CompletableFuture.completedFuture(null);
        }
        return cacheService.getOrCache("uuid_" + key, () -> {
            String bedrockPrefix = plugin.getConfigManager().getBedrockPrefix();
            if (plugin.getConfigManager().isGeyserEnabled() && playerName.startsWith(bedrockPrefix)) {
                try {
                    String rawGamertag = playerName.substring(bedrockPrefix.length());
                    return FloodgateApi.getInstance().getUuidFor(rawGamertag).thenCompose(bedrockUuid -> {
                        if (bedrockUuid != null) {
                            try {
                                FloodgatePlayer fp = FloodgateApi.getInstance().getPlayer(bedrockUuid);
                                if (fp != null && fp.getLinkedPlayer() != null) return CompletableFuture.completedFuture(fp.getLinkedPlayer().getJavaUniqueId());
                            } catch (Exception ignored) {}
                            return CompletableFuture.completedFuture(bedrockUuid);
                        }
                        return storage.getUuidByName(playerName).thenCompose(uuid -> {
                            if (uuid != null) return CompletableFuture.completedFuture(uuid);
                            return lookupMojangUuid(playerName);
                        });
                    }).exceptionally(t -> null).thenCompose(result -> {
                        if (result != null) return CompletableFuture.completedFuture(result);
                        return storage.getUuidByName(playerName).thenCompose(uuid -> {
                            if (uuid != null) return CompletableFuture.completedFuture(uuid);
                            return lookupMojangUuid(playerName);
                        });
                    });
                } catch (Throwable t) { 
                    // Fallthrough to standard lookup
                }
            }
            return storage.getUuidByName(playerName).thenCompose(uuid -> {
                if (uuid != null) return CompletableFuture.completedFuture(uuid);
                return fallbackUuidForUnknownPlayer(playerName);
            });
        }, plugin.getConfigManager().getPlayerLookupTTL()).whenComplete((uuid, ex) -> {
            if (ex == null && uuid == null) missCache.put(key, System.currentTimeMillis());
            if (uuid != null) missCache.remove(key);
        });
    }

    /**
     * Resolution for a name with NO local record. On offline-mode servers the
     * Mojang API must never be trusted: a valid-looking name can belong to a
     * real Mojang account owned by a stranger, which would redirect the
     * punishment to the wrong identity while the actual player joins with an
     * offline UUID. On offline servers an unseen player will join with the
     * offline UUID, so that is the only correct guess.
     */
    private CompletableFuture<UUID> fallbackUuidForUnknownPlayer(String playerName) {
        if (!org.bukkit.Bukkit.getOnlineMode()) {
            return CompletableFuture.completedFuture(java.util.UUID.nameUUIDFromBytes(
                    ("OfflinePlayer:" + playerName).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        }
        return lookupMojangUuid(playerName);
    }

    public String getPlayerIp(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }

    public CompletableFuture<OfflinePlayer> getOfflineTarget(String target) {
        Player online = Bukkit.getPlayer(target);
        if (online != null) {
            return CompletableFuture.completedFuture(online);
        }
        online = Bukkit.getPlayerExact(target);
        if (online != null) {
            return CompletableFuture.completedFuture(online);
        }
        return getPlayerUuid(target).thenApply(uuid -> uuid != null ? Bukkit.getOfflinePlayer(uuid) : null);
    }

    public static boolean isValidIpv4(String ip) {
        if (ip == null || !IP_PATTERN.matcher(ip).matches()) return false;
        String[] parts = ip.split("\\.", -1);
        for (String p : parts) {
            try {
                int v = Integer.parseInt(p);
                if (v < 0 || v > 255) return false;
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return true;
    }

    public CompletableFuture<String> getIpFromTarget(String target) {
        if (IP_PATTERN.matcher(target).matches()) return CompletableFuture.completedFuture(target);
        return getPlayerUuid(target).thenCompose(uuid -> {
            if (uuid == null) return CompletableFuture.completedFuture(null);
            return storage.getLastIpForUuid(uuid).thenCompose(ip -> {
                if (ip == null) return CompletableFuture.failedFuture(new IllegalStateException("no-ip-on-record"));
                return CompletableFuture.completedFuture(ip);
            });
        });
    }

    public boolean isIpPattern(String input) {
        return IP_PATTERN.matcher(input).matches();
    }
}
