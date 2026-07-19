package io.groupchat.model;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A single chat message. Serialized directly to WebSocket clients (public fields)
 * and rendered into the markdown transcript.
 */
public class Message {

    public String id = UUID.randomUUID().toString();
    public MessageType type;
    /** Stable id of the sender: "user" or an agent id such as "claude". */
    public String sender;
    /** Human friendly display name, e.g. "You" or "Claude". */
    public String senderName;
    public String content;
    /** Agent ids this message is addressed to (parsed from @mentions). */
    public List<String> mentions = new ArrayList<>();
    /**
     * Depth in the AI-to-AI collaboration chain. User messages start at 0.
     * Each AI-triggered hop increments by 1. The orchestrator blocks further
     * AI-triggered hops when chainDepth reaches {@code Orchestrator.MAX_CHAIN_DEPTH}.
     */
    public int chainDepth = 0;
    /** Epoch millis. */
    public long timestamp = System.currentTimeMillis();

    public Message() {
    }

    public Message(MessageType type, String sender, String senderName, String content) {
        this.type = type;
        this.sender = sender;
        this.senderName = senderName;
        this.content = content;
    }

    public static Message user(String content) {
        return new Message(MessageType.USER, "user", "You", content);
    }

    public static Message agent(String agentId, String agentName, String content) {
        return new Message(MessageType.AGENT, agentId, agentName, content);
    }

    public static Message system(String content) {
        return new Message(MessageType.SYSTEM, "system", "System", content);
    }

    public static Message synthesis(String senderName, String content) {
        return new Message(MessageType.SYNTHESIS, "synthesis", senderName, content);
    }

    public Message mentions(List<String> mentions) {
        this.mentions = mentions != null ? mentions : new ArrayList<>();
        return this;
    }
}
