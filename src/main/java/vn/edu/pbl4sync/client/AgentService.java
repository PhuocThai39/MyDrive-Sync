package vn.edu.pbl4sync.client;

import vn.edu.pbl4sync.client.sync.*;
import vn.edu.pbl4sync.common.*;

import java.awt.Desktop;
import java.nio.file.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BiConsumer;

import static vn.edu.pbl4sync.common.MessageTypes.*;

public class AgentService implements AutoCloseable {
    private final ClientConfig config;
    private final NetworkClient network = new NetworkClient();
    private final ExecutorService workers = Executors.newFixedThreadPool(6);
    private final ScheduledExecutorService syncScheduler = Executors.newSingleThreadScheduledExecutor();
    private final IgnoreRegistry ignore = new IgnoreRegistry();
    private final Map<Long, FolderWatcher> watchers = new ConcurrentHashMap<>();
    private final Set<String> pendingDeferredSync = ConcurrentHashMap.newKeySet();
    private final Set<String> pendingLocalUploadRetry = ConcurrentHashMap.newKeySet();
    private final Set<String> suppressedWarnings = ConcurrentHashMap.newKeySet();
    private volatile List<Map<String, String>> workspaces = new ArrayList<>();
    private volatile LocalStateStore state;
    private volatile BiConsumer<String, String> notificationListener = (l, m) -> {
    };
    private long userId;
    private long agentId;
    private String username = "";
    private String systemRole = "USER";

    public AgentService(ClientConfig config) {
        this.config = config;
        network.setEventHandler(this::handleEvent);
        network.setDisconnectHandler(m -> notify("ERROR", "Disconnected from server: " + m));
    }

    public void connectAndLogin(String host, int port, String username, String password) throws Exception {
        config.setHost(host);
        config.setPort(port);
        config.save();
        network.connect(host, port);
        Packet login = Packet.request(LOGIN_REQUEST)
                .with("username", username)
                .with("password", password)
                .with("deviceName", config.deviceName());
        Packet r = network.request(login, Duration.ofSeconds(10));
        if (!LOGIN_RESPONSE.equals(r.type()) || !r.getBoolean("success", false)) {
            network.close();
            throw new IllegalArgumentException(r.get("message").isBlank() ? "Login failed" : r.get("message"));
        }
        this.userId = r.getLong("userId", 0);
        this.agentId = r.getLong("agentId", 0);
        this.username = r.get("username");
        this.systemRole = r.get("systemRole");
        Files.createDirectories(config.syncRoot());
        this.state = new LocalStateStore(config.syncRoot());
        refreshWorkspaces();
        startWatchers();
        workers.submit(() -> {
            try {
                syncNow();
            } catch (Exception e) {
                notify("WARN", "Initial sync: " + e.getMessage());
            }
        });
    }

    public synchronized List<Map<String, String>> refreshWorkspaces() throws Exception {
        Packet r = request(Packet.request(LIST_WORKSPACES_REQUEST));
        workspaces = TableCodec.decode(r.get("rows"));
        return workspaces;
    }

    public List<Map<String, String>> workspaces() {
        return List.copyOf(workspaces);
    }

    public boolean isAdmin() {
        return "ADMIN".equalsIgnoreCase(systemRole);
    }

    public String username() {
        return username;
    }

    public long userId() {
        return userId;
    }

    public long agentId() {
        return agentId;
    }

    public ClientConfig config() {
        return config;
    }

    public boolean connected() {
        return network.isConnected();
    }

    public Map<String, String> dashboard() throws Exception {
        return request(Packet.request(DASHBOARD_REQUEST)).data();
    }

    public List<Map<String, String>> listFiles(long workspaceId) throws Exception {
        return TableCodec
                .decode(request(Packet.request(LIST_FILES_REQUEST).with("workspaceId", workspaceId)).get("rows"));
    }

