package io.groupchat.provider;

import io.groupchat.model.Agent;
import io.groupchat.util.Json;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Provider that drives a local CLI tool (e.g. auggie, claude, codex CLIs) as a
 * subprocess. The composed prompt is written to the process stdin and the
 * reply is read from stdout.
 *
 * <p>The agent's {@code command} is a whitespace separated command line, e.g.
 * {@code "auggie --print"}. Quote-aware parsing keeps simple quoted args intact.
 */
public class CliProvider implements AgentProvider {

    /**
     * Default max runtime for a CLI call when the agent does not override it.
     * Agentic CLIs (Claude Code running tools / sub-agents) routinely exceed a
     * few minutes, so this is generous; override per agent via timeoutSeconds.
     */
    private static final int DEFAULT_TIMEOUT_SECONDS = 900;

    @Override
    public String type() {
        return "cli";
    }

    @Override
    public boolean supportsFreeMode(Agent agent) {
        // A CLI tool still spends the user's quota unless it is explicitly pointed
        // at a free option, so it only counts as free-mode-capable when a separate
        // freeCommand is configured.
        return agent != null && agent.freeCommand != null && !agent.freeCommand.isBlank();
    }

    @Override
    public AgentResponse generate(AgentRequest request) {
        Agent agent = request.agent;
        String commandLine = request.effectiveCommand();
        if (commandLine == null || commandLine.isBlank()) {
            return AgentResponse.error("Missing command for cli agent " + agent.id);
        }
        try {
            List<String> cmd = parseCommand(commandLine);
            // Force line-buffered stdout where it is safe to do so (Linux: stdbuf) so
            // streaming output arrives promptly. We deliberately avoid PTY wrappers,
            // which echo stdin, inject terminal control codes, and make CLIs like
            // Claude Code switch to full TUI output — corrupting the captured stream
            // and breaking stdin delivery.
            List<String> lineBufferedCmd = wrapForLineBuffering(cmd);
            ProcessBuilder pb = new ProcessBuilder(lineBufferedCmd);
            // Run the subprocess in the agent's configured working directory so tools
            // like Claude Code / auggie (which operate on and restrict access to their
            // current directory) can read the intended project. Defaults to the launch dir.
            if (agent.cwd != null && !agent.cwd.isBlank()) {
                java.io.File dir = new java.io.File(agent.cwd);
                if (!dir.isDirectory()) {
                    return AgentResponse.error("cwd is not a directory for cli agent "
                            + agent.id + ": " + agent.cwd);
                }
                pb.directory(dir);
            }
            // Merge the agent's configured env on top of the inherited environment so
            // tools like Claude Code can authenticate against a gateway (e.g. DeepSeek's
            // Anthropic endpoint) headlessly via ANTHROPIC_BASE_URL / ANTHROPIC_AUTH_TOKEN.
            if (agent.env != null && !agent.env.isEmpty()) {
                pb.environment().putAll(agent.env);
            }
            Process process = pb.start();

            // Drain stderr on a daemon thread to prevent deadlock: without this,
            // a chatty CLI that fills the OS stderr pipe buffer (~64KB) would block
            // on write(stderr) while we block on read(stdout) — mutual deadlock.
            StringBuilder stderrBuf = new StringBuilder();
            Thread stderrThread = new Thread(() -> {
                try (BufferedReader reader = process.errorReader()) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        stderrBuf.append(line).append('\n');
                    }
                } catch (IOException ignored) {
                }
            }, "cli-stderr-" + agent.id);
            stderrThread.setDaemon(true);
            stderrThread.start();

            String input = request.composedUserContent();
            try (OutputStream os = process.getOutputStream()) {
                os.write(input.getBytes(StandardCharsets.UTF_8));
                os.flush();
            }

