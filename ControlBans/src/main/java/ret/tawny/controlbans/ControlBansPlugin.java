package ret.tawny.controlbans;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.commands.*;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;
import ret.tawny.controlbans.commands.gui.SettingsGuiManager;
import ret.tawny.controlbans.commands.gui.ReportGuiManager;
import ret.tawny.controlbans.commands.gui.MyReportsGuiManager;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.config.ConfigUpdater;
import ret.tawny.controlbans.listeners.*;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.*;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.MongoManager;
import ret.tawny.controlbans.storage.RedisManager;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.util.SchedulerAdapter;
import ret.tawny.controlbans.util.UpdateChecker;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Level;

public class ControlBansPlugin extends JavaPlugin {

    private static ControlBansPlugin instance;

    private ConfigManager configManager;
    private LocaleManager localeManager;
    private StorageInterface storage;
    private PunishmentService punishmentService;
    private AltService altService;
    private IntegrationService integrationService;
    private ImportService importService;
    private SchedulerAdapter schedulerAdapter;
    private ProxyService proxyService;
    private HistoryGuiManager historyGuiManager;
    private AltsGuiManager altsGuiManager;
    private SettingsGuiManager settingsGuiManager;
    private PunishGuiManager punishGuiManager;
    private ReportGuiManager reportGuiManager;
    private MyReportsGuiManager myReportsGuiManager;
    private AppealService appealService;
    private CacheService cacheService;
    private VoidJailService voidJailService;
    private SkinBanService skinBanService;
    private VoiceChatService voiceChatService;
    private ChatInputListener chatInputListener;
    private RedisManager redisManager;
    private FreezeManager freezeManager;
    private ChatManager chatManager;
    private DataExportService dataExportService;
    private AutoModService autoModService;
    private ReportService reportService;
    private NoteService noteService;
    private UpdateChecker updateChecker;

    private PlayerChatListener playerChatListener;

