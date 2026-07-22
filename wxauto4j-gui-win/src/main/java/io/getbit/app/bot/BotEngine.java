package io.getbit.app.bot;

import io.getbit.Chat;
import io.getbit.WeChat;
import io.getbit.app.ai.AiClient;
import io.getbit.app.ai.MemoryMessage;
import io.getbit.app.ai.OpenAiClient;
import io.getbit.app.config.ApiConfig;
import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ConfigManager;
import io.getbit.app.prompt.PromptManager;
import io.getbit.elements.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 机器人核心引擎
 *
 * <p>负责：</p>
 * <ul>
 *   <li>启动/停止消息监听循环</li>
 *   <li>管理白名单/黑名单模式</li>
 *   <li>调度消息处理链</li>
 *   <li>管理 AI 客户端实例</li>
 *   <li>每日定时启停</li>
 * </ul>
 */
public class BotEngine {

    private static final Logger LOG = Logger.getLogger(BotEngine.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");

    private final ConfigManager configManager;
    private final PromptManager promptManager;

    /** 微信实例 */
    private WeChat weChat;

    /** 配置 */
    private AppConfig config;

    /** 记忆管理器 */
    private MemoryManager memoryManager;

    /** 消息处理器 */
    private MessageHandler messageHandler;

    /** 转发引擎 */
    private ForwardEngine forwardEngine;

    /** 新好友处理器 */
    private NewFriendHandler newFriendHandler;

    /** 调度引擎 */
    private SchedulerEngine schedulerEngine;

    /** 朋友圈处理器 */
    private MomentsHandler momentsHandler;

    /** AI 客户端缓存（按接口索引） */
    private final List<OpenAiClient> aiClients = new CopyOnWriteArrayList<>();

    /** 监听轮询线程 */
    private ScheduledExecutorService listenExecutor;

    /** 每日启停调度器 */
    private ScheduledExecutorService dailyExecutor;

    /** 是否运行中 */
    private volatile boolean running = false;

    /** 启动时间 */
    private LocalDateTime startTime;

    /** 日志回调 */
    private Consumer<String> logCallback;

    /** 消息统计 */
    private volatile int totalMessagesReceived = 0;
    private volatile int totalMessagesSent = 0;

    public BotEngine(ConfigManager configManager, PromptManager promptManager) {
        this.configManager = configManager;
        this.promptManager = promptManager;
        this.config = configManager.getConfig();
    }

    /**
     * 设置日志回调
     */
    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    /**
     * 启动机器人
     */
    public synchronized void start(WeChat weChat) {
        if (running) {
            log("机器人已在运行中");
            return;
        }

        this.weChat = weChat;
        this.config = configManager.getConfig();
        this.startTime = LocalDateTime.now();

        log("正在启动机器人...");

        // 初始化 AI 客户端
        initAiClients();

        // 初始化记忆管理器
        String wxId = weChat.getNickname(); // 简化处理，用昵称作为目录标识
        memoryManager = new MemoryManager(configManager.getMemoryDir(), config, wxId);

        // 初始化消息处理器
        messageHandler = new MessageHandler(config, weChat, memoryManager, promptManager);
        messageHandler.setAiClientProvider(this::resolveAiClient);

        // 初始化转发引擎
        forwardEngine = new ForwardEngine(config, weChat);

        // 初始化新好友处理器
        newFriendHandler = new NewFriendHandler(config, weChat);

        // 初始化调度引擎
        schedulerEngine = new SchedulerEngine(config, weChat);

        // 初始化朋友圈处理器
        momentsHandler = new MomentsHandler(config, weChat);

        // 启动消息监听
        startListening();

        // 启动新好友检查
        if (config.isNewFriendSwitch()) {
            newFriendHandler.start();
        }

        // 启动定时任务调度
        schedulerEngine.start();

        // 启动朋友圈任务
        momentsHandler.start();

        // 启动每日启停调度
        if (config.isEverydayStartStopBotSwitch()) {
            startDailySchedule();
        }

        running = true;
        log("机器人启动成功 [" + startTime.format(TIME_FMT) + "]");
    }

    /**
     * 停止机器人
     */
    public synchronized void stop() {
        if (!running) return;

        log("正在停止机器人...");

        // 停止消息监听
        stopListening();

        // 停止新好友检查
        if (newFriendHandler != null) {
            newFriendHandler.stop();
        }

        // 停止定时任务
        if (schedulerEngine != null) {
            schedulerEngine.stop();
        }

        // 停止朋友圈任务
        if (momentsHandler != null) {
            momentsHandler.stop();
        }

        // 停止每日启停
        stopDailySchedule();

        running = false;
        log("机器人已停止");
    }

    /**
     * 重新加载配置并重新初始化
     */
    public void reload() {
        log("正在热重载配置...");
        configManager.reload();
        this.config = configManager.getConfig();

        if (running) {
            stop();
            start(weChat);
        }
        log("配置热重载完成");
    }

    /**
     * 是否正在运行
     */
    public boolean isRunning() {
        return running;
    }

    /**
     * 获取启动时间
     */
    public LocalDateTime getStartTime() {
        return startTime;
    }

    /**
     * 获取接收消息总数
     */
    public int getTotalMessagesReceived() {
        return totalMessagesReceived;
    }

    /**
     * 获取发送消息总数
     */
    public int getTotalMessagesSent() {
        return totalMessagesSent;
    }

    /**
     * 获取记忆管理器
     */
    public MemoryManager getMemoryManager() {
        return memoryManager;
    }

    // ==================== 内部方法 ====================

    /**
     * 初始化 AI 客户端列表
     */
    private void initAiClients() {
        aiClients.clear();
        List<ApiConfig> apiConfigs = config.getApiConfigs();
        for (ApiConfig apiConfig : apiConfigs) {
            aiClients.add(new OpenAiClient(apiConfig));
        }
        log("已初始化 " + aiClients.size() + " 个 AI 接口");
    }

    /**
     * 根据聊天窗口解析 AI 客户端
     */
    private AiClient resolveAiClient(String chatName, boolean isGroup) {
        Map<String, Integer> apiMap = isGroup ? config.getGroupApiMap() : config.getChatApiMap();
        if (apiMap != null && apiMap.containsKey(chatName)) {
            int index = apiMap.get(chatName);
            if (index >= 0 && index < aiClients.size()) {
                return aiClients.get(index);
            }
        }
        // 使用默认接口
        int defaultIndex = config.getApiIndex();
        if (defaultIndex >= 0 && defaultIndex < aiClients.size()) {
            return aiClients.get(defaultIndex);
        }
        return null;
    }

    /**
     * 启动消息监听循环
     */
    private void startListening() {
        if (listenExecutor != null) {
            listenExecutor.shutdown();
        }
        listenExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-listen");
            t.setDaemon(true);
            return t;
        });

