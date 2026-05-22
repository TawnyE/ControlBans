package ret.tawny.controlbans.services;

import java.util.UUID;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.JoinConfiguration;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.model.Punishment;
import ret.tawny.controlbans.model.PunishmentType;
import ret.tawny.controlbans.util.IpUtil;
import ret.tawny.controlbans.util.SchedulerAdapter;
import ret.tawny.controlbans.util.TimeUtil;

import java.util.List;
import java.util.regex.Pattern;

public class NotificationService {

    private final ControlBansPlugin plugin;
    private final ConfigManager configManager;
    private final LocaleManager localeManager;
    private final SchedulerAdapter scheduler;
    private final ProxyService proxyService;

    private static final Pattern IP_PATTERN = Pattern.compile("^([0-9]{1,3}\\.){3}[0-9]{1,3}$");

    public NotificationService(ControlBansPlugin plugin) {
        this.plugin = plugin;
        this.configManager = plugin.getConfigManager();
        this.localeManager = plugin.getLocaleManager();
        this.scheduler = plugin.getSchedulerAdapter();
        this.proxyService = plugin.getProxyService();
    }

    public void broadcastPunishment(Punishment punishment) {
        if (!configManager.isBroadcastEnabled()) return;
        if (punishment.isSilent()) return;
        Component message = formatBroadcastMessage(punishment);
        proxyService.sendBroadcastMessage(LegacyComponentSerializer.legacySection().serialize(message));
    }

    public void broadcastUnban(String playerName, String staffName) {
        if (!configManager.isBroadcastEnabled()) return;
        String display = IP_PATTERN.matcher(playerName).matches() ? IpUtil.maskIp(playerName) : playerName;
        Component message = localeManager.getMessage("broadcasts.unban",
                Placeholder.unparsed("player", display),
                Placeholder.unparsed("staff", staffName));
        proxyService.sendBroadcastMessage(LegacyComponentSerializer.legacySection().serialize(message));
        sendStaffAlertForAction("unban", display, staffName);
    }

    public void broadcastUnmute(String playerName, String staffName) {
        if (!configManager.isBroadcastEnabled()) return;
        Component message = localeManager.getMessage("broadcasts.unmute",
                Placeholder.unparsed("player", playerName),
                Placeholder.unparsed("staff", staffName));
        proxyService.sendBroadcastMessage(LegacyComponentSerializer.legacySection().serialize(message));
        sendStaffAlertForAction("unmute", playerName, staffName);
    }

    public void sendStaffAlert(Punishment punishment) {
        if (!configManager.isStaffAlertsEnabled()) return;

        String typeKey;
        if (punishment.getType() == PunishmentType.IPBAN) typeKey = "ipban";
        else if (punishment.getType() == PunishmentType.TEMPVOICEMUTE) typeKey = "tempvoicemute";
        else typeKey = punishment.getType().name().toLowerCase();

        String displayName = switch (punishment.getType()) {
            case IPBAN, TEMPIPBAN, IPMUTE, TEMPIPMUTE -> IpUtil.maskIp(punishment.getTargetName());
            default -> punishment.getTargetName() != null ? punishment.getTargetName() : "Unknown";
        };

        Component alert = localeManager.getMessage("staff-alerts." + typeKey,
                Placeholder.unparsed("player", displayName),
                Placeholder.unparsed("staff", punishment.getStaffName() != null ? punishment.getStaffName() : "Console"),
                Placeholder.unparsed("reason", punishment.getReason() != null ? punishment.getReason() : "Unspecified"),
                Placeholder.unparsed("duration", punishment.getType().isTemporary()
                        ? TimeUtil.formatDuration(punishment.getRemainingTime() / 1000) : "Permanent"));

        broadcastToStaff(alert, null);
    }

    public void sendStaffAlertForAction(String actionKey, String playerName, String staffName) {
        if (!configManager.isStaffAlertsEnabled()) return;

        Component alert = localeManager.getMessage("staff-alerts." + actionKey,
                Placeholder.unparsed("player", playerName != null ? playerName : "Unknown"),
                Placeholder.unparsed("staff", staffName != null ? staffName : "Console"));

        String soundName = null;
        if (configManager.isStaffAlertSoundEnabled()) {
            soundName = configManager.getStaffAlertSound();
        }

        broadcastToStaff(alert, soundName);
    }

