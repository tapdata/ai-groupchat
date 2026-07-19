package io.groupchat.provider;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Registry of available {@link AgentProvider}s keyed by their {@link AgentProvider#type()}.
 *
 * <p>Extensibility lives here: register new providers and they become usable by
 * any agent whose {@code provider} field matches the type.
 */
public class ProviderRegistry {

    private final Map<String, AgentProvider> providers = new LinkedHashMap<>();

    public ProviderRegistry register(AgentProvider provider) {
        providers.put(provider.type().toLowerCase(), provider);
        return this;
    }

    public Optional<AgentProvider> get(String type) {
        if (type == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(providers.get(type.toLowerCase()));
    }

    public boolean has(String type) {
        return type != null && providers.containsKey(type.toLowerCase());
    }

    /** Built-in providers shipped with the app. */
    public static ProviderRegistry withDefaults() {
        return new ProviderRegistry()
                .register(new AnthropicProvider())
                .register(new OpenAiProvider())
                .register(new CliProvider());
    }
}
