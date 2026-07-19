package io.groupchat.provider;

/**
 * Result of a provider call.
 */
public class AgentResponse {

    public final boolean ok;
    public final String content;
    public final String error;
    /** Optional usage/quota reported by the provider for this call. */
    public final Usage usage;

    private AgentResponse(boolean ok, String content, String error, Usage usage) {
        this.ok = ok;
        this.content = content;
        this.error = error;
        this.usage = usage;
    }

    public static AgentResponse ok(String content) {
        return new AgentResponse(true, content, null, null);
    }

    public static AgentResponse ok(String content, Usage usage) {
        return new AgentResponse(true, content, null, usage);
    }

    public static AgentResponse error(String error) {
        return new AgentResponse(false, null, error, null);
    }
}
