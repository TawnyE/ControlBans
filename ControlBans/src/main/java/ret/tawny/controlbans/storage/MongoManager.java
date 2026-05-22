package ret.tawny.controlbans.storage;

import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.IndexOptions;
import com.mongodb.client.model.ReplaceOptions;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import com.mongodb.event.ClusterClosedEvent;
import com.mongodb.event.ClusterDescriptionChangedEvent;
import com.mongodb.event.ClusterListener;
import com.mongodb.event.ClusterOpeningEvent;
import org.bson.Document;
import org.bson.conversions.Bson;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.regex.Pattern;

public class MongoManager implements StorageInterface {

    private final ControlBansPlugin plugin;
    private final ConfigManager config;
    private MongoClient mongoClient;
    private MongoDatabase database;
    private boolean connected = false;
    private final ExecutorService asyncExecutor;
    private static final AtomicInteger EXECUTOR_THREAD_ID = new AtomicInteger();

    private static final String COL_BANS = "controlbans_bans";
    private static final String COL_MUTES = "controlbans_mutes";
    private static final String COL_WARNINGS = "controlbans_warnings";
    private static final String COL_KICKS = "controlbans_kicks";
    private static final String COL_VOICEMUTES = "controlbans_voicemutes";
    private static final String COL_HISTORY = "controlbans_history";
    private static final String COL_APPEALS = "controlbans_appeals";
    private static final String COL_REPORTS = "controlbans_reports";
    private static final String COL_NOTES = "controlbans_notes";
    private static final List<String> PUNISHMENT_COLLECTIONS = List.of(
            COL_BANS,
            COL_MUTES,
            COL_WARNINGS,
            COL_KICKS,
            COL_VOICEMUTES
    );
    private static final List<String> ALL_COLLECTIONS = List.of(
            COL_BANS,
            COL_MUTES,
            COL_WARNINGS,
            COL_KICKS,
            COL_VOICEMUTES,
            COL_HISTORY,
            COL_APPEALS,
            COL_REPORTS,
            COL_NOTES
    );

    public MongoManager(ControlBansPlugin plugin, ConfigManager config) {
        this.plugin = plugin;
        this.config = config;
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "ControlBans-Mongo-" + EXECUTOR_THREAD_ID.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        this.asyncExecutor = Executors.newCachedThreadPool(threadFactory);
    }

    private final ClusterListener clusterStateListener = new ClusterListener() {
        @Override
        public void clusterOpening(ClusterOpeningEvent event) {
        }

        @Override
        public void clusterDescriptionChanged(ClusterDescriptionChangedEvent event) {
            boolean hasWritableServer = event.getNewDescription().hasWritableServer();
            if (!hasWritableServer && connected) {
                plugin.getLogger().warning("MongoDB connection lost. Operations will be paused until reconnection.");
                connected = false;
            } else if (hasWritableServer && !connected) {
                plugin.getLogger().info("MongoDB connection restored.");
                connected = true;
            }
        }

        @Override
        public void clusterClosed(ClusterClosedEvent event) {
            connected = false;
        }
    };

    @Override
    public void initialize() {
        try {
            mongoClient = MongoClients.create(config.getMongoConnectionString());
            database = mongoClient.getDatabase(config.getMongoDatabase());
            database.listCollectionNames().first();
            connected = true;
            plugin.getLogger().info("MongoDB connected successfully.");
            createIndexes();
            startJanitor();
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to connect to MongoDB", e);
            throw new RuntimeException("MongoDB initialization failed", e);
        }
    }

