package io.getbit.wxdb.query;

import io.getbit.wxdb.model.Session;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * 会话查询
 */
public class SessionQuery {

    private final String jdbcUrl;
    private final String derivedKey;

    public SessionQuery(String jdbcUrl, String derivedKey) {
        this.jdbcUrl = jdbcUrl;
        this.derivedKey = derivedKey;
    }

    /**
     * 查询所有会话，按时间倒序
     */
    public List<Session> getAll() {
        return query("SELECT * FROM SessionTable ORDER BY last_timestamp DESC", new Object[]{});
    }

    /**
     * 查询最近 N 条会话
     */
    public List<Session> getRecent(int limit) {
        return query("SELECT * FROM SessionTable ORDER BY last_timestamp DESC LIMIT ?", new Object[]{limit});
    }

    /**
     * 按 username 查询会话
     */
    public Session getByUsername(String username) {
        List<Session> results = query(
                "SELECT * FROM SessionTable WHERE username = ? LIMIT 1",
                new Object[]{username});
        return results.isEmpty() ? null : results.get(0);
    }

    /**
     * 查询有未读消息的会话
     */
    public List<Session> getUnread() {
        return query("SELECT * FROM SessionTable WHERE unread_count > 0 ORDER BY last_timestamp DESC",
                new Object[]{});
    }

    /**
     * 搜索会话（按 username 模糊匹配）
     */
    public List<Session> search(String keyword) {
        return query(
                "SELECT * FROM SessionTable WHERE username LIKE ? ORDER BY last_timestamp DESC",
                new Object[]{"%" + keyword + "%"});
    }

    private Connection openConnection() throws SQLException {
        return DriverManager.getConnection(jdbcUrl);
    }

    private List<Session> query(String sql, Object[] params) {
        List<Session> sessions = new ArrayList<>();
        try (Connection conn = openConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            for (int i = 0; i < params.length; i++) {
                ps.setObject(i + 1, params[i]);
            }
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    sessions.add(mapSession(rs));
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Session query failed: " + e.getMessage(), e);
        }
        return sessions;
    }

    private Session mapSession(ResultSet rs) throws SQLException {
        Session s = new Session();
        s.setUsername(rs.getString("username"));
        s.setType(rs.getInt("type"));
        s.setUnreadCount(rs.getInt("unread_count"));
        s.setSummary(rs.getString("summary"));
        s.setDraft(rs.getString("draft"));
        s.setLastTimestamp(rs.getLong("last_timestamp"));
        s.setSortTimestamp(rs.getLong("sort_timestamp"));
        return s;
    }
}
