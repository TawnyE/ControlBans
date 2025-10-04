package ret.tawny.controlbans.util;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeUtil {
    
    private static final Pattern DURATION_PATTERN = Pattern.compile(
        "(?:(\\d+)y)?(?:(\\d+)mo)?(?:(\\d+)w)?(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?"
    );
    
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter
        .ofPattern("yyyy-MM-dd HH:mm:ss")
        .withZone(ZoneId.systemDefault());
    
    public static long parseDuration(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("Duration cannot be empty");
        }
        
        Matcher matcher = DURATION_PATTERN.matcher(input.toLowerCase());
        if (!matcher.matches()) {
            throw new IllegalArgumentException("Invalid duration format: " + input);
        }
        
        long totalSeconds = 0;
        
        // Years
        String years = matcher.group(1);
        if (years != null) {
            totalSeconds += Long.parseLong(years) * 365 * 24 * 60 * 60;
        }
        
        // Months
        String months = matcher.group(2);
        if (months != null) {
            totalSeconds += Long.parseLong(months) * 30 * 24 * 60 * 60;
        }
        
        // Weeks
        String weeks = matcher.group(3);
        if (weeks != null) {
            totalSeconds += Long.parseLong(weeks) * 7 * 24 * 60 * 60;
        }
        
        // Days
        String days = matcher.group(4);
        if (days != null) {
            totalSeconds += Long.parseLong(days) * 24 * 60 * 60;
        }
        
        // Hours
        String hours = matcher.group(5);
        if (hours != null) {
            totalSeconds += Long.parseLong(hours) * 60 * 60;
        }
        
        // Minutes
        String minutes = matcher.group(6);
        if (minutes != null) {
            totalSeconds += Long.parseLong(minutes) * 60;
        }
        
        // Seconds
        String seconds = matcher.group(7);
        if (seconds != null) {
            totalSeconds += Long.parseLong(seconds);
        }
        
        if (totalSeconds == 0) {
            throw new IllegalArgumentException("Duration must be greater than 0");
        }
        
        return totalSeconds;
    }
    
    public static String formatDuration(long seconds) {
        if (seconds <= 0) {
            return "permanent";
        }
        
        Duration duration = Duration.ofSeconds(seconds);
        
        StringBuilder sb = new StringBuilder();
        
        long days = duration.toDays();
        if (days > 0) {
            sb.append(days).append("d ");
            duration = duration.minusDays(days);
        }
        
        long hours = duration.toHours();
        if (hours > 0) {
            sb.append(hours).append("h ");
            duration = duration.minusHours(hours);
        }
        
        long minutes = duration.toMinutes();
        if (minutes > 0) {
            sb.append(minutes).append("m ");
            duration = duration.minusMinutes(minutes);
        }
        
        long secs = duration.getSeconds();
        if (secs > 0 || sb.length() == 0) {
            sb.append(secs).append("s");
        }
        
        return sb.toString().trim();
    }
    
    public static String formatDate(long timestamp) {
        return DATE_FORMATTER.format(Instant.ofEpochMilli(timestamp));
    }
    
    public static String formatTimeAgo(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        
        if (seconds < 60) {
            return seconds + " second" + (seconds != 1 ? "s" : "") + " ago";
        }
        
        long minutes = seconds / 60;
        if (minutes < 60) {
            return minutes + " minute" + (minutes != 1 ? "s" : "") + " ago";
        }
        
        long hours = minutes / 60;
        if (hours < 24) {
            return hours + " hour" + (hours != 1 ? "s" : "") + " ago";
        }
        
        long days = hours / 24;
        if (days < 30) {
            return days + " day" + (days != 1 ? "s" : "") + " ago";
        }
        
        long months = days / 30;
        if (months < 12) {
            return months + " month" + (months != 1 ? "s" : "") + " ago";
        }
        
        long years = months / 12;
        return years + " year" + (years != 1 ? "s" : "") + " ago";
    }
}