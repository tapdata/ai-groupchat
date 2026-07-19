package io.groupchat.usage;

import io.groupchat.config.AppConfig;
import io.groupchat.model.Agent;
import io.groupchat.provider.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks the most recently reported {@link Usage} per agent and renders a
 * {@link TokenStatus} snapshot for the whole roster on demand.
 *
 * <p>Agents that have never reported usage (e.g. CLI tools, or APIs not yet
 * called) appear with a null percentage and an "N/A" note so the UI can still
 * list them.
 */
public class UsageRegistry {

    private final AppConfig config;
    private final Map<String, Usage> byAgent = new ConcurrentHashMap<>();

    public UsageRegistry(AppConfig config) {
        this.config = config;
    }

    /** Record the latest usage for an agent. Null is ignored. */
    public void update(String agentId, Usage usage) {
        if (agentId != null && usage != null) {
            byAgent.put(agentId, usage);
        }
    }

    public void remove(String agentId) {
        if (agentId != null) {
            byAgent.remove(agentId);
        }
    }

    /** Snapshot for every agent in the current roster. */
    public List<TokenStatus> snapshot() {
        List<TokenStatus> out = new ArrayList<>();
        for (Agent a : config.agents) {
            TokenStatus s = new TokenStatus(a.id, a.name);
            Usage u = byAgent.get(a.id);
            if (u != null) {
                s.remaining = u.remaining;
                s.limit = u.limit;
                s.unit = u.unit;
                s.percent = u.percent();
                s.note = u.note;
            } else {
                s.note = "N/A";
            }
            out.add(s);
        }
        return out;
    }
}
