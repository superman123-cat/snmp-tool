package com.snmpweb.device;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public class SnmpVarBind {
    private String oid;
    private String name;
    private String value;
    private String displayValue;
    private String syntax;
    private String dataType;
    private long timestamp;

    public SnmpVarBind() {}

    public SnmpVarBind(String oid, String value, String syntax) {
        this.oid = oid;
        this.value = value;
        this.syntax = syntax;
        this.timestamp = System.currentTimeMillis();
    }

    public String getOid() { return oid; }
    public void setOid(String oid) { this.oid = oid; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }
    public String getDisplayValue() { return displayValue; }
    public void setDisplayValue(String displayValue) { this.displayValue = displayValue; }
    public String getSyntax() { return syntax; }
    public void setSyntax(String syntax) { this.syntax = syntax; }
    public String getDataType() { return dataType; }
    public void setDataType(String dataType) { this.dataType = dataType; }
    public long getTimestamp() { return timestamp; }
    public void setTimestamp(long timestamp) { this.timestamp = timestamp; }
}
