package io.groupchat.maintain;

import io.groupchat.chat.ChatRoom;
import io.groupchat.config.AppConfig;
import io.groupchat.model.Agent;
import io.groupchat.model.Message;
import io.groupchat.util.Proc;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Self-optimization for the project owner: hands a natural-language requirement to
 * a CLI coding agent running in the project directory, rebuilds with Maven, and on
 * a successful build restarts the service with the new jar.
 */
public class Maintainer {

    private final AppConfig config;
    private final ChatRoom room;
    private final ProjectContext ctx;
    private final ServiceController service;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "maintainer");
        t.setDaemon(true);
        return t;
    });

    public Maintainer(AppConfig config, ChatRoom room, ProjectContext ctx,
                      ServiceController service) {
        this.config = config;
        this.room = room;
        this.ctx = ctx;
        this.service = service;
    }

    /** Run a self-optimization request asynchronously. */
    public void optimize(String requirement) {
        if (!ctx.owner) {
            room.post(Message.system("Self-optimization is only available to the project owner "
                    + "(running from the source tree). Skipping."));
            return;
        }
        if (requirement == null || requirement.isBlank()) {
            room.post(Message.system("Usage: /optimize <what you want changed>"));
            return;
        }
        Agent coder = pickCoder();
        if (coder == null) {
            room.post(Message.system("No ready CLI coding agent available to optimize the project. "
                    + "Configure a cli agent and set it as maintainer with /config or /synthesizer."));
            return;
        }
        pool.submit(() -> runOptimize(coder, requirement));
    }

    private void runOptimize(Agent coder, String requirement) {
        room.post(Message.system(coder.name + " is optimizing the project: " + requirement));
        String prompt = "You are editing the Java project in this working directory "
                + "(an app called ai-groupchat). Implement the following change directly in the "
                + "source files, keeping the build compiling.\n\nRequirement:\n" + requirement;
        Proc.Result edit = Proc.run(CliCommand.of(coder.command), ctx.projectDir, prompt, 15, TimeUnit.MINUTES);
        room.post(Message.agent(coder.id, coder.name, tail(edit.output)));
        if (edit.timedOut) {
            room.post(Message.system("Optimization timed out; not rebuilding."));
            return;
        }

        room.post(Message.system("Building with Maven…"));
        Proc.Result build = Proc.run(List.of("mvn", "-q", "-batch-mode", "package", "-DskipTests"),
                ctx.projectDir, null, 15, TimeUnit.MINUTES);
        if (!build.ok()) {
            room.post(Message.system("Build failed (exit " + build.exit + "), keeping the running version:\n"
                    + tail(build.output)));
            return;
        }
        room.post(Message.system("Build succeeded. Restarting with the new version."));
        service.restart(room);
    }

    /** Choose the maintainer agent (must be a ready CLI tool). */
    private Agent pickCoder() {
        Agent preferred = config.findAgent(config.maintainerId).orElse(null);
        if (isUsable(preferred)) {
            return preferred;
        }
        for (Agent a : config.agents) {
            if (isUsable(a)) {
                return a;
            }
        }
        return null;
    }

    private static boolean isUsable(Agent a) {
        return a != null && a.enabled && "cli".equalsIgnoreCase(a.provider) && a.isReady();
    }

    private static String tail(String text) {
        if (text == null || text.isBlank()) {
            return "(no output)";
        }
        return text.length() <= 4000 ? text : "…" + text.substring(text.length() - 4000);
    }

    /** Whitespace/quote aware split of a CLI command line. */
    static final class CliCommand {
        static List<String> of(String command) {
            return io.groupchat.provider.CliProvider.parseCommand(command);
        }
    }
}
