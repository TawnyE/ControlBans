package ret.tawny.controlbans.model;

import java.util.UUID;

public class Punishment {
    private final int id;
    private final String punishmentId;
    private final PunishmentType type;
    private final UUID targetUuid;
    private final String targetName;
    private final String targetIp;
    private final String reason;
    private final UUID staffUuid;
    private final String staffName;
    private final long createdTime;
    private final long expiryTime;
    private final String serverOrigin;
    private final boolean silent;
    private final boolean ipBan;
    private final boolean active;

    private Punishment(Builder builder) {
        this.id = builder.id;
        this.punishmentId = builder.punishmentId;
        this.type = builder.type;
        this.targetUuid = builder.targetUuid;
        this.targetName = builder.targetName;
        this.targetIp = builder.targetIp;
        this.reason = builder.reason;
        this.staffUuid = builder.staffUuid;
        this.staffName = builder.staffName;
        this.createdTime = builder.createdTime;
        this.expiryTime = builder.expiryTime;
        this.serverOrigin = builder.serverOrigin;
        this.silent = builder.silent;
        this.ipBan = builder.ipBan;
        this.active = builder.active;
    }

    public static Builder builder() {
        return new Builder();
    }

    // Getters
    public int getId() { return id; }
    public String getPunishmentId() { return punishmentId; }
    public PunishmentType getType() { return type; }
    public UUID getTargetUuid() { return targetUuid; }
    public String getTargetName() { return targetName; }
    public String getTargetIp() { return targetIp; }
    public String getReason() { return reason; }
    public UUID getStaffUuid() { return staffUuid; }
    public String getStaffName() { return staffName; }
    public long getCreatedTime() { return createdTime; }
    public long getExpiryTime() { return expiryTime; }
    public String getServerOrigin() { return serverOrigin; }
    public boolean isSilent() { return silent; }
    public boolean isIpBan() { return ipBan; }
    public boolean isActive() { return active; }

    public boolean isPermanent() {
        return expiryTime == -1 || expiryTime == 0;
    }

    public boolean isExpired() {
        return !isPermanent() && System.currentTimeMillis() > expiryTime;
    }

    public long getRemainingTime() {
        if (isPermanent()) return -1;
        long remaining = expiryTime - System.currentTimeMillis();
        return Math.max(0, remaining);
    }

    public static class Builder {
        private int id;
        private String punishmentId;
        private PunishmentType type;
        private UUID targetUuid;
        private String targetName;
        private String targetIp;
        private String reason;
        private UUID staffUuid;
        private String staffName;
        private long createdTime;
        private long expiryTime;
        private String serverOrigin;
        private boolean silent;
        private boolean ipBan;
        private boolean active = true;

        public Builder id(int id) { this.id = id; return this; }
        public Builder punishmentId(String punishmentId) { this.punishmentId = punishmentId; return this; }
        public Builder type(PunishmentType type) { this.type = type; return this; }
        public Builder targetUuid(UUID targetUuid) { this.targetUuid = targetUuid; return this; }
        public Builder targetName(String targetName) { this.targetName = targetName; return this; }
        public Builder targetIp(String targetIp) { this.targetIp = targetIp; return this; }
        public Builder reason(String reason) { this.reason = reason; return this; }
        public Builder staffUuid(UUID staffUuid) { this.staffUuid = staffUuid; return this; }
        public Builder staffName(String staffName) { this.staffName = staffName; return this; }
        public Builder createdTime(long createdTime) { this.createdTime = createdTime; return this; }
        public Builder expiryTime(long expiryTime) { this.expiryTime = expiryTime; return this; }
        public Builder serverOrigin(String serverOrigin) { this.serverOrigin = serverOrigin; return this; }
        public Builder silent(boolean silent) { this.silent = silent; return this; }
        public Builder ipBan(boolean ipBan) { this.ipBan = ipBan; return this; }
        public Builder active(boolean active) { this.active = active; return this; }

        public Punishment build() {
            return new Punishment(this);
        }
    }
}