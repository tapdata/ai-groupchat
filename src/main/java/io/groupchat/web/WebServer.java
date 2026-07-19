package io.groupchat.web;

import io.groupchat.chat.CommandHandler;
import io.groupchat.chat.SessionManager;
import io.groupchat.config.AppConfig;
import io.groupchat.maintain.ProjectContext;
import io.groupchat.model.Message;
import io.groupchat.provider.ProviderRegistry;
import io.groupchat.security.AccessGuard;
import io.groupchat.security.DeviceRegistry;
import io.groupchat.util.Json;
import io.javalin.Javalin;
import io.javalin.http.staticfiles.Location;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Embedded Javalin server: serves the static chat UI, exposes agent roster and
 * session CRUD endpoints, and bridges WebSocket connections to the correct
 * chat session.
 *
 * <p>WebSocket clients connect to {@code /ws?session=<id>} and are routed to
 * the corresponding {@link SessionManager.Session}.
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Session IDs are validated against {@code [a-zA-Z0-9._-]+}.</li>
 *   <li>Context file paths are validated for traversal and extension.</li>
 * </ul>
 */
public class WebServer {

    private final AppConfig config;
    private final SessionManager sessionManager;
    private final ProviderRegistry registry;
    private final ProjectContext projectContext;
    private final Path baseDir;
    private final AccessGuard access;
    private final DeviceRegistry devices;
    private Javalin app;

    public WebServer(AppConfig config, SessionManager sessionManager,
                     ProviderRegistry registry, ProjectContext projectContext,
                     Path baseDir) {
        this(config, sessionManager, registry, projectContext, baseDir, null, null);
    }

    public WebServer(AppConfig config, SessionManager sessionManager,
                     ProviderRegistry registry, ProjectContext projectContext,
                     Path baseDir, AccessGuard access, DeviceRegistry devices) {
        this.config = config;
        this.sessionManager = sessionManager;
        this.registry = registry;
        this.projectContext = projectContext;
        this.baseDir = baseDir;
        this.access = access;
        this.devices = devices;
    }

