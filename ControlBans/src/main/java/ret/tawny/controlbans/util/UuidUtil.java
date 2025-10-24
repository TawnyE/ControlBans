package ret.tawny.controlbans.util;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;

import java.util.UUID;
import java.util.regex.Pattern;

public final class UuidUtil {

    // **THE FIX (Part 1):** A regex to validate usernames before sending them to Mojang.
    // Minecraft usernames can only contain letters, numbers, and underscores.
    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");

    public static UUID lookupUuid(String playerName) {
        if (playerName == null || !VALID_USERNAME_PATTERN.matcher(playerName).matches()) {
            return null;
        }

        try {
            OfflinePlayer player = Bukkit.getOfflinePlayer(playerName);
            return (player.hasPlayedBefore() || player.isOnline()) ? player.getUniqueId() : null;
        } catch (Exception ignored) {
            return null;
        }
    }
}