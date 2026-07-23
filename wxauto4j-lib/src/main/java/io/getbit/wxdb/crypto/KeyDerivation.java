package io.getbit.wxdb.crypto;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HexFormat;

/**
 * 密钥派生工具
 * <p>
 * 微信 4.x macOS 使用 PBKDF2-HMAC-SHA512 从 raw key 派生每个数据库的 SQLCipher 密钥。
 * 公式: PBKDF2-HMAC-SHA512(rawKey, dbSalt[0:16], 256000) -> 32 bytes
 * <p>
 * 注意：不使用 Java 内置的 PBEKeySpec/SecretKeyFactory，因为它们会将 char[] 通过
 * UTF-8 编码转为 byte[]，导致 >=128 的字节产生多字节序列，与 Python hashlib.pbkdf2_hmac
 * 直接使用原始字节的结果不一致。这里手动实现 PBKDF2 以直接传递原始字节。
 */
public class KeyDerivation {

    private static final int ITERATIONS = 256000;
    private static final int KEY_LENGTH = 32; // 32 bytes
    private static final int HMAC_LENGTH = 64; // SHA-512 output = 64 bytes

    /**
     * 从数据库文件读取 salt 并派生密钥
     *
     * @param rawKeyHex raw key 的 hex 字符串（64字符）
     * @param dbPath    加密数据库文件路径
     * @return 派生密钥的 hex 字符串
     */
    public static String deriveKey(String rawKeyHex, String dbPath) {
        try {
            byte[] salt = readSalt(dbPath);
            return deriveKey(rawKeyHex, salt);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read salt from: " + dbPath, e);
        }
    }

    /**
     * 使用指定 salt 派生密钥
     */
    public static String deriveKey(String rawKeyHex, byte[] salt) {
        byte[] password = hexToBytes(rawKeyHex);
        byte[] derived = pbkdf2HmacSha512(password, salt, ITERATIONS, KEY_LENGTH);
        return HexFormat.of().formatHex(derived);
    }

    /**
     * 手动实现 PBKDF2-HMAC-SHA512（RFC 2898）
     * 直接使用原始字节作为密码，避免 Java PBEKeySpec 的 UTF-8 编码问题
     */
    private static byte[] pbkdf2HmacSha512(byte[] password, byte[] salt, int iterations, int dkLen) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(password, "HmacSHA512"));

            int blockCount = (dkLen + HMAC_LENGTH - 1) / HMAC_LENGTH;
            byte[] result = new byte[blockCount * HMAC_LENGTH];

            byte[] blockBuf = new byte[salt.length + 4];
            System.arraycopy(salt, 0, blockBuf, 0, salt.length);

            for (int blockIndex = 1; blockIndex <= blockCount; blockIndex++) {
                // U1 = HMAC(password, salt || INT_32_BE(blockIndex))
                blockBuf[salt.length]     = (byte) (blockIndex >>> 24);
                blockBuf[salt.length + 1] = (byte) (blockIndex >>> 16);
                blockBuf[salt.length + 2] = (byte) (blockIndex >>> 8);
                blockBuf[salt.length + 3] = (byte) (blockIndex);

                byte[] u = mac.doFinal(blockBuf);
                byte[] t = Arrays.copyOf(u, u.length);

                // U2..Uc
                for (int iter = 1; iter < iterations; iter++) {
                    u = mac.doFinal(u);
                    for (int j = 0; j < t.length; j++) {
                        t[j] ^= u[j];
                    }
                }

                System.arraycopy(t, 0, result, (blockIndex - 1) * HMAC_LENGTH, HMAC_LENGTH);
            }

            return Arrays.copyOf(result, dkLen);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new RuntimeException("PBKDF2-HMAC-SHA512 failed", e);
        }
    }

    /**
     * 从数据库文件头部读取 salt（前16字节）
     */
    public static byte[] readSalt(String dbPath) throws IOException {
        try (FileInputStream fis = new FileInputStream(dbPath)) {
            byte[] salt = new byte[16];
            int read = fis.read(salt);
            if (read < 16) {
                throw new IOException("Database file too short: " + dbPath);
            }
            return salt;
        }
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
