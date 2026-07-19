package io.groupchat.orchestrate;

import io.groupchat.config.AppConfig;

import java.util.List;

/**
 * Pluggable policy for the @all "everyone helps, then we converge" flow.
 *
 * <p>The default implementation collects every agent's answer (already visible in
 * the shared transcript) and then asks one agent to review them and produce a
 * consolidated final answer. Alternative strategies (voting, multi-round debate,
 * ...) can be dropped in without touching the orchestrator.
 */
public interface ReviewStrategy {

    /** One collected answer from a single agent. */
    final class Answer {
        public final String agentId;
        public final String agentName;
        public final String content;

        public Answer(String agentId, String agentName, String content) {
            this.agentId = agentId;
            this.agentName = agentName;
            this.content = content;
        }
    }

    String name();

    /** Id of the agent that performs the final synthesis, or null to skip synthesis. */
    String synthesizerId(AppConfig config, List<Answer> answers);

    /** Instruction handed to the synthesizer; the shared transcript is supplied separately as context. */
    String buildSynthesisPrompt(String question, List<Answer> answers);
}
