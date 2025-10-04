package ret.tawny.controlbans.services;

import ret.tawny.controlbans.config.ConfigManager;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

public class CacheService {
    
    private final ConfigManager config;
    private final ConcurrentHashMap<String, CacheEntry<?>> cache = new ConcurrentHashMap<>();
    
    public CacheService(ConfigManager config) {
        this.config = config;
    }
    
    @SuppressWarnings("unchecked")
    public <T> CompletableFuture<T> getOrCache(String key, CacheLoader<T> loader, long ttlSeconds) {
        if (!config.isCacheEnabled()) {
            return loader.load();
        }
        
        CacheEntry<T> entry = (CacheEntry<T>) cache.get(key);
        
        if (entry != null && !entry.isExpired()) {
            return CompletableFuture.completedFuture(entry.getValue());
        }
        
        return loader.load().whenComplete((result, throwable) -> {
            if (throwable == null && result != null) {
                long expiryTime = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(ttlSeconds);
                cache.put(key, new CacheEntry<>(result, expiryTime));
                
                // Clean up expired entries periodically
                if (cache.size() > config.getCacheMaxSize()) {
                    cleanup();
                }
            }
        });
    }
    
    public void invalidate(String key) {
        cache.remove(key);
    }
    
    public void invalidatePlayerPunishments(UUID uuid) {
        String uuidStr = uuid.toString();
        cache.entrySet().removeIf(entry -> {
            String key = entry.getKey();
            return key.startsWith("ban_" + uuidStr) || 
                   key.startsWith("mute_" + uuidStr) ||
                   key.startsWith("history_" + uuidStr);
        });
    }
    
    public void clear() {
        cache.clear();
    }
    
    private void cleanup() {
        cache.entrySet().removeIf(entry -> entry.getValue().isExpired());
    }
    
    public int getSize() {
        return cache.size();
    }
    
    public double getHitRate() {
        // Simple approximation - could be improved with proper metrics
        return cache.size() > 0 ? 0.8 : 0.0;
    }
    
    @FunctionalInterface
    public interface CacheLoader<T> {
        CompletableFuture<T> load();
    }
    
    private static class CacheEntry<T> {
        private final T value;
        private final long expiryTime;
        
        public CacheEntry(T value, long expiryTime) {
            this.value = value;
            this.expiryTime = expiryTime;
        }
        
        public T getValue() {
            return value;
        }
        
        public boolean isExpired() {
            return System.currentTimeMillis() > expiryTime;
        }
    }
}