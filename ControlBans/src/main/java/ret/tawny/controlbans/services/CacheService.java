package ret.tawny.controlbans.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import ret.tawny.controlbans.config.ConfigManager;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

public class CacheService {

    private final ConfigManager config;

    private final Cache<String, CompletableFuture<Object>> generalCache;
    private final Cache<String, List<String>> offlineSuggestions;

    public CacheService(ConfigManager config) {
        this.config = config;

        this.generalCache = Caffeine.newBuilder()
                .maximumSize(config.getCacheMaxSize())
                .build();

        this.offlineSuggestions = Caffeine.newBuilder()
                .maximumSize(500)
                .expireAfterWrite(config.getOfflineSuggestionsTTL(), TimeUnit.SECONDS)
                .build();
    }

    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> getOrCache(String key, CacheLoader<T> loader, long ttlSeconds) {
        if (!config.isCacheEnabled()) {
            return loader.load();
        }

        long ttl = ttlSeconds > 0 ? ttlSeconds : config.getPunishmentCheckTTL();
        long expireAt = System.currentTimeMillis() + (ttl * 1000L);

        // Coalesced load: concurrent callers for the same key share ONE load future.
        CompletableFuture<Object> future = generalCache.asMap().computeIfAbsent(key, k -> {
            CompletableFuture<Object> loading = loader.load()
                    // Null results are delivered to callers but not retained (matches previous behavior)
                    .thenApply(result -> result == null ? null : (Object) new TimedValue(result, expireAt));
            // Failures must not be cached permanently — evict the failed future.
            loading.whenComplete((value, error) -> {
                if (error != null || value == null) {
                    generalCache.asMap().remove(key, loading);
                }
            });
            return loading;
        });

        return future.thenCompose(value -> {
            if (value == null) {
                return CompletableFuture.completedFuture(null);
            }
            TimedValue timed = (TimedValue) value;
            if (timed.isExpired()) {
                generalCache.asMap().remove(key, future);
                return getOrCache(key, loader, ttlSeconds);
            }
            return CompletableFuture.completedFuture((T) timed.value());
        });
    }

    private record TimedValue(Object value, long expireAt) {
        boolean isExpired() { return System.currentTimeMillis() > expireAt; }
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

    public void invalidatePlayerPunishments(java.util.UUID uuid) {
        String uuidStr = uuid.toString();
        generalCache.invalidate("ban_" + uuidStr);
        generalCache.invalidate("mute_" + uuidStr);
        generalCache.invalidate("voicemute_" + uuidStr);
        // History entries are keyed with the limit ("history_<uuid>_<limit>") — evict all of them.
        generalCache.asMap().keySet().removeIf(key -> key.startsWith("history_" + uuidStr));
        generalCache.invalidate("alts_" + uuidStr);
        // Name->UUID entries are keyed "uuid_<lowercase-name>" (see PlayerResolver),
        // not "uuid_<uuid>". Evict any uuid_ entry resolving to this UUID.
        generalCache.asMap().entrySet().removeIf(e -> {
            String k = e.getKey();
            if (k == null || !k.startsWith("uuid_")) return false;
            try {
                Object v = e.getValue() == null ? null : e.getValue().getNow(null);
                if (v instanceof TimedValue tv) {
                    Object inner = tv.value();
                    if (inner instanceof java.util.UUID u) return u.equals(uuid);
                }
            } catch (Exception ignored) {}
            return false;
        });
    }

    public void clear() {
        generalCache.invalidateAll();
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
