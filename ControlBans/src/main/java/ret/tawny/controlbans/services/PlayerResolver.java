package ret.tawny.controlbans.services;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.UuidUtil;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public class PlayerResolver {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final CacheService cacheService;

    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

    public PlayerResolver(ControlBansPlugin plugin, StorageInterface storage, CacheService cacheService) {
        this.plugin = plugin;
        this.storage = storage;
        this.cacheService = cacheService;
    }

    public CompletableFuture<UUID> getPlayerUuid(String playerName) {
        return cacheService.getOrCache("uuid_" + playerName.toLowerCase(), () -> {
            String bedrockPrefix = plugin.getConfigManager().getBedrockPrefix();
            if (plugin.getConfigManager().isGeyserEnabled() && playerName.startsWith(bedrockPrefix)) {
                try {
                    String rawGamertag = playerName.substring(bedrockPrefix.length());
                    return FloodgateApi.getInstance().getUuidFor(rawGamertag).thenApply(bedrockUuid -> {
                        if (bedrockUuid == null) return null;
                        try {
                            FloodgatePlayer fp = FloodgateApi.getInstance().getPlayer(bedrockUuid);
                            if (fp != null && fp.getLinkedPlayer() != null) return fp.getLinkedPlayer().getJavaUniqueId();
                        } catch (Exception ignored) {}
                        return bedrockUuid;
                    });
                } catch (Throwable t) { return CompletableFuture.completedFuture(null); }
            }
            return storage.getUuidByName(playerName).thenCompose(uuid -> {
                if (uuid != null) return CompletableFuture.completedFuture(uuid);
                return CompletableFuture.supplyAsync(() -> UuidUtil.lookupUuid(playerName));
            });
        }, plugin.getConfigManager().getPlayerLookupTTL());
    }

    public String getPlayerIp(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
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
