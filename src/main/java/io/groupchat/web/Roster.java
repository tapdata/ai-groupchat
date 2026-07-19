package io.groupchat.web;

import io.groupchat.config.AppConfig;
import io.groupchat.maintain.ProjectContext;
import io.groupchat.model.Agent;
import io.groupchat.provider.ProviderRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the secret-free roster payload shown in the UI and broadcast over the
 * WebSocket: agent metadata plus global flags (free mode, owner). Never includes
 * API keys or any other secret field.
 */
public final class Roster {

    private Roster() {
    }

    /**
     * Best-effort resolution of which model an agent is currently using.
     * API agents have {@code a.model} set directly; CLI agents may carry it
     * in {@code a.env} (e.g. {@code ANTHROPIC_MODEL}) instead.
     */
    private static String effectiveModel(Agent a) {
        if (a.model != null && !a.model.isBlank()) return a.model;
        if (a.env != null) {
            for (String key : new String[]{"ANTHROPIC_MODEL", "OPENAI_MODEL", "CODEX_MODEL"}) {
                String m = a.env.get(key);
                if (m != null && !m.isBlank()) return m;
            }
        }
        return null;
    }

    public static Map<String, Object> snapshot(AppConfig config, ProviderRegistry registry,
                                               ProjectContext ctx) {
        List<Map<String, Object>> agents = new ArrayList<>();
        for (Agent a : config.agents) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", a.id);
            m.put("name", a.name);
            m.put("provider", a.provider);
            m.put("enabled", a.enabled);
            m.put("ready", a.isReady());
            m.put("freeMode", a.freeMode);
            m.put("freeModeSupported", registry.get(a.provider)
                    .map(p -> p.supportsFreeMode(a)).orElse(false));
            m.put("model", effectiveModel(a));
            agents.add(m);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("agents", agents);
        out.put("owner", ctx != null && ctx.owner);
        out.put("synthesizerId", config.synthesizerId);
        out.put("maintainerId", config.maintainerId);
        return out;
    }
}
