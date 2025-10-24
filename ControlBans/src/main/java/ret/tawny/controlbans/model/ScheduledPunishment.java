package ret.tawny.controlbans.model;

import java.time.Instant;
import java.util.UUID;

public class ScheduledPunishment {

    private final int id;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final String reason;
    private final UUID staffUuid;
    private final String staffName;
    private final long executionTime;
    private final long durationSeconds;
    private final boolean silent;
    private final boolean ipBan;
    private final String category;
    private final int escalationLevel;

    private ScheduledPunishment(Builder builder) {
        this.id = builder.id;
        this.type = builder.type;
        this.targetUuid = builder.targetUuid;
        this.targetName = builder.targetName;
        this.reason = builder.reason;
        this.staffUuid = builder.staffUuid;
        this.staffName = builder.staffName;
        this.executionTime = builder.executionTime;
        this.durationSeconds = builder.durationSeconds;
        this.silent = builder.silent;
        this.ipBan = builder.ipBan;
        this.category = builder.category;
        this.escalationLevel = builder.escalationLevel;
    }

    public int getId() { return id; }
    public PunishmentType getType() { return type; }
    public UUID getTargetUuid() { return targetUuid; }
    public String getTargetName() { return targetName; }
    public String getReason() { return reason; }
    public UUID getStaffUuid() { return staffUuid; }
    public String getStaffName() { return staffName; }
    public long getExecutionTime() { return executionTime; }
    public long getDurationSeconds() { return durationSeconds; }
    public boolean isSilent() { return silent; }
    public boolean isIpBan() { return ipBan; }
    public String getCategory() { return category; }
    public int getEscalationLevel() { return escalationLevel; }

    public boolean isDue() {
        return System.currentTimeMillis() >= executionTime;
    }

    public Instant getExecutionInstant() {
        return Instant.ofEpochMilli(executionTime);
    }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private int id;
        private PunishmentType type;
        private UUID targetUuid;
        private String targetName;
        private String reason;
        private UUID staffUuid;
        private String staffName;
        private long executionTime;
        private long durationSeconds;
        private boolean silent;
        private boolean ipBan;
        private String category;
        private int escalationLevel;

        public Builder id(int id) { this.id = id; return this; }
        public Builder type(PunishmentType type) { this.type = type; return this; }
        public Builder targetUuid(UUID targetUuid) { this.targetUuid = targetUuid; return this; }
        public Builder targetName(String targetName) { this.targetName = targetName; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder staffUuid(UUID staffUuid) { this.staffUuid = staffUuid; return this; }
        public Builder staffName(String staffName) { this.staffName = staffName; return this; }
        public Builder executionTime(long executionTime) { this.executionTime = executionTime; return this; }
        public Builder durationSeconds(long durationSeconds) { this.durationSeconds = durationSeconds; return this; }
        public Builder silent(boolean silent) { this.silent = silent; return this; }
        public Builder ipBan(boolean ipBan) { this.ipBan = ipBan; return this; }
        public Builder category(String category) { this.category = category; return this; }
        public Builder escalationLevel(int escalationLevel) { this.escalationLevel = escalationLevel; return this; }

        public ScheduledPunishment build() { return new ScheduledPunishment(this); }
    }
}
