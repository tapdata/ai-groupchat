package io.groupchat.chat;

import io.groupchat.model.Message;
import io.groupchat.util.Json;
import io.javalin.websocket.WsContext;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * In-memory room state: the running message history, the connected WebSocket
 * clients, and the live markdown transcript.
 *
 * <p>Every {@link #post(Message)} stores the message, appends it to the
 * transcript and broadcasts it to all clients, so the full conversation is
 * visible to the user and available as shared context to every AI.
 */
public class ChatRoom {

    private final List<Message> history = new ArrayList<>();
    private final CopyOnWriteArraySet<WsContext> clients = new CopyOnWriteArraySet<>();
    private final ChatLogger logger;
    private volatile Supplier<Object> rosterSupplier;
    private volatile Supplier<Object> statusSupplier;
    private volatile int contextWindowMessages = 0; // 0 = unlimited
    private volatile Consumer<Message> messageListener;
    private volatile String contextContent = null;

    public ChatRoom(ChatLogger logger) {
        this.logger = logger;
    }

    /**
     * Set read-only markdown content that is prepended to the AI context window
     * before the chat transcript. Used for context MD files.
     */
    public void setContextContent(String content) {
        this.contextContent = (content != null && !content.isBlank()) ? content : null;
    }

    /** The current context content, or null. */
    public String contextContent() {
        return contextContent;
    }

    /** Load persisted messages into memory without re-logging or triggering agents. */
    public synchronized void restoreHistory(List<Message> messages) {
        if (messages != null && !messages.isEmpty()) {
            history.addAll(messages);
        }
    }

    /** Max messages to include in AI context. 0 = unlimited. */
    public void setContextWindowMessages(int n) {
        this.contextWindowMessages = Math.max(0, n);
    }

    /** Source of the current roster payload (agents + flags) for broadcasts. */
    public void setRosterSupplier(Supplier<Object> rosterSupplier) {
        this.rosterSupplier = rosterSupplier;
    }

    /** Source of the current token-status payload for broadcasts. */
    public void setStatusSupplier(Supplier<Object> statusSupplier) {
        this.statusSupplier = statusSupplier;
    }

    /**
     * Listener fired when a message is finalized (posted non-streaming or
     * endStream'd). Used by {@link io.groupchat.orchestrate.Orchestrator} to
     * detect @mentions in both user and agent messages.
     */
    public void setMessageListener(Consumer<Message> listener) {
        this.messageListener = listener;
    }

    public void addClient(WsContext ctx) {
        clients.add(ctx);
        sendHistory(ctx);
        if (rosterSupplier != null) {
            send(ctx, envelopeData("roster", rosterSupplier.get()));
        }
        if (statusSupplier != null) {
            send(ctx, envelopeData("status", statusSupplier.get()));
        }
    }

    public void removeClient(WsContext ctx) {
        clients.remove(ctx);
    }

    /** Store, persist and broadcast a message. */
    public synchronized Message post(Message message) {
        history.add(message);
        logger.log(message);
        broadcast(envelope("message", message));
        if (messageListener != null) {
            messageListener.accept(message);
        }
        return message;
    }

    /**
     * Begin a streaming message: store it and broadcast the initial chunk as a
     * normal message. Subsequent {@link #appendStream} calls grow the same bubble.
     * Not logged to the transcript until {@link #endStream}.
     */
    public synchronized Message beginStream(Message message) {
        history.add(message);
        broadcast(envelope("message", message));
        return message;
    }

    /** Append an incremental chunk to a streaming message and broadcast the delta. */
    public synchronized void appendStream(Message message, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        message.content = (message.content == null ? "" : message.content) + delta;
        broadcast(Json.write(java.util.Map.of(
                "kind", "append", "id", message.id, "delta", delta)));
    }

    /**
     * Replace a streaming message's content mid-stream (e.g. swap out a
     * "thinking…" placeholder with the first real chunk). Does NOT log to the
     * transcript or fire the message listener — only {@link #endStream} does that.
     */
    public synchronized void replaceContent(Message message, String newContent) {
        if (newContent != null) {
            message.content = newContent;
        }
        broadcast(Json.write(java.util.Map.of(
                "kind", "replace", "id", message.id, "content", message.content)));
    }

    /** Finalize a streaming message with canonical content and persist it. */
    public synchronized void endStream(Message message, String finalContent) {
        if (finalContent != null) {
            message.content = finalContent;
        }
        String content = message.content == null ? "" : message.content;
        broadcast(Json.write(java.util.Map.of(
                "kind", "replace", "id", message.id, "content", content)));
        logger.log(message);
        if (messageListener != null) {
            messageListener.accept(message);
        }
    }

    /** Push the current roster (membership + flags) to all clients. */
    public void broadcastRoster() {
        if (rosterSupplier != null) {
            broadcast(envelopeData("roster", rosterSupplier.get()));
        }
    }

    /** Push the current token-status snapshot to all clients. */
    public void broadcastStatus() {
        if (statusSupplier != null) {
            broadcast(envelopeData("status", statusSupplier.get()));
        }
    }

    private void sendHistory(WsContext ctx) {
        List<Message> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<>(history);
        }
        for (Message m : snapshot) {
            send(ctx, envelope("message", m));
        }
    }

    private void broadcast(String payload) {
        for (WsContext ctx : clients) {
            send(ctx, payload);
        }
    }

    private static void send(WsContext ctx, String payload) {
        try {
            if (ctx.session.isOpen()) {
                ctx.send(payload);
            }
        } catch (Exception ignored) {
            // client may have disconnected mid-broadcast
        }
    }

    private static String envelope(String kind, Message message) {
        return Json.write(java.util.Map.of("kind", kind, "message", message));
    }

    private static String envelopeData(String kind, Object data) {
        return Json.write(java.util.Map.of("kind", kind, "data", data));
    }

    /** Render the recent transcript as markdown for use as AI context. */
    public synchronized String renderContext() {
        StringBuilder sb = new StringBuilder();

        // Prepend read-only context (e.g. from a context MD file) before chat history
        if (contextContent != null) {
            sb.append("# Context Document\n\n")
              .append(contextContent)
              .append("\n\n---\n\n");
        }

        int historyStartPos = sb.length();
        int window = contextWindowMessages;
        int start = window > 0 ? Math.max(0, history.size() - window) : 0;
        if (start > 0) {
            sb.append("(Earlier messages omitted — context window: last "
                    + window + " of " + history.size() + " messages)\n\n");
        }
        for (int i = start; i < history.size(); i++) {
            Message m = history.get(i);
            String name = m.senderName != null ? m.senderName : m.sender;
            sb.append("**").append(name).append("**: ")
              .append(m.content).append("\n\n");
        }
        return sb.toString();
    }

    public synchronized List<Message> history() {
        return new ArrayList<>(history);
    }
}
