package ret.tawny.controlbans.util;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
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

    public void runTaskLater(Runnable task, long delayTicks) {
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runDelayed(plugin, t -> task.run(), delayTicks);
        } else {
            Bukkit.getScheduler().runTaskLater(plugin, task, delayTicks);
        }
    }

    public void runTaskTimer(Runnable task, long delayTicks, long periodTicks) {
        if (isFolia) {
            Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delayTicks, periodTicks);
        } else {
            Bukkit.getScheduler().runTaskTimer(plugin, task, delayTicks, periodTicks);
        }
    }

    public void runTaskLaterAsync(Runnable task, long delayTicks) {
        if (isFolia) {
            // Elite: on Folia, GlobalRegionScheduler is NOT async — use AsyncScheduler for true async delay
            Bukkit.getAsyncScheduler().runDelayed(plugin, t -> task.run(), delayTicks * 50, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, task, delayTicks);
        }
    }

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

    public void runTaskAsynchronously(Runnable task) {
        runTaskAsyncNow(task);
    }

    public void runTaskAsyncNow(Runnable task) {
        if (isFolia) {
            Bukkit.getAsyncScheduler().runNow(plugin, t -> task.run());
        } else {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
        }
    }

    public void runTaskTimerAsync(Runnable task, long delayTicks, long periodTicks) {
        if (isFolia) {
            Bukkit.getAsyncScheduler().runAtFixedRate(plugin, t -> task.run(), delayTicks * 50, periodTicks * 50, TimeUnit.MILLISECONDS);
        } else {
            Bukkit.getScheduler().runTaskTimerAsynchronously(plugin, task, delayTicks, periodTicks);
        }
    }

    public <T> CompletableFuture<T> callSync(Supplier<T> supplier) {
        CompletableFuture<T> future = new CompletableFuture<>();
        try {
            runTask(() -> {
                try {
                    future.complete(supplier.get());
                } catch (Throwable throwable) {
                    future.completeExceptionally(throwable);
                }
            });
        } catch (Throwable t) {
            // Plugin disabled / scheduler shut down — runTask above threw before
            // scheduling, so complete exceptionally instead of hanging forever.
            future.completeExceptionally(t);
        }
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