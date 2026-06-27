package com.snmpweb.auth;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.util.HashSet;
import java.util.Set;

public class User {
    private String username;
    /** BCrypt hash of the user's password. Never serialized to clients. */
    @JsonIgnore
    private String passwordHash;
    private Set<String> roles = new HashSet<>();
    private String displayName;

    public User() {}

    public User(String username, String passwordHash, String displayName, String... roles) {
        this.username = username;
        this.passwordHash = passwordHash;
        this.displayName = displayName;
        for (String r : roles) {
            this.roles.add(r);
        }
    }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }
    /** @return BCrypt hash, never the plaintext password. */
    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }
    /** Legacy accessor for BCrypt verification; returns the hash, not plaintext. */
    public String getPassword() { return passwordHash; }
    public void setPassword(String passwordHash) { this.passwordHash = passwordHash; }
    public Set<String> getRoles() { return roles; }
    public void setRoles(Set<String> roles) { this.roles = roles; }
    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public boolean hasRole(String role) {
        return roles.contains(role);
    }
}
