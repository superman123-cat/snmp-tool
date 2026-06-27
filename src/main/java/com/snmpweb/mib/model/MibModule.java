package com.snmpweb.mib.model;

import java.util.ArrayList;
import java.util.List;

public class MibModule {
    private String name;
    private String fileName;
    private String lastUpdated;
    private String organization;
    private String contactInfo;
    private String description;
    private List<MibNode> nodes = new ArrayList<>();

    public MibModule() {}

    public MibModule(String name) {
        this.name = name;
    }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getFileName() { return fileName; }
    public void setFileName(String fileName) { this.fileName = fileName; }
    public String getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(String lastUpdated) { this.lastUpdated = lastUpdated; }
    public String getOrganization() { return organization; }
    public void setOrganization(String organization) { this.organization = organization; }
    public String getContactInfo() { return contactInfo; }
    public void setContactInfo(String contactInfo) { this.contactInfo = contactInfo; }
    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }
    public List<MibNode> getNodes() { return nodes; }
    public void setNodes(List<MibNode> nodes) { this.nodes = nodes; }

    public void addNode(MibNode node) {
        nodes.add(node);
    }
}
