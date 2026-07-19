package io.groupchat.provider;

import io.groupchat.model.Agent;

/**
 * Everything a provider needs to produce one reply.
 *
 * <p>Because every message in the room is visible to every AI, the full running
 * transcript is handed to the provider as {@code contextMarkdown}. {@code prompt}
 * is the specific instruction directed at this agent right now (the user's
 * mention text, the broadcast question, or a synthesis instruction).
 */
public class AgentRequest {

    public final Agent agent;
    public final String contextMarkdown;
    public final String prompt;
    /** When true, providers should prefer the agent's free model / base URL. */
    public final boolean freeMode;
    /** Optional sink for incremental output chunks (streaming). May be null. */
    public final java.util.function.Consumer<String> onChunk;

    public AgentRequest(Agent agent, String contextMarkdown, String prompt) {
        this(agent, contextMarkdown, prompt, false);
    }

    public AgentRequest(Agent agent, String contextMarkdown, String prompt, boolean freeMode) {
        this(agent, contextMarkdown, prompt, freeMode, null);
    }

    public AgentRequest(Agent agent, String contextMarkdown, String prompt, boolean freeMode,
                        java.util.function.Consumer<String> onChunk) {
        this.agent = agent;
        this.contextMarkdown = contextMarkdown;
        this.prompt = prompt;
        this.freeMode = freeMode;
        this.onChunk = onChunk;
    }

    /** Emit an incremental output chunk to the streaming sink, if one is set. */
    public void emit(String delta) {
        if (onChunk != null && delta != null && !delta.isEmpty()) {
            onChunk.accept(delta);
        }
    }

    /** Model to use, honoring free mode when a free model is configured. */
    public String effectiveModel() {
        if (freeMode && notBlank(agent.freeModel)) {
            return agent.freeModel;
        }
        return agent.model;
    }

    /** Base URL to use, honoring free mode when a free base URL is configured. */
    public String effectiveBaseUrl() {
        if (freeMode && notBlank(agent.freeBaseUrl)) {
            return agent.freeBaseUrl;
        }
        return agent.baseUrl;
    }

    /** Command line to run, honoring free mode when a free command is configured. */
    public String effectiveCommand() {
        if (freeMode && notBlank(agent.freeCommand)) {
            return agent.freeCommand;
        }
        return agent.command;
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    /** Composed user content: shared context followed by this agent's task. */
    public String composedUserContent() {
        StringBuilder sb = new StringBuilder();
        if (contextMarkdown != null && !contextMarkdown.isBlank()) {
            sb.append("# Group chat so far\n\n")
              .append(contextMarkdown)
              .append("\n\n");
        }
        sb.append("# Your task\n\n").append(prompt);
        return sb.toString();
    }
}
