package io.getbit.wxdb.frida;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 通过 Frida CLI 从微信进程提取 SQLCipher raw key。
 * <p>
 * 默认使用 spawn 模式：frida 启动微信进程，在数据库打开前就 hook CCKeyDerivationPBKDF，
 * 确保能捕获到 PBKDF 调用。用户需要扫码登录。
 * <p>
 * 也支持 attach 模式（附加到已运行的微信），但微信已打开数据库后不会再触发 PBKDF，
 * 仅适用于微信刚启动尚未打开数据库的短暂窗口期。
 * <p>
 * 依赖：系统需安装 frida CLI（brew install frida-tools）。
 */
public class FridaKeyExtractor {

    private static final String FRIDA_PATH = findFridaStatic();
    private static final Pattern RAW_KEY_PATTERN = Pattern.compile("([0-9a-f]{64})");

    /**
     * 根据密钥输出文件路径生成 Hook JS 脚本。
     * <p>
     * 密钥直接写入文件，不依赖 frida 的 stdout（frida 在非 TTY 环境下不会输出到 stdout）。
     */
    private static String buildHookScript(String keyFilePath) {
        // 转义路径中的反斜杠，供 JS 字符串使用
        String escapedPath = keyFilePath.replace("\\", "\\\\");
        return """
                const captured = new Set();
                const keyFile = "%s";
                const pbkdf = Module.findGlobalExportByName("CCKeyDerivationPBKDF");
                if (pbkdf) {
                    Interceptor.attach(pbkdf, {
                        onEnter(args) {
                            const passwordLen = args[2].toInt32();
                            const derivedKeyLen = args[8] ? args[8].toInt32() : 0;
                            if (derivedKeyLen === 32 && passwordLen === 32) {
                                try {
                                    const rawKeyBytes = args[1].readByteArray(passwordLen);
                                    const rawKeyHex = Array.from(new Uint8Array(rawKeyBytes))
                                        .map(b => b.toString(16).padStart(2, '0')).join('');
                                    if (!captured.has(rawKeyHex)) {
                                        captured.add(rawKeyHex);
                                        try {
                                            const f = new File(keyFile, 'w');
                                            f.write(rawKeyHex);
                                            f.flush();
                                            f.close();
                                        } catch(e) {
                                            console.log('Failed to write key file: ' + e);
                                        }
                                    }
                                } catch(e) {
                                    console.log('Hook error: ' + e);
                                }
                            }
                        }
                    });
                } else {
                    console.log('ERROR: CCKeyDerivationPBKDF not found');
                }
                """.formatted(escapedPath);
    }

    private String fridaPath;
    private int timeoutSeconds;
    private String lastError = null;

    public FridaKeyExtractor() {
        this.fridaPath = FRIDA_PATH;
        this.timeoutSeconds = 120; // spawn 模式需要较长时间（用户需扫码登录）
    }

    /**
     * 获取最后一次提取失败的错误信息
     */
    public String getLastError() {
        return lastError;
    }

    /**
     * 设置超时时间（秒）
     */
    public FridaKeyExtractor setTimeout(int seconds) {
        this.timeoutSeconds = seconds;
        return this;
    }

    /**
     * 提取 raw key（默认 spawn 模式）。
     * <p>
     * 会关闭当前运行的微信，由 frida 重新启动。用户需要扫码登录。
     *
     * @return raw key 的 hex 字符串（64字符），如果提取失败返回 null
     */
    public String extractKey() {
        lastError = null;
        String wechatBin = findWeChatBinary();
        if (wechatBin == null) {
            lastError = "找不到微信可执行文件";
            System.err.println("[FridaKeyExtractor] " + lastError);
            return null;
        }
        return spawnAndExtract(wechatBin);
    }

    /**
     * 附加到已运行的微信进程提取 key（不重启微信）。
     * <p>
     * 注意：如果微信已打开数据库（通常启动后几秒内完成），PBKDF 不会再被调用，提取会失败。
     *
     * @return raw key hex，失败返回 null
     */
    public String extractKeyByAttach() {
        int pid = getWeChatPid();
        if (pid <= 0) {
            System.err.println("[FridaKeyExtractor] 微信未运行");
            return null;
        }
        System.out.println("[FridaKeyExtractor] 微信 PID=" + pid + "，attach 模式...");
        return attachAndExtract(pid);
    }

    /**
     * 通过 spawn 方式启动微信并提取 raw key。
     *
     * @param wechatBinary 微信可执行文件路径
     * @return raw key hex 字符串，失败返回 null
     */
    public String extractKeyBySpawn(String wechatBinary) {
        System.out.println("[FridaKeyExtractor] Spawn 微信: " + wechatBinary);
        return spawnAndExtract(wechatBinary);
    }

    // ==================== 核心提取逻辑 ====================

