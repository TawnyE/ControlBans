package ret.tawny.controlbans.api;

/**
 * Static entry point to the ControlBans public API.
 * <p>
 * Obtain the provider via {@link #get()} once the plugin has enabled, e.g. lazily
 * inside your own onEnable (ControlBans is not a loadbefore/depend target unless you
 * declare it, so guarding with {@code Bukkit.getPluginManager().isPluginEnabled("ControlBans")}
 * is recommended).
 */
public final class ControlBansAPI {

    private static volatile ControlBans provider;

    private ControlBansAPI() {
    }

    /**
     * Returns the active ControlBans API provider.
     *
     * @return the API provider
     * @throws IllegalStateException if ControlBans has not finished enabling yet
     */
    public static ControlBans get() {
        ControlBans current = provider;
        if (current == null) {
            throw new IllegalStateException("ControlBans API is not available yet. Call this after ControlBans has enabled.");
        }
        return current;
    }

    /** Internal: called by the plugin during startup. Not part of the public surface. */
    public static void setProvider(ControlBans newProvider) {
        provider = newProvider;
    }

    /** Internal: clears the provider on shutdown. Not part of the public surface. */
    public static void clearProvider() {
        provider = null;
    }
}
