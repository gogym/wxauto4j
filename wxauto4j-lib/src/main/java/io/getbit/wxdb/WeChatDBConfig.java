package io.getbit.wxdb;

import io.getbit.wxdb.crypto.KeyDerivation;
import io.getbit.wxdb.frida.FridaKeyExtractor;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * 微信数据库配置
 * <p>
 * 所有路径均通过外部传入，不硬编码任何默认值。
 */
public class WeChatDBConfig {

    private String rawKey;
    private String wechatDataDir;
    private String wxId;
    private String sqlcipherPath;
    private String decryptedDbDir;

    private WeChatDBConfig() {
    }

    /**
     * 使用 raw key 创建配置，所有路径需通过 setter 设置。
     *
     * @param rawKeyHex 64 位 hex 格式的 raw key
     */
    public static WeChatDBConfig fromRawKey(String rawKeyHex) {
        WeChatDBConfig config = new WeChatDBConfig();
        config.rawKey = rawKeyHex;
        config.sqlcipherPath = findSqlcipher();
        return config;
    }

    /**
     * 从 key 文件加载密钥
     *
     * @param keyFilePath key 文件路径（文件内容为 64 位 hex 字符串）
     */
    public static WeChatDBConfig fromKeyFile(String keyFilePath) {
        try {
            String key = Files.readString(Path.of(keyFilePath)).trim();
            return fromRawKey(key);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read key file: " + keyFilePath, e);
        }
    }

    /**
     * 通过 Frida 附加到正在运行的微信进程，自动提取 raw key。
     *
     * @param timeoutSeconds 等待 PBKDF 调用的超时秒数
     */
    public static WeChatDBConfig fromFrida(int timeoutSeconds) {
        FridaKeyExtractor extractor = new FridaKeyExtractor();
        extractor.setTimeout(timeoutSeconds);
        String key = extractor.extractKey();
        if (key == null) {
            throw new RuntimeException("Failed to extract key via Frida. " +
                    "Make sure WeChat is running and frida CLI is installed.");
        }
        return fromRawKey(key);
    }

    /**
     * 通过 Frida 提取 key（默认 30 秒超时）
     */
    public static WeChatDBConfig fromFrida() {
        return fromFrida(30);
    }

    // ==================== 路径设置 ====================

    /**
     * 设置微信数据目录
     *
     * @param dir 例如 ~/Library/Containers/com.tencent.xinWeChat/Data/Documents/xwechat_files
     */
    public WeChatDBConfig wechatDataDir(String dir) {
        this.wechatDataDir = dir;
        this.wxId = autoDetectWxId(dir);
        return this;
    }

    /**
     * 设置微信用户 ID（db_storage 下的第一层目录名）
     */
    public WeChatDBConfig wxId(String wxId) {
        this.wxId = wxId;
        return this;
    }

    /**
     * 设置 sqlcipher 可执行文件路径
     */
    public WeChatDBConfig sqlcipherPath(String path) {
        this.sqlcipherPath = path;
        return this;
    }

    /**
     * 设置解密后数据库输出目录
     */
    public WeChatDBConfig decryptedDbDir(String dir) {
        this.decryptedDbDir = dir;
        return this;
    }

    // ==================== 自动检测 ====================

    /**
     * 自动检测微信用户 ID（db_storage 下的第一层目录名）
     */
    private static String autoDetectWxId(String dataDir) {
        if (dataDir == null) return null;
        File dir = new File(dataDir);
        if (!dir.exists()) return null;
        File[] children = dir.listFiles(File::isDirectory);
        if (children == null || children.length == 0) return null;
        for (File child : children) {
            if (new File(child, "db_storage").exists()) {
                return child.getName();
            }
        }
        return children[0].getName();
    }

    /**
     * 在系统 PATH 中查找 sqlcipher
     */
    private static String findSqlcipher() {
        List<String> candidates = Arrays.asList(
                "/usr/local/bin/sqlcipher",
                "/opt/homebrew/bin/sqlcipher",
                "/usr/bin/sqlcipher"
        );
        for (String path : candidates) {
            if (new File(path).exists()) return path;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                File f = new File(dir, "sqlcipher");
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return "sqlcipher";
    }

    // ==================== Getters & Setters ====================

    public String getRawKey() {
        return rawKey;
    }

    public byte[] getRawKeyBytes() {
        return hexToBytes(rawKey);
    }

    public String getWechatDataDir() {
        return wechatDataDir;
    }

    public void setWechatDataDir(String dir) {
        this.wechatDataDir = dir;
    }

    public String getWxId() {
        return wxId;
    }

    public void setWxId(String wxId) {
        this.wxId = wxId;
    }

    public String getSqlcipherPath() {
        return sqlcipherPath;
    }

    public void setSqlcipherPath(String path) {
        this.sqlcipherPath = path;
    }

    public String getDecryptedDbDir() {
        return decryptedDbDir;
    }

    public void setDecryptedDbDir(String dir) {
        this.decryptedDbDir = dir;
    }

    /**
     * 获取 db_storage 根目录
     */
    public String getDbStorageDir() {
        return wechatDataDir + "/" + wxId + "/db_storage";
    }

    /**
     * 获取指定数据库的加密文件路径
     */
    public String getEncryptedDbPath(String category, String dbName) {
        return getDbStorageDir() + "/" + category + "/" + dbName;
    }

    /**
     * 获取指定加密数据库的完整 JDBC URL（含密钥参数）
     * <p>
     * 返回格式: jdbc:sqlite:/path/to/db?cipher=sqlcipher&legacy=4&key=x'hexkey'
     * <p>
     * 密钥通过 URL 参数传递，确保驱动在打开文件前就获得密钥。
     */
    public String getEncryptedDbJdbcUrl(String category, String dbName) {
        String encPath = getEncryptedDbPath(category, dbName);
        String derivedKey = KeyDerivation.deriveKey(rawKey, encPath);
        return "jdbc:sqlite:" + encPath + "?cipher=sqlcipher&legacy=4&key=x'" + derivedKey + "'";
    }

    /**
     * 获取指定数据库的派生密钥（hex 格式）
     * <p>
     * 每个数据库文件有独立的 salt，因此派生出不同的密钥。
     *
     * @param category 数据库分类
     * @param dbName   数据库文件名
     * @return 派生密钥的 hex 字符串
     */
    public String getDerivedKey(String category, String dbName) {
        String encPath = getEncryptedDbPath(category, dbName);
        return KeyDerivation.deriveKey(rawKey, encPath);
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return data;
    }
}
