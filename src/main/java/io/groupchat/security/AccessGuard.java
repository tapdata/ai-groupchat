package io.groupchat.security;

import io.javalin.http.Context;
import io.javalin.websocket.WsConnectContext;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/** Request gate for API and WebSocket access. */
public class AccessGuard {

    private final DeviceRegistry devices;

    public AccessGuard(DeviceRegistry devices) {
        this.devices = devices;
    }

    public void handleHttp(Context ctx) {
        if (isPublicHttpPath(ctx.path())) {
            return;
        }
        AuthResult auth = authenticate(ctx.ip(), ctx.header("Cookie"), ctx.host());
        ctx.attribute("gc-auth", auth);
        if (auth.allowed()) {
            return;
        }
        ctx.status(401).json(Map.of(
                "error", "Device not trusted",
                "message", "Open this page locally and run /device pair <name>, then enter the code here."
        ));
        ctx.skipRemainingHandlers();
    }

    public boolean allowWs(WsConnectContext ctx) {
        return authenticate(wsIp(ctx), ctx.header("Cookie"), ctx.host()).allowed();
    }

    public AuthResult authenticate(String ip, String cookieHeader) {
        return authenticate(ip, cookieHeader, null);
    }

    public AuthResult authenticate(String ip, String cookieHeader, String host) {
        AccessConfig cfg = devices.config();
        if (!cfg.enabled) {
            return new AuthResult(true, true, false, null, ip, "access disabled");
        }
        if (cfg.allowLocalhost && isLocalhost(ip) && isLocalHostHeader(host)) {
            return new AuthResult(true, true, true, null, ip, "localhost");
        }
        if (isAllowedIp(ip, cfg.allowedIps)) {
            return new AuthResult(true, true, false, null, ip, "allowed ip");
        }
        String token = cookieValue(cookieHeader, cfg.tokenCookieName);
        TrustedDevice device = devices.authenticate(token, ip);
        if (device != null) {
            return new AuthResult(true, true, false, device, ip, "trusted device");
        }
        return new AuthResult(false, false, false, null, ip, "untrusted device");
    }

    public Map<String, Object> statusPayload(String ip, String cookieHeader) {
        return statusPayload(ip, cookieHeader, null);
    }

    public Map<String, Object> statusPayload(String ip, String cookieHeader, String host) {
        AuthResult auth = authenticate(ip, cookieHeader, host);
        AccessConfig cfg = devices.config();
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("enabled", cfg.enabled);
        map.put("authenticated", auth.authenticated());
        map.put("localhost", auth.localhost());
        map.put("reason", auth.reason());
        map.put("ip", ip);
        if (auth.device() != null) {
            map.put("deviceId", auth.device().id);
            map.put("deviceName", auth.device().name);
            map.put("role", auth.device().role);
        }
        return map;
    }

    public String cookieName() {
        return devices.config().tokenCookieName;
    }

    private static boolean isPublicHttpPath(String path) {
        if (path == null) return false;
        return path.equals("/")
                || path.equals("/index.html")
                || path.equals("/app.js")
                || path.equals("/styles.css")
                || path.equals("/api/access/status")
                || path.equals("/api/access/claim")
                || path.equals("/api/access/logout");
    }

    private static String wsIp(WsConnectContext ctx) {
        try {
            if (ctx.session.getRemoteAddress() instanceof InetSocketAddress address) {
                return address.getAddress().getHostAddress();
            }
        } catch (Exception e) {
            // fall through
        }
        return ctx.host();
    }

    private static String cookieValue(String header, String name) {
        if (header == null || name == null || name.isBlank()) {
            return null;
        }
        String[] parts = header.split(";");
        for (String part : parts) {
            int eq = part.indexOf('=');
            if (eq < 0) continue;
            String k = part.substring(0, eq).trim();
            if (name.equals(k)) {
                return part.substring(eq + 1).trim();
            }
        }
        return null;
    }

    private static boolean isLocalhost(String ip) {
        String h = stripBrackets(ip);
        return "127.0.0.1".equals(h)
                || "0:0:0:0:0:0:0:1".equals(h)
                || "::1".equals(h)
                || "localhost".equalsIgnoreCase(h);
    }

    /** Javalin/Jetty may return IPv6 addresses wrapped in brackets, e.g. [::1]. */
    private static String stripBrackets(String ip) {
        if (ip == null) return null;
        String h = ip.strip();
        if (h.startsWith("[") && h.endsWith("]")) {
            return h.substring(1, h.length() - 1);
        }
        return h;
    }

    private static boolean isLocalHostHeader(String host) {
        if (host == null || host.isBlank()) {
            return true;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.startsWith("localhost:") || h.equals("localhost")) return true;
        if (h.startsWith("127.0.0.1:") || h.equals("127.0.0.1")) return true;
        return h.startsWith("[::1]:") || h.equals("[::1]") || h.equals("::1");
    }

    private static boolean isAllowedIp(String ip, Iterable<String> rules) {
        if (ip == null || rules == null) return false;
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) continue;
            if (ipMatches(ip, rule.strip())) return true;
        }
        return false;
    }

    private static boolean ipMatches(String ip, String rule) {
        String cleanIp = stripBrackets(ip);
        if (!rule.contains("/")) {
            return cleanIp.equals(rule);
        }
        try {
            String[] parts = rule.split("/", 2);
            byte[] address = InetAddress.getByName(cleanIp).getAddress();
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            if (address.length != network.length) return false;
            int maskLen = Integer.parseInt(parts[1]);
            int fullBytes = maskLen / 8;
            int remainingBits = maskLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (address[i] != network[i]) return false;
            }
            if (remainingBits == 0) return true;
            int mask = 0xff << (8 - remainingBits);
            return (address[fullBytes] & mask) == (network[fullBytes] & mask);
        } catch (Exception e) {
            return false;
        }
    }

    public record AuthResult(boolean allowed, boolean authenticated, boolean localhost,
                             TrustedDevice device, String ip, String reason) {}
}
