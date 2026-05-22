package ret.tawny.controlbans.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.logging.Level;
import java.util.regex.Pattern;

public final class UuidUtil {

    private static final Pattern VALID_USERNAME_PATTERN = Pattern.compile("^[a-zA-Z0-9_]{3,16}$");
    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5))
            .build();

    public static UUID lookupUuid(String playerName) {
        if (playerName == null || !VALID_USERNAME_PATTERN.matcher(playerName).matches()) return null;

        for (Player player : Bukkit.getOnlinePlayers()) {
            if (player.getName().equalsIgnoreCase(playerName)) {
                return player.getUniqueId();
            }
        }

        return fetchFromMojang(playerName);
    }

    private static UUID fetchFromMojang(String playerName) {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create("https://api.mojang.com/users/profiles/minecraft/" + playerName))
                    .GET()
                    .build();

            HttpResponse<String> response = HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonObject json = JsonParser.parseString(response.body()).getAsJsonObject();
                if (json.has("id")) {
                    return parseUuidFromless(json.get("id").getAsString());
                }
            } else if (response.statusCode() == 429) {
                Bukkit.getLogger().warning("[ControlBans] Mojang API Rate Limit reached while looking up: " + playerName);
            }
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[ControlBans] Failed Mojang UUID lookup for: " + playerName, e);
        }
        return null;
    }

    private static UUID parseUuidFromless(String id) {
        if (id == null || id.length() != 32) return null;
        try {
            return UUID.fromString(
                    id.substring(0, 8) + "-" +
                            id.substring(8, 12) + "-" +
                            id.substring(12, 16) + "-" +
                            id.substring(16, 20) + "-" +
                            id.substring(20, 32)
            );
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}