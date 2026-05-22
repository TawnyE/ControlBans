package ret.tawny.controlbans.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

public class ChatUtil {

    public static Component colorize(String message) {
        if (message == null) {
            return Component.empty();
        }
        return LegacyComponentSerializer.legacyAmpersand().deserialize(message);
    }
}