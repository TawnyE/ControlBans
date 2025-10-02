package ret.tawny.controlbans.util;

import java.security.SecureRandom;
import java.util.Random;

public final class IdUtil {

    private static final String ALPHANUMERIC = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int ID_LENGTH = 6;
    private static final Random RANDOM = new SecureRandom();

    /**
     * Generates a short, random, alphanumeric ID for a punishment.
     * @return A 6-character uppercase alphanumeric string.
     */
    public static String generatePunishmentId() {
        StringBuilder sb = new StringBuilder(ID_LENGTH);
        for (int i = 0; i < ID_LENGTH; i++) {
            sb.append(ALPHANUMERIC.charAt(RANDOM.nextInt(ALPHANUMERIC.length())));
        }
        return sb.toString();
    }
}