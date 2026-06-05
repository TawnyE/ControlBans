package ret.tawny.controlbans.services;

import github.scarsz.discordsrv.DiscordSRV;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.listeners.DiscordSRVHookListener;

public class DiscordSRVIntegration {
    
    public static void register(ControlBansPlugin plugin) {
        DiscordSRV.api.subscribe(new DiscordSRVHookListener(plugin));
    }
}