    /**
     * Spawn 模式：frida 启动微信，在 PBKDF 调用前 hook。
     * <p>
     * macOS Gatekeeper 首次启动无签名副本时只做验证不会真正打开，
     * 因此加入重试逻辑：第一次等待60秒，如果没捕获到密钥就自动重试第二次。
     */
    private String spawnAndExtract(String binary) {
        int maxAttempts = 2;
        int firstAttemptSeconds = 30; // 首次尝试超时（Gatekeeper 验证通常很快）
        int secondAttemptSeconds = 180; // 第二次尝试超时（3分钟，足够扫码登录）

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            System.out.println("[FridaKeyExtractor] 第 " + attempt + " 次尝试 spawn 微信...");
            if (attempt == 2) {
                System.out.println("[FridaKeyExtractor] 首次尝试未捕获密钥，正在重试（Gatekeeper 验证可能已完成）...");
            }

            try {
                // 清理之前残留的微信副本和 frida 进程
                cleanupFridaProcesses();

                // 创建密钥输出文件
                Path keyFile = Files.createTempFile("wx_key_", ".txt");
                Files.delete(keyFile); // 删除文件，让 JS 创建（作为信号）

                // 生成带文件路径的 hook 脚本
                String hookScript = buildHookScript(keyFile.toString());
                Path scriptFile = Files.createTempFile("wx_hook_", ".js");
                Files.writeString(scriptFile, hookScript);

                ProcessBuilder pb = new ProcessBuilder(
                        fridaPath,
                        "-q",
                        "-f", binary,
                        "-l", scriptFile.toString(),
                        "-t", String.valueOf(timeoutSeconds)
                );
                pb.redirectErrorStream(false);

                Process process = pb.start();

                // 第一次用较短超时，第二次用完整超时
                int waitSeconds = (attempt == 1) ? firstAttemptSeconds : secondAttemptSeconds;
                String key = readKeyFromFile(keyFile, waitSeconds);

                // 终止 frida 进程
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);

                // 清理
                cleanupFridaProcesses();
                Files.deleteIfExists(scriptFile);
                Files.deleteIfExists(keyFile);

                if (key != null) {
                    return key;
                }

                // 第一次失败，继续重试
                System.out.println("[FridaKeyExtractor] 第 " + attempt + " 次尝试未捕获密钥");
            } catch (Exception e) {
                System.err.println("[FridaKeyExtractor] 第 " + attempt + " 次尝试失败: " + e.getMessage());
                cleanupFridaProcesses();
            }
        }

        lastError = "已尝试 " + maxAttempts + " 次，均未捕获到密钥";
        System.err.println("[FridaKeyExtractor] " + lastError);
        return null;
    }

    /**
     * Attach 模式：附加到已运行的微信
     */
    private String attachAndExtract(int pid) {
        Path keyFile = null;
        try {
            keyFile = Files.createTempFile("wx_key_", ".txt");
            Files.delete(keyFile);

            String hookScript = buildHookScript(keyFile.toString());
            Path scriptFile = Files.createTempFile("wx_hook_", ".js");
            Files.writeString(scriptFile, hookScript);

            ProcessBuilder pb = new ProcessBuilder(
                    fridaPath,
                    "-q",
                    "-p", String.valueOf(pid),
                    "-l", scriptFile.toString(),
                    "-t", String.valueOf(timeoutSeconds)
            );
            pb.redirectErrorStream(false);

            Process process = pb.start();
            String key = readKeyFromFile(keyFile, timeoutSeconds);

            process.destroyForcibly();
            Files.deleteIfExists(scriptFile);
            Files.deleteIfExists(keyFile);

            return key;
        } catch (Exception e) {
            System.err.println("[FridaKeyExtractor] Attach 提取失败: " + e.getMessage());
            lastError = e.getMessage();
            if (keyFile != null) {
                try { Files.deleteIfExists(keyFile); } catch (Exception ignored) {}
            }
            return null;
        }
    }

    /**
     * 轮询密钥文件，等待 JS hook 将密钥写入。
     *
     * @param keyFile 密钥文件路径
     * @param waitSeconds 等待超时（秒）
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
                    System.out.println("[FridaKeyExtractor] 密钥已提取: " + rawKey.substring(0, 8) + "...");
                    return rawKey;
                }
            }
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}
        }

        System.err.println("[FridaKeyExtractor] 等待 " + waitSeconds + "s 未捕获到密钥");
        return null;
    }

    // ==================== 工具方法 ====================

    /**
     * 查找可用于 Frida 注入的微信可执行文件路径。
     * <p>
     * macOS 上 Apple 签名的应用无法被 frida 注入，需要先将微信副本拷贝到 /tmp 并移除代码签名。
     *
     * @return 无签名的微信 可执行文件路径，失败返回 null
     */
    public static String findWeChatBinary() {
        // 优先使用已有的无签名副本
        String copyBin = "/tmp/WeChat_copy.app/Contents/MacOS/WeChat";
        if (new File(copyBin).exists()) {
            System.out.println("[FridaKeyExtractor] 使用已有无签名副本: " + copyBin);
            return copyBin;
        }

        // 查找原始微信安装路径
        String[] candidates = {
                "/Applications/WeChat.app",
                "/Applications/微信.app",
                System.getProperty("user.home") + "/Applications/WeChat.app"
        };
        String originalApp = null;
        for (String path : candidates) {
            if (new File(path).exists()) {
                originalApp = path;
                break;
            }
        }
        if (originalApp == null) {
            // 用 mdfind 搜索
            try {
                ProcessBuilder pb = new ProcessBuilder("mdfind", "-name", "WeChat.app");
                Process process = pb.start();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                    String line = reader.readLine();
                    process.waitFor(5, TimeUnit.SECONDS);
                    if (line != null && !line.isBlank() && new File(line.trim()).exists()) {
                        originalApp = line.trim();
                    }
                }
            } catch (Exception ignored) {}
        }
        if (originalApp == null) {
            System.err.println("[FridaKeyExtractor] 未找到微信安装路径");
            return null;
        }

        // 拷贝微信到 /tmp 并移除代码签名
        System.out.println("[FridaKeyExtractor] 正在创建无签名副本...");
        try {
            // 删除可能存在的旧副本
            new ProcessBuilder("rm", "-rf", "/tmp/WeChat_copy.app").start().waitFor(10, TimeUnit.SECONDS);

            // 拷贝
            Process cpProc = new ProcessBuilder("cp", "-R", originalApp, "/tmp/WeChat_copy.app").start();
            cpProc.waitFor(60, TimeUnit.SECONDS);
            if (cpProc.exitValue() != 0) {
                System.err.println("[FridaKeyExtractor] 拷贝失败");
                return null;
            }

            // 移除代码签名（使 frida 可以注入）
            Process signProc = new ProcessBuilder("codesign", "--remove-signature", "/tmp/WeChat_copy.app").start();
            signProc.waitFor(10, TimeUnit.SECONDS);
            if (signProc.exitValue() != 0) {
                System.err.println("[FridaKeyExtractor] 移除签名失败");
                return null;
            }

            System.out.println("[FridaKeyExtractor] 无签名副本已创建: " + copyBin);
            return copyBin;
        } catch (Exception e) {
            System.err.println("[FridaKeyExtractor] 创建副本失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 获取微信进程 PID
     */
    private static int getWeChatPid() {
        try {
            ProcessBuilder pb = new ProcessBuilder("pgrep", "-x", "WeChat");
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line = reader.readLine();
                process.waitFor(5, TimeUnit.SECONDS);
                if (line != null && !line.isBlank()) {
                    return Integer.parseInt(line.trim());
                }
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /**
     * 清理 Frida 相关进程：杀掉微信无签名副本和所有 frida-helper 残留进程。
     * <p>
     * 原因：Frida spawn 模式启动的微信副本，在 Frida 退出后会变成挂起状态（T），
     * 无法响应用户操作，也无法正常退出。frida-helper 进程也会残留。
     */
    public static void cleanupFridaProcesses() {
        try {
            // 1. 杀掉 /tmp/WeChat_copy.app 的所有进程
            ProcessBuilder pb = new ProcessBuilder("pkill", "-9", "-f", "WeChat_copy");
            Process p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            // 2. 杀掉所有 frida-helper 进程
            pb = new ProcessBuilder("pkill", "-9", "-f", "frida-helper");
            p = pb.start();
            p.waitFor(3, TimeUnit.SECONDS);

            // 3. 等待进程完全退出
            Thread.sleep(500);

            System.out.println("[FridaKeyExtractor] Frida 残留进程已清理");
        } catch (Exception e) {
            System.err.println("[FridaKeyExtractor] 清理进程异常: " + e.getMessage());
        }
    }

    /**
     * 查找 frida CLI 路径
     */
    private static String findFridaStatic() {
        String[] candidates = {
                "/usr/local/bin/frida",
                "/opt/homebrew/bin/frida",
                "/usr/bin/frida"
        };
        for (String path : candidates) {
            if (new File(path).exists()) return path;
        }
        String pathEnv = System.getenv("PATH");
        if (pathEnv != null) {
            for (String dir : pathEnv.split(":")) {
                File f = new File(dir, "frida");
                if (f.exists()) return f.getAbsolutePath();
            }
        }
        return "frida";
    }

    /**
     * 提取 key 并保存到文件
     *
     * @param keyFilePath 保存路径
     * @return raw key hex，失败返回 null
     */
    public String extractAndSave(String keyFilePath) {
        String key = extractKey();
        if (key != null) {
            try {
                Files.writeString(Path.of(keyFilePath), key);
                System.out.println("[FridaKeyExtractor] 密钥已保存到: " + keyFilePath);
            } catch (IOException e) {
                System.err.println("[FridaKeyExtractor] 保存失败: " + e.getMessage());
            }
        }
        return key;
    }
}
