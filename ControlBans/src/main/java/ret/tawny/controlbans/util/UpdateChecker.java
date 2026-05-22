package ret.tawny.controlbans.util;

import com.google.gson.JsonArray;
import com.google.gson.JsonParser;
import ret.tawny.controlbans.ControlBansPlugin;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

public class UpdateChecker {

    private static final String MODRINTH_PROJECT_ID = "2V1taxea";

    private static final String MODRINTH_API =
        "https://api.modrinth.com/v2/project/" + MODRINTH_PROJECT_ID + "/version?include_changelog=false";

    private final ControlBansPlugin plugin;
    private final HttpClient httpClient;

    private volatile String latestVersion = null;
    private volatile boolean updateAvailable = false;

    public UpdateChecker(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();
    }

    public CompletableFuture<Void> check() {
        String currentVersion = plugin.getDescription().getVersion();
        String userAgent = "tawny/ControlBans/" + currentVersion + " (discord.com/invite/AnQvddTZDg)";

        return CompletableFuture.runAsync(() -> {
            try {
                HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(MODRINTH_API))
                    .header("User-Agent", userAgent)
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();

                HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 404) {
                    plugin.getLogger().warning("[UpdateChecker] Project not found on Modrinth (404). " +
                        "Check that MODRINTH_PROJECT_ID is set to the correct numeric ID.");
                    return;
                }

                if (response.statusCode() != 200) {
                    plugin.getLogger().warning("[UpdateChecker] Modrinth API returned status "
                        + response.statusCode() + " — skipping update check.");
                    return;
                }

                JsonArray versions = JsonParser.parseString(response.body()).getAsJsonArray();

                if (versions.isEmpty()) {
                    return;
                }

                String latest = versions.get(0)
                    .getAsJsonObject()
                    .get("version_number")
                    .getAsString();

                latestVersion = latest;

                if (!currentVersion.equalsIgnoreCase(latest)) {
                    updateAvailable = true;
                    plugin.getLogger().info("[ControlBans] Update available: v" + latest
                        + " (running v" + currentVersion + ")");
                    plugin.getLogger().info("[ControlBans] https://modrinth.com/plugin/controlbans");
                } else {
                    updateAvailable = false;
                    plugin.getLogger().info("[ControlBans] Running latest version (v" + currentVersion + ").");
                }

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (Exception e) {
                plugin.getLogger().warning("[UpdateChecker] Could not reach Modrinth: " + e.getMessage());
            }
        });
    }

    public boolean isUpdateAvailable() {
        return updateAvailable;
    }

    public String getLatestVersion() {
        return latestVersion;
    }
}
