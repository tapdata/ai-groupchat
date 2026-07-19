package io.groupchat.util;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Small helper to run an external command with an optional working directory and
 * stdin, capturing the combined stdout+stderr and enforcing a timeout.
 *
 * <p>stdin is written on a separate thread and output is drained on another so a
 * chatty or slow process cannot deadlock the caller.
 */
public final class Proc {

    /** Outcome of a single command run. */
    public static final class Result {
        public final int exit;
        public final String output;
        public final boolean timedOut;

        public Result(int exit, String output, boolean timedOut) {
            this.exit = exit;
            this.output = output;
            this.timedOut = timedOut;
        }

        public boolean ok() {
            return exit == 0 && !timedOut;
        }
    }

    private Proc() {
    }

    public static Result run(List<String> cmd, Path workdir, String stdin,
                             long timeout, TimeUnit unit) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (workdir != null) {
                pb.directory(workdir.toFile());
            }
            pb.redirectErrorStream(true);
            Process p = pb.start();

            Thread writer = new Thread(() -> {
                try (OutputStream os = p.getOutputStream()) {
                    if (stdin != null) {
                        os.write(stdin.getBytes(StandardCharsets.UTF_8));
                    }
                    os.flush();
                } catch (Exception ignored) {
                    // process may not read stdin
                }
            });
            writer.setDaemon(true);
            writer.start();

            StringBuilder out = new StringBuilder();
            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(new InputStreamReader(
                        p.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        synchronized (out) {
                            out.append(line).append('\n');
                        }
                    }
                } catch (Exception ignored) {
                    // stream closed when the process exits
                }
            });
            reader.setDaemon(true);
            reader.start();

            boolean done = p.waitFor(timeout, unit);
            if (!done) {
                p.destroyForcibly();
            }
            reader.join(2000);
            synchronized (out) {
                return new Result(done ? p.exitValue() : -1, out.toString().trim(), !done);
            }
        } catch (Exception e) {
            return new Result(-1, "Process failed: " + e.getMessage(), false);
        }
    }

    /** True when the given executable resolves on the current PATH. */
    public static boolean onPath(String tool) {
        if (tool == null || tool.isBlank()) {
            return false;
        }
        boolean windows = System.getProperty("os.name", "").toLowerCase().contains("win");
        List<String> probe = windows ? List.of("where", tool) : List.of("which", tool);
        Result r = run(probe, null, null, 10, TimeUnit.SECONDS);
        return r.exit == 0 && !r.output.isBlank();
    }
}
