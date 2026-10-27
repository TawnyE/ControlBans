package ret.tawny.controlbans.api;

import org.jetbrains.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Public, asynchronous ControlBans API.
 * <p>
 * All methods are non-blocking and complete on internal worker threads. Never touch
 * Bukkit state from a completion stage unless you re-dispatch to the server thread
 * (or the entity scheduler on Folia).
 * <p>
 * Obtain via {@link ControlBansAPI#get()}.
 */
public interface ControlBans {

    /**
     * @param uuid player UUID
     * @return the player's active ban, or empty if not currently banned
     */
    CompletableFuture<Optional<ApiPunishment>> getActiveBan(UUID uuid);

    /**
     * @param uuid player UUID
     * @return the player's active mute, or empty if not currently muted
     */
    CompletableFuture<Optional<ApiPunishment>> getActiveMute(UUID uuid);

    /**
     * @param uuid player UUID
     * @return true while the player has an active ban
     */
    CompletableFuture<Boolean> isBanned(UUID uuid);

    /**
     * @param uuid player UUID
     * @return true while the player has an active mute
     */
    CompletableFuture<Boolean> isMuted(UUID uuid);

    /**
     * @param uuid player UUID
     * @return all recorded punishments for the player, newest first
     */
    CompletableFuture<List<ApiPunishment>> getHistory(UUID uuid);

    /**
     * Bans a player. Goes through the same code path as the in-game {@code /ban} command,
     * including protection checks and the duplicate-active-ban guard.
     *
     * @param uuid       target player UUID
     * @param targetName target player's name (used for messages/records)
     * @param reason     ban reason, or {@code null} for the configured default
     * @param duration   ban length, or {@code null} for a permanent ban
     * @param operator   operator display name, or {@code null} for the console
     * @return the created punishment, resolving to the active ban record afterwards
     */
    CompletableFuture<ApiPunishment> ban(UUID uuid, String targetName, @Nullable String reason,
                                         @Nullable Duration duration, @Nullable String operator);

    /**
     * Mutes a player. Mirrors {@code /mute} / {@code /tempmute}.
     *
     * @param uuid       target player UUID
     * @param targetName target player's name
     * @param reason     mute reason, or {@code null} for the configured default
     * @param duration   mute length, or {@code null} for a permanent mute
     * @param operator   operator display name, or {@code null} for the console
     * @return the created punishment
     */
    CompletableFuture<ApiPunishment> mute(UUID uuid, String targetName, @Nullable String reason,
                                          @Nullable Duration duration, @Nullable String operator);

    /**
     * Kicks a player. Mirrors {@code /kick}.
     *
     * @param uuid       target player UUID
     * @param targetName target player's name
     * @param reason     kick reason, or {@code null} for the configured default
     * @param operator   operator display name, or {@code null} for the console
     * @return the recorded kick punishment
     */
    CompletableFuture<ApiPunishment> kick(UUID uuid, String targetName, @Nullable String reason,
                                          @Nullable String operator);

    /**
     * Warns a player. Mirrors {@code /warn}; warnings are duration-less by design.
     *
     * @param uuid       target player UUID
     * @param targetName target player's name
     * @param reason     warning reason, or {@code null} for the configured default
     * @param operator   operator display name, or {@code null} for the console
     * @return the recorded warning
     */
    CompletableFuture<ApiPunishment> warn(UUID uuid, String targetName, @Nullable String reason,
                                          @Nullable String operator);

    /**
     * Removes the player's active ban. Mirrors {@code /unban}.
     *
     * @param uuid player UUID
     * @return true if an active ban was removed, false if the player was not banned
     */
    CompletableFuture<Boolean> unban(UUID uuid);

    /**
     * Removes the player's active mute. Mirrors {@code /unmute}.
     *
     * @param uuid player UUID
     * @return true if an active mute was removed, false if the player was not muted
     */
    CompletableFuture<Boolean> unmute(UUID uuid);

    /**
     * @return all reports currently pending review (status PENDING), newest first
     */
    CompletableFuture<List<ApiReport>> getPendingReports();
}
