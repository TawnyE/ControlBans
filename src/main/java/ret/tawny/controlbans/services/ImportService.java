package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;
import org.bukkit.command.CommandSender;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.PunishmentDao;

import java.io.File;
import java.io.FileReader;
import java.lang.reflect.Type;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class ImportService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final PunishmentDao punishmentDao;
    private final Gson gson = new Gson();

    // CORRECTED CONSTRUCTOR
    public ImportService(ControlBansPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.punishmentDao = new PunishmentDao();
    }

    public void importFromVanilla(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            sender.sendMessage("§eStarting vanilla import from banned-players.json...");
            File bannedPlayersFile = new File("banned-players.json");
            int count = 0;

            if (!bannedPlayersFile.exists()) {
                sender.sendMessage("§cFile not found: banned-players.json. Make sure it's in your server's root directory.");
                return;
            }

            try (FileReader reader = new FileReader(bannedPlayersFile)) {
                Type listType = new TypeToken<List<Map<String, String>>>() {}.getType();
                List<Map<String, String>> bannedPlayers = gson.fromJson(reader, listType);

                if (bannedPlayers == null || bannedPlayers.isEmpty()) {
                    sender.sendMessage("§eNo bans found in banned-players.json.");
                    return;
                }

                for (Map<String, String> entry : bannedPlayers) {
                    Punishment p = Punishment.builder()
                            .type(PunishmentType.BAN)
                            .targetUuid(UUID.fromString(entry.get("uuid")))
                            .targetName(entry.get("name"))
                            .reason(entry.get("reason"))
                            .staffName(entry.get("source"))
                            .createdTime(parseVanillaDate(entry.get("created")))
                            .expiryTime(parseVanillaExpiry(entry.get("expires")))
                            .active(true)
                            .build();

                    databaseManager.executeAsync(conn -> punishmentDao.insertBan(conn, p)).join();
                    count++;
                }
                sender.sendMessage("§aSuccessfully imported " + count + " ban(s) from banned-players.json.");

            } catch (Exception e) {
                sender.sendMessage("§cAn error occurred while importing from banned-players.json: " + e.getMessage());
                plugin.getLogger().log(Level.SEVERE, "Vanilla import failed", e);
            }
        });
    }

    private long parseVanillaDate(String dateStr) {
        try {
            // Example format: 2023-01-01 12:00:00 +0000
            SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss Z");
            format.setTimeZone(TimeZone.getTimeZone("UTC"));
            return format.parse(dateStr).getTime();
        } catch (Exception e) {
            return System.currentTimeMillis();
        }
    }

    private long parseVanillaExpiry(String expiryStr) {
        if ("forever".equalsIgnoreCase(expiryStr)) {
            return -1L;
        }
        return parseVanillaDate(expiryStr);
    }
}