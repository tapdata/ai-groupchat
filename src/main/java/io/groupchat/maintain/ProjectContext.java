package io.groupchat.maintain;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Detects whether the app is running from its own source tree ("owner" mode).
 *
 * <p>Ownership means a {@code pom.xml} belonging to this project is reachable from
 * the working directory. Only the owner may self-optimize and restart; plain
 * users running a distributed jar get the chat features but not self-editing.
 */
public class ProjectContext {

    private static final String MARKER = "ai-groupchat";

    /** True when this process can edit and rebuild its own source. */
    public final boolean owner;
    /** Directory containing the project pom.xml, or null when not the owner. */
    public final Path projectDir;
    /** Path to the runnable fat jar, or null when it cannot be located. */
    public final Path jarPath;

    private ProjectContext(boolean owner, Path projectDir, Path jarPath) {
        this.owner = owner;
        this.projectDir = projectDir;
        this.jarPath = jarPath;
    }

    public static ProjectContext detect(Path baseDir) {
        Path project = findProjectDir(baseDir);
        Path jar = locateJar();
        if (jar == null && project != null) {
            jar = project.resolve("target").resolve("ai-groupchat.jar");
        }
        return new ProjectContext(project != null, project, jar);
    }

    /** Walk up from baseDir looking for our pom.xml (max a few levels). */
    private static Path findProjectDir(Path baseDir) {
        Path dir = baseDir == null ? null : baseDir.toAbsolutePath();
        for (int i = 0; dir != null && i < 4; i++) {
            Path pom = dir.resolve("pom.xml");
            if (Files.isRegularFile(pom) && isOurPom(pom)) {
                return dir;
            }
            dir = dir.getParent();
        }
        return null;
    }

    private static boolean isOurPom(Path pom) {
        try {
            String text = Files.readString(pom, StandardCharsets.UTF_8);
            return text.contains("<artifactId>" + MARKER + "</artifactId>");
        } catch (Exception e) {
            return false;
        }
    }

    /** Resolve the jar this class was loaded from, if it is a real file. */
    private static Path locateJar() {
        try {
            Path p = Path.of(ProjectContext.class.getProtectionDomain()
                    .getCodeSource().getLocation().toURI());
            if (Files.isRegularFile(p) && p.toString().endsWith(".jar")) {
                return p;
            }
        } catch (Exception ignored) {
            // running from exploded classes (dev); fall back to target/ jar
        }
        return null;
    }
}
