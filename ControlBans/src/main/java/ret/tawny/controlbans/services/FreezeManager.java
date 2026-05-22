package ret.tawny.controlbans.services;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class FreezeManager {

    private final ControlBansPlugin plugin;
    private final Set<UUID> frozenPlayers = ConcurrentHashMap.newKeySet();

    public FreezeManager(ControlBansPlugin plugin) {
        this.plugin = plugin;
    }

    public boolean isFrozen(UUID uuid) {
        return frozenPlayers.contains(uuid);
    }

    public void setFrozen(UUID uuid, boolean frozen) {
        if (frozen) {
            frozenPlayers.add(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("freeze.frozen-title"));
                player.sendMessage(plugin.getLocaleManager().getMessage("freeze.frozen-discord"));
                player.sendMessage(plugin.getLocaleManager().getMessage("freeze.frozen-logout-warning"));
            }
        } else {
            frozenPlayers.remove(uuid);
            Player player = Bukkit.getPlayer(uuid);
            if (player != null) {
                player.sendMessage(plugin.getLocaleManager().getMessage("freeze.unfrozen"));
            }
        }
    }

    public void toggleFreeze(UUID uuid) {
        setFrozen(uuid, !isFrozen(uuid));
    }
}
