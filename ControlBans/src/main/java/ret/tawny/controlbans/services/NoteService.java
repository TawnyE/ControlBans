package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.storage.StorageInterface;

import java.io.File;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class NoteService {

    private final ControlBansPlugin plugin;
    private final StorageInterface storage;

    public NoteService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.storage = plugin.getStorage();
        migrateFromJson();
    }

    private void migrateFromJson() {
        File notesFile = new File(plugin.getDataFolder(), "notes.json");
        if (!notesFile.exists()) return;

        plugin.getLogger().info("Found legacy notes.json. Migrating to database...");
        try {
            String content = Files.readString(notesFile.toPath());
            Gson gson = new GsonBuilder().create();
            Type mapType = new TypeToken<Map<UUID, List<PlayerNote>>>() {}.getType();
            Map<UUID, List<PlayerNote>> loaded = gson.fromJson(content, mapType);

            if (loaded != null && !loaded.isEmpty()) {
                CompletableFuture<Void> all = CompletableFuture.completedFuture(null);
                for (Map.Entry<UUID, List<PlayerNote>> entry : loaded.entrySet()) {
                    UUID targetUuid = entry.getKey();
                    for (PlayerNote note : entry.getValue()) {
                        all = all.thenCompose(v -> storage.addNote(targetUuid, note.staffName(), note.noteText(), note.timestamp()));
                    }
                }
                all.thenRun(() -> {
                    notesFile.renameTo(new File(plugin.getDataFolder(), "notes.json.migrated"));
                    plugin.getLogger().info("Successfully migrated player notes to the database.");
                }).exceptionally(e -> {
                    plugin.getLogger().log(Level.SEVERE, "Failed to migrate notes to DB", e);
                    return null;
                });
            } else {
                notesFile.delete();
            }
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to migrate notes", e);
        }
    }

    public CompletableFuture<Void> addNote(UUID targetUuid, String staffName, String noteText) {
        return storage.addNote(targetUuid, staffName, noteText, System.currentTimeMillis());
    }

    public CompletableFuture<Boolean> removeNote(UUID targetUuid, int index) {
        return storage.removeNote(targetUuid, index);
    }

    public CompletableFuture<List<PlayerNote>> getNotes(UUID targetUuid) {
        return storage.getNotes(targetUuid);
    }

    public record PlayerNote(String staffName, String noteText, long timestamp) {}
}
