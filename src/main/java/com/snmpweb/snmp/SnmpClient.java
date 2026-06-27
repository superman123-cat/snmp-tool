package com.snmpweb.snmp;

import com.snmpweb.device.SnmpDevice;
import com.snmpweb.device.SnmpVarBind;
import org.snmp4j.CommunityTarget;
import org.snmp4j.PDU;
import org.snmp4j.PDUv1;
import org.snmp4j.ScopedPDU;
import org.snmp4j.Snmp;
import org.snmp4j.Target;
import org.snmp4j.TransportMapping;
import org.snmp4j.UserTarget;
import org.snmp4j.event.ResponseEvent;
import org.snmp4j.mp.MPv3;
import org.snmp4j.mp.SnmpConstants;
import org.snmp4j.security.AuthMD5;
import org.snmp4j.security.AuthSHA;
import org.snmp4j.security.PrivAES128;
import org.snmp4j.security.PrivDES;
import org.snmp4j.security.SecurityLevel;
import org.snmp4j.security.SecurityModels;
import org.snmp4j.security.SecurityProtocols;
import org.snmp4j.security.USM;
import org.snmp4j.security.UsmUser;
import org.snmp4j.smi.Address;
import org.snmp4j.smi.GenericAddress;
import org.snmp4j.smi.Integer32;
import org.snmp4j.smi.OID;
import org.snmp4j.smi.OctetString;
import org.snmp4j.smi.Variable;
import org.snmp4j.smi.VariableBinding;
import org.snmp4j.transport.DefaultUdpTransportMapping;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * SNMP communication layer implemented with SNMP4J.
 * Supports v1/v2c/v3, GET / GETNEXT / GETBULK, with retry handling.
 */
public class SnmpClient implements AutoCloseable {

    private final SnmpDevice device;
    private Snmp snmp;
    private TransportMapping<? extends Address> transport;
    private Target<?> target;

    public SnmpClient(SnmpDevice device) throws IOException {
        this.device = device;
        init();
    }

