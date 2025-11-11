package ret.tawny.controlbans.services;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.dao.PunishmentDao;
import ret.tawny.controlbans.util.IdUtil;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class ImportService {

    private final ControlBansPlugin plugin;
    private final DatabaseManager databaseManager;
    private final PunishmentDao punishmentDao;

    public ImportService(ControlBansPlugin plugin, DatabaseManager databaseManager) {
        this.plugin = plugin;
        this.databaseManager = databaseManager;
        this.punishmentDao = new PunishmentDao();
    }

    public void importFromEssentials(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            if (Bukkit.getPluginManager().getPlugin("Essentials") == null) {
                sender.sendMessage("§cEssentials is not installed on this server.");
                return;
            }

            File essentialsDataFolder = Bukkit.getPluginManager().getPlugin("Essentials").getDataFolder();
            File userdataFolder = new File(essentialsDataFolder, "userdata");

            if (!userdataFolder.exists() || !userdataFolder.isDirectory()) {
                sender.sendMessage("§cEssentials userdata folder not found!");
                sender.sendMessage("§cLooked in: " + userdataFolder.getPath());
                return;
            }

            sender.sendMessage("§eStarting Essentials import... This may take a while.");
            int muteCount = 0;
            File[] userFiles = userdataFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".yml"));

            if (userFiles == null || userFiles.length == 0) {
                sender.sendMessage("§eNo user data files found in Essentials userdata folder.");
                return;
            }

            for (File userFile : userFiles) {
                try {
                    YamlConfiguration userData = YamlConfiguration.loadConfiguration(userFile);
                    if (userData.getBoolean("muted", false)) {
                        long timeout = userData.getLong("mute-timeout", 0);
                        long created = userData.getLong("timestamps.mute", System.currentTimeMillis());
                        String reason = userData.getString("mute-reason", "Imported from Essentials");
                        UUID uuid = UUID.fromString(userFile.getName().replace(".yml", ""));

                        Punishment.Builder builder = Punishment.builder()
                                .punishmentId(IdUtil.generatePunishmentId())
                                .targetUuid(uuid)
                                .reason(reason)
                                .staffName("Imported")
                                .createdTime(created)
                                .active(true);

                        if (timeout > 0 && timeout > System.currentTimeMillis()) {
                            builder.type(PunishmentType.TEMPMUTE).expiryTime(timeout);
                        } else {
                            builder.type(PunishmentType.MUTE).expiryTime(-1);
                        }

                        Punishment punishment = builder.build();
                        databaseManager.executeAsync(conn -> punishmentDao.insertMute(conn, punishment)).join();
                        muteCount++;
                    }
                } catch (Exception e) {
                    plugin.getLogger().log(Level.WARNING, "Failed to import Essentials user file: " + userFile.getName(), e);
                }
            }
            sender.sendMessage("§aSuccessfully imported " + muteCount + " mute(s) from Essentials.");
        });
    }

    public void importFromLiteBans(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            sender.sendMessage("§eConnecting to LiteBans source database...");
            HikariConfig sourceConfig = new HikariConfig();
            String dbType = plugin.getConfig().getString("import.sources.litebans.type", "sqlite");

            try {
                switch (dbType.toLowerCase()) {
                    case "mysql", "mariadb" -> {
                        sourceConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
                        sourceConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s",
                                plugin.getConfig().getString("import.sources.litebans.host"),
                                plugin.getConfig().getInt("import.sources.litebans.port"),
                                plugin.getConfig().getString("import.sources.litebans.database")));
                        sourceConfig.setUsername(plugin.getConfig().getString("import.sources.litebans.username"));
                        sourceConfig.setPassword(plugin.getConfig().getString("import.sources.litebans.password"));
                    }
                    case "postgresql" -> {
                        sourceConfig.setDriverClassName("org.postgresql.Driver");
                        sourceConfig.setJdbcUrl(String.format("jdbc:postgresql://%s:%d/%s",
                                plugin.getConfig().getString("import.sources.litebans.host"),
                                plugin.getConfig().getInt("import.sources.litebans.port"),
                                plugin.getConfig().getString("import.sources.litebans.database")));
                        sourceConfig.setUsername(plugin.getConfig().getString("import.sources.litebans.username"));
                        sourceConfig.setPassword(plugin.getConfig().getString("import.sources.litebans.password"));
                    }
                    case "sqlite" -> {
                        File dbFile = new File(plugin.getConfig().getString("import.sources.litebans.sqlite-file"));
                        if (!dbFile.exists()) {
                            sender.sendMessage("§cLiteBans SQLite file not found at: " + dbFile.getPath());
                            sender.sendMessage("§cPlease check your config.yml path for 'import.sources.litebans.sqlite-file'");
                            return;
                        }
                        sourceConfig.setJdbcUrl("jdbc:sqlite:" + dbFile.getAbsolutePath());
                    }
                    default -> {
                        sender.sendMessage("§cUnsupported database type for LiteBans import: " + dbType);
                        return;
                    }
                }
            } catch (Exception e) {
                sender.sendMessage("§cFailed to configure LiteBans database connection. Please check your config and server logs.");
                plugin.getLogger().log(Level.SEVERE, "LiteBans import connection failed", e);
                return;
            }

            sourceConfig.setPoolName("ControlBans-LiteBans-Importer");
            sourceConfig.setMaximumPoolSize(3);

            try (HikariDataSource sourceDataSource = new HikariDataSource(sourceConfig);
                 Connection sourceConn = sourceDataSource.getConnection()) {

                sender.sendMessage("§eConnection successful. Starting import from LiteBans...");
                int totalImported = 0;
                totalImported += importTable(sourceConn, "bans", sender);
                totalImported += importTable(sourceConn, "mutes", sender);
                totalImported += importTable(sourceConn, "warnings", sender);
                totalImported += importTable(sourceConn, "kicks", sender);
                sender.sendMessage("§aLiteBans import complete. Imported a total of " + totalImported + " records.");

            } catch (Exception e) {
                sender.sendMessage("§cAn error occurred during the LiteBans import: " + e.getMessage());
                plugin.getLogger().log(Level.SEVERE, "LiteBans import failed", e);
            }
        });
    }

    public void importFromAdvancedBan(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            sender.sendMessage("§eConnecting to AdvancedBan source database...");
            HikariConfig sourceConfig = new HikariConfig();
            String dbType = plugin.getConfig().getString("import.sources.advancedban.type", "mysql");

            try {
                if ("mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType)) {
                    sourceConfig.setDriverClassName("com.mysql.cj.jdbc.Driver");
                    sourceConfig.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s",
                            plugin.getConfig().getString("import.sources.advancedban.host"),
                            plugin.getConfig().getInt("import.sources.advancedban.port"),
                            plugin.getConfig().getString("import.sources.advancedban.database")));
                    sourceConfig.setUsername(plugin.getConfig().getString("import.sources.advancedban.username"));
                    sourceConfig.setPassword(plugin.getConfig().getString("import.sources.advancedban.password"));
                } else {
                    sender.sendMessage("§cUnsupported database type for AdvancedBan import: " + dbType + ". Only MySQL/MariaDB is supported.");
                    return;
                }
            } catch (Exception e) {
                sender.sendMessage("§cFailed to configure AdvancedBan database connection. Please check your config and server logs.");
                plugin.getLogger().log(Level.SEVERE, "AdvancedBan import connection failed", e);
                return;
            }

            sourceConfig.setPoolName("ControlBans-AdvancedBan-Importer");
            sourceConfig.setMaximumPoolSize(3);

            try (HikariDataSource sourceDataSource = new HikariDataSource(sourceConfig);
                 Connection sourceConn = sourceDataSource.getConnection()) {

                sender.sendMessage("§eConnection successful. Starting import from AdvancedBan...");
                int totalImported = 0;
                totalImported += importAdvancedBanTable(sourceConn, "Punishments", sender);
                totalImported += importAdvancedBanTable(sourceConn, "PunishmentHistory", sender);
                sender.sendMessage("§aAdvancedBan import complete. Imported a total of " + totalImported + " records.");

            } catch (Exception e) {
                sender.sendMessage("§cAn error occurred during the AdvancedBan import: " + e.getMessage());
                plugin.getLogger().log(Level.SEVERE, "AdvancedBan import failed", e);
            }
        });
    }

    private int importAdvancedBanTable(Connection sourceConn, String tableName, CommandSender sender) {
        int count = 0;
        String sql = "SELECT * FROM " + tableName;
        try (Statement stmt = sourceConn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {

            while (rs.next()) {
                try {
                    String uuidStr = rs.getString("uuid");
                    if (uuidStr == null || "CONSOLE".equalsIgnoreCase(uuidStr)) continue;

                    PunishmentType type = mapAdvancedBanType(rs.getString("punishmentType"));
                    if (type == null) continue;

                    long endTime = rs.getLong("end");
                    boolean active = endTime == -1 || endTime > System.currentTimeMillis();

                    Punishment punishment = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId())
                            .targetUuid(UUID.fromString(uuidStr))
                            .targetName(rs.getString("name"))
                            .reason(rs.getString("reason"))
                            .staffName(rs.getString("operator"))
                            .createdTime(rs.getLong("start"))
                            .expiryTime(endTime)
                            .active(active)
                            .ipBan(rs.getString("punishmentType").contains("IP_BAN"))
                            .silent(false) // AdvancedBan doesn't have a silent concept
                            .serverOrigin("imported")
                            .type(type)
                            .build();

                    saveImportedPunishment(punishment);
                    count++;
                } catch (Exception e) {
                    // Ignore single record import failure
                }
            }
        } catch (SQLException e) {
            sender.sendMessage("§cFailed to import from table: " + tableName + ". It may not exist. Error: " + e.getMessage());
        }
        if (count > 0) {
            sender.sendMessage("§aImported " + count + " record(s) from " + tableName);
        }
        return count;
    }

    private PunishmentType mapAdvancedBanType(String abType) {
        return switch (abType) {
            case "BAN", "IP_BAN" -> PunishmentType.BAN;
            case "TEMP_BAN", "TEMP_IP_BAN" -> PunishmentType.TEMPBAN;
            case "MUTE" -> PunishmentType.MUTE;
            case "TEMP_MUTE" -> PunishmentType.TEMPMUTE;
            case "KICK" -> PunishmentType.KICK;
            case "WARN" -> PunishmentType.WARN;
            default -> null;
        };
    }

    private void saveImportedPunishment(Punishment punishment) {
        databaseManager.executeAsync(conn -> {
            switch (punishment.getType()) {
                case BAN, TEMPBAN, IPBAN -> punishmentDao.insertBan(conn, punishment);
                case MUTE, TEMPMUTE -> punishmentDao.insertMute(conn, punishment);
                case WARN -> punishmentDao.insertWarning(conn, punishment);
                case KICK -> punishmentDao.insertKick(conn, punishment);
            }
        }).join(); // We join to ensure imports happen sequentially
    }


    private int importTable(Connection sourceConn, String type, CommandSender sender) {
        String tableName = "litebans_" + type;
        int count = 0;
        try (Statement stmt = sourceConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {

            while (rs.next()) {
                try {
                    String uuid = rs.getString("uuid");
                    if (uuid == null) continue; // Skip invalid entries

                    Punishment.Builder builder = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId()) // Generate new unique ID
                            .targetUuid(UUID.fromString(uuid))
                            .targetIp(rs.getString("ip"))
                            .reason(rs.getString("reason"))
                            .staffUuid(rs.getString("banned_by_uuid") != null ? UUID.fromString(rs.getString("banned_by_uuid")) : null)
                            .staffName(rs.getString("banned_by_name"))
                            .createdTime(rs.getLong("time"))
                            .expiryTime(rs.getLong("until"))
                            .serverOrigin(rs.getString("server_origin"))
                            .silent(rs.getBoolean("silent"))
                            .ipBan(rs.getBoolean("ipban"))
                            .active(rs.getBoolean("active"));

                    Punishment p = switch (type) {
                        case "bans" -> builder.type(rs.getLong("until") == -1 ? PunishmentType.BAN : PunishmentType.TEMPBAN).build();
                        case "mutes" -> builder.type(rs.getLong("until") == -1 ? PunishmentType.MUTE : PunishmentType.TEMPMUTE).build();
                        case "warnings" -> builder.type(PunishmentType.WARN).build();
                        case "kicks" -> builder.type(PunishmentType.KICK).build();
                        default -> null;
                    };

                    if (p != null) {
                        saveImportedPunishment(p);
                        count++;
                    }
                } catch (Exception e) {
                    // Ignore single record import failure
                }
            }
        } catch (Exception e) {
            sender.sendMessage("§cFailed to import from table: " + tableName + ". It may not exist.");
        }
        if (count > 0) {
            sender.sendMessage("§aImported " + count + " record(s) from " + tableName);
        }
        return count;
    }
}