    public List<Map<String, String>> listFileVersions(long workspaceId, String path) throws Exception {
        Packet r = request(Packet.request(FILE_VERSIONS_REQUEST)
                .with("workspaceId", workspaceId).with("path", path));
        return TableCodec.decode(r.get("rows"));
    }

    public void rollbackFile(long workspaceId, String path, long targetVersion, long currentVersion) throws Exception {
        Packet r = request(Packet.request(FILE_ROLLBACK_REQUEST).with("workspaceId", workspaceId)
                .with("path", path).with("targetVersion", targetVersion).with("currentVersion", currentVersion));
        if (!FILE_ROLLBACK_RESULT.equals(r.type()) || !r.getBoolean("success", false)) {
            if (r.getBoolean("conflict", false)) {
                throw new IllegalStateException("File changed on the server; refresh its version history and retry");
            }
            throw new IllegalStateException(r.get("message").isBlank() ? "Rollback failed" : r.get("message"));
        }
        notify("INFO", path + " rolled back to v" + targetVersion
                + " as new version v" + r.get("version"));
    }

    public List<Map<String, String>> listMembers(long workspaceId) throws Exception {
        return TableCodec
                .decode(request(Packet.request(LIST_MEMBERS_REQUEST).with("workspaceId", workspaceId)).get("rows"));
    }

    public List<Map<String, String>> listUsers() throws Exception {
        return TableCodec.decode(request(Packet.request(LIST_USERS_REQUEST)).get("rows"));
    }

    public List<Map<String, String>> listAgents() throws Exception {
        return TableCodec.decode(request(Packet.request(LIST_AGENTS_REQUEST)).get("rows"));
    }

    public List<Map<String, String>> listActivity(Long workspaceId) throws Exception {
        Packet p = Packet.request(LIST_ACTIVITY_REQUEST).with("limit", 200);
        if (workspaceId != null)
            p.with("workspaceId", workspaceId);
        return TableCodec.decode(request(p).get("rows"));
    }

    public void createUser(String username, String password, String role) throws Exception {
        request(Packet.request(CREATE_USER_REQUEST).with("username", username).with("password", password).with("role",
                role));
    }

    public void setUserStatus(long userId, String status) throws Exception {
        request(Packet.request(SET_USER_STATUS_REQUEST).with("userId", userId).with("status", status));
    }

    public void createWorkspace(String name, String leaderUsername) throws Exception {
        request(Packet.request(CREATE_WORKSPACE_REQUEST).with("name", name).with("leaderUsername", leaderUsername));
        refreshWorkspaces();
        restartWatchers();
    }

    public void addMember(long ws, String username, boolean read, boolean write, boolean modify, boolean delete)
            throws Exception {
        request(Packet.request(ADD_MEMBER_REQUEST).with("workspaceId", ws).with("username", username)
                .with("read", read).with("write", write).with("modify", modify).with("delete", delete));
    }

    public void updatePermission(long ws, long memberUserId, boolean read, boolean write, boolean modify,
            boolean delete) throws Exception {
        request(Packet.request(UPDATE_PERMISSION_REQUEST).with("workspaceId", ws).with("userId", memberUserId)
                .with("read", read).with("write", write).with("modify", modify).with("delete", delete));
    }

    public void removeMember(long ws, long memberUserId) throws Exception {
        request(Packet.request(REMOVE_MEMBER_REQUEST).with("workspaceId", ws).with("userId", memberUserId));
    }

    public void changeLeader(long ws, String leaderUsername) throws Exception {
        request(Packet.request(CHANGE_LEADER_REQUEST).with("workspaceId", ws).with("leaderUsername", leaderUsername));
        refreshWorkspaces();
        restartWatchers();
    }

    public void deleteWorkspace(long ws) throws Exception {
        request(Packet.request(DELETE_WORKSPACE_REQUEST).with("workspaceId", ws));
        refreshWorkspaces();
        restartWatchers();
    }

