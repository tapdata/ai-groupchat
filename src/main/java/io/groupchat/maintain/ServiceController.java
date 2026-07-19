package io.groupchat.maintain;

import io.groupchat.chat.ChatRoom;
import io.groupchat.model.Message;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Restarts the running chat service in place by spawning a detached launcher that
 * waits for this process to exit and then starts a fresh JVM from the jar.
 */
public class ServiceController {

    private final ProjectContext ctx;

    public ServiceController(ProjectContext ctx) {
        this.ctx = ctx;
    }

    /** Spawn a replacement process and schedule this one to exit. */
    public void restart(ChatRoom room) {
        Path jar = ctx.jarPath;
        if (jar == null || !Files.isRegularFile(jar)) {
            room.post(Message.system("Cannot restart: runnable jar not found"
                    + (jar != null ? " at " + jar : "") + ". Build first with mvn package."));
            return;
        }
        try {
            Path workdir = Path.of(System.getProperty("user.dir"));
            boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
            String java = System.getProperty("java.home") + "/bin/java";
            String run = quote(java) + " -jar " + quote(jar.toString());
            List<String> cmd = windows
                    ? List.of("cmd", "/c", "ping 127.0.0.1 -n 2 > nul & " + run)
                    : List.of("bash", "-c", "sleep 1; " + run);
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(workdir.toFile());
            pb.inheritIO();
            pb.start();

            room.post(Message.system("Restarting service… this window will reconnect shortly."));
            new Thread(() -> {
                try {
                    Thread.sleep(600);
                } catch (InterruptedException ignored) {
                }
                System.exit(0);
            }, "restart-exit").start();
        } catch (Exception e) {
            room.post(Message.system("Restart failed: " + e.getMessage()));
        }
    }

    private static String quote(String s) {
        return "\"" + s + "\"";
    }
}
