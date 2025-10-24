package ret.tawny.controlbans.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class UuidUtil {

    // **THE FIX (Part 1):** A regex to validate usernames before sending them to Mojang.
    // Minecraft usernames can only contain letters, numbers, and underscores.
    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    /**
     * Asynchronously gets the UUID of a player from their name.
     * This is the recommended way to handle player lookups off the main thread.
     *
     * @param playerName The name of the player.
     * @return A CompletableFuture that will complete with the player's UUID, or null if not found.
     */
    public static CompletableFuture<UUID> getUuid(String playerName) {
        return CompletableFuture.supplyAsync(() -> {
            // **THE FIX (Part 2):** First, validate the username. If it contains invalid characters,
            // don't even bother trying to look it up. This prevents the crash.
            if (playerName == null || !VALID_USERNAME_PATTERN.matcher(playerName).matches()) {
                return null;
            }

            try {
                // **THE FIX (Part 3):** Wrap the API call in a try-catch block as a final failsafe.
                // If Mojang's servers are down or return any other error, we handle it gracefully
                // instead of spamming the console.
                OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
                return (player.hasPlayedBefore() || player.isOnline()) ? player.getUniqueId() : null;
            } catch (Exception e) {
                // An error occurred during lookup (e.g., Mojang API issue).
                // We can safely ignore it and return null, as the player was not found.
                return null;
            }
        });
    }
}