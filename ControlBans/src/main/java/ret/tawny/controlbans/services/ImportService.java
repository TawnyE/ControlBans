package ret.tawny.controlbans.services;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.IdUtil;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.stream.Stream;

public class ImportService {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final LocaleManager locale;

    private static final int MAX_CONCURRENT_IMPORTS = 50;

    public ImportService(ControlBansPlugin plugin, StorageInterface storage, LocaleManager locale) {
        this.plugin = plugin;
        this.storage = storage;
        this.locale = locale;
    }

    public void importFromEssentials(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            if (Bukkit.getPluginManager().getPlugin("Essentials") == null) {
                tell(sender, locale.getMessage("import.essentials-not-installed"));
                return;
            }
            File essentialsDataFolder = Bukkit.getPluginManager().getPlugin("Essentials").getDataFolder();
            File userdataFolder = new File(essentialsDataFolder, "userdata");

            if (!userdataFolder.exists() || !userdataFolder.isDirectory()) {
                tell(sender, locale.getMessage("import.essentials-userdata-not-found"));
                return;
            }

            tell(sender, locale.getMessage("import.essentials-starting"));

            AtomicInteger count = new AtomicInteger(0);
            Semaphore semaphore = new Semaphore(MAX_CONCURRENT_IMPORTS);
            List<CompletableFuture<Boolean>> importTasks = new ArrayList<>();

            try (Stream<Path> paths = Files.walk(userdataFolder.toPath())) {
                paths.filter(Files::isRegularFile)
                        .filter(p -> p.toString().endsWith(".yml"))
                        .forEach(path -> {
                            try {
                                semaphore.acquire();
                                CompletableFuture<Boolean> task = processEssentialsFile(path.toFile()).whenComplete((v, t) -> {
                                    semaphore.release();
                                    if (t == null && v != null && v) {
                                        count.incrementAndGet();
                                    }
                                });
                                importTasks.add(task);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                        });

                CompletableFuture.allOf(importTasks.toArray(new CompletableFuture[0])).join();

                tell(sender, locale.getMessage("import.essentials-success", Placeholder.unparsed("count", String.valueOf(count.get()))));

            } catch (Exception e) {
                tell(sender, locale.getMessage("import.import-failed", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
                plugin.getLogger().log(Level.SEVERE, "Error reading Essentials directory", e);
            }
        });
    }

    private CompletableFuture<Boolean> processEssentialsFile(File userFile) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                YamlConfiguration userData = YamlConfiguration.loadConfiguration(userFile);
                if (userData.getBoolean("muted", false)) {
                    long timeout = userData.getLong("mute-timeout", 0);
                    long created = userData.getLong("timestamps.mute", System.currentTimeMillis());
                    String reason = userData.getString("mute-reason", "Imported from Essentials");
                    String filename = userFile.getName().replace(".yml", "");

                    UUID uuid;
                    try {
                        uuid = UUID.fromString(filename);
                    } catch (IllegalArgumentException e) {
                        return false;
                    }

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

                    storage.importPunishment(builder.build()).join();
                    return true;
                }
            } catch (Exception e) {
            }
            return false;
        });
    }

    public void importFromLiteBans(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            tell(sender, locale.getMessage("import.litebans-connecting"));
            HikariConfig sourceConfig = new HikariConfig();
            String dbType = plugin.getConfig().getString("import.sources.litebans.type", "mysql");

            try {
                configureDataSource(sourceConfig, dbType, "import.sources.litebans");
            } catch (Exception e) {
                tell(sender, locale.getMessage("import.litebans-connection-failed"));
                return;
            }

            sourceConfig.setPoolName("ControlBans-LiteBans-Importer");
            sourceConfig.setMaximumPoolSize(1);

            try (HikariDataSource sourceDataSource = new HikariDataSource(sourceConfig);
                 Connection sourceConn = sourceDataSource.getConnection()) {

                tell(sender, locale.getMessage("import.litebans-connection-success"));

                int total = 0;
                total += importTable(sourceConn, "bans", sender);
                total += importTable(sourceConn, "mutes", sender);
                total += importTable(sourceConn, "warnings", sender);
                total += importTable(sourceConn, "kicks", sender);

                tell(sender, locale.getMessage("import.litebans-import-complete", Placeholder.unparsed("count", String.valueOf(total))));

            } catch (Exception e) {
                tell(sender, locale.getMessage("import.litebans-import-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            }
        });
    }

    public void importFromAdvancedBan(CommandSender sender) {
        CompletableFuture.runAsync(() -> {
            tell(sender, locale.getMessage("import.advancedban-connecting"));
            HikariConfig sourceConfig = new HikariConfig();

            try {
                configureDataSource(sourceConfig, "mysql", "import.sources.advancedban");
            } catch (Exception e) {
                tell(sender, locale.getMessage("import.advancedban-connection-failed"));
                return;
            }

            sourceConfig.setPoolName("ControlBans-AdvancedBan-Importer");
            sourceConfig.setMaximumPoolSize(1);

            try (HikariDataSource sourceDataSource = new HikariDataSource(sourceConfig);
                 Connection sourceConn = sourceDataSource.getConnection()) {

                tell(sender, locale.getMessage("import.advancedban-connection-success"));

                int total = importAdvancedBanTable(sourceConn, sender);
                tell(sender, locale.getMessage("import.advancedban-import-complete", Placeholder.unparsed("count", String.valueOf(total))));

            } catch (Exception e) {
                tell(sender, locale.getMessage("import.advancedban-import-error", Placeholder.unparsed("error", String.valueOf(e.getMessage()))));
            }
        });
    }

    private int importAdvancedBanTable(Connection sourceConn, CommandSender sender) {
        int count = 0;
        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_IMPORTS);
        List<CompletableFuture<Void>> importTasks = new ArrayList<>();

        try (Statement stmt = sourceConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM Punishments")) {

            while (rs.next()) {
                try {
                    String uuidStr = rs.getString("uuid");
                    String name = rs.getString("name");
                    String reason = rs.getString("reason");
                    String operator = rs.getString("operator");
                    String typeStr = rs.getString("punishmentType");
                    long start = rs.getLong("start");
                    long end = rs.getLong("end");

                    if (uuidStr == null) continue;

                    PunishmentType type = switch (typeStr.toUpperCase()) {
                        case "BAN" -> PunishmentType.BAN;
                        case "TEMP_BAN" -> PunishmentType.TEMPBAN;
                        case "IP_BAN" -> PunishmentType.IPBAN;
                        case "MUTE" -> PunishmentType.MUTE;
                        case "TEMP_MUTE" -> PunishmentType.TEMPMUTE;
                        case "WARNING" -> PunishmentType.WARN;
                        case "KICK" -> PunishmentType.KICK;
                        default -> null;
                    };

                    if (type == null) continue;

                    Punishment punishment = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId())
                            .targetUuid(UUID.fromString(uuidStr))
                            .targetName(name)
                            .reason(reason)
                            .staffName(operator)
                            .createdTime(start)
                            .expiryTime(end == -1 ? -1 : end)
                            .type(type)
                            .active(true)
                            .build();

                    semaphore.acquire();
                    CompletableFuture<Void> task = storage.importPunishment(punishment).whenComplete((v, t) -> semaphore.release());
                    importTasks.add(task);
                    count++;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            CompletableFuture.allOf(importTasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            tell(sender, locale.getMessage("import.import-table-failed", Placeholder.unparsed("table", "Punishments")));
            plugin.getLogger().log(Level.SEVERE, "Failed to import from AdvancedBan", e);
        }
        return count;
    }

    private void configureDataSource(HikariConfig config, String dbType, String configPath) {
        switch (dbType.toLowerCase()) {
            case "mysql", "mariadb" -> {
                config.setDriverClassName(resolveDriver("ret.tawny.controlbans.libs.mysql.cj.jdbc.Driver", "com.mysql.cj.jdbc.Driver"));
                config.setJdbcUrl(String.format("jdbc:mysql://%s:%d/%s?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC",
                        plugin.getConfig().getString(configPath + ".host"),
                        plugin.getConfig().getInt(configPath + ".port"),
                        plugin.getConfig().getString(configPath + ".database")));
                config.setUsername(plugin.getConfig().getString(configPath + ".username"));
                config.setPassword(plugin.getConfig().getString(configPath + ".password"));
            }
            case "sqlite" -> {
                config.setDriverClassName("org.sqlite.JDBC");
                String sqliteFile = plugin.getConfig().getString(configPath + ".file",
                        plugin.getConfig().getString(configPath + ".sqlite-file",
                                plugin.getConfig().getString(configPath + ".database", "litebans.db")));
                config.setJdbcUrl("jdbc:sqlite:" + sqliteFile);
            }
            default -> throw new IllegalArgumentException("Unsupported type: " + dbType);
        }
    }

    private static String resolveDriver(String shaded, String unshaded) {
        try {
            Class.forName(shaded);
            return shaded;
        } catch (ClassNotFoundException ignored) {}
        return unshaded;
    }

    private void tell(CommandSender sender, net.kyori.adventure.text.Component message) {
        try {
            plugin.getSchedulerAdapter().runTask(() -> {
                try {
                    sender.sendMessage(message);
                } catch (Exception ignored) {}
            });
        } catch (Exception e) {
            try {
                sender.sendMessage(message);
            } catch (Exception ignored) {}
        }
    }

    private int importTable(Connection sourceConn, String type, CommandSender sender) {
        String tableName = "litebans_" + type;
        int count = 0;
        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_IMPORTS);
        List<CompletableFuture<Void>> importTasks = new ArrayList<>();

        try (Statement stmt = sourceConn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT * FROM " + tableName)) {

            while (rs.next()) {
                try {
                    String uuid = rs.getString("uuid");
                    if (uuid == null) continue;

                    long untilRaw = rs.getLong("until");
                    if (rs.wasNull()) untilRaw = -1;
                    long until = untilRaw <= 0 ? -1 : untilRaw;
                    Punishment.Builder builder = Punishment.builder()
                            .punishmentId(IdUtil.generatePunishmentId())
                            .targetUuid(UUID.fromString(uuid))
                            .targetIp(rs.getString("ip"))
                            .reason(rs.getString("reason"))
                            .staffName(rs.getString("banned_by_name"))
                            .createdTime(rs.getLong("time"))
                            .expiryTime(until)
                            .active(rs.getBoolean("active"));

                    Punishment p = switch (type) {
                        case "bans" -> builder.type(until <= 0 ? PunishmentType.BAN : PunishmentType.TEMPBAN).build();
                        case "mutes" -> builder.type(until <= 0 ? PunishmentType.MUTE : PunishmentType.TEMPMUTE).build();
                        case "warnings" -> builder.type(PunishmentType.WARN).build();
                        case "kicks" -> builder.type(PunishmentType.KICK).build();
                        default -> null;
                    };

                    if (p != null) {
                        semaphore.acquire();
                        CompletableFuture<Void> task = storage.importPunishment(p).whenComplete((v, t) -> semaphore.release());
                        importTasks.add(task);
                        count++;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
            CompletableFuture.allOf(importTasks.toArray(new CompletableFuture[0])).join();
        } catch (Exception e) {
            tell(sender, locale.getMessage("import.import-table-failed", Placeholder.unparsed("table", tableName)));
            plugin.getLogger().log(Level.SEVERE, "Failed to import from table: " + tableName, e);
        }
        return count;
    }
}