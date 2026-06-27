package com.snmpweb.mib;

import com.snmpweb.common.ApiResponse;
import com.snmpweb.mib.model.MibModule;
import com.snmpweb.mib.model.MibNode;
import com.snmpweb.mib.model.UploadProgress;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/mib")
public class MibController {

    @Autowired
    private MibService mibService;

    @PostMapping("/upload")
    public ApiResponse<Map<String, Object>> upload(@RequestParam("files") MultipartFile[] files) throws IOException {
        if (files == null || files.length == 0) {
            return ApiResponse.badRequest("未选择文件");
        }
        List<MultipartFile> list = Arrays.asList(files);
        UploadProgress progress = mibService.processBatch(list);
        Map<String, Object> result = new HashMap<>();
        result.put("batchId", progress.getBatchId());
        result.put("total", progress.getTotal());
        return ApiResponse.success(result, "已接收 " + files.length + " 个文件，开始解析");
    }

    @GetMapping("/upload/progress/{batchId}")
    public ApiResponse<Map<String, Object>> progress(@PathVariable String batchId) {
        UploadProgress p = mibService.getProgress(batchId);
        if (p == null) {
            return ApiResponse.error(404, "批次不存在: " + batchId);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", p.getBatchId());
        result.put("total", p.getTotal());
        result.put("completed", p.getCompleted());
        result.put("success", p.getSuccess());
        result.put("failed", p.getFailed());
        result.put("percent", p.getPercent());
        result.put("status", p.getStatus());
        result.put("duration", p.getDuration());
        result.put("fileResults", p.getFileResults());
        result.put("fileErrors", p.getFileErrors());
        result.put("fileEntries", p.getFileEntries());
        return ApiResponse.success(result);
    }

    @GetMapping("/modules")
    public ApiResponse<List<Map<String, Object>>> modules() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (MibModule m : mibService.getModules()) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("name", m.getName());
            map.put("fileName", m.getFileName());
            map.put("nodeCount", m.getNodes().size());
            map.put("description", m.getDescription());
            map.put("lastUpdated", m.getLastUpdated());
            map.put("organization", m.getOrganization());
            result.add(map);
        }
        return ApiResponse.success(result);
    }

    @GetMapping("/tree")
    public ApiResponse<List<MibNode>> tree(@RequestParam(value = "oid", required = false) String oidPrefix) {
        if (oidPrefix != null && !oidPrefix.isEmpty()) {
            MibNode subtree = mibService.getSubtree(oidPrefix);
            if (subtree == null) {
                return ApiResponse.success(Collections.emptyList());
            }
            return ApiResponse.success(Collections.singletonList(subtree));
        }
        return ApiResponse.success(mibService.buildTree());
    }

    @GetMapping("/node")
    public ApiResponse<MibNode> node(@RequestParam(value = "name", required = false) String name,
                                     @RequestParam(value = "oid", required = false) String oid) {
        MibNode node = null;
        if (name != null && !name.isEmpty()) {
            node = mibService.getNodeByName(name);
        } else if (oid != null && !oid.isEmpty()) {
            node = mibService.getNodeByOid(oid);
        }
        if (node == null) {
            return ApiResponse.error(404, "节点不存在");
        }
        return ApiResponse.success(node);
    }

    @GetMapping("/search")
    public ApiResponse<Map<String, Object>> search(
            @RequestParam(value = "keyword", required = false) String keyword,
            @RequestParam(value = "name", required = false) String nameMatch,
            @RequestParam(value = "oid", required = false) String oidMatch,
            @RequestParam(value = "description", required = false) String descMatch,
            @RequestParam(value = "limit", required = false, defaultValue = "200") int limit) {
        long start = System.currentTimeMillis();
        List<MibNode> results = mibService.search(keyword, nameMatch, oidMatch, descMatch, limit);
        long elapsed = System.currentTimeMillis() - start;
        Map<String, Object> result = new HashMap<>();
        result.put("total", results.size());
        result.put("elapsedMs", elapsed);
        // Return slim view
        List<Map<String, Object>> items = results.stream().map(n -> {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", n.getName());
            m.put("oid", n.getOid());
            m.put("type", n.getType());
            m.put("syntax", n.getSyntax());
            m.put("access", n.getAccess());
            m.put("status", n.getStatus());
            m.put("description", n.getDescription());
            m.put("moduleName", n.getModuleName());
            return m;
        }).collect(Collectors.toList());
        result.put("items", items);
        return ApiResponse.success(result);
    }

    @DeleteMapping("/modules/{moduleName}")
    public ApiResponse<Void> deleteModule(@PathVariable String moduleName) {
        boolean ok = mibService.deleteModule(moduleName);
        if (!ok) {
            return ApiResponse.error(404, "模块不存在: " + moduleName);
        }
        return ApiResponse.success(null, "已删除模块: " + moduleName);
    }

    @DeleteMapping("/modules")
    public ApiResponse<Map<String, Object>> clearAllModules() {
        int removed = mibService.clearAll();
        Map<String, Object> result = new HashMap<>();
        result.put("removed", removed);
        return ApiResponse.success(result, "已清空全部 " + removed + " 个模块");
    }

    @GetMapping("/stats")
    public ApiResponse<Map<String, Object>> stats() {
        Map<String, Object> result = new HashMap<>();
        result.put("moduleCount", mibService.getModules().size());
        result.put("nodeCount", mibService.getNodeCount());
        return ApiResponse.success(result);
    }
}
