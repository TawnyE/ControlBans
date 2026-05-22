package ret.tawny.controlbans.model;

public enum PunishmentType {
    BAN("Ban", "banned", "controlbans_bans"),
    TEMPBAN("Temporary Ban", "temporarily banned", "controlbans_bans"),
    MUTE("Mute", "muted", "controlbans_mutes"),
    TEMPMUTE("Temporary Mute", "temporarily muted", "controlbans_mutes"),
    WARN("Warning", "warned", "controlbans_warnings"),
    KICK("Kick", "kicked", "controlbans_kicks"),
    IPBAN("IP Ban", "IP banned", "controlbans_bans"),
    TEMPIPBAN("Temporary IP Ban", "temporarily IP banned", "controlbans_bans"),
    VOICEMUTE("Voice Mute", "voice muted", "controlbans_voicemutes"),
    TEMPVOICEMUTE("Temporary Voice Mute", "temporarily voice muted", "controlbans_voicemutes"),
    IPMUTE("IP Mute", "IP muted", "controlbans_mutes"),
    TEMPIPMUTE("Temporary IP Mute", "temporarily IP muted", "controlbans_mutes");

    private final String displayName;
    private final String verb;
    private final String tableName;

    PunishmentType(String displayName, String verb, String tableName) {
        this.displayName = displayName;
        this.verb = verb;
        this.tableName = tableName;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getVerb() {
        return verb;
    }

    public String getTableName() {
        return tableName;
    }

    public boolean isBan() {
        return this == BAN || this == TEMPBAN || this == IPBAN || this == TEMPIPBAN;
    }

    public boolean isMute() {
        return this == MUTE || this == TEMPMUTE || this == IPMUTE || this == TEMPIPMUTE;
    }

    public boolean isTemporary() {
        return this == TEMPBAN || this == TEMPMUTE || this == TEMPVOICEMUTE || this == TEMPIPBAN || this == TEMPIPMUTE;
    }
}