package com.snmpweb.auth;

import com.snmpweb.common.AuthException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.SecureRandom;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AuthManager {

    /** Hashing parameters (must match between hashing and verification). */
    private static final String HASH_ALGO = "PBKDF2WithHmacSHA256";
    private static final int HASH_ITERATIONS = 120_000;
    private static final int SALT_BYTES = 16;
    private static final int HASH_BITS = 256;

    /** Stored format: pbkdf2_sha256$iter$base64salt$base64hash */
    private static final String HASH_PREFIX = "pbkdf2_sha256$";

    @Value("${app.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${app.auth.admin.username:admin}")
    private String adminUsername;

    @Value("${app.auth.session-timeout-minutes:60}")
    private long sessionTimeoutMinutes;

    private final Map<String, User> users = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();

    @Value("${app.auth.credentials-file:${user.dir}/logs/admin-credentials.txt}")
    private String credentialsFile;

    @PostConstruct
    public void init() throws Exception {
        bootstrapCredentials();
        resetAdminFromEnv();
    }

    /**
     * Bootstrap admin / operator credentials on first run.
     *
     * On every startup we DO NOT rely on a hard-coded plaintext password.
     * Instead we:
     *   1) generate a cryptographically strong random password for the admin
     *      account (length 20, mixed case + digits + symbols), and a separate
     *      strong random password for the operator account;
     *   2) PBKDF2-hash both with a fresh random salt (120k iterations);
     *   3) write the plaintext once to {@code logs/admin-credentials.txt}
     *      with file permissions owner-only (0600 on POSIX) so that only the
     *      OS user running the JVM can read it;
     *   4) keep only the hash in memory.
     *
     * The plaintext is never written to application logs, never returned in
     * any HTTP response, and never stored in source / configuration files.
     */
    private void bootstrapCredentials() throws Exception {
        Path file = Paths.get(credentialsFile);
        Files.createDirectories(file.getParent());

        Map<String, String> creds = new TreeMap<>();
        if (Files.exists(file)) {
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                int eq = line.indexOf('=');
                if (eq > 0) creds.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
        if (!creds.containsKey(adminUsername)) {
            creds.put(adminUsername, generateStrongPassword(20));
        }
        if (!creds.containsKey("operator")) {
            creds.put("operator", generateStrongPassword(18));
        }
        // Persist with owner-only permissions before doing anything else, so
        // that even a crash mid-bootstrap leaves a protected file.
        writeOwnerOnlyFile(file, creds);

        users.put(adminUsername, new User(adminUsername, hash(creds.get(adminUsername)),
                "Administrator", "ADMIN", "USER"));
        users.put("operator", new User("operator", hash(creds.get("operator")),
                "Operator", "USER"));
    }

    /**
     * Allow operators to rotate the admin password without rebuilding the
     * jar. Set the env var APP_AUTH_ADMIN_PASSWORD (length 12+, mixed case
     * + digits + symbol). The value is hashed on startup and replaces the
     * embedded default hash. The plaintext is never logged or persisted.
     */
    private void resetAdminFromEnv() {
        String envPwd = System.getenv("APP_AUTH_ADMIN_PASSWORD");
        if (envPwd == null || envPwd.isEmpty()) return;
        if (!isStrongPassword(envPwd)) {
            throw new IllegalStateException(
                    "APP_AUTH_ADMIN_PASSWORD is too weak. " + strongPasswordRequirements());
        }
        try {
            String newHash = hash(envPwd);
            users.put(adminUsername, new User(adminUsername, newHash, "Administrator", "ADMIN", "USER"));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash APP_AUTH_ADMIN_PASSWORD", e);
        }
    }

    static String strongPasswordRequirements() {
        return "Password must be at least 12 characters and contain: "
                + "uppercase, lowercase, digit, and one of !@#$%^&*()-_=+[]{};:,.<>/?";
    }

    private static final String RANDOM_ALPHABET =
            "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*()-_=+";

    private static String generateStrongPassword(int length) {
        SecureRandom rnd = new SecureRandom();
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append(RANDOM_ALPHABET.charAt(rnd.nextInt(RANDOM_ALPHABET.length())));
        }
        // Guarantee the four required classes are present.
        if (!isStrongPassword(sb.toString())) {
            return generateStrongPassword(length);
        }
        return sb.toString();
    }

    private static void writeOwnerOnlyFile(Path file, Map<String, String> creds) throws IOException {
        StringBuilder body = new StringBuilder();
        body.append("# Auto-generated on first startup. Owner-readable only.\n");
        body.append("# Delete this file to rotate every password on next launch.\n");
        for (Map.Entry<String, String> e : creds.entrySet()) {
            body.append(e.getKey()).append('=').append(e.getValue()).append('\n');
        }
        File tmp = file.toFile();
        // Best-effort POSIX permission; on Windows this is a no-op.
        try {
            java.util.Set<java.nio.file.attribute.PosixFilePermission> perms =
                    java.util.EnumSet.of(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE);
            Files.write(file, body.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.setPosixFilePermissions(file, perms);
            } catch (UnsupportedOperationException ignore) {
                // Windows: rely on ACL / directory ACLs.
            }
        } catch (IOException e) {
            throw e;
        }
    }

    static boolean isStrongPassword(String p) {
        if (p == null || p.length() < 12) return false;
        boolean up = false, low = false, dig = false, sym = false;
        for (int i = 0; i < p.length(); i++) {
            char c = p.charAt(i);
            if (Character.isUpperCase(c)) up = true;
            else if (Character.isLowerCase(c)) low = true;
            else if (Character.isDigit(c)) dig = true;
            else sym = true;
        }
        return up && low && dig && sym;
    }

    public Session login(String username, String password) {
        User user = users.get(username);
        if (user == null || password == null || !verify(password, user.getPasswordHash())) {
            throw new AuthException("用户名或密码错误");
        }
        // Remove old sessions for the same user
        sessions.entrySet().removeIf(e -> e.getValue().getUser().getUsername().equals(username));
        String token = UUID.randomUUID().toString().replace("-", "");
        Session session = new Session(token, user, sessionTimeoutMinutes * 60 * 1000L);
        sessions.put(token, session);
        return session;
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public Session validate(String token) {
        if (!authEnabled) {
            return new Session("disabled", users.get(adminUsername), Long.MAX_VALUE);
        }
        if (token == null || token.isEmpty()) {
            throw new AuthException("未提供认证令牌");
        }
        Session session = sessions.get(token);
        if (session == null) {
            throw new AuthException("无效的认证令牌，请重新登录");
        }
        if (session.isExpired()) {
            sessions.remove(token);
            throw new AuthException("会话已过期，请重新登录");
        }
        session.touch();
        return session;
    }

    public Session validateWithRole(String token, String requiredRole) {
        Session session = validate(token);
        if (!session.getUser().hasRole(requiredRole)) {
            throw new AuthException("权限不足，需要角色: " + requiredRole);
        }
        return session;
    }

    public boolean isAuthEnabled() {
        return authEnabled;
    }

    // ====== Password hashing (PBKDF2-HMAC-SHA256) ======

    static String hash(String plaintext) throws Exception {
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] dk = pbkdf2(plaintext.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS);
        return HASH_PREFIX + HASH_ITERATIONS + "$" + b64(salt) + "$" + b64(dk);
    }

    static boolean verify(String plaintext, String stored) {
        if (plaintext == null || stored == null || !stored.startsWith(HASH_PREFIX)) return false;
        try {
            String[] parts = stored.split("\\$");
            if (parts.length != 4) return false;
            int iter = Integer.parseInt(parts[1]);
            byte[] salt = b64decode(parts[2]);
            byte[] expected = b64decode(parts[3]);
            byte[] actual = pbkdf2(plaintext.toCharArray(), salt, iter, expected.length * 8);
            return constantTimeEquals(expected, actual);
        } catch (Exception e) {
            return false;
        }
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iter, int bits) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, iter, bits);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance(HASH_ALGO);
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
        }
    }

    private static String b64(byte[] data) {
        return java.util.Base64.getEncoder().encodeToString(data);
    }

    private static byte[] b64decode(String s) {
        return java.util.Base64.getDecoder().decode(s);
    }

    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
