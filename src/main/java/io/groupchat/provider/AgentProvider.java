package io.groupchat.provider;

/**
 * Strategy that knows how to turn an {@link AgentRequest} into an
 * {@link AgentResponse}. This is the single extension point of the system.
 *
 * <p>To add a new kind of AI member (a new API, a different CLI tool, a local
 * model, ...), implement this interface and register the instance in
 * {@link ProviderRegistry}. No other code needs to change.
 */
public interface AgentProvider {

    /** Unique provider type key, matched against {@code Agent.provider}. */
    String type();

    /** Produce a single reply. Implementations should be thread-safe. */
    AgentResponse generate(AgentRequest request);

    /**
     * Whether this agent can answer while "free mode" is on (a free model/base
     * URL is configured, or the tool is inherently free). Defaults to false so
     * paid APIs are skipped unless explicitly given a free option.
     */
    default boolean supportsFreeMode(io.groupchat.model.Agent agent) {
        return false;
    }
}
