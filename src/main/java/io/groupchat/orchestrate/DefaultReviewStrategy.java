package io.groupchat.orchestrate;

import io.groupchat.config.AppConfig;
import io.groupchat.model.Agent;

import java.util.List;

/**
 * Default review policy: after every agent has answered, one designated agent
 * reviews all answers (visible in the shared transcript) and writes a single
 * consolidated reply.
 */
public class DefaultReviewStrategy implements ReviewStrategy {

    @Override
    public String name() {
        return "default-synthesis";
    }

    @Override
    public String synthesizerId(AppConfig config, List<Answer> answers) {
        if (config.synthesizerId != null) {
            Agent a = config.findAgent(config.synthesizerId).orElse(null);
            if (a != null && a.enabled) {
                return a.id;
            }
        }
        // fall back to the first agent that produced an answer
        return answers.isEmpty() ? null : answers.get(0).agentId;
    }

    @Override
    public String buildSynthesisPrompt(String question, List<Answer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("You are acting as the moderator of this AI group chat.\n\n");
        sb.append("The user's question was:\n\n").append(question).append("\n\n");
        sb.append("Each member has already posted their answer above (")
          .append(answers.size()).append(" answers");
        if (!answers.isEmpty()) {
            sb.append(" from ");
            for (int i = 0; i < answers.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(answers.get(i).agentName);
            }
        }
        sb.append(").\n\n");
        sb.append("Review all of them critically, reconcile disagreements, and write a single ")
          .append("consolidated final answer for the user. Note where members agreed and ")
          .append("call out anything important they missed. Be concise and decisive.");
        return sb.toString();
    }
}
