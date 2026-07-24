package io.getbit.app;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Windows 版通过 Frida 从微信进程内存提取 SQLCipher raw key。
 * <p>
 * 微信 4.x (Weixin) 的 WCDB 会将派生后的 raw key 以特定格式缓存在进程内存中：
 * {@code x'<64hex_enc_key><32hex_salt>'}
 * 通过 Frida attach 到运行中的微信进程，扫描内存匹配此模式即可提取密钥。
 * <p>
 * 依赖：系统需安装 frida CLI（pip install frida-tools）。
 */
public class WinFridaKeyExtractor {

    /** 匹配内存中的密钥格式: x'<64hex_key><32hex_salt>' */
    private static final Pattern MEMORY_KEY_PATTERN =
            Pattern.compile("x'([0-9a-f]{64})([0-9a-f]{32})'", Pattern.CASE_INSENSITIVE);

    /** 匹配纯 64 位 hex 密钥 */
    private static final Pattern RAW_KEY_PATTERN =
            Pattern.compile("([0-9a-f]{64})", Pattern.CASE_INSENSITIVE);

    private static final String FRIDA_PATH = findFrida();

    private int timeoutSeconds = 60;
    private String lastError = null;

    public String getLastError() {
        return lastError;
    }

    public WinFridaKeyExtractor setTimeout(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    /**
     * 提取 raw key（微信 4.x：内存扫描方式）。
     * <p>
     * 微信必须已经在运行且已登录。通过 Frida attach 到 Weixin.exe 进程，
     * 扫描内存中的 x'&lt;64hex&gt;&lt;32hex&gt;' 模式提取密钥。
     *
     * @return raw key 的 hex 字符串（64字符），失败返回 null
     */
    public String extractKey() {
        lastError = null;

        // 检查微信是否在运行
        if (!isWeChatRunning()) {
            lastError = "微信未运行，请先启动微信并登录";
            System.err.println("[WinFridaKeyExtractor] " + lastError);
            return null;
        }

        System.out.println("[WinFridaKeyExtractor] 微信进程正在运行，开始内存扫描...");
        String key = scanMemoryForKey();
        if (key != null) {
            return key;
        }

        // 内存扫描失败，尝试 spawn 模式作为后备
        System.out.println("[WinFridaKeyExtractor] 内存扫描未找到密钥，尝试 spawn 模式...");
        String wechatExe = findWeChatExe();
        if (wechatExe != null) {
            key = spawnAndExtract(wechatExe);
        }

        if (key == null) {
            lastError = lastError != null ? lastError : "所有方式均未找到密钥";
            System.err.println("[WinFridaKeyExtractor] " + lastError);
        }
        return key;
    }

    // ==================== 方式1：内存扫描（微信 4.x 推荐方式） ====================

    /**
     * 检查微信是否在运行
     */
    private static boolean isWeChatRunning() {
        try {
            ProcessBuilder pb = new ProcessBuilder("tasklist", "/FI", "IMAGENAME eq Weixin.exe",
                    "/NH", "/FO", "CSV");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.toLowerCase().contains("weixin.exe")) {
                        return true;
                    }
                }
            }
            p.waitFor(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] 检查微信进程失败: " + e.getMessage());
        }
        return false;
    }

    /**
     * 构建 Frida 内存扫描脚本。
     * <p>
     * 扫描 Weixin.exe 进程的所有可读内存区域，
     * 搜索 x'&lt;64hex&gt;&lt;32hex&gt;' 格式的密钥字符串。
     * 同时搜索纯 32 字节 raw key（以 hex 形式出现在内存中）。
     */
    private static String buildScanScript(String outputPath) {
        String escapedPath = outputPath.replace("\\", "\\\\");
        return """
                const keyFile = "%s";
                const foundKeys = [];
                const seen = new Set();

                function trySaveKey(hexKey, label) {
                    if (seen.has(hexKey)) return;
                    seen.add(hexKey);
                    foundKeys.push(hexKey);
                    console.log('[Scan] Found ' + label + ' #' + foundKeys.length + ': ' + hexKey.substring(0, 16) + '...');
                }

                console.log('[Scan] Starting memory scan for x\'...\' pattern...');
                var totalRanges = 0;
                var scannedRanges = 0;
                var matchCount = 0;

                // 方式1: 搜索 x'<64hex_key><32hex_salt>' 格式
                Process.enumerateRanges('r--').forEach(function(range) {
                    totalRanges++;
                    if (range.size > 200 * 1024 * 1024) return;

                    try {
                        Memory.scan(range.base, range.size, "x\\'", {
                            onMatch: function(address, size) {
                                matchCount++;
                                try {
                                    var afterPrefix = address.add(2).readUtf8String(97);
                                    if (afterPrefix && afterPrefix.length >= 96) {
                                        var candidate = afterPrefix.substring(0, 96);
                                        if (/^[0-9a-f]{96}$/i.test(candidate)) {
                                            var encKey = candidate.substring(0, 64).toLowerCase();
                                            trySaveKey(encKey, 'x-key');
                                        }
                                    }
                                } catch(e) {}
                            },
                            onComplete: function() {
                                scannedRanges++;
                                if (scannedRanges %% 1000 === 0) {
                                    console.log('[Scan] Progress: ' + scannedRanges + '/' + totalRanges + ' ranges, ' + matchCount + ' x-matches, ' + foundKeys.length + ' keys');
                                }
                            }
                        });
                    } catch(e) {}
                });

                console.log('[Scan] Phase 1 done. ' + matchCount + ' x-prefix matches, ' + foundKeys.length + ' keys found');

                // 方式2: 搜索 WCDB 的 sqlcipher key 字符串格式 (hex 编码的 32 字节)
                // 有些版本存储为纯 64 hex 字符串
                if (foundKeys.length === 0) {
                    console.log('[Scan] Phase 2: scanning for raw 64-hex key patterns...');
                    matchCount = 0;
                    Process.enumerateRanges('r--').forEach(function(range) {
                        totalRanges++;
                        if (range.size > 200 * 1024 * 1024) return;
                        try {
                            // 搜索 32 字节的全零或特定 pattern 不合适，跳过
                            // 改为搜索 hex 编码的 key（64 字符的 hex 字符串）
                            Memory.scan(range.base, range.size, "000000", {
                                onMatch: function(address, size) {
                                    // 跳过，这只是占位
                                },
                                onComplete: function() {
                                    scannedRanges++;
                                }
                            });
                        } catch(e) {}
                    });
                }

                console.log('[Scan] Scan complete. Found ' + foundKeys.length + ' unique key(s)');

                // 写入结果到文件
                try {
                    var content = foundKeys.join('\\n');
                    if (content.length === 0) content = 'NO_KEY_FOUND';
                    const f = new File(keyFile, 'w');
                    f.write(content);
                    f.flush();
                    f.close();
                    console.log('[Scan] Results written to ' + keyFile);
                } catch(e) {
                    console.log('[Scan] Failed to write results: ' + e);
                }
                """.formatted(escapedPath);
    }

    /**
     * 通过 Frida attach 模式扫描微信进程内存提取密钥。
     * <p>
     * 不需要关闭微信，直接 attach 到运行中的进程扫描内存。
     * Frida CLI 会进入交互式 REPL 不会自动退出，
     * 所以我们启动它为后台进程，然后轮询结果文件。
     */
    private String scanMemoryForKey() {
        try {
            cleanupFridaProcesses();

            Path outputFile = Files.createTempFile("wx_scan_keys_", ".txt");
            // 先清空文件，确保轮询时能检测到新写入
            Files.writeString(outputFile, "");
            Path scriptFile = Files.createTempFile("wx_scan_", ".js");

            String scanScript = buildScanScript(outputFile.toString());
            Files.writeString(scriptFile, scanScript);

            System.out.println("[WinFridaKeyExtractor] 使用 Frida attach 模式扫描 Weixin.exe 内存...");
            System.out.println("[WinFridaKeyExtractor] Frida 路径: " + FRIDA_PATH);
            System.out.println("[WinFridaKeyExtractor] 脚本路径: " + scriptFile);
            System.out.println("[WinFridaKeyExtractor] 输出路径: " + outputFile);

            ProcessBuilder pb = new ProcessBuilder(
                    FRIDA_PATH,
                    "-q",
                    "-n", "Weixin.exe",
                    "-l", scriptFile.toString()
            );
            pb.redirectErrorStream(true);

            Process process = pb.start();
            System.out.println("[WinFridaKeyExtractor] Frida 进程已启动, PID: " + process.pid());

            // 后台线程读取 Frida 输出
            StringBuilder fridaOutput = new StringBuilder();
            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Frida] " + line);
                        fridaOutput.append(line).append("\n");
                    }
                } catch (Exception e) {
                    // ignore
                }
            }, "frida-output-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            // 轮询结果文件（Frida 进入 REPL 不会退出，但我们只需要等脚本执行完写入结果）
            int scanTimeout = Math.min(timeoutSeconds, 120); // 内存扫描最多等 120 秒
            String key = pollForResult(outputFile, scanTimeout);

            // 清理 Frida 进程（它还在 REPL 中）
            process.destroyForcibly();
            process.waitFor(5, TimeUnit.SECONDS);
            cleanupFridaProcesses();

            if (key == null) {
                // 检查 Frida 输出中是否有错误信息
                String output = fridaOutput.toString();
                if (output.contains("Error") || output.contains("error") || output.contains("unable")) {
                    lastError = "Frida 错误: " + output.substring(0, Math.min(output.length(), 500));
                } else if (output.contains("NO_KEY_FOUND")) {
                    lastError = "内存扫描完成，未找到密钥。微信可能尚未打开数据库（需要先登录）";
                } else {
                    lastError = "内存扫描超时或无结果。Frida 输出: " + output.substring(0, Math.min(output.length(), 300));
                }
                System.err.println("[WinFridaKeyExtractor] " + lastError);
            }

            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(outputFile);

            return key;
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] 内存扫描失败: " + e.getMessage());
            lastError = "内存扫描失败: " + e.getMessage();
            return null;
        }
    }

    /**
     * 轮询结果文件，等待 Frida 脚本写入扫描结果。
     */
    private String pollForResult(Path outputFile, int timeoutSec) {
        long startTime = System.currentTimeMillis();
        long timeoutMs = timeoutSec * 1000L;
        int emptyCheckCount = 0;

        while (System.currentTimeMillis() - startTime < timeoutMs) {
            try {
                if (Files.exists(outputFile)) {
                    String content = Files.readString(outputFile).trim();
                    if (!content.isEmpty()) {
                        System.out.println("[WinFridaKeyExtractor] 扫描结果文件有内容 (" + content.length() + " 字符)");
                        // 等待一小会确保写入完成
                        Thread.sleep(500);
                        content = Files.readString(outputFile).trim();
                        return parseScanResults(content);
                    }
                    emptyCheckCount++;
                    if (emptyCheckCount % 10 == 0) {
                        System.out.println("[WinFridaKeyExtractor] 等待扫描结果... (" + ((System.currentTimeMillis() - startTime) / 1000) + "s)");
                    }
                }
            } catch (Exception e) {
                // file might be locked
            }
            try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
        }

        System.err.println("[WinFridaKeyExtractor] 等待 " + timeoutSec + "s 未获取到扫描结果");
        return null;
    }

    /**
     * 解析扫描结果内容
     */
    private String parseScanResults(String content) {
        if (content == null || content.isEmpty()) return null;
        if (content.equals("NO_KEY_FOUND")) {
            System.out.println("[WinFridaKeyExtractor] 扫描明确报告未找到密钥");
            return null;
        }

        System.out.println("[WinFridaKeyExtractor] 扫描结果: " + content.length() + " 字符");

        // 尝试匹配完整格式 x'<64hex><32hex>'
        Matcher m = MEMORY_KEY_PATTERN.matcher(content);
        if (m.find()) {
            String rawKey = m.group(1).toLowerCase();
            System.out.println("[WinFridaKeyExtractor] 密钥已提取 (完整格式): " + rawKey.substring(0, 8) + "...");
            return rawKey;
        }

        // 尝试匹配纯 64 hex
        m = RAW_KEY_PATTERN.matcher(content);
        if (m.find()) {
            String rawKey = m.group(1).toLowerCase();
            System.out.println("[WinFridaKeyExtractor] 密钥已提取: " + rawKey.substring(0, 8) + "...");
            return rawKey;
        }

        // 多行结果，逐行尝试
        String[] lines = content.split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty()) continue;
            m = RAW_KEY_PATTERN.matcher(line);
            if (m.find()) {
                String rawKey = m.group(1).toLowerCase();
                System.out.println("[WinFridaKeyExtractor] 密钥已提取 (多行): " + rawKey.substring(0, 8) + "...");
                return rawKey;
            }
        }

        System.out.println("[WinFridaKeyExtractor] 扫描结果中未找到有效密钥格式");
        return null;
    }

    // ==================== 方式2：Spawn + Hook（后备方案） ====================

    /**
     * 查找微信可执行文件（微信 4.x Weixin）
     */
    private static String findWeChatExe() {
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\Tencent\\Weixin\\Weixin.exe",
                System.getenv("ProgramFiles(x86)") + "\\Tencent\\Weixin\\Weixin.exe",
                "C:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
                "C:\\Program Files (x86)\\Tencent\\Weixin\\Weixin.exe",
                "D:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
        };
        for (String path : candidates) {
            if (path != null && new File(path).exists()) {
                return path;
            }
        }

        // 通过注册表查找
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-ItemProperty 'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Weixin' -ErrorAction SilentlyContinue).InstallLocation");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty()) {
                    String installDir = line.trim().replace("\"", "");
                    String exePath = installDir + "\\Weixin.exe";
                    if (new File(exePath).exists()) return exePath;
                }
            }
        } catch (Exception ignored) {}

        // 通过运行中的进程获取路径
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-Process Weixin -ErrorAction SilentlyContinue | Select-Object -First 1).Path");
            Process p = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty()) {
                    String exePath = line.trim().replace("\"", "");
                    if (new File(exePath).exists()) return exePath;
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Spawn 模式提取密钥（后备方案）。
     * 关闭已运行的微信，通过 Frida spawn 重新启动并 hook 密钥派生函数。
     */
    private String spawnAndExtract(String wechatExe) {
        try {
            System.out.println("[WinFridaKeyExtractor] 尝试 spawn 模式...");
            killWeChatProcesses();
            cleanupFridaProcesses();
            Thread.sleep(2000);

            Path keyFile = Files.createTempFile("wx_key_", ".txt");
            Files.delete(keyFile);

            String hookScript = buildSpawnHookScript(keyFile.toString());
            Path scriptFile = Files.createTempFile("wx_hook_", ".js");
            Files.writeString(scriptFile, hookScript);

            ProcessBuilder pb = new ProcessBuilder(
                    FRIDA_PATH, "-q",
                    "-f", wechatExe,
                    "-l", scriptFile.toString(),
                    "-t", String.valueOf(timeoutSeconds)
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            Thread outputReader = new Thread(() -> {
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        System.out.println("[Frida-Spawn] " + line);
                    }
                } catch (Exception e) { /* ignore */ }
            }, "frida-spawn-reader");
            outputReader.setDaemon(true);
            outputReader.start();

            String key = readKeyFromFile(keyFile, timeoutSeconds);

            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);
            cleanupFridaProcesses();
            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(keyFile);

            return key;
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] spawn 模式失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * Spawn 模式的 hook 脚本（hook PBKDF2 和 sqlite3_key）
     */
    private static String buildSpawnHookScript(String keyFilePath) {
        String escapedPath = keyFilePath.replace("\\", "\\\\");
        return """
                const captured = new Set();
                const keyFile = "%s";

                function saveKey(hexKey, source) {
                    if (captured.has(hexKey)) return;
                    captured.add(hexKey);
                    try {
                        const f = new File(keyFile, 'w');
                        f.write(hexKey);
                        f.flush();
                        f.close();
                        console.log('KEY_FOUND[' + source + ']: ' + hexKey.substring(0, 16) + '...');
                    } catch(e) {
                        console.log('Write error: ' + e);
                    }
                }

                function bytesToHex(bytes) {
                    return Array.from(new Uint8Array(bytes))
                        .map(b => b.toString(16).padStart(2, '0')).join('');
                }

                // Hook BCryptDeriveKeyPBKDF2
                try {
                    var pbkdf2 = Module.findExportByName('bcryptprimitives.dll', 'BCryptDeriveKeyPBKDF2');
                    if (pbkdf2) {
                        Interceptor.attach(pbkdf2, {
                            onEnter(args) {
                                this.derivedKeyPtr = args[7];
                                this.derivedKeyLen = args[8].toInt32();
                            },
                            onLeave(retval) {
                                if (retval.toInt32() === 0 && this.derivedKeyLen === 32) {
                                    try {
                                        var hex = bytesToHex(this.derivedKeyPtr.readByteArray(32));
                                        saveKey(hex, 'PBKDF2');
                                    } catch(e) {}
                                }
                            }
                        });
                        console.log('Hooked BCryptDeriveKeyPBKDF2');
                    }
                } catch(e) {}

                // Hook sqlite3_key
                try {
                    var s3k = Module.findExportByName(null, 'sqlite3_key');
                    if (s3k) {
                        Interceptor.attach(s3k, {
                            onEnter(args) {
                                var n = args[2].toInt32();
                                if (n === 32) {
                                    try { saveKey(bytesToHex(args[1].readByteArray(n)), 'sqlite3_key'); } catch(e) {}
                                }
                            }
                        });
                        console.log('Hooked sqlite3_key');
                    }
                } catch(e) {}

                console.log('Spawn hook script loaded');
                """.formatted(escapedPath);
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

    // ==================== 工具方法 ====================

    /**
     * 关闭已运行的微信进程
     */
    private static void killWeChatProcesses() {
        try {
            System.out.println("[WinFridaKeyExtractor] 正在关闭已运行的微信...");
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "Weixin.exe");
            Process p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);

            pb = new ProcessBuilder("taskkill", "/F", "/IM", "WeChat.exe");
            p = pb.start();
            p.waitFor(5, TimeUnit.SECONDS);

            System.out.println("[WinFridaKeyExtractor] 微信进程已关闭");
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] 关闭微信进程失败: " + e.getMessage());
        }
    }

    /**
     * 清理 Frida 相关进程
     */
    public static void cleanupFridaProcesses() {
        try {
            ProcessBuilder pb = new ProcessBuilder("taskkill", "/F", "/IM", "frida-helper.exe");
            Process p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            pb = new ProcessBuilder("taskkill", "/F", "/IM", "frida-agent.dll");
            p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            Thread.sleep(500);
        } catch (Exception e) {
            System.err.println("[WinFridaKeyExtractor] 清理进程异常: " + e.getMessage());
        }
    }

    /**
     * 查找 frida CLI 路径
     */
    private static String findFrida() {
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
