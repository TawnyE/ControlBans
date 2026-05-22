package ret.tawny.controlbans.services;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Player;
import ret.tawny.controlbans.ControlBansPlugin;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class AutoModService {

    private static final String STAFF_ALERT_PERMISSION = "controlbans.alerts.receive";

    private final ControlBansPlugin plugin;
    private final List<FilterRule> rules = new ArrayList<>();

    private final Cache<UUID, Deque<Long>> messageTimestamps;
    private final Cache<UUID, String> lastMessages;

    private boolean antiSpamEnabled;
    private int spamMaxMessages;
    private int spamSeconds;
    private boolean blockSimilar;

    private boolean antiCapsEnabled;
    private int capsMinLength;
    private double capsPercentage;

    public AutoModService(ControlBansPlugin plugin) {
        this.plugin = plugin;

        this.messageTimestamps = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();

        this.lastMessages = Caffeine.newBuilder()
                .expireAfterWrite(1, TimeUnit.MINUTES)
                .build();

        loadRules();
    }

    public void loadRules() {
        rules.clear();

        antiSpamEnabled = plugin.getConfig().getBoolean("automod.anti-spam.enabled", true);
        spamMaxMessages = plugin.getConfig().getInt("automod.anti-spam.max-messages", 4);
        spamSeconds = plugin.getConfig().getInt("automod.anti-spam.seconds", 4);
        blockSimilar = plugin.getConfig().getBoolean("automod.anti-spam.block-similar", true);

        antiCapsEnabled = plugin.getConfig().getBoolean("automod.anti-caps.enabled", true);
        capsMinLength = plugin.getConfig().getInt("automod.anti-caps.min-length", 5);
        capsPercentage = plugin.getConfig().getDouble("automod.anti-caps.percentage", 0.7);

        if (!plugin.getConfig().getBoolean("automod.enabled", true)) {
            return;
        }

        ConfigurationSection section = plugin.getConfig().getConfigurationSection("automod.rules");
        if (section == null) {
            return;
        }

        for (String key : section.getKeys(false)) {
            String patternString = section.getString(key + ".pattern");
            String message = section.getString(key + ".message", "Action blocked by AutoMod.");
            String actionStr = section.getString(key + ".action", "CANCEL");

            if (patternString == null) {
                continue;
            }

            try {
                Pattern pattern = Pattern.compile(patternString, Pattern.CASE_INSENSITIVE);
                Action action = Action.valueOf(actionStr.toUpperCase(Locale.ROOT));
                rules.add(new FilterRule(key, pattern, message, action));
            } catch (PatternSyntaxException e) {
                plugin.getLogger().warning("Invalid Regex pattern for AutoMod rule '" + key + "': " + e.getMessage());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("Invalid Action for AutoMod rule '" + key + "': " + actionStr);
            }
        }

        if (!rules.isEmpty() || antiSpamEnabled || antiCapsEnabled) {
            plugin.getLogger().info("AutoMod loaded (Regex: " + rules.size() + ", Anti-Spam: " + antiSpamEnabled + ", Anti-Caps: " + antiCapsEnabled + ")");
        }
    }

    public FilterRule checkContent(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        for (FilterRule rule : rules) {
            Matcher matcher = rule.pattern().matcher(content);
            if (matcher.find()) {
                return rule;
            }
        }
        return null;
    }

    public boolean isSpamming(Player player, String message) {
        if (!antiSpamEnabled || player.hasPermission("controlbans.bypass.spam")) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        long now = System.currentTimeMillis();

        if (blockSimilar) {
            String lastMsg = lastMessages.asMap().put(uuid, message);
            if (lastMsg != null && lastMsg.equalsIgnoreCase(message)) {
                return true;
            }
        }

        Deque<Long> timestamps = messageTimestamps.get(uuid, k -> new ArrayDeque<>());
        if (timestamps != null) {
            synchronized (timestamps) {
                timestamps.addLast(now);
                while (!timestamps.isEmpty() && now - timestamps.getFirst() > (spamSeconds * 1000L)) {
                    timestamps.removeFirst();
                }
                if (timestamps.size() > spamMaxMessages) {
                    return true;
                }
            }
        }
        return false;
    }

    public String fixCaps(String message) {
        if (!antiCapsEnabled || message == null || message.length() < capsMinLength) {
            return message;
        }

        long upperCount = message.chars().filter(Character::isUpperCase).count();
        long lettersCount = message.chars().filter(Character::isLetter).count();

        if (lettersCount == 0) {
            return message;
        }

        double percentage = (double) upperCount / lettersCount;
        if (percentage >= capsPercentage) {
            return message.toLowerCase(Locale.ROOT);
        }

        return message;
    }

    public void handleViolation(Player player, FilterRule rule, String type) {
        plugin.getLogger().info("[AutoMod] " + player.getName() + " triggered '" + rule.id() + "' in " + type);

        if (rule.action() == Action.CANCEL) {
            player.sendMessage(plugin.getLocaleManager().getMessage("automod.rule-denied",
                    Placeholder.unparsed("message", rule.denialMessage())));
        }

        String alert = LegacyComponentSerializer.legacySection().serialize(
                plugin.getLocaleManager().getMessage("automod.staff-alert",
                        Placeholder.unparsed("player", player.getName()),
                        Placeholder.unparsed("rule", rule.id()),
                        Placeholder.unparsed("type", type)));
        plugin.getProxyService().sendStaffAlertMessage(alert, STAFF_ALERT_PERMISSION);
    }

    public enum Action {
        CANCEL,
        SHADOW,
        LOG_ONLY
    }

    public record FilterRule(String id, Pattern pattern, String denialMessage, Action action) {
    }
}
