package io.getbit.app.prompt;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Prompt 管理器
 *
 * <p>管理 {@code ~/.wxauto4j/prompt/} 目录下的 Prompt 文件。
 * 每个 .md 文件对应一份 Prompt，文件名（不含扩展名）即 Prompt 名称。</p>
 *
 * <p>优先级：群组/用户专属 Prompt > 全局默认 Prompt > 空字符串</p>
 */
public class PromptManager {

    private static final Logger LOG = Logger.getLogger(PromptManager.class.getName());

    /** Prompt 文件目录 */
    private final Path promptDir;

    public PromptManager(Path promptDir) {
        this.promptDir = promptDir;
    }

    /**
     * 获取所有 Prompt 名称列表
     *
     * @return Prompt 名称列表（不含 .md 扩展名）
     */
    public List<String> listPromptNames() {
        List<String> names = new ArrayList<>();
        if (!Files.exists(promptDir)) {
            return names;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(promptDir, "*.md")) {
            for (Path entry : stream) {
                String fileName = entry.getFileName().toString();
                String name = fileName.substring(0, fileName.length() - 3); // 去掉 .md
                names.add(name);
            }
        } catch (IOException e) {
            LOG.log(Level.WARNING, "列出 Prompt 文件失败", e);
        }
        return names;
    }

    /**
     * 读取指定 Prompt 的内容
     *
     * @param name Prompt 名称（不含 .md）
     * @return Prompt 内容，不存在返回 null
     */
    public String readPrompt(String name) {
        Path file = promptDir.resolve(name + ".md");
        if (!Files.exists(file)) {
            return null;
        }
        try {
            return Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "读取 Prompt 失败: " + name, e);
            return null;
        }
    }

    /**
     * 写入/更新 Prompt 内容
     *
     * @param name    Prompt 名称
     * @param content Prompt 内容
     */
    public void writePrompt(String name, String content) {
        Path file = promptDir.resolve(name + ".md");
        try {
            Files.writeString(file, content, StandardCharsets.UTF_8);
            LOG.info("Prompt 已保存: " + name);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "保存 Prompt 失败: " + name, e);
        }
    }

    /**
     * 删除 Prompt 文件
     *
     * @param name Prompt 名称
     * @return 是否删除成功
     */
    public boolean deletePrompt(String name) {
        Path file = promptDir.resolve(name + ".md");
        try {
            return Files.deleteIfExists(file);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "删除 Prompt 失败: " + name, e);
            return false;
        }
    }

    /**
     * 检查 Prompt 是否存在
     */
    public boolean exists(String name) {
        return Files.exists(promptDir.resolve(name + ".md"));
    }

    /**
     * 获取指定聊天窗口的有效 Prompt 内容
     *
     * <p>优先级：专属 Prompt 映射 > 全局默认 Prompt</p>
     *
     * @param chatName        聊天窗口名称（群名或用户昵称）
     * @param promptMap       专属 Prompt 映射（群/用户 → Prompt 名称）
     * @param defaultPrompt   全局默认 Prompt 名称
     * @return Prompt 内容，如果都找不到返回空字符串
     */
    public String resolvePrompt(String chatName, Map<String, String> promptMap, String defaultPrompt) {
        // 1. 检查专属映射
        if (promptMap != null && promptMap.containsKey(chatName)) {
            String promptName = promptMap.get(chatName);
            String content = readPrompt(promptName);
            if (content != null) {
                return content;
            }
        }

        // 2. 使用全局默认
        if (defaultPrompt != null && !defaultPrompt.isEmpty()) {
            String content = readPrompt(defaultPrompt);
            if (content != null) {
                return content;
            }
        }

        // 3. 都没有
        return "";
    }
}