    @Override
    public void onEnable() {
        instance = this;

        saveDefaultConfig();

        ConfigUpdater.update(this);

        try {
            initializePlugin();
            getLogger().info("ControlBans has been enabled successfully!");

            this.updateChecker = new UpdateChecker(this);
            schedulerAdapter.runTaskLaterAsync(updateChecker::check, 30L * 20L);
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

            if (integrationService != null)
                integrationService.shutdown();
            if (redisManager != null)
                redisManager.shutdown();

            if (storage != null)
                storage.shutdown();

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

        initializeStorage();

        if (configManager.isRedisEnabled()) {
            redisManager = new RedisManager(this, configManager);
            redisManager.initialize();
        }

        cacheService = new CacheService(configManager);

        punishmentService = new PunishmentService(this, storage, cacheService);
        altService = new AltService(this, storage, cacheService);
        appealService = new AppealService(storage, configManager);
        autoModService = new AutoModService(this);
        reportService = new ReportService(this);
        noteService = new NoteService(this);

        voidJailService = new VoidJailService(this);
        skinBanService = new SkinBanService(this);

        historyGuiManager = new HistoryGuiManager(this);
        altsGuiManager = new AltsGuiManager(this);
        punishGuiManager = new PunishGuiManager(this);
        reportGuiManager = new ReportGuiManager(this);
        myReportsGuiManager = new MyReportsGuiManager(this);

        chatInputListener = new ChatInputListener(this);
        settingsGuiManager = new SettingsGuiManager(this, chatInputListener);

        freezeManager = new FreezeManager(this);
        chatManager = new ChatManager(this);
    }

    private void initializeStorage() {
        String dbType = configManager.getDatabaseType();
        if (dbType.equalsIgnoreCase("mongodb")) {
            storage = new MongoManager(this, configManager);
        } else {
            storage = new DatabaseManager(this, configManager);
        }
        storage.initialize();
        getLogger().info("Storage initialized using " + dbType + ".");
    }

    private void initializeMetrics() {
        int pluginId = 27613;
        Metrics metrics = new Metrics(this, pluginId);

        metrics.addCustomChart(new SimplePie("database_type", () -> configManager.getDatabaseType()));
        metrics.addCustomChart(new SimplePie("language", () -> configManager.getLanguage()));
        metrics.addCustomChart(new DrilldownPie("enabled_integrations", () -> {
            Map<String, Map<String, Integer>> map = new HashMap<>();
            Map<String, Integer> integrations = new HashMap<>();
            if (configManager.isDiscordEnabled())
                integrations.put("DiscordSRV", 1);
            if (configManager.isMCBlacklistEnabled())
                integrations.put("MCBlacklist", 1);
            if (integrations.isEmpty())
                integrations.put("None", 1);
            map.put("Integrations", integrations);
            return map;
        }));
        metrics.addCustomChart(new SimplePie("alt_punishment_enabled",
                () -> configManager.isAltPunishEnabled() ? "Enabled" : "Disabled"));
        getLogger().info("bStats metrics initialized.");
    }

    private void registerCommands() {
        new BanCommand(this).register();
        new TempBanCommand(this).register();
        new UnbanCommand(this).register();
        new MuteCommand(this).register();
        new TempMuteCommand(this).register();
        new UnmuteCommand(this).register();
        new ShadowMuteCommand(this).register();
        new WarnCommand(this).register();
        new KickCommand(this).register();
        new IpBanCommand(this).register();
        new IpMuteCommand(this).register();
        new UnIpBanCommand(this).register();
        new UnIpMuteCommand(this).register();
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
        new TempVoiceMuteCommand(this).register();
        new VoiceUnmuteCommand(this).register();
        new FreezeCommand(this).register();
        new ChatCommand(this).register();
        new PunishCommand(this, punishGuiManager).register();
        new ReportCommand(this).register();
        new ReportsCommand(this).register();
        new BanlistCommand(this).register();
        new MutelistCommand(this).register();
        new BlameCommand(this).register();
        new NoteCommand(this).register();
        new StaffCommand(this).register();
        getLogger().info("Commands registered");
    }

    private void registerListeners() {
        playerChatListener = new PlayerChatListener(this);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        new ProxyMessengerListener(this);
        getServer().getPluginManager().registerEvents(playerChatListener, this);
        getServer().getPluginManager().registerEvents(new AutoModListener(this), this);
        getServer().getPluginManager()
                .registerEvents(new GuiListener(historyGuiManager, altsGuiManager, settingsGuiManager, punishGuiManager, reportGuiManager, myReportsGuiManager, this), this);
        getServer().getPluginManager().registerEvents(new VoidJailListener(this), this);
        getServer().getPluginManager().registerEvents(new FreezeListener(this, freezeManager), this);
        getServer().getPluginManager().registerEvents(new SkinBanListener(this), this);
        getServer().getPluginManager().registerEvents(chatInputListener, this);
        getServer().getPluginManager().registerEvents(chatManager, this);
        getLogger().info("Listeners registered");
    }

    private void initializeOptionalServices() {
        try {
            integrationService = new IntegrationService(this, configManager);
            integrationService.initialize();
            getLogger().info("Integration service initialized");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to initialize integration service", e);
        }

        importService = new ImportService(this, storage, localeManager);
        dataExportService = new DataExportService(this, punishmentService);
        getLogger().info("Import/Export service initialized");

        if (configManager.isVoiceChatIntegrationEnabled()) {
            if (getServer().getPluginManager().isPluginEnabled("voicechat")) {
                try {
                    Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin");
                    voiceChatService = new VoiceChatService(this);
                    voiceChatService.initialize();
                    getLogger().info("Voice Chat integration initialized.");
                } catch (ClassNotFoundException e) {
                    getLogger().warning("Voice Chat plugin found but API classes missing. Integration disabled.");
                } catch (Throwable t) {
                    getLogger().log(Level.WARNING, "Failed to initialize Voice Chat integration", t);
                }
            } else {
                getLogger().warning("Voice Chat integration is enabled in config, but 'voicechat' plugin is not found!");
            }
        }
    }

    public void reload() {
        getLogger().info("Reloading ControlBans configuration...");
        try {
            ConfigUpdater.update(this);
            configManager.loadConfig();
            localeManager.reload();

            if (voidJailService != null) {
                voidJailService.loadJailLocation();
            }
            if (autoModService != null) {
                autoModService.loadRules();
            }
            if (cacheService != null) {
                cacheService.invalidateAll();
            }

            if (integrationService == null) {
                try {
                    integrationService = new IntegrationService(this, configManager);
                    integrationService.initialize();
                    getLogger().info("Integration service initialized during reload");
                } catch (Exception exception) {
                    getLogger().log(Level.WARNING, "Failed to initialize integration service during reload", exception);
                }
            } else {
                integrationService.reload();
            }

            if (configManager.isVoiceChatIntegrationEnabled()) {
                if (voiceChatService == null) {
                    if (getServer().getPluginManager().isPluginEnabled("voicechat")) {
                        try {
                            Class.forName("de.maxhenkel.voicechat.api.VoicechatPlugin");
                            voiceChatService = new VoiceChatService(this);
                            voiceChatService.initialize();
                            getLogger().info("Voice Chat integration initialized during reload.");
                        } catch (ClassNotFoundException e) {
                            getLogger().warning("Voice Chat API classes missing.");
                        } catch (Throwable throwable) {
                            getLogger().log(Level.WARNING, "Failed to initialize Voice Chat integration during reload", throwable);
                        }
                    } else {
                        getLogger().warning("Voice Chat integration is enabled in config, but 'voicechat' plugin is not found!");
                    }
                }
            } else if (voiceChatService != null) {
                getLogger().warning("Voice Chat integration was disabled in config. Restart the server to fully unload it.");
            }

            getLogger().info("ControlBans configuration reloaded successfully. Database backend changes still require a full server restart.");
        } catch (Exception e) {
            getLogger().log(Level.WARNING, "Failed to reload configuration", e);
        }
    }

    public static ControlBansPlugin getInstance() {
        return instance;
    }

    public ConfigManager getConfigManager() {
        return configManager;
    }

    public LocaleManager getLocaleManager() {
        return localeManager;
    }

    public StorageInterface getStorage() {
        return storage;
    }

    public DatabaseManager getDatabaseManager() {
        return (storage instanceof DatabaseManager) ? (DatabaseManager) storage : null;
    }

    public PunishmentService getPunishmentService() {
        return punishmentService;
    }

    public NotificationService getNotificationService() {
        return punishmentService.getNotificationService();
    }

    public AltService getAltService() {
        return altService;
    }

    public IntegrationService getIntegrationService() {
        return integrationService;
    }

    public ImportService getImportService() {
        return importService;
    }

    public SchedulerAdapter getSchedulerAdapter() {
        return schedulerAdapter;
    }

    public ProxyService getProxyService() {
        return proxyService;
    }

    public AppealService getAppealService() {
        return appealService;
    }

    public CacheService getCacheService() {
        return cacheService;
    }

    public VoidJailService getVoidJailService() {
        return voidJailService;
    }

    public SkinBanService getSkinBanService() {
        return skinBanService;
    }

    public VoiceChatService getVoiceChatService() {
        return voiceChatService;
    }

    public SettingsGuiManager getSettingsGuiManager() {
        return settingsGuiManager;
    }

    public PunishGuiManager getPunishGuiManager() {
        return punishGuiManager;
    }

    public ReportGuiManager getReportGuiManager() {
        return reportGuiManager;
    }

    public MyReportsGuiManager getMyReportsGuiManager() {
        return myReportsGuiManager;
    }

    public RedisManager getRedisManager() {
        return redisManager;
    }

    public ChatInputListener getChatInputListener() {
        return chatInputListener;
    }

    public FreezeManager getFreezeManager() {
        return freezeManager;
    }

    public ChatManager getChatManager() {
        return chatManager;
    }

    public DataExportService getDataExportService() {
        return dataExportService;
    }

    public PlayerChatListener getPlayerChatListener() {
        return playerChatListener;
    }

    public AutoModService getAutoModService() {
        return autoModService;
    }

    public ReportService getReportService() {
        return reportService;
    }

    public NoteService getNoteService() {
        return noteService;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }
}