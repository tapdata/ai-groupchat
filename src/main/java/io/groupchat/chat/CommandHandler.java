package io.groupchat.chat;

import io.groupchat.config.AppConfig;
import io.groupchat.config.ConfigStore;
import io.groupchat.maintain.Installer;
import io.groupchat.maintain.Maintainer;
import io.groupchat.maintain.ServiceController;
import io.groupchat.model.Agent;
import io.groupchat.model.Message;
import io.groupchat.model.MessageType;
import io.groupchat.provider.AgentRequest;
import io.groupchat.provider.AgentResponse;
import io.groupchat.provider.ProviderRegistry;
import io.groupchat.security.DeviceRegistry;
import io.groupchat.security.TrustedDevice;
import io.groupchat.usage.TokenStatus;
import io.groupchat.usage.UsageRegistry;
import io.groupchat.util.Json;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Handles slash commands typed in the chat box. Commands never reach the AIs and
 * their responses are posted as SYSTEM messages.
 *
 * <pre>
 *   /help                         show commands
 *   /agents                       list agents and readiness
 *   /config &lt;id&gt; &lt;key&gt;=&lt;value&gt;     set one field
 *   /env &lt;id&gt;                     list an agent's CLI env vars (secrets masked)
 *   /rename &lt;id&gt; &lt;new name&gt;        change an agent's display nickname
 *   /add &lt;id&gt; &lt;provider&gt; [name]    add a new AI member
 *   /remove &lt;id&gt;                   remove a member
 *   /free on|off                  toggle free mode
 *   /tokens                       show token usage
 *   /synthesizer &lt;id&gt;             set the @all synthesizer
 *   /optimize &lt;requirement&gt;       (owner) self-optimize the project and restart
 *   /restart                      (owner) restart the service
 *   /install [id]                 install CLI tool(s)
 * </pre>
 */
public class CommandHandler {

    private final AppConfig config;
    private final ConfigStore store;
    private final ChatRoom room;
    private final UsageRegistry usage;
    private final Maintainer maintainer;
    private final Installer installer;
    private final ServiceController service;
    private final Path contextMdPath;       // nullable — set when session has a context MD
    private final DeviceRegistry devices;   // nullable — access control not wired in tests/legacy paths
    private final ProviderRegistry registry; // nullable — needed for /merge compress

    public CommandHandler(AppConfig config, ConfigStore store, ChatRoom room,
                          UsageRegistry usage, Maintainer maintainer, Installer installer,
                          ServiceController service) {
        this(config, store, room, usage, maintainer, installer, service, null, null, null);
    }

    public CommandHandler(AppConfig config, ConfigStore store, ChatRoom room,
                          UsageRegistry usage, Maintainer maintainer, Installer installer,
                          ServiceController service, Path contextMdPath) {
        this(config, store, room, usage, maintainer, installer, service, contextMdPath, null, null);
    }

    public CommandHandler(AppConfig config, ConfigStore store, ChatRoom room,
                          UsageRegistry usage, Maintainer maintainer, Installer installer,
                          ServiceController service, Path contextMdPath, DeviceRegistry devices) {
        this(config, store, room, usage, maintainer, installer, service, contextMdPath, devices, null);
    }

    public CommandHandler(AppConfig config, ConfigStore store, ChatRoom room,
                          UsageRegistry usage, Maintainer maintainer, Installer installer,
                          ServiceController service, Path contextMdPath, DeviceRegistry devices,
                          ProviderRegistry registry) {
        this.config = config;
        this.store = store;
        this.room = room;
        this.usage = usage;
        this.maintainer = maintainer;
        this.installer = installer;
        this.service = service;
        this.contextMdPath = contextMdPath;
        this.devices = devices;
        this.registry = registry;
    }

    public boolean isCommand(String content) {
        return content != null && content.stripLeading().startsWith("/");
    }

    public void handle(String content) {
        String line = content.strip();
        String[] head = line.split("\\s+", 2);
        String cmd = head[0].toLowerCase();
        String rest = head.length > 1 ? head[1].strip() : "";
        switch (cmd) {
            case "/help" -> room.post(Message.system(helpText()));
            case "/agents" -> room.post(Message.system(agentsText()));
            case "/config" -> handleConfig(rest);
            case "/env" -> handleEnv(rest);
            case "/rename" -> handleRename(rest);
            case "/add" -> handleAdd(rest);
            case "/remove" -> handleRemove(rest);
            case "/free" -> handleFree(rest);
            case "/tokens" -> room.post(Message.system(tokensText()));
            case "/synthesizer" -> handleSynthesizer(rest);
            case "/optimize" -> {
                if (maintainer == null) room.post(Message.system("Maintainer not available for this session."));
                else maintainer.optimize(rest);
            }
            case "/restart" -> service.restart(room);
            case "/install" -> {
                if (installer == null) room.post(Message.system("Installer not available for this session."));
                else installer.installNow(rest.isBlank() ? null : rest.trim());
            }
            case "/merge" -> handleMerge(rest);
            case "/device" -> handleDevice(rest);
            case "/access" -> handleAccess(rest);
            case "/modellist" -> handleModelList(rest);
            default -> room.post(Message.system("Unknown command: " + cmd + " (try /help)"));
        }
    }

