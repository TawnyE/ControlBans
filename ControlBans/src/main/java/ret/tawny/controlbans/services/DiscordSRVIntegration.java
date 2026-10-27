package ret.tawny.controlbans.services;

import github.scarsz.discordsrv.DiscordSRV;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.listeners.DiscordSRVHookListener;

import java.util.List;

public class DiscordSRVIntegration {

    private static final List<Object> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void register(ControlBansPlugin plugin) {
        DiscordSRVHookListener listener = new DiscordSRVHookListener(plugin);
        listeners.add(listener);
        DiscordSRV.api.subscribe(listener);
    }

    public static void unregister(ControlBansPlugin plugin) {
        for (Object listener : List.copyOf(listeners)) {
            try {
                DiscordSRV.api.unsubscribe(listener);
            } catch (Exception ignored) {}
            listeners.remove(listener);
        }
    }

    /**
     * Sends an embed to a Discord channel via the DiscordSRV API (direct calls —
     * this class is only loaded when DiscordSRV is installed).
     *
     * @param channelId channel id or game-channel name configured in DiscordSRV
     * @param fields    [name, value, inline] triples
     */
    public static void sendChannelEmbed(String channelId, String title, String description,
                                        String colorHex, String footer, List<String[]> fields) {
        github.scarsz.discordsrv.dependencies.jda.api.entities.TextChannel channel =
                DiscordSRV.getPlugin().getDestinationTextChannelForGameChannelName(channelId);
        if (channel == null && DiscordSRV.getPlugin().getJda() != null) {
            channel = DiscordSRV.getPlugin().getJda().getTextChannelById(channelId);
        }
        if (channel == null) return;

        github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder builder =
                new github.scarsz.discordsrv.dependencies.jda.api.EmbedBuilder();
        if (title != null && !title.isEmpty()) builder.setTitle(title);
        if (description != null && !description.isEmpty()) builder.setDescription(description);
        if (colorHex != null && !colorHex.isEmpty()) {
            try {
                builder.setColor(new java.awt.Color(Integer.parseInt(colorHex.replace("#", ""), 16)));
            } catch (Exception ignored) {}
        }
        if (footer != null && !footer.isEmpty()) builder.setFooter(footer, null);
        if (fields != null) {
            for (String[] f : fields) {
                builder.addField(f[0], f[1], Boolean.parseBoolean(f[2]));
            }
        }
        channel.sendMessageEmbeds(builder.build()).queue();
    }
}
