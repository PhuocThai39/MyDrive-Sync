package vn.edu.pbl4sync.server.db;

import vn.edu.pbl4sync.common.HashUtil;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

public class Database implements AutoCloseable {
    private final SimpleConnectionPool pool;

    public Database(String url, String user, String password, int poolSize, String initialAdminPassword) throws Exception {
        Class.forName("com.mysql.cj.jdbc.Driver");
        this.pool = new SimpleConnectionPool(url, user, password, poolSize);
        initializeSchema();
        markAllAgentsOffline();
        seedAdmin(initialAdminPassword);
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
