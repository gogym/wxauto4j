package io.getbit.wxdb;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.*;

/**
 * 微信数据库表结构导出工具
 * <p>
 * 从配置文件读取密钥，连接所有加密数据库，输出完整的 CREATE TABLE 语句。
 */
public class SchemaDump {

    public static void main(String[] args) throws Exception {
        // 加载配置
        Path configDir = Paths.get(System.getProperty("user.home"), ".wxauto4j");
        Path configFile = configDir.resolve("config.json");
        String json = Files.readString(configFile);

        // 简单解析 key 和 dataDir（避免引入 Gson 依赖）
        String rawKey = extractJsonValue(json, "wxRawKey");
        String dataDir = extractJsonValue(json, "wxDataDir");

        if (rawKey == null || rawKey.length() != 64) {
            System.err.println("密钥未配置或格式不正确，请先通过 GUI 提取密钥");
            System.exit(1);
        }

        System.out.println("========================================");
        System.out.println("微信数据库表结构导出");
        System.out.println("========================================");
        System.out.println("数据目录: " + dataDir);
        System.out.println("密钥: " + rawKey.substring(0, 8) + "...");
        System.out.println();

        WeChatDBConfig config = WeChatDBConfig.fromRawKey(rawKey).wechatDataDir(dataDir);

        Class.forName("org.sqlite.JDBC");

        String dbStorageDir = config.getDbStorageDir();
        System.out.println("db_storage 目录: " + dbStorageDir);
        System.out.println();

        // 遍历 db_storage 下所有子目录和 .db 文件
        File root = new File(dbStorageDir);
        if (!root.exists()) {
            System.err.println("db_storage 目录不存在: " + dbStorageDir);
            System.exit(1);
        }

        // 收集所有数据库文件
        File[] categories = root.listFiles(File::isDirectory);
        if (categories == null) {
            System.err.println("db_storage 目录为空");
            System.exit(1);
        }

        for (File categoryDir : categories) {
            File[] dbFiles = categoryDir.listFiles((dir, name) -> name.endsWith(".db"));
            if (dbFiles == null) continue;

            for (File dbFile : dbFiles) {
                String category = categoryDir.getName();
                String dbName = dbFile.getName();
                String jdbcUrl = config.getEncryptedDbJdbcUrl(category, dbName);

                System.out.println("========================================");
                System.out.println("数据库: " + category + "/" + dbName);
                System.out.println("文件: " + dbFile.getAbsolutePath());
                System.out.println("大小: " + (dbFile.length() / 1024) + " KB");
                System.out.println("----------------------------------------");

                try (Connection conn = DriverManager.getConnection(jdbcUrl)) {
                    // 查询所有表
                    try (Statement stmt = conn.createStatement();
                         ResultSet rs = stmt.executeQuery(
                                 "SELECT name, sql FROM sqlite_master WHERE type='table' ORDER BY name")) {
                        int tableCount = 0;
                        while (rs.next()) {
                            tableCount++;
                            String tableName = rs.getString("name");
                            String createSql = rs.getString("sql");
                            System.out.println();
                            System.out.println("【表 " + tableCount + "】" + tableName);
                            System.out.println(createSql);

                            // 查询索引
                            try (Statement idxStmt = conn.createStatement();
                                 ResultSet idxRs = idxStmt.executeQuery(
                                         "SELECT sql FROM sqlite_master WHERE type='index' AND tbl_name='" + tableName + "' AND sql IS NOT NULL")) {
                                while (idxRs.next()) {
                                    System.out.println("  索引: " + idxRs.getString(1));
                                }
                            }

                            // 查询行数
                            try (Statement cntStmt = conn.createStatement();
                                 ResultSet cntRs = cntStmt.executeQuery("SELECT count(*) FROM \"" + tableName + "\"")) {
                                if (cntRs.next()) {
                                    System.out.println("  行数: " + cntRs.getInt(1));
                                }
                            } catch (SQLException ignored) {}
                        }
                        if (tableCount == 0) {
                            System.out.println("  (无表)");
                        } else {
                            System.out.println();
                            System.out.println("共 " + tableCount + " 张表");
                        }
                    }
                } catch (SQLException e) {
                    System.err.println("  连接失败: " + e.getMessage());
                }
                System.out.println();
            }
        }
    }

    private static String extractJsonValue(String json, String key) {
        String searchKey = "\"" + key + "\"";
        int idx = json.indexOf(searchKey);
        if (idx < 0) return null;
        idx = json.indexOf(':', idx + searchKey.length());
        if (idx < 0) return null;
        idx++;
        // skip whitespace
        while (idx < json.length() && Character.isWhitespace(json.charAt(idx))) idx++;
        if (idx >= json.length()) return null;
        if (json.charAt(idx) == '"') {
            // string value
            int end = json.indexOf('"', idx + 1);
            if (end < 0) return null;
            return json.substring(idx + 1, end);
        } else {
            // non-string value
            int end = idx;
            while (end < json.length() && json.charAt(end) != ',' && json.charAt(end) != '}') end++;
            return json.substring(idx, end).trim();
        }
    }
}
