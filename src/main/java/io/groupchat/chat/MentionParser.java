package io.groupchat.chat;

import io.groupchat.model.Agent;
import io.groupchat.config.AppConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses @mentions from a user message.
 *
 * <p>Recognizes {@code @all} / {@code @everyone} / {@code @所有人} as a broadcast to
 * every enabled agent, and {@code @<agentId>} for a specific agent. Matching of
 * agent handles is case-insensitive.
 */
public final class MentionParser {

    /** Result of parsing one message. */
    public static final class Result {
        /** Agent ids explicitly targeted. */
        public final List<String> targets;
        /** True when the message addresses everyone. */
        public final boolean broadcast;

        public Result(List<String> targets, boolean broadcast) {
            this.targets = targets;
            this.broadcast = broadcast;
        }

        public boolean hasMentions() {
            return broadcast || !targets.isEmpty();
        }
    }

    private MentionParser() {
    }

    public static Result parse(String content, AppConfig config) {
        Set<String> targets = new LinkedHashSet<>();
        boolean broadcast = false;
        if (content == null) {
            return new Result(new ArrayList<>(), false);
        }
        for (String token : tokens(content)) {
            String handle = token.toLowerCase();
            if (handle.equals("all") || handle.equals("everyone") || token.equals("所有人")) {
                broadcast = true;
                continue;
            }
            for (Agent agent : config.agents) {
                if (agent.id != null && agent.id.equalsIgnoreCase(handle)) {
                    targets.add(agent.id);
                }
            }
        }
        return new Result(new ArrayList<>(targets), broadcast);
    }

    /** Extract bare handles following '@' up to the next whitespace/punctuation. */
    private static List<String> tokens(String content) {
        List<String> result = new ArrayList<>();
        int i = 0;
        while (i < content.length()) {
            if (content.charAt(i) == '@') {
                int j = i + 1;
                StringBuilder sb = new StringBuilder();
                while (j < content.length() && isHandleChar(content.charAt(j))) {
                    sb.append(content.charAt(j));
                    j++;
                }
                if (sb.length() > 0) {
                    result.add(sb.toString());
                }
                i = j;
            } else {
                i++;
            }
        }
        return result;
    }

    private static boolean isHandleChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '-' || c >= 0x4E00;
    }
}
