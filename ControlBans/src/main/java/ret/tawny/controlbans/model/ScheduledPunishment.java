package ret.tawny.controlbans.model;

public record ScheduledPunishment(
String scheduleId,
ScheduledAction action,
PunishmentType type,
String targetName,
String targetIp,
long durationSeconds,
String reason,
String staffUuid,
String staffName,
long executeAt,
long createdAt,
boolean silent,
String serverOrigin
) {
public enum ScheduledAction {
BAN,
TEMPBAN,
MUTE,
TEMPMUTE,
KICK,
WARN,
VOICEMUTE,
TEMPVOICEMUTE,
IPBAN,
TEMPIPBAN,
IPMUTE,
TEMPIPMUTE
}
}
