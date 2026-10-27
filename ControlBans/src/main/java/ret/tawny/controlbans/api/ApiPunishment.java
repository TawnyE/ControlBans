package ret.tawny.controlbans.api;

import ret.tawny.controlbans.model.PunishmentType;
import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a single punishment record.
 *
 * @param id         short public punishment id (e.g. {@code "A1B2C3D4"})
 * @param type       the punishment category
 * @param targetUuid UUID of the punished player
 * @param targetName last known name of the punished player
 * @param reason     punishment reason (may be a default from config)
 * @param operator   display name of the issuing staff member, or {@code "Console"} for console/no operator
 * @param issuedAt   epoch millis when the punishment was issued
 * @param expiresAt  epoch millis when the punishment expires, or {@code -1} for permanent
 * @param silent     whether the punishment was issued silently
 * @param active     whether the punishment is currently active
 */
public record ApiPunishment(String id,
                            PunishmentType type,
                            UUID targetUuid,
                            @Nullable String targetName,
                            @Nullable String reason,
                            String operator,
                            long issuedAt,
                            long expiresAt,
                            boolean silent,
                            boolean active) {

    /**
     * @return true if this punishment never expires
     */
    public boolean isPermanent() {
        return expiresAt <= 0;
    }
}
