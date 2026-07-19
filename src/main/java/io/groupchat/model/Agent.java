package io.groupchat.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Configuration for one AI member of the group chat.
 *
 * <p>An agent is bound to a pluggable provider (see {@code provider} field) which
 * decides how the request is executed (HTTP API, local CLI subprocess, ...).
 * New provider types only require a new {@code AgentProvider} implementation
 * registered in the {@code ProviderRegistry}; this model stays unchanged.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class Agent {

    /** Stable id, also used as the @mention handle, e.g. "claude". */
    public String id;
    /** Display name shown in the UI, e.g. "Claude". */
    public String name;
    /** Provider type key, e.g. "anthropic", "openai", "cli". */
    public String provider;
    /** Model name for API providers, e.g. "claude-3-5-sonnet-latest". */
    public String model;
    /** Secret API key. Persisted locally; never broadcast or logged in plaintext. */
    public String apiKey;
    /** Optional base URL override for API providers. */
    public String baseUrl;
    /** Command line for the "cli" provider, e.g. "auggie --print". */
    public String command;
    /**
     * Working directory for CLI subprocesses. Tools like Claude Code / auggie
     * operate on (and restrict file access to) their current directory, so set
     * this to the project you want the agent to read, e.g.
     * "/Users/me/IdeaProjects/tapdata-connectors". Defaults to the launch dir.
     */
    public String cwd;
    /** Command line used when free mode is on (CLI providers); marks free-mode support. */
    public String freeCommand;
    /** Optional system/persona prompt prepended to each request. */
    public String systemPrompt;
    /** Model used when free mode is on (API providers); marks free-mode support. */
    public String freeModel;
    /** Base URL used when free mode is on (API providers); marks free-mode support. */
    public String freeBaseUrl;
    /** Shell command to install this agent's CLI tool if missing, e.g. "npm install -g @augmentcode/auggie". */
    public String installCommand;
    /**
     * Extra environment variables passed to CLI subprocesses (e.g.
     * ANTHROPIC_BASE_URL, ANTHROPIC_AUTH_TOKEN, ANTHROPIC_MODEL) so a tool like
     * Claude Code can run headless against a compatible gateway without an
     * interactive login. Merged on top of the inherited process environment.
     */
    public java.util.Map<String, String> env;
    /**
     * Max seconds to wait for a CLI subprocess before forcibly killing it. Agentic
     * CLIs (e.g. Claude Code running tools / sub-agents) can run for many minutes,
     * so this is generous and configurable. Null falls back to the provider default.
     */
    public Integer timeoutSeconds;
    /** Whether this agent participates in @all and can be mentioned. */
    public boolean enabled = true;
    /** When true, use freeModel / freeBaseUrl / freeCommand instead of the regular ones. */
    public boolean freeMode = false;
    /**
     * When false, CLI tool-call output is collapsed into summary lines.
     * Natural language responses still stream normally. Default true (show everything).
     */
    public boolean verbose = true;

    public Agent() {
    }

    public Agent(String id, String name, String provider) {
        this.id = id;
        this.name = name;
        this.provider = provider;
    }

    /** True when the agent has the minimum configuration needed to respond. */
    @JsonIgnore
    public boolean isReady() {
        if ("cli".equalsIgnoreCase(provider)) {
            return (command != null && !command.isBlank())
                    || (freeCommand != null && !freeCommand.isBlank());
        }
        if (freeMode) {
            // API agent in free mode: need apiKey + a usable model. freeBaseUrl
            // alone is not enough — effectiveModel() would return the regular
            // model, so model must be configured too.
            boolean hasFreeModel = freeModel != null && !freeModel.isBlank();
            boolean hasFreeBaseUrl = freeBaseUrl != null && !freeBaseUrl.isBlank();
            if (hasFreeModel || hasFreeBaseUrl) {
                return apiKey != null && !apiKey.isBlank()
                        && ((hasFreeModel) || (model != null && !model.isBlank()));
            }
            // No free option configured — fall through to regular config
        }
        return apiKey != null && !apiKey.isBlank() && model != null && !model.isBlank();
    }
}
