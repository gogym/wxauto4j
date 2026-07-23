package io.getbit.wxdb.query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * SQLite 连接管理器
 * <p>
 * 统一管理连接的创建、PRAGMA 优化配置、有效性检查和自动重连。
 * 所有 Query 类共享此管理器，避免重复的连接管理代码。
 * <p>
 * 提供轮询连接池：预创建多个连接，轮询时从池中获取，用完后关闭并异步补充新连接。
 * 连接不复用，因为 SQLite 每个连接维护独立的 page cache，WAL 模式下
 * 其他进程（微信）写入的数据不会更新已缓存的 change counter，导致复用连接看不到新数据。
 */
public class DbConnectionHelper {

    private static final Logger LOG = Logger.getLogger(DbConnectionHelper.class.getName());

    /** 轮询连接池大小 */
    private static final int POOL_SIZE = 3;

    private final String jdbcUrl;
    private volatile Connection connection;

    /** 轮询连接池：预创建连接，用完关闭并异步补充 */
    private final LinkedBlockingDeque<Connection> pollPool = new LinkedBlockingDeque<>();
    private volatile boolean poolInitialized = false;

    public DbConnectionHelper(String jdbcUrl) {
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * 获取共享连接（用于非轮询场景）
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
     * 从轮询连接池获取一个连接。
     * 池中的连接都是预先创建好的，获取操作是即时的。
     * 如果池为空（极端情况），会同步创建一个新连接。
     */
    public Connection acquirePollConnection() throws SQLException {
        ensurePoolInitialized();
        Connection conn = pollPool.poll();
        if (conn != null && !conn.isClosed()) {
            return conn;
        }
        // 池空或连接已关闭，同步创建
        return createOptimizedConnection();
    }

    /**
     * 释放轮询连接：关闭连接，异步补充新连接到池中。
     * 连接不能复用，因为 SQLite 每个连接维护独立的 page cache，
     * WAL 模式下其他进程写入不会更新已缓存的 change counter。
     */
    public void releasePollConnection(Connection conn) {
        if (conn != null) {
            try { conn.close(); } catch (SQLException ignored) {}
        }
        // 异步补充一个新连接到池中
        if (pollPool.size() < POOL_SIZE) {
            Thread filler = new Thread(() -> {
                try {
                    pollPool.offer(createOptimizedConnection());
                } catch (SQLException e) {
                    LOG.log(Level.WARNING, "轮询连接池补充失败", e);
                }
            }, "pool-filler");
            filler.setDaemon(true);
            filler.start();
        }
    }

    private void ensurePoolInitialized() {
        if (!poolInitialized) {
            synchronized (this) {
                if (!poolInitialized) {
                    for (int i = 0; i < POOL_SIZE; i++) {
                        try {
                            pollPool.offer(createOptimizedConnection());
                        } catch (SQLException e) {
                            LOG.log(Level.WARNING, "轮询连接池初始化失败", e);
                        }
                    }
                    poolInitialized = true;
                }
            }
        }
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
            conn.createStatement().execute("PRAGMA busy_timeout = 5000");
            conn.createStatement().execute("PRAGMA cache_size = -2000");
        } catch (SQLException e) {
            LOG.log(Level.WARNING, "Failed to apply PRAGMA optimizations", e);
        }
    }

    /**
     * 关闭所有连接（共享连接 + 轮询池）
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
        // 关闭轮询池中的所有连接
        Connection poolConn;
        while ((poolConn = pollPool.poll()) != null) {
            try { poolConn.close(); } catch (SQLException ignored) {}
        }
        poolInitialized = false;
    }
}
