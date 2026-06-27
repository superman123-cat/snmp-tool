package com.snmpweb.device;

import com.snmpweb.snmp.SnmpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class DeviceService {

    private static final Logger log = LoggerFactory.getLogger(DeviceService.class);

    private final ConcurrentMap<String, SnmpDevice> devices = new ConcurrentHashMap<>();

    public List<SnmpDevice> list() {
        return new ArrayList<>(devices.values());
    }

    public SnmpDevice get(String id) {
        return devices.get(id);
    }

    public SnmpDevice add(SnmpDevice device) {
        if (device.getId() == null) {
            device.setId(java.util.UUID.randomUUID().toString().replace("-", ""));
        }
        applyDefaults(device);
        devices.put(device.getId(), device);
        return device;
    }

    public SnmpDevice update(SnmpDevice device) {
        SnmpDevice existing = devices.get(device.getId());
        if (existing == null) {
            return null;
        }
        applyDefaults(device);
        // Keep password from existing if the new value is the mask "********"
        if ("********".equals(device.getAuthPassword())) {
            device.setAuthPassword(existing.getAuthPassword());
        }
        if ("********".equals(device.getPrivPassword())) {
            device.setPrivPassword(existing.getPrivPassword());
        }
        devices.put(device.getId(), device);
        return device;
    }

    public boolean delete(String id) {
        return devices.remove(id) != null;
    }

    private void applyDefaults(SnmpDevice d) {
        if (d.getPort() <= 0) d.setPort(161);
        if (d.getVersion() == null || d.getVersion().isEmpty()) d.setVersion("v2c");
        if (d.getTimeoutMs() <= 0) d.setTimeoutMs(3000);
        if (d.getRetries() < 0) d.setRetries(1);
        if ("v1".equalsIgnoreCase(d.getVersion()) || "v2c".equalsIgnoreCase(d.getVersion())) {
            if (d.getCommunity() == null || d.getCommunity().isEmpty()) {
                d.setCommunity("public");
            }
        } else if ("v3".equalsIgnoreCase(d.getVersion())) {
            if (d.getSecurityLevel() == null || d.getSecurityLevel().isEmpty()) {
                if ((d.getAuthPassword() != null && !d.getAuthPassword().isEmpty())
                        && (d.getPrivPassword() != null && !d.getPrivPassword().isEmpty())) {
                    d.setSecurityLevel("authPriv");
                } else if (d.getAuthPassword() != null && !d.getAuthPassword().isEmpty()) {
                    d.setSecurityLevel("authNoPriv");
                } else {
                    d.setSecurityLevel("noAuthNoPriv");
                }
            }
            if (d.getAuthProtocol() == null) {
                d.setAuthProtocol("MD5");
            }
            if (d.getPrivProtocol() == null) {
                d.setPrivProtocol("DES");
            }
        }
    }

    /**
     * Test connection to a device. Updates the device status accordingly.
     */
    public String testConnection(String deviceId) {
        SnmpDevice device = devices.get(deviceId);
        if (device == null) return null;
        return testConnectionInternal(device);
    }

    /**
     * Test connection using transient device parameters WITHOUT persisting the
     * device. Used by the "测试连接" button in the add/edit form so that testing
     * before save does not create a duplicate device record (previously the form
     * called add() just to obtain an id, and the subsequent save() created a
     * second record).
     */
    public String testConnectionTransient(SnmpDevice device) {
        applyDefaults(device);
        return testConnectionInternal(device);
    }

    public String testConnectionInternal(SnmpDevice device) {
        device.setStatus(SnmpDevice.STATUS_CONNECTING);
        try (SnmpClient client = new SnmpClient(device)) {
            String result = client.test();
            device.setStatus(SnmpDevice.STATUS_ONLINE);
            device.setLastCheckedAt(System.currentTimeMillis());
            device.setLastError(null);
            return result;
        } catch (Exception e) {
            String msg = e.getMessage();
            log.warn("Connection test failed for {} {}: {}", device.getName(), device.getHost(), msg);
            if (msg != null && (msg.contains("auth") || msg.contains("Auth") || msg.contains("认证")
                    || msg.contains("wrong") || msg.contains("credential"))) {
                device.setStatus(SnmpDevice.STATUS_AUTH_FAILED);
            } else {
                device.setStatus(SnmpDevice.STATUS_OFFLINE);
            }
            device.setLastCheckedAt(System.currentTimeMillis());
            device.setLastError(msg);
            return null;
        }
    }

    /**
     * Refresh the status of all devices.
     */
    public List<SnmpDevice> refreshAll() {
        List<SnmpDevice> snapshot = new ArrayList<>(devices.values());
        for (SnmpDevice d : snapshot) {
            testConnectionInternal(d);
        }
        return snapshot;
    }

    /**
     * Run a single GET on a device for one or more OIDs.
     */
    public List<SnmpVarBind> get(String deviceId, String[] oids) throws Exception {
        SnmpDevice device = devices.get(deviceId);
        if (device == null) throw new IllegalArgumentException("设备不存在: " + deviceId);
        try (SnmpClient client = new SnmpClient(device)) {
            List<SnmpVarBind> result = client.get(oids);
            device.setStatus(SnmpDevice.STATUS_ONLINE);
            device.setLastCheckedAt(System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            markFailed(device, e);
            throw e;
        }
    }

    public List<SnmpVarBind> getNext(String deviceId, String[] oids) throws Exception {
        SnmpDevice device = devices.get(deviceId);
        if (device == null) throw new IllegalArgumentException("设备不存在: " + deviceId);
        try (SnmpClient client = new SnmpClient(device)) {
            List<SnmpVarBind> result = client.getNext(oids);
            device.setStatus(SnmpDevice.STATUS_ONLINE);
            device.setLastCheckedAt(System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            markFailed(device, e);
            throw e;
        }
    }

    public List<SnmpVarBind> getBulk(String deviceId, String oid, int nonRepeaters, int maxRepetitions) throws Exception {
        SnmpDevice device = devices.get(deviceId);
        if (device == null) throw new IllegalArgumentException("设备不存在: " + deviceId);
        try (SnmpClient client = new SnmpClient(device)) {
            List<SnmpVarBind> result = client.getBulk(oid, nonRepeaters, maxRepetitions);
            device.setStatus(SnmpDevice.STATUS_ONLINE);
            device.setLastCheckedAt(System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            markFailed(device, e);
            throw e;
        }
    }

    public List<SnmpVarBind> walk(String deviceId, String rootOid, int maxRows) throws Exception {
        SnmpDevice device = devices.get(deviceId);
        if (device == null) throw new IllegalArgumentException("设备不存在: " + deviceId);
        try (SnmpClient client = new SnmpClient(device)) {
            List<SnmpVarBind> result = client.walk(rootOid, maxRows);
            device.setStatus(SnmpDevice.STATUS_ONLINE);
            device.setLastCheckedAt(System.currentTimeMillis());
            return result;
        } catch (Exception e) {
            markFailed(device, e);
            throw e;
        }
    }

    private void markFailed(SnmpDevice device, Exception e) {
        device.setLastCheckedAt(System.currentTimeMillis());
        device.setLastError(e.getMessage());
        String msg = e.getMessage();
        if (msg != null && (msg.contains("auth") || msg.contains("认证"))) {
            device.setStatus(SnmpDevice.STATUS_AUTH_FAILED);
        } else {
            device.setStatus(SnmpDevice.STATUS_OFFLINE);
        }
    }

    public Map<String, SnmpDevice> getDeviceMap() {
        return devices;
    }
}