    private Packet request(Packet p) throws Exception {
        Packet r = network.request(p, Duration.ofSeconds(15));
        if (ERROR.equals(r.type()))
            throw new IllegalStateException(r.get("message"));
        return r;
    }

    private void startWatchers() throws Exception {
        Files.createDirectories(config.syncRoot());
        for (Map<String, String> ws : workspaces) {
            long id = parseLong(ws.get("id"), 0);
            Path dir = workspaceDir(ws);
            Files.createDirectories(dir);
            FolderWatcher watcher = new FolderWatcher(dir, ignore, (action, path) -> localFileEvent(id, path, action));
            watchers.put(id, watcher);
        }
    }

    private synchronized void restartWatchers() throws Exception {
        for (FolderWatcher w : watchers.values())
            w.close();
        watchers.clear();
        startWatchers();
    }

    private void localFileEvent(long ws, String path, String action) {
        workers.submit(() -> {
            try {
                uploadLocalChange(ws, path, action);
            } catch (Exception e) {
                notify("ERROR", action + " " + path + " failed: " + e.getMessage());
            }
        });
    }

    private void uploadLocalChange(long ws, String path, String action) throws Exception {
        Map<String, String> workspace = workspace(ws);
        if (workspace == null)
            return;
        if (!allowed(workspace, action)) {
            notify("WARN", "Permission denied locally for " + action + ": " + path);
            return;
        }

        long baseVersion = state.uploadBaseVersion(ws, path);
        Packet p = Packet.request(FILE_EVENT)
                .with("workspaceId", ws)
                .with("path", path)
                .with("action", action)
                .with("baseVersion", baseVersion);

        Packet r;
        if ("DELETE".equals(action)) {
            r = network.request(p, Duration.ofSeconds(30));
        } else {
            Path file = safeResolve(workspaceDir(workspace), path);
            if (!Files.isRegularFile(file))
                return;
            r = network.request(p, file, Duration.ofMinutes(5));
        }

        if (ERROR.equals(r.type()))
            throw new IllegalStateException(r.get("message"));
        if (!FILE_EVENT_RESULT.equals(r.type()))
            return;

        if (r.getBoolean("conflict", false)) {
            state.markConflicted(ws, path);
            warnOnce("conflict-upload:" + ws + ":" + path,
                    path + " conflicted: local base v" + baseVersion + ", server is v" + r.get("currentVersion"));
            syncNow();
            return;
        }

        long newVersion = r.getLong("version", 0);
        if ("DELETE".equals(action)) {
            // Keep the tombstone version so a later CREATE of the same path can send
            // the correct baseVersion instead of pretending the path never existed.
            state.put(ws, path, newVersion, "");
        } else {
            state.put(ws, path, newVersion, r.get("checksum"));
        }
        notify("INFO", path + " synchronized (v" + newVersion + ")");
    }

    public void syncNow() throws Exception {
        List<Map<String, String>> rows = new ArrayList<>();
        List<Map<String, String>> backups = new ArrayList<>();
        for (Map<String, String> ws : workspaces) {
            long wsId = parseLong(ws.get("id"), 0);
            String workspaceName = ws.getOrDefault("name", String.valueOf(wsId));
            for (LocalBackupStore.BackupVersion backup :
                    LocalBackupStore.listVersions(config.syncRoot(), wsId, workspaceName)) {
                Map<String, String> row = new LinkedHashMap<>();
                row.put("workspace_id", String.valueOf(wsId));
                row.put("path", backup.path());
                row.put("version", String.valueOf(backup.version()));
                backups.add(row);
            }
            Path dir = workspaceDir(ws);
            if (!Files.exists(dir))
                continue;
            try (var stream = Files.walk(dir)) {
                for (Path p : stream.filter(Files::isRegularFile).toList()) {
                    if (SyncFileFilter.shouldIgnore(p))
                        continue;
                    String rel = dir.relativize(p).toString().replace('\\', '/');
                    Map<String, String> row = new LinkedHashMap<>();
                    row.put("workspace_id", String.valueOf(wsId));
                    row.put("path", rel);
                    row.put("size", String.valueOf(Files.size(p)));
                    row.put("checksum", HashUtil.sha256(p));
                    row.put("known_version", String.valueOf(state.version(wsId, rel)));
                    row.put("conflicted", String.valueOf(state.isConflicted(wsId, rel)));
                    rows.add(row);
                }
            }
        }
        Packet p = Packet.request(SYNC_REPORT).with("rows", TableCodec.encode(rows))
                .with("backups", TableCodec.encode(backups));
        Packet r = network.request(p, Duration.ofMinutes(2));
        if (ERROR.equals(r.type()))
            throw new IllegalStateException(r.get("message"));
        notify("INFO", "Sync scan completed; server scheduled " + r.get("actions") + " action(s)");
    }