    private void handleConfig(String rest) {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || !parts[1].contains("=")) {
            room.post(Message.system("Usage: /config <agentId> <key>=<value>\n\n"
                    + "Set a configuration field for an agent. Available keys:\n"
                    + "  apiKey        — API key (stored locally, never shown to clients)\n"
                    + "  model         — Model name, e.g. deepseek-v4-pro, gpt-4o\n"
                    + "  provider      — Backend type: anthropic, openai, or cli\n"
                    + "  name          — Display name shown in the UI\n"
                    + "  baseUrl       — Override the API base URL (for proxies / compatible APIs)\n"
                    + "  systemPrompt  — Persona / system prompt prepended to each request\n"
                    + "  freeModel     — Model used when free mode is on\n"
                    + "  freeBaseUrl   — Base URL used when free mode is on\n"
                    + "  command       — CLI command, e.g. \"auggie --print\"\n"
                    + "  cwd           — working directory for CLI subprocesses (the project\n"
                    + "                  the agent should read), e.g. /path/to/project\n"
                    + "  freeCommand   — CLI command when free mode is on\n"
                    + "  installCommand — Shell command to install this agent's CLI tool\n"
                    + "  enabled       — true or false, whether this agent participates in @all\n"
                    + "  freeMode      — true or false, use free model/command for this agent\n"
                    + "  timeoutSeconds — max seconds a CLI agent may run (default 900); empty resets\n"
                    + "  env.<NAME>    — set an environment variable for CLI subprocesses\n"
                    + "                  (empty value removes it); e.g. env.ANTHROPIC_AUTH_TOKEN=...\n\n"
                    + "Examples:\n"
                    + "  /config claude apiKey=sk-abc123\n"
                    + "  /config claude baseUrl=https://api.deepseek.com\n"
                    + "  /config claudecli env.ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic\n"
                    + "  /config claude enabled=false"));
            return;
        }
        String id = parts[0];
        Agent agent = config.findAgent(id).orElse(null);
        if (agent == null) {
            room.post(Message.system("No such agent: " + id));
            return;
        }
        int eq = parts[1].indexOf('=');
        String key = parts[1].substring(0, eq).trim();
        String value = parts[1].substring(eq + 1).trim();
        if (!applyField(agent, key, value)) {
            room.post(Message.system("Unknown field: " + key));
            return;
        }
        store.save();
        room.broadcastRoster();
        String shown = isSensitiveKey(key) ? "***" : (value.isEmpty() ? "(removed)" : value);
        room.post(Message.system(agent.name + "." + key + " set to " + shown
                + " (ready=" + agent.isReady() + ")"));
    }

    /** Secret-bearing keys whose values must never be echoed back to clients. */
    private static boolean isSensitiveKey(String key) {
        String k = key.toLowerCase();
        return k.equals("apikey")
                || k.contains("token")
                || k.contains("secret")
                || k.contains("password")
                || k.contains("auth")
                || k.endsWith("key");
    }

    private boolean applyField(Agent agent, String key, String value) {
        // Per-agent environment variables: env.<NAME>=<value> (NAME is case-sensitive).
        // An empty value removes the variable.
        if (key.length() > 4 && key.regionMatches(true, 0, "env.", 0, 4)) {
            String varName = key.substring(4).trim();
            if (varName.isEmpty()) {
                return false;
            }
            if (agent.env == null) {
                agent.env = new java.util.LinkedHashMap<>();
            }
            if (value.isEmpty()) {
                agent.env.remove(varName);
            } else {
                agent.env.put(varName, value);
            }
            return true;
        }
        switch (key.toLowerCase()) {
            case "apikey" -> agent.apiKey = value;
            case "model" -> agent.model = value;
            case "name" -> agent.name = value;
            case "provider" -> agent.provider = value;
            case "baseurl" -> agent.baseUrl = value;
            case "command" -> agent.command = value;
            case "cwd" -> agent.cwd = value;
            case "freecommand" -> agent.freeCommand = value;
            case "systemprompt" -> agent.systemPrompt = value;
            case "freemodel" -> agent.freeModel = value;
            case "freebaseurl" -> agent.freeBaseUrl = value;
            case "installcommand" -> agent.installCommand = value;
            case "enabled" -> agent.enabled = Boolean.parseBoolean(value);
            case "freemode" -> agent.freeMode = Boolean.parseBoolean(value);
            case "timeoutseconds" -> {
                if (value.isEmpty()) {
                    agent.timeoutSeconds = null;
                } else {
                    try {
                        int secs = Integer.parseInt(value.trim());
                        agent.timeoutSeconds = secs > 0 ? secs : null;
                    } catch (NumberFormatException e) {
                        return false;
                    }
                }
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private void handleEnv(String rest) {
        String id = rest.trim();
        if (id.isBlank()) {
            room.post(Message.system("Usage: /env <agentId>\n\n"
                    + "List the environment variables configured for an agent's CLI\n"
                    + "subprocess. Secret-looking values (token/key/secret/auth) are masked.\n"
                    + "Set or remove variables with /config <id> env.<NAME>=<value>.\n"
                    + "Example: /env claudecli"));
            return;
        }
        Agent agent = config.findAgent(id).orElse(null);
        if (agent == null) {
            room.post(Message.system("No such agent: \"" + id + "\". Use /agents to see the roster."));
            return;
        }
        if (agent.env == null || agent.env.isEmpty()) {
            room.post(Message.system(agent.name + " has no environment variables set. "
                    + "Add one with /config " + agent.id + " env.<NAME>=<value>"));
            return;
        }
        StringBuilder sb = new StringBuilder("Environment variables for " + agent.name + ":\n");
        for (java.util.Map.Entry<String, String> e : agent.env.entrySet()) {
            String shown = isSensitiveKey(e.getKey()) ? "***" : e.getValue();
            sb.append("  ").append(e.getKey()).append("=").append(shown).append("\n");
        }
        room.post(Message.system(sb.toString().stripTrailing()));
    }

    private void handleRename(String rest) {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            room.post(Message.system("Usage: /rename <agentId> <new name>\n\n"
                    + "Change an agent's display name. The @handle (id) stays the same.\n"
                    + "Example: /rename claude 东大一哥"));
            return;
        }
        Agent agent = config.findAgent(parts[0]).orElse(null);
        if (agent == null) {
            room.post(Message.system("No such agent: " + parts[0]));
            return;
        }
        String old = agent.name;
        agent.name = parts[1].trim();
        store.save();
        room.broadcastRoster();
        room.post(Message.system("Renamed " + old + " to " + agent.name));
    }

    private void handleAdd(String rest) {
        String[] parts = rest.split("\\s+", 3);
        if (parts.length < 2) {
            room.post(Message.system("Usage: /add <id> <provider> [name]\n\n"
                    + "Add a new AI member to the chat.\n"
                    + "  <id>       — unique @mention handle, e.g. \"claude\", \"gpt\"\n"
                    + "  <provider> — AI backend type:\n"
                    + "               anthropic — Claude / Anthropic Messages API\n"
                    + "               openai    — GPT / DeepSeek / any OpenAI-compatible API\n"
                    + "               cli       — local CLI subprocess (e.g. auggie, ollama)\n"
                    + "  [name]     — optional display name (defaults to capitalized id)\n\n"
                    + "Examples:\n"
                    + "  /add claude anthropic Claude\n"
                    + "  /add deepseek openai DeepSeek\n"
                    + "  /add auggie cli"));
            return;
        }
        String id = parts[0].toLowerCase();
        if (config.findAgent(id).isPresent()) {
            room.post(Message.system("Agent already exists: " + id));
            return;
        }
        String provider = parts[1].toLowerCase();
        String name = parts.length > 2 ? parts[2].trim() : Character.toUpperCase(id.charAt(0)) + id.substring(1);
        config.agents.add(new Agent(id, name, provider));
        store.save();
        room.broadcastRoster();
        room.post(Message.system("Added @" + id + " (" + name + ", provider=" + provider
                + "). Configure it with /config " + id + " ..."));
    }

    private void handleRemove(String rest) {
        String id = rest.trim();
        if (id.isBlank()) {
            room.post(Message.system("Usage: /remove <agentId>\n\n"
                    + "Permanently remove an agent and forget its usage data.\n"
                    + "Example: /remove codex"));
            return;
        }
        Agent agent = config.findAgent(id).orElse(null);
        if (agent == null) {
            room.post(Message.system("No such agent: \"" + id + "\". Use /agents to see the current roster."));
            return;
        }
        config.agents.remove(agent);
        usage.remove(agent.id);
        store.save();
        room.broadcastRoster();
        room.broadcastStatus();
        room.post(Message.system("Removed @" + agent.id));
    }

    private void handleFree(String rest) {
        String[] parts = rest.split("\\s+", 2);
        if (parts.length < 2 || parts[1].isBlank()) {
            room.post(Message.system("Usage: /free <agentId> on|off   (or /free all on|off)\n\n"
                    + "Toggle free mode for one agent or all agents.\n"
                    + "When an agent's free mode is ON, it uses freeModel/freeBaseUrl/freeCommand\n"
                    + "instead of the regular paid model/config.\n\n"
                    + "Examples:\n"
                    + "  /free claude on      — Claude uses its free model\n"
                    + "  /free codex off       — Codex uses its regular (paid) model\n"
                    + "  /free all on          — all agents switch to free mode\n"
                    + "  /free all off         — all agents switch to regular mode\n\n"
                    + "Use /agents to see each agent's current free mode status."));
            return;
        }
        String id = parts[0].toLowerCase();
        String v = parts[1].trim().toLowerCase();
        boolean on;
        if (v.equals("on") || v.equals("true")) {
            on = true;
        } else if (v.equals("off") || v.equals("false")) {
            on = false;
        } else {
            room.post(Message.system("Invalid value: " + v + ". Use on or off."));
            return;
        }

        if (id.equals("all")) {
            for (Agent a : config.agents) {
                a.freeMode = on;
            }
            store.save();
            room.broadcastRoster();
            room.post(Message.system("Free mode " + (on ? "ON" : "OFF") + " for all agents."));
        } else {
            Agent agent = config.findAgent(id).orElse(null);
            if (agent == null) {
                room.post(Message.system("No such agent: \"" + id + "\". Use /agents to see the roster."));
                return;
            }
            agent.freeMode = on;
            store.save();
            room.broadcastRoster();
            room.post(Message.system(agent.name + " free mode is now " + (on ? "ON" : "OFF") + "."));
        }
    }

    private String tokensText() {
        StringBuilder sb = new StringBuilder("Token usage:\n");
        for (TokenStatus s : usage.snapshot()) {
            sb.append("- ").append(s.name).append(": ");
            if (s.percent != null) {
                sb.append(s.percent).append("% (")
                  .append(s.remaining).append("/").append(s.limit).append(" ")
                  .append(s.unit != null ? s.unit : "").append(")");
            } else {
                sb.append(s.note != null ? s.note : "N/A");
            }
            sb.append("\n");
        }
        return sb.toString();
    }

    private void handleSynthesizer(String rest) {
        String id = rest.trim();
        if (id.isBlank()) {
            room.post(Message.system("Usage: /synthesizer <agentId>\n\n"
                    + "Set which agent produces the final consolidated answer during @all.\n"
                    + "The synthesizer reviews every other agent's answer and produces a\n"
                    + "single synthesized response. Current: " + config.synthesizerId + "\n\n"
                    + "Example: /synthesizer claude"));
            return;
        }
        if (config.findAgent(id).isEmpty()) {
            room.post(Message.system("No such agent: \"" + id + "\". Use /agents to see available agents."));
            return;
        }
        config.synthesizerId = id;
        store.save();
        room.broadcastRoster();
        room.post(Message.system("Synthesizer set to " + id));
    }

    private void handleMerge(String rest) {
        if (contextMdPath == null) {
            room.post(Message.system("This session has no context MD file. "
                    + "Open a session from a .md file to enable /merge.\n\n"
                    + "Usage: /merge <summary of what to save>\n"
                    + "       /merge compress\n\n"
                    + "/merge <summary> appends key chat findings to the context MD.\n"
                    + "/merge compress auto-merges recent chat, then compresses the entire context."));
            return;
        }
        if (rest.isBlank()) {
            room.post(Message.system("Usage: /merge <summary of what to save>\n"
                    + "       /merge compress\n\n"
                    + "/merge <summary> — appends your summary to the context file: "
                    + contextMdPath + "\n\n"
                    + "/merge compress — auto-merges recent messages into the context, "
                    + "then asks the synthesizer to produce a condensed version of the entire file."));
            return;
        }

        if (rest.equalsIgnoreCase("compress")) {
            handleMergeCompress();
            return;
        }

        try {
            if (onMerge == null) {
                room.post(Message.system("Merge not available: no SessionManager callback registered."));
                return;
            }
            room.post(Message.system("Merging summary into context: "
                    + contextMdPath.getFileName()));
            onMerge.accept(rest, List.of());
        } catch (Exception e) {
            room.post(Message.system("Merge failed: " + e.getMessage()));
        }
    }

    // ── /merge compress ─────────────────────────────────────────────────

    private void handleMergeCompress() {
        if (onMerge == null) {
            room.post(Message.system("Merge not available: no SessionManager callback registered."));
            return;
        }
        if (registry == null) {
            room.post(Message.system("Compression not available: no provider registry."));
            return;
        }

        // Run compression in background so we don't block the WebSocket thread.
        new Thread(() -> {
            try {
                // ── Step 1: auto-merge recent messages into context ──
                room.post(Message.system("⏳ Merging recent chat into context..."));
                List<Message> recent = getRecentNonSystemMessages(30);
                onMerge.accept("Auto-merged by /merge compress", recent);

                // ── Step 2: snapshot original before compression ──
                String original = Files.readString(contextMdPath, StandardCharsets.UTF_8);
                long originalLines = original.lines().count();

                // ── Step 3: compress context via synthesizer ──
                String synthId = config.synthesizerId;
                Agent synth = config.findAgent(synthId).orElse(null);
                if (synth == null || !registry.has(synth.provider)) {
                    room.post(Message.system("❌ Synthesizer '" + synthId
                            + "' not available for compression."));
                    return;
                }
                room.post(Message.system("⏳ Compressing context with " + synth.name
                        + " (" + originalLines + " lines)..."));
                String prompt = """
                        You are maintaining a project context file. The "# Context Document" \
                        above is the current project context.

                        Your task: produce a CLEAN, CONDENSED version. This is NOT a summary — \
                        it must remain a COMPLETE technical reference that another developer or \
                        AI can use to understand the project without reading the original.

                        CRITICAL RULES — violating any of these means the compression is WRONG:
                        1. Keep EVERY completed fix: each bug's symptom, root cause, fix, and \
                        affected files MUST be preserved — just use concise tables/bullets.
                        2. Keep EVERY design decision and technical detail: SQL before/after, \
                        code snippets showing the fix, call chains, and file paths.
                        3. Keep ALL file structure listings and command references.
                        4. Keep ALL remaining TODOs and their context.
                        5. Only remove: duplicate paragraphs, chat meta-commentary (e.g. "Let me \
                        read the file...", "Now I understand..."), and greeting/farewell fluff.
                        6. If a section contains real technical content, KEEP IT. When in doubt, \
                        keep it — a slightly verbose reference is infinitely better than losing \
                        critical bug-fix context.
                        7. Preserve the overall structure: overview → fixes → design details → \
                        file structure → todos → commands.
                        8. Include "_Last updated_" date at the top.
                        9. Ignore the chat transcript after the "---" separator — only compress \
                        the "# Context Document" section.

                        TARGET: the output should be roughly 60-80% of the original size, not \
                        drastically shorter. If the original is 200 lines, aim for 120-160 lines. \
                        Do NOT collapse bug fixes into one-liners — each fix entry needs \
                        symptoms + root cause + fix + files.

                        OUTPUT ONLY the condensed markdown. No preamble, no explanations. \
                        Start directly with the markdown content.""";
                AgentResponse resp = registry.get(synth.provider).orElseThrow()
                        .generate(new AgentRequest(synth, room.renderContext(), prompt,
                                false, delta -> {}));

                if (!resp.ok || resp.content == null || resp.content.isBlank()) {
                    room.post(Message.system("❌ Compression failed: "
                            + (resp.error != null ? resp.error : "no output from synthesizer")));
                    return;
                }

                // ── Step 4: validate compressed output ──
                String compressed = extractMarkdown(resp.content);
                long compressedLines = compressed.lines().count();
                double ratio = originalLines > 0 ? (double) compressedLines / originalLines : 0;

                // Safety: if compressed output is <40% of original or <20 lines, it's probably
                // over-compressed. Save to a preview file instead and warn the user.
                boolean tooShort = compressedLines < 20 || ratio < 0.4;
                if (tooShort) {
                    // Create backup of original before any writes
                    Path backupPath = contextMdPath.resolveSibling(
                            contextMdPath.getFileName() + ".bak");
                    Files.writeString(backupPath, original, StandardCharsets.UTF_8);

                    // Save compressed preview alongside (don't overwrite original)
                    Path previewPath = contextMdPath.resolveSibling(
                            contextMdPath.getFileName() + ".compressed-preview");
                    Files.writeString(previewPath, compressed, StandardCharsets.UTF_8);

                    room.post(Message.system(
                            "⚠️  Compression result is suspiciously short ("
                            + compressedLines + " lines vs " + originalLines
                            + " original, " + Math.round(ratio * 100) + "%).\n\n"
                            + "The original file was NOT overwritten.\n"
                            + "• Preview saved to: " + previewPath.getFileName() + "\n"
                            + "• Backup saved to: " + backupPath.getFileName() + "\n\n"
                            + "Review the preview. If it's acceptable, run:\n"
                            + "  cp " + previewPath.getFileName() + " "
                            + contextMdPath.getFileName() + "\n"
                            + "To discard and keep the original, delete both .bak and "
                            + ".compressed-preview files."));
                    return;
                }

                // ── Step 5: create backup, then write ──
                Path backupPath = contextMdPath.resolveSibling(
                        contextMdPath.getFileName() + ".bak");
                Files.writeString(backupPath, original, StandardCharsets.UTF_8);
                Files.writeString(contextMdPath, compressed, StandardCharsets.UTF_8);

                // ── Step 6: reload context in room ──
                room.setContextContent(compressed);

                room.post(Message.system("✅ Context compressed. File: "
                        + contextMdPath.getFileName()
                        + " (" + compressedLines + " lines, "
                        + (compressed.length() / 1024) + " KB, "
                        + Math.round(ratio * 100) + "% of original)\n"
                        + "Backup: " + backupPath.getFileName()
                        + " (auto-delete after verifying)"));
            } catch (Exception e) {
                room.post(Message.system("❌ Compression error: " + e.getMessage()));
            }
        }, "merge-compress").start();
    }

    /** Collect the last {@code count} non-system messages from history. */
    private List<Message> getRecentNonSystemMessages(int count) {
        List<Message> all = room.history();
        List<Message> recent = new ArrayList<>();
        for (int i = all.size() - 1; i >= 0 && recent.size() < count; i--) {
            Message m = all.get(i);
            if (m.type != MessageType.SYSTEM) {
                recent.add(0, m);  // prepend to preserve chronological order
            }
        }
        return recent;
    }

    /** Extract clean markdown from a synthesizer response that may contain conversational wrapper text. */
    private static String extractMarkdown(String content) {
        // If the response wraps the markdown in a code fence, extract it.
        Pattern fence = Pattern.compile("```(?:markdown|md)?\\s*\\n(.*?)\\n```", Pattern.DOTALL);
        Matcher m = fence.matcher(content);
        if (m.find()) {
            return m.group(1).strip();
        }
        // Strip common preamble phrases like "Here is the condensed version:"
        String stripped = content
                .replaceAll("(?i)^(here('s| is)|this is|below is) (the |a )?"
                        + "(condensed|compressed|cleaned|updated|revised) .*?\\n", "")
                .replaceAll("(?i)^(sure|okay|here you go|done)[.!]?\\s*\\n+", "");
        return stripped.strip();
    }

    /**
     * Callback for executing the actual file merge. Set by SessionManager when
     * creating the CommandHandler for a session with a context MD file.
     * Null means merge is not available.
     */
    private java.util.function.BiConsumer<String, List<Message>> onMerge;

    public void setOnMerge(java.util.function.BiConsumer<String, List<Message>> onMerge) {
        this.onMerge = onMerge;
    }

    private void handleDevice(String rest) {
        if (devices == null) {
            room.post(Message.system("Device registry is not available in this session."));
            return;
        }
        String[] parts = rest.split("\\s+", 2);
        String sub = parts.length > 0 ? parts[0].toLowerCase() : "";
        String arg = parts.length > 1 ? parts[1].strip() : "";
        switch (sub) {
            case "pair" -> {
                DeviceRegistry.PairingCode pair = devices.createPairingCode(arg);
                room.post(Message.system("Pairing code for " + pair.name() + ": "
                        + pair.code() + "\n\n"
                        + "Open the remote browser, enter this code in the access prompt, "
                        + "and it will be trusted until you revoke it. Expires at "
                        + pair.expiresAt() + "."));
            }
            case "list" -> room.post(Message.system(deviceListText()));
            case "revoke" -> {
                if (arg.isBlank()) {
                    room.post(Message.system("Usage: /device revoke <deviceId|name>"));
                    return;
                }
                boolean removed = devices.revoke(arg);
                room.post(Message.system(removed ? "Revoked device: " + arg : "No matching device: " + arg));
            }
            case "status" -> room.post(Message.system(devices.statusText()));
            default -> room.post(Message.system("""
                    Usage:
                      /device pair <name>       Generate a one-time code for a phone/browser
                      /device list              List trusted devices
                      /device revoke <id|name>  Remove a trusted device
                      /device status            Show access-control status
                    """.stripTrailing()));
        }
    }

    private void handleAccess(String rest) {
        if (devices == null) {
            room.post(Message.system("Access control is not available in this session."));
            return;
        }
        String sub = rest.trim().toLowerCase();
        switch (sub) {
            case "on" -> {
                devices.setEnabled(true);
                room.post(Message.system("Access control is ON. Localhost remains allowed; remote browsers need a trusted device token."));
            }
            case "off" -> {
                devices.setEnabled(false);
                room.post(Message.system("Access control is OFF. Remote clients can access the app without a device token."));
            }
            case "status", "" -> room.post(Message.system(devices.statusText()));
            default -> room.post(Message.system("""
                    Usage:
                      /access status
                      /access on
                      /access off
                    """.stripTrailing()));
        }
    }

    private void handleModelList(String rest) {
        String id = rest.trim();
        if (id.isBlank()) {
            room.post(Message.system("Usage: /modellist <agentId>\n\n"
                    + "Query the agent's configured API endpoint for available models.\n"
                    + "Example: /modellist claude"));
            return;
        }
        Agent agent = config.findAgent(id).orElse(null);
        if (agent == null) {
            room.post(Message.system("No such agent: " + id));
            return;
        }

        // Special handling for Auggie: use `auggie model list` command
        if ("auggie".equalsIgnoreCase(agent.id)) {
            handleAuggieModelList(agent);
            return;
        }

        // Resolve base URL and API key according to provider type.
        String baseUrl = null;
        String apiKey = null;
        String provider = agent.provider != null ? agent.provider.toLowerCase() : "";

        if ("openai".equals(provider) || "anthropic".equals(provider)) {
            baseUrl = agent.baseUrl;
            apiKey = agent.apiKey;
        } else if ("cli".equals(provider)) {
            // Try to infer API endpoint from environment variables.
            if (agent.env != null) {
                baseUrl = agent.env.get("ANTHROPIC_BASE_URL");
                if (baseUrl == null) baseUrl = agent.env.get("OPENAI_BASE_URL");
                apiKey = agent.env.get("ANTHROPIC_AUTH_TOKEN");
                if (apiKey == null) apiKey = agent.env.get("OPENAI_API_KEY");
                if (apiKey == null) apiKey = agent.env.get("CODEX_API_KEY");
            }
        }

        if (apiKey == null || apiKey.isBlank()) {
            room.post(Message.system("No API key configured for " + agent.name
                    + ". Set one with /config " + agent.id + " apiKey=..."));
            return;
        }

        // Collect URLs to try. For DeepSeek's Anthropic proxy the /anthropic/v1/models
        // endpoint returns empty, so we also try the parent (OpenAI-compatible) URL.
        List<String> urls = new ArrayList<>();
        if (baseUrl != null && !baseUrl.isBlank()) {
            String stripped = baseUrl.replaceAll("/+$", "");
            urls.add(stripped + "/v1/models");
            // If the base URL ends with /anthropic, also try the parent.
            if (stripped.endsWith("/anthropic")) {
                urls.add(stripped.replaceAll("/anthropic$", "") + "/v1/models");
            }
        } else {
            // Fall back to well-known defaults.
            urls.add("https://api.openai.com/v1/models");
            urls.add("https://api.anthropic.com/v1/models");
        }

        List<String> models = null;
        String lastError = null;

        for (String url : urls) {
            try {
                var builder = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .timeout(Duration.ofSeconds(15))
                        .GET();

                // Try Anthropic-style auth first for /anthropic URLs, else Bearer.
                if (url.contains("/anthropic")) {
                    builder.header("x-api-key", apiKey);
                    builder.header("anthropic-version", "2023-06-01");
                } else {
                    builder.header("authorization", "Bearer " + apiKey);
                }

                HttpResponse<String> resp = HttpClient.newHttpClient()
                        .send(builder.build(), HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() / 100 != 2) {
                    lastError = "HTTP " + resp.statusCode() + " from " + url;
                    continue;
                }

                var root = Json.read(resp.body());
                var data = root.get("data");
                if (data != null && data.isArray() && data.size() > 0) {
                    models = new ArrayList<>();
                    for (var node : data) {
                        String modelId = node.has("id") ? node.get("id").asText(null) : null;
                        if (modelId != null && !modelId.isBlank()) {
                            models.add(modelId);
                        }
                    }
                    if (!models.isEmpty()) break; // got results, stop trying
                } else {
                    lastError = "empty model list from " + url;
                }
            } catch (Exception e) {
                lastError = url + ": " + e.getMessage();
            }
        }

        if (models == null || models.isEmpty()) {
            room.post(Message.system("No models found for " + agent.name
                    + (lastError != null ? " (" + lastError + ")" : "")));
            return;
        }

        StringBuilder sb = new StringBuilder("Available models for ");
        sb.append(agent.name).append(":\n");
        for (String m : models) {
            sb.append("  • ").append(m);
            // Highlight the currently configured model.
            if (m.equals(agent.model)
                    || (agent.env != null && m.equals(agent.env.get("ANTHROPIC_MODEL")))) {
                sb.append("  ← current");
            }
            sb.append("\n");
        }
        room.post(Message.system(sb.toString().stripTrailing()));
    }

    private void handleAuggieModelList(Agent agent) {
        try {
            Process p = new ProcessBuilder("auggie", "model", "list")
                    .redirectErrorStream(true)
                    .start();

            StringBuilder output = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    output.append(line).append("\n");
                }
            }

            boolean completed = p.waitFor(30, TimeUnit.SECONDS);
            if (!completed) {
                p.destroyForcibly();
                room.post(Message.system("Auggie model list command timed out after 30s"));
                return;
            }

            if (p.exitValue() != 0) {
                room.post(Message.system("Auggie model list failed:\n" + output.toString()));
                return;
            }

            // Parse the output and highlight the current model
            String result = output.toString();
            String currentModel = agent.model;

            if (currentModel != null && !currentModel.isBlank()) {
                // Highlight the current model by adding "← current" marker
                String[] lines = result.split("\n");
                StringBuilder highlighted = new StringBuilder();
                for (String line : lines) {
                    highlighted.append(line);
                    // Check if this line contains the current model ID in brackets
                    if (line.contains("[" + currentModel.toLowerCase() + "]")
                        || line.contains("[" + currentModel + "]")) {
                        highlighted.append("  ← current");
                    }
                    highlighted.append("\n");
                }
                result = highlighted.toString();
            }

            room.post(Message.system(result.stripTrailing()));

        } catch (Exception e) {
            room.post(Message.system("Failed to query Auggie models: " + e.getMessage()));
        }
    }

    private String deviceListText() {
        List<TrustedDevice> list = devices.devices();
        if (list.isEmpty()) {
            return "No trusted devices yet. Use /device pair <name> to register your phone/browser.";
        }
        StringBuilder sb = new StringBuilder("Trusted devices:\n");
        for (TrustedDevice d : list) {
            sb.append("- ").append(d.name != null ? d.name : "(unnamed)")
              .append(" id=").append(d.id)
              .append(" enabled=").append(d.enabled)
              .append(" role=").append(d.role != null ? d.role : "user")
              .append(" lastIp=").append(d.lastSeenIp != null ? d.lastSeenIp : "-")
              .append(" lastSeen=").append(d.lastSeenAt != null ? d.lastSeenAt : "-")
              .append("\n");
        }
        return sb.toString().stripTrailing();
    }

    private String agentsText() {
        StringBuilder sb = new StringBuilder("Agents:\n");
        for (Agent a : config.agents) {
            sb.append("- @").append(a.id).append(" (").append(a.name).append(") ")
              .append("provider=").append(a.provider)
              .append(a.model != null ? " model=" + a.model : "")
              .append(" enabled=").append(a.enabled)
              .append(" freeMode=").append(a.freeMode)
              .append(" ready=").append(a.isReady()).append("\n");
        }
        sb.append("Synthesizer: ").append(config.synthesizerId);
        return sb.toString();
    }

    private String helpText() {
        return """
                ═══════════════════════════════════════════════
                AI Group Chat — Command Reference
                ═══════════════════════════════════════════════

                📖 GENERAL

                  /help
                    Show this detailed command reference.

                  /agents
                    List all configured agents with their provider, model, enabled
                    status, and whether they are ready to respond (apiKey + model set).
                    Also shows the current @all synthesizer.

                  /tokens
                    Show per-agent token usage and rate-limit window from the last
                    API response. N/A means no usage data yet (agent hasn't replied).

                ───────────────────────────────────────────────
                ⚙️ CONFIGURATION

                  /config <agentId> <key>=<value>
                    Set a single configuration field for an agent. Changes take
                    effect immediately and are persisted to disk.

                    Available keys (case-insensitive):
                      apiKey          API key / secret (stored locally, masked in logs)
                      model           Model name, e.g. "deepseek-v4-pro", "gpt-4o"
                      name            Display name shown in the sidebar and messages
                      provider        "anthropic", "openai", or "cli"
                      baseUrl         Override the API base URL
                                      (use https://api.deepseek.com for DeepSeek models)
                      systemPrompt    Persona / instructions prepended to every request
                      freeModel       Model used when free mode is on
                      freeBaseUrl     Base URL used when free mode is on
                      command         CLI command, e.g. "auggie --print"
                      cwd             Working directory for CLI subprocesses — set this to
                                      the project the agent should read (tools like Claude
                                      Code / auggie restrict access to their current dir)
                      freeCommand     CLI command when free mode is on
                      installCommand  Shell command to install the CLI tool
                      enabled         "true" or "false" — control @all participation
                      freeMode        "true" or "false" — use free model/command for this agent
                      timeoutSeconds  Max seconds a CLI agent may run before being killed
                                      (default 900). Raise it for agentic CLIs like Claude
                                      Code that run many tools / sub-agents. Empty resets.
                      env.<NAME>      Environment variable for CLI subprocesses; lets a
                                      tool like Claude Code run headless against a gateway
                                      via ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN, etc.
                                      An empty value removes the variable. Secret-looking
                                      values (token/key/secret/auth) are masked in output.

                    Examples:
                      /config claude apiKey=sk-abc123
                      /config claude baseUrl=https://api.deepseek.com
                      /config claude model=deepseek-v4-pro
                      /config codex enabled=false
                      /config auggie command=auggie --print
                      /config claudecli command=claude -p
                      /config claudecli env.ANTHROPIC_BASE_URL=https://api.deepseek.com/anthropic
                      /config claudecli env.ANTHROPIC_AUTH_TOKEN=sk-...
                      /config claudecli env.ANTHROPIC_MODEL=deepseek-v4-pro
                      /config claudecli cwd=/Users/me/IdeaProjects/tapdata-connectors

                  /modellist <agentId>
                    Query the agent's configured API endpoint for available
                    models. Displays all model IDs the API key has access to.
                    The agent's currently configured model is marked with ←.
                    Example: /modellist claudecli

                  /env <agentId>
                    List the environment variables set for an agent's CLI
                    subprocess. Secret-looking values (token/key/secret/auth)
                    are masked; others are shown so you can verify them.
                    Example: /env claudecli

                  /rename <agentId> <new name>
                    Change an agent's display name without touching other settings.
                    Example: /rename claude 东大一哥

                ───────────────────────────────────────────────
                👥 MEMBERS

                  /add <id> <provider> [name]
                    Add a new AI member. Choose the right provider for the API:

                    anthropic — Claude / Anthropic Messages API
                                (key starts with "sk-ant-", endpoint api.anthropic.com)
                    openai    — GPT / DeepSeek / any OpenAI-compatible API
                                (key starts with "sk-"; set baseUrl for non-OpenAI)
                    cli       — Local subprocess (auggie, ollama, llama-cli, etc.)

                    Examples:
                      /add claude anthropic Claude
                      /add deepseek openai DeepSeek
                      /add auggie cli

                    After adding, configure with /config <id> apiKey=... etc.

                  /remove <agentId>
                    Permanently remove an agent and forget its usage data.
                    Example: /remove codex

                ───────────────────────────────────────────────
                🎯 SYNTHESIS & MODES

                  /synthesizer <agentId>
                    Set which agent produces the final consolidated answer during
                    @all. The synthesizer sees every other agent's answer and
                    produces a single reviewed response.
                    Example: /synthesizer claude

                  /free <agentId> on|off    (or /free all on|off)
                    Toggle free mode for one agent or all agents at once.
                    When an agent's free mode is ON, it uses freeModel /
                    freeBaseUrl / freeCommand instead of the regular ones.
                    Examples: /free claude on, /free all off

                ───────────────────────────────────────────────
                🔧 MAINTENANCE (owner mode only — running from source)

                  /optimize <natural language requirement>
                    Ask the maintainer CLI agent to edit this project's source
                    code, rebuild with Maven, and restart the service. The agent
                    works in the source tree and can modify any file.
                    Example: /optimize add a /clear command that resets chat

                  /restart
                    Rebuild (mvn package) and restart the service in-place.
                    The browser will reconnect automatically.

                  /install [agentId]
                    Run the installCommand for one agent or all agents that have
                    one configured. Runs silently in background at startup too.
                    Example: /install auggie

                  /merge <summary>
                    Append a concise summary to the session's context MD file.
                    This lets you save key decisions/findings
                    so future AI sessions can reference them without re-reading
                    the full history. Only available for sessions opened from a
                    .md file.
                    Example: /merge decided to use PostgreSQL for the user service

                  /merge compress
                    Auto-merge recent chat messages into the context MD file,
                    then ask the synthesizer agent to read the entire context
                    and produce a condensed version. The original is replaced
                    with the condensed output. Only available for sessions
                    opened from a .md file.
                    Example: /merge compress

                ───────────────────────────────────────────────
                🔐 REMOTE ACCESS

                  /device pair <name>
                    Generate a one-time pairing code for a phone or another
                    browser. Open the remote page, enter the code, and the
                    browser receives a trusted device token.

                  /device list
                    List trusted devices, their ids, last seen IPs, and roles.

                  /device revoke <id|name>
                    Remove a trusted device token. Use this if a phone is lost.

                  /access status
                    Show whether access control is on and how many devices are
                    trusted or waiting to pair.

                  /access on|off
                    Enable or disable device-token access control. Localhost is
                    always allowed by default.

                ───────────────────────────────────────────────
                💬 MENTIONS

                  @<agentId>      Ask a single agent. e.g. @claude hello
                  @all            Ask all enabled agents in parallel, then one
                                  synthesizer reviews their answers and produces
                                  a consolidated final response.

                Pro tip: type @ to see an autocomplete list of available agents.
                """;
    }
}
