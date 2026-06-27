package com.snmpweb.device;

import com.snmpweb.common.ApiResponse;
import com.snmpweb.mib.MibService;
import com.snmpweb.mib.model.MibNode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {

    @Autowired
    private DeviceService deviceService;

    @Autowired
    private MibService mibService;

    @GetMapping
    public ApiResponse<List<SnmpDevice>> list() {
        List<SnmpDevice> safe = deviceService.list().stream()
                .map(SnmpDevice::toSafeView)
                .collect(Collectors.toList());
        return ApiResponse.success(safe);
    }

    @GetMapping("/{id}")
    public ApiResponse<SnmpDevice> get(@PathVariable String id) {
        SnmpDevice d = deviceService.get(id);
        if (d == null) return ApiResponse.error(404, "设备不存在");
        return ApiResponse.success(d.toSafeView());
    }

    @PostMapping
    public ApiResponse<SnmpDevice> add(@RequestBody SnmpDevice device) {
        SnmpDevice created = deviceService.add(device);
        return ApiResponse.success(created.toSafeView(), "设备已添加");
    }

    @PutMapping("/{id}")
    public ApiResponse<SnmpDevice> update(@PathVariable String id, @RequestBody SnmpDevice device) {
        device.setId(id);
        SnmpDevice updated = deviceService.update(device);
        if (updated == null) return ApiResponse.error(404, "设备不存在");
        return ApiResponse.success(updated.toSafeView(), "设备已更新");
    }

    @DeleteMapping("/{id}")
    public ApiResponse<Void> delete(@PathVariable String id) {
        boolean ok = deviceService.delete(id);
        if (!ok) return ApiResponse.error(404, "设备不存在");
        return ApiResponse.success(null, "设备已删除");
    }

    @PostMapping("/{id}/test")
    public ApiResponse<Map<String, Object>> test(@PathVariable String id) {
        String result = deviceService.testConnection(id);
        SnmpDevice d = deviceService.get(id);
        Map<String, Object> map = new LinkedHashMap<>();
        if (d != null) {
            map.put("status", d.getStatus());
            map.put("lastCheckedAt", d.getLastCheckedAt());
            map.put("lastError", d.getLastError());
            map.put("sysDescr", result);
        }
        if (result == null) {
            return ApiResponse.error(500, "连接失败: " + (d != null ? d.getLastError() : "未知错误"));
        }
        return ApiResponse.success(map, "连接成功");
    }

    @PostMapping("/refresh-all")
    public ApiResponse<List<SnmpDevice>> refreshAll() {
        List<SnmpDevice> updated = deviceService.refreshAll();
        return ApiResponse.success(updated.stream().map(SnmpDevice::toSafeView).collect(Collectors.toList()),
                "已刷新全部设备状态");
    }

    /**
     * Test a connection using the submitted device parameters WITHOUT saving
     * a device record. Returns the sysDescr and resulting status. This avoids
     * the duplicate-device problem where "测试连接" previously created a temp
     * device via add() and the later "保存" created a second one.
     */
    @PostMapping("/test")
    public ApiResponse<Map<String, Object>> testTransient(@RequestBody SnmpDevice device) {
        String result = deviceService.testConnectionTransient(device);
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", device.getStatus());
        map.put("lastCheckedAt", device.getLastCheckedAt());
        map.put("lastError", device.getLastError());
        map.put("sysDescr", result);
        if (result == null) {
            return ApiResponse.error(500, "连接失败: " + device.getLastError());
        }
        return ApiResponse.success(map, "连接成功");
    }

    /**
     * GET one or more OIDs.
     */
    @PostMapping("/{id}/get")
    public ApiResponse<List<SnmpVarBind>> get(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String[] oids = extractOids(body);
            List<SnmpVarBind> result = deviceService.get(id, oids);
            enrichWithMibInfo(result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("SNMP GET 失败: " + e.getMessage());
        }
    }

    /**
     * GETNEXT one or more OIDs.
     */
    @PostMapping("/{id}/getnext")
    public ApiResponse<List<SnmpVarBind>> getNext(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String[] oids = extractOids(body);
            List<SnmpVarBind> result = deviceService.getNext(id, oids);
            enrichWithMibInfo(result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("SNMP GETNEXT 失败: " + e.getMessage());
        }
    }

    /**
     * GETBULK on a single OID with nonRepeaters and maxRepetitions.
     */
    @PostMapping("/{id}/getbulk")
    public ApiResponse<List<SnmpVarBind>> getBulk(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String oid = (String) body.get("oid");
            int nonRepeaters = body.get("nonRepeaters") == null ? 0 : ((Number) body.get("nonRepeaters")).intValue();
            int maxRepetitions = body.get("maxRepetitions") == null ? 10 : ((Number) body.get("maxRepetitions")).intValue();
            if (oid == null || oid.isEmpty()) return ApiResponse.badRequest("oid 不能为空");
            List<SnmpVarBind> result = deviceService.getBulk(id, oid, nonRepeaters, maxRepetitions);
            enrichWithMibInfo(result);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("SNMP GETBULK 失败: " + e.getMessage());
        }
    }

    /**
     * Walk a whole table subtree.
     */
    @PostMapping("/{id}/walk")
    public ApiResponse<Map<String, Object>> walk(@PathVariable String id, @RequestBody Map<String, Object> body) {
        try {
            String oid = (String) body.get("oid");
            int maxRows = body.get("maxRows") == null ? 200 : ((Number) body.get("maxRows")).intValue();
            if (oid == null || oid.isEmpty()) return ApiResponse.badRequest("oid 不能为空");
            long start = System.currentTimeMillis();
            List<SnmpVarBind> rows = deviceService.walk(id, oid, maxRows);
            long elapsed = System.currentTimeMillis() - start;
            enrichWithMibInfo(rows);
            // Try to resolve table name from MIB
            String tableName = oid;
            MibNode node = mibService.getNodeByOid(oid);
            if (node != null) tableName = node.getName();
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("rootOid", oid);
            result.put("tableName", tableName);
            result.put("rowCount", rows.size());
            result.put("elapsedMs", elapsed);
            result.put("rows", rows);
            return ApiResponse.success(result);
        } catch (Exception e) {
            return ApiResponse.error("SNMP WALK 失败: " + e.getMessage());
        }
    }

    private String[] extractOids(Map<String, Object> body) {
        Object oid = body.get("oid");
        Object oids = body.get("oids");
        List<String> list = new ArrayList<>();
        if (oids instanceof List) {
            for (Object o : (List<?>) oids) {
                list.add(String.valueOf(o));
            }
        } else if (oid != null) {
            list.add(String.valueOf(oid));
        }
        if (list.isEmpty()) {
            throw new IllegalArgumentException("oid 或 oids 不能为空");
        }
        return list.toArray(new String[0]);
    }

    private void enrichWithMibInfo(List<SnmpVarBind> varBinds) {
        if (varBinds == null) return;
        for (SnmpVarBind vb : varBinds) {
            if (vb.getOid() == null) continue;
            MibNode node = mibService.getNodeByOid(vb.getOid());
            if (node == null) {
                // Try progressively shorter prefixes
                String oid = vb.getOid();
                while (oid.contains(".") && node == null) {
                    int idx = oid.lastIndexOf('.');
                    oid = oid.substring(0, idx);
                    node = mibService.getNodeByOid(oid);
                }
            }
            if (node != null) {
                vb.setName(node.getName());
                if (vb.getDataType() == null) {
                    vb.setDataType(node.getSyntax());
                }
            }
        }
    }
}
