package ret.tawny.controlbans.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class UuidUtil {

    /**
     * Asynchronously gets the UUID of a player from their name.
     * This is the recommended way to handle player lookups off the main thread.
     *
     * @param playerName The name of the player.
     * @return A CompletableFuture that will complete with the player's UUID, or null if not found.
     */
    public static CompletableFuture<UUID> getUuid(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            // This is thread-safe on modern Paper servers
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return (player.hasPlayedBefore() || player.isOnline()) ? player.getUniqueId() : null;
        });
    }
}