    private void startJanitor() {
        plugin.getServer().getScheduler().runTaskTimerAsynchronously(plugin, () -> {
            if (!connected) return;

            int banDays = plugin.getConfig().getInt("database.janitor.ban-retention-days", 30);
            int kickDays = plugin.getConfig().getInt("database.janitor.kick-retention-days", 90);
            int warnDays = plugin.getConfig().getInt("database.janitor.warning-retention-days", 90);
            int reportDays = plugin.getConfig().getInt("database.janitor.report-retention-days", 60);

            long banRetention = System.currentTimeMillis() - (banDays * 24L * 60 * 60 * 1000);
            long kickRetention = System.currentTimeMillis() - (kickDays * 24L * 60 * 60 * 1000);
            long warningRetention = System.currentTimeMillis() - (warnDays * 24L * 60 * 60 * 1000);
            long reportRetention = System.currentTimeMillis() - (reportDays * 24L * 60 * 60 * 1000);

            try {
                int deletedBans = 0;
                for (String collection : new String[]{COL_BANS, COL_MUTES, COL_VOICEMUTES}) {
                    Bson banFilter = Filters.and(Filters.gt("expiry_time", 0L), Filters.lt("expiry_time", banRetention));
                    deletedBans += getCollection(collection).deleteMany(banFilter).getDeletedCount();
                }
                if (deletedBans > 0) plugin.getLogger().info("[Mongo Janitor] Purged " + deletedBans + " ancient temporary punishments.");

                Bson kickFilter = Filters.lt("created_time", kickRetention);
                long deletedKicks = getCollection(COL_KICKS).deleteMany(kickFilter).getDeletedCount();
                if (deletedKicks > 0) plugin.getLogger().info("[Mongo Janitor] Purged " + deletedKicks + " old kicks.");

                Bson warnFilter = Filters.or(
                        Filters.and(Filters.gt("expiry_time", 0L), Filters.lt("expiry_time", warningRetention)),
                        Filters.and(Filters.eq("active", false), Filters.lt("created_time", warningRetention))
                );
                long deletedWarns = getCollection(COL_WARNINGS).deleteMany(warnFilter).getDeletedCount();
                if (deletedWarns > 0) plugin.getLogger().info("[Mongo Janitor] Purged " + deletedWarns + " old warnings.");

                Bson reportFilter = Filters.and(
                        Filters.or(Filters.eq("status", "RESOLVED"), Filters.eq("status", "DISMISSED")),
                        Filters.lt("time", reportRetention)
                );
                long deletedReports = getCollection(COL_REPORTS).deleteMany(reportFilter).getDeletedCount();
                if (deletedReports > 0) plugin.getLogger().info("[Mongo Janitor] Purged " + deletedReports + " old resolved/dismissed reports.");
            } catch (Exception e) {
                plugin.getLogger().warning("[Mongo Janitor] Cleanup failed: " + e.getMessage());
            }
        }, 20L * 60 * 5, 20L * 60 * 60 * 24);
    }

    private void createIndexes() {
        for (String colName : PUNISHMENT_COLLECTIONS) {
            MongoCollection<Document> col = getCollection(colName);
            col.createIndex(new Document("punishment_id", 1), new IndexOptions().unique(true));
            col.createIndex(new Document("target_uuid", 1));
            col.createIndex(new Document("active", 1));
            col.createIndex(new Document("created_time", -1));
            col.createIndex(new Document("target_ip", 1));
        }
        getCollection(COL_HISTORY).createIndex(new Document("date", -1));
        getCollection(COL_HISTORY).createIndex(new Document("name", 1));
        getCollection(COL_HISTORY).createIndex(new Document("uuid", 1));
        getCollection(COL_HISTORY).createIndex(new Document("ip", 1));
        getCollection(COL_HISTORY).createIndex(new Document("uuid", 1).append("ip", 1),
                new IndexOptions().unique(true));
        getCollection(COL_APPEALS).createIndex(new Document("target_uuid", 1));
        getCollection(COL_APPEALS).createIndex(new Document("created_at", -1));
        getCollection(COL_REPORTS).createIndex(new Document("reporter_uuid", 1));
        getCollection(COL_REPORTS).createIndex(new Document("target_name", 1));
        getCollection(COL_NOTES).createIndex(new Document("uuid", 1));
    }

    private MongoCollection<Document> getCollection(String name) {
        return database.getCollection(name);
    }

    private <T> CompletableFuture<T> supplyAsync(java.util.function.Supplier<T> supplier) {
        return CompletableFuture.supplyAsync(supplier, asyncExecutor);
    }

    private CompletableFuture<Void> runAsync(Runnable runnable) {
        return CompletableFuture.runAsync(runnable, asyncExecutor);
    }

