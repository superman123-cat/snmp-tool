package com.snmpweb.auth;

import com.snmpweb.common.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    @Autowired
    private AuthManager authManager;

    @Value("${app.auth.token-prefix:Bearer}")
    private String tokenPrefix;

    @Value("${app.auth.token-header:Authorization}")
    private String tokenHeader;

    @PostMapping("/login")
    public ApiResponse<Map<String, Object>> login(@RequestBody Map<String, String> body) {
        String username = body.get("username");
        String password = body.get("password");
        if (username == null || password == null) {
            return ApiResponse.badRequest("用户名和密码不能为空");
        }
        Session session = authManager.login(username, password);
        Map<String, Object> result = new HashMap<>();
        result.put("token", session.getToken());
        result.put("tokenType", tokenPrefix);
        result.put("username", session.getUser().getUsername());
        result.put("displayName", session.getUser().getDisplayName());
        result.put("roles", session.getUser().getRoles());
        return ApiResponse.success(result, "登录成功");
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(HttpServletRequest request) {
        String header = request.getHeader(tokenHeader);
        String token = header;
        if (header != null && header.startsWith(tokenPrefix + " ")) {
            token = header.substring(tokenPrefix.length() + 1);
        }
        authManager.logout(token);
        return ApiResponse.success(null, "已退出登录");
    }

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> status(HttpServletRequest request) {
        Map<String, Object> result = new HashMap<>();
        result.put("authEnabled", authManager.isAuthEnabled());
        String header = request.getHeader(tokenHeader);
        if (header != null && !header.isEmpty()) {
            String token = header.startsWith(tokenPrefix + " ") ? header.substring(tokenPrefix.length() + 1) : header;
            try {
                Session session = authManager.validate(token);
                result.put("valid", true);
                result.put("username", session.getUser().getUsername());
                result.put("displayName", session.getUser().getDisplayName());
                result.put("roles", session.getUser().getRoles());
            } catch (Exception e) {
                result.put("valid", false);
            }
        } else {
            result.put("valid", false);
        }
        return ApiResponse.success(result);
    }
}
