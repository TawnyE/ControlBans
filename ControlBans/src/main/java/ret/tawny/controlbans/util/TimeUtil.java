package ret.tawny.controlbans.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtil {

    private static final Pattern DURATION_PATTERN = Pattern.compile(
            "(?:(\\d+)\\s*y)?\\s*" +
                    "(?:(\\d+)\\s*mo)?\\s*" +
                    "(?:(\\d+)\\s*w)?\\s*" +
                    "(?:(\\d+)\\s*d)?\\s*" +
                    "(?:(\\d+)\\s*h)?\\s*" +
                    "(?:(\\d+)\\s*m)?\\s*" +
                    "(?:(\\d+)\\s*s)?",
            Pattern.CASE_INSENSITIVE
    );

    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.systemDefault());

    public static long parseDuration(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }

        String cleanInput = input.replaceAll("\\s+", "");
        Matcher matcher = DURATION_PATTERN.matcher(cleanInput);

        if (!matcher.matches() || input.matches("(?i).*[^a-z0-9\\s].*")) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }

        long totalSeconds = 0;
        boolean found = false;

        if (matcher.group(1) != null) { totalSeconds += Long.parseLong(matcher.group(1)) * 31536000; found = true; }
        if (matcher.group(2) != null) { totalSeconds += Long.parseLong(matcher.group(2)) * 2592000; found = true; }
        if (matcher.group(3) != null) { totalSeconds += Long.parseLong(matcher.group(3)) * 604800; found = true; }
        if (matcher.group(4) != null) { totalSeconds += Long.parseLong(matcher.group(4)) * 86400; found = true; }
        if (matcher.group(5) != null) { totalSeconds += Long.parseLong(matcher.group(5)) * 3600; found = true; }
        if (matcher.group(6) != null) { totalSeconds += Long.parseLong(matcher.group(6)) * 60; found = true; }
        if (matcher.group(7) != null) { totalSeconds += Long.parseLong(matcher.group(7)); found = true; }

        if (!found) {
            try {
                totalSeconds = Long.parseLong(cleanInput);
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid duration format: " + input);
            }
        }

        if (totalSeconds == 0) throw new IllegalArgumentException("Duration must be greater than 0");
        return totalSeconds;
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "permanent";
        Duration duration = Duration.ofSeconds(seconds);
        StringBuilder sb = new StringBuilder();
        long days = duration.toDays();
        if (days > 0) { sb.append(days).append("d "); duration = duration.minusDays(days); }
        long hours = duration.toHours();
        if (hours > 0) { sb.append(hours).append("h "); duration = duration.minusHours(hours); }
        long minutes = duration.toMinutes();
        if (minutes > 0) { sb.append(minutes).append("m "); duration = duration.minusMinutes(minutes); }
        long secs = duration.getSeconds();
        if (secs > 0 || sb.length() == 0) sb.append(secs).append("s");
        return sb.toString().trim();
    }

    public static String formatDate(long timestamp) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }
}