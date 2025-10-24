package ret.tawny.controlbans;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.commands.*;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.listeners.GuiListener;
import ret.tawny.controlbans.listeners.PlayerChatListener;
import ret.tawny.controlbans.listeners.PlayerJoinListener;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.*;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.util.SchedulerAdapter;

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
    private BenchmarkService benchmarkService;
    private AnalyticsService analyticsService;
    private ScheduledPunishmentService scheduledPunishmentService;
    private EscalationService escalationService;
    private AppealService appealService;
    private AuditService auditService;
    private HealthService healthService;

    @Override
    public void onEnable() {
        instance = this;
        getLogger().info("Enabling ControlBans v" + getPluginMeta().getVersion());

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
            if (webService != null) webService.shutdown();
            if (integrationService != null) integrationService.shutdown();
            if (scheduledPunishmentService != null) scheduledPunishmentService.stop();
            if (escalationService != null) escalationService.stop();
            if (benchmarkService != null) benchmarkService.stop();
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
        proxyService = new ProxyService(this);
        benchmarkService = new BenchmarkService(this);
        benchmarkService.bindProxyService(proxyService);

        databaseManager = new DatabaseManager(this, configManager);
        databaseManager.initialize();
        databaseManager.setMetricsCollector(benchmarkService);

        CacheService cacheService = new CacheService(configManager);
        punishmentService = new PunishmentService(this, databaseManager, cacheService);
        altService = new AltService(this, databaseManager, cacheService);

        analyticsService = new AnalyticsService(databaseManager, configManager);
        auditService = new AuditService(databaseManager);
        punishmentService.setAuditService(auditService);

        scheduledPunishmentService = new ScheduledPunishmentService(this, databaseManager, punishmentService, benchmarkService);
        escalationService = new EscalationService(this, databaseManager, scheduledPunishmentService);
        punishmentService.setEscalationService(escalationService);

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
        getLogger().info("Commands registered");
    }

    private void registerListeners() {
        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        getServer().getPluginManager().registerEvents(new PlayerChatListener(this), this);
        getServer().getPluginManager().registerEvents(new GuiListener(historyGuiManager, altsGuiManager), this);
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
            integrationService = new IntegrationService(this, configManager, benchmarkService);
            integrationService.initialize();
            getLogger().info("Integration service initialized");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize integration service", e);
        }

        appealService = new AppealService(databaseManager, configManager, integrationService);
        healthService = new HealthService(this, databaseManager, integrationService, proxyService, scheduledPunishmentService, benchmarkService);

        importService = new ImportService(this, databaseManager);
        getLogger().info("Import service initialized");

        scheduledPunishmentService.start();
        escalationService.start();
        benchmarkService.start();
    }

    public void reload() {
        getLogger().info("Reloading ControlBans configuration...");
        try {
            configManager.loadConfig();
            localeManager.reload();

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
    public BenchmarkService getBenchmarkService() { return benchmarkService; }
    public AnalyticsService getAnalyticsService() { return analyticsService; }
    public ScheduledPunishmentService getScheduledPunishmentService() { return scheduledPunishmentService; }
    public EscalationService getEscalationService() { return escalationService; }
    public AppealService getAppealService() { return appealService; }
    public AuditService getAuditService() { return auditService; }
    public HealthService getHealthService() { return healthService; }
}