package ret.tawny.controlbans.services;

import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.storage.StorageInterface;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class AppealService {

    private static final int MAX_MESSAGE_LENGTH = 2000;

    private final StorageInterface storage;
    private final ConfigManager configManager;

    public AppealService(StorageInterface storage, ConfigManager configManager) {
        this.storage = storage;
        this.configManager = configManager;
    }

    public CompletableFuture<AppealResult> submitAppeal(Punishment punishment, String rawMessage) {
        if (punishment == null || !punishment.getType().isMute()) {
            return CompletableFuture.completedFuture(AppealResult.notMuted());
        }

        if (!configManager.isAppealsEnabled()) {
            return CompletableFuture.completedFuture(AppealResult.disabled());
        }

        String message = sanitizeMessage(rawMessage, punishment.getReason());
        UUID targetUuid = punishment.getTargetUuid();
        String punishmentId = punishment.getPunishmentId();

        long now = System.currentTimeMillis();
        Duration cooldown = configManager.getAppealCooldown();
        Duration window = configManager.getAppealWindowDuration();
        int maxSubmissions = configManager.getAppealMaxSubmissions();

        return storage.getLastAppealTime(targetUuid).thenCompose(lastCreated -> {
            long cooldownMillis = cooldown.toMillis();
            if (cooldownMillis > 0 && lastCreated > 0 && now - lastCreated < cooldownMillis) {
                return CompletableFuture.completedFuture(AppealResult.onCooldown(lastCreated + cooldownMillis));
            }

            if (maxSubmissions > 0 && !window.isZero()) {
                long windowMillis = window.toMillis();
                long windowStart = now - windowMillis;

                return storage.getAppealCount(targetUuid, windowStart).thenCompose(count -> {
                    if (count >= maxSubmissions) {
                        return CompletableFuture.completedFuture(AppealResult.limitReached(now + cooldownMillis));
                    }

                    return insertAppeal(punishmentId, targetUuid, message, now);
                });
            }

            return insertAppeal(punishmentId, targetUuid, message, now);
        });
    }

    private CompletableFuture<AppealResult> insertAppeal(String punishmentId, UUID targetUuid, String message, long now) {
        return storage.addAppeal(punishmentId, targetUuid, message, now)
                .thenApply(v -> AppealResult.accepted());
    }

    private String sanitizeMessage(String rawMessage, String fallbackReason) {
        String message = rawMessage == null ? "" : rawMessage.trim();
        if (message.isEmpty()) {
            message = fallbackReason == null ? "No reason provided" : fallbackReason;
        }
        if (message.length() > MAX_MESSAGE_LENGTH) {
            message = message.substring(0, MAX_MESSAGE_LENGTH);
        }
        return message;
    }

    public enum AppealStatus {
        ACCEPTED,
        NOT_MUTED,
        DISABLED,
        ON_COOLDOWN,
        LIMIT_REACHED
    }

    public record AppealResult(AppealStatus status, long nextAllowedAt) {
        private static final AppealResult ACCEPTED = new AppealResult(AppealStatus.ACCEPTED, 0L);
        private static final AppealResult NOT_MUTED = new AppealResult(AppealStatus.NOT_MUTED, 0L);
        private static final AppealResult DISABLED = new AppealResult(AppealStatus.DISABLED, 0L);

        public static AppealResult accepted() { return ACCEPTED; }
        public static AppealResult notMuted() { return NOT_MUTED; }
        public static AppealResult disabled() { return DISABLED; }
        public static AppealResult onCooldown(long nextAllowedAt) { return new AppealResult(AppealStatus.ON_COOLDOWN, nextAllowedAt); }
        public static AppealResult limitReached(long nextAllowedAt) { return new AppealResult(AppealStatus.LIMIT_REACHED, nextAllowedAt); }
    }
}