package ret.tawny.controlbans;

import org.bstats.bukkit.Metrics;
import org.bstats.charts.DrilldownPie;
import org.bstats.charts.SimplePie;
import org.bukkit.plugin.java.JavaPlugin;
import ret.tawny.controlbans.commands.*;
import ret.tawny.controlbans.api.ControlBansAPI;
import ret.tawny.controlbans.commands.gui.AltsGuiManager;
import ret.tawny.controlbans.commands.gui.AppealsGuiManager;
import ret.tawny.controlbans.commands.gui.HistoryGuiManager;
import ret.tawny.controlbans.commands.gui.MainMenuGuiManager;
import ret.tawny.controlbans.commands.gui.MyReportsGuiManager;
import ret.tawny.controlbans.commands.gui.OnlinePlayersGuiManager;
import ret.tawny.controlbans.commands.gui.PunishGuiManager;
import ret.tawny.controlbans.commands.gui.ReportGuiManager;
import ret.tawny.controlbans.commands.gui.SettingsGuiManager;
import ret.tawny.controlbans.commands.gui.StaffHistoryGuiManager;
import ret.tawny.controlbans.commands.gui.StaffReportsGuiManager;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.config.ConfigUpdater;
import ret.tawny.controlbans.listeners.*;
import ret.tawny.controlbans.config.LocaleValidator;
import ret.tawny.controlbans.locale.LocaleManager;
import ret.tawny.controlbans.services.*;
import ret.tawny.controlbans.storage.DatabaseManager;
import ret.tawny.controlbans.storage.StorageInterface;
import ret.tawny.controlbans.ui.PunishUiManager;
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
    private MainMenuGuiManager mainMenuGuiManager;
    private OnlinePlayersGuiManager onlinePlayersGuiManager;
    private StaffHistoryGuiManager staffHistoryGuiManager;
    private StaffReportsGuiManager staffReportsGuiManager;
    private AppealsGuiManager appealsGuiManager;
    private SkinBanService skinBanService;
    private AppealService appealService;
    private CacheService cacheService;
    private VoiceChatService voiceChatService;
    private EscalationService escalationService;
    private PunishUiManager punishUiManager;
    private ChatInputListener chatInputListener;
    private FreezeManager freezeManager;
    private ChatManager chatManager;
    private DataExportService dataExportService;
    private ReportService reportService;
    private NoteService noteService;
    private ScheduledService scheduledService;
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
            String msg = e.getMessage();
            if (msg != null && msg.contains("ControlBans could not connect to the database")) {
                getLogger().log(Level.SEVERE, "Plugin disabled due to database connection failure. Fix the issues shown above and restart the server.");
            } else {
                getLogger().log(Level.SEVERE, "A critical error occurred during plugin initialization.", e);
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private void initializePlugin() {
        initializeCore();
        LocaleValidator.validate(localeManager, configManager.getLanguage());
        initializeMetrics();
        registerCommands();
        registerListeners();
        initializeOptionalServices();

        if (scheduledService != null) {
            scheduledService.loadScheduledPunishments();
            scheduledService.startChecker();
        }
    }

    @Override
    public void onDisable() {
        ControlBansAPI.clearProvider();
        getLogger().info("Disabling ControlBans...");
        try {
            getServer().getMessenger().unregisterOutgoingPluginChannel(this);
            getServer().getMessenger().unregisterIncomingPluginChannel(this);

            if (voiceChatService != null) {
                try {
                    voiceChatService.shutdown();
                } catch (Throwable ignored) {}
                voiceChatService = null;
            }
            try {
                ret.tawny.controlbans.services.DiscordSRVIntegration.unregister(this);
            } catch (Throwable ignored) {}

            if (freezeManager != null) {
                try {
                    freezeManager.clearAll();
                } catch (Throwable ignored) {}
            }
            if (chatInputListener != null) {
                try {
                    chatInputListener.clearAll();
                } catch (Throwable ignored) {}
            }
            if (dataExportService != null) {
                try {
                    dataExportService.shutdown();
                } catch (Throwable ignored) {}
            }
            try {
                ret.tawny.controlbans.services.PlayerResolver.shutdown();
            } catch (Throwable ignored) {}

            if (integrationService != null)
                integrationService.shutdown();

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

        cacheService = new CacheService(configManager);

        punishmentService = new PunishmentService(this, storage, cacheService);
        escalationService = new EscalationService(this);
        altService = new AltService(this, storage, cacheService);
        appealService = new AppealService(storage, configManager);
        reportService = new ReportService(this);
        noteService = new NoteService(this);

        historyGuiManager = new HistoryGuiManager(this);
        altsGuiManager = new AltsGuiManager(this);
        punishGuiManager = new PunishGuiManager(this);
        punishUiManager = new PunishUiManager(this, punishGuiManager);
        reportGuiManager = new ReportGuiManager(this);
        myReportsGuiManager = new MyReportsGuiManager(this);
        mainMenuGuiManager = new MainMenuGuiManager(this);
        onlinePlayersGuiManager = new OnlinePlayersGuiManager(this);
        staffHistoryGuiManager = new StaffHistoryGuiManager(this);
        staffReportsGuiManager = new StaffReportsGuiManager(this);
        appealsGuiManager = new AppealsGuiManager(this);
        skinBanService = new SkinBanService(this);

        // Public API provider: immutable snapshots over the internal services.
        ControlBansAPI.setProvider(new ApiProvider(this));

        chatInputListener = new ChatInputListener(this);
        settingsGuiManager = new SettingsGuiManager(this, chatInputListener);

        freezeManager = new FreezeManager(this);
        chatManager = new ChatManager(this);
        scheduledService = new ScheduledService(this);
    }

    private void initializeStorage() {
        String dbType = configManager.getDatabaseType();
        storage = new DatabaseManager(this, configManager);
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
        new VoiceMuteCommand(this).register();
        new TempVoiceMuteCommand(this).register();
        new VoiceUnmuteCommand(this).register();
        new FreezeCommand(this).register();
        new ChatCommand(this).register();
        new PunishCommand(this).register();
        new ReportCommand(this).register();
        new ReportsCommand(this).register();
        new BanlistCommand(this).register();
        new MutelistCommand(this).register();
        new BlameCommand(this).register();
        new NoteCommand(this).register();
        new StaffCommand(this).register();
        new ScheduledPunishmentCommand(this).register();
        new BulkPunishmentCommand(this).register();
        new AppealsCommand(this).register();
        new BanSkinCommand(this).register();
        new UnbanSkinCommand(this).register();
        getLogger().info("Commands registered");
    }

    private void registerListeners() {
        playerChatListener = new PlayerChatListener(this);

        getServer().getPluginManager().registerEvents(new PlayerJoinListener(this), this);
        new ProxyMessengerListener(this);
        getServer().getPluginManager().registerEvents(playerChatListener, this);
        getServer().getPluginManager()
                .registerEvents(new GuiListener(historyGuiManager, altsGuiManager, settingsGuiManager, punishGuiManager, reportGuiManager, myReportsGuiManager, mainMenuGuiManager, onlinePlayersGuiManager, staffHistoryGuiManager, staffReportsGuiManager, appealsGuiManager, this), this);
        getServer().getPluginManager().registerEvents(new SkinBanListener(this, skinBanService), this);
        getServer().getPluginManager().registerEvents(new FreezeListener(this, freezeManager), this);
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

        if (getServer().getPluginManager().isPluginEnabled("DiscordSRV")) {
            try {
                ret.tawny.controlbans.services.DiscordSRVIntegration.register(this);
                getLogger().info("DiscordSRV hook registered for preventing muted players from sending messages.");
            } catch (Throwable t) {
                getLogger().log(Level.WARNING, "Failed to register DiscordSRV hook", t);
            }
        }
    }

    public void reload() {
        getLogger().info("Reloading ControlBans configuration...");
        try {
            ConfigUpdater.update(this);
            configManager.loadConfig();
            localeManager.reload();
            // TimeUtil already reconfigured via ConfigManager.loadConfig()

            if (cacheService != null) {
                cacheService.invalidateAll();
            }
            if (playerChatListener != null) {
                playerChatListener.clearAllMuteStates();
            }
            if (voiceChatService != null) {
                // Drop stale voice states; they reload lazily via updateStatus on next packet/join.
                try {
                    for (org.bukkit.entity.Player p : getServer().getOnlinePlayers()) {
                        voiceChatService.invalidate(p.getUniqueId());
                    }
                } catch (Exception ignored) {}
            }
            if (chatManager != null) {
                try {
                    chatManager.reloadState();
                } catch (Exception ignored) {}
            }
            if (scheduledService != null) {
                try {
                    scheduledService.loadScheduledPunishments();
                } catch (Exception ignored) {}
            }
            if (proxyService != null) {
                try {
                    proxyService.clearQueue();
                } catch (Exception ignored) {}
            }
            // Invalidate the escalation preset cache so /reload picks up new presets without restart
            if (escalationService != null) {
                escalationService.invalidateCache();
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
                try {
                    voiceChatService.shutdown();
                } catch (Exception ignored) {}
                voiceChatService = null;
                getLogger().info("Voice Chat integration unloaded via reload.");
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
        return (DatabaseManager) storage;
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

    public VoiceChatService getVoiceChatService() {
        return voiceChatService;
    }

    public EscalationService getEscalationService() {
        return escalationService;
    }

    public PunishUiManager getPunishUiManager() {
        return punishUiManager;
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

    public ReportService getReportService() {
        return reportService;
    }

    public ScheduledService getScheduledService() {
        return scheduledService;
    }

    public NoteService getNoteService() {
        return noteService;
    }

    public UpdateChecker getUpdateChecker() {
        return updateChecker;
    }

    public MainMenuGuiManager getMainMenuGuiManager() {
        return mainMenuGuiManager;
    }

    public OnlinePlayersGuiManager getOnlinePlayersGuiManager() {
        return onlinePlayersGuiManager;
    }

    public StaffHistoryGuiManager getStaffHistoryGuiManager() {
        return staffHistoryGuiManager;
    }

    public StaffReportsGuiManager getStaffReportsGuiManager() {
        return staffReportsGuiManager;
    }

    public AppealsGuiManager getAppealsGuiManager() {
        return appealsGuiManager;
    }

    public SkinBanService getSkinBanService() {
        return skinBanService;
    }
}