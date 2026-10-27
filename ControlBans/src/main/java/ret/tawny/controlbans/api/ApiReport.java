package ret.tawny.controlbans.api;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * Immutable snapshot of a player-submitted report.
 *
 * @param id           unique report id
 * @param reporterUuid UUID of the reporting player
 * @param reporterName name of the reporting player
 * @param targetName   name of the reported player
 * @param reason       report reason text
 * @param category     report category (e.g. "hacking", "chat", "other")
 * @param timestamp    epoch millis when the report was submitted
 * @param status       report status: PENDING, RESOLVED or REJECTED
 */
public record ApiReport(String id,
                        UUID reporterUuid,
                        @Nullable String reporterName,
                        String targetName,
                        @Nullable String reason,
                        String category,
                        long timestamp,
                        String status) {
}
