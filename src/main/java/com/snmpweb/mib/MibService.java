package com.snmpweb.mib;

import com.snmpweb.mib.model.MibModule;
import com.snmpweb.mib.model.MibNode;
import com.snmpweb.mib.model.UploadProgress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class MibService {

    private static final Logger log = LoggerFactory.getLogger(MibService.class);

    @Value("${app.mib.upload-dir:${user.dir}/mib-files}")
    private String uploadDir;

    /** Per-file parse timeout in seconds. A single MIB that triggers regex
     *  catastrophic backtracking (or otherwise hangs) will not freeze the batch. */
    @Value("${app.mib.parse-timeout-seconds:30}")
    private long parseTimeoutSeconds;

    @Autowired
    @Qualifier("mibTaskExecutor")
    private TaskExecutor taskExecutor;

    private final MibParser parser = new MibParser();
    private final List<MibModule> modules = Collections.synchronizedList(new ArrayList<>());
    private final ConcurrentMap<String, MibNode> nodeByName = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, MibNode> nodeByOid = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, UploadProgress> uploadProgressMap = new ConcurrentHashMap<>();

    /** Cached tree structure built by buildTree(). Invalidated on any structural
     *  change (import batch, module delete, clearAll). Avoids rebuilding the
     *  parent->children map and sorting 7000+ nodes on every /api/mib/tree call. */
    private volatile List<MibNode> cachedTree = null;

    @PostConstruct
    public void init() throws IOException {
        Path dir = Paths.get(uploadDir);
        Files.createDirectories(dir);
        log.info("MIB upload directory: {}", dir.toAbsolutePath());
    }

    /**
     * Process an uploaded batch of MIB files asynchronously.
     * Returns a batch id that can be used to poll progress.
     *
     * IMPORTANT: Spring deletes MultipartFile temp files when the request
     * completes. Since parsing runs async after the controller returns, we
     * must read the bytes SYNCHRONOUSLY here, before dispatching the async
     * task. Otherwise the async thread hits FileNotFoundException on the
     * already-deleted .tmp files (root cause of "only 1 file succeeds").
     */
    public UploadProgress processBatch(List<MultipartFile> files) {
        String batchId = UUID.randomUUID().toString().replace("-", "");
        UploadProgress progress = new UploadProgress(batchId, files.size());
        uploadProgressMap.put(batchId, progress);

        // Read every file's bytes now (synchronously, on the request thread).
        // Each SubmittedFile is a unique, in-memory snapshot that survives the
        // request lifecycle. Key = submissionKey (original filename, which may
        // be a relative path like "3.0/WRI-FAN-MIB.mib" for folder uploads).
        List<SubmittedFile> submitted = new ArrayList<>(files.size());
        for (MultipartFile mf : files) {
            String key = mf.getOriginalFilename();
            if (key == null || key.isEmpty()) key = "unnamed-" + UUID.randomUUID();
            SubmittedFile sf = new SubmittedFile(key, mf.getSize());
            progress.registerFile(key, key);
            try {
                sf.bytes = mf.getBytes();
                sf.readOk = true;
            } catch (Exception e) {
                log.error("Failed to read upload '{}' on request thread: {}", key, e.getMessage());
                sf.readOk = false;
                sf.readError = e.getMessage();
            }
            submitted.add(sf);
        }

        // Dispatch the async parse using in-memory bytes (no temp file access).
        taskExecutor.execute(() -> doProcessBatch(batchId, submitted));
        return progress;
    }

    void doProcessBatch(String batchId, List<SubmittedFile> files) {
        UploadProgress progress = uploadProgressMap.get(batchId);
        progress.setStatus(UploadProgress.STATUS_PARSING);
        List<MibModule> newlyParsed = new ArrayList<>();

        // Dedicated single-thread executor for parsing. Parsing happens here
        // one file at a time; if one file hangs (e.g. regex catastrophic
        // backtracking), the Future.get(timeout) below abandons it and the
        // batch continues with the next file.
        ExecutorService parseExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "mib-parse-" + batchId.substring(0, Math.min(8, batchId.length())));
            t.setDaemon(true);
            return t;
        });

        try {
            for (SubmittedFile file : files) {
                String key = file.submissionKey;
                // If reading the bytes failed on the request thread, fail fast.
                if (!file.readOk) {
                    progress.markFailed(key, "Failed to read uploaded bytes: " + file.readError);
                    log.error("Skipping MIB file {} (bytes unavailable)", key);
                    continue;
                }

                final String content = new String(file.bytes, StandardCharsets.UTF_8);
                Future<MibModule> future = parseExecutor.submit(() -> parser.parse(content, key));

                MibModule module = null;
                try {
                    module = future.get(parseTimeoutSeconds, TimeUnit.SECONDS);
                } catch (TimeoutException te) {
                    // Cancel interrupts the parsing thread so the regex stops.
                    future.cancel(true);
                    String msg = "Parse timeout after " + parseTimeoutSeconds + "s (likely regex backtracking)";
                    progress.markFailed(key, msg);
                    log.error("MIB parse timed out ({}s), skipping: {}", parseTimeoutSeconds, key);
                    continue;
                } catch (ExecutionException ee) {
                    Throwable cause = ee.getCause() != null ? ee.getCause() : ee;
                    log.error("Failed to parse MIB file: " + key, cause);
                    progress.markFailed(key, cause.getMessage() != null ? cause.getMessage() : cause.toString());
                    continue;
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    progress.markFailed(key, "Batch interrupted");
                    log.warn("Batch {} interrupted while parsing {}", batchId, key);
                    break;
                }

                try {
                    // Save to disk under uploadDir, preserving any relative path.
                    Path dest = Paths.get(uploadDir, key);
                    Files.createDirectories(dest.getParent());
                    Files.write(dest, file.bytes,
                            StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING);

                    newlyParsed.add(module);
                    progress.markSuccess(key, module.getName(), module.getNodes().size());
                    log.info("Parsed MIB file {}: module={}, nodes={}",
                            key, module.getName(), module.getNodes().size());
                } catch (Exception e) {
                    log.error("Failed to persist parsed MIB file: " + key, e);
                    progress.markFailed(key, e.getMessage());
                }
            }
        } finally {
            parseExecutor.shutdownNow();
        }

        // Resolve OIDs across all modules (existing + newly parsed)
        List<MibModule> all = new ArrayList<>(modules);
        all.addAll(newlyParsed);
        try {
            parser.resolveOids(all);
        } catch (Exception e) {
            log.error("OID resolution failed", e);
        }

        // Register newly parsed modules and build indexes
        for (MibModule m : newlyParsed) {
            modules.add(m);
            for (MibNode n : m.getNodes()) {
                if (n.getName() != null) {
                    nodeByName.put(n.getName(), n);
                }
                if (n.getOid() != null) {
                    nodeByOid.putIfAbsent(n.getOid(), n);
                }
            }
        }
        // Invalidate cached tree so next /api/mib/tree reflects new nodes
        if (!newlyParsed.isEmpty()) cachedTree = null;
        progress.finish();
        log.info("Batch {} complete: success={}, failed={}", batchId, progress.getSuccess(), progress.getFailed());
    }

    public UploadProgress getProgress(String batchId) {
        return uploadProgressMap.get(batchId);
    }

    public List<MibModule> getModules() {
        return Collections.unmodifiableList(modules);
    }

    public MibNode getNodeByName(String name) {
        return nodeByName.get(name);
    }

    public MibNode getNodeByOid(String oid) {
        return nodeByOid.get(oid);
    }

    public int getNodeCount() {
        return nodeByName.size();
    }

    /**
     * Search MIB nodes by multiple conditions.
     */
    public List<MibNode> search(String keyword, String nameMatch, String oidMatch, String descMatch, int limit) {
        List<MibNode> results = new ArrayList<>();
        int max = limit <= 0 ? 200 : limit;
        for (MibNode node : nodeByName.values()) {
            if (matches(node, keyword, nameMatch, oidMatch, descMatch)) {
                results.add(node);
                if (results.size() >= max) break;
            }
        }
        return results;
    }

    private boolean matches(MibNode node, String keyword, String nameMatch, String oidMatch, String descMatch) {
        String name = node.getName() == null ? "" : node.getName();
        String oid = node.getOid() == null ? "" : node.getOid();
        String desc = node.getDescription() == null ? "" : node.getDescription();

        // Combined keyword search across all fields
        if (keyword != null && !keyword.trim().isEmpty()) {
            String k = keyword.trim().toLowerCase();
            boolean hit = name.toLowerCase().contains(k)
                    || oid.contains(k)
                    || desc.toLowerCase().contains(k);
            if (!hit) return false;
        }
        // Name match (exact or fuzzy)
        if (nameMatch != null && !nameMatch.trim().isEmpty()) {
            String nm = nameMatch.trim();
            boolean hit = nm.equalsIgnoreCase(name) || name.toLowerCase().contains(nm.toLowerCase());
            if (!hit) return false;
        }
        // OID match (exact or prefix)
        if (oidMatch != null && !oidMatch.trim().isEmpty()) {
            String om = oidMatch.trim();
            boolean hit = oid.equals(om) || oid.startsWith(om + ".") || oid.startsWith(om);
            if (!hit) return false;
        }
        // Description keyword match
        if (descMatch != null && !descMatch.trim().isEmpty()) {
            String dm = descMatch.trim().toLowerCase();
            if (!desc.toLowerCase().contains(dm)) return false;
        }
        return true;
    }

    /**
     * Build a hierarchical tree structure from all parsed nodes, rooted at common parents.
     */
    public List<MibNode> buildTree() {
        if (cachedTree != null) return cachedTree;
        List<MibNode> result = buildTreeUncached();
        cachedTree = result;
        return result;
    }

    private List<MibNode> buildTreeUncached() {
        // Collect every parsed node from all modules so we can resolve parent
        // links even when a node was dropped from nodeByOid. nodeByOid uses
        // putIfAbsent, so when two nodes share an OID (common when a vendor
        // MIB reuses standard OIDs, e.g. POWER-ETHERNET-MIB's 1.3.6.1.2.1.105.x),
        // the second registration is discarded. The original buildTree built
        // knownNames from nodeByOid only, so the discarded node's name was
        // missing from knownNames and its children (e.g. all poePsePort* nodes)
        // were treated as top-level roots ("poe" appearing at the outer level).
        //
        // We must, however, DEDUPLICATE by node name: the same logical node is
        // typically defined in multiple imported modules/versions, and if every
        // duplicate is kept, each copy gets the same children attached and the
        // serialized tree explodes exponentially (observed 1.2GB for ~7k nodes).
        List<MibNode> allNodes = new ArrayList<>();
        List<MibModule> snapshot;
        synchronized (modules) {
            snapshot = new ArrayList<>(modules);
        }
        for (MibModule m : snapshot) {
            if (m.getNodes() != null) allNodes.addAll(m.getNodes());
        }
        // knownNames must include EVERY parsed name (even from OID-conflicted
        // nodes) so children can find their parents.
        Set<String> knownNames = new HashSet<>();
        for (MibNode node : allNodes) {
            if (node.getName() != null) knownNames.add(node.getName());
        }
        // Deduplicate tree nodes by name — keep the first occurrence per name.
        // This preserves every distinct node while preventing the same node
        // from appearing N times (which would duplicate its whole subtree).
        Map<String, MibNode> uniqueByName = new LinkedHashMap<>();
        for (MibNode node : allNodes) {
            String name = node.getName();
            if (name == null || name.isEmpty()) {
                // Nameless nodes can't be parents/children by name; keep as-is.
                uniqueByName.put("__oid__" + (node.getOid() == null ? "" : node.getOid()), node);
            } else if (!uniqueByName.containsKey(name)) {
                uniqueByName.put(name, node);
            }
        }
        Collection<MibNode> treeNodes = uniqueByName.values();

        // Group nodes by parent name
        Map<String, List<MibNode>> byParent = new HashMap<>();
        List<MibNode> roots = new ArrayList<>();
        for (MibNode node : treeNodes) {
            String parent = node.getParentName();
            if (parent == null || parent.isEmpty() || !knownNames.contains(parent)) {
                roots.add(node);
            }
            if (parent != null && !parent.isEmpty()) {
                byParent.computeIfAbsent(parent, k -> new ArrayList<>()).add(node);
            }
        }
        // Attach children
        for (MibNode node : treeNodes) {
            List<MibNode> children = byParent.get(node.getName());
            if (children != null) {
                children.sort(Comparator.comparingInt((MibNode n) ->
                        n.getNumericId() == null ? Integer.MAX_VALUE : n.getNumericId())
                        .thenComparing(n -> n.getName() == null ? "" : n.getName()));
                node.setChildren(children);
            } else {
                node.setChildren(null);
            }
        }
        // Sort roots by OID then name
        roots.sort(Comparator
                .comparing((MibNode n) -> n.getOid() == null ? "" : n.getOid())
                .thenComparing(n -> n.getName() == null ? "" : n.getName()));
        return roots;
    }

    /**
     * Build a tree limited to a subtree under the given OID.
     */
    public MibNode getSubtree(String oidPrefix) {
        MibNode root = nodeByOid.get(oidPrefix);
        if (root == null) {
            // try prefix
            for (MibNode n : nodeByOid.values()) {
                if (n.getOid() != null && n.getOid().equals(oidPrefix)) {
                    root = n;
                    break;
                }
            }
        }
        if (root == null) return null;
        return cloneWithChildren(root, 0, Integer.MAX_VALUE);
    }

    private MibNode cloneWithChildren(MibNode src, int depth, int maxDepth) {
        MibNode copy = new MibNode();
        copy.setName(src.getName());
        copy.setOid(src.getOid());
        copy.setParentName(src.getParentName());
        copy.setNumericId(src.getNumericId());
        copy.setType(src.getType());
        copy.setSyntax(src.getSyntax());
        copy.setAccess(src.getAccess());
        copy.setStatus(src.getStatus());
        copy.setDescription(src.getDescription());
        copy.setUnits(src.getUnits());
        copy.setModuleName(src.getModuleName());
        copy.setIndexes(src.getIndexes());
        copy.setLeaf(src.isLeaf());
        copy.setTable(src.isTable());
        copy.setTableEntry(src.isTableEntry());
        if (depth < maxDepth && src.getChildren() != null) {
            for (MibNode c : src.getChildren()) {
                copy.addChild(cloneWithChildren(c, depth + 1, maxDepth));
            }
        }
        return copy;
    }

    public boolean deleteModule(String moduleName) {
        synchronized (modules) {
            MibModule target = null;
            for (MibModule m : modules) {
                if (m.getName().equals(moduleName)) {
                    target = m;
                    break;
                }
            }
            if (target == null) return false;
            modules.remove(target);
            for (MibNode n : target.getNodes()) {
                if (n.getName() != null) nodeByName.remove(n.getName(), n);
                if (n.getOid() != null) nodeByOid.remove(n.getOid(), n);
            }
            cachedTree = null;
            return true;
        }
    }

    /** Remove every parsed module and all node indexes (full reset). */
    public int clearAll() {
        synchronized (modules) {
            int count = modules.size();
            modules.clear();
            nodeByName.clear();
            nodeByOid.clear();
            uploadProgressMap.clear();
            cachedTree = null;
            return count;
        }
    }

    public List<MibNode> getAllNodes() {
        return new ArrayList<>(nodeByOid.values());
    }

    /**
     * In-memory snapshot of one uploaded file, captured on the request thread
     * so the async parser is independent of the (already cleaned up) temp files.
     * The submissionKey preserves any relative path from folder uploads.
     */
    static final class SubmittedFile {
        final String submissionKey;
        final long size;
        byte[] bytes;
        boolean readOk;
        String readError;

        SubmittedFile(String submissionKey, long size) {
            this.submissionKey = submissionKey;
            this.size = size;
        }
    }
}
