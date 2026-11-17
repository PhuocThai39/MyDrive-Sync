package vn.edu.pbl4sync.server.db;

import vn.edu.pbl4sync.common.ChunkManifest;
import vn.edu.pbl4sync.common.HashUtil;

import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class Database implements AutoCloseable {
    private final SimpleConnectionPool pool;

    public Database(String url, String user, String password, int poolSize, Properties connectionProperties,
                    String initialAdminPassword) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        this.pool = new SimpleConnectionPool(url, user, password, poolSize, connectionProperties);
        initializeSchema();
        markAllAgentsOffline();
        seedAdmin(initialAdminPassword);
    }

    SimpleConnectionPool pool() {
        return pool;
    }

    private void initializeSchema() throws SQLException {
        List<String> ddl = List.of(
            """
            CREATE TABLE IF NOT EXISTS users (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              username VARCHAR(80) NOT NULL UNIQUE,
              password_hash VARCHAR(512) NOT NULL,
              system_role VARCHAR(20) NOT NULL DEFAULT 'USER',
              status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS workspaces (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              name VARCHAR(150) NOT NULL UNIQUE,
              created_by BIGINT NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              CONSTRAINT fk_ws_creator FOREIGN KEY(created_by) REFERENCES users(id)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS workspace_members (
              workspace_id BIGINT NOT NULL,
              user_id BIGINT NOT NULL,
              workspace_role VARCHAR(20) NOT NULL DEFAULT 'MEMBER',
              can_read BOOLEAN NOT NULL DEFAULT TRUE,
              can_write BOOLEAN NOT NULL DEFAULT FALSE,
              can_modify BOOLEAN NOT NULL DEFAULT FALSE,
              can_delete BOOLEAN NOT NULL DEFAULT FALSE,
              joined_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY(workspace_id, user_id),
              CONSTRAINT fk_wm_ws FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
              CONSTRAINT fk_wm_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS agents (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_id BIGINT NOT NULL,
              device_name VARCHAR(180) NOT NULL,
              last_ip VARCHAR(64),
              status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
              last_online TIMESTAMP NULL,
              UNIQUE KEY uq_agent(user_id, device_name),
              CONSTRAINT fk_agent_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS files (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              workspace_id BIGINT NOT NULL,
              relative_path VARCHAR(700) NOT NULL,
              size BIGINT NOT NULL DEFAULT 0,
              checksum VARCHAR(128),
              version BIGINT NOT NULL DEFAULT 1,
              modified_by BIGINT,
              modified_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              deleted BOOLEAN NOT NULL DEFAULT FALSE,
              UNIQUE KEY uq_workspace_path(workspace_id, relative_path),
              INDEX idx_files_ws(workspace_id),
              CONSTRAINT fk_file_ws FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE CASCADE,
              CONSTRAINT fk_file_user FOREIGN KEY(modified_by) REFERENCES users(id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS file_versions (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              file_id BIGINT NOT NULL,
              version BIGINT NOT NULL,
              base_version BIGINT NOT NULL,
              checksum VARCHAR(128) NOT NULL,
              manifest MEDIUMTEXT NOT NULL,
              is_deleted BOOLEAN NOT NULL DEFAULT FALSE,
              source_user_id BIGINT NULL,
              source_device VARCHAR(180),
              operation VARCHAR(30) NOT NULL,
              rollback_from_version BIGINT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              UNIQUE KEY uq_file_version(file_id,version),
              CONSTRAINT fk_file_versions_file FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE,
              CONSTRAINT fk_file_versions_user FOREIGN KEY(source_user_id) REFERENCES users(id) ON DELETE SET NULL
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS chunks (
              hash VARCHAR(64) PRIMARY KEY,
              size INT NOT NULL,
              content LONGBLOB NOT NULL,
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS file_version_chunks (
              file_id BIGINT NOT NULL,
              version BIGINT NOT NULL,
              chunk_order INT NOT NULL,
              chunk_hash VARCHAR(64) NOT NULL,
              chunk_size INT NOT NULL,
              PRIMARY KEY(file_id,version,chunk_order),
              INDEX idx_fvc_chunk_hash(chunk_hash),
              CONSTRAINT fk_fvc_file FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE,
              CONSTRAINT fk_fvc_version FOREIGN KEY(file_id,version)
                REFERENCES file_versions(file_id,version) ON DELETE CASCADE,
              CONSTRAINT fk_fvc_chunk FOREIGN KEY(chunk_hash) REFERENCES chunks(hash)
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS file_replicas (
              file_id BIGINT NOT NULL,
              agent_id BIGINT NOT NULL,
              version BIGINT NOT NULL,
              checksum VARCHAR(128),
              last_seen TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              PRIMARY KEY(file_id, agent_id),
              CONSTRAINT fk_rep_file FOREIGN KEY(file_id) REFERENCES files(id) ON DELETE CASCADE,
              CONSTRAINT fk_rep_agent FOREIGN KEY(agent_id) REFERENCES agents(id) ON DELETE CASCADE
            )
            """,
            """
            CREATE TABLE IF NOT EXISTS activities (
              id BIGINT PRIMARY KEY AUTO_INCREMENT,
              user_id BIGINT,
              workspace_id BIGINT,
              action VARCHAR(80) NOT NULL,
              detail VARCHAR(1000),
              created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
              INDEX idx_activity_time(created_at),
              CONSTRAINT fk_act_user FOREIGN KEY(user_id) REFERENCES users(id) ON DELETE SET NULL,
              CONSTRAINT fk_act_ws FOREIGN KEY(workspace_id) REFERENCES workspaces(id) ON DELETE SET NULL
            )
            """
        );
        Connection c = pool.borrow();
        try (Statement st = c.createStatement()) {
            for (String sql : ddl) st.execute(sql);
        } finally {
            pool.release(c);
        }
    }

    private void seedAdmin(String initialAdminPassword) throws SQLException {
        if (findUserByUsername("admin") != null) return;
        createUser("admin", initialAdminPassword.toCharArray(), "ADMIN");
        logActivity(null, null, "SYSTEM", "Default admin created (username: admin)");
    }

    public Map<String, String> authenticate(String username, char[] password) throws SQLException {
        Map<String, String> user = findUserByUsername(username);
        if (user == null || !"ACTIVE".equalsIgnoreCase(user.get("status"))) return null;
        if (!HashUtil.verifyPassword(password, user.get("password_hash"))) return null;
        user.remove("password_hash");
        return user;
    }

    public Map<String, String> findUserByUsername(String username) throws SQLException {
        return queryOne("SELECT id,username,password_hash,system_role,status FROM users WHERE username=?", username);
    }

    public long createUser(String username, char[] password, String role) throws SQLException {
        String hash = HashUtil.hashPassword(password);
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(
                "INSERT INTO users(username,password_hash,system_role,status) VALUES(?,?,?,'ACTIVE')",
                Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, username);
            ps.setString(2, hash);
            ps.setString(3, role == null ? "USER" : role.toUpperCase(Locale.ROOT));
            ps.executeUpdate();
            try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); return rs.getLong(1); }
        } finally { pool.release(c); }
    }

    public void setUserStatus(long userId, String status) throws SQLException {
        update("UPDATE users SET status=? WHERE id=?", status, userId);
    }

    public List<Map<String, String>> listUsers() throws SQLException {
        return query("SELECT id,username,system_role,status,created_at FROM users ORDER BY username");
    }

    public long registerAgent(long userId, String deviceName, String ip) throws SQLException {
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO agents(user_id,device_name,last_ip,status,last_online) VALUES(?,?,?,'ONLINE',NOW()) " +
                    "ON DUPLICATE KEY UPDATE last_ip=VALUES(last_ip),status='ONLINE',last_online=NOW()")) {
                ps.setLong(1, userId); ps.setString(2, deviceName); ps.setString(3, ip); ps.executeUpdate();
            }
            long id;
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM agents WHERE user_id=? AND device_name=?")) {
                ps.setLong(1, userId); ps.setString(2, deviceName);
                try (ResultSet rs = ps.executeQuery()) { rs.next(); id = rs.getLong(1); }
            }
            c.commit();
            return id;
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }


    public void markAllAgentsOffline() throws SQLException {
        update("UPDATE agents SET status='OFFLINE'");
    }

    public void markAgentOffline(long agentId) throws SQLException {
        update("UPDATE agents SET status='OFFLINE',last_online=NOW() WHERE id=?", agentId);
    }

    public List<Map<String, String>> listAgents() throws SQLException {
        return query("SELECT a.id,a.device_name,u.username,a.last_ip,a.status,a.last_online FROM agents a JOIN users u ON u.id=a.user_id ORDER BY a.status DESC,u.username");
    }

    public List<Map<String, String>> listWorkspaces(long userId, boolean admin) throws SQLException {
        if (admin) {
            return query("""
                SELECT w.id,w.name,COALESCE(l.username,'') leader,'ADMIN' workspace_role,
                       'true' can_read,'true' can_write,'true' can_modify,'true' can_delete,
                       (SELECT COUNT(*) FROM workspace_members x WHERE x.workspace_id=w.id) member_count
                FROM workspaces w
                LEFT JOIN workspace_members lm ON lm.workspace_id=w.id AND lm.workspace_role='LEADER'
                LEFT JOIN users l ON l.id=lm.user_id
                ORDER BY w.name
                """);
        }
        return query("""
            SELECT w.id,w.name,COALESCE(l.username,'') leader,wm.workspace_role,
                   wm.can_read,wm.can_write,wm.can_modify,wm.can_delete,
                   (SELECT COUNT(*) FROM workspace_members x WHERE x.workspace_id=w.id) member_count
            FROM workspace_members wm
            JOIN workspaces w ON w.id=wm.workspace_id
            LEFT JOIN workspace_members lm ON lm.workspace_id=w.id AND lm.workspace_role='LEADER'
            LEFT JOIN users l ON l.id=lm.user_id
            WHERE wm.user_id=? ORDER BY w.name
            """, userId);
    }

    public long createWorkspace(long creatorId, String name, String leaderUsername) throws SQLException {
        Map<String, String> leader = findUserByUsername(leaderUsername);
        if (leader == null) throw new SQLException("Leader user not found");
        long leaderId = Long.parseLong(leader.get("id"));
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            long wsId;
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO workspaces(name,created_by) VALUES(?,?)", Statement.RETURN_GENERATED_KEYS)) {
                ps.setString(1, name); ps.setLong(2, creatorId); ps.executeUpdate();
                try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); wsId = rs.getLong(1); }
            }
            try (PreparedStatement ps = c.prepareStatement(
                    "INSERT INTO workspace_members(workspace_id,user_id,workspace_role,can_read,can_write,can_modify,can_delete) VALUES(?,?,'LEADER',1,1,1,1)")) {
                ps.setLong(1, wsId); ps.setLong(2, leaderId); ps.executeUpdate();
            }
            c.commit();
            return wsId;
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }


    public void changeLeader(long workspaceId, String newLeaderUsername) throws SQLException {
        Map<String, String> user = findUserByUsername(newLeaderUsername);
        if (user == null) throw new SQLException("New leader user not found");
        long newLeaderId = Long.parseLong(user.get("id"));
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement("UPDATE workspace_members SET workspace_role='MEMBER' WHERE workspace_id=? AND workspace_role='LEADER'")) {
                ps.setLong(1, workspaceId); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO workspace_members(workspace_id,user_id,workspace_role,can_read,can_write,can_modify,can_delete)
                VALUES(?,?,'LEADER',1,1,1,1)
                ON DUPLICATE KEY UPDATE workspace_role='LEADER',can_read=1,can_write=1,can_modify=1,can_delete=1
                """)) {
                ps.setLong(1, workspaceId); ps.setLong(2, newLeaderId); ps.executeUpdate();
            }
            c.commit();
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    public void deleteWorkspace(long workspaceId) throws SQLException {
        update("DELETE FROM workspaces WHERE id=?", workspaceId);
    }

    public boolean isLeader(long userId, long workspaceId) throws SQLException {
        Map<String, String> row = queryOne("SELECT 1 ok FROM workspace_members WHERE workspace_id=? AND user_id=? AND workspace_role='LEADER'", workspaceId, userId);
        return row != null;
    }

    public boolean isMember(long userId, long workspaceId) throws SQLException {
        return queryOne("SELECT 1 ok FROM workspace_members WHERE workspace_id=? AND user_id=?", workspaceId, userId) != null;
    }

    public boolean hasPermission(long userId, boolean admin, long workspaceId, String permission) throws SQLException {
        if (admin) return true;
        String column = switch (permission.toUpperCase(Locale.ROOT)) {
            case "READ" -> "can_read";
            case "WRITE" -> "can_write";
            case "MODIFY" -> "can_modify";
            case "DELETE" -> "can_delete";
            default -> throw new IllegalArgumentException("Unknown permission " + permission);
        };
        Map<String, String> row = queryOne("SELECT " + column + " allowed FROM workspace_members WHERE workspace_id=? AND user_id=?", workspaceId, userId);
        return row != null && ("1".equals(row.get("allowed")) || "true".equalsIgnoreCase(row.get("allowed")));
    }

    public List<Map<String, String>> listMembers(long workspaceId) throws SQLException {
        return query("""
            SELECT u.id user_id,u.username,wm.workspace_role,wm.can_read,wm.can_write,wm.can_modify,wm.can_delete
            FROM workspace_members wm JOIN users u ON u.id=wm.user_id
            WHERE wm.workspace_id=? ORDER BY wm.workspace_role='LEADER' DESC,u.username
            """, workspaceId);
    }

    public void addMember(long workspaceId, String username, String role, boolean read, boolean write, boolean modify, boolean delete) throws SQLException {
        Map<String, String> user = findUserByUsername(username);
        if (user == null) throw new SQLException("User not found");
        long uid = Long.parseLong(user.get("id"));
        update("""
            INSERT INTO workspace_members(workspace_id,user_id,workspace_role,can_read,can_write,can_modify,can_delete)
            VALUES(?,?,?,?,?,?,?)
            ON DUPLICATE KEY UPDATE workspace_role=VALUES(workspace_role),can_read=VALUES(can_read),can_write=VALUES(can_write),can_modify=VALUES(can_modify),can_delete=VALUES(can_delete)
            """, workspaceId, uid, role, read, write, modify, delete);
    }

    public void updatePermissions(long workspaceId, long userId, boolean read, boolean write, boolean modify, boolean delete) throws SQLException {
        update("UPDATE workspace_members SET can_read=?,can_write=?,can_modify=?,can_delete=? WHERE workspace_id=? AND user_id=?",
                read, write, modify, delete, workspaceId, userId);
    }

    public void removeMember(long workspaceId, long userId) throws SQLException {
        update("DELETE FROM workspace_members WHERE workspace_id=? AND user_id=? AND workspace_role<>'LEADER'", workspaceId, userId);
    }

    public List<Long> workspaceUserIds(long workspaceId) throws SQLException {
        List<Long> ids = new ArrayList<>();
        for (Map<String, String> r : query("SELECT user_id FROM workspace_members WHERE workspace_id=?", workspaceId)) {
            ids.add(Long.parseLong(r.get("user_id")));
        }
        return ids;
    }

    public List<Map<String, String>> listFiles(long workspaceId) throws SQLException {
        return query("""
            SELECT f.id,f.relative_path,f.size,f.checksum,f.version,f.deleted,f.modified_at,COALESCE(u.username,'') modified_by
            FROM files f LEFT JOIN users u ON u.id=f.modified_by
            WHERE f.workspace_id=? ORDER BY f.deleted, f.relative_path
            """, workspaceId);
    }

    public List<Map<String, String>> listFileVersions(long fileId) throws SQLException {
        List<Map<String, String>> versions = query("""
            SELECT version,checksum,manifest,is_deleted,operation,rollback_from_version,created_at
            FROM file_versions WHERE file_id=? ORDER BY version DESC
            """, fileId);
        for (Map<String, String> version : versions) {
            try {
                ChunkManifest manifest = ChunkManifest.parse(version.getOrDefault("manifest", ""));
                version.put("size", Long.toString(manifest.totalSize()));
                version.put("manifest_valid", "true");
            } catch (IllegalArgumentException e) {
                version.put("size", "");
                version.put("manifest_valid", "false");
            }
            version.remove("manifest");
        }
        return versions;
    }

    public Map<String, String> findFile(long workspaceId, String relativePath) throws SQLException {
        return queryOne("SELECT id,workspace_id,relative_path,size,checksum,version,deleted,modified_by,modified_at FROM files WHERE workspace_id=? AND relative_path=?",
                workspaceId, relativePath);
    }

    public Map<String, String> findFileById(long fileId) throws SQLException {
        return queryOne("SELECT id,workspace_id,relative_path,size,checksum,version,deleted FROM files WHERE id=?", fileId);
    }

    public String memberRole(long workspaceId, long userId) throws SQLException {
        Map<String, String> row = queryOne("SELECT workspace_role FROM workspace_members WHERE workspace_id=? AND user_id=?", workspaceId, userId);
        return row == null ? "" : row.getOrDefault("workspace_role", "");
    }

    public Map<String, String> applyFileChange(long workspaceId, String path, long size, String checksum,
                                                    long userId, long baseVersion, boolean deleted) throws SQLException {
        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            Map<String, String> existing = queryOne(c,
                    "SELECT id,size,checksum,version,deleted FROM files WHERE workspace_id=? AND relative_path=? FOR UPDATE",
                    workspaceId, path);

            long id;
            long version;

            if (existing == null) {
                // A truly new path must be based on version 0. If the Agent claims it
                // edited/deleted a version that the server does not have, do not guess.
                if (baseVersion != 0) {
                    c.rollback();
                    return row("conflict", true, "current_version", 0, "current_checksum", "", "current_deleted", true);
                }

                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO files(workspace_id,relative_path,size,checksum,version,modified_by,modified_at,deleted) VALUES(?,?,?,?,1,?,NOW(),?)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, workspaceId);
                    ps.setString(2, path);
                    ps.setLong(3, size);
                    ps.setString(4, checksum);
                    ps.setLong(5, userId);
                    ps.setBoolean(6, deleted);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) { rs.next(); id = rs.getLong(1); }
                }
                version = 1;
            } else {
                id = Long.parseLong(existing.get("id"));
                long currentVersion = Long.parseLong(existing.get("version"));

                // Optimistic concurrency check: the Agent is only allowed to change
                // exactly the version on which its local edit/delete was based.
                if (baseVersion != currentVersion) {
                    c.rollback();
                    return row(
                            "conflict", true,
                            "id", id,
                            "current_version", currentVersion,
                            "current_checksum", existing.getOrDefault("checksum", ""),
                            "current_deleted", existing.getOrDefault("deleted", "false"),
                            "current_size", existing.getOrDefault("size", "0")
                    );
                }

                version = currentVersion + 1;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE files SET size=?,checksum=?,version=?,modified_by=?,modified_at=NOW(),deleted=? WHERE id=?")) {
                    ps.setLong(1, size);
                    ps.setString(2, checksum);
                    ps.setLong(3, version);
                    ps.setLong(4, userId);
                    ps.setBoolean(5, deleted);
                    ps.setLong(6, id);
                    ps.executeUpdate();
                }
            }

            c.commit();
            return row(
                    "conflict", false,
                    "id", id,
                    "workspace_id", workspaceId,
                    "relative_path", path,
                    "size", size,
                    "checksum", checksum,
                    "version", version,
                    "deleted", deleted
            );
        } catch (SQLException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    /**
     * Applies a chunk-backed CREATE/MODIFY and records its version and chunk
     * mapping in the same transaction. The existing applyFileChange method is
     * intentionally retained for legacy payloads and DELETE events.
     */
    public Map<String, String> applyFileChangeChunked(long workspaceId, String path, long size,
                                                       String checksum, long userId, String sourceDevice,
                                                       long baseVersion, ChunkManifest manifest) throws SQLException {
        if (path == null || path.isBlank()) throw new IllegalArgumentException("File path is required");
        if (size < 0) throw new IllegalArgumentException("File size cannot be negative");
        if (checksum == null || !checksum.matches("[0-9a-fA-F]{64}")) {
            throw new IllegalArgumentException("File checksum must be a 64-character SHA-256 hash");
        }
        if (baseVersion < 0) throw new IllegalArgumentException("Base version cannot be negative");
        Objects.requireNonNull(manifest, "manifest");
        if (manifest.totalSize() != size) throw new IllegalArgumentException("Manifest size does not match file size");
        String manifestText = manifest.serialize();
        if (manifestText.getBytes(StandardCharsets.UTF_8).length > 16_777_215) {
            throw new IllegalArgumentException("Manifest exceeds the MEDIUMTEXT size limit");
        }

        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            Map<String, String> existing = queryOne(c,
                    "SELECT id,size,checksum,version,deleted FROM files WHERE workspace_id=? AND relative_path=? FOR UPDATE",
                    workspaceId, path);

            if (existing == null && baseVersion != 0) {
                c.rollback();
                return row("conflict", true, "current_version", 0, "current_checksum", "", "current_deleted", true);
            }
            if (existing != null) {
                long currentVersion = Long.parseLong(existing.get("version"));
                if (baseVersion != currentVersion) {
                    c.rollback();
                    return row(
                            "conflict", true,
                            "id", existing.get("id"),
                            "current_version", currentVersion,
                            "current_checksum", existing.getOrDefault("checksum", ""),
                            "current_deleted", existing.getOrDefault("deleted", "false"),
                            "current_size", existing.getOrDefault("size", "0")
                    );
                }
            }

            verifyManifestChunks(c, manifest);

            long fileId;
            long version;
            String operation;
            if (existing == null) {
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO files(workspace_id,relative_path,size,checksum,version,modified_by,modified_at,deleted) VALUES(?,?,?,?,1,?,NOW(),FALSE)",
                        Statement.RETURN_GENERATED_KEYS)) {
                    ps.setLong(1, workspaceId);
                    ps.setString(2, path);
                    ps.setLong(3, size);
                    ps.setString(4, checksum);
                    ps.setLong(5, userId);
                    ps.executeUpdate();
                    try (ResultSet rs = ps.getGeneratedKeys()) {
                        if (!rs.next()) throw new SQLException("Database did not return the new file id");
                        fileId = rs.getLong(1);
                    }
                }
                version = 1;
                operation = "CREATE";
            } else {
                fileId = Long.parseLong(existing.get("id"));
                long currentVersion = Long.parseLong(existing.get("version"));
                try {
                    version = Math.addExact(currentVersion, 1);
                } catch (ArithmeticException e) {
                    throw new SQLException("File version number overflow", e);
                }
                operation = "true".equalsIgnoreCase(existing.getOrDefault("deleted", "false"))
                        || "1".equals(existing.getOrDefault("deleted", "false")) ? "CREATE" : "MODIFY";
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE files SET size=?,checksum=?,version=?,modified_by=?,modified_at=NOW(),deleted=FALSE WHERE id=?")) {
                    ps.setLong(1, size);
                    ps.setString(2, checksum);
                    ps.setLong(3, version);
                    ps.setLong(4, userId);
                    ps.setLong(5, fileId);
                    ps.executeUpdate();
                }
            }

            insertFileVersion(c, fileId, version, baseVersion, checksum, manifestText, false,
                    userId, sourceDevice, operation, null);
            insertVersionChunks(c, fileId, version, manifest);
            c.commit();
            return row(
                    "conflict", false,
                    "id", fileId,
                    "workspace_id", workspaceId,
                    "relative_path", path,
                    "size", size,
                    "checksum", checksum,
                    "version", version,
                    "deleted", false
            );
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    /**
     * Creates a new ROLLBACK version from an existing chunk-backed snapshot.
     * The source version remains immutable and its chunk mappings are copied
     * into the new version atomically with the current files row.
     */
    public Map<String, String> copyFileVersion(long fileId, long sourceVersion, long baseVersion,
                                               long userId, String sourceDevice) throws SQLException {
        return copyFileVersionInternal(fileId, null, sourceVersion, baseVersion, userId,
                sourceDevice, null, false);
    }

    /**
     * Creates a new rollback version and its successful Activity row atomically.
     * The source version and all older versions remain unchanged. The supplied
     * checksum is the SHA-256 of the assembled source snapshot (empty for a
     * deleted snapshot).
     */
    public Map<String, String> rollbackFileVersion(long fileId, long workspaceId,
                                                   long sourceVersion, long baseVersion,
                                                   long userId, String sourceDevice,
                                                   String assembledChecksum) throws SQLException {
        if (workspaceId <= 0) throw new IllegalArgumentException("Workspace ID must be positive");
        if (userId <= 0) throw new IllegalArgumentException("User ID must be positive");
        return copyFileVersionInternal(fileId, workspaceId, sourceVersion, baseVersion, userId,
                sourceDevice, assembledChecksum, true);
    }

    private Map<String, String> copyFileVersionInternal(long fileId, Long expectedWorkspaceId,
                                                        long sourceVersion, long baseVersion,
                                                        long userId, String sourceDevice,
                                                        String assembledChecksum, boolean recordActivity)
            throws SQLException {
        if (fileId <= 0 || sourceVersion <= 0 || baseVersion < 0) {
            throw new IllegalArgumentException("Invalid file or version number");
        }

        Connection c = pool.borrow();
        try {
            c.setAutoCommit(false);
            Map<String, String> current = queryOne(c,
                    "SELECT id,workspace_id,relative_path,size,checksum,version,deleted FROM files WHERE id=? FOR UPDATE",
                    fileId);
            if (current == null) throw new SQLException("File does not exist: " + fileId);
            long workspaceId = Long.parseLong(current.get("workspace_id"));
            if (expectedWorkspaceId != null && workspaceId != expectedWorkspaceId) {
                throw new SQLException("File does not belong to workspace " + expectedWorkspaceId);
            }

            long currentVersion = Long.parseLong(current.get("version"));
            if (baseVersion != currentVersion) {
                c.rollback();
                return row(
                        "conflict", true,
                        "id", fileId,
                        "current_version", currentVersion,
                        "current_checksum", current.getOrDefault("checksum", ""),
                        "current_deleted", current.getOrDefault("deleted", "false"),
                        "current_size", current.getOrDefault("size", "0")
                );
            }

            Map<String, String> source = queryOne(c,
                    "SELECT checksum,manifest,is_deleted FROM file_versions WHERE file_id=? AND version=? FOR UPDATE",
                    fileId, sourceVersion);
            if (source == null) {
                throw new SQLException("Snapshot version " + sourceVersion + " does not belong to file " + fileId);
            }
            String checksum = source.getOrDefault("checksum", "");
            String manifestText = source.getOrDefault("manifest", "");
            ChunkManifest manifest;
            try {
                manifest = ChunkManifest.parse(manifestText);
            } catch (IllegalArgumentException e) {
                throw new SQLException("Snapshot manifest is invalid for version " + sourceVersion, e);
            }
            boolean deleted = "true".equalsIgnoreCase(source.getOrDefault("is_deleted", "false"))
                    || "1".equals(source.getOrDefault("is_deleted", "false"));
            if (deleted && manifest.totalSize() != 0) {
                throw new SQLException("Deleted snapshot has a non-empty manifest");
            }
            if (recordActivity && !deleted
                    && (assembledChecksum == null || !checksum.equalsIgnoreCase(assembledChecksum))) {
                throw new SQLException("Assembled file checksum does not match the source snapshot");
            }
            verifyManifestChunks(c, manifest);
            verifyVersionChunkMapping(c, fileId, sourceVersion, manifest);

            long newVersion;
            try {
                newVersion = Math.addExact(currentVersion, 1);
            } catch (ArithmeticException e) {
                throw new SQLException("File version number overflow", e);
            }

            try (PreparedStatement ps = c.prepareStatement(
                    "UPDATE files SET size=?,checksum=?,version=?,modified_by=?,modified_at=NOW(),deleted=? WHERE id=?")) {
                ps.setLong(1, manifest.totalSize());
                ps.setString(2, checksum);
                ps.setLong(3, newVersion);
                ps.setLong(4, userId);
                ps.setBoolean(5, deleted);
                ps.setLong(6, fileId);
                if (ps.executeUpdate() != 1) throw new SQLException("File metadata was not updated");
            }

            insertFileVersion(c, fileId, newVersion, currentVersion, checksum, manifestText, deleted,
                    userId, sourceDevice, "ROLLBACK", sourceVersion);
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO file_version_chunks(file_id,version,chunk_order,chunk_hash,chunk_size)
                    SELECT file_id,?,chunk_order,chunk_hash,chunk_size
                    FROM file_version_chunks WHERE file_id=? AND version=? ORDER BY chunk_order
                    """)) {
                ps.setLong(1, newVersion);
                ps.setLong(2, fileId);
                ps.setLong(3, sourceVersion);
                ps.executeUpdate();
            }
            verifyVersionChunkMapping(c, fileId, newVersion, manifest);

            if (recordActivity) {
                String detail = "Rollback from v" + currentVersion + " to v" + sourceVersion
                        + ", created v" + newVersion + " (" + current.get("relative_path") + ")";
                try (PreparedStatement ps = c.prepareStatement(
                        "INSERT INTO activities(user_id,workspace_id,action,detail) VALUES(?,?,?,?)")) {
                    ps.setLong(1, userId);
                    ps.setLong(2, workspaceId);
                    ps.setString(3, "ROLLBACK");
                    ps.setString(4, detail);
                    ps.executeUpdate();
                }
            }

            c.commit();
            if (!recordActivity) {
                return row(
                        "conflict", false,
                        "id", fileId,
                        "workspace_id", current.get("workspace_id"),
                        "relative_path", current.get("relative_path"),
                        "size", manifest.totalSize(),
                        "checksum", checksum,
                        "version", newVersion,
                        "deleted", deleted,
                        "rollback_from_version", sourceVersion
                );
            }
            return row(
                    "conflict", false,
                    "id", fileId,
                    "workspace_id", workspaceId,
                    "relative_path", current.get("relative_path"),
                    "base_version", currentVersion,
                    "version", newVersion,
                    "checksum", checksum,
                    "size", manifest.totalSize(),
                    "manifest", manifestText,
                    "deleted", deleted,
                    "operation", "ROLLBACK",
                    "rollback_from_version", sourceVersion
            );
        } catch (SQLException | RuntimeException e) {
            try { c.rollback(); } catch (SQLException ignored) { }
            throw e;
        } finally {
            try { c.setAutoCommit(true); } catch (SQLException ignored) { }
            pool.release(c);
        }
    }

    private void verifyManifestChunks(Connection c, ChunkManifest manifest) throws SQLException {
        Map<String, Integer> expectedSizes = new LinkedHashMap<>();
        for (ChunkManifest.Entry entry : manifest.chunks()) {
            Integer previous = expectedSizes.putIfAbsent(entry.hash(), entry.length());
            if (previous != null && previous != entry.length()) {
                throw new SQLException("Manifest assigns multiple sizes to chunk " + entry.hash());
            }
        }

        List<Map.Entry<String, Integer>> uniqueChunks = new ArrayList<>(expectedSizes.entrySet());
        final int batchSize = 500;
        for (int start = 0; start < uniqueChunks.size(); start += batchSize) {
            int end = Math.min(start + batchSize, uniqueChunks.size());
            String placeholders = String.join(",", Collections.nCopies(end - start, "?"));
            Map<String, Integer> foundSizes = new HashMap<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT hash,size FROM chunks WHERE hash IN (" + placeholders + ")")) {
                for (int i = start; i < end; i++) ps.setString(i - start + 1, uniqueChunks.get(i).getKey());
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) foundSizes.put(rs.getString("hash"), rs.getInt("size"));
                }
            }
            for (int i = start; i < end; i++) {
                Map.Entry<String, Integer> expected = uniqueChunks.get(i);
                Integer actual = foundSizes.get(expected.getKey());
                if (actual == null) throw new SQLException("Required chunk is missing: " + expected.getKey());
                if (!actual.equals(expected.getValue())) {
                    throw new SQLException("Stored chunk size mismatch: " + expected.getKey());
                }
            }
        }
    }

    private void verifyVersionChunkMapping(Connection c, long fileId, long version,
                                           ChunkManifest manifest) throws SQLException {
        int order = 0;
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT chunk_order,chunk_hash,chunk_size
                FROM file_version_chunks WHERE file_id=? AND version=? ORDER BY chunk_order ASC
                """)) {
            ps.setLong(1, fileId);
            ps.setLong(2, version);
            try (ResultSet rs = ps.executeQuery()) {
                for (ChunkManifest.Entry expected : manifest.chunks()) {
                    if (!rs.next()) throw new SQLException("Snapshot has an incomplete chunk mapping");
                    if (rs.getInt("chunk_order") != order
                            || !expected.hash().equals(rs.getString("chunk_hash"))
                            || expected.length() != rs.getInt("chunk_size")) {
                        throw new SQLException("Snapshot chunk mapping does not match its manifest");
                    }
                    order++;
                }
                if (rs.next()) throw new SQLException("Snapshot contains unexpected chunk mappings");
            }
        }
    }

    private void insertFileVersion(Connection c, long fileId, long version, long baseVersion,
                                   String checksum, String manifest, boolean deleted, long userId,
                                   String sourceDevice, String operation, Long rollbackFromVersion) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO file_versions(file_id,version,base_version,checksum,manifest,is_deleted,
                                          source_user_id,source_device,operation,rollback_from_version)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """)) {
            ps.setLong(1, fileId);
            ps.setLong(2, version);
            ps.setLong(3, baseVersion);
            ps.setString(4, checksum);
            ps.setString(5, manifest);
            ps.setBoolean(6, deleted);
            ps.setLong(7, userId);
            ps.setString(8, sourceDevice);
            ps.setString(9, operation);
            if (rollbackFromVersion == null) ps.setNull(10, Types.BIGINT);
            else ps.setLong(10, rollbackFromVersion);
            ps.executeUpdate();
        }
    }

    private void insertVersionChunks(Connection c, long fileId, long version,
                                     ChunkManifest manifest) throws SQLException {
        if (manifest.chunkCount() == 0) return;
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO file_version_chunks(file_id,version,chunk_order,chunk_hash,chunk_size)
                VALUES(?,?,?,?,?)
                """)) {
            int order = 0;
            int pending = 0;
            for (ChunkManifest.Entry chunk : manifest.chunks()) {
                ps.setLong(1, fileId);
                ps.setLong(2, version);
                ps.setInt(3, order++);
                ps.setString(4, chunk.hash());
                ps.setInt(5, chunk.length());
                ps.addBatch();
                if (++pending == 500) {
                    ps.executeBatch();
                    pending = 0;
                }
            }
            if (pending > 0) ps.executeBatch();
        }
    }

    public void markReplica(long fileId, long agentId, long version, String checksum) throws SQLException {
        update("""
            INSERT INTO file_replicas(file_id,agent_id,version,checksum,last_seen) VALUES(?,?,?,?,NOW())
            ON DUPLICATE KEY UPDATE version=VALUES(version),checksum=VALUES(checksum),last_seen=NOW()
            """, fileId, agentId, version, checksum);
    }

    public List<Long> replicaAgents(long fileId, long version) throws SQLException {
        List<Long> result = new ArrayList<>();
        for (Map<String, String> row : query("SELECT agent_id FROM file_replicas WHERE file_id=? AND version=? ORDER BY last_seen DESC", fileId, version)) {
            result.add(Long.parseLong(row.get("agent_id")));
        }
        return result;
    }

    public List<Map<String, String>> filesForUser(long userId, boolean admin) throws SQLException {
        if (admin) {
            return query("SELECT id,workspace_id,relative_path,size,checksum,version,deleted FROM files");
        }
        return query("""
            SELECT f.id,f.workspace_id,f.relative_path,f.size,f.checksum,f.version,f.deleted
            FROM files f JOIN workspace_members wm ON wm.workspace_id=f.workspace_id
            WHERE wm.user_id=? AND wm.can_read=1
            """, userId);
    }

    public void logActivity(Long userId, Long workspaceId, String action, String detail) throws SQLException {
        update("INSERT INTO activities(user_id,workspace_id,action,detail) VALUES(?,?,?,?)", userId, workspaceId, action, detail);
    }

    public List<Map<String, String>> listActivities(
        long userId,
        boolean admin,
        Long workspaceId,
        int limit
) throws SQLException {

    limit = Math.max(1, Math.min(limit, 500));

    if (workspaceId != null) {

        if (admin) {
            String sql = """
                SELECT a.id,
                       COALESCE(u.username,'SYSTEM') username,
                       COALESCE(w.name,'') workspace,
                       a.action,
                       a.detail,
                       a.created_at
                FROM activities a
                LEFT JOIN users u ON u.id=a.user_id
                LEFT JOIN workspaces w ON w.id=a.workspace_id
                WHERE a.workspace_id=?
                ORDER BY a.id DESC
                LIMIT %d
                """.formatted(limit);

            return query(sql, workspaceId);
        }

        String sql = """
            SELECT a.id,
                   COALESCE(u.username,'SYSTEM') username,
                   COALESCE(w.name,'') workspace,
                   a.action,
                   a.detail,
                   a.created_at
            FROM activities a
            LEFT JOIN users u ON u.id=a.user_id
            LEFT JOIN workspaces w ON w.id=a.workspace_id
            JOIN workspace_members wm
                 ON wm.workspace_id=a.workspace_id
                AND wm.user_id=?
            WHERE a.workspace_id=?
            ORDER BY a.id DESC
            LIMIT %d
            """.formatted(limit);

        return query(sql, userId, workspaceId);
    }

    if (admin) {
        String sql = """
            SELECT a.id,
                   COALESCE(u.username,'SYSTEM') username,
                   COALESCE(w.name,'') workspace,
                   a.action,
                   a.detail,
                   a.created_at
            FROM activities a
            LEFT JOIN users u ON u.id=a.user_id
            LEFT JOIN workspaces w ON w.id=a.workspace_id
            ORDER BY a.id DESC
            LIMIT %d
            """.formatted(limit);

        return query(sql);
    }

    String sql = """
        SELECT DISTINCT a.id,
                        COALESCE(u.username,'SYSTEM') username,
                        COALESCE(w.name,'') workspace,
                        a.action,
                        a.detail,
                        a.created_at
        FROM activities a
        LEFT JOIN users u ON u.id=a.user_id
        LEFT JOIN workspaces w ON w.id=a.workspace_id
        LEFT JOIN workspace_members wm
               ON wm.workspace_id=a.workspace_id
        WHERE a.user_id=? OR wm.user_id=?
        ORDER BY a.id DESC
        LIMIT %d
        """.formatted(limit);

    return query(sql, userId, userId);
}

    public Map<String, String> dashboard(long userId, boolean admin, int onlineCount) throws SQLException {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("online_agents", String.valueOf(onlineCount));
        if (admin) {
            m.put("users", scalar("SELECT COUNT(*) FROM users"));
            m.put("workspaces", scalar("SELECT COUNT(*) FROM workspaces"));
            m.put("files", scalar("SELECT COUNT(*) FROM files WHERE deleted=0"));
            m.put("offline_agents", scalar("SELECT COUNT(*) FROM agents WHERE status='OFFLINE'"));
        } else {
            m.put("workspaces", scalar("SELECT COUNT(*) FROM workspace_members WHERE user_id=?", userId));
            m.put("files", scalar("SELECT COUNT(*) FROM files f JOIN workspace_members wm ON wm.workspace_id=f.workspace_id WHERE wm.user_id=? AND wm.can_read=1 AND f.deleted=0", userId));
            m.put("activities", scalar("SELECT COUNT(*) FROM activities WHERE user_id=?", userId));
        }
        return m;
    }

    private String scalar(String sql, Object... params) throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() ? rs.getString(1) : "0"; }
        } finally { pool.release(c); }
    }

    private void update(String sql, Object... params) throws SQLException {
        Connection c = pool.borrow();
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params); ps.executeUpdate();
        } finally { pool.release(c); }
    }

    private List<Map<String, String>> query(String sql, Object... params) throws SQLException {
        Connection c = pool.borrow();
        try { return query(c, sql, params); } finally { pool.release(c); }
    }

    private Map<String, String> queryOne(String sql, Object... params) throws SQLException {
        Connection c = pool.borrow();
        try { return queryOne(c, sql, params); } finally { pool.release(c); }
    }

    private List<Map<String, String>> query(Connection c, String sql, Object... params) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            bind(ps, params);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, String>> out = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, String> row = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        String label = md.getColumnLabel(i);
                        Object value = rs.getObject(i);
                        row.put(label.toLowerCase(Locale.ROOT), value == null ? "" : String.valueOf(value));
                    }
                    out.add(row);
                }
                return out;
            }
        }
    }

    private Map<String, String> queryOne(Connection c, String sql, Object... params) throws SQLException {
        List<Map<String, String>> rows = query(c, sql, params);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private void bind(PreparedStatement ps, Object... params) throws SQLException {
        for (int i = 0; i < params.length; i++) {
            Object p = params[i];
            if (p == null) ps.setNull(i + 1, Types.NULL);
            else if (p instanceof Long v) ps.setLong(i + 1, v);
            else if (p instanceof Integer v) ps.setInt(i + 1, v);
            else if (p instanceof Boolean v) ps.setBoolean(i + 1, v);
            else ps.setString(i + 1, String.valueOf(p));
        }
    }

    private Map<String, String> row(Object... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(String.valueOf(kv[i]), String.valueOf(kv[i + 1]));
        return m;
    }

    @Override public void close() { pool.close(); }
}
