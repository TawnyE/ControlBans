package ret.tawny.controlbans.storage;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;
import redis.clients.jedis.exceptions.JedisConnectionException;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class RedisManager {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private JedisPool jedisPool;
    private ExecutorService pubSubExecutor;
    private JedisPubSub pubSubListener;
    private volatile boolean running = false;

    private static final String CHANNEL_PUNISHMENTS = "controlbans:punishments";
    private static final String CACHE_PREFIX = "controlbans:";

    public RedisManager(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
    }

    public void initialize() {
        if (!config.isRedisEnabled()) {
            plugin.getLogger().info("Redis is disabled in config.");
            return;
        }

        try {
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            poolConfig.setMaxTotal(10);
            poolConfig.setMaxIdle(5);
            poolConfig.setMinIdle(1);
            poolConfig.setTestOnBorrow(true);

            String password = config.getRedisPassword();
            if (password == null || password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort());
            } else {
                jedisPool = new JedisPool(poolConfig, config.getRedisHost(), config.getRedisPort(), 2000, password);
            }

            try (Jedis jedis = jedisPool.getResource()) {
                jedis.ping();
            }

            running = true;
            plugin.getLogger().info("Redis connection established to " + config.getRedisHost() + ":" + config.getRedisPort());

            if (config.isRedisPubSubEnabled()) {
                startPubSubListener();
            }

        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to Redis", e);
            running = false;
        }
    }

    private void startPubSubListener() {
        pubSubExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "ControlBans-Redis-PubSub");
            t.setDaemon(true);
            return t;
        });

        pubSubExecutor.submit(() -> {
            while (running) {
                try {
                    pubSubListener = new JedisPubSub() {
                        @Override
                        public void onMessage(String channel, String message) {
                            if (CHANNEL_PUNISHMENTS.equals(channel)) {
                                handlePunishmentMessage(message);
                            }
                        }
                    };

                    try (Jedis jedis = jedisPool.getResource()) {
                        plugin.getLogger().info("Redis pub/sub listener subscribed to channel: " + CHANNEL_PUNISHMENTS);
                        jedis.subscribe(pubSubListener, CHANNEL_PUNISHMENTS);
                    }

                } catch (JedisConnectionException e) {
                    if (running) {
                        plugin.getLogger().warning("Redis connection lost. Attempting reconnect in 5 seconds...");
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                            Thread.currentThread().interrupt();
                            break;
                        }
                    }
                } catch (Exception e) {
                    if (running) {
                        plugin.getLogger().log(Level.SEVERE, "Unexpected error in Redis Pub/Sub thread", e);
                        try {
                            Thread.sleep(5000);
                        } catch (InterruptedException ignored) {
                            break;
                        }
                    }
                }
            }
        });
    }

    private void handlePunishmentMessage(String message) {
        try {
            String[] parts = message.split(":");
            if (parts.length >= 3) {
                String action = parts[0];
                String uuid = parts[1];

                if ("BAN".equals(action) || "UNBAN".equals(action) || "MUTE".equals(action) || "UNMUTE".equals(action)) {
                    plugin.getCacheService().invalidatePlayerPunishments(java.util.UUID.fromString(uuid));
                    plugin.getLogger().fine("Cache invalidated for " + uuid + " via Redis pub/sub");
                }
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to handle Redis punishment message: " + message, e);
        }
    }

    public void publishPunishmentAction(String action, java.util.UUID uuid, String type) {
        if (!running || jedisPool == null) return;

        plugin.getSchedulerAdapter().runTaskAsynchronously(() -> {
            try (Jedis jedis = jedisPool.getResource()) {
                String message = action + ":" + uuid.toString() + ":" + type;
                jedis.publish(CHANNEL_PUNISHMENTS, message);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to publish punishment action to Redis", e);
            }
        });
    }

    public void cacheBan(java.util.UUID uuid, String jsonData) { cacheSet("ban:" + uuid.toString(), jsonData); }
    public String getCachedBan(java.util.UUID uuid) { return cacheGet("ban:" + uuid.toString()); }
    public void cacheMute(java.util.UUID uuid, String jsonData) { cacheSet("mute:" + uuid.toString(), jsonData); }
    public String getCachedMute(java.util.UUID uuid) { return cacheGet("mute:" + uuid.toString()); }

    public void invalidatePlayer(java.util.UUID uuid) {
        if (!running || jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            jedis.del(CACHE_PREFIX + "ban:" + uuid.toString());
            jedis.del(CACHE_PREFIX + "mute:" + uuid.toString());
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to invalidate Redis cache for " + uuid, e);
        }
    }

    private void cacheSet(String key, String value) {
        if (!running || jedisPool == null) return;
        try (Jedis jedis = jedisPool.getResource()) {
            int ttl = config.getRedisCacheTTL();
            if (ttl > 0) jedis.setex(CACHE_PREFIX + key, ttl, value);
            else jedis.set(CACHE_PREFIX + key, value);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to set Redis cache", e);
        }
    }

    private String cacheGet(String key) {
        if (!running || jedisPool == null) return null;
        try (Jedis jedis = jedisPool.getResource()) {
            return jedis.get(CACHE_PREFIX + key);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to get Redis cache", e);
            return null;
        }
    }

    public boolean isRunning() { return running; }

    public void shutdown() {
        running = false;
        if (pubSubListener != null && pubSubListener.isSubscribed()) {
            try { pubSubListener.unsubscribe(); } catch (Exception ignored) {}
        }
        if (pubSubExecutor != null) pubSubExecutor.shutdownNow();
        if (jedisPool != null && !jedisPool.isClosed()) {
            jedisPool.close();
            plugin.getLogger().info("Redis connection pool closed.");
        }
    }
}