    private void handleEvent(ReceivedPacket rp) {
        try {
            Packet p = rp.packet();
            switch (p.type()) {
                case FILE_BACKUP -> saveBackupEvent(p, rp.payloadPath());
                case FILE_DELIVER -> applyDeliveredFile(p, rp.payloadPath());
                case FILE_DELETE -> applyDelete(p);
                case FILE_FETCH_REQUEST -> workers.submit(() -> relayFile(p));
                case FILE_UPLOAD_REQUEST -> workers.submit(() -> {
                    try {
                        uploadLocalChange(p.getLong("workspaceId", 0), p.get("path"), p.get("action"));
                    } catch (Exception e) {
                        notify("ERROR", "Requested upload failed: " + e.getMessage());
                    }
                });
                case SYNC_STATE ->
                    state.put(p.getLong("workspaceId", 0), p.get("path"), p.getLong("version", 0), p.get("checksum"));
                case CONFLICT_NOTICE -> notify("WARN", "Conflict: " + p.get("path") + " - " + p.get("message"));
                case NOTIFICATION -> notify(p.get("level").isBlank() ? "INFO" : p.get("level"), p.get("message"));
                case WORKSPACES_CHANGED -> workers.submit(() -> {
                    try {
                        refreshWorkspaces();
                        restartWatchers();
                        syncNow();
                        notify("INFO", "Workspace list updated");
                    } catch (Exception e) {
                        notify("WARN", "Cannot refresh workspaces: " + e.getMessage());
                    }
                });
                default -> {
                }
            }
        } catch (Exception e) {
            notify("ERROR", "Incoming sync error: " + e.getMessage());
        }
    }