    private void init() throws IOException {
        transport = new DefaultUdpTransportMapping();
        snmp = new Snmp(transport);
        if (isV3()) {
            // Configure USM and register security protocols
            USM usm = new USM(SecurityProtocols.getInstance(),
                    new OctetString(MPv3.createLocalEngineID()), 0);
            SecurityModels.getInstance().addSecurityModel(usm);
            SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthMD5());
            SecurityProtocols.getInstance().addAuthenticationProtocol(new AuthSHA());
            SecurityProtocols.getInstance().addPrivacyProtocol(new PrivDES());
            SecurityProtocols.getInstance().addPrivacyProtocol(new PrivAES128());
            // Add the user
            OctetString user = octet(device.getSecurityName());
            OID authOid = resolveAuthProtocol(device.getAuthProtocol());
            OID privOid = resolvePrivProtocol(device.getPrivProtocol());
            UsmUser usmUser = new UsmUser(user,
                    authOid,
                    octet(device.getAuthPassword()),
                    privOid,
                    octet(device.getPrivPassword()));
            snmp.getUSM().addUser(user, usmUser);
        }
        transport.listen();
        target = buildTarget();
    }

    private boolean isV3() {
        return "v3".equalsIgnoreCase(device.getVersion());
    }

    @SuppressWarnings("rawtypes")
    private Target buildTarget() {
        Address addr = GenericAddress.parse("udp:" + device.getHost() + "/" + device.getPort());
        Target t;
        int securityLevel = resolveSecurityLevel();
        int version = resolveVersion();
        if (isV3()) {
            UserTarget ut = new UserTarget();
            ut.setAddress(addr);
            ut.setRetries(device.getRetries());
            ut.setTimeout(device.getTimeoutMs());
            ut.setVersion(version);
            ut.setSecurityLevel(securityLevel);
            ut.setSecurityName(octet(device.getSecurityName()));
            t = ut;
        } else {
            CommunityTarget ct = new CommunityTarget();
            ct.setAddress(addr);
            ct.setCommunity(octet(device.getCommunity() == null ? "public" : device.getCommunity()));
            ct.setRetries(device.getRetries());
            ct.setTimeout(device.getTimeoutMs());
            ct.setVersion(version);
            ct.setSecurityLevel(SecurityLevel.NOAUTH_NOPRIV);
            t = ct;
        }
        return t;
    }

    private int resolveVersion() {
        String v = device.getVersion();
        if ("v1".equalsIgnoreCase(v)) return SnmpConstants.version1;
        if ("v3".equalsIgnoreCase(v)) return SnmpConstants.version3;
        return SnmpConstants.version2c; // default
    }

    private int resolveSecurityLevel() {
        String lvl = device.getSecurityLevel();
        if ("authPriv".equalsIgnoreCase(lvl)) return SecurityLevel.AUTH_PRIV;
        if ("authNoPriv".equalsIgnoreCase(lvl)) return SecurityLevel.AUTH_NOPRIV;
        return SecurityLevel.NOAUTH_NOPRIV;
    }

    private OID resolveAuthProtocol(String p) {
        if (p == null) return null;
        switch (p.toUpperCase()) {
            case "MD5": return AuthMD5.ID;
            case "SHA":
            case "SHA1": return AuthSHA.ID;
            default: return null;
        }
    }

    private OID resolvePrivProtocol(String p) {
        if (p == null) return null;
        switch (p.toUpperCase()) {
            case "DES": return PrivDES.ID;
            case "AES":
            case "AES128": return PrivAES128.ID;
            default: return null;
        }
    }

    private OctetString octet(String s) {
        return s == null ? new OctetString() : new OctetString(s);
    }

    private PDU createPdu(int type) {
        PDU pdu;
        if (isV3()) {
            pdu = new ScopedPDU();
            if (device.getContextName() != null && !device.getContextName().isEmpty()) {
                ((ScopedPDU) pdu).setContextName(octet(device.getContextName()));
            }
        } else {
            pdu = (resolveVersion() == SnmpConstants.version1) ? new PDUv1() : new PDU();
        }
        pdu.setType(type);
        return pdu;
    }

    /**
     * Perform an SNMP GET for one or more OIDs.
     */
    public List<SnmpVarBind> get(String... oids) throws IOException {
        PDU pdu = createPdu(PDU.GET);
        for (String o : oids) {
            pdu.add(new VariableBinding(new OID(o)));
        }
        ResponseEvent response = snmp.send(pdu, target);
        return toVarBinds(response, oids);
    }

    /**
     * Perform an SNMP GETNEXT.
     */
    public List<SnmpVarBind> getNext(String... oids) throws IOException {
        PDU pdu = createPdu(PDU.GETNEXT);
        for (String o : oids) {
            pdu.add(new VariableBinding(new OID(o)));
        }
        ResponseEvent response = snmp.send(pdu, target);
        return toVarBinds(response, oids);
    }

    /**
     * Perform an SNMP GETBULK, returning up to maxRepetitions rows for non-repeaters.
     */
    public List<SnmpVarBind> getBulk(String oid, int nonRepeaters, int maxRepetitions) throws IOException {
        PDU pdu = createPdu(PDU.GETBULK);
        pdu.setMaxRepetitions(maxRepetitions);
        pdu.setNonRepeaters(nonRepeaters);
        pdu.add(new VariableBinding(new OID(oid)));
        ResponseEvent response = snmp.send(pdu, target);
        return toVarBinds(response, null);
    }

    /**
     * Walk a subtree using GETBULK (v2c/v3) or GETNEXT chain (v1).
     * Stops when the returned OID leaves the subtree or an end-of-tree is reached.
     */
    public List<SnmpVarBind> walk(String rootOid, int maxRows) throws IOException {
        List<SnmpVarBind> results = new ArrayList<>();
        String current = rootOid;
        int safety = maxRows <= 0 ? 1000 : Math.min(maxRows, 5000);
        boolean useBulk = !isV1();
        int bulkSize = 10;
        while (results.size() < maxRows + 0 && safety-- > 0) {
            List<SnmpVarBind> batch;
            if (useBulk) {
                batch = getBulk(current, 0, Math.min(bulkSize, Math.max(1, maxRows - results.size())));
            } else {
                batch = getNext(current);
            }
            if (batch.isEmpty()) break;
            boolean anyInSubtree = false;
            for (SnmpVarBind vb : batch) {
                if (vb.getOid() == null || vb.getOid().startsWith("null")) continue;
                if (!vb.getOid().startsWith(rootOid + ".") && !vb.getOid().equals(rootOid)) {
                    // left the subtree
                    return results;
                }
                // Check endOfMibView style sentinel
                if (vb.getValue() == null && "endOfMibView".equalsIgnoreCase(vb.getSyntax())) {
                    return results;
                }
                if (vb.getValue() != null) {
                    results.add(vb);
                    anyInSubtree = true;
                    current = vb.getOid();
                    if (results.size() >= maxRows) return results;
                }
            }
            if (!anyInSubtree) break;
        }
        return results;
    }

    private boolean isV1() {
        return resolveVersion() == SnmpConstants.version1;
    }

    private List<SnmpVarBind> toVarBinds(ResponseEvent response, String[] requestedOids) throws IOException {
        List<SnmpVarBind> result = new ArrayList<>();
        if (response == null) {
            return result;
        }
        PDU responsePdu = response.getResponse();
        if (responsePdu == null) {
            // timeout
            throw new IOException("SNMP 响应超时 (目标未响应或超时时间过短)");
        }
        int errorStatus = responsePdu.getErrorStatus();
        if (errorStatus != PDU.noError) {
            throw new IOException("SNMP 错误: " + responsePdu.getErrorStatusText()
                    + " (code=" + errorStatus + ")");
        }
        for (Object o : responsePdu.getVariableBindings()) {
            VariableBinding vb = (VariableBinding) o;
            SnmpVarBind var = new SnmpVarBind();
            var.setOid(vvOid(vb.getOid()));
            Variable v = vb.getOid() != null ? vb.getVariable() : null;
            if (v != null) {
                String syntaxStr = v.getSyntaxString();
                var.setSyntax(syntaxStr);
                var.setDataType(resolveDataType(v));
                var.setValue(toDisplayValue(v));
                var.setDisplayValue(v.toString());
            } else {
                var.setSyntax("null");
            }
            result.add(var);
        }
        return result;
    }

    private String vvOid(OID oid) {
        return oid == null ? null : oid.toString();
    }

    private String toDisplayValue(Variable v) {
        if (v == null) return null;
        try {
            // Octet strings: show hex if non-printable
            if (v instanceof OctetString) {
                OctetString os = (OctetString) v;
                byte[] bytes = os.getValue();
                if (bytes != null && bytes.length > 0 && isPrintable(bytes)) {
                    return new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
                }
                return os.toHexString();
            }
            if (v instanceof Integer32) {
                return String.valueOf(v.toInt());
            }
            return v.toString();
        } catch (Exception e) {
            return v.toString();
        }
    }

    private boolean isPrintable(byte[] bytes) {
        for (byte b : bytes) {
            if (b < 0x20 && b != 0x09 && b != 0x0A && b != 0x0D) return false;
        }
        return true;
    }

    private String resolveDataType(Variable v) {
        if (v == null) return "Unknown";
        String syntaxStr = v.getSyntaxString();
        if (syntaxStr != null && !syntaxStr.isEmpty()) {
            return syntaxStr;
        }
        return v.getClass().getSimpleName();
    }

    /**
     * Quick connectivity test: GET sysDescr (1.3.6.1.2.1.1.1.0). Returns the value on success.
     */
    public String test() throws IOException {
        try {
            List<SnmpVarBind> r = get("1.3.6.1.2.1.1.1.0");
            if (r.isEmpty()) {
                throw new IOException("设备未返回任何数据");
            }
            SnmpVarBind vb = r.get(0);
            return vb.getValue();
        } catch (IOException e) {
            // Try a GETNEXT in case sysDescr.0 doesn't exist on the device
            try {
                List<SnmpVarBind> r = getNext("1.3.6.1.2.1.1.1");
                if (!r.isEmpty()) return r.get(0).getValue();
            } catch (IOException ignore) {}
            throw e;
        }
    }

    @Override
    public void close() {
        try {
            if (transport != null) transport.close();
        } catch (Exception ignored) {}
        try {
            if (snmp != null) snmp.close();
        } catch (Exception ignored) {}
    }
}
