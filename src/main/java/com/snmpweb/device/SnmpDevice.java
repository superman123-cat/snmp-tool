package com.snmpweb.device;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SnmpDevice {
    public static final String STATUS_ONLINE = "online";
    public static final String STATUS_OFFLINE = "offline";
    public static final String STATUS_CONNECTING = "connecting";
    public static final String STATUS_AUTH_FAILED = "auth_failed";
    public static final String STATUS_UNKNOWN = "unknown";

    private String id;
    private String name;
    private String host;
    private int port;
    private String version; // v1, v2c, v3
    private int timeoutMs;
    private int retries;
    // v1/v2c
    private String community;
    // v3
    private String securityName;
    private String authProtocol; // none, MD5, SHA
    private String authPassword;
    private String privProtocol; // none, DES, AES
    private String privPassword;
    private String securityLevel; // noAuthNoPriv, authNoPriv, authPriv
    private String contextName;
    // runtime
    private AtomicReference<String> status = new AtomicReference<>(STATUS_UNKNOWN);
    private long lastCheckedAt;
    private String lastError;

    public SnmpDevice() {
        this.id = UUID.randomUUID().toString().replace("-", "");
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }
    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }
    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }
    public int getTimeoutMs() { return timeoutMs; }
    public void setTimeoutMs(int timeoutMs) { this.timeoutMs = timeoutMs; }
    public int getRetries() { return retries; }
    public void setRetries(int retries) { this.retries = retries; }
    public String getCommunity() { return community; }
    public void setCommunity(String community) { this.community = community; }
    public String getSecurityName() { return securityName; }
    public void setSecurityName(String securityName) { this.securityName = securityName; }
    public String getAuthProtocol() { return authProtocol; }
    public void setAuthProtocol(String authProtocol) { this.authProtocol = authProtocol; }
    public String getAuthPassword() { return authPassword; }
    public void setAuthPassword(String authPassword) { this.authPassword = authPassword; }
    public String getPrivProtocol() { return privProtocol; }
    public void setPrivProtocol(String privProtocol) { this.privProtocol = privProtocol; }
    public String getPrivPassword() { return privPassword; }
    public void setPrivPassword(String privPassword) { this.privPassword = privPassword; }
    public String getSecurityLevel() { return securityLevel; }
    public void setSecurityLevel(String securityLevel) { this.securityLevel = securityLevel; }
    public String getContextName() { return contextName; }
    public void setContextName(String contextName) { this.contextName = contextName; }

    public String getStatus() { return status.get(); }
    public void setStatus(String s) { status.set(s); }
    public long getLastCheckedAt() { return lastCheckedAt; }
    public void setLastCheckedAt(long t) { this.lastCheckedAt = t; }
    public String getLastError() { return lastError; }
    public void setLastError(String e) { this.lastError = e; }

    /**
     * Return a copy without sensitive password fields for safe transmission to the client.
     */
    public SnmpDevice toSafeView() {
        SnmpDevice v = new SnmpDevice();
        v.id = this.id;
        v.name = this.name;
        v.host = this.host;
        v.port = this.port;
        v.version = this.version;
        v.timeoutMs = this.timeoutMs;
        v.retries = this.retries;
        v.community = this.community;
        v.securityName = this.securityName;
        v.authProtocol = this.authProtocol;
        v.privProtocol = this.privProtocol;
        v.securityLevel = this.securityLevel;
        v.contextName = this.contextName;
        // Mask passwords
        v.authPassword = (this.authPassword != null && !this.authPassword.isEmpty()) ? "********" : null;
        v.privPassword = (this.privPassword != null && !this.privPassword.isEmpty()) ? "********" : null;
        v.status = new AtomicReference<>(this.getStatus());
        v.lastCheckedAt = this.lastCheckedAt;
        v.lastError = this.lastError;
        return v;
    }
}
