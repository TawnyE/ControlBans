package ret.tawny.controlbans.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ret.tawny.controlbans.config.ConfigManager;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CacheService {

    private final ConfigManager config;

    private final Cache<String, Object> generalCache;
    private final Cache<UUID, Boolean> activePunishmentCache;
    private final Cache<String, List<String>> offlineSuggestions;

    public CacheService(ConfigManager config) {
        this.config = config;

        this.generalCache = Caffeine.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .expireAfterWrite(config.getPlayerLookupTTL(), TimeUnit.SECONDS)
                .build();

        this.activePunishmentCache = Caffeine.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .expireAfterWrite(config.getPunishmentCheckTTL(), TimeUnit.SECONDS)
                .build();

        this.offlineSuggestions = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(30, TimeUnit.SECONDS)
                .build();
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> getOrCache(String key, CacheLoader<T> loader, long ttlSeconds) {
        if (!config.isCacheEnabled()) {
            return loader.load();
        }

        T cached = (T) generalCache.getIfPresent(key);
        if (cached != null) {
            return CompletableFuture.completedFuture(cached);
        }

        return loader.load().whenComplete((result, throwable) -> {
            if (throwable == null && result != null) {
                generalCache.put(key, result);
            }
        });
    }

    public void cacheOfflineSuggestions(String query, List<String> names) {
        offlineSuggestions.put(query.toLowerCase(), names);
    }

    public List<String> getOfflineSuggestions(String query) {
        return offlineSuggestions.getIfPresent(query.toLowerCase());
    }

    public void invalidate(String key) {
        generalCache.invalidate(key);
    }

    public void invalidatePlayerPunishments(UUID uuid) {
        String uuidStr = uuid.toString();
        generalCache.asMap().keySet().removeIf(key ->
                key.startsWith("ban_" + uuidStr) ||
                        key.startsWith("mute_" + uuidStr) ||
                        key.startsWith("voicemute_" + uuidStr) ||
                        key.startsWith("history_" + uuidStr)
        );
        activePunishmentCache.invalidate(uuid);
    }

    public void clear() {
        generalCache.invalidateAll();
        activePunishmentCache.invalidateAll();
        offlineSuggestions.invalidateAll();
    }

    public void invalidateAll() {
        clear();
    }

    @FunctionalInterface
    public interface CacheLoader<T> {
        CompletableFuture<T> load();
    }
}
