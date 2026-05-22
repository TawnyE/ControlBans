package ret.tawny.controlbans.services;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.util.ChatUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class ChatManager implements Listener {

    private final ControlBansPlugin plugin;
    private volatile boolean locked = false;
    private volatile long slowmodeDelay = 0;
    private final Map<UUID, Long> lastMessageTime = new ConcurrentHashMap<>();

    public ChatManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
        loadState();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastMessageTime.remove(event.getPlayer().getUniqueId());
    }

    public boolean isLocked() {
        return locked;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        saveState();
    }

    public long getSlowmodeDelay() {
        return slowmodeDelay;
    }

    public void setSlowmodeDelay(long delaySeconds) {
        this.slowmodeDelay = delaySeconds;
        saveState();
    }

    public boolean canChat(Player player) {
        if (player.hasPermission("controlbans.bypass.chat"))
            return true;

        if (locked) {
            plugin.getSchedulerAdapter().runTaskForPlayer(player,
                    () -> player.sendMessage(ChatUtil.colorize("&cGlobal chat is currently locked.")));
            return false;
        }

        if (slowmodeDelay > 0) {
            long now = System.currentTimeMillis();
            long last = lastMessageTime.getOrDefault(player.getUniqueId(), 0L);
            long diff = (now - last) / 1000;

            if (diff < slowmodeDelay) {
                plugin.getSchedulerAdapter().runTaskForPlayer(player, () -> player.sendMessage(
                        ChatUtil.colorize("&cChat is in slowmode. Please wait " + (slowmodeDelay - diff) + "s.")));
                return false;
            }
            lastMessageTime.put(player.getUniqueId(), now);
        }

        return true;
    }

    private void loadState() {
        var config = plugin.getConfig();
        this.locked = config.getBoolean("chat-state.locked", false);
        this.slowmodeDelay = config.getLong("chat-state.slowmode-delay", 0);
    }

    private void saveState() {
        var config = plugin.getConfig();
        config.set("chat-state.locked", locked);
        config.set("chat-state.slowmode-delay", slowmodeDelay);
        plugin.saveConfig();
    }
}
