package io.groupchat.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

/** Persisted access-control settings and trusted browser devices. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class AccessConfig {

    /** When true, non-local clients need a trusted device token. */
    public boolean enabled = true;
    /** Always allow loopback access so the owner cannot lock themselves out. */
    public boolean allowLocalhost = true;
    /** Optional IP/CIDR allowlist for LAN/VPN networks. */
    public List<String> allowedIps = new ArrayList<>();
    /** Cookie name used for trusted browser device tokens. */
    public String tokenCookieName = "gc_device";
    /** Pairing code lifetime in minutes. */
    public int pairingMinutes = 10;
    /** Registered devices. Tokens are stored as SHA-256 hashes, not plaintext. */
    public List<TrustedDevice> devices = new ArrayList<>();

    public static AccessConfig defaults() {
        return new AccessConfig();
    }
}
