package io.groupchat.security;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;

/** One registered browser/device allowed to use the remote chat UI. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TrustedDevice {
    public String id;
    public String name;
    public String tokenHash;
    public String role = "user";
    public boolean enabled = true;
    public String firstSeenIp;
    public String lastSeenIp;
    public Instant createdAt;
    public Instant lastSeenAt;
}
