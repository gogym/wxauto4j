package io.getbit.wxdb.query;

import io.getbit.wxdb.model.ChatMessage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.*;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

/**
 * 消息查询
 * <p>
 * 微信消息分布在多个 message_N.db 中，每个聊天对应一个 Msg_&lt;md5(username)&gt; 表。
 * 通过计算 username 的 MD5 哈希来定位消息表。
 * <p>
 * 使用 sqlite-jdbc-crypt 驱动直接连接加密数据库，无需解密。
 */
public class MessageQuery {

    private static final Logger LOG = Logger.getLogger(MessageQuery.class.getName());

    private final List<String> jdbcUrls;
    private final List<String> derivedKeys;
    private final Map<String, DbConnectionHelper> connHelpers = new HashMap<>();
    private final Map<String, TableRef> tableCache = new ConcurrentHashMap<>();

    public MessageQuery(List<String> jdbcUrls, List<String> derivedKeys) {
        this.jdbcUrls = jdbcUrls;
        this.derivedKeys = derivedKeys;
        // 为每个 DB 创建连接管理器
        for (int i = 0; i < jdbcUrls.size(); i++) {
            connHelpers.put(jdbcUrls.get(i), new DbConnectionHelper(jdbcUrls.get(i)));
        }
    }

    /**
     * 查询指定用户的最近 N 条消息
     */
    public List<ChatMessage> getRecentMessages(String username, int limit) {
        TableRef ref = findTable(username);
        if (ref == null) return List.of();

        String sql = "SELECT * FROM " + ref.tableName +
                " ORDER BY create_time DESC LIMIT ?";
        return queryMessages(ref, sql, new Object[]{limit});
    }

    /**
     * 查询指定用户在时间范围内的消息
     *
     * @param startTime 开始时间（unix timestamp，秒）
     * @param endTime   结束时间（unix timestamp，秒）
     */
    public List<ChatMessage> getMessages(String username, long startTime, long endTime) {
        TableRef ref = findTable(username);
        if (ref == null) return List.of();

        String sql = "SELECT * FROM " + ref.tableName +
                " WHERE create_time >= ? AND create_time <= ? ORDER BY create_time ASC";
        return queryMessages(ref, sql, new Object[]{startTime, endTime});
    }

    /**
     * 查询指定用户的所有消息
     */
    public List<ChatMessage> getAllMessages(String username) {
        TableRef ref = findTable(username);
        if (ref == null) return List.of();

        String sql = "SELECT * FROM " + ref.tableName + " ORDER BY create_time ASC";
        return queryMessages(ref, sql, new Object[]{});
    }

    /**
     * 搜索消息内容（文本匹配）
     */
    public List<ChatMessage> searchMessages(String username, String keyword) {
        TableRef ref = findTable(username);
        if (ref == null) return List.of();

        String sql = "SELECT * FROM " + ref.tableName +
                " WHERE message_content LIKE ? ORDER BY create_time DESC";
        return queryMessages(ref, sql, new Object[]{"%" + keyword + "%"});
    }

    /**
     * 在所有消息数据库中搜索包含关键词的消息
     */
    public List<ChatMessage> searchAllMessages(String keyword) {
        List<ChatMessage> results = new ArrayList<>();
        for (int i = 0; i < jdbcUrls.size(); i++) {
            List<String> tables = listMsgTables(jdbcUrls.get(i));
            for (String table : tables) {
                String sql = "SELECT * FROM " + table +
                        " WHERE message_content LIKE ? ORDER BY create_time DESC LIMIT 50";
                results.addAll(queryMessages(getHelper(jdbcUrls.get(i)), sql, new Object[]{"%" + keyword + "%"}));
            }
        }
        results.sort((a, b) -> Long.compare(b.getCreateTime(), a.getCreateTime()));
        return results;
    }

