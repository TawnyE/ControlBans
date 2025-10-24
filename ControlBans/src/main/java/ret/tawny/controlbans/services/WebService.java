package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import jakarta.servlet.AsyncContext;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public class WebService {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final ConfigManager config;
    private final AnalyticsService analyticsService;
    private final AppealService appealService;
    private final HealthService healthService;
    private final BenchmarkService benchmarkService;
    private final AuditService auditService;
    private Server server;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WebService(ControlBansPlugin plugin, PunishmentService punishmentService, ConfigManager config) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.config = config;
        this.analyticsService = plugin.getAnalyticsService();
        this.appealService = plugin.getAppealService();
        this.healthService = plugin.getHealthService();
        this.benchmarkService = plugin.getBenchmarkService();
        this.auditService = plugin.getAuditService();
    }

    public synchronized void start() {
        if (!config.isWebEnabled()) {
            return;
        }

        if (server != null && (server.isStarting() || server.isStarted())) {
            plugin.getLogger().warning("Web service is already running.");
            return;
        }

        server = new Server(new InetSocketAddress(config.getWebHost(), config.getWebPort()));
        ServletContextHandler context = new ServletContextHandler(ServletContextHandler.NO_SESSIONS);
        context.setContextPath("/");

        ServletHolder apiHolder = new ServletHolder("api", new ApiServlet());
        context.addServlet(apiHolder, "/api/*");

        URL webResource = plugin.getClass().getClassLoader().getResource("web");
        if (webResource == null) {
            plugin.getLogger().severe("Could not find 'web' resource folder in JAR. Web UI will not be available.");
            destroyServerQuietly();
            return;
        }

        ServletHolder staticHolder = new ServletHolder("static", new DefaultServlet());
        staticHolder.setInitParameter("resourceBase", webResource.toExternalForm());
        staticHolder.setInitParameter("dirAllowed", "false");
        staticHolder.setInitParameter("pathInfoOnly", "true");
        context.addServlet(staticHolder, "/*");

        server.setHandler(context);

        Thread webThread = new Thread(() -> {
            try {
                server.start();
                plugin.getLogger().info("Web service started on " + config.getWebHost() + ":" + config.getWebPort() + ".");
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Failed to start web server", e);
                destroyServerQuietly();
            }
        }, "ControlBans-WebServer");
        webThread.setDaemon(true);
        webThread.start();
    }

    public synchronized void shutdown() {
        if (server != null) {
            try {
                if (server.isRunning() || server.isStarting()) {
                    server.stop();
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Error shutting down web server", e);
            } finally {
                destroyServerQuietly();
                plugin.getLogger().info("Web service stopped.");
            }
        }
    }

    public void restart() {
        shutdown();
        start();
    }

    private class ApiServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            String path = req.getPathInfo();
            String endpoint = path != null ? path : "/";
            String token = req.getHeader("Authorization");
            boolean requireToken = !"/health".equals(endpoint) || config.isHealthcheckTokenRequired();
            if (requireToken && !config.getWebAdminToken().equals(token)) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }

            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());

            switch (endpoint) {
                case "/punishments" -> handlePunishments(req, resp);
                case "/analytics" -> handleAnalytics(req, resp);
                case "/health" -> handleHealth(req, resp);
                case "/metrics" -> handleMetrics(resp);
                case "/appeals" -> handleAppeals(req, resp);
                case "/audit" -> handleAudit(req, resp);
                case "/roadmap" -> handleRoadmap(resp);
                default -> {
                    resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                    resp.getWriter().write("{\"error\":\"Endpoint not found\"}");
                    benchmarkService.recordWebRequest(endpoint, 0L, HttpServletResponse.SC_NOT_FOUND);
                }
            }
        }
    }

    private void destroyServerQuietly() {
        if (server != null) {
            try {
                server.destroy();
            } catch (Exception ignored) {
            }
            server = null;
        }
    }

    private void handlePunishments(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(10000L);
        long started = System.nanoTime();
        CompletableFuture<List<Punishment>> future = punishmentService.getRecentPunishments(config.getWebRecordsPerPage());
        future.whenComplete((punishments, throwable) -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
            int status = HttpServletResponse.SC_OK;
            try {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to fetch recent punishments for web API", throwable);
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    asyncResp.setStatus(status);
                    asyncResp.getWriter().write("{\"error\":\"Database query failed\"}");
                } else {
                    asyncResp.setStatus(HttpServletResponse.SC_OK);
                    asyncResp.getWriter().write(gson.toJson(punishments));
                }
            } catch (IOException ioException) {
                plugin.getLogger().log(Level.WARNING, "Failed to write web API response", ioException);
            } finally {
                benchmarkService.recordWebRequest("punishments", System.nanoTime() - started, status);
                asyncContext.complete();
            }
        });
    }

    private void handleAnalytics(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(10000L);
        long started = System.nanoTime();
        analyticsService.getDashboardSnapshot().whenComplete((snapshot, throwable) -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
            int status = HttpServletResponse.SC_OK;
            try {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to compute analytics snapshot", throwable);
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    asyncResp.setStatus(status);
                    asyncResp.getWriter().write("{\"error\":\"Analytics failed\"}");
                } else {
                    asyncResp.setStatus(HttpServletResponse.SC_OK);
                    asyncResp.getWriter().write(gson.toJson(snapshot));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write analytics response", e);
            } finally {
                benchmarkService.recordWebRequest("analytics", System.nanoTime() - started, status);
                asyncContext.complete();
            }
        });
    }

    private void handleHealth(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(10000L);
        long started = System.nanoTime();
        healthService.refresh().whenComplete((report, throwable) -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
            int status = HttpServletResponse.SC_OK;
            try {
                if (throwable != null) {
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    asyncResp.setStatus(status);
                    asyncResp.getWriter().write("{\"error\":\"Health probe failed\"}");
                } else {
                    asyncResp.setStatus(HttpServletResponse.SC_OK);
                    asyncResp.getWriter().write(gson.toJson(report));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write health response", e);
            } finally {
                benchmarkService.recordWebRequest("health", System.nanoTime() - started, status);
                asyncContext.complete();
            }
        });
    }

    private void handleMetrics(HttpServletResponse resp) throws IOException {
        long started = System.nanoTime();
        BenchmarkService.BenchmarkSnapshot snapshot = benchmarkService.snapshot();
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(snapshot));
        benchmarkService.recordWebRequest("metrics", System.nanoTime() - started, HttpServletResponse.SC_OK);
    }

    private void handleAppeals(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(10000L);
        long started = System.nanoTime();
        appealService.listOpenAppeals().whenComplete((appeals, throwable) -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
            int status = HttpServletResponse.SC_OK;
            try {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to fetch appeals", throwable);
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    asyncResp.setStatus(status);
                    asyncResp.getWriter().write("{\"error\":\"Appeal query failed\"}");
                } else {
                    asyncResp.setStatus(HttpServletResponse.SC_OK);
                    asyncResp.getWriter().write(gson.toJson(appeals));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write appeals response", e);
            } finally {
                benchmarkService.recordWebRequest("appeals", System.nanoTime() - started, status);
                asyncContext.complete();
            }
        });
    }

    private void handleAudit(HttpServletRequest req, HttpServletResponse resp) {
        AsyncContext asyncContext = req.startAsync();
        asyncContext.setTimeout(10000L);
        long started = System.nanoTime();
        auditService.fetchRecent(50).whenComplete((entries, throwable) -> {
            HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
            int status = HttpServletResponse.SC_OK;
            try {
                if (throwable != null) {
                    plugin.getLogger().log(Level.WARNING, "Failed to fetch audit log", throwable);
                    status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
                    asyncResp.setStatus(status);
                    asyncResp.getWriter().write("{\"error\":\"Audit query failed\"}");
                } else {
                    asyncResp.setStatus(HttpServletResponse.SC_OK);
                    asyncResp.getWriter().write(gson.toJson(entries));
                }
            } catch (IOException e) {
                plugin.getLogger().log(Level.WARNING, "Failed to write audit response", e);
            } finally {
                benchmarkService.recordWebRequest("audit", System.nanoTime() - started, status);
                asyncContext.complete();
            }
        });
    }

    private void handleRoadmap(HttpServletResponse resp) throws IOException {
        long started = System.nanoTime();
        resp.setStatus(HttpServletResponse.SC_OK);
        resp.getWriter().write(gson.toJson(config.getRoadmapMarkers()));
        benchmarkService.recordWebRequest("roadmap", System.nanoTime() - started, HttpServletResponse.SC_OK);
    }
}
