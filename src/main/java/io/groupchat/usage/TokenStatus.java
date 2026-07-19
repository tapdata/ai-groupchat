package io.groupchat.usage;

/**
 * UI-facing snapshot of one agent's token / quota status. Serialized to the
 * WebSocket clients so the sidebar can render a percentage bar or "N/A".
 */
public class TokenStatus {

    public String agentId;
    public String name;
    /** Remaining as 0-100 percentage, or null when unknown. */
    public Integer percent;
    public Long remaining;
    public Long limit;
    public String unit;
    /** Short note, e.g. "rate-limit window" or "N/A". */
    public String note;

    public TokenStatus() {
    }

    public TokenStatus(String agentId, String name) {
        this.agentId = agentId;
        this.name = name;
    }
}
