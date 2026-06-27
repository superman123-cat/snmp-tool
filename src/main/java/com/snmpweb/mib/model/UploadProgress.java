package com.snmpweb.mib.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class UploadProgress {
    public static final String STATUS_QUEUED = "queued";
    public static final String STATUS_UPLOADING = "uploading";
    public static final String STATUS_PARSING = "parsing";
    public static final String STATUS_SUCCESS = "success";
    public static final String STATUS_FAILED = "failed";

    private String batchId;
    private int total;
    private int completed;
    private int success;
    private int failed;
    private String status;
    private long startTime;
    private long endTime;
    // Keyed by submissionKey (unique per file, preserves relative path when present)
    private final Map<String, String> fileResults = new HashMap<>();
    private final Map<String, String> fileErrors = new HashMap<>();
    // Ordered list with full info for frontend rendering
    private final List<FileEntry> fileEntries = new ArrayList<>();

    public UploadProgress(String batchId, int total) {
        this.batchId = batchId;
        this.total = total;
        this.completed = 0;
        this.success = 0;
        this.failed = 0;
        this.status = STATUS_QUEUED;
        this.startTime = System.currentTimeMillis();
    }

    /** Register a file in the queue before parsing starts. */
    public void registerFile(String submissionKey, String displayName) {
        FileEntry e = new FileEntry();
        e.setKey(submissionKey);
        e.setName(displayName);
        e.setStatus(STATUS_QUEUED);
        fileEntries.add(e);
    }

    public void markSuccess(String submissionKey, String moduleName, int nodeCount) {
        completed++;
        success++;
        fileResults.put(submissionKey, STATUS_SUCCESS);
        FileEntry e = findEntry(submissionKey);
        if (e != null) {
            e.setStatus(STATUS_SUCCESS);
            e.setModuleName(moduleName);
            e.setNodeCount(nodeCount);
        }
    }

    public void markFailed(String submissionKey, String error) {
        completed++;
        failed++;
        fileResults.put(submissionKey, STATUS_FAILED);
        fileErrors.put(submissionKey, error);
        FileEntry e = findEntry(submissionKey);
        if (e != null) {
            e.setStatus(STATUS_FAILED);
            e.setError(error);
        }
    }

    private FileEntry findEntry(String key) {
        for (FileEntry e : fileEntries) {
            if (e.getKey().equals(key)) return e;
        }
        return null;
    }

    public void finish() {
        this.endTime = System.currentTimeMillis();
        this.status = failed == 0 ? STATUS_SUCCESS : (success == 0 ? STATUS_FAILED : "partial");
    }

    public int getPercent() {
        if (total == 0) return 0;
        return (int) ((completed * 100L) / total);
    }

    public String getBatchId() { return batchId; }
    public int getTotal() { return total; }
    public int getCompleted() { return completed; }
    public int getSuccess() { return success; }
    public int getFailed() { return failed; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public Map<String, String> getFileResults() { return fileResults; }
    public Map<String, String> getFileErrors() { return fileErrors; }
    public List<FileEntry> getFileEntries() { return fileEntries; }
    public long getDuration() {
        long end = endTime == 0 ? System.currentTimeMillis() : endTime;
        return end - startTime;
    }

    /** Per-file status entry with full info for frontend rendering. */
    public static class FileEntry {
        private String key;
        private String name;
        private String status;
        private String error;
        private String moduleName;
        private int nodeCount;

        public String getKey() { return key; }
        public void setKey(String key) { this.key = key; }
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public String getError() { return error; }
        public void setError(String error) { this.error = error; }
        public String getModuleName() { return moduleName; }
        public void setModuleName(String moduleName) { this.moduleName = moduleName; }
        public int getNodeCount() { return nodeCount; }
        public void setNodeCount(int nodeCount) { this.nodeCount = nodeCount; }
    }
}
