package io.groupchat.orchestrate;

import io.groupchat.chat.ChatRoom;
import io.groupchat.chat.MentionParser;
import io.groupchat.config.AppConfig;
import io.groupchat.model.Agent;
import io.groupchat.model.Message;
import io.groupchat.model.MessageType;
import io.groupchat.provider.AgentRequest;
import io.groupchat.provider.AgentResponse;
import io.groupchat.provider.ProviderRegistry;
import io.groupchat.usage.UsageRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Routes messages to the right agent(s) and runs the @all / group review flow.
 *
 * <h3>Mention routing</h3>
 * <ul>
 *   <li>{@code @agent} — that single agent replies.</li>
 *   <li>{@code @agent1 @agent2} — those agents reply in parallel, then the
 *       {@link ReviewStrategy} produces a consolidated answer (same as @all).</li>
 *   <li>{@code @all} — every ready agent replies in parallel, then the
 *       {@link ReviewStrategy} produces a consolidated answer.</li>
 * </ul>
 *
 * <h3>AI-to-AI collaboration</h3>
 * <p>Agent messages are also scanned for @mentions so agents can hand off work
 * to each other. To prevent runaway chains:
 * <ul>
 *   <li>Agents cannot @mention themselves.</li>
 *   <li>Agents cannot use {@code @all} — only the user can broadcast.</li>
 *   <li>Chains are capped at {@link #MAX_CHAIN_DEPTH} hops; the user must
 *       intervene before the chain can continue.</li>
 * </ul>
 */
public class Orchestrator {

    /** Maximum AI-to-AI hops before the user must intervene. */
    public static final int MAX_CHAIN_DEPTH = 5;

    private final AppConfig config;
    private final ProviderRegistry registry;
    private final ChatRoom room;
    private final ReviewStrategy reviewStrategy;
    private final UsageRegistry usage;
    private final ExecutorService pool = Executors.newCachedThreadPool();

    public Orchestrator(AppConfig config, ProviderRegistry registry, ChatRoom room,
                        ReviewStrategy reviewStrategy, UsageRegistry usage) {
        this.config = config;
        this.registry = registry;
        this.room = room;
        this.reviewStrategy = reviewStrategy;
        this.usage = usage;
    }

    // ── Unified entry point (user + agent messages) ──────────────────────

    /**
     * Called by {@link ChatRoom}'s message listener whenever a message is
     * finalized. Parses @mentions and dispatches to the right agent(s),
     * enforcing collaboration depth and anti-abuse rules.
     */
    public void onMessage(Message message) {
        // System and synthesis messages never trigger further agents.
        if (message.type == MessageType.SYSTEM || message.type == MessageType.SYNTHESIS) {
            return;
        }

        MentionParser.Result mentions = resolveMentions(message);
        if (!mentions.hasMentions()) {
            return;
        }

        // ── Rule 1: AI agents cannot broadcast ────────────────────────
        if (mentions.broadcast && message.type == MessageType.AGENT) {
            room.post(Message.system(
                    "⛔ " + message.senderName + " tried @all — only the user can broadcast."));
            return;
        }

        // ── Rule 2: remove self-mentions ──────────────────────────────
        List<String> targets = new ArrayList<>(mentions.targets);
        if (message.type == MessageType.AGENT) {
            targets.remove(message.sender);
        }

        if (targets.isEmpty() && !mentions.broadcast) {
            return;
        }

        // ── Rule 3: depth cap ─────────────────────────────────────────
        int nextDepth = message.chainDepth + 1;
        if (message.type == MessageType.AGENT && nextDepth > MAX_CHAIN_DEPTH) {
            room.post(Message.system(
                    "⛔ AI collaboration chain reached depth " + MAX_CHAIN_DEPTH + ". "
                    + "The user must intervene before the chain can continue. "
                    + "Type a message to resume."));
            return;
        }

        pool.submit(() -> {
            try {
                if (mentions.broadcast) {
                    runBroadcast(message.content);
                } else if (targets.size() >= 2) {
                    runGroup(message.content, targets, nextDepth);
                } else {
                    for (String id : targets) {
                        config.findAgent(id).ifPresent(a -> runAgent(a, message.content, nextDepth));
                    }
                }
            } catch (Exception e) {
                room.post(Message.system("Orchestration error: " + e.getMessage()));
            }
        });
    }

    // ── Broadcast (@all) and group (@agent1 @agent2) ────────────────────

    private void runBroadcast(String question) {
        List<String> agentIds = new ArrayList<>();
        for (Agent a : config.enabledAgents()) {
            if (!registry.has(a.provider)) {
                continue;
            }
            agentIds.add(a.id);
        }
        if (agentIds.isEmpty()) {
            room.post(Message.system("No ready agents to answer @all."));
            return;
        }
        runGroup(question, agentIds, 1);
    }

    /**
     * Run a specific set of agents in parallel, then synthesise a consolidated
     * answer.  Used by both {@code @all} and multi-mention
     * ({@code @agent1 @agent2}) paths.
     *
     * @param targets    agent IDs to invoke
     * @param chainDepth collaboration depth to stamp on each agent's response
     */
    private void runGroup(String question, List<String> targets, int chainDepth) {
        List<Agent> agents = new ArrayList<>();
        for (String id : targets) {
            Agent a = config.findAgent(id).orElse(null);
            if (a == null) continue;
            if (!registry.has(a.provider)) continue;
            agents.add(a);
        }
        if (agents.isEmpty()) {
            room.post(Message.system("No ready agents in the group."));
            return;
        }
        List<CompletableFuture<ReviewStrategy.Answer>> futures = new ArrayList<>();
        for (Agent a : agents) {
            futures.add(CompletableFuture.supplyAsync(() -> {
                String content = runAgent(a, question, chainDepth);
                return content == null ? null : new ReviewStrategy.Answer(a.id, a.name, content);
            }, pool));
        }
        List<ReviewStrategy.Answer> answers = new ArrayList<>();
        for (int i = 0; i < futures.size(); i++) {
            Agent agent = agents.get(i);
            CompletableFuture<ReviewStrategy.Answer> f = futures.get(i);
            try {
                ReviewStrategy.Answer ans = f.get(groupWaitSeconds(agent), TimeUnit.SECONDS);
                if (ans != null) {
                    answers.add(ans);
                }
            } catch (TimeoutException e) {
                f.cancel(true);
                room.post(Message.system(agent.name + " did not finish before the group timeout; continuing."));
            } catch (Exception ignored) {
            }
        }
        runSynthesis(question, answers);
    }

    private int groupWaitSeconds(Agent agent) {
        if (agent.timeoutSeconds != null && agent.timeoutSeconds > 0) {
            return agent.timeoutSeconds + 10;
        }
        return 910;
    }

    private void runSynthesis(String question, List<ReviewStrategy.Answer> answers) {
        if (answers.isEmpty()) {
            return;
        }
        String synthId = reviewStrategy.synthesizerId(config, answers);
        Agent synth = synthId == null ? null : config.findAgent(synthId).orElse(null);
        if (synth == null || !registry.has(synth.provider)) {
            return;
        }
        if (!freeAllowed(synth)) {
            room.post(Message.system("Synthesis skipped: " + synth.name
                    + " is in free mode but has no free option configured."));
            return;
        }
        String prompt = reviewStrategy.buildSynthesisPrompt(question, answers);
        // Stream the synthesis into a single bubble. Show a "thinking" placeholder
        // immediately so the user gets instant feedback.
        String synthName = synth.name + " (synthesis)";
        Message holder = room.beginStream(Message.synthesis(synthName, "🤔 Synthesizing…"));
        boolean[] thinkingShown = {true};

        java.util.function.Consumer<String> onChunk = delta -> {
            if (thinkingShown[0]) {
                room.replaceContent(holder, delta);
                thinkingShown[0] = false;
            } else {
                room.appendStream(holder, delta);
            }
        };
        try {
            AgentResponse resp = registry.get(synth.provider).orElseThrow()
                    .generate(new AgentRequest(synth, room.renderContext(), prompt, synth.freeMode, onChunk));
            recordUsage(synth, resp);
            if (resp.ok) {
                String finalContent = finalContent(resp.content, holder);
                if (thinkingShown[0]) {
                    room.replaceContent(holder, finalContent);
                }
                room.endStream(holder, finalContent);
                return;
            }
            String partial = holder.content == null ? "" : holder.content;
            if (thinkingShown[0]) {
                room.replaceContent(holder, "Synthesis by " + synth.name + " failed: " + resp.error);
            }
            room.endStream(holder, thinkingShown[0]
                    ? "Synthesis by " + synth.name + " failed: " + resp.error
                    : partial + "\n\n[failed: " + resp.error + "]");
        } catch (Exception e) {
            String error = errorMessage(e);
            String partial = holder.content == null ? "" : holder.content;
            if (thinkingShown[0]) {
                room.replaceContent(holder, "Synthesis by " + synth.name + " failed: " + error);
            }
            room.endStream(holder, thinkingShown[0]
                    ? "Synthesis by " + synth.name + " failed: " + error
                    : partial + "\n\n[failed: " + error + "]");
        }
    }

    // ── Single-agent run ─────────────────────────────────────────────────

    /**
     * Run one agent and post its reply (or an error). Returns reply content or null.
     *
     * @param chainDepth the collaboration depth to stamp on the agent's
     *                   response message (1 for user-triggered, 2+ for
     *                   AI-triggered).
     */
    private String runAgent(Agent agent, String prompt, int chainDepth) {
        if (!registry.has(agent.provider)) {
            room.post(Message.system("No provider '" + agent.provider + "' for agent " + agent.id));
            return null;
        }
        if (!freeAllowed(agent)) {
            room.post(Message.system(agent.name + " skipped: free mode is on and no free option is configured."));
            return null;
        }
        // Stream incremental output into a single chat bubble. Immediately show a
        // "thinking" placeholder so the user sees instant feedback, then replace it
        // with the first real chunk. Non-streaming providers still post once at the end.
        Message holder = room.beginStream(Message.agent(agent.id, agent.name, "🤔 Thinking…"));
        boolean[] thinkingShown = {true};  // true = placeholder still showing, replace next chunk

        java.util.function.Consumer<String> onChunk = delta -> {
            if (thinkingShown[0]) {
                room.replaceContent(holder, delta);
                thinkingShown[0] = false;
            } else {
                room.appendStream(holder, delta);
            }
        };
        try {
            AgentResponse resp = registry.get(agent.provider).orElseThrow()
                    .generate(new AgentRequest(agent, room.renderContext(), prompt, agent.freeMode, onChunk));
            recordUsage(agent, resp);
            if (resp.ok) {
                String finalContent = finalContent(resp.content, holder);
                if (thinkingShown[0]) {
                    // Provider returned without streaming — replace placeholder with full response
                    room.replaceContent(holder, finalContent);
                }
                holder.chainDepth = chainDepth;
                room.endStream(holder, finalContent);
                return finalContent;
            }
            String partial = holder.content == null ? "" : holder.content;
            if (thinkingShown[0]) {
                // Provider failed without any streaming output — replace the
                // "thinking" placeholder cleanly instead of appending to it.
                room.replaceContent(holder, agent.name + " failed: " + resp.error);
            }
            room.endStream(holder, thinkingShown[0]
                    ? agent.name + " failed: " + resp.error
                    : partial + "\n\n[failed: " + resp.error + "]");
            return null;
        } catch (Exception e) {
            String error = errorMessage(e);
            String partial = holder.content == null ? "" : holder.content;
            if (thinkingShown[0]) {
                room.replaceContent(holder, agent.name + " failed: " + error);
            }
            room.endStream(holder, thinkingShown[0]
                    ? agent.name + " failed: " + error
                    : partial + "\n\n[failed: " + error + "]");
            return null;
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Parse @mentions from a message. If the user didn't @mention anyone,
     * fall back to replying to the last AI that spoke in the chat.
     */
    private MentionParser.Result resolveMentions(Message message) {
        MentionParser.Result mentions = MentionParser.parse(message.content, config);
        if (!mentions.hasMentions() && message.type == MessageType.USER) {
            Agent lastSpeaker = findLastSpeakingAgent();
            if (lastSpeaker != null) {
                return new MentionParser.Result(List.of(lastSpeaker.id), false);
            }
        }
        return mentions;
    }

    /**
     * Scan the chat history backwards for the most recent AI agent message
     * and return that agent. Returns null if no AI has spoken yet.
     */
    private Agent findLastSpeakingAgent() {
        List<Message> history = room.history();
        for (int i = history.size() - 1; i >= 0; i--) {
            Message m = history.get(i);
            if (m.type == MessageType.AGENT) {
                return config.findAgent(m.sender).orElse(null);
            }
        }
        return null;
    }

    /**
     * True when free mode is off for this agent, or the agent's provider actually
     * supports a free alternative. An agent with freeMode=true but no freeModel /
     * freeCommand / freeBaseUrl would silently fall back to the paid path — this
     * guard prevents that.
     */
    private boolean freeAllowed(Agent agent) {
        if (!agent.freeMode) {
            return true;
        }
        return registry.get(agent.provider).map(p -> p.supportsFreeMode(agent)).orElse(false);
    }

    /** Store and broadcast any usage reported by a provider call. */
    private void recordUsage(Agent agent, AgentResponse resp) {
        if (resp != null && resp.usage != null) {
            usage.update(agent.id, resp.usage);
            room.broadcastStatus();
        }
    }

    private static String errorMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String finalContent(String providerContent, Message holder) {
        if (providerContent != null && !providerContent.isBlank()) {
            return providerContent;
        }
        return holder.content == null ? "" : holder.content;
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
