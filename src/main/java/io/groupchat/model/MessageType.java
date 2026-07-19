package io.groupchat.model;

/**
 * Kind of message shown in the group chat.
 */
public enum MessageType {
    /** A message typed by the human user. */
    USER,
    /** A reply produced by an AI agent. */
    AGENT,
    /** A system notice (config changes, join/leave, errors). */
    SYSTEM,
    /** A consolidated answer produced by the review/synthesis phase. */
    SYNTHESIS
}
