package io.groupchat.config;

import io.groupchat.model.Agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Root persisted configuration: the roster of AI agents plus a few global settings.
 */
public class AppConfig {

    /** HTTP port for the local web UI. */
    public int port = 7860;
    /** Agent id used to synthesize the final answer during @all review. Defaults to first ready agent. */
    public String synthesizerId;
    /** When true, only free-mode-capable agents respond; others are skipped with a notice. */
    public boolean freeMode = false;
    /** Agent id (a CLI coding tool) used to self-optimize this project. */
    public String maintainerId;
    /** Max messages included in AI context. 0 = unlimited. */
    public int contextWindowMessages = 50;

    /**
     * The AI members of the chat. Declared as concrete CopyOnWriteArrayList so
     * Jackson deserialization instantiates the thread-safe type rather than
     * overwriting it with a plain ArrayList.
     */
    public CopyOnWriteArrayList<Agent> agents = new CopyOnWriteArrayList<>();

    public Optional<Agent> findAgent(String id) {
        if (id == null) {
            return Optional.empty();
        }
        return agents.stream().filter(a -> id.equalsIgnoreCase(a.id)).findFirst();
    }

    public List<Agent> enabledAgents() {
        List<Agent> result = new ArrayList<>();
        for (Agent a : agents) {
            if (a.enabled) {
                result.add(a);
            }
        }
        return result;
    }

    /** Seed the default roster (claude / codex / auggie) on first run. */
    public static AppConfig defaults() {
        AppConfig cfg = new AppConfig();

        Agent claude = new Agent("claude", "Claude", "anthropic");
        claude.model = "claude-3-5-sonnet-latest";
        claude.systemPrompt = "You are Claude, a member of a collaborative AI group chat.";

        Agent codex = new Agent("codex", "Codex", "openai");
        codex.model = "gpt-4o";
        codex.systemPrompt = "You are Codex, a member of a collaborative AI group chat.";

        Agent auggie = new Agent("auggie", "Auggie", "cli");
        auggie.command = "auggie --print";
        auggie.installCommand = "npm install -g @augmentcode/auggie";
        auggie.systemPrompt = "You are Auggie, a member of a collaborative AI group chat.";

        cfg.agents.add(claude);
        cfg.agents.add(codex);
        cfg.agents.add(auggie);
        cfg.synthesizerId = "claude";
        cfg.maintainerId = "auggie";
        return cfg;
    }
}
