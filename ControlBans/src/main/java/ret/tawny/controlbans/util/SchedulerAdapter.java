package ret.tawny.controlbans.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Supplier;

public class SchedulerAdapter {

    private final JavaPlugin plugin;
    private final boolean isFolia;
    private final Executor syncExecutor;

    public SchedulerAdapter(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.isFolia = isFolia();
        this.syncExecutor = command -> runTask(command);
    }

    private boolean isFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Executes a task on the next tick for the entire server.
     * On Folia, this uses the Global Region Scheduler.
     * On Paper/Spigot, this uses the Bukkit Scheduler.
     */
    public void runTask(Runnable task) {
        if (!isFolia && Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().execute(plugin, task);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Executes a task related to a specific player.
     * On Folia, this uses the Player's Entity Scheduler.
     * On Paper/Spigot, this uses the Bukkit Scheduler.
     */
    public void runTaskForPlayer(Player player, Runnable task) {
        if (player == null) {
            runTask(task);
            return;
        }

        if (!isFolia && Bukkit.isPrimaryThread()) {
            task.run();
            return;
        }

        if (isFolia) {
            player.getScheduler().execute(plugin, task, null, 1L);
        } else {
            Bukkit.getScheduler().runTask(plugin, task);
        }
    }

    /**
     * Executes a task asynchronously. This is safe for both platforms.
     */
    public void runTaskAsynchronously(Runnable task) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
    }

    public <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        runTask(() -> {
            try {
                future.complete(supplier.get());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            }
        });
        return future;
    }

    public CompletableFuture<Void> runSync(Runnable runnable) {
        return callSync(() -> {
            runnable.run();
            return null;
        });
    }

    public Executor syncExecutor() {
        return syncExecutor;
    }
}