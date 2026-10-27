package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

public class DataExportService {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final Gson gson;
    private final ExecutorService ioExecutor = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "ControlBans-Export");
        t.setDaemon(true);
        return t;
    });

    public DataExportService(ControlBansPlugin plugin, PunishmentService punishmentService) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.gson = new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create();
    }

    public CompletableFuture<File> exportAll() {
        return exportFiltered(null);
    }

    public CompletableFuture<File> exportFiltered(PunishmentType type) {
        return punishmentService.getAllPunishments().thenApplyAsync(punishments -> {
            try {
                File exportDir = new File(plugin.getDataFolder(), "exports");
                if (!exportDir.exists()) {
                    exportDir.mkdirs();
                }

                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss"));
                String typeStr = type != null ? "-" + type.name().toLowerCase() : "";
                File exportFile = new File(exportDir, "controlbans-export" + typeStr + "-" + timestamp + ".json");

                List<Punishment> filteredList;
                if (type != null) {
                    filteredList = punishments.stream()
                            .filter(p -> p.getType() == type)
                            .toList();
                } else {
                    filteredList = punishments;
                }

                try (FileOutputStream fos = new FileOutputStream(exportFile);
                     OutputStreamWriter osw = new OutputStreamWriter(fos, StandardCharsets.UTF_8);
                     JsonWriter writer = new JsonWriter(osw)) {

                    writer.setIndent("  ");
                    writer.beginObject();

                    writer.name("plugin").value("ControlBans");
                    writer.name("version").value("4.5");
                    writer.name("exportedAt").value(System.currentTimeMillis());
                    writer.name("totalRecords").value(filteredList.size());

                    writer.name("records");
                    writer.beginArray();

                    for (Punishment p : filteredList) {
                        ExportRecord record = toExportRecord(p);
                        gson.toJson(record, ExportRecord.class, writer);
                    }

                    writer.endArray();
                    writer.endObject();
                }

                plugin.getLogger()
                        .info("Exported " + filteredList.size() + " punishment records to " + exportFile.getName());
                return exportFile;
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to export punishment data", e);
                return null;
            }
        }, ioExecutor);
    }

    public CompletableFuture<ImportResult> importFromFile(File file) {
        return CompletableFuture.supplyAsync(() -> {
            try {
                if (!file.exists() || !file.isFile()) {
                    return new ImportResult(false, 0, 0, "File not found: " + file.getName());
                }

                List<CompletableFuture<Void>> imports = new ArrayList<>();
                List<String> errors = java.util.Collections.synchronizedList(new ArrayList<>());
                java.util.concurrent.atomic.AtomicInteger imported = new java.util.concurrent.atomic.AtomicInteger();
                java.util.concurrent.atomic.AtomicInteger failed = new java.util.concurrent.atomic.AtomicInteger();

                try (FileInputStream fis = new FileInputStream(file);
                     InputStreamReader isr = new InputStreamReader(fis, StandardCharsets.UTF_8);
                     JsonReader reader = new JsonReader(isr)) {

                    reader.beginObject();

                    while (reader.hasNext()) {
                        String name = reader.nextName();
                        if (name.equals("records")) {
                            reader.beginArray();
                            while (reader.hasNext()) {
                                try {
                                    ExportRecord record = gson.fromJson(reader, ExportRecord.class);
                                    Punishment punishment = fromExportRecord(record);
                                    imports.add(punishmentService.importPunishment(punishment).whenComplete((v, ex) -> {
                                        if (ex != null) {
                                            failed.incrementAndGet();
                                            if (errors.size() < 10) {
                                                errors.add("Failed to import record: " + ex.getMessage());
                                            }
                                        } else {
                                            imported.incrementAndGet();
                                        }
                                    }));
                                } catch (Exception e) {
                                    failed.incrementAndGet();
                                    if (errors.size() < 10) {
                                        errors.add("Failed to import record: " + e.getMessage());
                                    }
                                }
                            }
                            reader.endArray();
                        } else {
                            reader.skipValue();
                        }
                    }
                    reader.endObject();
                }

                // Wait once for all imports instead of joining per record
                CompletableFuture.allOf(imports.toArray(new CompletableFuture[0])).join();

                int importedCount = imported.get();
                int failedCount = failed.get();
                String message = "Imported " + importedCount + " records" + (failedCount > 0 ? ", " + failedCount + " failed" : "");
                if (!errors.isEmpty()) {
                    message += "\nFirst " + errors.size() + " errors:\n" + String.join("\n", errors);
                }

                plugin.getLogger().info("Import complete: " + importedCount + " success, " + failedCount + " failed");
                return new ImportResult(true, importedCount, failedCount, message);
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to import punishment data", e);
                return new ImportResult(false, 0, 0, "Import failed: " + e.getMessage());
            }
        }, ioExecutor);
    }

    public List<File> listExportFiles() {
        File exportDir = new File(plugin.getDataFolder(), "exports");
        if (!exportDir.exists()) {
            return List.of();
        }

        File[] files = exportDir.listFiles((dir, name) -> name.endsWith(".json"));
        return files != null ? List.of(files) : List.of();
    }

    public void shutdown() {
        ioExecutor.shutdown();
        try {
            if (!ioExecutor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                ioExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            ioExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private ExportRecord toExportRecord(Punishment p) {
        return new ExportRecord(
                p.getPunishmentId(),
                p.getType().name(),
                p.getTargetUuid() != null ? p.getTargetUuid().toString() : null,
                p.getTargetName(),
                p.getTargetIp(),
                p.getReason(),
                p.getStaffUuid() != null ? p.getStaffUuid().toString() : null,
                p.getStaffName(),
                p.getCreatedTime(),
                p.getExpiryTime(),
                p.getServerOrigin(),
                p.isSilent(),
                p.isIpBan(),
                p.isActive());
    }

    private Punishment fromExportRecord(ExportRecord r) {
        PunishmentType type;
        try {
            type = PunishmentType.valueOf(r.type);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown punishment type '" + r.type + "' for record " + r.punishmentId);
        }
        UUID targetUuid = r.targetUuid != null ? UUID.fromString(r.targetUuid) : UUID.nameUUIDFromBytes("unknown".getBytes(java.nio.charset.StandardCharsets.UTF_8));

        return Punishment.builder()
                .punishmentId(r.punishmentId)
                .type(type)
                .targetUuid(targetUuid)
                .targetName(r.targetName)
                .targetIp(r.targetIp)
                .reason(r.reason)
                .staffUuid(r.staffUuid != null ? UUID.fromString(r.staffUuid) : null)
                .staffName(r.staffName)
                .createdTime(r.createdTime)
                .expiryTime(r.expiryTime)
                .serverOrigin(r.serverOrigin)
                .silent(r.silent)
                .ipBan(r.ipBan)
                .active(r.active)
                .build();
    }

    public record ExportData(
            String plugin,
            String version,
            long exportedAt,
            int totalRecords,
            List<ExportRecord> records) {
    }

    public record ExportRecord(
            String punishmentId,
            String type,
            String targetUuid,
            String targetName,
            String targetIp,
            String reason,
            String staffUuid,
            String staffName,
            long createdTime,
            long expiryTime,
            String serverOrigin,
            boolean silent,
            boolean ipBan,
            boolean active) {
    }

    public record ImportResult(
            boolean success,
            int imported,
            int failed,
            String message) {
    }
}