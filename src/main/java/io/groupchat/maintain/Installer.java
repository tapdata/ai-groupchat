package io.groupchat.maintain;

import io.groupchat.config.AppConfig;
import io.groupchat.chat.ChatRoom;
import io.groupchat.model.Agent;
import io.groupchat.model.Message;
import io.groupchat.util.Proc;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Installs the CLI tools that backing agents need, so a user who only has Java 17
 * can still use CLI-based members. Missing tools are installed quietly in the
 * background at startup; {@link #installNow} surfaces progress for manual runs.
 */
public class Installer {

    private final AppConfig config;
    private final ChatRoom room;
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "installer");
        t.setDaemon(true);
        return t;
    });

    public Installer(AppConfig config, ChatRoom room) {
        this.config = config;
        this.room = room;
    }

    /** Check every CLI agent at startup and silently install whatever is missing. */
    public void ensureAllInBackground() {
        pool.submit(() -> {
            for (Agent a : config.agents) {
                if (!a.enabled || !"cli".equalsIgnoreCase(a.provider)) {
                    continue;
                }
                if (a.installCommand == null || a.installCommand.isBlank()) {
                    continue;
                }
                String tool = firstToken(a.command);
                if (tool == null || Proc.onPath(tool)) {
                    continue;
                }
                runInstall(a, false);
            }
        });
    }

    /** Manually (re)install one agent or all, reporting progress to the chat. */
    public void installNow(String agentId) {
        pool.submit(() -> {
            for (Agent a : config.agents) {
                if (agentId != null && !agentId.equalsIgnoreCase(a.id)) {
                    continue;
                }
                if (a.installCommand == null || a.installCommand.isBlank()) {
                    if (agentId != null) {
                        room.post(Message.system("No installCommand configured for " + a.id));
                    }
                    continue;
                }
                runInstall(a, true);
            }
        });
    }

    private void runInstall(Agent a, boolean announce) {
        if (announce) {
            room.post(Message.system("Installing " + a.name + ": " + a.installCommand));
        } else {
            System.out.println("[installer] " + a.id + ": " + a.installCommand);
        }
        Proc.Result r = Proc.run(shell(a.installCommand), null, null, 10, TimeUnit.MINUTES);
        if (announce) {
            if (r.ok()) {
                room.post(Message.system(a.name + " installed successfully."));
            } else {
                room.post(Message.system(a.name + " install failed (exit " + r.exit + "): " + tail(r.output)));
            }
        } else {
            System.out.println("[installer] " + a.id + " exit=" + r.exit);
        }
    }

    private static List<String> shell(String command) {
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        return windows ? List.of("cmd", "/c", command) : List.of("bash", "-lc", command);
    }

    private static String firstToken(String command) {
        if (command == null || command.isBlank()) {
            return null;
        }
        String first = command.strip().split("\\s+", 2)[0];
        int slash = Math.max(first.lastIndexOf('/'), first.lastIndexOf('\\'));
        return slash >= 0 ? first.substring(slash + 1) : first;
    }

    private static String tail(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= 500 ? text : text.substring(text.length() - 500);
    }
}