    @Override
    public CompletableFuture<Void> insertBan(Punishment p) { return runAsync(() -> insertPunishmentSync(COL_BANS, p)); }
    @Override
    public CompletableFuture<Void> insertMute(Punishment p) { return runAsync(() -> insertPunishmentSync(COL_MUTES, p)); }
    @Override
    public CompletableFuture<Void> insertWarning(Punishment p) { return runAsync(() -> insertPunishmentSync(COL_WARNINGS, p)); }
    @Override
    public CompletableFuture<Void> insertKick(Punishment p) { return runAsync(() -> insertPunishmentSync(COL_KICKS, p)); }
    @Override
    public CompletableFuture<Void> insertVoiceMute(Punishment p) { return runAsync(() -> insertPunishmentSync(COL_VOICEMUTES, p)); }

    private void insertPunishmentSync(String collection, Punishment p) {
        if (!connected) return;
        recordHistorySync(p.getTargetUuid(), p.getTargetName(), p.getTargetIp());
        Document doc = punishmentToDocument(p);
        getCollection(collection).replaceOne(Filters.eq("punishment_id", p.getPunishmentId()), doc, new ReplaceOptions().upsert(true));
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveBan(UUID uuid) { return supplyAsync(() -> getActivePunishmentSync(COL_BANS, uuid, PunishmentType.BAN)); }
    @Override
    public CompletableFuture<Optional<Punishment>> getActiveMute(UUID uuid) { return supplyAsync(() -> getActivePunishmentSync(COL_MUTES, uuid, PunishmentType.MUTE)); }
    @Override
    public CompletableFuture<Optional<Punishment>> getActiveVoiceMute(UUID uuid) { return supplyAsync(() -> getActivePunishmentSync(COL_VOICEMUTES, uuid, PunishmentType.VOICEMUTE)); }

    private Optional<Punishment> getActivePunishmentSync(String collection, UUID uuid, PunishmentType type) {
        if (!connected) return Optional.empty();
        Bson filter = Filters.and(
                Filters.eq("target_uuid", uuid.toString()),
                Filters.eq("active", true),
                Filters.or(
                        Filters.eq("expiry_time", -1L),
                        Filters.eq("expiry_time", 0L),
                        Filters.gt("expiry_time", System.currentTimeMillis())
                )
        );
        Document doc = getCollection(collection).find(filter).sort(Sorts.descending("created_time")).first();
        return doc != null ? Optional.of(documentToPunishment(doc, type)) : Optional.empty();
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getActiveIpBan(String ip) { return supplyAsync(() -> getActiveIpPunishmentSync(COL_BANS, ip, PunishmentType.IPBAN)); }
    @Override
    public CompletableFuture<Optional<Punishment>> getActiveIpMute(String ip) { return supplyAsync(() -> getActiveIpPunishmentSync(COL_MUTES, ip, PunishmentType.MUTE)); }

    private Optional<Punishment> getActiveIpPunishmentSync(String collection, String ip, PunishmentType type) {
        if (!connected) return Optional.empty();
        Bson filter = Filters.and(
                Filters.eq("target_ip", ip),
                Filters.eq("ip_ban", true),
                Filters.eq("active", true),
                Filters.or(
                        Filters.eq("expiry_time", -1L),
                        Filters.eq("expiry_time", 0L),
                        Filters.gt("expiry_time", System.currentTimeMillis())
                )
        );
        Document doc = getCollection(collection).find(filter).sort(Sorts.descending("created_time")).first();
        if (doc == null) return Optional.empty();
        Punishment punishment = documentToPunishment(doc, type);
        if (punishment.getExpiryTime() != -1 && punishment.getExpiryTime() != 0) {
            return Optional.of(punishment.toBuilder().type(PunishmentType.TEMPMUTE).build());
        }
        return Optional.of(punishment);
    }

    @Override
    public CompletableFuture<Optional<Punishment>> getPunishmentById(String id) {
        return supplyAsync(() -> {
            for (String col : PUNISHMENT_COLLECTIONS) {
                Document doc = getCollection(col).find(Filters.eq("punishment_id", id)).first();
                if (doc != null) {
                    return Optional.of(documentToPunishment(doc, defaultTypeForCollection(col)));
                }
            }
            return Optional.empty();
        });
    }

    @Override
    public CompletableFuture<List<String>> getNamesStartingWith(String prefix) {
        return supplyAsync(() -> {
            List<String> names = new ArrayList<>();
            if (!connected) return names;
            Bson filter = Filters.regex("name", "^" + Pattern.quote(prefix), "i");
            Set<String> deduped = new LinkedHashSet<>();
            for (Document doc : getCollection(COL_HISTORY).find(filter).sort(Sorts.descending("date")).limit(25)) {
                String name = doc.getString("name");
                if (name == null || name.isBlank()) {
                    continue;
                }
                deduped.add(name);
                if (deduped.size() >= 10) {
                    break;
                }
            }
            names.addAll(deduped);
            return names;
        });
    }

    @Override
    public CompletableFuture<List<Punishment>> getPunishmentHistory(UUID uuid, int limit) {
        return supplyAsync(() -> {
            List<Punishment> history = new ArrayList<>();
            if (!connected) return history;

            Bson filter = Filters.eq("target_uuid", uuid.toString());
            for (String collection : PUNISHMENT_COLLECTIONS) {
                appendPunishments(history, collection, filter, limit);
            }

            history.sort(Comparator.comparingLong(Punishment::getCreatedTime).reversed());
            if (limit > 0 && history.size() > limit) {
                return new ArrayList<>(history.subList(0, limit));
            }
            return history;
        });
    }

    @Override
    public CompletableFuture<List<Punishment>> getRecentPunishments(int limit) {
        return supplyAsync(() -> {
            List<Punishment> punishments = new ArrayList<>();
            if (!connected) return punishments;

            for (String collection : PUNISHMENT_COLLECTIONS) {
                appendPunishments(punishments, collection, new Document(), limit);
            }

            punishments.sort(Comparator.comparingLong(Punishment::getCreatedTime).reversed());
            if (limit > 0 && punishments.size() > limit) {
                return new ArrayList<>(punishments.subList(0, limit));
            }
            return punishments;
        });
    }
    @Override
    public CompletableFuture<List<Punishment>> getAllPunishments() {
        return supplyAsync(() -> {
            List<Punishment> punishments = new ArrayList<>();
            if (!connected) return punishments;

            for (String collection : PUNISHMENT_COLLECTIONS) {
                appendPunishments(punishments, collection, new Document(), 0);
            }

            punishments.sort(Comparator.comparingLong(Punishment::getCreatedTime).reversed());
            return punishments;
        });
    }

    @Override
    public CompletableFuture<Void> removeBan(UUID u, UUID rb, String rbn) { return runAsync(() -> removeSync(COL_BANS, u, rb, rbn)); }
    @Override
    public CompletableFuture<Void> removeMute(UUID u, UUID rb, String rbn) { return runAsync(() -> removeSync(COL_MUTES, u, rb, rbn)); }
    @Override
    public CompletableFuture<Void> removeVoiceMute(UUID u, UUID rb, String rbn) { return runAsync(() -> removeSync(COL_VOICEMUTES, u, rb, rbn)); }
    @Override
    public CompletableFuture<Void> removeIpBan(String ip, UUID rb, String rbn) {
        return runAsync(() -> {
            Bson filter = Filters.and(
                    Filters.eq("target_ip", ip),
                    Filters.eq("ip_ban", true),
                    Filters.eq("active", true)
            );
            Bson update = removalUpdate(rb, rbn);
            getCollection(COL_BANS).updateMany(filter, update);
        });
    }

    @Override
    public CompletableFuture<Void> removeIpMute(String ip, UUID rb, String rbn) {
        return runAsync(() -> {
            Bson filter = Filters.and(
                    Filters.eq("target_ip", ip),
                    Filters.eq("ip_ban", true),
                    Filters.eq("active", true)
            );
            Bson update = removalUpdate(rb, rbn);
            getCollection(COL_MUTES).updateMany(filter, update);
        });
    }

    private void removeSync(String col, UUID u, UUID rb, String rbn) {
        Bson filter = Filters.and(Filters.eq("target_uuid", u.toString()), Filters.eq("active", true));
        Bson update = removalUpdate(rb, rbn);
        getCollection(col).updateMany(filter, update);
    }

    @Override
    public CompletableFuture<Void> recordHistory(UUID uuid, String name, String ip) {
        return runAsync(() -> recordHistorySync(uuid, name, ip));
    }

    @Override
    public CompletableFuture<Void> clearAllData() {
        return runAsync(() -> {
            if (!connected) return;
            for (String collection : ALL_COLLECTIONS) {
                getCollection(collection).deleteMany(new Document());
            }
        });
    }
    @Override
    public CompletableFuture<Void> clearPlayerData(UUID u) {
        return runAsync(() -> {
            if (!connected) return;
            for (String collection : PUNISHMENT_COLLECTIONS) {
                getCollection(collection).deleteMany(Filters.eq("target_uuid", u.toString()));
            }
            getCollection(COL_HISTORY).deleteMany(Filters.eq("uuid", u.toString()));
            getCollection(COL_APPEALS).deleteMany(Filters.eq("target_uuid", u.toString()));
        });
    }

    @Override
    public CompletableFuture<String> getLastIpForUuid(UUID u) { return supplyAsync(() -> {
        Document d = getCollection(COL_HISTORY).find(Filters.eq("uuid", u.toString())).sort(Sorts.descending("date")).first();
        return d != null ? d.getString("ip") : null;
    });}

    @Override
    public CompletableFuture<String> getLastKnownName(UUID u) { return supplyAsync(() -> {
        Document d = getCollection(COL_HISTORY).find(Filters.eq("uuid", u.toString())).sort(Sorts.descending("date")).first();
        return d != null ? d.getString("name") : null;
    });}

    @Override
    public CompletableFuture<UUID> getUuidByName(String n) { return supplyAsync(() -> {
        Document d = getCollection(COL_HISTORY).find(Filters.eq("name", n)).sort(Sorts.descending("date")).first();
        return d != null ? UUID.fromString(d.getString("uuid")) : null;
    });}

    @Override
    public CompletableFuture<Set<String>> getIpsForUuid(UUID u) {
        return supplyAsync(() -> {
            Set<String> ips = new HashSet<>();
            if (!connected) return ips;
            for (Document doc : getCollection(COL_HISTORY).find(Filters.eq("uuid", u.toString()))) {
                String ip = doc.getString("ip");
                if (ip != null && !ip.isBlank()) {
                    ips.add(ip);
                }
            }
            return ips;
        });
    }
    @Override
    public CompletableFuture<Set<UUID>> getUuidsOnIp(String i) {
        return supplyAsync(() -> {
            Set<UUID> uuids = new HashSet<>();
            if (!connected) return uuids;
            for (Document doc : getCollection(COL_HISTORY).find(Filters.eq("ip", i))) {
                String uuid = doc.getString("uuid");
                if (uuid != null) {
                    uuids.add(UUID.fromString(uuid));
                }
            }
            return uuids;
        });
    }
    @Override
    public CompletableFuture<Integer> getUserCountOnIp(String i) {
        return supplyAsync(() -> {
            if (!connected) return 0;
            List<String> uuids = getCollection(COL_HISTORY).distinct("uuid", Filters.eq("ip", i), String.class).into(new ArrayList<>());
            return uuids.size();
        });
    }
    @Override
    public CompletableFuture<Void> addAppeal(String id, UUID u, String m, long t) {
        return runAsync(() -> {
            if (!connected) return;
            Document doc = new Document("target_uuid", u.toString())
                    .append("punishment_id", id)
                    .append("message", m)
                    .append("created_at", t);
            getCollection(COL_APPEALS).insertOne(doc);
        });
    }
    @Override
    public CompletableFuture<Long> getLastAppealTime(UUID u) {
        return supplyAsync(() -> {
            if (!connected) return 0L;
            Document doc = getCollection(COL_APPEALS)
                    .find(Filters.eq("target_uuid", u.toString()))
                    .sort(Sorts.descending("created_at"))
                    .first();
            return doc != null ? doc.getLong("created_at") : 0L;
        });
    }
    @Override
    public CompletableFuture<Integer> getAppealCount(UUID u, long s) {
        return supplyAsync(() -> {
            if (!connected) return 0;
            return Math.toIntExact(getCollection(COL_APPEALS)
                    .countDocuments(Filters.and(
                            Filters.eq("target_uuid", u.toString()),
                            Filters.gte("created_at", s)
                    )));
        });
    }
    @Override
    public CompletableFuture<Void> insertReport(String id, UUID reporterUuid, String reporterName, String targetName, String reason, long timestamp, String status) {
        return runAsync(() -> {
            if (!connected) return;
            Document doc = new Document("id", id)
                    .append("reporter_uuid", reporterUuid.toString())
                    .append("reporter_name", reporterName)
                    .append("target_name", targetName)
                    .append("reason", reason)
                    .append("time", timestamp)
                    .append("status", status);
            getCollection(COL_REPORTS).replaceOne(Filters.eq("id", id), doc, new ReplaceOptions().upsert(true));
        });
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReports() {
        return supplyAsync(() -> {
            List<ret.tawny.controlbans.services.ReportService.Report> reports = new ArrayList<>();
            if (!connected) return reports;
            for (Document doc : getCollection(COL_REPORTS).find().sort(Sorts.descending("time"))) {
                reports.add(documentToReport(doc));
            }
            return reports;
        });
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.ReportService.Report>> getReportsByReporter(UUID reporterUuid) {
        return supplyAsync(() -> {
            List<ret.tawny.controlbans.services.ReportService.Report> reports = new ArrayList<>();
            if (!connected) return reports;
            for (Document doc : getCollection(COL_REPORTS).find(Filters.eq("reporter_uuid", reporterUuid.toString())).sort(Sorts.descending("time"))) {
                reports.add(documentToReport(doc));
            }
            return reports;
        });
    }

    @Override
    public CompletableFuture<Boolean> updateReportStatus(String id, String status) {
        return supplyAsync(() -> {
            if (!connected) return false;
            var result = getCollection(COL_REPORTS).updateOne(Filters.eq("id", id), Updates.set("status", status));
            return result.getModifiedCount() > 0;
        });
    }

    @Override
    public CompletableFuture<Void> addNote(UUID targetUuid, String staffName, String noteText, long timestamp) {
        return runAsync(() -> {
            if (!connected) return;
            Document doc = new Document("uuid", targetUuid.toString())
                    .append("staff_name", staffName)
                    .append("note_text", noteText)
                    .append("time", timestamp);
            getCollection(COL_NOTES).insertOne(doc);
        });
    }

    @Override
    public CompletableFuture<Boolean> removeNote(UUID targetUuid, int index) {
        return supplyAsync(() -> {
            if (!connected) return false;
            var iterable = getCollection(COL_NOTES).find(Filters.eq("uuid", targetUuid.toString())).sort(Sorts.ascending("time")).skip(index - 1).limit(1);
            Document doc = iterable.first();
            if (doc != null) {
                var result = getCollection(COL_NOTES).deleteOne(Filters.eq("_id", doc.getObjectId("_id")));
                return result.getDeletedCount() > 0;
            }
            return false;
        });
    }

    @Override
    public CompletableFuture<List<ret.tawny.controlbans.services.NoteService.PlayerNote>> getNotes(UUID targetUuid) {
        return supplyAsync(() -> {
            List<ret.tawny.controlbans.services.NoteService.PlayerNote> notes = new ArrayList<>();
            if (!connected) return notes;
            for (Document doc : getCollection(COL_NOTES).find(Filters.eq("uuid", targetUuid.toString())).sort(Sorts.ascending("time"))) {
                notes.add(new ret.tawny.controlbans.services.NoteService.PlayerNote(
                        doc.getString("staff_name"),
                        doc.getString("note_text"),
                        doc.getLong("time")
                ));
            }
            return notes;
        });
    }

    private ret.tawny.controlbans.services.ReportService.Report documentToReport(Document doc) {
        return new ret.tawny.controlbans.services.ReportService.Report(
                doc.getString("id"),
                UUID.fromString(doc.getString("reporter_uuid")),
                doc.getString("reporter_name"),
                doc.getString("target_name"),
                doc.getString("reason"),
                doc.getLong("time"),
                doc.getString("status")
        );
    }

    @Override
    public CompletableFuture<Void> importPunishment(Punishment p) {
        return switch (p.getType()) {
            case BAN, TEMPBAN, IPBAN, TEMPIPBAN -> insertBan(p);
            case MUTE, TEMPMUTE, IPMUTE, TEMPIPMUTE -> insertMute(p);
            case WARN -> insertWarning(p);
            case KICK -> insertKick(p);
            case VOICEMUTE, TEMPVOICEMUTE -> insertVoiceMute(p);
        };
    }

    private Document punishmentToDocument(Punishment p) {
        return new Document("punishment_id", p.getPunishmentId())
                .append("type", p.getType().name())
                .append("target_uuid", p.getTargetUuid().toString())
                .append("target_name", p.getTargetName())
                .append("target_ip", p.getTargetIp())
                .append("reason", p.getReason())
                .append("staff_uuid", p.getStaffUuid() != null ? p.getStaffUuid().toString() : null)
                .append("staff_name", p.getStaffName())
                .append("created_time", p.getCreatedTime())
                .append("expiry_time", p.getExpiryTime())
                .append("server_origin", p.getServerOrigin())
                .append("silent", p.isSilent())
                .append("ip_ban", p.isIpBan())
                .append("active", p.isActive());
    }

    private Punishment documentToPunishment(Document doc, PunishmentType type) {
        String storedType = doc.getString("type");
        PunishmentType resolvedType = storedType != null ? PunishmentType.valueOf(storedType) : type;
        String staffUuid = doc.getString("staff_uuid");
        String targetUuidStr = doc.getString("target_uuid");
        UUID targetUuid = targetUuidStr != null ? UUID.fromString(targetUuidStr) : UUID.nameUUIDFromBytes("unknown".getBytes());

        return Punishment.builder()
                .punishmentId(doc.getString("punishment_id"))
                .type(resolvedType)
                .targetUuid(targetUuid)
                .targetName(doc.getString("target_name"))
                .targetIp(doc.getString("target_ip"))
                .reason(doc.getString("reason"))
                .staffUuid(staffUuid != null ? UUID.fromString(staffUuid) : null)
                .staffName(doc.getString("staff_name"))
                .createdTime(doc.getLong("created_time"))
                .expiryTime(doc.getLong("expiry_time"))
                .serverOrigin(doc.getString("server_origin"))
                .silent(Boolean.TRUE.equals(doc.getBoolean("silent")))
                .ipBan(Boolean.TRUE.equals(doc.getBoolean("ip_ban")))
                .active(Boolean.TRUE.equals(doc.getBoolean("active")))
                .build();
    }

    @Override
    public void shutdown() {
        if (mongoClient != null) {
            mongoClient.close();
        }
        asyncExecutor.shutdownNow();
        try {
            asyncExecutor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void appendPunishments(List<Punishment> target, String collection, Bson filter, int limit) {
        var iterable = getCollection(collection).find(filter).sort(Sorts.descending("created_time"));
        if (limit > 0) {
            iterable = iterable.limit(limit);
        }
        for (Document doc : iterable) {
            target.add(documentToPunishment(doc, defaultTypeForCollection(collection)));
        }
    }

    private PunishmentType defaultTypeForCollection(String collection) {
        return switch (collection) {
            case COL_BANS -> PunishmentType.BAN;
            case COL_MUTES -> PunishmentType.MUTE;
            case COL_WARNINGS -> PunishmentType.WARN;
            case COL_KICKS -> PunishmentType.KICK;
            case COL_VOICEMUTES -> PunishmentType.VOICEMUTE;
            default -> PunishmentType.BAN;
        };
    }

    private Bson removalUpdate(UUID removedBy, String removedByName) {
        List<Bson> updates = new ArrayList<>();
        updates.add(Updates.set("active", false));
        updates.add(Updates.set("removed_by_name", removedByName));
        updates.add(Updates.set("removed_by_date", System.currentTimeMillis()));
        if (removedBy != null) {
            updates.add(Updates.set("removed_by_uuid", removedBy.toString()));
        }
        return Updates.combine(updates);
    }

    private void recordHistorySync(UUID uuid, String name, String ip) {
        if (!connected || uuid == null || name == null || name.equalsIgnoreCase("unknown")) {
            return;
        }

        String targetIp = ip != null ? ip : "0.0.0.0";
        long now = System.currentTimeMillis();
        Document doc = new Document("uuid", uuid.toString())
                .append("name", name)
                .append("ip", targetIp)
                .append("date", now);

        try {
            getCollection(COL_HISTORY).replaceOne(
                    Filters.and(Filters.eq("uuid", uuid.toString()), Filters.eq("ip", targetIp)),
                    doc,
                    new ReplaceOptions().upsert(true)
            );
        } catch (Exception exception) {
            plugin.getLogger().log(Level.FINE, "Ignoring Mongo history write race for " + uuid, exception);
        }
    }
}
