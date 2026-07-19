package io.groupchat.chat;

import io.groupchat.config.AppConfig;
import io.groupchat.config.ConfigStore;
import io.groupchat.maintain.Installer;
import io.groupchat.maintain.Maintainer;
import io.groupchat.maintain.ProjectContext;
import io.groupchat.maintain.ServiceController;
import io.groupchat.model.Message;
import io.groupchat.orchestrate.Orchestrator;
import io.groupchat.orchestrate.ReviewStrategy;
import io.groupchat.provider.ProviderRegistry;
import io.groupchat.security.DeviceRegistry;
import io.groupchat.usage.UsageRegistry;
import io.groupchat.web.Roster;

import com.fasterxml.jackson.core.type.TypeReference;
import io.groupchat.util.Json;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Manages multiple chat sessions, each with its own transcript and optional
 * read-only context markdown file.
 *
 * <h3>Transcript vs Context</h3>
 * <ul>
 *   <li><b>Transcript</b> — always written to {@code logs/<sessionId>.md}. Every
 *       chat message is appended here. Used to restore history on reconnect.</li>
 *   <li><b>Context MD</b> — optional read-only file under {@code docs/}. Its entire content is
 *       injected into the AI context window (before the chat transcript) so the
 *       AI has background knowledge. Never written to by chat — use
 *       {@code /merge} to explicitly append important chat findings.</li>
 * </ul>
 *
 * <h3>Security</h3>
 * <ul>
 *   <li>Session IDs are restricted to {@code [a-zA-Z0-9._-]+} (max 64 chars).</li>
 *   <li>Context file paths are validated: no {@code ..} traversal, must be under
 *       {@code docs/}, must end with {@code .md}.</li>
 *   <li>Transcript files always live in {@code logs/} under the project dir.</li>
 * </ul>
 */
public class SessionManager {

