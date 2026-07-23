package io.getbit.wxdb.query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 连接管理器
 * <p>
 * 统一管理连接的创建、PRAGMA 优化配置、有效性检查和自动重连。
 * 所有 Query 类共享此管理器，避免重复的连接管理代码。
 */
public class DbConnectionHelper {

    private static final Logger LOG = Logger.getLogger(DbConnectionHelper.class.getName());

    private final String jdbcUrl;
    private volatile Connection connection;

    public DbConnectionHelper(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * 获取连接（线程安全）
     * <p>
     * 如果连接不存在或已关闭，自动创建新连接并应用 PRAGMA 优化。
     * 保持 auto-commit 开启，确保每次查询都能看到最新数据。
     */
    public synchronized Connection getConnection() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            if (!connection.getAutoCommit()) {
                connection.setAutoCommit(true);
            }
            return connection;
        }
        connection = createOptimizedConnection();
        return connection;
    }

    /**
     * 创建带 PRAGMA 优化的新连接
     */
    private Connection createOptimizedConnection() throws SQLException {
        Connection conn = DriverManager.getConnection(jdbcUrl);
        conn.setAutoCommit(true);
        applyPragmas(conn);
        return conn;
    }

    /**
     * 应用 SQLite 性能优化 PRAGMA
     */
    private void applyPragmas(Connection conn) {
        try {
            // 设置忙等待超时 5 秒，避免 SQLITE_BUSY 错误
            conn.createStatement().execute("PRAGMA busy_timeout = 5000");
            // 设置页缓存 2MB（负数单位为 KB），减少磁盘 IO
            conn.createStatement().execute("PRAGMA cache_size = -2000");
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Failed to apply PRAGMA optimizations", e);
        }
    }

    /**
     * 重建连接：关闭旧连接，创建新连接。
     * 用于多进程场景（微信进程写入，本进程读取），新连接会重新读取 WAL 索引，
     * 从而看到其他进程写入的新数据。
     */
    public synchronized void reconnect() throws SQLException {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
            connection = null;
        }
        connection = createOptimizedConnection();
    }

    /**
     * 关闭连接
     */
    public synchronized void close() {
        if (connection != null) {
            try {
                if (!connection.isClosed()) {
                    connection.close();
                }
            } catch (SQLException ignored) {
            }
            connection = null;
        }
    }
}