    private void applyDeliveredFile(Packet p, Path tempPayload) throws Exception {
        if (tempPayload == null)
            return;
        long ws = p.getLong("workspaceId", 0);
        Map<String, String> workspace = workspace(ws);
        if (workspace == null) {
            refreshWorkspaces();
            workspace = workspace(ws);
        }
        if (workspace == null)
            return;

        String rel = p.get("path");
        long incomingVersion = p.getLong("version", 0);
        long knownVersion = state.version(ws, rel);
        String remoteChecksum = p.get("checksum");
        Path target = safeResolve(workspaceDir(workspace), rel);
        String localChecksum = Files.isRegularFile(target) ? HashUtil.sha256(target) : "";
        boolean staleRemoteVersion = incomingVersion < knownVersion;
        boolean sameVersionDifferentContent = !p.getBoolean("conflict", false)
                && incomingVersion == knownVersion
                && !Objects.equals(remoteChecksum, localChecksum) && !localChecksum.isBlank();
        if (staleRemoteVersion || sameVersionDifferentContent) {
            Files.createDirectories(target.getParent());
            String key = "conflict-remote:" + ws + ":" + rel;
            if (Files.isRegularFile(target)) {
                createConflictCopy(ws, rel, target);
                warnOnce(key, "Conflict detected for " + rel
                        + ": local version is newer or different from server. A conflict copy was saved.");
            } else {
                warnOnce(key, "Conflict detected for " + rel
                        + ": local version is newer or different from server. Server version will not overwrite the local copy.");
            }
            return;
        }

        Files.createDirectories(target.getParent());

        boolean dirty = Files.isRegularFile(target) && isLocalDirty(ws, rel, target);
        long pendingStaleEditBase = Files.isRegularFile(target)
                && !dirty
                && !Objects.equals(localChecksum, remoteChecksum)
                ? knownVersion : -1;
        boolean conflict = p.getBoolean("conflict", false) || dirty;
        if (conflict && Files.isRegularFile(target)) {
            createConflictCopy(ws, rel, target);
            warnOnce("conflict-local:" + ws + ":" + rel,
                    "Conflict copy created for " + rel
                            + ". Local changes were preserved and the server version is waiting for resolution.");
        }

        try {
            Path staged = Files.createTempFile(target.getParent(), target.getFileName() + ".", ".tmp");
            try {
                Files.copy(tempPayload, staged, StandardCopyOption.REPLACE_EXISTING);
                if (!Objects.equals(HashUtil.sha256(staged), remoteChecksum)) {
                    throw new IllegalStateException("Received content checksum does not match server metadata");
                }
                String currentChecksum = Files.isRegularFile(target) ? HashUtil.sha256(target) : "";
                if (!Objects.equals(currentChecksum, localChecksum)) {
                    throw new IllegalStateException("Local file changed during synchronization; refusing overwrite");
                }
                if (!conflict && !currentChecksum.isBlank()
                        && !Objects.equals(currentChecksum, remoteChecksum)) {
                    createBackup(ws, rel, target, knownVersion, currentChecksum, remoteChecksum);
                }
                String finalLocalChecksum = Files.isRegularFile(target) ? HashUtil.sha256(target) : "";
                if (!Objects.equals(finalLocalChecksum, localChecksum)) {
                    throw new IllegalStateException("Local file changed during synchronization; refusing overwrite");
                }
                ignore.ignore(target, 3000);
                try {
                    Files.move(staged, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                } catch (AtomicMoveNotSupportedException e) {
                    Files.move(staged, target, StandardCopyOption.REPLACE_EXISTING);
                }
            } finally {
                Files.deleteIfExists(staged);
            }
            suppressedWarnings.remove(ws + ":" + rel);
            pendingDeferredSync.remove(ws + ":" + rel);
            pendingLocalUploadRetry.remove(ws + ":" + rel);
        } catch (Exception ex) {
            String key = ws + ":" + rel;
            if (Files.isRegularFile(target)) {
                if (!dirty) {
                    warnOnce(key, "New server version received for " + rel
                            + ", but the file is currently locked; sync will retry after it is closed.");
                    scheduleDeferredSync(key, rel);
                    return;
                }
                createConflictCopy(ws, rel, target);
                warnOnce(key, "Conflict detected for " + rel
                        + ". Local changes were preserved. The server version remains authoritative until resolved.");
                scheduleLocalUploadRetry(key, ws, rel);
                return;
            }
            warnOnce(key, "New server version received for " + rel
                    + ", but the local file is not available right now. Sync will retry when the file is available again.");
            scheduleDeferredSync(key, rel);
            return;
        }

        String checksum = HashUtil.sha256(target);
        if (pendingStaleEditBase >= 0) {
            state.markRemoteApplied(ws, rel, incomingVersion, checksum, pendingStaleEditBase);
        } else {
            state.put(ws, rel, incomingVersion, checksum);
        }
        network.send(Packet.event(FILE_APPLIED)
                .with("fileId", p.get("fileId"))
                .with("version", incomingVersion)
                .with("checksum", checksum));
        notify("INFO", "Downloaded/synced " + rel + " (v" + incomingVersion + ")");
    }

    private void applyDelete(Packet p) throws Exception {
        long ws = p.getLong("workspaceId", 0);
        Map<String, String> workspace = workspace(ws);
        if (workspace == null)
            return;

        String rel = p.get("path");
        long incomingVersion = p.getLong("version", 0);
        long knownVersion = state.version(ws, rel);
        if (incomingVersion < knownVersion) {
            notify("WARN",
                    "Ignored stale remote delete of " + rel + " (v" + incomingVersion + " < v" + knownVersion + ")");
            return;
        }

        Path target = safeResolve(workspaceDir(workspace), rel);
        boolean dirty = Files.isRegularFile(target) && isLocalDirty(ws, rel, target);
        if (dirty) {
            Path conflictCopy = createConflictCopy(ws, rel, target);
            notify("WARN", "Unsynced local changes preserved before remote delete: " + conflictCopy);
        }
        if (Files.isRegularFile(target) && !dirty) {
            createBackup(ws, rel, target, knownVersion, HashUtil.sha256(target));
        }

        ignore.ignore(target, 3000);
        Files.deleteIfExists(target);
        // Keep a tombstone version instead of removing local state completely.
        state.put(ws, rel, incomingVersion, "");
        notify("INFO", rel + " deleted by synchronization (v" + incomingVersion + ")");
    }

    private void relayFile(Packet p) {
        try {
            long ws = p.getLong("workspaceId", 0);
            Map<String, String> workspace = workspace(ws);
            if (workspace == null)
                return;
            Path file = safeResolve(workspaceDir(workspace), p.get("path"));
            if (!Files.isRegularFile(file)) {
                notify("WARN", "Server requested a replica that is missing locally: " + p.get("path"));
                return;
            }
            Packet relay = Packet.event(FILE_RELAY_UPLOAD)
                    .with("ticket", p.get("ticket"))
                    .with("targetAgentId", p.get("targetAgentId"))
                    .with("workspaceId", ws)
                    .with("path", p.get("path"))
                    .with("fileId", p.get("fileId"))
                    .with("version", p.get("version"))
                    .with("checksum", p.get("checksum"))
                    .with("conflict", p.getBoolean("conflict", false));
            network.send(relay, file);
        } catch (Exception e) {
            notify("ERROR", "Replica relay failed: " + e.getMessage());
        }
    }

    public Path workspaceDir(long workspaceId) {
        Map<String, String> ws = workspace(workspaceId);
        return ws == null ? null : workspaceDir(ws);
    }

    public Path workspaceDir(Map<String, String> ws) {
        String safe = ws.getOrDefault("name", "workspace").replaceAll("[^\\p{L}\\p{N}._ -]", "_").trim();
        return config.syncRoot().resolve(ws.get("id") + "_" + safe).toAbsolutePath().normalize();
    }

    public void uploadFile(long workspaceId, Path source) throws Exception {
        Map<String, String> ws = workspace(workspaceId);
        if (ws == null)
            throw new IllegalArgumentException("Workspace not found");
        Path dir = workspaceDir(ws);
        Files.createDirectories(dir);
        Path target = dir.resolve(source.getFileName().toString());
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING);
    }