    public void start() {
        app = Javalin.create(cfg -> cfg.staticFiles.add("/web", Location.CLASSPATH));
        if (access != null) {
            app.before(access::handleHttp);
        }

        // ── REST API ──────────────────────────────────────────────────

        app.get("/api/access/status", ctx -> {
            if (access == null) {
                ctx.json(Map.of("enabled", false, "authenticated", true, "reason", "access disabled"));
                return;
            }
            ctx.json(access.statusPayload(ctx.ip(), ctx.header("Cookie"), ctx.host()));
        });

        app.post("/api/access/claim", ctx -> {
            if (devices == null || access == null) {
                ctx.status(503).json(Map.of("error", "Access control unavailable"));
                return;
            }
            String code = null;
            String name = null;
            try {
                var body = Json.read(ctx.body());
                code = body.has("code") ? body.get("code").asText(null) : null;
                name = body.has("name") ? body.get("name").asText(null) : null;
            } catch (Exception ignored) {
                // handled below
            }
            DeviceRegistry.RegisteredDevice registered = devices.claimPairingCode(code, name, ctx.ip());
            if (registered == null) {
                ctx.status(400).json(Map.of("error", "Invalid or expired pairing code"));
                return;
            }
            ctx.header("Set-Cookie", access.cookieName() + "=" + registered.token()
                    + "; Path=/; Max-Age=31536000; SameSite=Lax; HttpOnly");
            ctx.json(Map.of(
                    "ok", true,
                    "deviceId", registered.device().id,
                    "deviceName", registered.device().name
            ));
        });

        app.post("/api/access/logout", ctx -> {
            if (access != null) {
                ctx.header("Set-Cookie", access.cookieName()
                        + "=; Path=/; Max-Age=0; SameSite=Lax; HttpOnly");
            }
            ctx.json(Map.of("ok", true));
        });

        app.get("/api/agents", ctx ->
                ctx.json(Roster.snapshot(config, registry, projectContext)));

        app.get("/api/sessions", ctx ->
                ctx.json(sessionManager.listSessions()));

        app.post("/api/sessions", ctx -> {
            String sessionId = null;
            String mdFilePath = null;
            try {
                var body = Json.read(ctx.body());
                sessionId = body.has("sessionId") ? body.get("sessionId").asText(null) : null;
                mdFilePath = body.has("mdFile") ? body.get("mdFile").asText(null) : null;
            } catch (Exception ignored) {
                // accept empty body — just open the default session
            }

            SessionManager.Session session;
            if (mdFilePath != null && !mdFilePath.isBlank()) {
                // Validate and open as context MD file
                Path mdFile;
                try {
                    mdFile = SessionManager.resolveAndValidateContextPath(baseDir, Paths.get(mdFilePath));
                } catch (IllegalArgumentException e) {
                    ctx.status(400).json(Map.of("error", e.getMessage()));
                    return;
                }
                session = sessionManager.openFromFile(mdFile);
            } else {
                String id = (sessionId != null && !sessionId.isBlank())
                        ? SessionManager.validateOrCoerceId(sessionId)
                        : null;
                session = sessionManager.getOrCreate(id, null);
            }

            ctx.json(sessionPayload(session));
        });

        // Delete (close) a session
        app.delete("/api/sessions/{sessionId}", ctx -> {
            String sessionId = ctx.pathParam("sessionId");
            sessionId = SessionManager.validateOrCoerceId(sessionId);
            sessionManager.removeSession(sessionId);
            ctx.json(Map.of("ok", true));
        });

        // ── WebSocket ─────────────────────────────────────────────────

        app.ws("/ws", ws -> {
            ws.onConnect(ctx -> {
                if (access != null && !access.allowWs(ctx)) {
                    ctx.session.close(1008, "Device not trusted");
                    return;
                }
                // Jetty closes idle WebSockets after 30s by default; keep alive.
                ctx.session.setIdleTimeout(java.time.Duration.ofHours(12));

                String rawId = ctx.queryParam("session");
                String sessionId = SessionManager.validateOrCoerceId(rawId);
                SessionManager.Session session = sessionManager.getOrCreate(sessionId, null);
                ctx.attribute("gc-session", session);
                session.room.addClient(ctx);
            });
            ws.onClose(ctx -> {
                SessionManager.Session session = ctx.attribute("gc-session");
                if (session != null) {
                    session.room.removeClient(ctx);
                }
            });
            ws.onError(ctx -> {
                SessionManager.Session session = ctx.attribute("gc-session");
                if (session != null) {
                    session.room.removeClient(ctx);
                }
            });
            ws.onMessage(ctx -> {
                SessionManager.Session session = ctx.attribute("gc-session");
                if (session == null) {
                    return;
                }
                handleIncoming(ctx.message(), session);
            });
        });

        app.start(config.port);
    }

    private void handleIncoming(String raw, SessionManager.Session session) {
        String content;
        try {
            content = Json.read(raw).path("content").asText("").strip();
        } catch (Exception e) {
            content = raw == null ? "" : raw.strip();
        }
        if (content.isEmpty()) {
            return;
        }
        if (session.commands().isCommand(content)) {
            session.commands().handle(content);
            return;
        }
        session.room.post(Message.user(content));
    }

    private Map<String, Object> sessionPayload(SessionManager.Session session) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", session.id);
        payload.put("name", session.name);
        payload.put("transcriptPath", session.transcriptPath.toString());
        payload.put("contextMdPath", session.contextMdPath != null ? session.contextMdPath.toString() : null);
        payload.put("messageCount", session.room.history().size());
        return payload;
    }

    public int port() {
        return config.port;
    }

    public void stop() {
        if (app != null) {
            app.stop();
        }
    }
}
