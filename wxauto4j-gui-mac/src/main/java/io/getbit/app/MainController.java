package io.getbit.app;

import io.getbit.WeChat;
import io.getbit.app.bot.BotEngine;
import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ConfigManager;
import io.getbit.app.prompt.PromptManager;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.scene.text.Font;
import javafx.scene.text.FontWeight;

import java.net.URL;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 主界面控制器
 *
 * <p>处理导航、微信连接、机器人启停、日志显示等核心功能。
 * 左侧 TreeView 导航，右侧 StackPane 切换不同功能面板。</p>
 */
public class MainController implements Initializable {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ==================== FXML 绑定 ====================

    @FXML
    private Button btnConnect;

    @FXML
    private Button btnStartBot;

    @FXML
    private Label lblStatus;

    @FXML
    private Label lblBotStatus;

    @FXML
    private TreeView<String> navTree;

    @FXML
    private StackPane contentPane;

    @FXML
    private TextArea txtLog;

    @FXML
    private Button btnClearLog;

    // ==================== 核心组件 ====================

    /** 配置管理器 */
    private ConfigManager configManager;

    /** Prompt 管理器 */
    private PromptManager promptManager;

    /** 机器人引擎 */
    private BotEngine botEngine;

    /** 微信实例 */
    private WeChat weChat;

    /** 后台线程池 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wx-worker");
        t.setDaemon(true);
        return t;
    });

    /** 导航面板映射 */
    private final Map<String, Node> panels = new LinkedHashMap<>();

    /** 当前选中的面板 */
    private String currentPanel = "状态面板";

    // ==================== 初始化 ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化配置
        configManager = new ConfigManager();
        configManager.init();
        promptManager = new PromptManager(configManager.getPromptDir());

        // 初始化导航树
        initNavigation();

        // 初始化面板
        initPanels();

        // 默认显示状态面板
        showPanel("状态面板");

