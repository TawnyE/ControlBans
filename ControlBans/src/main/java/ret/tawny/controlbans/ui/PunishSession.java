package ret.tawny.controlbans.ui;

import ret.tawny.controlbans.services.EscalationService;

import java.util.UUID;

/**
 * Tracks which target + category a staff member is currently picking a
 * preset for. Needed because dialog callbacks fire later (possibly minutes
 * after the dialog was opened) and must re-derive the context.
 */
public record PunishSession(UUID targetUuid, String targetName, EscalationService.Category category, long createdAt) {

    public static final long TTL_MILLIS = 5 * 60 * 1000L;

    public boolean isExpired(long now) {
        return now - createdAt > TTL_MILLIS;
    }
}
