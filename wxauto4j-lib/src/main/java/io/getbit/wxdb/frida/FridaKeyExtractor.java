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
    private static final Pattern RAW_KEY_PATTERN = Pattern.compile("RAW_KEY=([0-9a-f]{64})");

    /**
     * Hook JS 脚本 - 拦截 CCKeyDerivationPBKDF 提取 raw key
     */
    private static final String HOOK_SCRIPT = """
            const captured = new Set();
            const pbkdf = Module.findGlobalExportByName("CCKeyDerivationPBKDF");
            if (pbkdf) {
                send("FOUND_PBKDF");
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
                                    send("RAW_KEY=" + rawKeyHex);
                                }
                            } catch(e) {
                                send("ERROR:" + e);
                            }
                        }
                    }
                });
                send("HOOKS_INSTALLED");
            } else {
                send("ERROR:CCKeyDerivationPBKDF not found");
            }
            """;

    private String fridaPath;
    private int timeoutSeconds;

    public FridaKeyExtractor() {
        this.fridaPath = FRIDA_PATH;
        this.timeoutSeconds = 120; // spawn 模式需要较长时间（用户需扫码登录）
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
        String wechatBin = findWeChatBinary();
        if (wechatBin == null) {
            System.err.println("[FridaKeyExtractor] 找不到微信可执行文件");
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
     * Spawn 模式：frida 启动微信，在 PBKDF 调用前 hook
     */
    private String spawnAndExtract(String binary) {
        try {
            // 先清理之前残留的微信副本和 frida 进程
            cleanupFridaProcesses();

            Path scriptFile = Files.createTempFile("wx_hook_", ".js");
            Files.writeString(scriptFile, HOOK_SCRIPT);

            ProcessBuilder pb = new ProcessBuilder(
                    fridaPath,
                    "-q",                     // quiet mode
                    "-f", binary,             // spawn 模式
                    "-l", scriptFile.toString(),
                    "-t", String.valueOf(timeoutSeconds)  // 保持 frida 运行，使 spawn 的进程存活
            );
            pb.redirectErrorStream(false);

            System.out.println("[FridaKeyExtractor] 正在通过 Frida 启动微信并 hook PBKDF...");
            System.out.println("[FridaKeyExtractor] 请在微信中扫码登录，等待数据库打开...");

            Process process = pb.start();
            String key = readKeyFromOutput(process);

            // 先强制终止 frida 进程
            process.destroyForcibly();
            process.waitFor(3, TimeUnit.SECONDS);

            // 清理：杀掉微信副本和 frida-helper 残留进程
            cleanupFridaProcesses();

            Files.deleteIfExists(scriptFile);

            return key;
        } catch (Exception e) {
            System.err.println("[FridaKeyExtractor] Spawn 提取失败: " + e.getMessage());
            // 失败时也清理
            cleanupFridaProcesses();
            return null;
        }
    }

    /**
     * Attach 模式：附加到已运行的微信
     */
    private String attachAndExtract(int pid) {
        try {
            Path scriptFile = Files.createTempFile("wx_hook_", ".js");
            Files.writeString(scriptFile, HOOK_SCRIPT);

            ProcessBuilder pb = new ProcessBuilder(
                    fridaPath,
                    "-q",
                    "-p", String.valueOf(pid),
                    "-l", scriptFile.toString(),
                    "-t", String.valueOf(timeoutSeconds)
            );
            pb.redirectErrorStream(false);

            Process process = pb.start();
            String key = readKeyFromOutput(process);

            process.destroyForcibly();
            Files.deleteIfExists(scriptFile);

            return key;
        } catch (Exception e) {
            System.err.println("[FridaKeyExtractor] Attach 提取失败: " + e.getMessage());
            return null;
        }
    }

    /**
     * 从 frida 进程输出中解析 raw key
     */
    private String readKeyFromOutput(Process process) throws IOException, InterruptedException {
        // 在单独线程中消费 stderr，防止阻塞
        Thread stderrThread = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains("Error") || line.contains("error")) {
                        System.err.println("[Frida STDERR] " + line);
                    }
                }
            } catch (IOException ignored) {}
        });
        stderrThread.setDaemon(true);
        stderrThread.start();

        String rawKey = null;
        boolean hookInstalled = false;

        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            long startTime = System.currentTimeMillis();
            long timeoutMs = timeoutSeconds * 1000L;

            String line;
            while ((line = reader.readLine()) != null) {
                if (System.currentTimeMillis() - startTime > timeoutMs) {
                    System.err.println("[FridaKeyExtractor] 超时 (" + timeoutSeconds + "s)");
                    break;
                }

                // 在输出中查找 RAW_KEY=xxx（frida CLI 可能包裹在 JSON 或 REPL 格式中）
                Matcher matcher = RAW_KEY_PATTERN.matcher(line);
                if (matcher.find()) {
                    rawKey = matcher.group(1);
                    System.out.println("[FridaKeyExtractor] 密钥已提取: " + rawKey.substring(0, 8) + "...");
                    break;
                }

                if (line.contains("HOOKS_INSTALLED")) {
                    hookInstalled = true;
                    System.out.println("[FridaKeyExtractor] Hook 已安装，等待 PBKDF 调用...");
                }

                if (line.contains("ERROR:")) {
                    System.err.println("[FridaKeyExtractor] 脚本错误: " + line);
                }
            }
        }

        if (rawKey == null) {
            System.err.println("[FridaKeyExtractor] 未捕获到密钥。Hook 已安装: " + hookInstalled);
        }

        return rawKey;
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
