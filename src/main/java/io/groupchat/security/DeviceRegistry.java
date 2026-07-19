package io.groupchat.security;

import com.fasterxml.jackson.core.type.TypeReference;
import io.groupchat.util.Json;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Loads access settings and manages pairing codes plus trusted device tokens. */
public class DeviceRegistry {

    private static final SecureRandom RNG = new SecureRandom();

    private final Path file;
    private final Map<String, PendingPair> pendingPairs = new LinkedHashMap<>();
    private AccessConfig config;

    public DeviceRegistry(Path baseDir) {
        this.file = baseDir.resolve("config").resolve("access.json").normalize();
        load();
    }

    public synchronized AccessConfig config() {
        return config;
    }

    public synchronized boolean enabled() {
        return config.enabled;
    }

    public synchronized void setEnabled(boolean enabled) {
        config.enabled = enabled;
        save();
    }

    public synchronized List<TrustedDevice> devices() {
        return new ArrayList<>(config.devices);
    }

    public synchronized TrustedDevice authenticate(String token, String ip) {
        if (token == null || token.isBlank()) {
            return null;
        }
        String hash = hash(token);
        for (TrustedDevice device : config.devices) {
            if (device.enabled && hash.equals(device.tokenHash)) {
                device.lastSeenIp = ip;
                device.lastSeenAt = Instant.now();
                save();
                return device;
            }
        }
        return null;
    }

    public synchronized PairingCode createPairingCode(String name) {
        pruneExpiredPairs();
        String code = randomDigits(6);
        while (pendingPairs.containsKey(code)) {
            code = randomDigits(6);
        }
        String cleanName = (name == null || name.isBlank()) ? "Remote device" : name.strip();
        Instant expiresAt = Instant.now().plusSeconds(Math.max(1, config.pairingMinutes) * 60L);
        pendingPairs.put(code, new PendingPair(cleanName, expiresAt));
        return new PairingCode(code, cleanName, expiresAt);
    }

    public synchronized RegisteredDevice claimPairingCode(String code, String fallbackName, String ip) {
        pruneExpiredPairs();
        String normalized = code == null ? "" : code.replaceAll("[^0-9]", "");
        PendingPair pair = pendingPairs.remove(normalized);
        if (pair == null) {
            return null;
        }
        String token = randomToken();
        TrustedDevice device = new TrustedDevice();
        device.id = randomId();
        device.name = !pair.name.isBlank() ? pair.name
                : ((fallbackName == null || fallbackName.isBlank()) ? "Remote device" : fallbackName.strip());
        device.tokenHash = hash(token);
        device.role = "user";
        device.enabled = true;
        device.firstSeenIp = ip;
        device.lastSeenIp = ip;
        device.createdAt = Instant.now();
        device.lastSeenAt = device.createdAt;
        config.devices.add(device);
        save();
        return new RegisteredDevice(device, token);
    }

    public synchronized boolean revoke(String idOrName) {
        if (idOrName == null || idOrName.isBlank()) {
            return false;
        }
        String key = idOrName.strip().toLowerCase(Locale.ROOT);
        boolean removed = config.devices.removeIf(d ->
                key.equalsIgnoreCase(d.id) || (d.name != null && key.equals(d.name.toLowerCase(Locale.ROOT))));
        if (removed) {
            save();
        }
        return removed;
    }

    public synchronized String statusText() {
        pruneExpiredPairs();
        StringBuilder sb = new StringBuilder();
        sb.append("Access control: ").append(config.enabled ? "ON" : "OFF").append("\n");
        sb.append("Localhost allowed: ").append(config.allowLocalhost).append("\n");
        sb.append("Trusted devices: ").append(config.devices.size()).append("\n");
        sb.append("Pending pairing codes: ").append(pendingPairs.size());
        return sb.toString();
    }

    private void load() {
        try {
            Files.createDirectories(file.getParent());
            if (Files.exists(file)) {
                config = Json.MAPPER.readValue(file.toFile(), new TypeReference<AccessConfig>() {});
            } else {
                config = AccessConfig.defaults();
                save();
            }
        } catch (Exception e) {
            System.err.println("WARNING: Failed to load access config: " + e.getMessage());
            config = AccessConfig.defaults();
        }
        if (config.allowedIps == null) config.allowedIps = new ArrayList<>();
        if (config.devices == null) config.devices = new ArrayList<>();
        if (config.tokenCookieName == null || config.tokenCookieName.isBlank()) {
            config.tokenCookieName = "gc_device";
        }
        if (config.pairingMinutes <= 0) {
            config.pairingMinutes = 10;
        }
    }

    private void save() {
        try {
            Files.createDirectories(file.getParent());
            Json.MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), config);
        } catch (Exception e) {
            System.err.println("WARNING: Failed to save access config: " + e.getMessage());
        }
    }

    private void pruneExpiredPairs() {
        Instant now = Instant.now();
        pendingPairs.entrySet().removeIf(e -> e.getValue().expiresAt.isBefore(now));
    }

    private static String randomDigits(int len) {
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(RNG.nextInt(10));
        }
        return sb.toString();
    }

    private static String randomId() {
        byte[] bytes = new byte[8];
        RNG.nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }

    private static String randomToken() {
        byte[] bytes = new byte[32];
        RNG.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public static String hash(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] out = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(out);
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record PendingPair(String name, Instant expiresAt) {}

    public record PairingCode(String code, String name, Instant expiresAt) {}

    public record RegisteredDevice(TrustedDevice device, String token) {}
}