    public void deleteLocalFile(long workspaceId, String relativePath) throws Exception {
        Map<String, String> ws = workspace(workspaceId);
        if (ws == null)
            return;
        Files.deleteIfExists(safeResolve(workspaceDir(ws), relativePath));
    }

    public void deleteWorkspaceFile(long workspaceId, String relativePath) throws Exception {
        Map<String, String> ws = workspace(workspaceId);
        if (ws == null)
            throw new IllegalArgumentException("Workspace not found");
        if (!allowed(ws, "DELETE"))
            throw new SecurityException("No DELETE permission");

        long baseVersion = state.version(workspaceId, relativePath);
        Path local = safeResolve(workspaceDir(ws), relativePath);
        ignore.ignore(local, 3000);
        Files.deleteIfExists(local);

        Packet p = Packet.request(FILE_EVENT)
                .with("workspaceId", workspaceId)
                .with("path", relativePath)
                .with("action", "DELETE")
                .with("baseVersion", baseVersion);
        Packet r = network.request(p, Duration.ofSeconds(30));
        if (ERROR.equals(r.type()))
            throw new IllegalStateException(r.get("message"));

        if (r.getBoolean("conflict", false)) {
            notify("WARN", relativePath + " delete conflicted: local base v" + baseVersion
                    + ", server is v" + r.get("currentVersion"));
            syncNow(); // the current server version will be restored locally
            return;
        }

        state.put(workspaceId, relativePath, r.getLong("version", 0), "");
    }

