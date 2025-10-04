package ret.tawny.controlbans;

import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.commands.*;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.listeners.PlayerChatListener;
import ret.tawny.controlbans.listeners.PlayerJoinListener;
import ret.tawny.controlbans.services.*;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.util.SchedulerAdapter;

import java.util.logging.Level;

public class ControlBansPlugin extends JavaPlugin {

    private static ControlBansPlugin instance;

    private ConfigManager configManager;
    private DatabaseManager databaseManager;
    private PunishmentService punishmentService;
    private AltService altService;
    private CacheService cacheService;
    private WebService webService;
    private IntegrationService integrationService;
    private ImportService importService;
    private SchedulerAdapter schedulerAdapter;
    private ProxyService proxyService;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Enabling ControlBans v" + getDescription().getVersion());

        // Register plugin messaging channels for proxy communication
        getServer().getMessenger().registerOutgoingPluginChannel(this, "controlbans:main");

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
        registerCommands();
        registerListeners();
        initializeOptionalServices();
    }

    @Override
    public void onDisable() {
        getLogger().info("Disabling ControlBans...");
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
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

        schedulerAdapter = new SchedulerAdapter(this);
        proxyService = new ProxyService(this);

        databaseManager = new DatabaseManager(this, configManager);
        databaseManager.initialize();

        cacheService = new CacheService(configManager);
        punishmentService = new PunishmentService(this, databaseManager, cacheService);
        altService = new AltService(databaseManager, cacheService);

        getLogger().info("Core services initialized");
    }

    private void registerCommands() {
        new BanCommand(this, punishmentService).register();
        new TempBanCommand(this, punishmentService).register();
        new UnbanCommand(this, punishmentService).register();
        new MuteCommand(this, punishmentService).register();
        new TempMuteCommand(this, punishmentService).register();
        new UnmuteCommand(this, punishmentService).register();
        new WarnCommand(this, punishmentService).register();
        new KickCommand(this, punishmentService).register();
        new IpBanCommand(this, punishmentService).register();
        new IpMuteCommand(this, punishmentService).register();
        new HistoryCommand(this, punishmentService).register();
        new CheckCommand(this, punishmentService).register();
        new AltsCommand(this, altService).register();
        new ControlBansCommand(this).register();
        getLogger().info("Commands registered");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this, punishmentService), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(punishmentService), this);
        getLogger().info("Listeners registered");
    }

    private void initializeOptionalServices() {
        if (configManager.isWebEnabled()) {
            try {
                webService = new WebService(this, punishmentService, altService, configManager);
                webService.start();
                getLogger().info("Web service started on " + configManager.getWebHost() + ":" + configManager.getWebPort());
            } catch (Exception e) {
                getLogger().log(Level.WARNING, "Failed to start web service", e);
            }
        }

        try {
            integrationService = new IntegrationService(this, configManager, punishmentService);
            integrationService.initialize();
            getLogger().info("Integration service initialized");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize integration service", e);
        }

        importService = new ImportService(this, databaseManager);
        getLogger().info("Import service initialized");
    }

    public void reload() {
        getLogger().info("Reloading ControlBans configuration...");
        try {
            configManager.loadConfig();
            if (webService != null) {
                if (configManager.isWebEnabled()) {
                    webService.restart();
                } else {
                    webService.shutdown();
                    webService = null;
                }
            } else if (configManager.isWebEnabled()) {
                webService = new WebService(this, punishmentService, altService, configManager);
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
    public DatabaseManager getDatabaseManager() { return databaseManager; }
    public PunishmentService getPunishmentService() { return punishmentService; }
    public AltService getAltService() { return altService; }
    public CacheService getCacheService() { return cacheService; }
    public WebService getWebService() { return webService; }
    public IntegrationService getIntegrationService() { return integrationService; }
    public ImportService getImportService() { return importService; }
    public SchedulerAdapter getSchedulerAdapter() { return schedulerAdapter; }
    public ProxyService getProxyService() { return proxyService; }
}