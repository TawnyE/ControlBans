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

    private static final Pattern INVALID_CHARS = Pattern.compile("[^a-z0-9\\s]", Pattern.CASE_INSENSITIVE);

    // Configurable formatter — defaults to UTC and config.yml default pattern, updated by ConfigManager
    private static volatile ZoneId configuredZone = ZoneId.of("UTC");
    private static volatile String configuredPattern = "yyyy-MM-dd HH:mm:ss";
    private static volatile DateTimeFormatter configuredFormatter = DateTimeFormatter
            .ofPattern(configuredPattern)
            .withZone(configuredZone);

    private static final DateTimeFormatter FALLBACK_FORMATTER = DateTimeFormatter
            .ofPattern("yyyy-MM-dd HH:mm:ss")
            .withZone(ZoneId.of("UTC"));

    /**
     * Called by ConfigManager after loading config.yml to keep timestamps consistent across the network.
     */
    public static void configure(String datePattern, String timezoneId) {
        try {
            ZoneId zone = (timezoneId == null || timezoneId.isBlank()) ? ZoneId.of("UTC") : ZoneId.of(timezoneId.trim());
            String pattern = (datePattern == null || datePattern.isBlank()) ? "yyyy-MM-dd HH:mm:ss" : datePattern.trim();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pattern).withZone(zone);
            configuredZone = zone;
            configuredPattern = pattern;
            configuredFormatter = fmt;
        } catch (Exception ignored) {
            configuredZone = ZoneId.of("UTC");
            configuredPattern = "yyyy-MM-dd HH:mm:ss";
            configuredFormatter = FALLBACK_FORMATTER;
        }
    }

    public static long parseDuration(String input) {
        if (input == null || input.trim().isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }

        String trimmed = input.trim();

        // Fast reject: contains character that can never be part of a duration token
        if (INVALID_CHARS.matcher(trimmed).find()) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }

        String cleanInput = trimmed.replaceAll("\\s+", "");
        if (cleanInput.isEmpty()) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }

        // Bare number like "3600" — treat as seconds (must precede regex which requires a suffix).
        if (cleanInput.matches("\\d+")) {
            try {
                long bare = Long.parseLong(cleanInput);
                if (bare <= 0) throw new IllegalArgumentException("Duration must be greater than 0");
                return bare;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Duration overflow: " + input);
            }
        }

        Matcher matcher = DURATION_PATTERN.matcher(cleanInput);

        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }

        long totalSeconds = 0;
        boolean found = false;

        try {
            if (matcher.group(1) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(1)), 31536000L)); found = true; }
            if (matcher.group(2) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(2)), 2592000L)); found = true; }
            if (matcher.group(3) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(3)), 604800L)); found = true; }
            if (matcher.group(4) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(4)), 86400L)); found = true; }
            if (matcher.group(5) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(5)), 3600L)); found = true; }
            if (matcher.group(6) != null) { totalSeconds = Math.addExact(totalSeconds, Math.multiplyExact(Long.parseLong(matcher.group(6)), 60L)); found = true; }
            if (matcher.group(7) != null) { totalSeconds = Math.addExact(totalSeconds, Long.parseLong(matcher.group(7))); found = true; }
        } catch (ArithmeticException | NumberFormatException e) {
            throw new IllegalArgumentException("Duration overflow: " + input);
        }

        if (!found) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }

        if (totalSeconds <= 0) throw new IllegalArgumentException("Duration must be greater than 0");
        return totalSeconds;
    }

    public static String formatDuration(long seconds) {
        if (seconds <= 0) return "permanent";
        // Preserve exact display for large durations: keep days as primary unit for readability,
        // but avoid Duration overflow for extreme values ( > 1e9 days still safe with Duration)
        try {
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
        } catch (Exception e) {
            // Fallback for overflow edge: manual calculation
            long days = seconds / 86400;
            long rem = seconds % 86400;
            long hours = rem / 3600; rem %= 3600;
            long mins = rem / 60; long secs = rem % 60;
            StringBuilder sb = new StringBuilder();
            if (days > 0) sb.append(days).append("d ");
            if (hours > 0) sb.append(hours).append("h ");
            if (mins > 0) sb.append(mins).append("m ");
            if (secs > 0 || sb.length() == 0) sb.append(secs).append("s");
            return sb.toString().trim();
        }
    }

    public static String formatDate(long timestamp) {
        try {
            return configuredFormatter.format(Instant.ofEpochMilli(timestamp));
        } catch (Exception e) {
            return FALLBACK_FORMATTER.format(Instant.ofEpochMilli(timestamp));
        }
    }

    /**
     * Explicit formatting with custom zone/pattern — used by exports where config may be overridden.
     */
    public static String formatDate(long timestamp, String pattern, String timezoneId) {
        try {
            ZoneId zone = (timezoneId == null || timezoneId.isBlank()) ? configuredZone : ZoneId.of(timezoneId.trim());
            String pat = (pattern == null || pattern.isBlank()) ? configuredPattern : pattern.trim();
            DateTimeFormatter fmt = DateTimeFormatter.ofPattern(pat).withZone(zone);
            return fmt.format(Instant.ofEpochMilli(timestamp));
        } catch (Exception e) {
            return FALLBACK_FORMATTER.format(Instant.ofEpochMilli(timestamp));
        }
    }
}