        listenExecutor.scheduleAtFixedRate(this::pollMessages, 2, 2, TimeUnit.SECONDS);
        log("消息监听已启动");
    }

    /**
     * 停止消息监听
     */
    private void stopListening() {
        if (listenExecutor != null) {
            listenExecutor.shutdown();
            listenExecutor = null;
        }
    }

    /**
     * 轮询新消息
     */
    private void pollMessages() {
        if (!running || weChat == null) return;

        try {
            // 获取当前会话列表
            List<String> sessions = weChat.GetSessionList();

            // 根据监听模式过滤
            List<String> targets = filterListenTargets(sessions);

            for (String session : targets) {
                try {
                    weChat.ChatWith(session, false);
                    Thread.sleep(300);

                    List<Message> messages = weChat.GetAllMessage();
                    for (Message msg : messages) {
                        if (msg.isFriend()) {
                            totalMessagesReceived++;
                            boolean isGroup = isGroupChat(session);
                            Chat chat = weChat.GetSubWindow(session);
                            messageHandler.handle(msg, session, chat, isGroup);

                            // 自定义转发
                            if (config.isCustomForwardSwitch() && forwardEngine != null) {
                                forwardEngine.process(msg, session, isGroup);
                            }
                        }
                    }
                } catch (Exception e) {
                    // 单个会话失败不影响其他
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "消息轮询异常", e);
        }
    }

    /**
     * 根据监听模式过滤目标会话
     */
    private List<String> filterListenTargets(List<String> sessions) {
        if (config.isAllListenSwitch()) {
            // 黑名单模式：全局监听，过滤免打扰
            return new ArrayList<>(sessions);
        } else {
            // 白名单模式：只监听配置的
            List<String> targets = new ArrayList<>();
            for (String session : sessions) {
                if (config.getListenList().contains(session)) {
                    targets.add(session);
                }
                if (config.isGroupSwitch() && config.getGroup().contains(session)) {
                    targets.add(session);
                }
            }
            return targets;
        }
    }

    /**
     * 判断是否为群聊（简单判断：在群组列表中）
     */
    private boolean isGroupChat(String sessionName) {
        return config.getGroup().contains(sessionName);
    }

    /**
     * 启动每日启停调度
     */
    private void startDailySchedule() {
        if (dailyExecutor != null) {
            dailyExecutor.shutdown();
        }
        dailyExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "bot-daily");
            t.setDaemon(true);
            return t;
        });

        // 每分钟检查一次是否到达启停时间
        dailyExecutor.scheduleAtFixedRate(this::checkDailySchedule, 1, 1, TimeUnit.MINUTES);
        log("每日启停调度已启动");
    }

    /**
     * 停止每日启停调度
     */
    private void stopDailySchedule() {
        if (dailyExecutor != null) {
            dailyExecutor.shutdown();
            dailyExecutor = null;
        }
    }

    /**
     * 检查每日启停时间
     */
    private void checkDailySchedule() {
        try {
            String now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
            String startTime = config.getEverydayStartBotTime();
            String stopTime = config.getEverydayStopBotTime();

            if (now.equals(startTime) && !running) {
                log("到达每日启动时间: " + startTime);
                start(weChat);
            } else if (now.equals(stopTime) && running) {
                log("到达每日停止时间: " + stopTime);
                stop();
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "每日启停检查异常", e);
        }
    }

    /**
     * 输出日志
     */
    private void log(String message) {
        LOG.info(message);
        if (logCallback != null) {
            logCallback.accept(message);
        }
    }
}