            int timeoutSec = (agent.timeoutSeconds != null && agent.timeoutSeconds > 0)
                    ? agent.timeoutSeconds : DEFAULT_TIMEOUT_SECONDS;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() -> {
                try {
                    return streamStdout(process, request);
                } catch (Exception e) {
                    throw new CompletionException(e);
                }
            });

            boolean finished = process.waitFor(timeoutSec, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
                stdoutFuture.cancel(true);
                stderrThread.join(2000);
                return AgentResponse.error("CLI agent " + agent.id + " timed out after "
                        + timeoutSec + "s (raise with /config " + agent.id + " timeoutSeconds=...)");
            }
            String stdout;
            try {
                stdout = stdoutFuture.get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
                stderrThread.join(2000);
                return AgentResponse.error("CLI agent " + agent.id
                        + " exited but stdout did not close");
            } catch (ExecutionException e) {
                stderrThread.join(2000);
                return AgentResponse.error("CLI stdout read failed: "
                        + rootMessage(e.getCause()));
            }
            stderrThread.join(5000);
            int exit = process.exitValue();
            String text = stdout.trim();
            if (exit != 0) {
                String stderr = stderrBuf.toString().trim();
                return AgentResponse.error("CLI exit " + exit + ": "
                        + exitErrorDetail(stderr, text));
            }
            if (text.isEmpty()) {
                String stderr = stderrBuf.toString().trim();
                if (!stderr.isEmpty()) {
                    return AgentResponse.error("CLI agent " + agent.id
                            + " produced no stdout. stderr: " + stderr);
                }
                return AgentResponse.error("CLI agent " + agent.id + " produced no output");
            }
            return AgentResponse.ok(text);
        } catch (Exception e) {
            return AgentResponse.error("CLI call failed: " + e.getMessage());
        }
    }

    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur instanceof CompletionException && cur.getCause() != null) {
            cur = cur.getCause();
        }
        String msg = cur == null ? null : cur.getMessage();
        return msg == null || msg.isBlank() ? String.valueOf(cur) : msg;
    }

    private static String exitErrorDetail(String stderr, String stdoutText) {
        String out = stdoutText == null ? "" : stdoutText.trim();
        String err = stderr == null ? "" : stderr.trim();

        if (isSpecificCodexFailure(out) && (err.isEmpty() || isGenericCodexStderr(err))) {
            return out;
        }
        if (err.isEmpty()) {
            return out;
        }
        if (out.isEmpty() || err.contains(out)) {
            return err;
        }
        return err + "\n" + out;
    }

    private static boolean isSpecificCodexFailure(String text) {
        return text != null && text.startsWith("Codex CLI failed: ");
    }

    private static boolean isGenericCodexStderr(String stderr) {
        if (stderr == null || stderr.isBlank()) {
            return true;
        }
        String normalized = stderr.replaceAll("(?m)^WARNING:.*(?:\\R|$)", "").trim();
        return normalized.isEmpty() || normalized.equals("Reading prompt from stdin...");
    }

    /**
     * Matches ANSI escape sequences: CSI (ESC [ ... letter, including private
     * "?"-prefixed params like ESC[?2004l), OSC, and other ESC sequences.
     */
    private static final Pattern ANSI_PATTERN = Pattern.compile(
            "\\u001B\\[[0-9;?]*[a-zA-Z]|\\u001B\\][0-9][^\\u0007]*\\u0007|\\u001B[^\\[\\]].");

    /**
     * Read the process stdout line by line. Every line has ANSI escapes stripped.
     * When the agent is non-verbose, tool-call blocks are collapsed into summary
     * lines; natural language still streams so the bubble grows incrementally.
     * The full raw output is accumulated and returned as the canonical reply.
     */
    private static String streamStdout(Process process, AgentRequest request) throws Exception {
        if (isClaudeStreamJson(request.effectiveCommand())) {
            return streamClaudeJson(process, request);
        }
        if (isCodexJson(request.effectiveCommand())) {
            return streamCodexJson(process, request);
        }
        boolean filterTools = !request.agent.verbose;
        StringBuilder rawBuf = new StringBuilder();
        ToolBlock currentBlock = null;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rawBuf.append(line).append('\n');
                String clean = stripAnsi(line);

                if (!filterTools) {
                    request.emit(clean + "\n");
                } else {
                    currentBlock = filterLine(clean, currentBlock, request);
                }
            }
            // Flush any open tool block
            if (filterTools && currentBlock != null) {
                emitToolSummary(currentBlock, request);
            }
        }
        return rawBuf.toString();
    }

    /** Strip ANSI escape sequences from a string. */
    static String stripAnsi(String s) {
        if (s == null) return null;
        return ANSI_PATTERN.matcher(s).replaceAll("");
    }

    // ── Claude Code stream-json (JSONL event stream) ───────────────

    /**
     * True when the command opts into Claude Code's JSONL event stream
     * ({@code --output-format stream-json}). Plain {@code --print} (text) does
     * not stream at all — Claude computes the whole answer then prints once —
     * so this mode is required for incremental output.
     */
    static boolean isClaudeStreamJson(String commandLine) {
        return commandLine != null && commandLine.contains("stream-json");
    }

    static boolean isCodexJson(String commandLine) {
        return commandLine != null
                && commandLine.contains("codex")
                && commandLine.contains("exec")
                && commandLine.contains("--json");
    }

    /**
     * Parse Claude Code's {@code --output-format stream-json} JSONL events,
     * emitting assistant text as it arrives (token-level when
     * {@code --include-partial-messages} is set) and collapsing tool use into
     * one-line summaries. Lines that are not JSON objects are ignored. The full
     * assistant text is accumulated and returned as the canonical reply,
     * falling back to the final {@code result} event when no text streamed.
     */
    private static String streamClaudeJson(Process process, AgentRequest request) throws Exception {
        StringBuilder canonical = new StringBuilder();
        String resultText = null;
        boolean includePartialMessages = includesPartialMessages(request.effectiveCommand());
        String assistantSnapshot = "";
        // With partial messages enabled, Claude Code may stream either native
        // stream_event deltas or repeated assistant-message snapshots, depending
        // on version/provider behavior. Once native stream_events are seen, the
        // trailing assistant messages merely repeat content and must be ignored.
        // Conversely, when assistant snapshots arrive first (common with
        // DeepSeek-style backends), stream_event text_delta events that follow
        // replay already-emitted text — those must be suppressed too.
        // Snapshot-style assistant messages are handled below by diffing against
        // the previous snapshot.
        boolean sawStreamEvent = false;
        boolean sawAssistantText = false;
        // A tool call streams as: content_block_start (name) → input_json_delta…
        // (the args, e.g. the command or file path) → content_block_stop. We hold
        // each open tool_use block here, keyed by its event "index", and emit one
        // enriched marker (e.g. "[…Bash: mvn clean package]") at stop so the
        // otherwise-silent tool-execution gap shows WHAT is running.
        Map<Integer, PendingTool> pendingTools = new HashMap<>();

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.charAt(0) != '{') {
                    continue;
                }
                JsonNode node;
                try {
                    node = Json.MAPPER.readTree(t);
                } catch (Exception e) {
                    continue;
                }
                String type = node.path("type").asText("");
                if (type.equals("stream_event")) {
                    JsonNode ev = node.path("event");
                    String et = ev.path("type").asText("");
                    int idx = ev.path("index").asInt(-1);
                    if (et.equals("content_block_delta")) {
                        JsonNode delta = ev.path("delta");
                        String dt = delta.path("type").asText("");
                        if (dt.equals("text_delta")) {
                            // When assistant snapshots are already streaming text,
                            // this stream_event text_delta would replay the same
                            // content — skip it. Tool blocks (start/stop) are
                            // still processed below.
                            if (sawAssistantText) {
                                continue;
                            }
                            // Only mark stream_event text as seen when we actually
                            // consume it — tool-call stream_events (content_block_start
                            // / stop) should not suppress assistant text snapshots.
                            sawStreamEvent = true;
                            String text = delta.path("text").asText("");
                            if (!text.isEmpty()) {
                                canonical.append(text);
                                request.emit(text);
                            }
                        } else if (dt.equals("input_json_delta")) {
                            PendingTool pt = pendingTools.get(idx);
                            if (pt != null) {
                                pt.inputJson.append(delta.path("partial_json").asText(""));
                            }
                        }
                    } else if (et.equals("content_block_start")) {
                        JsonNode cb = ev.path("content_block");
                        if (cb.path("type").asText("").equals("tool_use")) {
                            PendingTool pt = new PendingTool(cb.path("name").asText("tool"));
                            JsonNode pre = cb.path("input");
                            if (pre.isObject() && pre.size() > 0) {
                                pt.preInput = pre;
                            }
                            pendingTools.put(idx, pt);
                        }
                    } else if (et.equals("content_block_stop")) {
                        PendingTool pt = pendingTools.remove(idx);
                        if (pt != null) {
                            request.emit(toolMarker(pt.name, pt.resolveInput()));
                        }
                    }
                } else if (type.equals("assistant")) {
                    // Fallback only when partial messages are disabled. If any
                    // stream_event was seen, this is a duplicate — skip it.
                    if (sawStreamEvent) {
                        continue;
                    }
                    if (includePartialMessages) {
                        // Claude Code 2.1.x may emit partial output as repeated
                        // assistant message snapshots rather than stream_event
                        // text_delta events. Treat those as cumulative snapshots
                        // and only stream the new suffix; otherwise each update
                        // repeats the whole current sentence in the chat bubble.
                        String snapshot = assistantSnapshotText(node.path("message"));
                        String delta = snapshotDelta(assistantSnapshot, snapshot);
                        if (!delta.isEmpty()) {
                            request.emit(delta);
                            sawAssistantText = true;
                        }
                        assistantSnapshot = snapshot;
                        canonical.setLength(0);
                        canonical.append(snapshot);
                    } else {
                        for (JsonNode block : node.path("message").path("content")) {
                            String bt = block.path("type").asText("");
                            if (bt.equals("text")) {
                                String text = block.path("text").asText("");
                                if (!text.isEmpty()) {
                                    canonical.append(text);
                                    request.emit(text);
                                }
                            } else if (bt.equals("tool_use")) {
                                request.emit(toolMarker(block.path("name").asText("tool"),
                                        block.path("input")));
                            }
                        }
                    }
                } else if (type.equals("result")) {
                    JsonNode r = node.path("result");
                    if (r.isTextual()) {
                        resultText = r.asText();
                    }
                }
            }
            // Defensive: emit any tool block that never received an explicit stop.
            for (PendingTool pt : pendingTools.values()) {
                request.emit(toolMarker(pt.name, pt.resolveInput()));
            }
        }
        if (canonical.length() > 0) {
            return canonical.toString();
        }
        return resultText != null ? resultText : "";
    }

    /**
     * Parse Codex CLI's {@code codex exec --json} JSONL events. Emits both
     * user-facing {@code event_msg/agent_message} text and activity markers from
     * {@code item.started/completed} events (tool calls, command execution, file
     * changes, etc.) to show real-time progress and prevent perceived timeouts.
     */
    private static String streamCodexJson(Process process, AgentRequest request) throws Exception {
        StringBuilder streamedSnapshot = new StringBuilder();
        String finalMessage = null;
        String lastSnapshot = "";
        String lastError = null;
        String lastTurnError = null;  // track turn.failed separately from info/warning items
        int eventCount = 0;
        int itemCount = 0;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String t = line.trim();
                if (t.isEmpty() || t.charAt(0) != '{') {
                    continue;
                }
                JsonNode node;
                try {
                    node = Json.MAPPER.readTree(t);
                } catch (Exception e) {
                    continue;
                }
                eventCount++;
                String type = node.path("type").asText("");

                // Top-level error event (e.g. "Reconnecting... 1/5")
                if (type.equals("error")) {
                    String message = node.path("message").asText("");
                    if (!message.isEmpty()) {
                        lastError = message;
                    }
                    continue;
                }

                // Turn-level failure — captures the definitive error reason. Always
                // stash it separately from info-level item errors (hook warnings, etc.)
                // so the final fallback message is accurate.
                if (type.equals("turn.failed")) {
                    String message = node.path("error").path("message").asText("");
                    if (!message.isEmpty()) {
                        lastTurnError = message;
                        lastError = message;
                    }
                    continue;
                }

                // Turn completed — may carry a result text or last agent message
                // in newer Codex CLI versions. Without this handler, a turn that
                // runs tools but produces no agent_message would return empty.
                if (type.equals("turn.completed")) {
                    String result = node.path("result").asText("");
                    if (!result.isEmpty()) {
                        finalMessage = result;
                    }
                    // Fallback: some versions embed usage info
                    JsonNode usage = node.path("usage");
                    if (finalMessage == null && usage.isObject()) {
                        long outputTokens = usage.path("output_tokens").asLong(0);
                        if (outputTokens == 0 && lastTurnError == null) {
                            lastError = "Turn completed but produced no output tokens";
                        }
                    }
                    continue;
                }

                // Stream user-facing agent messages
                if (type.equals("event_msg")) {
                    JsonNode payload = node.path("payload");
                    if (payload.path("type").asText("").equals("agent_message")) {
                        String message = payload.path("message").asText("");
                        if (message.isEmpty() || message.equals(lastSnapshot)) {
                            continue;
                        }
                        String delta = snapshotDelta(lastSnapshot, message);
                        if (!delta.isEmpty()) {
                            request.emit(delta);
                        }
                        lastSnapshot = message;
                        streamedSnapshot.setLength(0);
                        streamedSnapshot.append(message);
                        if (payload.path("phase").asText("").equals("final_answer")) {
                            finalMessage = message;
                        }
                    } else if (payload.path("type").asText("").equals("task_complete")) {
                        String message = payload.path("last_agent_message").asText("");
                        if (!message.isEmpty()) {
                            finalMessage = message;
                        }
                    }
                }

                // Stream activity markers for tool/command/file events
                else if (type.equals("item.started") || type.equals("item.completed")) {
                    JsonNode item = node.path("item");
                    String itemType = item.path("type").asText("");

                    // Only emit on completion to show what actually happened
                    if (!type.equals("item.completed")) {
                        continue;
                    }
                    itemCount++;

                    String marker = null;
                    switch (itemType) {
                        case "agent_message":
                            String message = firstTextual(item, "text", "message");
                            if (message != null && !message.isEmpty()
                                    && !message.equals(lastSnapshot)) {
                                String delta = snapshotDelta(lastSnapshot, message);
                                if (!delta.isEmpty()) {
                                    request.emit(delta);
                                }
                                lastSnapshot = message;
                                streamedSnapshot.setLength(0);
                                streamedSnapshot.append(message);
                                finalMessage = message;
                            }
                            break;
                        case "error":
                            String error = item.path("message").asText("");
                            if (!error.isEmpty()) {
                                // Info/warning items (hook trust bypass, etc.) only
                                // set lastError as a fallback; turn-level errors
                                // recorded via turn.failed take precedence.
                                if (lastError == null) {
                                    lastError = error;
                                }
                            }
                            break;
                        case "command_execution":
                            String cmd = item.path("command").asText("");
                            if (!cmd.isEmpty()) {
                                marker = toolMarker("Bash", item);
                            }
                            break;
                        case "file_change":
                            String path = item.path("path").asText("");
                            if (!path.isEmpty()) {
                                marker = toolMarker("Edit", item);
                            }
                            break;
                        case "mcp_tool_call":
                            String toolName = item.path("name").asText("");
                            if (!toolName.isEmpty()) {
                                marker = toolMarker(toolName, item.path("arguments"));
                            }
                            break;
                        case "web_search":
                            String query = item.path("query").asText("");
                            if (!query.isEmpty()) {
                                marker = toolMarker("Search", item);
                            }
                            break;
                        case "reasoning":
                        case "assistant_thinking":
                            // Thinking markers are too verbose, skip
                            break;
                    }

                    if (marker != null) {
                        request.emit(marker);
                    }
                }
            }
        }
        if (finalMessage != null) {
            return finalMessage;
        }
        if (streamedSnapshot.length() > 0) {
            return streamedSnapshot.toString();
        }
        // When the turn failed with an explicit error, surface it.
        if (lastTurnError != null) {
            return "Codex CLI failed: " + lastTurnError;
        }
        // Fallback: use any error captured from items or top-level events.
        if (lastError != null) {
            return "Codex CLI failed: " + lastError;
        }
        // Truly nothing captured — provide diagnostics to help debug.
        if (eventCount > 0) {
            return "Codex CLI processed " + eventCount + " event(s) and "
                    + itemCount + " item(s) but produced no text";
        }
        return "Codex CLI produced no JSON events on stdout";
    }

    static boolean includesPartialMessages(String commandLine) {
        return commandLine != null && commandLine.contains("--include-partial-messages");
    }

    private static String assistantSnapshotText(JsonNode message) {
        StringBuilder sb = new StringBuilder();
        for (JsonNode block : message.path("content")) {
            if (block.path("type").asText("").equals("text")) {
                sb.append(block.path("text").asText(""));
            }
        }
        return sb.toString();
    }

    static String snapshotDelta(String previous, String current) {
        if (current == null || current.isEmpty()) {
            return "";
        }
        if (previous == null || previous.isEmpty()) {
            return current;
        }
        if (current.startsWith(previous)) {
            return current.substring(previous.length());
        }
        int common = commonPrefixLength(previous, current);
        return current.substring(common);
    }

    private static int commonPrefixLength(String a, String b) {
        int max = Math.min(a.length(), b.length());
        int i = 0;
        while (i < max && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    /** A tool_use block whose argument JSON is still streaming in. */
    private static class PendingTool {
        final String name;
        final StringBuilder inputJson = new StringBuilder();
        JsonNode preInput;

        PendingTool(String name) {
            this.name = name;
        }

        /** The tool input as a parsed object, from up-front input or streamed JSON. */
        JsonNode resolveInput() {
            if (preInput != null) {
                return preInput;
            }
            if (inputJson.length() > 0) {
                try {
                    return Json.MAPPER.readTree(inputJson.toString());
                } catch (Exception ignored) {
                    // partial / malformed JSON — fall back to name-only marker
                }
            }
            return null;
        }
    }

    /** Render a one-line tool marker, e.g. {@code "\n[…Bash: mvn clean]\n"}. */
    private static String toolMarker(String name, JsonNode input) {
        return "\n[…" + toolLabel(name, input) + "]\n";
    }

    /**
     * Build a concise label from a tool name and its input, picking the most
     * informative argument (command / file path / pattern / description / …) and
     * trimming it to a single short line. Falls back to just the name.
     */
    private static String toolLabel(String name, JsonNode input) {
        if (input == null || !input.isObject()) {
            return name;
        }
        String detail = firstTextual(input,
                "command", "file_path", "path", "pattern", "query",
                "url", "description", "prompt", "content");
        if (detail == null || detail.isBlank()) {
            return name;
        }
        detail = detail.replaceAll("\\s+", " ").trim();
        int max = 80;
        if (detail.length() > max) {
            detail = detail.substring(0, max) + "…";
        }
        return name + ": " + detail;
    }

    /** First non-empty textual value among the given field names, or null. */
    private static String firstTextual(JsonNode input, String... fields) {
        for (String f : fields) {
            JsonNode v = input.get(f);
            if (v != null && v.isTextual()) {
                String s = v.asText("");
                if (!s.isEmpty()) {
                    return s;
                }
            }
        }
        return null;
    }

    // ── Non-verbose filtering state machine ────────────────────────

    private static class ToolBlock {
        String toolName;
        String command;
        int lineCount;
    }

    /**
     * Process one clean (ANSI-free) line through the tool-block filter.
     * Returns the current (possibly new or null) tool block.
     */
    private static ToolBlock filterLine(String line, ToolBlock block, AgentRequest request) {
        // Detect tool-call start: "🔧 Tool call: <name>" or "Tool call: <name>"
        String toolCallName = matchToolCall(line);
        if (toolCallName != null) {
            if (block != null) emitToolSummary(block, request);
            ToolBlock tb = new ToolBlock();
            tb.toolName = toolCallName;
            return tb;
        }

        if (block != null) {
            // Check for command line inside a tool call block
            if (block.command == null && line.trim().startsWith("command:")) {
                block.command = extractQuotedValue(line);
            }
            // End of tool block: blank line or agent natural language resumes
            if (isToolResult(line) || line.trim().isEmpty()) {
                block.lineCount++;
                return block; // still in block
            }
            // If we see natural language (not tool-related), end the block
            if (isNaturalLanguage(line)) {
                emitToolSummary(block, request);
                request.emit(line + "\n");
                return null;
            }
            // Otherwise, count it as tool output
            block.lineCount++;
            return block;
        }

        // Outside a tool block: pass through natural language
        if (isToolResult(line)) {
            // Lone tool result without a preceding tool-call marker — skip
            return null;
        }
        request.emit(line + "\n");
        return null;
    }

    /** Extract tool name from a "Tool call: <name>" line. */
    private static String matchToolCall(String line) {
        String trimmed = line.trim();
        // Strip possible leading emoji
        String noEmoji = trimmed.replaceFirst("^[\\u2600-\\u27BF\\uD800-\\uDFFF🔧📋📤✅❌🤖]+\\s*", "");
        if (noEmoji.startsWith("Tool call:")) {
            String name = noEmoji.substring("Tool call:".length()).trim();
            return name.isEmpty() ? "unknown" : name;
        }
        return null;
    }

    private static boolean isToolResult(String line) {
        String t = line.trim();
        return t.startsWith("📋 Tool result:")
                || t.startsWith("Tool result:")
                || t.startsWith("✅ Command completed")
                || t.startsWith("📤 Output:")
                || t.startsWith("Output:");
    }

    /** Crude heuristic: natural language lines start with words, markdown, or emojis. */
    private static boolean isNaturalLanguage(String line) {
        String t = line.trim();
        if (t.isEmpty()) return false;
        // Tool-output patterns
        if (t.startsWith("command:") || t.startsWith("cwd:") || t.startsWith("wait:")
                || t.startsWith("max_wait_seconds:") || t.startsWith("total ")
                || t.startsWith("drwx") || t.startsWith("-rw") || t.startsWith("<?xml")
                || t.startsWith("<project") || t.startsWith("package ") || t.startsWith("import ")
                || t.startsWith("public class") || t.startsWith("private ") || t.startsWith("/Users/")) {
            return false;
        }
        return true;
    }

    private static String extractQuotedValue(String line) {
        int start = line.indexOf('"');
        if (start < 0) return null;
        int end = line.indexOf('"', start + 1);
        if (end < 0) return null;
        String val = line.substring(start + 1, end);
        // Truncate long commands
        return val.length() > 60 ? val.substring(0, 57) + "..." : val;
    }

    private static void emitToolSummary(ToolBlock block, AgentRequest request) {
        String name = block.toolName != null ? block.toolName : "unknown";
        String detail = block.command != null ? ": " + block.command
                : " (" + block.lineCount + " lines)";
        request.emit("[…" + name + detail + "]\n");
    }

    /** Minimal whitespace splitter with support for single/double quoted args. */
    public static List<String> parseCommand(String command) {
        List<String> args = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < command.length(); i++) {
            char c = command.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                } else {
                    current.append(c);
                }
            } else if (c == '\'' || c == '"') {
                quote = c;
            } else if (Character.isWhitespace(c)) {
                if (current.length() > 0) {
                    args.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            args.add(current.toString());
        }
        return args;
    }

    /**
     * Wrap a command so its stdout is line-buffered instead of block-buffered.
     * When a process writes to a pipe (as in Java's ProcessBuilder), the C
     * runtime may default to block buffering, making output arrive in bursts.
     *
     * <p>On Linux we use {@code stdbuf -oL -eL} (GNU coreutils), which only
     * adjusts stdio buffering and does <em>not</em> allocate a PTY.
     *
     * <p>We deliberately do NOT use a PTY (e.g. macOS {@code script}): a PTY
     * echoes stdin, emits terminal control sequences, and makes CLIs like
     * Claude Code switch to full TUI output — which corrupts the captured stream
     * ("gibberish") and breaks stdin delivery (the tool sees no prompt). On
     * macOS / Windows / unknown platforms the command is returned unchanged;
     * reading the pipe with {@code readLine()} still streams line-by-line, and
     * well-behaved CLIs flush their pipe output promptly.
     */
    static List<String> wrapForLineBuffering(List<String> cmd) {
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("linux")) {
            // Linux: stdbuf from GNU coreutils adjusts stdio buffering.
            // -oL = line-buffered stdout, -eL = line-buffered stderr.
            List<String> wrapped = new ArrayList<>();
            wrapped.add("stdbuf");
            wrapped.add("-oL");
            wrapped.add("-eL");
            wrapped.addAll(cmd);
            return wrapped;
        }
        // macOS / Windows / unknown: run unchanged. A PTY wrapper would corrupt
        // output and break stdin; most CLIs flush their pipe output promptly.
        return cmd;
    }
}
