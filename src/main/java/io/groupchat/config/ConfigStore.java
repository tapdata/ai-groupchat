package io.groupchat.config;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Loads and persists {@link AppConfig} as local JSON (config/agents.json).
 *
 * <p>The file contains API keys, so it lives only on disk and is never exposed
 * to chat clients or the markdown transcript.
 */
public class ConfigStore {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(SerializationFeature.INDENT_OUTPUT)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private final Path file;
    private AppConfig config;

    public ConfigStore(Path baseDir) {
        this.file = baseDir.resolve("config").resolve("agents.json");
    }

    public synchronized AppConfig load() {
        if (config != null) {
            return config;
        }
        try {
            if (Files.exists(file)) {
                config = MAPPER.readValue(file.toFile(), AppConfig.class);
            } else {
                config = AppConfig.defaults();
                save();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to load config: " + file, e);
        }
        return config;
    }

    public synchronized void save() {
        try {
            Files.createDirectories(file.getParent());
            MAPPER.writeValue(file.toFile(), config);
        } catch (IOException e) {
            throw new RuntimeException("Failed to save config: " + file, e);
        }
    }

    public AppConfig config() {
        return load();
    }

    public Path file() {
        return file;
    }
}
