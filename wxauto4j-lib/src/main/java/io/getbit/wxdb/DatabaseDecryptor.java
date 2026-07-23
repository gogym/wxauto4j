package io.getbit.wxdb;

import io.getbit.wxdb.crypto.KeyDerivation;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.TimeUnit;

/**
 * 数据库解密工具
 * <p>
 * 使用 sqlcipher 命令行工具将加密数据库导出为纯 SQL，再导入为普通 SQLite 数据库。
 */
public class DatabaseDecryptor {

    private final WeChatDBConfig config;

    public DatabaseDecryptor(WeChatDBConfig config) {
        this.config = config;
    }

    /**
     * 解密指定数据库并返回纯 SQLite 文件路径
     *
     * @param category 数据库分类（session, contact, message 等）
     * @param dbName   数据库文件名（如 session.db）
     * @return 解密后的纯 SQLite 数据库路径
     */
    public String decrypt(String category, String dbName) {
        String encPath = config.getEncryptedDbPath(category, dbName);
        String plainName = dbName.replace(".db", "_plain.db");
        String plainPath = config.getDecryptedDbDir() + "/" + plainName;

        // 如果已解密且文件存在，直接返回
        File plainFile = new File(plainPath);
        if (plainFile.exists() && plainFile.length() > 1000) {
            return plainPath;
        }

        try {
            // 确保输出目录存在
            Files.createDirectories(Path.of(config.getDecryptedDbDir()));

            // 派生密钥
            String derivedKey = KeyDerivation.deriveKey(config.getRawKey(), encPath);
            System.out.println("[decrypt] " + category + "/" + dbName + " derivedKey=" + derivedKey.substring(0, 16) + "...");

            // 复制加密 DB 到临时目录（避免 WAL 文件问题）
            Path tmpDb = Files.createTempFile("wx_enc_", ".db");
            Files.copy(Path.of(encPath), tmpDb, StandardCopyOption.REPLACE_EXISTING);

            // 复制 WAL 和 SHM（如果存在）
            for (String ext : new String[]{"-wal", "-shm"}) {
                Path src = Path.of(encPath + ext);
                if (Files.exists(src)) {
                    Files.copy(src, Path.of(tmpDb + ext), StandardCopyOption.REPLACE_EXISTING);
                }
            }

            // 删除旧的输出文件
            if (plainFile.exists()) {
                plainFile.delete();
            }

            // 使用 sqlcipher_export 直接导出为纯 SQLite
            // 这比 .dump + sqlite3 导入更可靠
            String exportSql = String.format(
                    "PRAGMA key = \"x'%s'\";\n" +
                    "PRAGMA kdf_iter = 1;\n" +
                    "PRAGMA cipher_compatibility = 4;\n" +
                    "PRAGMA cipher_page_size = 4096;\n" +
                    "ATTACH DATABASE '%s' AS plaintext KEY '';\n" +
                    "SELECT sqlcipher_export('plaintext');\n" +
                    "DETACH DATABASE plaintext;\n",
                    derivedKey, plainPath.replace("'", "''"));

            String[] result = executeSqlcipher(tmpDb.toString(), exportSql);
            String stdout = result[0];
            String stderr = result[1];

            System.out.println("[decrypt] stdout: " + stdout.substring(0, Math.min(200, stdout.length())));
            if (!stderr.isBlank()) {
                System.out.println("[decrypt] stderr: " + stderr.substring(0, Math.min(500, stderr.length())));
            }

            // 清理临时文件
            Files.deleteIfExists(tmpDb);
            Files.deleteIfExists(Path.of(tmpDb + "-wal"));
            Files.deleteIfExists(Path.of(tmpDb + "-shm"));

            if (!plainFile.exists() || plainFile.length() < 100) {
                throw new RuntimeException("Decryption produced empty file: " + plainPath
                        + ". sqlcipher stderr: " + stderr);
            }

            return plainPath;
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt " + encPath + ": " + e.getMessage(), e);
        }
    }

    /**
     * 强制重新解密指定数据库（跳过缓存检查，始终重新解密）
     *
     * @param category 数据库分类
     * @param dbName   数据库文件名
     * @return 解密后的纯 SQLite 数据库路径
     */
    public String forceReDecrypt(String category, String dbName) {
        String plainName = dbName.replace(".db", "_plain.db");
        String plainPath = config.getDecryptedDbDir() + "/" + plainName;
        // 删除旧的解密文件，强制重新解密
        File plainFile = new File(plainPath);
        if (plainFile.exists()) {
            plainFile.delete();
        }
        return decrypt(category, dbName);
    }

    /**
     * 批量解密所有核心数据库
     */
    public void decryptAll() {
        // Session
        decrypt("session", "session.db");

        // Contact
        decrypt("contact", "contact.db");

        // Message DBs
        for (int i = 0; i < 10; i++) {
            String dbPath = config.getEncryptedDbPath("message", "message_" + i + ".db");
            if (new File(dbPath).exists()) {
                decrypt("message", "message_" + i + ".db");
            }
        }
    }

    /**
     * 执行 sqlcipher 命令，返回 [stdout, stderr]
     */
    private String[] executeSqlcipher(String dbPath, String sql) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(config.getSqlcipherPath(), dbPath);
        pb.redirectErrorStream(false);
        Process process = pb.start();

        // 写入 SQL 并关闭 stdin
        try (OutputStream os = process.getOutputStream()) {
            os.write(sql.getBytes());
            os.flush();
        }

        // 用线程读取 stderr（避免缓冲区满导致死锁）
        StringBuilder stderrBuf = new StringBuilder();
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    stderrBuf.append(line).append("\n");
                }
            } catch (IOException e) {
                stderrBuf.append("Error reading stderr: ").append(e.getMessage());
            }
        }, "sqlcipher-stderr");
        stderrThread.setDaemon(true);
        stderrThread.start();

        // 读取 stdout
        String stdout;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line).append("\n");
            }
            stdout = sb.toString();
        }

        boolean finished = process.waitFor(120, TimeUnit.SECONDS);
        stderrThread.join(5000);

        if (!finished) {
            process.destroyForcibly();
            throw new RuntimeException("sqlcipher timed out. stderr: " + stderrBuf);
        }

        int exitCode = process.exitValue();
        if (exitCode != 0) {
            throw new RuntimeException("sqlcipher exited with code " + exitCode + ". stderr: " + stderrBuf);
        }

        return new String[]{stdout, stderrBuf.toString()};
    }


}
