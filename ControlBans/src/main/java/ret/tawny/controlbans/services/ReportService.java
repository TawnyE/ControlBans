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
                    all = all.thenCompose(v -> storage.insertReport(r.id(), r.reporterUuid(), r.reporterName(), r.targetName(), r.reason(), r.timestamp(), r.status()));
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

    public boolean submitReport(UUID reporterUuid, String reporterName, String targetName, String reason) {
        long lastTime = lastReportTime.getOrDefault(reporterUuid, 0L);
        long now = System.currentTimeMillis();
        long cooldownMs = 60_000L;

        if (now - lastTime < cooldownMs) {
            long remaining = (cooldownMs - (now - lastTime)) / 1000;
            Player p = plugin.getServer().getPlayer(reporterUuid);
            if (p != null) {
                p.sendMessage(plugin.getLocaleManager().getMessage("report.cooldown",
                        net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("duration", remaining + "s")));
            }
            return false;
        }

        storage.getReportsByReporter(reporterUuid).thenAccept(reports -> {
            for (Report r : reports) {
                if (r.targetName().equalsIgnoreCase(targetName) && (now - r.timestamp()) < 300_000L) {
                    Player p = plugin.getServer().getPlayer(reporterUuid);
                    if (p != null) {
                        p.sendMessage(plugin.getLocaleManager().getMessage("report.already-reported"));
                    }
                    return;
                }
            }

            Report report = new Report(UUID.randomUUID().toString(), reporterUuid, reporterName, targetName, reason, now, "PENDING");
            storage.insertReport(report.id(), report.reporterUuid(), report.reporterName(), report.targetName(), report.reason(), report.timestamp(), report.status())
                    .thenRun(() -> {
                        lastReportTime.put(reporterUuid, now);
                        notifyStaff(report);
                        Player p = plugin.getServer().getPlayer(reporterUuid);
                        if (p != null) {
                            p.sendMessage(plugin.getLocaleManager().getMessage("report.submitted",
                                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", targetName)));
                        }
                    });
        });

        return true;
    }

    private void notifyStaff(Report report) {
        plugin.getSchedulerAdapter().runTask(() -> {
            net.kyori.adventure.text.Component message = plugin.getLocaleManager().getMessage("report.staff-alert",
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("reporter", report.reporterName()),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("player", report.targetName()),
                    net.kyori.adventure.text.minimessage.tag.resolver.Placeholder.unparsed("reason", report.reason()));

            for (var player : plugin.getServer().getOnlinePlayers()) {
                if (player.hasPermission("controlbans.alerts.receive")) {
                    player.sendMessage(message);
                }
            }
        });

        plugin.getIntegrationService().onReport(report);
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

    public void cleanupPlayer(UUID uuid) {
        lastReportTime.remove(uuid);
    }

    public record Report(String id, UUID reporterUuid, String reporterName, String targetName, String reason, long timestamp, String status) {
        public Report {
            if (status == null) status = "PENDING";
        }
    }
}
