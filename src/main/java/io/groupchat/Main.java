package io.groupchat;

import io.groupchat.chat.ChatLogger;
import io.groupchat.chat.ChatRoom;
import io.groupchat.chat.CommandHandler;
import io.groupchat.chat.SessionManager;
import io.groupchat.config.AppConfig;
import io.groupchat.config.ConfigStore;
import io.groupchat.maintain.Installer;
import io.groupchat.maintain.Maintainer;
import io.groupchat.maintain.ProjectContext;
import io.groupchat.maintain.ServiceController;
import io.groupchat.model.Message;
import io.groupchat.orchestrate.DefaultReviewStrategy;
import io.groupchat.orchestrate.Orchestrator;
import io.groupchat.provider.ProviderRegistry;
import io.groupchat.security.AccessGuard;
import io.groupchat.security.DeviceRegistry;
import io.groupchat.usage.UsageRegistry;
import io.groupchat.web.Roster;
import io.groupchat.web.WebServer;

import java.awt.Desktop;
import java.net.URI;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

/**
 * Application entry point. Wires the pieces together, starts the embedded web
 * server and opens the group chat in the default browser.
 *
 * <p>Supports {@code --context <docs/file.md>} to open a specific markdown
 * file as a context session. The file content is injected as AI context; chat
 * history is logged to a transcript file under {@code logs/}. The legacy
 * {@code --transcript} flag is kept as an alias for compatibility.
 */
public class Main {

    public static void main(String[] args) {
        Path baseDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Options options = Options.parse(args, baseDir);

        ConfigStore store = new ConfigStore(baseDir);
        AppConfig config = store.load();

        ProjectContext projectContext = ProjectContext.detect(baseDir);
        ProviderRegistry registry = ProviderRegistry.withDefaults();
        UsageRegistry usage = new UsageRegistry(config);
        ServiceController service = new ServiceController(projectContext);
        DeviceRegistry devices = new DeviceRegistry(baseDir);
        AccessGuard access = new AccessGuard(devices);

        // SessionManager creates per-session ChatRooms, Loggers, Orchestrators,
        // and CommandHandlers. Agents, config, and usage are shared.
        SessionManager sessionManager = new SessionManager(
                config, store, registry, new DefaultReviewStrategy(), usage,
                baseDir, projectContext, service,
                "main", devices);

        // Create the default session, optionally with a context MD file.
        SessionManager.Session defaultSession;
        if (options.contextFile != null) {
            defaultSession = sessionManager.openFromFile(options.contextFile);
        } else {
            defaultSession = sessionManager.defaultSession();
        }

        // Maintainer + Installer need the default session's room for posting
        // progress messages during /maintain and /install commands.
        Maintainer maintainer = new Maintainer(config, defaultSession.room, projectContext, service);
        Installer installer = new Installer(config, defaultSession.room);

        // Replace the default session's CommandHandler with one that has the real
        // maintainer + installer (the initial one was created with nulls).
        CommandHandler commands = new CommandHandler(config, store, defaultSession.room,
                usage, maintainer, installer, service, defaultSession.contextMdPath, devices, registry);
        if (defaultSession.contextMdPath != null) {
            commands.setOnMerge((summary, messages) ->
                    sessionManager.mergeToContext(defaultSession, summary, messages));
        }
        defaultSession.setCommands(commands);

        WebServer server = new WebServer(config, sessionManager, registry, projectContext, baseDir, access, devices);
        server.start();

        // Quietly install any missing CLI tools.
        installer.ensureAllInBackground();

        String resumed = defaultSession.room.history().isEmpty() ? "" : " Resumed history.";
        String contextInfo = defaultSession.contextMdPath != null
                ? " Context: " + defaultSession.contextMdPath + "." : "";
        defaultSession.room.post(Message.system("Welcome to AI Group Chat. Type /help for commands. "
                + "Mention an agent with @id, or @all to ask everyone. "
                + "Transcript: " + defaultSession.transcriptPath + "." + contextInfo + resumed));

        String url = "http://localhost:" + config.port;
        System.out.println("AI Group Chat running at " + url);
        System.out.println("Transcript: " + defaultSession.transcriptPath);
        if (defaultSession.contextMdPath != null) {
            System.out.println("Context:    " + defaultSession.contextMdPath);
        }
        if (!Boolean.getBoolean("groupchat.noBrowser")) {
            openBrowser(url);
        }

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            sessionManager.shutdownAll();
            server.stop();
        }));
    }

    private static void openBrowser(String url) {
        try {
            if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
                Desktop.getDesktop().browse(URI.create(url));
                return;
            }
        } catch (Exception ignored) {
            // fall through to OS-specific command
        }
        try {
            String os = System.getProperty("os.name").toLowerCase();
            ProcessBuilder pb;
            if (os.contains("mac")) {
                pb = new ProcessBuilder("open", url);
            } else if (os.contains("win")) {
                pb = new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url);
            } else {
                pb = new ProcessBuilder("xdg-open", url);
            }
            pb.start();
        } catch (Exception e) {
            System.out.println("Open your browser at " + url);
        }
    }

    private record Options(Path contextFile) {
        static Options parse(String[] args, Path baseDir) {
            Path contextFile = null;
            for (int i = 0; i < args.length; i++) {
                String arg = args[i];
                if ("--context".equals(arg) || "--transcript".equals(arg) || "--resume".equals(arg)) {
                    if (i + 1 >= args.length) {
                        throw new IllegalArgumentException(arg + " requires a markdown file path");
                    }
                    contextFile = resolve(baseDir, args[++i]);
                } else if (arg.startsWith("--context=")) {
                    contextFile = resolve(baseDir, arg.substring("--context=".length()));
                } else if (arg.startsWith("--transcript=")) {
                    contextFile = resolve(baseDir, arg.substring("--transcript=".length()));
                } else if (arg.startsWith("--resume=")) {
                    contextFile = resolve(baseDir, arg.substring("--resume=".length()));
                } else if ("--help".equals(arg) || "-h".equals(arg)) {
                    printUsageAndExit();
                } else {
                    throw new IllegalArgumentException("Unknown argument: " + arg);
                }
            }
            return new Options(contextFile);
        }

        private static Path resolve(Path baseDir, String value) {
            return Paths.get(value);
        }

        private static void printUsageAndExit() {
            System.out.println("""
                    Usage: java -jar ai-groupchat.jar [--context <docs/file.md>]

                      --context          Open a markdown file as read-only AI context.
                                         Context files must live under docs/.
                                         The file content is injected into every AI request.
                                         Chat history is logged separately to logs/<name>.md.
                      --transcript       Legacy alias for --context.
                      --resume           Legacy alias for --context.
                      -h, --help         Show this help.
                    """);
            System.exit(0);
        }
    }
}
