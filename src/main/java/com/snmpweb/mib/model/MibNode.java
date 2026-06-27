package com.snmpweb.mib.model;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.ArrayList;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class MibNode {
    private String name;
    private String oid;
    private String parentName;
    private Integer numericId;
    private String type;
    private String syntax;
    private String access;
    private String status;
    private String description;
    private String units;
    private String moduleName;
    private List<String> indexes;
    private boolean leaf;
    private boolean table;
    private boolean tableEntry;
    private List<MibNode> children;

    public MibNode() {
        this.children = new ArrayList<>();
    }

    public MibNode(String name, String oid) {
        this();
        this.name = name;
        this.oid = oid;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getOid() { return oid; }
    public void setOid(String oid) { this.oid = oid; }
    public String getParentName() { return parentName; }
    public void setParentName(String parentName) { this.parentName = parentName; }
    public Integer getNumericId() { return numericId; }
    public void setNumericId(Integer numericId) { this.numericId = numericId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getSyntax() { return syntax; }
    public void setSyntax(String syntax) { this.syntax = syntax; }
    public String getAccess() { return access; }
    public void setAccess(String access) { this.access = access; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public String getUnits() { return units; }
    public void setUnits(String units) { this.units = units; }
    public String getModuleName() { return moduleName; }
    public void setModuleName(String moduleName) { this.moduleName = moduleName; }
    public List<String> getIndexes() { return indexes; }
    public void setIndexes(List<String> indexes) { this.indexes = indexes; }
    public boolean isLeaf() { return leaf; }
    public void setLeaf(boolean leaf) { this.leaf = leaf; }
    public boolean isTable() { return table; }
    public void setTable(boolean table) { this.table = table; }
    public boolean isTableEntry() { return tableEntry; }
    public void setTableEntry(boolean tableEntry) { this.tableEntry = tableEntry; }
    public List<MibNode> getChildren() { return children; }
    public void setChildren(List<MibNode> children) { this.children = children; }

    public void addChild(MibNode child) {
        this.children.add(child);
    }

    public boolean hasChildren() {
        return children != null && !children.isEmpty();
    }
}
