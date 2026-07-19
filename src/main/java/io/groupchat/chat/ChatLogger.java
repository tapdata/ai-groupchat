package io.groupchat.chat;

import io.groupchat.config.AppConfig;
import io.groupchat.model.Agent;
import io.groupchat.model.Message;
import io.groupchat.model.MessageType;

import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Appends every chat message to a local markdown transcript in real time.
 *
 * <p>API keys and other secrets pulled from the live {@link AppConfig} are masked
 * before anything is written, so the transcript is safe to share.
 */
public class ChatLogger {

    private static final DateTimeFormatter TS =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter FILE_TS =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").withZone(ZoneId.systemDefault());
    private static final Pattern ENTRY =
            Pattern.compile("(?m)^### (.+?) · (USER|AGENT|SYSTEM|SYNTHESIS) · (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2})\\R\\R");

    private final Path file;
    private final AppConfig config;

    public ChatLogger(Path baseDir, AppConfig config) {
        this(baseDir, config, null);
    }

    public ChatLogger(Path baseDir, AppConfig config, Path transcriptFile) {
        this.config = config;
        Path dir;
        if (transcriptFile != null) {
            Path resolved = transcriptFile.isAbsolute() ? transcriptFile : baseDir.resolve(transcriptFile);
            this.file = resolved.normalize();
            dir = this.file.getParent();
        } else {
            dir = baseDir.resolve("logs");
            this.file = dir.resolve("chat-" + FILE_TS.format(Instant.now()) + ".md");
        }
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to create logs dir: " + dir, e);
        }
        if (isNewOrEmpty()) {
            writeHeader();
        }
    }

    private void writeHeader() {
        write("# AI Group Chat\n\n_Started " + TS.format(Instant.now()) + "_\n\n");
    }

    private boolean isNewOrEmpty() {
        try {
            return !Files.exists(file) || Files.size(file) == 0;
        } catch (IOException e) {
            throw new RuntimeException("Failed to inspect transcript: " + file, e);
        }
    }

    public synchronized void log(Message message) {
        String time = TS.format(Instant.ofEpochMilli(message.timestamp));
        String name = message.senderName != null ? message.senderName : message.sender;
        StringBuilder sb = new StringBuilder();
        sb.append("### ").append(name)
          .append(" · ").append(message.type)
          .append(" · ").append(time).append("\n\n");
        sb.append(mask(message.content)).append("\n\n");
        write(sb.toString());
    }

    /** Replace any configured secret values with a placeholder. */
    String mask(String text) {
        if (text == null || text.isEmpty()) {
            return text;
        }
        String result = text;
        for (Agent agent : config.agents) {
            if (agent.apiKey != null && agent.apiKey.length() >= 6) {
                result = result.replace(agent.apiKey, "***");
            }
        }
        return result;
    }

    private void write(String content) {
        try (Writer w = Files.newBufferedWriter(file, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            w.write(content);
        } catch (IOException e) {
            throw new RuntimeException("Failed to write transcript: " + file, e);
        }
    }

    public Path file() {
        return file;
    }

    public void markResumed() {
        write("\n---\n\n_Resumed " + TS.format(Instant.now()) + "_\n\n");
    }

    public List<Message> readHistory() {
        if (!Files.exists(file)) {
            return List.of();
        }
        String markdown;
        try {
            markdown = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read transcript: " + file, e);
        }

        List<Message> messages = new ArrayList<>();
        Matcher matcher = ENTRY.matcher(markdown);
        List<Match> matches = new ArrayList<>();
        while (matcher.find()) {
            matches.add(new Match(matcher.start(), matcher.end(), matcher.group(1), matcher.group(2), matcher.group(3)));
        }
        for (int i = 0; i < matches.size(); i++) {
            Match current = matches.get(i);
            int contentEnd = i + 1 < matches.size() ? matches.get(i + 1).start : markdown.length();
            String content = markdown.substring(current.end, contentEnd).stripTrailing();
            Message message = new Message(MessageType.valueOf(current.type), senderFor(current.name, current.type),
                    current.name, content);
            message.timestamp = parseTimestamp(current.time);
            messages.add(message);
        }
        return messages;
    }

    private long parseTimestamp(String time) {
        try {
            LocalDateTime parsed = LocalDateTime.parse(time, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            return parsed.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
        } catch (DateTimeParseException e) {
            return System.currentTimeMillis();
        }
    }

    private String senderFor(String name, String type) {
        if ("USER".equals(type)) {
            return "user";
        }
        if ("SYSTEM".equals(type)) {
            return "system";
        }
        if ("SYNTHESIS".equals(type)) {
            return "synthesis";
        }
        for (Agent agent : config.agents) {
            if (agent.name != null && agent.name.equals(name)) {
                return agent.id;
            }
        }
        return name == null ? "agent" : name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]+", "");
    }

    private record Match(int start, int end, String name, String type, String time) {
    }
}
