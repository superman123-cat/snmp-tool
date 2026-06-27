package com.snmpweb.controller;

import com.snmpweb.common.ApiResponse;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    @Autowired
    private Environment env;

    @GetMapping("/info")
    public ApiResponse<Map<String, Object>> info() {
        Map<String, Object> result = new HashMap<>();
        result.put("name", "SNMP NetConf Tool");
        result.put("version", "1.0.0");
        result.put("javaVersion", System.getProperty("java.version"));
        result.put("serverPort", env.getProperty("server.port"));
        return ApiResponse.success(result);
    }
}
