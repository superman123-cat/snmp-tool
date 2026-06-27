package com.snmpweb.auth;

public class Session {
    private String token;
    private User user;
    private long createdAt;
    private long lastAccessedAt;
    private long ttlMillis;

    public Session(String token, User user, long ttlMillis) {
        this.token = token;
        this.user = user;
        this.ttlMillis = ttlMillis;
        long now = System.currentTimeMillis();
        this.createdAt = now;
        this.lastAccessedAt = now;
    }

    public String getToken() { return token; }
    public User getUser() { return user; }
    public long getCreatedAt() { return createdAt; }
    public long getLastAccessedAt() { return lastAccessedAt; }

    public boolean isExpired() {
        return System.currentTimeMillis() - lastAccessedAt > ttlMillis;
    }

    public void touch() {
        this.lastAccessedAt = System.currentTimeMillis();
    }
}
