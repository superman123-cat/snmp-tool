package com.snmpweb.mib;

import com.snmpweb.mib.model.MibModule;
import com.snmpweb.mib.model.MibNode;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Custom MIB file parser. Handles the ASN.1-like MIB syntax including
 * MODULE-IDENTITY, OBJECT-TYPE, OBJECT-IDENTITY, NOTIFICATION-TYPE,
 * OBJECT-GROUP, TEXTUAL-CONVENTION and OID assignments.
 *
 * Multi-pass approach:
 * 1) Strip comments
 * 2) Extract module + raw node definitions
 * 3) Resolve OIDs through parent chain across all modules
 */
public class MibParser {

    private static final Pattern MODULE_HEADER = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+DEFINITIONS\\s*(?::?=\\s*)?BEGIN", Pattern.DOTALL);

    private static final Pattern OBJECT_TYPE_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+OBJECT-TYPE\\s+(.*?)::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern MODULE_IDENTITY_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+MODULE-IDENTITY\\s+(.*?)::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern OBJECT_IDENTITY_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+OBJECT-IDENTITY\\s+(.*?)::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern NOTIFICATION_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+NOTIFICATION-TYPE\\s+(.*?)::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern OBJECT_GROUP_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+OBJECT-GROUP\\s+(.*?)::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern TEXTUAL_CONV_BLOCK = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+::=\\s+TEXTUAL-CONVENTION(.*?)(?=\n[A-Za-z][A-Za-z0-9-]*\\s+(OBJECT-TYPE|OBJECT-IDENTITY|MODULE-IDENTITY|NOTIFICATION-TYPE|OBJECT-GROUP|OBJECT IDENTIFIER|DEFINITIONS|::=))",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern OID_ASSIGNMENT = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+OBJECT\\s+IDENTIFIER\\s*::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern SIMPLE_ASSIGNMENT = Pattern.compile(
            "([A-Za-z][A-Za-z0-9-]*)\\s+::=\\s*\\{([^}]*)\\}",
            Pattern.DOTALL);

    private static final Pattern SYNTAX_PATTERN = Pattern.compile(
            "SYNTAX\\s+([\\s\\S]*?)(?=MAX-ACCESS|MIN-ACCESS|ACCESS|STATUS|UNITS|DESCRIPTION|INDEX|DEFVAL|::=|\\Z)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ACCESS_PATTERN = Pattern.compile(
            "(?:MAX-ACCESS|ACCESS|MIN-ACCESS)\\s+(read-only|read-write|read-create|not-accessible|accessible-for-notify|write-only)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern STATUS_PATTERN = Pattern.compile(
            "STATUS\\s+(current|deprecated|obsolete|mandatory)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern DESCRIPTION_PATTERN = Pattern.compile(
            "DESCRIPTION\\s+\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    private static final Pattern UNITS_PATTERN = Pattern.compile(
            "UNITS\\s+\"((?:[^\"\\\\]|\\\\.)*)\"",
            Pattern.DOTALL);

    private static final Pattern INDEX_PATTERN = Pattern.compile(
            "INDEX\\s*\\{([^}]*)\\}", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static final Pattern LAST_UPDATED_PATTERN = Pattern.compile(
            "LAST-UPDATED\\s+\"([^\"]*)\"", Pattern.DOTALL);

    private static final Pattern ORG_PATTERN = Pattern.compile(
            "ORGANIZATION\\s+\"([^\"]*)\"", Pattern.DOTALL);

    private static final Pattern CONTACT_PATTERN = Pattern.compile(
            "CONTACT-INFO\\s+\"([^\"]*)\"", Pattern.DOTALL);

    // Standard root OIDs used for resolution bootstrap
    private static final Map<String, String> WELL_KNOWN_OIDS = new LinkedHashMap<>();
    static {
        WELL_KNOWN_OIDS.put("iso", "1");
        WELL_KNOWN_OIDS.put("org", "1.3");
        WELL_KNOWN_OIDS.put("dod", "1.3.6");
        WELL_KNOWN_OIDS.put("internet", "1.3.6.1");
        WELL_KNOWN_OIDS.put("directory", "1.3.6.1.1");
        WELL_KNOWN_OIDS.put("mgmt", "1.3.6.1.2");
        WELL_KNOWN_OIDS.put("mib-2", "1.3.6.1.2.1");
        WELL_KNOWN_OIDS.put("mib_2", "1.3.6.1.2.1");
        WELL_KNOWN_OIDS.put("experimental", "1.3.6.1.3");
        WELL_KNOWN_OIDS.put("private", "1.3.6.1.4");
        WELL_KNOWN_OIDS.put("enterprises", "1.3.6.1.4.1");
        WELL_KNOWN_OIDS.put("security", "1.3.6.1.5");
        WELL_KNOWN_OIDS.put("snmpV2", "1.3.6.1.6");
        WELL_KNOWN_OIDS.put("snmpModules", "1.3.6.1.6.3");
        WELL_KNOWN_OIDS.put("ccitt", "0");
        WELL_KNOWN_OIDS.put("zeroDotZero", "0.0");
        WELL_KNOWN_OIDS.put("snmpV2-SMI", "1.3.6.1.6");
    }

    public MibModule parse(String content, String fileName) {
        String cleaned = stripComments(content);
        Matcher modMatcher = MODULE_HEADER.matcher(cleaned);
        String moduleName;
        String moduleBody;
        if (modMatcher.find()) {
            moduleName = modMatcher.group(1);
            int bodyStart = modMatcher.end();
            int endIdx = cleaned.lastIndexOf("END");
            moduleBody = endIdx > bodyStart ? cleaned.substring(bodyStart, endIdx) : cleaned.substring(bodyStart);
        } else {
            // No formal module header - treat whole file as a module named after the file
            moduleName = fileName != null ? fileName.replaceAll("\\.(mib|txt|my)$", "") : "UnknownModule";
            moduleBody = cleaned;
        }

        MibModule module = new MibModule(moduleName);
        module.setFileName(fileName);

        Matcher lu = LAST_UPDATED_PATTERN.matcher(moduleBody);
        if (lu.find()) module.setLastUpdated(lu.group(1));
        Matcher org = ORG_PATTERN.matcher(moduleBody);
        if (org.find()) module.setOrganization(org.group(1));
        Matcher contact = CONTACT_PATTERN.matcher(moduleBody);
        if (contact.find()) module.setContactInfo(contact.group(1));

        // Parse MODULE-IDENTITY description
        Matcher modId = MODULE_IDENTITY_BLOCK.matcher(moduleBody);
        while (modId.find()) {
            String name = modId.group(1);
            String body = modId.group(2);
            String oidDef = modId.group(3).trim();
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("MODULE-IDENTITY");
            parseCommonFields(node, body);
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse OBJECT-TYPE
        Matcher ot = OBJECT_TYPE_BLOCK.matcher(moduleBody);
        while (ot.find()) {
            String name = ot.group(1);
            String body = ot.group(2);
            String oidDef = ot.group(3).trim();
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("OBJECT-TYPE");
            parseCommonFields(node, body);
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse OBJECT-IDENTITY
        Matcher oi = OBJECT_IDENTITY_BLOCK.matcher(moduleBody);
        while (oi.find()) {
            String name = oi.group(1);
            String body = oi.group(2);
            String oidDef = oi.group(3).trim();
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("OBJECT-IDENTITY");
            parseCommonFields(node, body);
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse NOTIFICATION-TYPE
        Matcher nt = NOTIFICATION_BLOCK.matcher(moduleBody);
        while (nt.find()) {
            String name = nt.group(1);
            String body = nt.group(2);
            String oidDef = nt.group(3).trim();
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("NOTIFICATION-TYPE");
            parseCommonFields(node, body);
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse OBJECT-GROUP
        Matcher og = OBJECT_GROUP_BLOCK.matcher(moduleBody);
        while (og.find()) {
            String name = og.group(1);
            String body = og.group(2);
            String oidDef = og.group(3).trim();
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("OBJECT-GROUP");
            parseCommonFields(node, body);
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse OBJECT IDENTIFIER assignments: name OBJECT IDENTIFIER ::= { ... }
        Matcher oidAssign = OID_ASSIGNMENT.matcher(moduleBody);
        while (oidAssign.find()) {
            String name = oidAssign.group(1);
            String oidDef = oidAssign.group(2).trim();
            if (module.getNodes().stream().anyMatch(n -> name.equals(n.getName()))) {
                continue;
            }
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("OBJECT IDENTIFIER");
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        // Parse simple OID assignments: name ::= { ... } (those not already captured)
        Matcher simpleAssign = SIMPLE_ASSIGNMENT.matcher(moduleBody);
        while (simpleAssign.find()) {
            String name = simpleAssign.group(1);
            String oidDef = simpleAssign.group(2).trim();
            // Skip keywords
            if (isKeyword(name)) continue;
            if (module.getNodes().stream().anyMatch(n -> name.equals(n.getName()))) {
                continue;
            }
            // Only treat as OID assignment if the content looks like an OID definition
            if (!looksLikeOidDef(oidDef)) continue;
            MibNode node = new MibNode();
            node.setName(name);
            node.setModuleName(moduleName);
            node.setType("OBJECT IDENTIFIER");
            setOidReference(node, oidDef);
            module.addNode(node);
        }

        return module;
    }

    private boolean looksLikeOidDef(String oidDef) {
        // OID def like "iso 3", "1.3.6.1", "internet 2 1", "iso(1) org(3)"
        String s = oidDef.trim();
        if (s.isEmpty()) return false;
        // must contain digits or known oid names
        return s.matches(".*\\d.*") || containsKnownOidName(s);
    }

    private boolean containsKnownOidName(String s) {
        for (String name : WELL_KNOWN_OIDS.keySet()) {
            if (s.contains(name)) return true;
        }
        return false;
    }

    private boolean isKeyword(String name) {
        switch (name) {
            case "SYNTAX": case "MAX-ACCESS": case "MIN-ACCESS": case "ACCESS":
            case "STATUS": case "DESCRIPTION": case "INDEX": case "DEFVAL":
            case "UNITS": case "REFERENCE": case "OBJECTS": case "NOTIFICATIONS":
            case "OBJECT-TYPE": case "OBJECT-GROUP": case "NOTIFICATION-GROUP":
            case "MODULE-COMPLIANCE": case "AGENT-CAPABILITIES": case "WRITE-SYNTAX":
            case "LAST-UPDATED": case "ORGANIZATION": case "CONTACT-INFO":
            case "REVISION": case "IMPORTS": case "EXPORTS": case "BEGIN": case "END":
            case "FROM": case "INTEGER": case "OCTET": case "STRING":
            case "OBJECT": case "IDENTIFIER": case "MODULE-IDENTITY":
            case "NOTIFICATION-TYPE": case "OBJECT-IDENTITY": case "TEXTUAL-CONVENTION":
            case "DISPLAY-HINT": case "AUGMENTS": case "DEFINITIONS":
                return true;
            default:
                return false;
        }
    }

    private void parseCommonFields(MibNode node, String body) {
        Matcher syntax = SYNTAX_PATTERN.matcher(body);
        if (syntax.find()) {
            String s = syntax.group(1).trim();
            // Clean trailing whitespace/newlines
            s = s.replaceAll("\\s+", " ").trim();
            // Strip trailing "}" artifacts
            if (s.endsWith("}")) s = s.substring(0, s.length() - 1).trim();
            node.setSyntax(s);
            // Determine a simple type
            node.setType(node.getType() + " [" + extractSimpleType(s) + "]");
        }
        Matcher access = ACCESS_PATTERN.matcher(body);
        if (access.find()) {
            node.setAccess(access.group(1).toLowerCase());
        }
        Matcher status = STATUS_PATTERN.matcher(body);
        if (status.find()) {
            node.setStatus(status.group(1).toLowerCase());
        }
        Matcher desc = DESCRIPTION_PATTERN.matcher(body);
        if (desc.find()) {
            node.setDescription(unescape(desc.group(1)).trim());
        }
        Matcher units = UNITS_PATTERN.matcher(body);
        if (units.find()) {
            node.setUnits(units.group(1).trim());
        }
        Matcher idx = INDEX_PATTERN.matcher(body);
        if (idx.find()) {
            String indexPart = idx.group(1);
            List<String> indexes = new ArrayList<>();
            for (String part : indexPart.split(",")) {
                String p = part.trim();
                // Remove IMPLIED prefix and any "(size)" annotations
                p = p.replaceFirst("(?i)IMPLIED\\s+", "");
                p = p.replaceAll("\\(.*?\\)", "").trim();
                if (!p.isEmpty()) {
                    indexes.add(p);
                }
            }
            if (!indexes.isEmpty()) node.setIndexes(indexes);
        }
    }

    private String extractSimpleType(String syntax) {
        String s = syntax.trim();
        if (s.startsWith("INTEGER")) return "INTEGER";
        if (s.startsWith("OCTET STRING") || s.startsWith("OCTET")) return "OCTET STRING";
        if (s.startsWith("OBJECT IDENTIFIER")) return "OBJECT IDENTIFIER";
        if (s.startsWith("IpAddress")) return "IpAddress";
        if (s.startsWith("Counter32") || s.startsWith("Counter64")) return "Counter";
        if (s.startsWith("Gauge32") || s.startsWith("Gauge")) return "Gauge";
        if (s.startsWith("TimeTicks")) return "TimeTicks";
        if (s.startsWith("Opaque")) return "Opaque";
        if (s.startsWith("Bits") || s.startsWith("BITS")) return "BITS";
        if (s.startsWith("TruthValue")) return "TruthValue";
        if (s.startsWith("DisplayString")) return "DisplayString";
        if (s.contains("SEQUENCE OF")) return "SEQUENCE";
        if (s.startsWith("SEQUENCE")) return "SEQUENCE";
        if (s.startsWith("RowStatus")) return "RowStatus";
        if (s.contains("{")) {
            // enum or textual convention
            return "Enum";
        }
        // textual convention reference
        int space = s.indexOf(' ');
        return space > 0 ? s.substring(0, space) : s;
    }

    private void setOidReference(MibNode node, String oidDef) {
        oidDef = oidDef.trim();
        // Handle form "parent(n)" sequences and plain numeric/named tokens
        List<Token> tokens = tokenizeOidDef(oidDef);
        if (tokens.isEmpty()) return;
        Token last = tokens.get(tokens.size() - 1);
        // The last token's numeric value is the node's numeric ID
        if (last.number != null) {
            node.setNumericId(last.number);
        }
        // Parent name: either the first named token (if multiple tokens), or "root" placeholder
        Token parentToken = null;
        for (int i = 0; i < tokens.size(); i++) {
            if (i == tokens.size() - 1 && tokens.size() == 1) {
                // single token like { internet 1 } where the only token is the child number
                // parent is implied from context - we keep null and resolve later
                parentToken = null;
                break;
            }
            if (tokens.get(i).name != null) {
                parentToken = tokens.get(i);
                break;
            }
        }
        if (parentToken != null) {
            node.setParentName(parentToken.name);
        }
    }

    private List<Token> tokenizeOidDef(String oidDef) {
        List<Token> tokens = new ArrayList<>();
        // Replace newlines with spaces
        String s = oidDef.replaceAll("\\s+", " ").trim();
        // Match patterns: name(number), name, number
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(
                "([A-Za-z][A-Za-z0-9-]*)\\s*\\(\\s*(\\d+)\\s*\\)|([A-Za-z][A-Za-z0-9-]*)|(\\d+)");
        Matcher m = p.matcher(s);
        while (m.find()) {
            Token t = new Token();
            if (m.group(1) != null) {
                t.name = m.group(1);
                t.number = Integer.parseInt(m.group(2));
            } else if (m.group(3) != null) {
                t.name = m.group(3);
            } else if (m.group(4) != null) {
                t.number = Integer.parseInt(m.group(4));
            }
            tokens.add(t);
        }
        return tokens;
    }

    private static class Token {
        String name;
        Integer number;
    }

    private String unescape(String s) {
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }

    /**
     * Strip MIB comments: -- single line, and -- ... -- block comments.
     * Also strips ASN.1 block comments (rare but possible).
     */
    public String stripComments(String content) {
        // Remove block comments -- ... -- (spanning lines)
        // We'll handle -- ... end-of-line as the most common case,
        // plus -- ... -- block style.
        StringBuilder sb = new StringBuilder();
        int i = 0;
        int len = content.length();
        while (i < len) {
            char c = content.charAt(i);
            if (c == '-' && i + 1 < len && content.charAt(i + 1) == '-') {
                // comment start
                // check for block comment closing -- ... --
                // find next "--"
                int end = content.indexOf("--", i + 2);
                int eol = content.indexOf('\n', i);
                if (end != -1 && (eol == -1 || end < eol)) {
                    // block comment spanning lines: skip to after closing --
                    i = end + 2;
                    // keep the newline
                } else {
                    // single line comment
                    if (eol == -1) {
                        i = len;
                    } else {
                        i = eol; // keep the newline char
                    }
                }
            } else if (c == '/' && i + 1 < len && content.charAt(i + 1) == '*') {
                int end = content.indexOf("*/", i + 2);
                if (end == -1) {
                    i = len;
                } else {
                    i = end + 2;
                }
            } else {
                sb.append(c);
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * Resolve OIDs for all nodes across all modules using the parent chain.
     * Nodes whose OID cannot be resolved get an empty/null OID.
     */
    public void resolveOids(List<MibModule> modules) {
        // Build a global name -> node map and name -> oid map (from WELL_KNOWN first)
        Map<String, MibNode> nameToNode = new HashMap<>();
        Map<String, String> nameToOid = new LinkedHashMap<>(WELL_KNOWN_OIDS);
        for (MibModule m : modules) {
            for (MibNode n : m.getNodes()) {
                nameToNode.put(n.getName(), n);
            }
        }

        // Iteratively resolve OIDs. Up to N passes to handle dependencies.
        boolean changed = true;
        int maxPasses = 10;
        int pass = 0;
        while (changed && pass < maxPasses) {
            changed = false;
            pass++;
            for (MibModule m : modules) {
                for (MibNode n : m.getNodes()) {
                    if (n.getOid() != null) continue;
                    String parentName = n.getParentName();
                    if (parentName == null) {
                        // Try to infer from a single token def or skip
                        continue;
                    }
                    String parentOid = nameToOid.get(parentName);
                    if (parentOid == null) {
                        // parent node may itself be unresolved
                        MibNode parentNode = nameToNode.get(parentName);
                        if (parentNode != null && parentNode.getOid() != null) {
                            parentOid = parentNode.getOid();
                            nameToOid.put(parentName, parentOid);
                        }
                    }
                    if (parentOid != null && n.getNumericId() != null) {
                        String oid = parentOid + "." + n.getNumericId();
                        n.setOid(oid);
                        nameToOid.put(n.getName(), oid);
                        changed = true;
                    }
                }
            }
        }

        // Determine leaf status and table/entry flags
        Set<String> nodeNames = new HashSet<>(nameToNode.keySet());
        Set<String> parentNames = new HashSet<>();
        for (MibModule m : modules) {
            for (MibNode n : m.getNodes()) {
                if (n.getParentName() != null) parentNames.add(n.getParentName());
            }
        }
        for (MibModule m : modules) {
            for (MibNode n : m.getNodes()) {
                n.setLeaf(!parentNames.contains(n.getName()));
                String nm = n.getName() == null ? "" : n.getName().toLowerCase();
                String ty = n.getType() == null ? "" : n.getType();
                if (ty.contains("SEQUENCE") || (n.getSyntax() != null && n.getSyntax().contains("SEQUENCE OF"))) {
                    n.setTable(true);
                }
                if (nm.endsWith("entry") || nm.endsWith("row")) {
                    n.setTableEntry(true);
                }
            }
        }
    }
}
