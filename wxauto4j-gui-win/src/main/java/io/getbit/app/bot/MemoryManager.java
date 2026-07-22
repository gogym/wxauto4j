package io.getbit.app.bot;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import io.getbit.app.ai.MemoryMessage;
import io.getbit.app.config.AppConfig;

import java.io.IOException;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 对话记忆管理器
 *
 * <p>按聊天窗口独立存储历史消息到 {@code ~/.wxauto4j/memory/{wxId}/{chatName}/} 目录。
 * 支持最大存储条数限制和 AI 上下文携带条数控制。</p>
 */
public class MemoryManager {

    private static final Logger LOG = Logger.getLogger(MemoryManager.class.getName());
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** 记忆存储目录根路径 */
    private final Path memoryDir;

    /** 配置引用 */
    private final AppConfig config;

    /** 当前微信 ID */
    private final String wxId;

    /** 内存缓存：chatName -> 消息列表 */
    private final Map<String, List<MemoryMessage>> cache = new ConcurrentHashMap<>();

    public MemoryManager(Path memoryDir, AppConfig config, String wxId) {
        this.memoryDir = memoryDir;
        this.config = config;
        this.wxId = wxId;
    }

    /**
     * 添加一条消息到记忆
     *
     * @param chatName 聊天窗口名称
     * @param message  消息
     */
    public void addMessage(String chatName, MemoryMessage message) {
        if (!config.isMemorySwitch()) return;

        List<MemoryMessage> messages = cache.computeIfAbsent(
                sanitizeChatName(chatName), k -> loadFromDisk(chatName));

        messages.add(message);

        // 超出最大条数时移除最早的
        int maxCount = config.getMemoryMaxCount();
        while (messages.size() > maxCount) {
            messages.remove(0);
        }

        // 异步保存到磁盘
        saveToDisk(chatName, messages);
    }

    /**
     * 获取用于 AI 请求的历史消息
     *
     * @param chatName 聊天窗口名称
     * @return 最近 N 条历史消息
     */
    public List<MemoryMessage> getContextHistory(String chatName) {
        if (!config.isMemorySwitch()) return new ArrayList<>();

        List<MemoryMessage> messages = cache.computeIfAbsent(
                sanitizeChatName(chatName), k -> loadFromDisk(chatName));

        int contextCount = config.getMemoryContextCount();
        int start = Math.max(0, messages.size() - contextCount);
        return new ArrayList<>(messages.subList(start, messages.size()));
    }

    /**
     * 获取指定窗口的全部记忆
     */
    public List<MemoryMessage> getAllMessages(String chatName) {
        List<MemoryMessage> messages = cache.computeIfAbsent(
                sanitizeChatName(chatName), k -> loadFromDisk(chatName));
        return new ArrayList<>(messages);
    }

    /**
     * 获取所有有记忆的聊天窗口名称
     */
    public List<String> listChatNames() {
        List<String> names = new ArrayList<>();
        Path wxDir = memoryDir.resolve(wxId);
        if (!Files.exists(wxDir)) return names;

        try {
            Files.list(wxDir).forEach(p -> {
                if (Files.isDirectory(p)) {
                    // 尝试读取 name.json 获取原始名称
                    Path nameFile = p.resolve("name.json");
                    if (Files.exists(nameFile)) {
                        try {
                            String originalName = Files.readString(nameFile, StandardCharsets.UTF_8).trim();
                            names.add(originalName);
                        } catch (IOException e) {
                            names.add(p.getFileName().toString());
                        }
                    } else {
                        names.add(p.getFileName().toString());
                    }
                }
            });
        } catch (IOException e) {
            LOG.log(Level.WARNING, "列出记忆目录失败", e);
        }
        return names;
    }

    /**
     * 清除指定窗口的记忆
     */
    public void clearMemory(String chatName) {
        String safeName = sanitizeChatName(chatName);
        cache.remove(safeName);
        Path chatDir = memoryDir.resolve(wxId).resolve(safeName);
        try {
            if (Files.exists(chatDir)) {
                Files.walk(chatDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                        });
            }
            LOG.info("已清除记忆: " + chatName);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "清除记忆失败: " + chatName, e);
        }
    }

    /**
     * 清除全部记忆
     */
    public void clearAllMemory() {
        cache.clear();
        Path wxDir = memoryDir.resolve(wxId);
        try {
            if (Files.exists(wxDir)) {
                Files.walk(wxDir)
                        .sorted(java.util.Comparator.reverseOrder())
                        .forEach(p -> {
                            try { Files.delete(p); } catch (IOException e) { /* ignore */ }
                        });
            }
            LOG.info("已清除全部记忆");
        } catch (IOException e) {
            LOG.log(Level.WARNING, "清除全部记忆失败", e);
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 清理聊天窗口名称，使其适合作为目录名
     */
    private String sanitizeChatName(String chatName) {
        if (chatName == null || chatName.isEmpty()) {
            return "hash_" + String.valueOf(System.currentTimeMillis());
        }
        // 移除 Windows 文件名非法字符
        String safe = chatName.replaceAll("[\\\\/:*?\"<>|]", "_");
        if (safe.isEmpty()) {
            return "hash_" + Math.abs(chatName.hashCode());
        }
        return safe;
    }

    /**
     * 从磁盘加载记忆
     */
    private List<MemoryMessage> loadFromDisk(String chatName) {
        String safeName = sanitizeChatName(chatName);
        Path chatDir = memoryDir.resolve(wxId).resolve(safeName);
        Path memoryFile = chatDir.resolve(safeName + "_memory.json");

        if (!Files.exists(memoryFile)) {
            return new ArrayList<>();
        }

        try {
            String json = Files.readString(memoryFile, StandardCharsets.UTF_8);
            Type listType = new TypeToken<List<MemoryMessage>>() {}.getType();
            List<MemoryMessage> loaded = GSON.fromJson(json, listType);
            return loaded != null ? new ArrayList<>(loaded) : new ArrayList<>();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "加载记忆失败: " + chatName, e);
            return new ArrayList<>();
        }
    }

    /**
     * 保存记忆到磁盘
     */
    private void saveToDisk(String chatName, List<MemoryMessage> messages) {
        String safeName = sanitizeChatName(chatName);
        Path chatDir = memoryDir.resolve(wxId).resolve(safeName);
        Path memoryFile = chatDir.resolve(safeName + "_memory.json");

        try {
            Files.createDirectories(chatDir);

            // 如果原始名称和目录名不一致，保存 name.json
            if (!safeName.equals(chatName)) {
                Path nameFile = chatDir.resolve("name.json");
                Files.writeString(nameFile, chatName, StandardCharsets.UTF_8);
            }

            String json = GSON.toJson(messages);
            Files.writeString(memoryFile, json, StandardCharsets.UTF_8);
        } catch (IOException e) {
            LOG.log(Level.WARNING, "保存记忆失败: " + chatName, e);
        }
    }
}
