package ret.tawny.controlbans.util;

public final class IpUtil {

    private IpUtil() {}

    public static String maskIp(String ip) {
        if (ip == null || ip.isBlank()) return "?.?.?.?";
        if (ip.contains(":")) {
            int colon = ip.indexOf(':');
            return colon > 0 ? ip.substring(0, colon) + ":****" : "?:****";
        }
        String[] parts = ip.split("\\.");
        if (parts.length == 4) {
            return parts[0] + "." + parts[1] + ".*.*";
        }
        return "?.?.?.?";
    }
}
