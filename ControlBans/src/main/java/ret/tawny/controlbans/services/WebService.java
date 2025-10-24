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
    private Server server;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WebService(ControlBansPlugin plugin, PunishmentService punishmentService, ConfigManager config) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.config = config;
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
            String token = req.getHeader("Authorization");
            if (!config.getWebAdminToken().equals(token)) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }

            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());

            String path = req.getPathInfo();
            if ("/punishments".equals(path)) {
                AsyncContext asyncContext = req.startAsync();
                asyncContext.setTimeout(10000L);
                CompletableFuture<List<Punishment>> future = punishmentService.getRecentPunishments(config.getWebRecordsPerPage());
                future.whenComplete((punishments, throwable) -> {
                    HttpServletResponse asyncResp = (HttpServletResponse) asyncContext.getResponse();
                    try {
                        if (throwable != null) {
                            plugin.getLogger().log(Level.WARNING, "Failed to fetch recent punishments for web API", throwable);
                            asyncResp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                            asyncResp.getWriter().write("{\"error\":\"Database query failed\"}");
                        } else {
                            asyncResp.setStatus(HttpServletResponse.SC_OK);
                            asyncResp.getWriter().write(gson.toJson(punishments));
                        }
                    } catch (IOException ioException) {
                        plugin.getLogger().log(Level.WARNING, "Failed to write web API response", ioException);
                    } finally {
                        asyncContext.complete();
                    }
                });
                return;
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"error\":\"Endpoint not found\"}");
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
}