    /**
     * 查询指定用户从某个 local_id 之后的新消息
     *
     * @param username    用户 username
     * @param afterLocalId 此 local_id 之后的消息
     */
    public List<ChatMessage> getMessagesNewerThan(String username, long afterLocalId) {
        TableRef ref = findTable(username);
        if (ref == null) return List.of();

        String sql = "SELECT * FROM " + ref.tableName +
                " WHERE local_id > ? ORDER BY local_id ASC";

        // 从轮询连接池获取预创建的连接，避免每次轮询都创建新连接。
        // 独立连接确保能看到微信进程写入的新数据（共享连接因 WAL -shm mmap 限制不行）。
        List<ChatMessage> messages = new ArrayList<>();
        DbConnectionHelper helper = getHelper(ref.jdbcUrl);
        Connection conn = null;
        try {
            conn = helper.acquirePollConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setLong(1, afterLocalId);
                ps.setQueryTimeout(5);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(mapMessage(rs));
                    }
                }
            }
        } catch (SQLException e) {
            System.err.println("[轮询] DB查询异常: " + e.getMessage());
        } finally {
            helper.releasePollConnection(conn);
        }
        return messages;
    }

    /**
     * 获取指定用户消息中的最大 local_id（用于增量轮询）
     */
    public long getMaxLocalId(String username) {
        TableRef ref = findTable(username);
        if (ref == null) return 0;

        try {
            Connection conn = getHelper(ref.jdbcUrl).getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                         "SELECT MAX(local_id) FROM " + ref.tableName)) {
                return rs.next() ? rs.getLong(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    /**
     * 在消息 DB 的 Name2Id 表中，根据 rowid 反查 username
     */
    public String resolveSenderUsername(int senderRowId) {
        for (int i = 0; i < jdbcUrls.size(); i++) {
            try {
                Connection conn = getHelper(jdbcUrls.get(i)).getConnection();
                try (PreparedStatement ps = conn.prepareStatement(
                        "SELECT user_name FROM Name2Id WHERE rowid = ?")) {
                    ps.setLong(1, senderRowId);
                    try (ResultSet rs = ps.executeQuery()) {
                        if (rs.next()) {
                            return rs.getString(1);
                        }
                    }
                }
            } catch (SQLException e) {
                // try next db
            }
        }
        return "unknown_" + senderRowId;
    }

    /**
     * 获取指定用户的消息总数
     */
    public int countMessages(String username) {
        TableRef ref = findTable(username);
        if (ref == null) return 0;

        try {
            Connection conn = getHelper(ref.jdbcUrl).getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT count(*) FROM " + ref.tableName)) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        } catch (SQLException e) {
            return 0;
        }
    }

    // ========== 内部方法 ==========

    private DbConnectionHelper getHelper(String jdbcUrl) {
        return connHelpers.get(jdbcUrl);
    }

    public void close() {
        for (DbConnectionHelper helper : connHelpers.values()) {
            helper.close();
        }
        connHelpers.clear();
        tableCache.clear();
    }

    /**
     * 查找 username 对应的消息表（带缓存）
     * <p>
     * 表名 = Msg_ + MD5(username)，需要在所有 message DB 中查找。
     * 结果缓存到 tableCache，避免每次都查 sqlite_master。
     */
    private TableRef findTable(String username) {
        return tableCache.computeIfAbsent(username, u -> {
            String tableName = "Msg_" + md5(u);
            for (int i = 0; i < jdbcUrls.size(); i++) {
                if (tableExists(jdbcUrls.get(i), tableName)) {
                    return new TableRef(jdbcUrls.get(i), tableName);
                }
            }
            return null;
        });
    }

    /**
     * 检查数据库中是否存在指定表
     */
    private boolean tableExists(String jdbcUrl, String tableName) {
        try {
            Connection conn = getHelper(jdbcUrl).getConnection();
            try (PreparedStatement ps = conn.prepareStatement(
                     "SELECT count(*) FROM sqlite_master WHERE type='table' AND name=?")) {
                ps.setString(1, tableName);
                try (ResultSet rs = ps.executeQuery()) {
                    return rs.next() && rs.getInt(1) > 0;
                }
            }
        } catch (SQLException e) {
            return false;
        }
    }

    /**
     * 列出数据库中所有 Msg_ 开头的表
     */
    private List<String> listMsgTables(String jdbcUrl) {
        List<String> tables = new ArrayList<>();
        try {
            Connection conn = getHelper(jdbcUrl).getConnection();
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(
                     "SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'Msg_%'")) {
                while (rs.next()) {
                    tables.add(rs.getString("name"));
                }
            }
        } catch (SQLException e) {
            // ignore
        }
        return tables;
    }

    private List<ChatMessage> queryMessages(TableRef ref, String sql, Object[] params) {
        return queryMessages(getHelper(ref.jdbcUrl), sql, params);
    }

    private List<ChatMessage> queryMessages(DbConnectionHelper helper, String sql, Object[] params) {
        List<ChatMessage> messages = new ArrayList<>();
        try {
            Connection conn = helper.getConnection();
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (int i = 0; i < params.length; i++) {
                    Object p = params[i];
                    if (p instanceof Long) {
                        ps.setLong(i + 1, (Long) p);
                    } else if (p instanceof Integer) {
                        ps.setInt(i + 1, (Integer) p);
                    } else if (p instanceof String) {
                        ps.setString(i + 1, (String) p);
                    } else {
                        ps.setObject(i + 1, p);
                    }
                }
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        messages.add(mapMessage(rs));
                    }
                }
            }
        } catch (SQLException e) {
            throw new RuntimeException("Message query failed: " + e.getMessage(), e);
        }
        return messages;
    }

    private ChatMessage mapMessage(ResultSet rs) throws SQLException {
        ChatMessage m = new ChatMessage();
        m.setLocalId(rs.getLong("local_id"));
        m.setServerId(rs.getLong("server_id"));
        m.setLocalType(rs.getInt("local_type"));
        m.setSortSeq(rs.getLong("sort_seq"));
        m.setRealSenderId(rs.getLong("real_sender_id"));
        m.setSenderId(rs.getString("real_sender_id"));
        m.setCreateTime(rs.getLong("create_time"));
        m.setStatus(rs.getInt("status"));
        m.setUploadStatus(rs.getInt("upload_status"));
        m.setDownloadStatus(rs.getInt("download_status"));
        m.setServerSeq(rs.getLong("server_seq"));
        m.setOriginSource(rs.getInt("origin_source"));
        m.setMessageContent(rs.getString("message_content"));
        m.setCompressContent(rs.getString("compress_content"));
        m.setSource(rs.getString("source"));
        return m;
    }

    /**
     * 计算 MD5 哈希
     */
    private static String md5(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("MD5 not available", e);
        }
    }

    private record TableRef(String jdbcUrl, String tableName) {}
}
