package ret.tawny.controlbans.model;

public enum PunishmentType {
    BAN("Ban", "banned", "litebans_bans"),
    TEMPBAN("Temporary Ban", "temporarily banned", "litebans_bans"),
    MUTE("Mute", "muted", "litebans_mutes"),
    TEMPMUTE("Temporary Mute", "temporarily muted", "litebans_mutes"),
    WARN("Warning", "warned", "litebans_warnings"),
    KICK("Kick", "kicked", "litebans_kicks"),
    IPBAN("IP Ban", "IP banned", "litebans_bans"),
    VOICEMUTE("Voice Mute", "voice muted", "controlbans_voicemutes");
    
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
        return this == BAN || this == TEMPBAN || this == IPBAN;
    }
    
    public boolean isMute() {
        return this == MUTE || this == TEMPMUTE;
    }
    
    public boolean isTemporary() {
        return this == TEMPBAN || this == TEMPMUTE;
    }
}