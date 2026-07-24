package io.getbit.app;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 版通过 Frida CLI 从微信进程提取 SQLCipher raw key。
 * <p>
 * Windows 上微信不需要无签名副本，Frida 可以直接 attach 或 spawn。
 * Hook 目标：WeChat.dll 中的密钥派生函数或内存扫描。
 * <p>
 * 依赖：系统需安装 frida CLI（pip install frida-tools）。
 */
public class WinFridaKeyExtractor {

    private static final Pattern RAW_KEY_PATTERN = Pattern.compile("([0-9a-f]{64})");
    private static final String FRIDA_PATH = findFrida();

    private int timeoutSeconds = 180;
    private String lastError = null;

    /**
     * 获取最后一次提取失败的错误信息
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 设置超时时间（秒）
     */
    public WinFridaKeyExtractor setTimeout(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    /**
     * 提取 raw key。
     * <p>
     * 使用 Frida spawn 模式启动微信，在密钥派生时 hook 捕获 raw key。
     *
     * @return raw key 的 hex 字符串（64字符），失败返回 null
     */
    public String extractKey() {
        lastError = null;
        String wechatExe = findWeChatExe();
        if (wechatExe == null) {
            lastError = "找不到微信可执行文件";
            System.err.println("[WinFridaKeyExtractor] " + lastError);
            return null;
        }
        return spawnAndExtract(wechatExe);
    }

    /**
     * 查找微信可执行文件
     */
    private static String findWeChatExe() {
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\Tencent\\WeChat\\WeChat.exe",
                System.getenv("ProgramFiles(x86)") + "\\Tencent\\WeChat\\WeChat.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\Tencent\\WeChat\\WeChat.exe",
                System.getProperty("user.home") + "\\AppData\\Roaming\\Tencent\\WeChat\\WeChat.exe",
                "C:\\Program Files\\Tencent\\WeChat\\WeChat.exe",
                "C:\\Program Files (x86)\\Tencent\\WeChat\\WeChat.exe",
                "D:\\Program Files\\Tencent\\WeChat\\WeChat.exe",
        };
        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // 通过注册表查找
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-ItemProperty 'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\WeChat' -ErrorAction SilentlyContinue).InstallLocation");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty()) {
                    String exePath = line.trim() + "\\WeChat.exe";
                    if (new File(exePath).exists()) return exePath;
                }
            }
        } catch (Exception ignored) {}

        // 通过 where 命令查找
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "WeChat.exe");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty() && new File(line.trim()).exists()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * 构建 Windows 版 Hook 脚本。
     * <p>
     * 尝试多种方式捕获密钥：
     * 1. Hook BCryptDeriveKeyPBKDF2（Windows CNG PBKDF2 实现）
     * 2. Hook CryptDeriveKey（CryptoAPI）
     * 3. 内存扫描 WeChat.dll 中的已知模式
     */
    private static String buildHookScript(String keyFilePath) {
        String escapedPath = keyFilePath.replace("\\", "\\\\");
        return """
                const captured = new Set();
                const keyFile = "%s";

                function saveKey(hexKey) {
                    if (captured.has(hexKey)) return;
                    captured.add(hexKey);
                    try {
                        const f = new File(keyFile, 'w');
                        f.write(hexKey);
                        f.flush();
                        f.close();
                        console.log('Key saved: ' + hexKey.substring(0, 8) + '...');
                    } catch(e) {
                        console.log('Failed to write key file: ' + e);
                    }
                }

                // 方式1: Hook BCryptDeriveKeyPBKDF2
                try {
                    const bcrypt = Module.getLoad('bcryptprimitives.dll');
                    if (bcrypt) {
                        const pbkdf2 = Module.findExportByName('bcryptprimitives.dll', 'BCryptDeriveKeyPBKDF2');
                        if (pbkdf2) {
                            Interceptor.attach(pbkdf2, {
                                onEnter(args) {
                                    // args: hAlg, hHash, pbPassword, cbPassword, pbSalt, cbSalt, cIterations, pbDerivedKey, cbDerivedKey
                                    const pwdLen = args[3].toInt32();
                                    const derivedLen = args[8].toInt32();
                                    if (pwdLen === 32 && derivedLen === 32) {
                                        try {
                                            const rawKeyBytes = args[2].readByteArray(pwdLen);
                                            const hex = Array.from(new Uint8Array(rawKeyBytes))
                                                .map(b => b.toString(16).padStart(2, '0')).join('');
                                            saveKey(hex);
                                        } catch(e) {}
                                    }
                                }
                            });
                            console.log('Hooked BCryptDeriveKeyPBKDF2');
                        }
                    }
                } catch(e) {
                    console.log('bcrypt hook failed: ' + e);
                }

                // 方式2: 扫描 WeChat.dll 内存中的密钥
                // 在密钥派生后，raw key 通常保存在模块的堆内存中
                setTimeout(function() {
                    if (captured.size > 0) return; // 已通过 hook 捕获
                    try {
                        const wechatDll = Process.findModuleByName('WeChat.dll');
                        if (wechatDll) {
                            console.log('Scanning WeChat.dll memory...');
                            const pattern = '00 00 00 00 00 00 00 00 ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ?? ??';
                            // 使用更通用的方法：扫描所有可读内存中的 32 字节对齐数据
                            Process.enumerateRanges('r--').forEach(function(range) {
                                if (captured.size > 0) return;
                                if (range.size > 100 * 1024 * 1024) return; // 跳过超大区域
                                try {
                                    Memory.scan(range.base, range.size, pattern, {
                                        onMatch: function(address, size) {
                                            // 验证是否为有效的 raw key（检查后续使用）
                                        },
                                        onComplete: function() {}
                                    });
                                } catch(e) {}
                            });
                        }
                    } catch(e) {
                        console.log('Memory scan failed: ' + e);
                    }
                }, 5000);

                // 方式3: Hook sqlite3_key（SQLCipher 扩展函数）
                try {
                    const sqlite3Key = Module.findExportByName(null, 'sqlite3_key');
                    if (sqlite3Key) {
                        Interceptor.attach(sqlite3Key, {
                            onEnter(args) {
                                // args: db, pKey, nKey
                                const nKey = args[2].toInt32();
                                if (nKey === 32) {
                                    try {
                                        const keyBytes = args[1].readByteArray(nKey);
                                        const hex = Array.from(new Uint8Array(keyBytes))
                                            .map(b => b.toString(16).padStart(2, '0')).join('');
                                        saveKey(hex);
                                    } catch(e) {}
                                }
                            }
                        });
                        console.log('Hooked sqlite3_key');
                    }
                } catch(e) {
                    console.log('sqlite3_key hook failed: ' + e);
                }
                """.formatted(escapedPath);
    }

    /**
     * Spawn 模式提取密钥
     */
    private String spawnAndExtract(String wechatExe) {
        int maxAttempts = 2;
        int firstAttemptSeconds = 30;
        int secondAttemptSeconds = 180;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            System.out.println("[WinFridaKeyExtractor] 第 " + attempt + " 次尝试 spawn 微信...");

            try {
                cleanupFridaProcesses();

                Path keyFile = Files.createTempFile("wx_key_", ".txt");
                Files.delete(keyFile);

                String hookScript = buildHookScript(keyFile.toString());
                Path scriptFile = Files.createTempFile("wx_hook_", ".js");
                Files.writeString(scriptFile, hookScript);

                ProcessBuilder pb = new ProcessBuilder(
                        FRIDA_PATH,
                        "-q",
                        "-f", wechatExe,
                        "-l", scriptFile.toString(),
                        "-t", String.valueOf(timeoutSeconds)
                );
                pb.redirectErrorStream(false);

                Process process = pb.start();

                int waitSeconds = (attempt == 1) ? firstAttemptSeconds : secondAttemptSeconds;
                String key = readKeyFromFile(keyFile, waitSeconds);

                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);

                cleanupFridaProcesses();
                Files.deleteIfExists(scriptFile);
                Files.deleteIfExists(keyFile);

                if (key != null) {
                    return key;
                }

                System.out.println("[WinFridaKeyExtractor] 第 " + attempt + " 次尝试未捕获密钥");
            } catch (Exception e) {
                System.err.println("[WinFridaKeyExtractor] 第 " + attempt + " 次尝试失败: " + e.getMessage());
                cleanupFridaProcesses();
            }
        }

        lastError = "已尝试 " + maxAttempts + " 次，均未捕获到密钥";
        System.err.println("[WinFridaKeyExtractor] " + lastError);
        return null;
    }

    /**
     * 轮询密钥文件
     */
    private String readKeyFromFile(Path keyFile, int waitSeconds) throws IOException, InterruptedException {
        long startTime = System.currentTimeMillis();
        long timeoutMs = waitSeconds * 1000L;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (Files.exists(keyFile)) {
                String content = Files.readString(keyFile).trim();
                Matcher matcher = RAW_KEY_PATTERN.matcher(content);
                if (matcher.find()) {
                    String rawKey = matcher.group(1);
                    System.out.println("[WinFridaKeyExtractor] 密钥已提取: " + rawKey.substring(0, 8) + "...");
                    return rawKey;
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        System.err.println("[WinFridaKeyExtractor] 等待 " + waitSeconds + "s 未捕获到密钥");
        return null;
    }

    /**
     * 清理 Frida 相关进程
     */
    public static void cleanupFridaProcesses() {
        try {
            // 杀掉所有 frida-helper 进程
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "frida-helper.exe");
            Process p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            pb = new ProcessBuilder("taskkill", "/F", "/IM", "frida-agent.dll");
            p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            Thread.sleep(500);
            System.out.println("[WinFridaKeyExtractor] Frida 残留进程已清理");
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] 清理进程异常: " + e.getMessage());
        }
    }

    /**
     * 查找 frida CLI 路径
     */
    private static String findFrida() {
        // 常见路径
        String[] candidates = {
                System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Python\\Python311\\Scripts\\frida.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Python\\Python310\\Scripts\\frida.exe",
                System.getProperty("user.home") + "\\AppData\\Local\\Programs\\Python\\Python39\\Scripts\\frida.exe",
                "C:\\Python311\\Scripts\\frida.exe",
                "C:\\Python310\\Scripts\\frida.exe",
        };
        for (String path : candidates) {
            if (path != null && new File(path).exists()) return path;
        }

        // 通过 PATH 查找
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(";")) {
                File f = new File(dir, "frida.exe");
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return "frida.exe";
    }
}
