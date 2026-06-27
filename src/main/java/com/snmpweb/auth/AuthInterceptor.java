package com.snmpweb.auth;

import com.snmpweb.common.ApiResponse;
import com.snmpweb.common.AuthException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashSet;
import java.util.Set;

@Component
public class AuthInterceptor implements HandlerInterceptor {

    @Autowired
    private AuthManager authManager;

    @Value("${app.auth.token-header:Authorization}")
    private String tokenHeader;

    @Value("${app.auth.token-prefix:Bearer}")
    private String tokenPrefix;

    private static final Set<String> PUBLIC_PATHS = new HashSet<>();
    static {
        PUBLIC_PATHS.add("/api/auth/login");
        PUBLIC_PATHS.add("/api/auth/status");
        PUBLIC_PATHS.add("/api/system/info");
    }

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
            return true;
        }
        String path = request.getRequestURI();
        if (PUBLIC_PATHS.contains(path)) {
            return true;
        }
        if (!path.startsWith("/api/")) {
            return true;
        }
        if (!authManager.isAuthEnabled()) {
            return true;
        }
        String header = request.getHeader(tokenHeader);
        String token = extractToken(header);
        try {
            Session session = authManager.validate(token);
            request.setAttribute("currentUser", session.getUser());
            request.setAttribute("session", session);
            return true;
        } catch (AuthException e) {
            response.setStatus(401);
            response.setContentType("application/json;charset=UTF-8");
            response.getWriter().write("{\"code\":401,\"message\":\"" + e.getMessage() + "\",\"data\":null}");
            return false;
        }
    }

    private String extractToken(String header) {
        if (header == null || header.isEmpty()) {
            return null;
        }
        if (tokenPrefix != null && !tokenPrefix.isEmpty() && header.startsWith(tokenPrefix + " ")) {
            return header.substring(tokenPrefix.length() + 1);
        }
        return header;
    }
}
