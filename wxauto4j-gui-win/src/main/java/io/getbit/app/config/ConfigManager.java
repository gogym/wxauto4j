package io.getbit.app.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 配置管理器
 *
 * <p>负责从 {@code ~/.wxauto4j/} 目录加载和保存配置。
 * 首次运行时自动创建目录和默认配置文件。</p>
 */
public class ConfigManager {

    private static final Logger LOG = Logger.getLogger(ConfigManager.class.getName());

    /** 配置根目录 */
    private final Path configDir;

    /** 配置文件路径 */
    private final Path configFile;

    /** Prompt 目录路径 */
    private final Path promptDir;

    /** 记忆目录路径 */
    private final Path memoryDir;

    /** 日志目录路径 */
    private final Path logsDir;

    /** Gson 实例 */
    private final Gson gson;

    /** 当前配置 */
    private AppConfig config;

    public ConfigManager() {
        this(Paths.get(System.getProperty("user.home"), ".wxauto4j"));
    }

    public ConfigManager(Path configDir) {
        this.configDir = configDir;
        this.configFile = configDir.resolve("config.json");
        this.promptDir = configDir.resolve("prompt");
        this.memoryDir = configDir.resolve("memory");
        this.logsDir = configDir.resolve("logs");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    /**
     * 初始化配置管理器
     *
     * <p>创建必要的目录结构，加载或创建默认配置文件。</p>
     */
    public void init() {
        try {
            // 创建目录结构
            Files.createDirectories(configDir);
            Files.createDirectories(promptDir);
            Files.createDirectories(memoryDir);
            Files.createDirectories(logsDir);

            // 加载或创建配置
            if (Files.exists(configFile)) {
                load();
            } else {
                config = new AppConfig();
                save();
                LOG.info("已创建默认配置文件: " + configFile);
            }

            // 确保默认 Prompt 文件存在
            ensureDefaultPrompt();

        } catch (IOException e) {
            LOG.log(Level.SEVERE, "初始化配置失败", e);
            config = new AppConfig();
        }
    }

    /**
     * 从文件加载配置
     */
    public void load() {
        try {
            String json = Files.readString(configFile, StandardCharsets.UTF_8);
            config = gson.fromJson(json, AppConfig.class);
            if (config == null) {
                config = new AppConfig();
            }
            LOG.info("配置加载成功: " + configFile);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "加载配置失败，使用默认配置", e);
            config = new AppConfig();
        }
    }

    /**
     * 保存当前配置到文件
     */
    public void save() {
        try {
            String json = gson.toJson(config);
            Files.writeString(configFile, json, StandardCharsets.UTF_8);
            LOG.info("配置保存成功: " + configFile);
        } catch (IOException e) {
            LOG.log(Level.SEVERE, "保存配置失败", e);
        }
    }

    /**
     * 热重载配置（重新从文件加载）
     */
    public void reload() {
        load();
        LOG.info("配置热重载完成");
    }

    /**
     * 获取当前配置
     */
    public AppConfig getConfig() {
        if (config == null) {
            config = new AppConfig();
        }
        return config;
    }

    /**
     * 设置当前配置（不会自动保存到文件，需调用 {@link #save()}）
     */
    public void setConfig(AppConfig config) {
        this.config = config;
    }

    /**
     * 获取配置根目录
     */
    public Path getConfigDir() {
        return configDir;
    }

    /**
     * 获取 Prompt 目录
     */
    public Path getPromptDir() {
        return promptDir;
    }

    /**
     * 获取记忆目录
     */
    public Path getMemoryDir() {
        return memoryDir;
    }

    /**
     * 获取日志目录
     */
    public Path getLogsDir() {
        return logsDir;
    }

    /**
     * 确保默认 Prompt 文件存在
     */
    private void ensureDefaultPrompt() {
        Path defaultPromptFile = promptDir.resolve("默认.md");
        if (!Files.exists(defaultPromptFile)) {
            try {
                String defaultContent = "你是一个智能助手，通过微信与用户交流。请友好、专业地回答用户的问题。";
                Files.writeString(defaultPromptFile, defaultContent, StandardCharsets.UTF_8);
                LOG.info("已创建默认 Prompt 文件: " + defaultPromptFile);
            } catch (IOException e) {
                LOG.log(Level.WARNING, "创建默认 Prompt 文件失败", e);
            }
        }
    }
}