    public void saveCopy(long workspaceId, String relativePath, Path destination) throws Exception {
        Map<String, String> ws = workspace(workspaceId);
        if (ws == null)
            throw new IllegalArgumentException("Workspace not found");
        Path source = safeResolve(workspaceDir(ws), relativePath);
        if (!Files.exists(source))
            throw new IllegalStateException("File is not available locally yet. Run Sync Now first.");
        Files.copy(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }

    public void openWorkspace(long workspaceId) throws Exception {
        Path p = workspaceDir(workspaceId);
        if (p != null) {
            Files.createDirectories(p);
            Desktop.getDesktop().open(p.toFile());
        }
    }

    public boolean canManageWorkspace(Map<String, String> ws) {
        return isAdmin() || "LEADER".equalsIgnoreCase(ws.getOrDefault("workspace_role", ""));
    }

    public boolean allowed(Map<String, String> ws, String action) {
        if (isAdmin())
            return true;
        String key = switch (action.toUpperCase(Locale.ROOT)) {
            case "CREATE", "WRITE" -> "can_write";
            case "MODIFY" -> "can_modify";
            case "DELETE" -> "can_delete";
            default -> "can_read";
        };
        return truthy(ws.get(key));
    }

    public void changeSyncRoot(Path root) throws Exception {
        config.setSyncRoot(root);
        config.save();
        for (FolderWatcher w : watchers.values())
            w.close();
        watchers.clear();
        Files.createDirectories(root);
        state = new LocalStateStore(root);
        startWatchers();
        syncNow();
    }

    private boolean isLocalDirty(long workspaceId, String relativePath, Path localFile) throws Exception {
        if (!Files.isRegularFile(localFile))
            return false;
        String knownChecksum = state.checksum(workspaceId, relativePath);
        String currentChecksum = HashUtil.sha256(localFile);
        return !Objects.equals(currentChecksum, knownChecksum);
    }

    private Path createConflictCopy(long workspaceId, String relativePath, Path source) throws Exception {
        Path conflictRoot = config.syncRoot()
                .resolve(".pbl4sync")
                .resolve("conflicts")
                .resolve(String.valueOf(workspaceId))
                .toAbsolutePath().normalize();

        Path logicalTarget = safeResolve(conflictRoot, relativePath);
        Files.createDirectories(logicalTarget.getParent());

        String name = logicalTarget.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        String ext = dot > 0 ? name.substring(dot) : "";
        String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS"));
        String device = config.deviceName().replaceAll("[^a-zA-Z0-9_-]", "_");

        Path conflict = logicalTarget.resolveSibling(base + "_conflict_" + device + "_" + stamp + ext);
        Files.copy(source, conflict, StandardCopyOption.REPLACE_EXISTING);
        return conflict;
    }

    private void saveBackupEvent(Packet packet, Path payload) throws Exception {
        if (payload == null)
            throw new IllegalStateException("Server backup event did not include file content");
        long workspaceId = packet.getLong("workspaceId", 0);
        Map<String, String> ws = workspace(workspaceId);
        if (ws == null) {
            refreshWorkspaces();
            ws = workspace(workspaceId);
        }
        if (ws == null)
            throw new IllegalStateException("Backup workspace is not available: " + workspaceId);

        String relativePath = packet.get("path");
        String checksum = packet.get("checksum");
        Path archived = LocalBackupStore.archiveVersion(config.syncRoot(), workspaceId,
                ws.getOrDefault("name", String.valueOf(workspaceId)), relativePath, payload,
                packet.getLong("version", 0), checksum);
        if (archived != null)
            notify("INFO", "Backed up " + relativePath + " (v" + packet.get("version") + ")");
    }

    private Path createBackup(long workspaceId, String relativePath, Path source, long version,
                              String expectedChecksum) throws Exception {
        return createBackup(workspaceId, relativePath, source, version, expectedChecksum, null);
    }

    private Path createBackup(long workspaceId, String relativePath, Path source, long version,
                              String expectedChecksum, String incomingChecksum) throws Exception {
        Map<String, String> ws = workspace(workspaceId);
        String workspaceName = ws == null ? String.valueOf(workspaceId)
                : ws.getOrDefault("name", String.valueOf(workspaceId));
        return LocalBackupStore.backupIfDifferent(config.syncRoot(), workspaceId, workspaceName,
                relativePath, source, version, expectedChecksum, incomingChecksum);
    }

    public void setNotificationListener(BiConsumer<String, String> listener) {
        this.notificationListener = listener;
    }

    private void scheduleDeferredSync(String key, String rel) {
        if (!pendingDeferredSync.add(key))
            return;
        syncScheduler.schedule(() -> {
            try {
                pendingDeferredSync.remove(key);
                syncNow();
            } catch (Exception e) {
                warnOnce(key, "Deferred sync for " + rel + " failed: " + e.getMessage());
                scheduleDeferredSync(key, rel);
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void scheduleLocalUploadRetry(String key, long ws, String rel) {
        if (!pendingLocalUploadRetry.add(key))
            return;
        syncScheduler.schedule(() -> {
            try {
                pendingLocalUploadRetry.remove(key);
                Map<String, String> workspace = workspace(ws);
                if (workspace == null)
                    return;
                Path file = safeResolve(workspaceDir(workspace), rel);
                if (!Files.isRegularFile(file))
                    return;
                if (isLocalDirty(ws, rel, file)) {
                    uploadLocalChange(ws, rel, "MODIFY");
                } else {
                    syncNow();
                }
            } catch (Exception e) {
                warnOnce(key, "Deferred local upload for " + rel + " failed: " + e.getMessage());
                scheduleLocalUploadRetry(key, ws, rel);
            }
        }, 3, TimeUnit.SECONDS);
    }

    private void warnOnce(String key, String message) {
        if (suppressedWarnings.add(key))
            notify("WARN", message);
    }

    private void notify(String level, String message) {
        if ("WARN".equalsIgnoreCase(level)) {
            String normalizedKey = "warn:" + level + ":" + message.replaceAll("\\d{8}_\\d{6}_\\d{3}", "<time>")
                    .replaceAll("[_-]conflict_[A-Za-z0-9_-]+", "_conflict_");
            if (!suppressedWarnings.add(normalizedKey))
                return;
        }
        notificationListener.accept(level, message);
    }

    private Map<String, String> workspace(long id) {
        for (Map<String, String> w : workspaces)
            if (parseLong(w.get("id"), -1) == id)
                return w;
        return null;
    }

    private Path safeResolve(Path root, String rel) {
        Path p = root.resolve(rel.replace('/', java.io.File.separatorChar)).normalize();
        if (!p.startsWith(root.normalize()))
            throw new IllegalArgumentException("Unsafe file path");
        return p;
    }

    private long parseLong(String s, long d) {
        try {
            return Long.parseLong(s);
        } catch (Exception e) {
            return d;
        }
    }

    private boolean truthy(String s) {
        return "1".equals(s) || "true".equalsIgnoreCase(s);
    }

    @Override
    public void close() {
        for (FolderWatcher w : watchers.values())
            w.close();
        watchers.clear();
        workers.shutdownNow();
        syncScheduler.shutdownNow();
        network.close();
    }
}