        appendLog("wxauto4j 已启动");
        appendLog("配置目录: " + configManager.getConfigDir());
    }

    /**
     * 初始化导航树
     */
    private void initNavigation() {
        TreeItem<String> root = new TreeItem<>("根节点");

        // 一级菜单
        TreeItem<String> statusItem = new TreeItem<>("📊 状态面板");
        TreeItem<String> chatItem = new TreeItem<>("💬 聊天");
        TreeItem<String> listenItem = new TreeItem<>("👂 私聊监听");
        TreeItem<String> groupItem = new TreeItem<>("👥 群组管理");
        TreeItem<String> promptItem = new TreeItem<>("📝 Prompt 管理");
        TreeItem<String> aiItem = new TreeItem<>("🤖 AI 接口");
        TreeItem<String> keywordItem = new TreeItem<>("🔑 关键词回复");
        TreeItem<String> forwardItem = new TreeItem<>("🔀 自定义转发");
        TreeItem<String> scheduleItem = new TreeItem<>("⏰ 定时消息");
        TreeItem<String> friendItem = new TreeItem<>("🤝 新好友");
        TreeItem<String> momentsItem = new TreeItem<>("🌸 朋友圈");
        TreeItem<String> memoryItem = new TreeItem<>("🧠 记忆管理");

        root.getChildren().addAll(
                statusItem, chatItem, listenItem, groupItem, promptItem,
                aiItem, keywordItem, forwardItem, scheduleItem, friendItem,
                momentsItem, memoryItem
        );

        // 展开所有一级
        root.setExpanded(true);
        statusItem.setExpanded(true);

        navTree.setRoot(root);

        // 点击事件
        navTree.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String text = newVal.getValue();
                // 去掉 emoji 前缀
                String panelName = text.replaceFirst("^[^\\s]+\\s+", "");
                showPanel(panelName);
            }
        });

        // 默认选中状态面板
        navTree.getSelectionModel().select(statusItem);
    }

    /**
     * 初始化所有面板
     */
    private void initPanels() {
        panels.put("状态面板", createStatusPanel());
        panels.put("聊天", createChatPanel());
        panels.put("私聊监听", createListenPanel());
        panels.put("群组管理", createGroupPanel());
        panels.put("Prompt 管理", createPromptPanel());
        panels.put("AI 接口", createAiPanel());
        panels.put("关键词回复", createKeywordPanel());
        panels.put("自定义转发", createForwardPanel());
        panels.put("定时消息", createSchedulePanel());
        panels.put("新好友", createFriendPanel());
        panels.put("朋友圈", createMomentsPanel());
        panels.put("记忆管理", createMemoryPanel());
    }

    /**
     * 切换面板
     */
    private void showPanel(String name) {
        Node panel = panels.get(name);
        if (panel != null) {
            contentPane.getChildren().setAll(panel);
            currentPanel = name;
        }
    }

    // ==================== 按钮事件 ====================

    /**
     * 连接/断开微信
     */
    @FXML
    private void onConnect() {
        if (weChat != null) {
            // 断开
            if (botEngine != null && botEngine.isRunning()) {
                botEngine.stop();
            }
            weChat = null;
            updateStatus(false);
            appendLog("已断开微信连接");
            return;
        }

        // 连接
        setBusy(true, "正在连接微信...");
        executor.submit(() -> {
            try {
                appendLog("正在初始化微信连接...");
                // 设置 SDK 日志回调，让内部日志输出到界面
                io.getbit.WeChat.setLogCallback(msg -> appendLog(msg));
                weChat = new WeChat();
                Platform.runLater(() -> {
                    updateStatus(true);
                    setBusy(false, "");
                    appendLog("已连接到微信，昵称: " + weChat.getNickname());
                });
            } catch (Exception e) {
                LOG.log(java.util.logging.Level.WARNING, "连接微信失败", e);
                Platform.runLater(() -> {
                    setBusy(false, "");
                    appendLog("连接微信失败: " + e.getMessage());
                    showError("连接失败", e.getMessage());
                });
            }
        });
    }

    /**
     * 启动/停止机器人
     */
    @FXML
    private void onStartBot() {
        if (weChat == null) {
            showError("未连接", "请先连接微信");
            return;
        }

        if (botEngine != null && botEngine.isRunning()) {
            // 停止
            botEngine.stop();
            btnStartBot.setText("启动机器人");
            lblBotStatus.setText("");
            appendLog("机器人已停止");
            return;
        }

        // 启动
        btnStartBot.setDisable(true);
        executor.submit(() -> {
            try {
                botEngine = new BotEngine(configManager, promptManager);
                botEngine.setLogCallback(this::appendLog);
                botEngine.start(weChat);
                Platform.runLater(() -> {
                    btnStartBot.setText("停止机器人");
                    btnStartBot.setDisable(false);
                    lblBotStatus.setText("🤖 机器人运行中");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    btnStartBot.setDisable(false);
                    showError("启动失败", e.getMessage());
                    appendLog("机器人启动失败: " + e.getMessage());
                });
            }
        });
    }

    /**
     * 清空日志
     */
    @FXML
    private void onClearLog() {
        txtLog.clear();
    }

    // ==================== 面板创建方法 ====================

    /**
     * 创建状态面板
     */
    private Node createStatusPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(16));

        Label title = new Label("运行状态");
        title.getStyleClass().add("section-title");

        Label lblWxStatus = new Label("微信状态: 未连接");
        Label lblBotRunStatus = new Label("机器人状态: 未启动");
        Label lblUptime = new Label("运行时长: -");
        Label lblMsgCount = new Label("消息统计: 接收 0 / 发送 0");

        // 刷新按钮
        Button btnRefresh = new Button("刷新状态");
        btnRefresh.getStyleClass().add("btn-default");
        btnRefresh.setOnAction(e -> {
            if (weChat != null) {
                lblWxStatus.setText("微信状态: 已连接 (" + weChat.getNickname() + ")");
            }
            if (botEngine != null && botEngine.isRunning()) {
                lblBotRunStatus.setText("机器人状态: 运行中");
                LocalDateTime start = botEngine.getStartTime();
                if (start != null) {
                    Duration d = Duration.between(start, LocalDateTime.now());
                    lblUptime.setText("运行时长: " + d.toHours() + "h " + (d.toMinutes() % 60) + "m");
                }
                lblMsgCount.setText("消息统计: 接收 " + botEngine.getTotalMessagesReceived()
                        + " / 发送 " + botEngine.getTotalMessagesSent());
            } else {
                lblBotRunStatus.setText("机器人状态: 未启动");
                lblUptime.setText("运行时长: -");
                lblMsgCount.setText("消息统计: 接收 0 / 发送 0");
            }
        });

        panel.getChildren().addAll(title, lblWxStatus, lblBotRunStatus, lblUptime, lblMsgCount, btnRefresh);
        return panel;
    }

    /**
     * 创建聊天面板
     */
    private Node createChatPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("手动聊天");
        title.getStyleClass().add("section-title");

        HBox recipientBox = new HBox(6);
        recipientBox.setAlignment(Pos.CENTER_LEFT);
        recipientBox.getChildren().addAll(new Label("发送给:"), createTextField("联系人/群名称", 300));

        TextArea txtMsg = new TextArea();
        txtMsg.setPromptText("输入消息... (Ctrl+Enter 发送)");
        txtMsg.setPrefRowCount(5);
        txtMsg.setWrapText(true);
        VBox.setVgrow(txtMsg, javafx.scene.layout.Priority.ALWAYS);

        HBox btnBox = new HBox(8);
        btnBox.setAlignment(Pos.CENTER_RIGHT);
        Button btnSend = new Button("发送");
        btnSend.getStyleClass().add("btn-success");
        btnSend.setOnAction(e -> {
            if (weChat == null) {
                showError("未连接", "请先连接微信");
                return;
            }
            // TODO: 获取收件人和消息内容并发送
            appendLog("发送消息功能待完善");
        });
        btnBox.getChildren().add(btnSend);

        panel.getChildren().addAll(title, recipientBox, txtMsg, btnBox);
        return panel;
    }

    /**
     * 创建私聊监听面板
     */
    private Node createListenPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("私聊监听配置");
        title.getStyleClass().add("section-title");

        CheckBox chkAllListen = new CheckBox("全局监听模式（黑名单）");
        CheckBox chkListenOnly = new CheckBox("只监听不回复");

        Label lblListenList = new Label("监听用户列表:");
        lblListenList.getStyleClass().add("sub-title");
        TextArea txtListenList = new TextArea();
        txtListenList.setPromptText("每行一个用户昵称");
        txtListenList.setPrefRowCount(8);

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setAllListenSwitch(chkAllListen.isSelected());
            cfg.setChatListenOnly(chkListenOnly.isSelected());
            String[] lines = txtListenList.getText().split("\n");
            List<String> list = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) list.add(trimmed);
            }
            cfg.setListenList(list);
            configManager.save();
            appendLog("私聊监听配置已保存");
        });

        panel.getChildren().addAll(title, chkAllListen, chkListenOnly, lblListenList, txtListenList, btnSave);
        return panel;
    }

    /**
     * 创建群组管理面板
     */
    private Node createGroupPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("群组管理配置");
        title.getStyleClass().add("section-title");

        CheckBox chkGroupSwitch = new CheckBox("群聊监听总开关");
        CheckBox chkGroupListenOnly = new CheckBox("群聊只监听不回复");
        CheckBox chkGroupReplyAt = new CheckBox("仅被 @ 时回复");
        CheckBox chkGroupReplyAtMsg = new CheckBox("回复时 @ 发言人");
        CheckBox chkGroupReplyQuote = new CheckBox("回复时引用原消息");
        CheckBox chkGroupWelcome = new CheckBox("开启群新人欢迎语");

        TextField txtWelcomeMsg = createTextField("欢迎语内容", 400);

        Label lblGroups = new Label("监听群组列表:");
        lblGroups.getStyleClass().add("sub-title");
        TextArea txtGroups = new TextArea();
        txtGroups.setPromptText("每行一个群聊名称");
        txtGroups.setPrefRowCount(6);

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setGroupSwitch(chkGroupSwitch.isSelected());
            cfg.setGroupListenOnly(chkGroupListenOnly.isSelected());
            cfg.setGroupReplyAt(chkGroupReplyAt.isSelected());
            cfg.setGroupReplyAtMsg(chkGroupReplyAtMsg.isSelected());
            cfg.setGroupReplyQuote(chkGroupReplyQuote.isSelected());
            cfg.setGroupWelcome(chkGroupWelcome.isSelected());
            cfg.setGroupWelcomeMsg(txtWelcomeMsg.getText());
            String[] lines = txtGroups.getText().split("\n");
            List<String> list = new ArrayList<>();
            for (String line : lines) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) list.add(trimmed);
            }
            cfg.setGroup(list);
            configManager.save();
            appendLog("群组管理配置已保存");
        });

        panel.getChildren().addAll(title, chkGroupSwitch, chkGroupListenOnly, chkGroupReplyAt,
                chkGroupReplyAtMsg, chkGroupReplyQuote, chkGroupWelcome, txtWelcomeMsg,
                lblGroups, txtGroups, btnSave);
        return panel;
    }

    /**
     * 创建 Prompt 管理面板
     */
    private Node createPromptPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("Prompt 管理");
        title.getStyleClass().add("section-title");

        HBox listBox = new HBox(10);
        ListView<String> promptList = new ListView<>();
        promptList.setPrefWidth(200);
        promptList.setPrefHeight(400);

        VBox editBox = new VBox(8);
        HBox.setHgrow(editBox, javafx.scene.layout.Priority.ALWAYS);
        TextField txtPromptName = createTextField("Prompt 名称", 200);
        TextArea txtPromptContent = new TextArea();
        txtPromptContent.setPromptText("Prompt 内容...");
        txtPromptContent.setWrapText(true);
        VBox.setVgrow(txtPromptContent, javafx.scene.layout.Priority.ALWAYS);

        HBox btnBox = new HBox(8);
        Button btnNew = new Button("新建");
        Button btnLoad = new Button("加载");
        Button btnSavePrompt = new Button("保存");
        Button btnDelete = new Button("删除");
        btnNew.getStyleClass().add("btn-default");
        btnLoad.getStyleClass().add("btn-default");
        btnSavePrompt.getStyleClass().add("btn-success");
        btnDelete.getStyleClass().add("btn-danger");

        btnNew.setOnAction(e -> {
            txtPromptName.clear();
            txtPromptContent.clear();
        });
        btnLoad.setOnAction(e -> {
            String selected = promptList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                txtPromptName.setText(selected);
                String content = promptManager.readPrompt(selected);
                txtPromptContent.setText(content != null ? content : "");
            }
        });
        btnSavePrompt.setOnAction(e -> {
            String name = txtPromptName.getText().trim();
            if (!name.isEmpty()) {
                promptManager.writePrompt(name, txtPromptContent.getText());
                refreshPromptList(promptList);
                appendLog("Prompt 已保存: " + name);
            }
        });
        btnDelete.setOnAction(e -> {
            String selected = promptList.getSelectionModel().getSelectedItem();
            if (selected != null) {
                promptManager.deletePrompt(selected);
                refreshPromptList(promptList);
                appendLog("Prompt 已删除: " + selected);
            }
        });

        btnBox.getChildren().addAll(btnNew, btnLoad, btnSavePrompt, btnDelete);
        editBox.getChildren().addAll(txtPromptName, txtPromptContent, btnBox);
        listBox.getChildren().addAll(promptList, editBox);

        panel.getChildren().addAll(title, listBox);

        // 初始加载
        refreshPromptList(promptList);
        return panel;
    }

    /**
     * 创建 AI 接口面板
     */
    private Node createAiPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("AI 接口配置");
        title.getStyleClass().add("section-title");

        Label lblApiList = new Label("接口列表（当前仅支持 OpenAI 兼容格式）:");
        lblApiList.getStyleClass().add("sub-title");

        TextArea txtApiConfigs = new TextArea();
        txtApiConfigs.setPromptText("每行一个接口配置，格式: API_KEY|BASE_URL|MODEL\n例: sk-xxx|https://api.openai.com/v1|gpt-4o");
        txtApiConfigs.setPrefRowCount(6);

        HBox indexBox = new HBox(6);
        indexBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtApiIndex = createTextField("默认接口索引 (0开始)", 100);

        HBox btnBox = new HBox(8);
        Button btnSave = new Button("保存");
        Button btnTest = new Button("测试接口");
        btnSave.getStyleClass().add("btn-success");
        btnTest.getStyleClass().add("btn-default");

        btnSave.setOnAction(e -> {
            appendLog("AI 接口配置已保存");
            configManager.save();
        });
        btnTest.setOnAction(e -> {
            appendLog("接口测试功能待实现");
        });

        btnBox.getChildren().addAll(btnSave, btnTest);
        panel.getChildren().addAll(title, lblApiList, txtApiConfigs, indexBox, btnBox);
        return panel;
    }

    /**
     * 创建关键词回复面板
     */
    private Node createKeywordPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("关键词回复配置");
        title.getStyleClass().add("section-title");

        CheckBox chkChatKeyword = new CheckBox("开启私聊关键词回复");
        CheckBox chkGroupKeyword = new CheckBox("开启群聊关键词回复");
        CheckBox chkGroupAtOnly = new CheckBox("群聊仅 @ 时触发");

        Label lblKeywords = new Label("关键词字典（每行格式: 关键词=回复内容）:");
        lblKeywords.getStyleClass().add("sub-title");
        TextArea txtKeywords = new TextArea();
        txtKeywords.setPromptText("你好=你好，有什么可以帮你的？\n天气=今天天气不错哦");
        txtKeywords.setPrefRowCount(8);

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setChatKeywordSwitch(chkChatKeyword.isSelected());
            cfg.setGroupKeywordSwitch(chkGroupKeyword.isSelected());
            cfg.setGroupKeywordAtOnly(chkGroupAtOnly.isSelected());
            Map<String, String> dict = new HashMap<>();
            String[] lines = txtKeywords.getText().split("\n");
            for (String line : lines) {
                String[] parts = line.split("=", 2);
                if (parts.length == 2) {
                    dict.put(parts[0].trim(), parts[1].trim());
                }
            }
            cfg.setKeywordDict(dict);
            configManager.save();
            appendLog("关键词回复配置已保存");
        });

        panel.getChildren().addAll(title, chkChatKeyword, chkGroupKeyword, chkGroupAtOnly, lblKeywords, txtKeywords, btnSave);
        return panel;
    }

    /**
     * 创建自定义转发面板
     */
    private Node createForwardPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("自定义规则转发");
        title.getStyleClass().add("section-title");

        CheckBox chkForward = new CheckBox("开启自定义转发");

        Label lblInfo = new Label("转发规则请在 config.json 中配置，GUI 编辑功能开发中...");
        lblInfo.getStyleClass().add("sub-title");

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setCustomForwardSwitch(chkForward.isSelected());
            configManager.save();
            appendLog("转发配置已保存");
        });

        panel.getChildren().addAll(title, chkForward, lblInfo, btnSave);
        return panel;
    }

    /**
     * 创建定时消息面板
     */
    private Node createSchedulePanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("定时/随机消息");
        title.getStyleClass().add("section-title");

        CheckBox chkScheduled = new CheckBox("开启定时消息");
        CheckBox chkRandom = new CheckBox("开启随机消息");
        CheckBox chkDailyStartStop = new CheckBox("开启每日定时启停");

        HBox timeBox = new HBox(10);
        timeBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtStartTime = createTextField("启动时间 (HH:MM)", 100);
        TextField txtStopTime = createTextField("停止时间 (HH:MM)", 100);
        timeBox.getChildren().addAll(new Label("启动:"), txtStartTime, new Label("停止:"), txtStopTime);

        Label lblInfo = new Label("定时任务详细配置请在 config.json 中编辑，GUI 编辑功能开发中...");
        lblInfo.getStyleClass().add("sub-title");

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setScheduledMsgSwitch(chkScheduled.isSelected());
            cfg.setRandomMsgSwitch(chkRandom.isSelected());
            cfg.setEverydayStartStopBotSwitch(chkDailyStartStop.isSelected());
            cfg.setEverydayStartBotTime(txtStartTime.getText());
            cfg.setEverydayStopBotTime(txtStopTime.getText());
            configManager.save();
            appendLog("定时消息配置已保存");
        });

        panel.getChildren().addAll(title, chkScheduled, chkRandom, chkDailyStartStop, timeBox, lblInfo, btnSave);
        return panel;
    }

    /**
     * 创建新好友面板
     */
    private Node createFriendPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("新好友管理");
        title.getStyleClass().add("section-title");

        CheckBox chkAutoAccept = new CheckBox("自动通过好友请求");
        CheckBox chkAutoGreet = new CheckBox("通过后自动打招呼");

        TextField txtPrefix = createTextField("备注前缀", 150);
        TextField txtSuffix = createTextField("备注后缀", 150);

        HBox intervalBox = new HBox(10);
        intervalBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtMinInterval = createTextField("最小间隔(秒)", 80);
        TextField txtMaxInterval = createTextField("最大间隔(秒)", 80);
        intervalBox.getChildren().addAll(new Label("检查间隔:"), txtMinInterval, new Label("~"), txtMaxInterval);

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setNewFriendSwitch(chkAutoAccept.isSelected());
            cfg.setNewFriendReplySwitch(chkAutoGreet.isSelected());
            cfg.setNewFriendRemarkPrefix(txtPrefix.getText());
            cfg.setNewFriendRemarkSuffix(txtSuffix.getText());
            try { cfg.setNewFriendCheckMin(Integer.parseInt(txtMinInterval.getText())); } catch (NumberFormatException ex) {}
            try { cfg.setNewFriendCheckMax(Integer.parseInt(txtMaxInterval.getText())); } catch (NumberFormatException ex) {}
            configManager.save();
            appendLog("新好友配置已保存");
        });

        panel.getChildren().addAll(title, chkAutoAccept, chkAutoGreet, txtPrefix, txtSuffix, intervalBox, btnSave);
        return panel;
    }

    /**
     * 创建朋友圈面板
     */
    private Node createMomentsPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("朋友圈管理");
        title.getStyleClass().add("section-title");

        CheckBox chkLike = new CheckBox("开启随机点赞");
        CheckBox chkScheduledMoments = new CheckBox("开启定时朋友圈");
        CheckBox chkRandomMoments = new CheckBox("开启随机朋友圈");

        HBox likeIntervalBox = new HBox(10);
        likeIntervalBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtLikeMin = createTextField("最小间隔(分钟)", 100);
        TextField txtLikeMax = createTextField("最大间隔(分钟)", 100);
        likeIntervalBox.getChildren().addAll(new Label("点赞间隔:"), txtLikeMin, new Label("~"), txtLikeMax);

        Label lblInfo = new Label("朋友圈任务详细配置请在 config.json 中编辑");
        lblInfo.getStyleClass().add("sub-title");

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setMomentsLikeSwitch(chkLike.isSelected());
            cfg.setScheduledMomentsSwitch(chkScheduledMoments.isSelected());
            cfg.setRandomMomentsSwitch(chkRandomMoments.isSelected());
            try { cfg.setMomentsLikeMin(Integer.parseInt(txtLikeMin.getText())); } catch (NumberFormatException ex) {}
            try { cfg.setMomentsLikeMax(Integer.parseInt(txtLikeMax.getText())); } catch (NumberFormatException ex) {}
            configManager.save();
            appendLog("朋友圈配置已保存");
        });

        panel.getChildren().addAll(title, chkLike, chkScheduledMoments, chkRandomMoments, likeIntervalBox, lblInfo, btnSave);
        return panel;
    }

    /**
     * 创建记忆管理面板
     */
    private Node createMemoryPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("对话记忆管理");
        title.getStyleClass().add("section-title");

        CheckBox chkMemory = new CheckBox("开启对话记忆");

        HBox countBox = new HBox(10);
        countBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtMaxCount = createTextField("最大存储条数", 100);
        TextField txtContextCount = createTextField("AI 带入条数", 100);
        countBox.getChildren().addAll(new Label("最大存储:"), txtMaxCount, new Label("AI 带入:"), txtContextCount);

        ListView<String> memoryList = new ListView<>();
        memoryList.setPrefHeight(200);

        HBox btnBox = new HBox(8);
        Button btnRefresh = new Button("刷新列表");
        Button btnClearOne = new Button("清除选中");
        Button btnClearAll = new Button("清除全部");
        btnRefresh.getStyleClass().add("btn-default");
        btnClearOne.getStyleClass().add("btn-danger");
        btnClearAll.getStyleClass().add("btn-danger");

        btnRefresh.setOnAction(e -> {
            if (botEngine != null && botEngine.getMemoryManager() != null) {
                List<String> names = botEngine.getMemoryManager().listChatNames();
                memoryList.getItems().setAll(names);
            }
        });
        btnClearOne.setOnAction(e -> {
            String selected = memoryList.getSelectionModel().getSelectedItem();
            if (selected != null && botEngine != null && botEngine.getMemoryManager() != null) {
                botEngine.getMemoryManager().clearMemory(selected);
                memoryList.getItems().remove(selected);
                appendLog("已清除记忆: " + selected);
            }
        });
        btnClearAll.setOnAction(e -> {
            if (botEngine != null && botEngine.getMemoryManager() != null) {
                botEngine.getMemoryManager().clearAllMemory();
                memoryList.getItems().clear();
                appendLog("已清除全部记忆");
            }
        });

        Button btnSave = new Button("保存配置");
        btnSave.getStyleClass().add("btn-success");
        btnSave.setOnAction(e -> {
            AppConfig cfg = configManager.getConfig();
            cfg.setMemorySwitch(chkMemory.isSelected());
            try { cfg.setMemoryMaxCount(Integer.parseInt(txtMaxCount.getText())); } catch (NumberFormatException ex) {}
            try { cfg.setMemoryContextCount(Integer.parseInt(txtContextCount.getText())); } catch (NumberFormatException ex) {}
            configManager.save();
            appendLog("记忆配置已保存");
        });

        btnBox.getChildren().addAll(btnRefresh, btnClearOne, btnClearAll, btnSave);
        panel.getChildren().addAll(title, chkMemory, countBox, memoryList, btnBox);
        return panel;
    }

    // ==================== 辅助方法 ====================

    private TextField createTextField(String prompt, double prefWidth) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setPrefWidth(prefWidth);
        return tf;
    }

    private void refreshPromptList(ListView<String> listView) {
        listView.getItems().setAll(promptManager.listPromptNames());
    }

    private void updateStatus(boolean connected) {
        if (connected) {
            lblStatus.setText("● 已连接");
            lblStatus.getStyleClass().removeAll("status-disconnected");
            lblStatus.getStyleClass().add("status-connected");
            btnConnect.setText("断开");
            btnStartBot.setDisable(false);
        } else {
            lblStatus.setText("○ 未连接");
            lblStatus.getStyleClass().removeAll("status-connected");
            lblStatus.getStyleClass().add("status-disconnected");
            btnConnect.setText("连接微信");
            btnStartBot.setDisable(true);
            btnStartBot.setText("启动机器人");
            lblBotStatus.setText("");
        }
    }

    private void setBusy(boolean busy, String text) {
        if (busy) {
            lblStatus.setText("⏳ " + text);
            btnConnect.setDisable(true);
        } else {
            btnConnect.setDisable(false);
            updateStatus(weChat != null);
        }
    }

    private void appendLog(String message) {
        String timestamp = LocalDateTime.now().format(TIME_FMT);
        String logLine = "[" + timestamp + "] " + message + "\n";
        Platform.runLater(() -> {
            txtLog.appendText(logLine);
            // 滚动到底部
            txtLog.setScrollTop(Double.MAX_VALUE);
        });
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content != null ? content : "未知错误");
        alert.showAndWait();
    }
}

