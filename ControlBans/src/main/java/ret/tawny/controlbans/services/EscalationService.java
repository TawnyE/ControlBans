package ret.tawny.controlbans.services;

import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public class EscalationService {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;

    public EscalationService(ControlBansPlugin plugin, PunishmentService punishmentService) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
    }

    public CompletableFuture<Long> calculateEscalation(UUID targetUuid, String reason, long currentDuration) {
        if (!plugin.getConfigManager().isEscalationEnabled()) {
            return CompletableFuture.completedFuture(currentDuration);
        }

        int historyLimit = plugin.getConfigManager().getEscalationHistoryLimit();
        long windowDays = plugin.getConfigManager().getEscalationWindowDays();
        long cutoff = windowDays > 0 ? System.currentTimeMillis() - (windowDays * 24L * 60L * 60L * 1000L) : Long.MIN_VALUE;
        String currentTrack = plugin.getConfigManager().resolveEscalationTrack(reason);

        return punishmentService.getPunishmentHistory(targetUuid, historyLimit).thenApply(history -> {
            long priorCount = history.stream()
                    .filter(this::countsTowardEscalation)
                    .filter(punishment -> punishment.getCreatedTime() >= cutoff)
                    .filter(punishment -> !plugin.getConfigManager().isEscalationReasonFiltered()
                            || currentTrack.equals(plugin.getConfigManager().resolveEscalationTrack(punishment.getReason())))
                    .count();

            String escalationSetting = plugin.getConfigManager().getEscalationLevels(reason).get((int) priorCount + 1);
            if (escalationSetting == null) return currentDuration;
            if (escalationSetting.equalsIgnoreCase("permanent")) return -1L;
            try { return Math.max(currentDuration, TimeUtil.parseDuration(escalationSetting)); } catch (Exception e) { return currentDuration; }
        });
    }

    private boolean countsTowardEscalation(Punishment punishment) {
        return switch (punishment.getType()) {
            case BAN -> plugin.getConfigManager().isEscalationBanIncluded();
            case TEMPBAN -> plugin.getConfigManager().isEscalationTempBanIncluded();
            case IPBAN -> plugin.getConfigManager().isEscalationIpBanIncluded();
            default -> false;
        };
    }
}
