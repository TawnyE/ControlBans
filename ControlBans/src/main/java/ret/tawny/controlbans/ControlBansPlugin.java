package ret.tawny.controlbans;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.commands.*;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.config.ConfigUpdater;
import ret.tawny.controlbans.listeners.*;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.*;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class ControlBansPlugin extends JavaPlugin {

    private static ControlBansPlugin instance;

    private ConfigManager configManager;
    private LocaleManager localeManager;
    private DatabaseManager databaseManager;
    private PunishmentService punishmentService;
    private AltService altService;
    private WebService webService;
    private IntegrationService integrationService;
    private ImportService importService;
    private SchedulerAdapter schedulerAdapter;
    private ProxyService proxyService;
    private HistoryGuiManager historyGuiManager;
    private AltsGuiManager altsGuiManager;
    private AppealService appealService;
    private CacheService cacheService;
    private VoidJailService voidJailService;
    private SkinBanService skinBanService;
    private VoiceChatService voiceChatService;


    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Enabling ControlBans v" + getPluginMeta().getVersion());

        // Save the default config if it doesn't exist
        saveDefaultConfig();

        // Update the configuration with new values
        try {
            ConfigUpdater.update(this);
        } catch (IOException e) {
            getLogger().log(Level.SEVERE, "Failed to update config.yml!", e);
        }

        try {
            initializePlugin();
            getLogger().info("ControlBans has been enabled successfully!");
        } catch (Exception e) {
            getLogger().log(Level.SEVERE, "A critical error occurred during plugin initialization.", e);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void initializePlugin() {
        initializeCore();
        initializeMetrics();
        registerCommands();
        registerListeners();
        initializeOptionalServices();
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling ControlBans...");
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
            getServer().getMessenger().unregisterIncomingPluginChannel(this);
            if (webService != null) webService.shutdown();
            if (integrationService != null) integrationService.shutdown();
            if (databaseManager != null) databaseManager.shutdown();
            getLogger().info("ControlBans has been disabled.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Error during plugin shutdown", e);
        }
        instance = null;
    }

    private void initializeCore() {
        configManager = new ConfigManager(this);
        configManager.loadConfig();

        localeManager = new LocaleManager(this);

        schedulerAdapter = new SchedulerAdapter(this);
        proxyService = new ProxyService(this, schedulerAdapter);

        databaseManager = new DatabaseManager(this, configManager);
        databaseManager.initialize();

        cacheService = new CacheService(configManager);
        punishmentService = new PunishmentService(this, databaseManager, cacheService);
        altService = new AltService(this, databaseManager, cacheService);
        appealService = new AppealService(databaseManager, configManager);
        voidJailService = new VoidJailService(this);
        skinBanService = new SkinBanService(this);
        voiceChatService = new VoiceChatService(this);


        historyGuiManager = new HistoryGuiManager(this);
        altsGuiManager = new AltsGuiManager(this);

        getLogger().info("Core services initialized");
    }

    private void initializeMetrics() {
        int pluginId = 27613;
        Metrics metrics = new Metrics(this, pluginId);

        metrics.addCustomChart(new SimplePie("database_type", () -> configManager.getDatabaseType()));
        metrics.addCustomChart(new SimplePie("language", () -> configManager.getLanguage()));
        metrics.addCustomChart(new DrilldownPie("enabled_integrations", () -> {
            Map<String, Map<String, Integer>> map = new HashMap<>();
            Map<String, Integer> integrations = new HashMap<>();
            if (configManager.isDiscordEnabled()) integrations.put("DiscordSRV", 1);
            if (configManager.isMCBlacklistEnabled()) integrations.put("MCBlacklist", 1);
            if (integrations.isEmpty()) integrations.put("None", 1);
            map.put("Integrations", integrations);
            return map;
        }));
        metrics.addCustomChart(new SimplePie("web_ui_enabled", () -> configManager.isWebEnabled() ? "Enabled" : "Disabled"));
        metrics.addCustomChart(new SimplePie("alt_punishment_enabled", () -> configManager.isAltPunishEnabled() ? "Enabled" : "Disabled"));
        getLogger().info("bStats metrics initialized.");
    }

    private void registerCommands() {
        new BanCommand(this).register();
        new TempBanCommand(this).register();
        new UnbanCommand(this).register();
        new MuteCommand(this).register();
        new TempMuteCommand(this).register();
        new UnmuteCommand(this).register();
        new WarnCommand(this).register();
        new KickCommand(this).register();
        new IpBanCommand(this).register();
        new IpMuteCommand(this).register();
        new HistoryCommand(this, historyGuiManager).register();
        new CheckCommand(this).register();
        new AltsCommand(this, altsGuiManager).register();
        new AppealCommand(this).register();
        new ControlBansCommand(this).register();
        new VoidJailCommand(this).register();
        new UnvoidJailCommand(this).register();
        new BanSkinCommand(this).register();
        new UnbanSkinCommand(this).register();
        new VoiceMuteCommand(this).register();
        new VoiceUnmuteCommand(this).register();
        getLogger().info("Commands registered");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        new ProxyMessengerListener(this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(historyGuiManager, altsGuiManager), this);
        getServer().getPluginManager().registerEvents(new VoidJailListener(this), this);
        getServer().getPluginManager().registerEvents(new SkinBanListener(this), this);
        getLogger().info("Listeners registered");
    }

    private void initializeOptionalServices() {
        if (configManager.isWebEnabled()) {
            try {
                webService = new WebService(this, punishmentService, configManager);
                webService.start();
                getLogger().info("Web service started on " + configManager.getWebHost() + ":" + configManager.getWebPort());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to start web service", e);
            }
        }

        try {
            integrationService = new IntegrationService(this, configManager);
            integrationService.initialize();
            getLogger().info("Integration service initialized");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize integration service", e);
        }

        importService = new ImportService(this, databaseManager);
        getLogger().info("Import service initialized");

        if (configManager.isVoiceChatIntegrationEnabled()) {
            voiceChatService.initialize();
        }
    }

    public void reload() {
        getLogger().info("Reloading ControlBans configuration...");
        try {
            // Update the config file before reloading it
            ConfigUpdater.update(this);
            configManager.loadConfig();
            localeManager.reload();
            if (voidJailService != null) {
                voidJailService.loadJailLocation();
            }

            if (webService != null) {
                if (configManager.isWebEnabled()) {
                    webService.restart();
                } else {
                    webService.shutdown();
                    webService = null;
                }
            } else if (configManager.isWebEnabled()) {
                webService = new WebService(this, punishmentService, configManager);
                try {
                    webService.start();
                } catch (Exception e) {
                    getLogger().log(Level.WARNING, "Failed to restart web service", e);
                }
            }
            if (integrationService != null) {
                integrationService.reload();
            }
            getLogger().info("ControlBans configuration reloaded successfully");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to reload configuration", e);
        }
    }

    public static ControlBansPlugin getInstance() { return instance; }
    public ConfigManager getConfigManager() { return configManager; }
    public LocaleManager getLocaleManager() { return localeManager; }
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PunishmentService getPunishmentService() { return punishmentService; }
    public AltService getAltService() { return altService; }
    public IntegrationService getIntegrationService() { return integrationService; }
    public ImportService getImportService() { return importService; }
    public SchedulerAdapter getSchedulerAdapter() { return schedulerAdapter; }
    public ProxyService getProxyService() { return proxyService; }
    public AppealService getAppealService() { return appealService; }
    public CacheService getCacheService() { return cacheService; }
    public VoidJailService getVoidJailService() { return voidJailService; }
    public SkinBanService getSkinBanService() { return skinBanService; }
}