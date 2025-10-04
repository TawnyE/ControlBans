package ret.tawny.controlbans.services;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.DefaultServlet;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import ret.tawny.controlbans.ControlBansPlugin;
import ret.tawny.controlbans.config.ConfigManager;
import ret.tawny.controlbans.model.Punishment;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public class WebService {

    private final ControlBansPlugin plugin;
    private final PunishmentService punishmentService;
    private final ConfigManager config;
    private Server server;
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();

    public WebService(ControlBansPlugin plugin, PunishmentService punishmentService, AltService altService, ConfigManager config) {
        this.plugin = plugin;
        this.punishmentService = punishmentService;
        this.config = config;
    }

    public void start() throws Exception {
        if (!config.isWebEnabled()) return;

        server = new Server(config.getWebPort());
        ServletContextHandler context = new ServletContextHandler();
        context.setContextPath("/");

        // API servlet
        ServletHolder apiHolder = new ServletHolder("api", new ApiServlet());
        context.addServlet(apiHolder, "/api/*");

        // Static content servlet (for web UI)
        ServletHolder staticHolder = new ServletHolder("static", new DefaultServlet());
        staticHolder.setInitParameter("resourceBase", plugin.getClass().getClassLoader().getResource("web").toExternalForm());
        staticHolder.setInitParameter("dirAllowed", "false");
        staticHolder.setInitParameter("pathInfoOnly", "true");
        context.addServlet(staticHolder, "/*");

        server.setHandler(context);

        new Thread(() -> {
            try {
                server.start();
                server.join();
            } catch (Exception e) {
                plugin.getLogger().severe("Failed to start web server: " + e.getMessage());
            }
        }).start();
    }

    public void shutdown() {
        if (server != null && server.isRunning()) {
            try {
                server.stop();
            } catch (Exception e) {
                plugin.getLogger().warning("Error shutting down web server: " + e.getMessage());
            }
        }
    }

    public void restart() throws Exception {
        shutdown();
        start();
    }

    private class ApiServlet extends HttpServlet {
        @Override
        protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
            // Simple token-based auth
            String token = req.getHeader("Authorization");
            if (!config.getWebAdminToken().equals(token)) {
                resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                resp.getWriter().write("{\"error\":\"Unauthorized\"}");
                return;
            }

            resp.setContentType("application/json");
            resp.setCharacterEncoding(StandardCharsets.UTF_8.name());

            String path = req.getPathInfo();
            if (path.equals("/punishments")) {
                CompletableFuture<List<Punishment>> future = punishmentService.getRecentPunishments(config.getWebRecordsPerPage());
                future.whenComplete((punishments, throwable) -> {
                    try {
                        if (throwable != null) {
                            resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
                            resp.getWriter().write("{\"error\":\"Database query failed\"}");
                        } else {
                            resp.setStatus(HttpServletResponse.SC_OK);
                            resp.getWriter().write(gson.toJson(punishments));
                        }
                    } catch (IOException e) {
                        // Ignore
                    }
                });
            } else {
                resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
                resp.getWriter().write("{\"error\":\"Endpoint not found\"}");
            }
        }
    }
}