    private void broadcastToStaff(Component message, String soundName) {
        Sound alertSound = null;
        if (soundName != null) {
            try {
                alertSound = Sound.valueOf(soundName);
            } catch (IllegalArgumentException ignored) {}
        }

        float volume = configManager.getStaffAlertVolume();
        float pitch = configManager.getStaffAlertPitch();

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.hasPermission("controlbans.alerts.receive")) {
                online.sendMessage(message);
                if (alertSound != null) {
                    online.playSound(online.getLocation(), alertSound, volume, pitch);
                }
            }
        }
    }

    private Component formatBroadcastMessage(Punishment punishment) {
        String typeKey;
        if (punishment.getType() == PunishmentType.IPBAN) typeKey = "ipban";
        else if (punishment.getType() == PunishmentType.TEMPVOICEMUTE) typeKey = "tempvoicemute";
        else typeKey = punishment.getType().name().toLowerCase();

        return localeManager.getMessage("broadcasts." + typeKey,
                Placeholder.unparsed("player", punishment.getTargetName()),
                Placeholder.unparsed("staff", punishment.getStaffName() != null ? punishment.getStaffName() : "Console"),
                Placeholder.unparsed("reason", punishment.getReason()),
                Placeholder.unparsed("duration", punishment.getType().isTemporary()
                        ? TimeUtil.formatDuration(punishment.getRemainingTime() / 1000) : "Permanent"));
    }

    public void onPunishmentSuccess(Punishment punishment) {
        scheduler.runTask(() -> {
            if (punishment.getType().isBan() || punishment.getType() == PunishmentType.KICK) {
                Component kickMessage = formatKickScreen(punishment);
                if (punishment.isIpBan() && punishment.getTargetIp() != null) {
                    for (Player player : Bukkit.getOnlinePlayers()) {
                        if (punishment.getTargetIp().equals(getPlayerIp(player.getUniqueId()))) {
                            player.kick(kickMessage);
                            proxyService.sendKickPlayerMessage(player.getName(),
                                    LegacyComponentSerializer.legacySection().serialize(kickMessage));
                        }
                    }
                } else {
                    Player target = Bukkit.getPlayer(punishment.getTargetUuid());
                    if (target != null && target.isOnline()) target.kick(kickMessage);
                    proxyService.sendKickPlayerMessage(punishment.getTargetName(),
                            LegacyComponentSerializer.legacySection().serialize(kickMessage));
                }
            }

            if (punishment.getType().isMute()) {
                Player target = Bukkit.getPlayer(punishment.getTargetUuid());
                if (target != null && target.isOnline()) {
                    formatMuteScreen(punishment).forEach(target::sendMessage);
                }
            }

            broadcastPunishment(punishment);
            sendStaffAlert(punishment);
        });

        IntegrationService integrationService = plugin.getIntegrationService();
        if (integrationService != null) {
            integrationService.onPunishment(punishment);
        }
    }

    public Component formatKickScreen(Punishment punishment) {
        String configPath = punishment.isIpBan()
                ? (punishment.isPermanent() ? "screens.ip_ban" : "screens.ip_tempban")
                : (punishment.getType() == PunishmentType.KICK ? "screens.kick"
                        : (punishment.isPermanent() ? "screens.ban" : "screens.tempban"));
        return Component.join(JoinConfiguration.newlines(), formatPunishmentScreen(punishment, configPath));
    }

    public List<Component> formatMuteScreen(Punishment punishment) {
        String configPath;
        if (punishment.getType() == PunishmentType.VOICEMUTE || punishment.getType() == PunishmentType.TEMPVOICEMUTE)
            configPath = "screens.voicemute";
        else configPath = punishment.isIpBan()
                ? (punishment.isPermanent() ? "screens.ip_mute" : "screens.ip_tempmute")
                : (punishment.getType() == PunishmentType.WARN ? "screens.warn"
                        : (punishment.isPermanent() ? "screens.mute" : "screens.tempmute"));
        return formatPunishmentScreen(punishment, configPath);
    }

    private List<Component> formatPunishmentScreen(Punishment punishment, String configPath) {
        String staff = punishment.getStaffName() != null ? punishment.getStaffName() : "Console";
        String reason = punishment.getReason() != null ? punishment.getReason() : "Unspecified";
        String id = punishment.getPunishmentId() != null ? punishment.getPunishmentId() : "N/A";
        String date = TimeUtil.formatDate(punishment.getCreatedTime());
        String duration = punishment.isPermanent() || punishment.getExpiryTime() <= 0
                ? "Permanent" : TimeUtil.formatDuration(punishment.getRemainingTime() / 1000);
        String playerName = punishment.getTargetName() != null ? punishment.getTargetName() : "Unknown";

        TagResolver placeholders = TagResolver.builder()
                .resolver(Placeholder.unparsed("player", playerName))
                .resolver(Placeholder.unparsed("staff", staff))
                .resolver(Placeholder.unparsed("reason", reason))
                .resolver(Placeholder.unparsed("id", id))
                .resolver(Placeholder.unparsed("date", date))
                .resolver(Placeholder.unparsed("duration", duration))
                .build();
        return localeManager.getMessageList(configPath, placeholders);
    }

    private String getPlayerIp(UUID uuid) {
        Player player = Bukkit.getPlayer(uuid);
        return player != null && player.getAddress() != null ? player.getAddress().getAddress().getHostAddress() : null;
    }
}
