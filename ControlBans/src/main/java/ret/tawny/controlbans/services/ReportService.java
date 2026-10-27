package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.storage.StorageInterface;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public class ReportService {

    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_RESOLVED = "RESOLVED";
    public static final String STATUS_REJECTED = "REJECTED";
    /** Legacy status treated as rejected for display/purge purposes. */
    public static final String STATUS_DISMISSED_LEGACY = "DISMISSED";

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;
    private final Map<UUID, Long> lastReportTime = new ConcurrentHashMap<>();

    public ReportService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        migrateFromJson();
    }

    private void migrateFromJson() {
        File reportsFile = new File(plugin.getDataFolder(), "reports.json");
        if (!reportsFile.exists()) return;

        plugin.getLogger().info("Found legacy reports.json. Migrating to database...");
        try {
            String content = Files.readString(reportsFile.toPath());
            Gson gson = new GsonBuilder().create();
            Type listType = new TypeToken<List<Report>>() {}.getType();
            List<Report> loaded = gson.fromJson(content, listType);

            if (loaded != null && !loaded.isEmpty()) {
                CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
                for (Report r : loaded) {
                    all = all.thenCompose(v -> storage.insertReport(r.id(), r.reporterUuid(), r.reporterName(), r.targetName(), r.targetUuid(), r.reason(), r.category(), r.timestamp(), r.status()));
                }
                all.thenRun(() -> {
                    reportsFile.renameTo(new File(plugin.getDataFolder(), "reports.json.migrated"));
                    plugin.getLogger().info("Successfully migrated " + loaded.size() + " reports to the database.");
                }).exceptionally(e -> {
                    plugin.getLogger().log(Level.SEVERE, "Failed to migrate reports to DB", e);
                    return null;
                });
            } else {
                reportsFile.delete();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate reports", e);
        }
    }

    public CompletableFuture<Boolean> submitReport(UUID reporterUuid, String reporterName, String targetName, String reason) {
        return submitReport(reporterUuid, reporterName, targetName, null, reason, null);
    }

    public CompletableFuture<Boolean> submitReport(UUID reporterUuid, String reporterName, String targetName, UUID targetUuid, String reason, String category) {
        if (!plugin.getConfigManager().isReportEnabled()) {
            Player p = plugin.getServer().getPlayer(reporterUuid);
            if (p != null) {
                p.sendMessage(plugin.getLocaleManager().getMessage("report.disabled"));
            }
            return CompletableFuture.completedFuture(false);
        }

        long now = System.currentTimeMillis();
        long cooldownMs = Math.max(0L, plugin.getConfigManager().getReportCooldownSeconds()) * 1000L;
        long duplicateWindowMs = Math.max(0L, plugin.getConfigManager().getReportDuplicateWindowSeconds()) * 1000L;

        if (reporterName != null && reporterName.equalsIgnoreCase(targetName)) {
            Player p = plugin.getServer().getPlayer(reporterUuid);
            if (p != null) {
                p.sendMessage(plugin.getLocaleManager().getMessage("report.self-report"));
            }
            return CompletableFuture.completedFuture(false);
        }

        java.util.concurrent.atomic.AtomicBoolean allowed = new java.util.concurrent.atomic.AtomicBoolean(false);
        lastReportTime.compute(reporterUuid, (k, old) -> {
            if (old != null && now - old < cooldownMs) {
                return old; // still on cooldown
            }
            allowed.set(true);
            return now;
        });
        if (!allowed.get()) {
            long lastTime = lastReportTime.getOrDefault(reporterUuid, now - cooldownMs);
            long remaining = (cooldownMs - (now - lastTime)) / 1000;
            Player p = plugin.getServer().getPlayer(reporterUuid);
            if (p != null) {
                p.sendMessage(plugin.getLocaleManager().getMessage("report.cooldown",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("duration", remaining + "s")));
            }
            return CompletableFuture.completedFuture(false);
        }

        return storage.getReportsByReporter(reporterUuid).thenCompose(reports -> {
            for (Report r : reports) {
                if (r.targetName().equalsIgnoreCase(targetName) && (now - r.timestamp()) < duplicateWindowMs) {
                    lastReportTime.remove(reporterUuid);
                    Player p = plugin.getServer().getPlayer(reporterUuid);
                    if (p != null) {
                        p.sendMessage(plugin.getLocaleManager().getMessage("report.already-reported"));
                    }
                    return CompletableFuture.completedFuture(false);
                }
            }

            Report report = new Report(UUID.randomUUID().toString(), reporterUuid, reporterName, targetName, targetUuid, reason, normalizeCategory(category), now, STATUS_PENDING);
            return storage.insertReport(report.id(), report.reporterUuid(), report.reporterName(), report.targetName(), report.targetUuid(), report.reason(), report.category(), report.timestamp(), report.status())
                    .thenApply(v -> {
                        plugin.getSchedulerAdapter().runTask(() -> {
                            notifyStaff(report);
                            Player p = plugin.getServer().getPlayer(reporterUuid);
                            if (p != null) {
                                p.sendMessage(plugin.getLocaleManager().getMessage("report.submitted",
                                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", targetName)));
                            }
                        });
                        return true;
                    });
        });
    }

    private void notifyStaff(Report report) {
        net.kyori.adventure.text.Component message = plugin.getLocaleManager().getMessage("report.staff-alert",
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("reporter", report.reporterName()),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", report.targetName()),
                net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("reason", report.reason()));
        String perm = plugin.getConfigManager().getReportNotifyPermission();

        for (var player : plugin.getServer().getOnlinePlayers()) {
            if (player.hasPermission("controlbans.alerts.receive") || player.hasPermission(perm)) {
                player.sendMessage(message);
            }
        }

        try {
            if (plugin.getIntegrationService() != null) plugin.getIntegrationService().onReport(report);
        } catch (Exception ignored) {}
    }

    public CompletableFuture<List<Report>> getReports() {
        return storage.getReports();
    }

    public CompletableFuture<List<Report>> getReportsByReporter(UUID reporterUuid) {
        return storage.getReportsByReporter(reporterUuid);
    }

    public CompletableFuture<Boolean> updateReportStatus(String id, String status) {
        return storage.updateReportStatus(id, status);
    }

    public CompletableFuture<Optional<Report>> getReport(String id) {
        return storage.getReport(id);
    }

    public CompletableFuture<Boolean> deleteReport(String id) {
        return storage.deleteReport(id);
    }

    private static String normalizeCategory(String category) {
        return (category == null || category.isBlank()) ? "other" : category;
    }

    /** Legacy "DISMISSED" is displayed as REJECTED. */
    public static String displayStatus(String status) {
        if (status == null) return STATUS_PENDING;
        return STATUS_DISMISSED_LEGACY.equalsIgnoreCase(status) ? STATUS_REJECTED : status.toUpperCase();
    }

    public void cleanupPlayer(UUID uuid) {
        lastReportTime.remove(uuid);
    }

    public record Report(String id, UUID reporterUuid, String reporterName, String targetName, UUID targetUuid, String reason, String category, long timestamp, String status) {
        public Report {
            if (status == null) status = STATUS_PENDING;
            category = normalizeCategory(category);
        }
    }
}