    private static final Pattern SAFE_ID = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}");
    private static final int MAX_ID_LENGTH = 64;

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Map<String, String> contextMapping = new ConcurrentHashMap<>(); // sessionId → context path
    private final Path metaFile; // config/session-contexts.json — persists contextMapping across restarts
    private final AppConfig config;
    private final ConfigStore store;
    private final ProviderRegistry registry;
    private final ReviewStrategy reviewStrategy;
    private final UsageRegistry usage;
    private final Path baseDir;
    private final Path logsDir;
    private final Path docsDir;
    private final ProjectContext projectContext;
    private final ServiceController service;
    private final DeviceRegistry devices;
    private final String defaultSessionId;

    public SessionManager(AppConfig config, ConfigStore store, ProviderRegistry registry,
                          ReviewStrategy reviewStrategy, UsageRegistry usage,
                          Path baseDir, ProjectContext projectContext,
                          ServiceController service,
                          String defaultSessionId) {
        this(config, store, registry, reviewStrategy, usage, baseDir, projectContext, service,
                defaultSessionId, null);
    }

    public SessionManager(AppConfig config, ConfigStore store, ProviderRegistry registry,
                          ReviewStrategy reviewStrategy, UsageRegistry usage,
                          Path baseDir, ProjectContext projectContext,
                          ServiceController service,
                          String defaultSessionId, DeviceRegistry devices) {
        this.config = config;
        this.store = store;
        this.registry = registry;
        this.reviewStrategy = reviewStrategy;
        this.usage = usage;
        this.baseDir = baseDir;
        this.logsDir = baseDir.resolve("logs").normalize();
        this.docsDir = baseDir.resolve("docs").normalize();
        this.projectContext = projectContext;
        this.service = service;
        this.devices = devices;
        this.defaultSessionId = (defaultSessionId != null && SAFE_ID.matcher(defaultSessionId).matches())
                ? defaultSessionId : "main";
        this.metaFile = baseDir.resolve("config").resolve("session-contexts.json").normalize();
        try {
            Files.createDirectories(logsDir);
            Files.createDirectories(docsDir);
            Files.createDirectories(metaFile.getParent());
        } catch (IOException e) {
            throw new RuntimeException("Failed to create session directories", e);
        }
        loadContextMapping();
    }

    // ── Session metadata persistence ──────────────────────────────────────

    /** Load persisted sessionId → context path mappings. */
    private void loadContextMapping() {
        try {
            Path source = Files.exists(metaFile) ? metaFile : logsDir.resolve(".sessions.json").normalize();
            if (Files.exists(source)) {
                Map<String, String> saved = Json.MAPPER.readValue(source.toFile(),
                        new TypeReference<Map<String, String>>() {});
                if (saved != null) {
                    contextMapping.putAll(saved);
                    migrateContextMappingsToDocs();
                    saveContextMapping();
                }
            }
        } catch (Exception e) {
            System.err.println("WARNING: Failed to load session metadata: " + e.getMessage());
        }
    }

    private void migrateContextMappingsToDocs() {
        contextMapping.replaceAll((id, value) -> {
            Path path = Paths.get(value);
            Path resolved = path.isAbsolute() ? path.normalize() : baseDir.resolve(path).normalize();
            if (resolved.startsWith(docsDir)) {
                return resolved.toString();
            }
            Path migrated = docsDir.resolve(resolved.getFileName()).normalize();
            return migrated.toString();
        });
        contextMapping.entrySet().removeIf(entry -> {
            try {
                Path path = Paths.get(entry.getValue()).normalize();
                return !path.startsWith(docsDir) || !Files.exists(path);
            } catch (Exception e) {
                return true;
            }
        });
    }

    /** Persist current context mappings to disk. */
    private void saveContextMapping() {
        try {
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(metaFile.toFile(), contextMapping);
        } catch (Exception e) {
            System.err.println("WARNING: Failed to save session metadata: " + e.getMessage());
        }
    }

    // ── Public API ───────────────────────────────────────────────────────

    /**
     * Get or create a session by id. If the session doesn't exist, a new one is
     * created. When {@code contextMdFile} is null but a persisted context mapping
     * exists for this session id, the persisted context path is restored.
     */
    public synchronized Session getOrCreate(String sessionId, Path contextMdFile) {
        String id = validateOrCoerceId(sessionId);
        Session existing = sessions.get(id);
        if (existing != null) {
            // Update context mapping if a new context file is provided
            if (contextMdFile != null) {
                String absPath = contextMdFile.toAbsolutePath().normalize().toString();
                if (!absPath.equals(contextMapping.get(id))) {
                    contextMapping.put(id, absPath);
                    saveContextMapping();
                    // Reload context content into the room
                    try {
                        if (Files.exists(contextMdFile)) {
                            String content = Files.readString(contextMdFile, StandardCharsets.UTF_8);
                            existing.room.setContextContent(content);
                        }
                    } catch (IOException e) {
                        System.err.println("WARNING: Failed to reload context: " + e.getMessage());
                    }
                }
            }
            return existing;
        }
        // Restore context from persisted metadata if not explicitly provided
        if (contextMdFile == null) {
            String saved = contextMapping.get(id);
            if (saved != null) {
                Path savedPath = Paths.get(saved);
                if (Files.exists(savedPath)) {
                    contextMdFile = savedPath;
                } else {
                    // Stale mapping — file no longer exists
                    contextMapping.remove(id);
                    saveContextMapping();
                }
            }
        }
        return createSession(id, contextMdFile);
    }

    /** Get an existing session, or null. */
    public synchronized Session get(String sessionId) {
        if (sessionId == null || sessionId.isBlank()) {
            sessionId = defaultSessionId;
        }
        return sessions.get(sessionId);
    }

    /** Get or create the default session. */
    public synchronized Session defaultSession() {
        return getOrCreate(defaultSessionId, null);
    }

    /** List metadata for all active sessions. */
    public synchronized List<SessionInfo> listSessions() {
        List<SessionInfo> list = new ArrayList<>();
        for (Session s : sessions.values()) {
            list.add(new SessionInfo(s.id, s.name,
                    s.transcriptPath.toString(),
                    s.contextMdPath != null ? s.contextMdPath.toString() : null,
                    s.room.history().size()));
        }
        return list;
    }

    /**
     * Open a session with a markdown file as its read-only context.
     * The file content is loaded and injected into AI context.
     * Transcript always goes to {@code logs/<id>.md}.
     *
     * @param mdFile path to the markdown context file
     */
    public synchronized Session openFromFile(Path mdFile) {
        Path resolved = resolveAndValidateContextPath(mdFile);
        String fileName = resolved.getFileName().toString();
        String sessionId = fileNameToSessionId(fileName);

        Session existing = sessions.get(sessionId);
        if (existing != null && resolved.equals(existing.contextMdPath)) {
            return existing;
        }

        // Ensure unique id: if a different file has the same basename, append suffix.
        int suffix = 1;
        String base = sessionId;
        while (sessions.containsKey(sessionId)) {
            sessionId = base + "-" + (suffix++);
        }

        return createSession(sessionId, resolved);
    }

    /**
     * Remove a session: shut down its orchestrator, disconnect clients,
     * remove from the session map, and clean up persisted metadata.
     * The transcript file is preserved on disk.
     */
    public synchronized void removeSession(String sessionId) {
        Session session = sessions.remove(sessionId);
        if (session == null) return;
        session.orchestrator.shutdown();
        contextMapping.remove(sessionId);
        saveContextMapping();
    }

    /** Shut down all sessions. Called on JVM exit. */
    public synchronized void shutdownAll() {
        for (Session s : sessions.values()) {
            s.orchestrator.shutdown();
        }
        sessions.clear();
    }

    // ── Path & ID validation ────────────────────────────────────────────

    /**
     * Validate that a session id is safe (alphanumeric + dot/underscore/hyphen).
     * Returns the validated id or a coerced safe version.
     */
    public static String validateOrCoerceId(String raw) {
        if (raw == null || raw.isBlank()) return "main";
        String trimmed = raw.strip();
        if (SAFE_ID.matcher(trimmed).matches()) return trimmed;
        // Coerce: replace unsafe chars, truncate
        String safe = trimmed.replaceAll("[^a-zA-Z0-9._-]", "-");
        if (safe.length() > MAX_ID_LENGTH) safe = safe.substring(0, MAX_ID_LENGTH);
        if (safe.isEmpty() || !Character.isLetterOrDigit(safe.charAt(0))) safe = "s-" + safe;
        return safe.isBlank() ? "session" : safe;
    }

    /**
     * Validate a context markdown path: must be under docs/, no {@code ..}
     * traversal, must end with {@code .md}. Bare relative filenames are resolved
     * under docs/ for convenience.
     */
    public static Path resolveAndValidateContextPath(Path baseDir, Path input) {
        Path docsDir = baseDir.resolve("docs").normalize();
        Path resolved;
        if (input.isAbsolute()) {
            resolved = input.normalize();
        } else if (input.getParent() == null) {
            resolved = docsDir.resolve(input).normalize();
        } else {
            resolved = baseDir.resolve(input).normalize();
        }

        // Must be under docs/
        if (!resolved.startsWith(docsDir)) {
            throw new IllegalArgumentException(
                    "Context file must be under the docs directory: " + docsDir);
        }

        // No ".." in the original input (extra guard against symlink tricks)
        for (Path part : input) {
            if ("..".equals(part.toString())) {
                throw new IllegalArgumentException("Path traversal not allowed: " + input);
            }
        }

        // Must be a .md file
        String fileName = resolved.getFileName().toString();
        if (!fileName.endsWith(".md")) {
            throw new IllegalArgumentException("Context file must be a .md file: " + fileName);
        }

        return resolved;
    }

    private Path resolveAndValidateContextPath(Path input) {
        return resolveAndValidateContextPath(baseDir, input);
    }

    private static String fileNameToSessionId(String fileName) {
        // Strip .md, replace unsafe chars
        String base = fileName.endsWith(".md")
                ? fileName.substring(0, fileName.length() - 3)
                : fileName;
        return validateOrCoerceId(base);
    }

    // ── Internal ─────────────────────────────────────────────────────────

    private Session createSession(String id, Path contextMdFile) {
        // Transcript always goes to logs/<id>.md
        Path transcriptPath = logsDir.resolve(id + ".md").normalize();

        // Read context content if a context MD file is provided
        String contextContent = null;
        if (contextMdFile != null) {
            try {
                if (Files.exists(contextMdFile)) {
                    contextContent = Files.readString(contextMdFile, StandardCharsets.UTF_8);
                }
            } catch (IOException e) {
                System.err.println("WARNING: Failed to read context file " + contextMdFile + ": " + e.getMessage());
            }
        }

        ChatLogger logger = new ChatLogger(baseDir, config, transcriptPath);
        ChatRoom room = new ChatRoom(logger);
        if (contextContent != null) {
            room.setContextContent(contextContent);
        }

        // Restore history from the transcript file (not the context file)
        List<Message> restored = logger.readHistory();
        room.restoreHistory(restored);
        room.setContextWindowMessages(config.contextWindowMessages);
        room.setRosterSupplier(() -> Roster.snapshot(config, registry, projectContext));
        room.setStatusSupplier(usage::snapshot);

        Orchestrator orchestrator = new Orchestrator(config, registry, room, reviewStrategy, usage);
        room.setMessageListener(orchestrator::onMessage);

        CommandHandler commands = new CommandHandler(config, store, room, usage,
                null, null, service, contextMdFile, devices, registry);

        String name = deriveSessionName(id, contextMdFile);

        Session session = new Session(id, name, room, logger, orchestrator, commands,
                transcriptPath, contextMdFile);

        // Bind merge callback after session is created (needs final reference)
        if (contextMdFile != null) {
            commands.setOnMerge((summary, messages) -> mergeToContext(session, summary, messages));
            // Persist the context mapping so it survives server restarts
            contextMapping.put(id, contextMdFile.toAbsolutePath().normalize().toString());
            saveContextMapping();
        }
        sessions.put(id, session);

        // Post welcome message
        StringBuilder welcome = new StringBuilder();
        welcome.append("Session '").append(name).append("'");
        if (contextMdFile != null) {
            welcome.append(" — Context: ").append(contextMdFile);
        }
        welcome.append(" — Transcript: ").append(transcriptPath);
        if (!restored.isEmpty()) {
            welcome.append(". Resumed ").append(restored.size()).append(" previous messages.");
        }
        welcome.append(".");
        room.post(Message.system(welcome.toString()));

        return session;
    }

    private String deriveSessionName(String id, Path contextMdFile) {
        if (contextMdFile != null) {
            String fileName = contextMdFile.getFileName().toString();
            if (fileName.endsWith(".md")) {
                return fileName.substring(0, fileName.length() - 3);
            }
            return fileName;
        }
        if (defaultSessionId.equals(id)) {
            return "Main Chat";
        }
        return id;
    }

    // ── Merge: append chat context to context MD file ───────────────────

    /** Append a concise chat finding summary to the context MD file. */
    public void mergeToContext(Session session, String summary, List<Message> recentMessages) {
        if (session.contextMdPath == null) {
            throw new IllegalStateException("This session has no context MD file to merge into.");
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n---\n\n");
        sb.append("## Merged from chat — ").append(java.time.LocalDateTime.now()).append("\n\n");
        if (summary != null && !summary.isBlank()) {
            sb.append("**Summary**: ").append(summary).append("\n\n");
        }
        if (recentMessages != null && !recentMessages.isEmpty()) {
            sb.append("### Key exchanges\n\n");
            for (Message m : recentMessages) {
                String name = m.senderName != null ? m.senderName : m.sender;
                sb.append("- **").append(name).append("**: ").append(m.content).append("\n");
            }
        }

        try {
            Files.writeString(session.contextMdPath, sb.toString(),
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write to context file: " + session.contextMdPath, e);
        }

        // Reload context content into the room so future AI requests see the update
        try {
            String updated = Files.readString(session.contextMdPath, StandardCharsets.UTF_8);
            session.room.setContextContent(updated);
        } catch (IOException e) {
            System.err.println("WARNING: Failed to reload context after merge: " + e.getMessage());
        }
    }

    // ── Types ────────────────────────────────────────────────────────────

    /** Lightweight session metadata returned to the frontend. */
    public record SessionInfo(String id, String name, String transcriptPath,
                              String contextMdPath, int messageCount) {}

    /** A complete chat session: isolated room, logger, and orchestrator. */
    public static class Session {
        public final String id;
        public final String name;
        public final ChatRoom room;
        public final ChatLogger logger;
        public final Orchestrator orchestrator;
        public final Path transcriptPath;
        /** Optional read-only context file injected into AI context. Null for ad-hoc sessions. */
        public final Path contextMdPath;
        private volatile CommandHandler commands;

        Session(String id, String name, ChatRoom room, ChatLogger logger,
                Orchestrator orchestrator, CommandHandler commands,
                Path transcriptPath, Path contextMdPath) {
            this.id = id;
            this.name = name;
            this.room = room;
            this.logger = logger;
            this.orchestrator = orchestrator;
            this.commands = commands;
            this.transcriptPath = transcriptPath;
            this.contextMdPath = contextMdPath;
        }

        public CommandHandler commands() {
            return commands;
        }

        public void setCommands(CommandHandler commands) {
            this.commands = commands;
        }
    }
}
