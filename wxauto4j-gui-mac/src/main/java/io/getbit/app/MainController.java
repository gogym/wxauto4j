package io.getbit.app;

import io.getbit.app.config.AppConfig;
import io.getbit.app.config.AtReminderRule;
import io.getbit.app.config.ConfigManager;
import io.getbit.app.bot.AtReminderTracker;
import io.getbit.wxdb.WeChatDB;
import io.getbit.wxdb.WeChatDBConfig;
import io.getbit.wxdb.frida.FridaKeyExtractor;
import io.getbit.wxdb.model.ChatMessage;
import io.getbit.wxdb.model.Contact;
import io.getbit.wxdb.monitor.MessageMonitor;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.*;
import javafx.scene.layout.*;

import java.net.URL;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Logger;

/**
 * 主界面控制器
 *
 * <p>处理导航、配置管理、密钥提取、日志显示等功能。</p>
 */
public class MainController implements Initializable {

    private static final Logger LOG = Logger.getLogger(MainController.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ==================== FXML 绑定 ====================

    @FXML
    private Label lblStatus;

    @FXML
    private TreeView<String> navTree;

    @FXML
    private ScrollPane contentScrollPane;

    @FXML
    private TextArea txtLog;

    @FXML
    private Button btnClearLog;

    // ==================== 核心组件 ====================

    /** 配置管理器 */
    private ConfigManager configManager;

    /** Prompt 管理器 */

    /** 后台线程池 */
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "wx-worker");
        t.setDaemon(true);
        return t;
    });

    /** 导航面板映射 */
    private final Map<String, Node> panels = new LinkedHashMap<>();

    /** 当前选中的面板 */
    private String currentPanel = "状态面板";

    /** 微信数据库 */
    private WeChatDB weChatDB;

    /** 消息监听器 */
    private MessageMonitor messageMonitor;

    /** 监听面板的消息显示区 */
    private TextArea txtMonitorMessages;

    /** 监听面板的监听列表 */
    private ListView<String> lstMonitoredContacts;

    /** 群聊监听面板的消息显示区 */
    private TextArea txtGroupMessages;

    /** 群聊监听面板的监听列表 */
    private ListView<String> lstMonitoredGroups;
    private ComboBox<String> cbSourceGroupRef;

    /** 密钥输入框 */
    private TextField txtKey;

    /** 密钥状态标签 */
    private Label lblDbKeyStatus;

    /** 开始监听时自动选中联系人，跳过历史消息加载 */
    private volatile boolean skipHistoryLoad = false;

    /** 群聊监听时跳过历史消息加载 */
    private volatile boolean skipGroupHistoryLoad = false;

    /** 每个聊天的消息缓冲区，支持多聊天同时监听 */
    private final Map<String, StringBuilder> chatBuffers = new java.util.concurrent.ConcurrentHashMap<>();

    /** 每个缓冲区最大保留行数，超出后截掉旧内容防止内存膨胀 */
    private static final int MAX_BUFFER_LINES = 500;

    /** TextArea 追加计数器，用于定期裁剪 */
    private int privateMsgCount = 0;
    private int groupMsgCount = 0;

    /** @提醒追踪器 */
    private AtReminderTracker atReminderTracker;

    /** 当前私聊面板选中的 username */
    private volatile String selectedPrivateChat = null;

    /** 当前群聊面板选中的 username */
    private volatile String selectedGroupChat = null;

    // ==================== 初始化 ====================

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        // 初始化配置
        configManager = new ConfigManager();
        configManager.init();

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
        TreeItem<String> listenItem = new TreeItem<>("👂 私聊监听");
        TreeItem<String> groupListenItem = new TreeItem<>("💬 群聊监听");
        TreeItem<String> groupItem = new TreeItem<>("👥 群组管理");

        root.getChildren().addAll(statusItem, listenItem, groupListenItem, groupItem);

        // 展开所有
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
        panels.put("私聊监听", createListenPanel());
        panels.put("群聊监听", createGroupListenPanel());
        panels.put("群组管理", createGroupPanel());
    }

    /**
     * 切换面板
     */
    private void showPanel(String name) {
        Node panel = panels.get(name);
        if (panel != null) {
            contentScrollPane.setContent(panel);
            currentPanel = name;
        }
    }

    // ==================== 按钮事件 ====================

    /**
     * 清空日志
     */
    @FXML
    private void onClearLog() {
        txtLog.clear();
    }

    // ==================== 面板创建方法 ====================

    /**
     * 创建状态面板（包含数据库密钥管理）
     */
    private Node createStatusPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(16));

        // ===== 运行状态区域 =====
        Label title = new Label("运行状态");
        title.getStyleClass().add("section-title");

        Label lblConfigStatus = new Label("配置状态: 已加载");
        Label lblConfigDir = new Label("配置目录: " + configManager.getConfigDir());

        // ===== 数据库密钥区域 =====
        Label dbKeyTitle = new Label("🔐 数据库密钥");
        dbKeyTitle.getStyleClass().add("section-title");

        Label desc = new Label("点击「一键初始化」，自动完成：创建微信无签名副本 → 启动微信 → 扫码登录 → 提取密钥 → 保存配置");
        desc.setWrapText(true);
        desc.getStyleClass().add("sub-title");

        // 密钥状态
        lblDbKeyStatus = new Label("当前状态: ");
        String currentKey = configManager.getConfig().getWxRawKey();
        if (currentKey != null && currentKey.length() == 64) {
            lblDbKeyStatus.setText("当前状态: ✅ 已配置 (" + currentKey.substring(0, 8) + "...)");
        } else {
            lblDbKeyStatus.setText("当前状态: ❌ 未配置");
        }

        // 密钥输入框
        Label lblKey = new Label("Raw Key:");
        txtKey = new TextField();
        txtKey.setPromptText("64位 hex 密钥，可通过 Frida 自动提取或手动填入");
        txtKey.setPrefWidth(500);
        if (currentKey != null && !currentKey.isEmpty()) {
            txtKey.setText(currentKey);
        }

        // 按钮区
        HBox btnBox = new HBox(8);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        Button btnOneClick = new Button("🚀 一键初始化");
        btnOneClick.getStyleClass().add("btn-primary");
        Button btnSaveKey = new Button("💾 保存密钥");
        btnSaveKey.getStyleClass().add("btn-success");
        Button btnDecrypt = new Button("🔓 测试数据库连接");
        btnDecrypt.getStyleClass().add("btn-success");

        // 刷新按钮
        Button btnRefresh = new Button("🔄 刷新状态");
        btnRefresh.getStyleClass().add("btn-default");
        btnRefresh.setOnAction(e -> {
            lblConfigStatus.setText("配置状态: 已加载");
            lblConfigDir.setText("配置目录: " + configManager.getConfigDir());
            String key = configManager.getConfig().getWxRawKey();
            if (key != null && key.length() == 64) {
                lblDbKeyStatus.setText("当前状态: ✅ 已配置 (" + key.substring(0, 8) + "...)");
                txtKey.setText(key);
            } else {
                lblDbKeyStatus.setText("当前状态: ❌ 未配置");
            }
        });

        // ===== 一键初始化按钮事件 =====
        btnOneClick.setOnAction(e -> {
            btnOneClick.setDisable(true);
            btnOneClick.setText("⏳ 正在初始化...");
            appendLog("🚀 开始一键初始化流程...");

            // 立即弹出等待窗口（非模态 Stage，可程序控制关闭）
            final javafx.stage.Stage[] waitingStage = new javafx.stage.Stage[1];
            final Label[] waitingHeader = new Label[1];
            final Label[] waitingContent = new Label[1];
            final boolean[] cancelled = {false};
            Platform.runLater(() -> {
                javafx.stage.Stage stage = new javafx.stage.Stage();
                stage.setTitle("一键初始化");
                stage.initModality(javafx.stage.Modality.NONE);
                stage.setResizable(false);

                Label header = new Label("正在初始化，请稍候...");
                header.setStyle("-fx-font-size: 14px; -fx-font-weight: bold;");
                Label content = new Label("正在创建微信无签名副本并启动微信，\n完成后请扫码登录。\n密钥提取成功后将自动关闭此窗口。");
                content.setWrapText(true);
                content.setStyle("-fx-font-size: 12px;");

                VBox box = new VBox(10, header, content);
                box.setPadding(new Insets(20));
                box.setAlignment(Pos.CENTER_LEFT);
                stage.setScene(new javafx.scene.Scene(box, 380, 150));

                // 用户手动关闭窗口时，中断流程
                stage.setOnCloseRequest(event -> {
                    cancelled[0] = true;
                    appendLog("⚠️ 用户取消了初始化流程");
                    FridaKeyExtractor.cleanupFridaProcesses();
                    Platform.runLater(() -> {
                        btnOneClick.setDisable(false);
                        btnOneClick.setText("🚀 一键初始化");
                    });
                });

                stage.show();

                waitingStage[0] = stage;
                waitingHeader[0] = header;
                waitingContent[0] = content;
            });

            executor.submit(() -> {
                try {
                    // ===== 步骤1：创建无签名副本 =====
                    if (cancelled[0]) { resetButton(btnOneClick); return; }
                    String copyBin = "/tmp/WeChat_copy.app/Contents/MacOS/WeChat";
                    java.io.File copyFile = new java.io.File(copyBin);

                    if (!copyFile.exists()) {
                        if (cancelled[0]) return;
                        String[] candidates = {
                                "/Applications/WeChat.app",
                                "/Applications/微信.app",
                                System.getProperty("user.home") + "/Applications/WeChat.app"
                        };
                        String originalApp = null;
                        for (String path : candidates) {
                            if (new java.io.File(path).exists()) {
                                originalApp = path;
                                break;
                            }
                        }
                        if (originalApp == null) {
                            Platform.runLater(() -> {
                                if (waitingStage[0] != null) waitingStage[0].close();
                                btnOneClick.setDisable(false);
                                btnOneClick.setText("🚀 一键初始化");
                                appendLog("❌ 未找到微信安装");
                                showError("初始化失败", "未找到微信安装，请确保已安装微信");
                            });
                            return;
                        }

                        Platform.runLater(() -> appendLog("📦 [1/5] 正在拷贝微信到 /tmp 并移除签名（约需1分钟）..."));

                        new ProcessBuilder("rm", "-rf", "/tmp/WeChat_copy.app").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                        Process cpProc = new ProcessBuilder("cp", "-R", originalApp, "/tmp/WeChat_copy.app").start();
                        cpProc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                        if (cpProc.exitValue() != 0) throw new RuntimeException("拷贝微信失败");

                        Process signProc = new ProcessBuilder("codesign", "--remove-signature", "/tmp/WeChat_copy.app").start();
                        signProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                        if (signProc.exitValue() != 0) throw new RuntimeException("移除签名失败");

                        Platform.runLater(() -> appendLog("✅ [1/5] 无签名副本已创建"));
                    } else {
                        Platform.runLater(() -> appendLog("✅ [1/5] 无签名副本已存在，跳过拷贝"));
                    }

                    if (cancelled[0]) return;

                    // ===== 步骤2：启动微信并提取密钥 =====
                    if (cancelled[0]) { resetButton(btnOneClick); return; }
                    Platform.runLater(() -> {
                        appendLog("🚀 [2/5] 正在通过 Frida 启动微信...");
                        if (waitingHeader[0] != null) {
                            waitingHeader[0].setText("正在启动微信，请稍候...");
                            waitingContent[0].setText("Frida 正在启动微信副本，\nmacOS 可能会弹出安全验证，请允许通过。\n微信窗口出现后将提示扫码登录。");
                        }
                    });

                    final boolean[] wechatVisible = {false};
                    Thread windowWatcher = new Thread(() -> {
                        for (int i = 0; i < 1800; i++) {
                            if (cancelled[0]) break;
                            try { Thread.sleep(1000); } catch (InterruptedException ignored) { break; }
                            if (isWeChatWindowVisible()) {
                                wechatVisible[0] = true;
                                Platform.runLater(() -> {
                                    appendLog("✅ 微信窗口已打开，请扫码登录！");
                                    if (waitingHeader[0] != null) {
                                        waitingHeader[0].setText("请在微信窗口中扫码登录");
                                        waitingContent[0].setText("微信已启动，请扫码登录...\n密钥提取成功后将自动关闭此窗口。");
                                    }
                                });
                                break;
                            }
                        }
                    }, "wechat-watcher");
                    windowWatcher.setDaemon(true);
                    windowWatcher.start();

                    FridaKeyExtractor extractor = new FridaKeyExtractor();
                    extractor.setTimeout(1800);
                    String key = extractor.extractKey();
                    String fridaError = extractor.getLastError();

                    windowWatcher.interrupt();

                    if (cancelled[0]) return;

                    Platform.runLater(() -> {
                        if (waitingStage[0] != null) {
                            waitingStage[0].close();
                            waitingStage[0] = null;
                        }
                    });

                    if (key == null) {
                        final String detail = fridaError != null ? fridaError : "未知错误";
                        Platform.runLater(() -> {
                            btnOneClick.setDisable(false);
                            btnOneClick.setText("🚀 一键初始化");
                            appendLog("❌ 密钥提取失败: " + detail);
                            showRetryDialog("提取失败", "无法提取密钥，详细信息:\n" + detail, btnOneClick);
                        });
                        return;
                    }

                    Platform.runLater(() -> {
                        txtKey.setText(key);
                        lblDbKeyStatus.setText("当前状态: ✅ 已提取 (" + key.substring(0, 8) + "...)");
                        appendLog("✅ [2/5] 密钥提取成功: " + key.substring(0, 8) + "...");
                    });

                    // ===== 步骤3：保存密钥到配置 =====
                    configManager.getConfig().setWxRawKey(key);
                    configManager.save();
                    Platform.runLater(() -> appendLog("✅ [3/5] 密钥已保存到配置文件"));

                    // ===== 步骤4：初始化数据库 =====
                    Platform.runLater(() -> appendLog("🔓 [4/5] 正在初始化数据库连接..."));
                    try {
                        AppConfig cfg = configManager.getConfig();
                        WeChatDBConfig dbConfig = WeChatDBConfig.fromRawKey(key)
                                .wechatDataDir(cfg.getWxDataDir());
                        weChatDB = new WeChatDB(dbConfig);
                        weChatDB.init();
                        messageMonitor = new MessageMonitor(weChatDB);
                        setupMessageCallback();
                        // 初始化@提醒追踪器
                        atReminderTracker = new AtReminderTracker(weChatDB, msg -> appendLog(msg));
                        atReminderTracker.setMessageSender((chatName, message) -> WeChatSender.sendTextMessage(chatName, message));
                        atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
                        // 自动加载监听群
                        autoLoadMonitorGroups();
                        Platform.runLater(() -> appendLog("✅ [4/5] 数据库连接已就绪"));
                    } catch (Exception ex) {
                        Platform.runLater(() -> appendLog("⚠️ [4/5] 数据库初始化失败（可稍后手动初始化）: " + ex.getMessage()));
                    }

                    // ===== 步骤5：完成 =====
                    Platform.runLater(() -> appendLog("✅ [5/5] 流程完成，微信副本保留在 /tmp/WeChat_copy.app"));

                    Platform.runLater(() -> {
                        btnOneClick.setDisable(false);
                        btnOneClick.setText("🚀 一键初始化");
                        appendLog("🎉 一键初始化完成！密钥已保存，数据库已就绪。可通过「启动微信副本」按钮再次启动微信。");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        if (waitingStage[0] != null) {
                            waitingStage[0].close();
                            waitingStage[0] = null;
                        }
                        resetButton(btnOneClick);
                        appendLog("❌ 初始化失败: " + ex.getMessage());
                        showError("初始化失败", ex.getMessage());
                    });
                }
            });
        });

        // 保存按钮事件
        btnSaveKey.setOnAction(e -> {
            String key = txtKey.getText().trim();
            if (key.length() != 64 || !key.matches("[0-9a-f]{64}")) {
                showError("格式错误", "密钥必须是 64 位 hex 字符串（0-9, a-f）");
                return;
            }
            configManager.getConfig().setWxRawKey(key);
            configManager.save();
            lblDbKeyStatus.setText("当前状态: ✅ 已配置 (" + key.substring(0, 8) + "...)");
            appendLog("密钥已保存到配置文件");
        });

        // 测试数据库连接按钮事件
        btnDecrypt.setOnAction(e -> {
            String key = txtKey.getText().trim();
            if (key.length() != 64) {
                showError("未配置", "请先提取或输入 64 位 hex 密钥");
                return;
            }
            btnDecrypt.setDisable(true);
            btnDecrypt.setText("⏳ 测试中...");
            appendLog("正在测试数据库连接...");

            executor.submit(() -> {
                try {
                    AppConfig cfg = configManager.getConfig();
                    WeChatDBConfig dbConfig = WeChatDBConfig.fromRawKey(key)
                            .wechatDataDir(cfg.getWxDataDir());

                    Platform.runLater(() -> appendLog("正在测试数据库连接..."));

                    weChatDB = new WeChatDB(dbConfig);
                    weChatDB.init();
                    messageMonitor = new MessageMonitor(weChatDB);
                    setupMessageCallback();
                    // 初始化@提醒追踪器
                    atReminderTracker = new AtReminderTracker(weChatDB, msg -> appendLog(msg));
                    atReminderTracker.setMessageSender((chatName, message) -> WeChatSender.sendTextMessage(chatName, message));
                    atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
                    // 自动加载监听群
                    autoLoadMonitorGroups();

                    Platform.runLater(() -> {
                        btnDecrypt.setDisable(false);
                        btnDecrypt.setText("🔓 测试数据库连接");
                        appendLog("✅ 数据库连接测试成功");
                        Alert alert = new Alert(Alert.AlertType.INFORMATION);
                        alert.setTitle("数据库连接测试");
                        alert.setHeaderText(null);
                        alert.setContentText("✅ 数据库连接成功！\n可以正常查询和监听消息。");
                        alert.showAndWait();
                    });
                } catch (Exception ex) {
                    StringBuilder errMsg = new StringBuilder();
                    Throwable t = ex;
                    while (t != null) {
                        if (errMsg.length() > 0) errMsg.append(" → ");
                        errMsg.append(t.getClass().getSimpleName()).append(": ").append(t.getMessage());
                        t = t.getCause();
                    }
                    final String finalErr = errMsg.toString();
                    Platform.runLater(() -> {
                        btnDecrypt.setDisable(false);
                        btnDecrypt.setText("🔓 测试数据库连接");
                        appendLog("❌ 数据库连接失败: " + finalErr);
                        Alert alert = new Alert(Alert.AlertType.ERROR);
                        alert.setTitle("数据库连接测试");
                        alert.setHeaderText(null);
                        alert.setContentText("❌ 数据库连接失败:\n" + finalErr);
                        alert.showAndWait();
                    });
                }
            });
        });

        Button btnLaunchWeChat = new Button("💬 启动微信副本");
        btnLaunchWeChat.getStyleClass().add("btn-default");
        btnLaunchWeChat.setOnAction(e -> {
            String copyApp = "/tmp/WeChat_copy.app";
            if (!new java.io.File(copyApp).exists()) {
                showError("副本不存在", "微信无签名副本不存在，请先执行一键初始化");
                return;
            }
            appendLog("🚀 正在启动微信副本...");
            try {
                new ProcessBuilder("open", copyApp).start();
                appendLog("✅ 微信副本已启动");
            } catch (Exception ex) {
                appendLog("❌ 启动微信副本失败: " + ex.getMessage());
            }
        });

        btnBox.getChildren().addAll(btnOneClick, btnLaunchWeChat, btnSaveKey, btnDecrypt);
        panel.getChildren().addAll(title, lblConfigStatus, lblConfigDir, btnRefresh,
                dbKeyTitle, desc, lblDbKeyStatus, lblKey, txtKey, btnBox);
        return panel;
    }

    /**
     * 创建私聊监听面板
     */
    private Node createListenPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("私聊消息监听");
        title.getStyleClass().add("section-title");

        // ===== 搜索联系人区域 =====
        Label lblSearch = new Label("搜索联系人:");
        lblSearch.getStyleClass().add("sub-title");
        HBox searchBox = new HBox(8);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtSearchContact = createTextField("输入昵称或备注搜索", 250);
        Button btnSearch = new Button("🔍 搜索");
        btnSearch.getStyleClass().add("btn-default");
        searchBox.getChildren().addAll(txtSearchContact, btnSearch);

        // 搜索结果列表（支持多选）
        ListView<String> lstSearchResults = new ListView<>();
        lstSearchResults.setPrefHeight(120);
        lstSearchResults.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);

        // ===== 监听控制区域 =====
        HBox controlBox = new HBox(8);
        controlBox.setAlignment(Pos.CENTER_LEFT);
        Button btnAddMonitor = new Button("➕ 添加监听");
        btnAddMonitor.getStyleClass().add("btn-success");
        Button btnStopMonitor = new Button("⏹ 停止监听");
        btnStopMonitor.getStyleClass().add("btn-danger");
        Button btnRefresh = new Button("📜 加载历史消息");
        btnRefresh.getStyleClass().add("btn-default");
        controlBox.getChildren().addAll(btnAddMonitor, btnStopMonitor, btnRefresh);

        // 监听列表
        Label lblMonitored = new Label("监听中的联系人（可多选停止）:");
        lblMonitored.getStyleClass().add("sub-title");
        lstMonitoredContacts = new ListView<>();
        lstMonitoredContacts.setPrefHeight(120);
        lstMonitoredContacts.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        lstMonitoredContacts.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #f7f7f7;");

        // ===== 消息显示区域 =====
        Label lblMessages = new Label("消息记录:");
        lblMessages.getStyleClass().add("sub-title");
        txtMonitorMessages = new TextArea();
        txtMonitorMessages.setEditable(false);
        txtMonitorMessages.setWrapText(true);
        txtMonitorMessages.setPrefRowCount(12);
        VBox.setVgrow(txtMonitorMessages, javafx.scene.layout.Priority.ALWAYS);

        // ===== 发送消息区域 =====
        Label lblSend = new Label("发送消息:");
        lblSend.getStyleClass().add("sub-title");
        TextField txtSendMessage = createTextField("输入消息内容", 400);
        Button btnSendMessage = new Button("📤 发送");
        btnSendMessage.getStyleClass().add("btn-primary");
        HBox sendBox = new HBox(8, txtSendMessage, btnSendMessage);
        sendBox.setAlignment(Pos.CENTER_LEFT);

        // ===== 事件绑定 =====
        btnSearch.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            String keyword = txtSearchContact.getText().trim();
            List<Contact> contacts = messageMonitor.searchContacts(keyword);
            lstSearchResults.getItems().clear();
            for (Contact c : contacts) {
                String display = c.getDisplayName() + " (" + c.getUsername() + ")";
                lstSearchResults.getItems().add(display);
            }
            appendLog("搜索到 " + contacts.size() + " 个联系人");
        });

        btnAddMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            var selectedItems = lstSearchResults.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("未选择", "请先搜索并选择一个或多个联系人");
                return;
            }
            // 复制一份避免迭代时修改
            var toAdd = new java.util.ArrayList<>(selectedItems);
            for (String selected : toAdd) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.startMonitoring(username);
                    chatBuffers.putIfAbsent(username, new StringBuilder());
                    appendLog("✅ 开始监听: " + selected);
                }
            }
            refreshMonitoredList();
            // 自动选中最后一个添加的联系人
            if (!toAdd.isEmpty() && !lstMonitoredContacts.getItems().isEmpty()) {
                skipHistoryLoad = true;
                lstMonitoredContacts.getSelectionModel().select(lstMonitoredContacts.getItems().size() - 1);
            }
            appendLog("✅ 已添加 " + toAdd.size() + " 个联系人到监听列表");
        });

        btnStopMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            var selectedItems = lstMonitoredContacts.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("未选择", "请先选择要停止监听的联系人");
                return;
            }
            var toStop = new java.util.ArrayList<>(selectedItems);
            for (String selected : toStop) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.stopMonitoring(username);
                    appendLog("⏹ 停止监听: " + selected);
                }
            }
            refreshMonitoredList();
        });

        btnRefresh.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            String selected = lstMonitoredContacts.getSelectionModel().getSelectedItem();
            if (selected == null) {
                selected = lstSearchResults.getSelectionModel().getSelectedItem();
            }
            if (selected != null) {
                String username = extractUsername(selected);
                if (username != null) {
                    appendLog("📜 正在加载历史消息...");
                    loadHistoryMessages(username);
                }
            } else {
                appendLog("❗ 请先选择联系人");
            }
        });

        // 点击监听列表时切换显示对应聊天的消息
        lstMonitoredContacts.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String username = extractUsername(newVal);
                selectedPrivateChat = username;
                if (!skipHistoryLoad && weChatDB != null && username != null) {
                    loadHistoryMessages(username);
                }
                // 显示该聊天的缓冲区
                showChatBuffer(username, txtMonitorMessages);
            } else {
                selectedPrivateChat = null;
            }
            skipHistoryLoad = false;
        });

        // 发送消息按钮
        btnSendMessage.setOnAction(e -> {
            String msg = txtSendMessage.getText().trim();
            if (msg.isEmpty()) {
                showError("未输入", "请输入要发送的消息内容");
                return;
            }
            // 从监听列表获取当前选中项的显示名称
            String selectedItem = lstMonitoredContacts.getSelectionModel().getSelectedItem();
            if (selectedItem == null) {
                showError("未选择", "请先在监听列表中选择一个联系人");
                return;
            }
            String chatName = extractDisplayName(selectedItem);
            appendLog("📤 正在发送消息给 " + chatName + "...");
            btnSendMessage.setDisable(true);
            // 在后台线程执行 AppleScript（会阻塞几秒）
            executor.submit(() -> {
                String result = WeChatSender.sendTextMessage(chatName, msg);
                Platform.runLater(() -> {
                    btnSendMessage.setDisable(false);
                    if ("ok".equals(result)) {
                        appendLog("✅ 消息已发送给 " + chatName);
                        txtSendMessage.clear();
                    } else {
                        appendLog("❗ 发送失败: " + result);
                    }
                });
            });
        });

        panel.getChildren().addAll(title, lblSearch, searchBox, lstSearchResults,
                controlBox, lblMonitored, lstMonitoredContacts, lblMessages, txtMonitorMessages,
                lblSend, sendBox);
        return panel;
    }

    /**
     * 创建群聊监听监听面板
     */
    private Node createGroupListenPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(10));

        Label title = new Label("\ud83d\udcac \u7fa4\u804a\u6d88\u606f\u76d1\u542c");
        title.getStyleClass().add("section-title");

        // ===== \u5de6\u4fa7\u9762\u677f\uff1a\u641c\u7d22 + \u76d1\u542c\u7ba1\u7406 =====
        VBox leftPanel = new VBox(8);
        leftPanel.setPrefWidth(320);
        leftPanel.setStyle("-fx-background-color: #fafafa; -fx-border-color: #e8e8e8; -fx-border-radius: 6; -fx-background-radius: 6; -fx-padding: 10;");

        // -- \u641c\u7d22\u7fa4\u804a --
        Label lblSearch = new Label("\ud83d\udd0d \u641c\u7d22\u7fa4\u804a");
        lblSearch.getStyleClass().add("sub-title");
        HBox searchBox = new HBox(6);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtSearchGroup = createTextField("\u8f93\u5165\u7fa4\u540d\u641c\u7d22", 180);
        HBox.setHgrow(txtSearchGroup, javafx.scene.layout.Priority.ALWAYS);
        Button btnSearch = new Button("\u641c\u7d22");
        btnSearch.getStyleClass().add("btn-default");
        searchBox.getChildren().addAll(txtSearchGroup, btnSearch);

        // \u641c\u7d22\u7ed3\u679c\u5217\u8868\uff08\u652f\u6301\u591a\u9009\uff09
        ListView<String> lstSearchResults = new ListView<>();
        lstSearchResults.setPrefHeight(160);
        lstSearchResults.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        lstSearchResults.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #ffffff; -fx-border-radius: 4; -fx-background-radius: 4;");

        // -- \u64cd\u4f5c\u6309\u94ae --
        HBox controlBox = new HBox(6);
        controlBox.setAlignment(Pos.CENTER_LEFT);
        Button btnAddMonitor = new Button("+ \u6dfb\u52a0\u76d1\u542c");
        btnAddMonitor.getStyleClass().add("btn-success");
        Button btnStopMonitor = new Button("\u23f9 \u505c\u6b62");
        btnStopMonitor.getStyleClass().add("btn-danger");
        Button btnRefresh = new Button("\ud83d\udcdc \u5386\u53f2");
        btnRefresh.getStyleClass().add("btn-default");
        controlBox.getChildren().addAll(btnAddMonitor, btnStopMonitor, btnRefresh);

        CheckBox chkAutoMonitor = new CheckBox("\u81ea\u52a8\u76d1\u542c\uff08\u542f\u52a8\u65f6\u81ea\u52a8\u76d1\u542c\uff09");
        chkAutoMonitor.setSelected(configManager.getConfig().getAutoMonitorGroups() != null
                && !configManager.getConfig().getAutoMonitorGroups().isEmpty());

        // -- \u76d1\u542c\u4e2d\u7684\u7fa4\u804a --
        Label lblMonitored = new Label("\ud83d\udccc \u76d1\u542c\u4e2d\u7684\u7fa4\u804a\uff08\u53ef\u591a\u9009\u505c\u6b62\uff09");
        lblMonitored.getStyleClass().add("sub-title");
        lstMonitoredGroups = new ListView<>();
        lstMonitoredGroups.setPrefHeight(140);
        lstMonitoredGroups.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        lstMonitoredGroups.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #ffffff; -fx-border-radius: 4; -fx-background-radius: 4;");

        leftPanel.getChildren().addAll(lblSearch, searchBox, lstSearchResults,
                controlBox, chkAutoMonitor, lblMonitored, lstMonitoredGroups);

        // ===== \u53f3\u4fa7\u9762\u677f\uff1a\u6d88\u606f + \u53d1\u9001 =====
        VBox rightPanel = new VBox(8);
        HBox.setHgrow(rightPanel, javafx.scene.layout.Priority.ALWAYS);

        // -- \u6d88\u606f\u8bb0\u5f55 --
        Label lblMessages = new Label("\ud83d\udcdd \u6d88\u606f\u8bb0\u5f55");
        lblMessages.getStyleClass().add("sub-title");
        txtGroupMessages = new TextArea();
        txtGroupMessages.setEditable(false);
        txtGroupMessages.setWrapText(true);
        txtGroupMessages.setPrefRowCount(16);
        txtGroupMessages.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #ffffff; -fx-border-radius: 4; -fx-background-radius: 4;");

        // -- \u53d1\u9001\u6d88\u606f --
        Label lblSend = new Label("\ud83d\udce4 \u53d1\u9001\u6d88\u606f");
        lblSend.getStyleClass().add("sub-title");
        TextField txtGroupSendMessage = createTextField("\u8f93\u5165\u6d88\u606f\u5185\u5bb9", 300);
        HBox.setHgrow(txtGroupSendMessage, javafx.scene.layout.Priority.ALWAYS);
        Button btnGroupSendMessage = new Button("\ud83d\udce4 \u53d1\u9001");
        btnGroupSendMessage.getStyleClass().add("btn-primary");
        HBox sendBox = new HBox(8, txtGroupSendMessage, btnGroupSendMessage);
        sendBox.setAlignment(Pos.CENTER_LEFT);

        rightPanel.getChildren().addAll(lblMessages, txtGroupMessages, lblSend, sendBox);

        // ===== \u7ec4\u5408\u5de6\u53f3\u4e24\u680f =====
        HBox mainContent = new HBox(12);
        mainContent.setAlignment(Pos.TOP_LEFT);
        mainContent.getChildren().addAll(leftPanel, rightPanel);

        // ===== @\u63d0\u9192\u89c4\u5219\u533a\u57df\uff08\u53ef\u6298\u53e0\uff09 =====
        Node atReminderSection = createAtReminderPanel();
        TitledPane atTitledPane = new TitledPane("\u23f0 @\u63d0\u9192\u89c4\u5219\u7ba1\u7406", atReminderSection);
        atTitledPane.setCollapsible(true);
        atTitledPane.setExpanded(false);
        atTitledPane.setStyle("-fx-border-color: #e8e8e8; -fx-border-radius: 6; -fx-background-radius: 6;");

        panel.getChildren().addAll(title, mainContent, atTitledPane);

        // ===== \u4e8b\u4ef6\u7ed1\u5b9a =====
        btnSearch.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("\u2757 \u8bf7\u5148\u521d\u59cb\u5316\u6570\u636e\u5e93");
                return;
            }
            String keyword = txtSearchGroup.getText().trim();
            List<Contact> groups = messageMonitor.searchChatrooms(keyword);
            lstSearchResults.getItems().clear();
            for (Contact c : groups) {
                String display = c.getDisplayName() + " (" + c.getUsername() + ")";
                lstSearchResults.getItems().add(display);
            }
            appendLog("\u641c\u7d22\u5230 " + groups.size() + " \u4e2a\u7fa4\u804a");
        });

        btnAddMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("\u2757 \u8bf7\u5148\u521d\u59cb\u5316\u6570\u636e\u5e93");
                return;
            }
            var selectedItems = lstSearchResults.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("\u672a\u9009\u62e9", "\u8bf7\u5148\u641c\u7d22\u5e76\u9009\u62e9\u4e00\u4e2a\u6216\u591a\u4e2a\u7fa4\u804a");
                return;
            }
            var toAdd = new java.util.ArrayList<>(selectedItems);
            for (String selected : toAdd) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.startMonitoring(username);
                    chatBuffers.putIfAbsent(username, new StringBuilder());
                    appendLog("\u2705 \u5f00\u59cb\u76d1\u542c\u7fa4\u804a: " + selected);
                }
            }
            refreshMonitoredGroupList();
            if (!toAdd.isEmpty() && !lstMonitoredGroups.getItems().isEmpty()) {
                skipGroupHistoryLoad = true;
                lstMonitoredGroups.getSelectionModel().select(lstMonitoredGroups.getItems().size() - 1);
            }
            appendLog("\u2705 \u5df2\u6dfb\u52a0 " + toAdd.size() + " \u4e2a\u7fa4\u804a\u5230\u76d1\u542c\u5217\u8868");

            // \u5982\u679c\u52fe\u9009\u4e86\u81ea\u52a8\u76d1\u542c\uff0c\u5c06\u65b0\u589e\u7684\u7fa4\u52a0\u5165\u81ea\u52a8\u76d1\u542c\u5217\u8868
            if (chkAutoMonitor.isSelected()) {
                AppConfig cfg = configManager.getConfig();
                List<String> autoList = cfg.getAutoMonitorGroups();
                if (autoList == null) autoList = new java.util.ArrayList<>();
                for (String selected : toAdd) {
                    String username = extractUsername(selected);
                    if (username != null && !autoList.contains(username)) {
                        autoList.add(username);
                    }
                }
                cfg.setAutoMonitorGroups(autoList);
                configManager.save();
                appendLog("\ud83d\udcbe \u5df2\u66f4\u65b0\u81ea\u52a8\u76d1\u542c\u7fa4\u5217\u8868");
            }
        });

        btnStopMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("\u2757 \u8bf7\u5148\u521d\u59cb\u5316\u6570\u636e\u5e93");
                return;
            }
            var selectedItems = lstMonitoredGroups.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("\u672a\u9009\u62e9", "\u8bf7\u5148\u9009\u62e9\u8981\u505c\u6b62\u76d1\u542c\u7684\u7fa4\u804a");
                return;
            }
            var toStop = new java.util.ArrayList<>(selectedItems);
            for (String selected : toStop) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.stopMonitoring(username);
                    appendLog("\u23f9 \u505c\u6b62\u76d1\u542c\u7fa4\u804a: " + selected);
                }
            }
            refreshMonitoredGroupList();

            // \u540c\u65f6\u4ece\u81ea\u52a8\u76d1\u542c\u5217\u8868\u4e2d\u79fb\u9664
            AppConfig cfg = configManager.getConfig();
            List<String> autoList = cfg.getAutoMonitorGroups();
            if (autoList != null) {
                for (String selected : toStop) {
                    String username = extractUsername(selected);
                    if (username != null) autoList.remove(username);
                }
                cfg.setAutoMonitorGroups(autoList);
                configManager.save();
            }
        });

        // \u81ea\u52a8\u76d1\u542c\u52fe\u9009\u6846\u53d8\u5316\u65f6\u540c\u6b65\u5230\u914d\u7f6e
        chkAutoMonitor.setOnAction(e -> {
            AppConfig cfg2 = configManager.getConfig();
            if (chkAutoMonitor.isSelected()) {
                List<String> autoList2 = new java.util.ArrayList<>();
                for (String uname : messageMonitor.getMonitoredUsernames()) {
                    if (uname.endsWith("@chatroom")) {
                        autoList2.add(uname);
                    }
                }
                cfg2.setAutoMonitorGroups(autoList2);
                configManager.save();
                appendLog("\u5df2\u52a0\u5165\u81ea\u52a8\u76d1\u542c: " + autoList2.size() + " \u4e2a\u7fa4\u804a");
            } else {
                cfg2.setAutoMonitorGroups(new java.util.ArrayList<>());
                configManager.save();
                appendLog("\u5df2\u6e05\u7a7a\u81ea\u52a8\u76d1\u542c\u5217\u8868");
            }
        });

        btnRefresh.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("\u2757 \u8bf7\u5148\u521d\u59cb\u5316\u6570\u636e\u5e93");
                return;
            }
            String selected = lstMonitoredGroups.getSelectionModel().getSelectedItem();
            if (selected == null) {
                selected = lstSearchResults.getSelectionModel().getSelectedItem();
            }
            if (selected != null) {
                String username = extractUsername(selected);
                if (username != null) {
                    appendLog("\ud83d\udcdc \u6b63\u5728\u52a0\u8f7d\u7fa4\u804a\u5386\u53f2\u6d88\u606f...");
                    loadGroupHistoryMessages(username);
                }
            } else {
                appendLog("\u2757 \u8bf7\u5148\u9009\u62e9\u7fa4\u804a");
            }
        });

        lstMonitoredGroups.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null) {
                String username = extractUsername(newVal);
                selectedGroupChat = username;
                if (!skipGroupHistoryLoad && weChatDB != null && username != null) {
                    loadGroupHistoryMessages(username);
                }
                showChatBuffer(username, txtGroupMessages);
            } else {
                selectedGroupChat = null;
            }
            skipGroupHistoryLoad = false;
        });

        // \u53d1\u9001\u6d88\u606f\u6309\u94ae
        btnGroupSendMessage.setOnAction(e -> {
            String msg = txtGroupSendMessage.getText().trim();
            if (msg.isEmpty()) {
                showError("\u672a\u8f93\u5165", "\u8bf7\u8f93\u5165\u8981\u53d1\u9001\u7684\u6d88\u606f\u5185\u5bb9");
                return;
            }
            String selectedItem = lstMonitoredGroups.getSelectionModel().getSelectedItem();
            if (selectedItem == null) {
                showError("\u672a\u9009\u62e9", "\u8bf7\u5148\u5728\u76d1\u542c\u5217\u8868\u4e2d\u9009\u62e9\u4e00\u4e2a\u7fa4\u804a");
                return;
            }
            String chatName = extractDisplayName(selectedItem);
            appendLog("\ud83d\udce4 \u6b63\u5728\u53d1\u9001\u6d88\u606f\u5230\u7fa4\u804a " + chatName + "...");
            btnGroupSendMessage.setDisable(true);
            executor.submit(() -> {
                String result = WeChatSender.sendTextMessage(chatName, msg);
                Platform.runLater(() -> {
                    btnGroupSendMessage.setDisable(false);
                    if ("ok".equals(result)) {
                        appendLog("\u2705 \u6d88\u606f\u5df2\u53d1\u9001\u5230\u7fa4\u804a " + chatName);
                        txtGroupSendMessage.clear();
                    } else {
                        appendLog("\u2757 \u53d1\u9001\u5931\u8d25: " + result);
                    }
                });
            });
        });

        return panel;
    }


    /**
     * 自动加载配置中的监听群列表
     */
    private void autoLoadMonitorGroups() {
        List<String> autoGroups = configManager.getConfig().getAutoMonitorGroups();
        if (autoGroups == null || autoGroups.isEmpty()) return;
        int count = 0;
        for (String username : autoGroups) {
            if (!messageMonitor.isMonitoring(username)) {
                messageMonitor.startMonitoring(username);
                chatBuffers.putIfAbsent(username, new StringBuilder());
                count++;
            }
        }
        if (count > 0) {
            refreshMonitoredGroupList();
            final int c = count;
            Platform.runLater(() -> appendLog("自动监听了 " + c + " 个群聊"));
        }
    }

    /**
     * 从显示文本中提取 username
     */
    private String extractUsername(String displayText) {
        if (displayText == null) return null;
        int start = displayText.lastIndexOf('(');
        int end = displayText.lastIndexOf(')');
        if (start >= 0 && end > start) {
            return displayText.substring(start + 1, end);
        }
        return displayText;
    }

    /**
     * 从显示文本中提取显示名称（括号前的部分）
     */
    private String extractDisplayName(String displayText) {
        if (displayText == null) return null;
        int idx = displayText.lastIndexOf('(');
        if (idx > 0) {
            return displayText.substring(0, idx).trim();
        }
        return displayText;
    }

    /**
     * 刷新监听列表
     */
    private void refreshMonitoredList() {
        if (lstMonitoredContacts == null || messageMonitor == null) return;
        lstMonitoredContacts.getItems().clear();
        for (String username : messageMonitor.getMonitoredUsernames()) {
            try {
                Contact c = weChatDB.getContact(username);
                String display = (c != null ? c.getDisplayName() : username) + " (" + username + ")";
                lstMonitoredContacts.getItems().add(display);
            } catch (Exception e) {
                lstMonitoredContacts.getItems().add(username);
            }
        }
    }

    /**
     * 加载指定联系人的所有历史消息（去重 + 时间正序，直接在当前线程执行）
     */
    private void loadHistoryMessages(String username) {
        if (weChatDB == null) {
            appendLog("❗ 数据库未初始化");
            return;
        }
        try {
            var msgs = weChatDB.getAllMessages(username);
            if (msgs == null) {
                appendLog("❗ 查询返回 null，跳过");
                return;
            }
            java.util.LinkedHashMap<Long, ChatMessage> deduped = new java.util.LinkedHashMap<>();
            for (var m : msgs) {
                if (m != null) deduped.put(m.getLocalId(), m);
            }
            var unique = new java.util.ArrayList<>(deduped.values());
            unique.sort((a, b) -> Long.compare(a.getCreateTime(), b.getCreateTime()));
            java.util.Map<String, String> nameCache = new java.util.HashMap<>();
            // 构建历史消息并写入缓冲区
            StringBuilder historyBuf = new StringBuilder();
            for (var m : unique) {
                String time = m.getCreateTime() > 0
                        ? java.time.Instant.ofEpochSecond(m.getCreateTime())
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : "??:??:??";
                String senderDisplay = resolveSenderName(m.getSenderId(), nameCache);
                String content = m.getMessageContent();
                if (content != null && content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                String typeTag = m.getLocalType() != 1 ? "[" + m.getTypeDescription() + "] " : "";
                historyBuf.append(String.format("[%s] %s: %s%s\n", time, senderDisplay, typeTag, content != null ? content : ""));
            }
            // 存入缓冲区（历史 + 后续新消息）
            StringBuilder buffer = chatBuffers.computeIfAbsent(username, k -> new StringBuilder());
            synchronized (buffer) {
                buffer.insert(0, historyBuf.toString());
            }
            // 如果当前选中的就是这个聊天，立即显示
            if (username.equals(selectedPrivateChat)) {
                showChatBuffer(username, txtMonitorMessages);
            }
            appendLog("📩 已加载 " + unique.size() + " 条历史消息（去重后）");
        } catch (Exception e) {
            appendLog("❗ 加载历史消息失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (e.getCause() != null) {
                appendLog("  原因: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * 刷新群聊监听列表
     */
    private void refreshMonitoredGroupList() {
        if (lstMonitoredGroups == null || messageMonitor == null) return;
        lstMonitoredGroups.getItems().clear();
        for (String username : messageMonitor.getMonitoredUsernames()) {
            if (username.endsWith("@chatroom")) {
                try {
                    Contact c = weChatDB.getContact(username);
                    String display = (c != null ? c.getDisplayName() : username) + " (" + username + ")";
                    lstMonitoredGroups.getItems().add(display);
                } catch (Exception e) {
                    lstMonitoredGroups.getItems().add(username);
                }
            }
        }
        // 同步到@提醒规则的源群下拉框
        if (cbSourceGroupRef != null) {
            cbSourceGroupRef.getItems().setAll(lstMonitoredGroups.getItems());
        }
    }

    /**
     * 加载指定群聊的所有历史消息（去重 + 时间正序）
     */
    private void loadGroupHistoryMessages(String username) {
        if (weChatDB == null) {
            appendLog("❗ 数据库未初始化");
            return;
        }
        try {
            var msgs = weChatDB.getAllMessages(username);
            if (msgs == null) {
                appendLog("❗ 查询返回 null，跳过");
                return;
            }
            java.util.LinkedHashMap<Long, ChatMessage> deduped = new java.util.LinkedHashMap<>();
            for (var m : msgs) {
                if (m != null) deduped.put(m.getLocalId(), m);
            }
            var unique = new java.util.ArrayList<>(deduped.values());
            unique.sort((a, b) -> Long.compare(a.getCreateTime(), b.getCreateTime()));
            java.util.Map<String, String> nameCache = new java.util.HashMap<>();
            StringBuilder historyBuf = new StringBuilder();
            for (var m : unique) {
                String time = m.getCreateTime() > 0
                        ? java.time.Instant.ofEpochSecond(m.getCreateTime())
                            .atZone(java.time.ZoneId.systemDefault())
                            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                        : "??:??:??";
                String senderDisplay = resolveSenderName(m.getSenderId(), nameCache);
                String content = m.getMessageContent();
                if (content != null && content.contains(":\n")) {
                    int colonIdx = content.indexOf(":\n");
                    content = content.substring(colonIdx + 2);
                }
                if (content != null && content.length() > 200) {
                    content = content.substring(0, 200) + "...";
                }
                String typeTag = m.getLocalType() != 1 ? "[" + m.getTypeDescription() + "] " : "";
                historyBuf.append(String.format("[%s] %s: %s%s\n", time, senderDisplay, typeTag, content != null ? content : ""));
            }
            StringBuilder buffer = chatBuffers.computeIfAbsent(username, k -> new StringBuilder());
            synchronized (buffer) {
                buffer.insert(0, historyBuf.toString());
            }
            if (username.equals(selectedGroupChat)) {
                showChatBuffer(username, txtGroupMessages);
            }
            appendLog("📩 已加载群聊 " + unique.size() + " 条历史消息（去重后）");
        } catch (Exception e) {
            appendLog("❗ 加载群聊历史消息失败: " + e.getClass().getSimpleName() + " - " + e.getMessage());
            if (e.getCause() != null) {
                appendLog("  原因: " + e.getCause().getClass().getSimpleName() + " - " + e.getCause().getMessage());
            }
            e.printStackTrace();
        }
    }

    /**
     * 解析发送者 ID 为显示名称（先通过 Name2Id 表查 username，再查联系人表获取昵称）
     */
    private String resolveSenderName(String senderId, java.util.Map<String, String> nameCache) {
        if (senderId == null || senderId.isEmpty()) return "?";
        if (nameCache.containsKey(senderId)) return nameCache.get(senderId);
        String display = senderId; // 默认显示 ID
        try {
            // senderId 可能是数字 rowid，需要先解析为 username
            String username = senderId;
            try {
                int rowId = Integer.parseInt(senderId);
                username = weChatDB.resolveSenderUsername(rowId);
            } catch (NumberFormatException ignored) {
                // senderId 本身已经是 username（如 wxid_xxx）
            }
            // 再用 username 查联系人表获取昵称
            Contact c = weChatDB.getContact(username);
            if (c != null) {
                display = c.getDisplayName();
            } else if (!username.equals(senderId)) {
                display = username;
            }
        } catch (Exception ignored) {}
        nameCache.put(senderId, display);
        return display;
    }

    /**
     * 设置统一的新消息回调，支持多聊天同时监听。
     * 消息按 chatUsername 路由到对应的缓冲区，并更新当前选中聊天的显示。
     */
    private void setupMessageCallback() {
        messageMonitor.setOnNewMessage(mm -> {
            String chatUser = mm.getChatUsername();
            String line = mm.toDisplayString() + "\n";
            // 追加到该聊天的缓冲区
            StringBuilder buffer = chatBuffers.computeIfAbsent(chatUser, k -> new StringBuilder());
            synchronized (buffer) {
                buffer.append(line);
                trimBuffer(buffer);
            }
            // 如果该聊天是当前选中的，实时更新显示
            if (chatUser.equals(selectedPrivateChat) && txtMonitorMessages != null) {
                Platform.runLater(() -> {
                    txtMonitorMessages.appendText(line);
                    privateMsgCount++;
                    if (privateMsgCount >= MAX_BUFFER_LINES) {
                        trimTextArea(txtMonitorMessages);
                        privateMsgCount = 0;
                    }
                    txtMonitorMessages.setScrollTop(Double.MAX_VALUE);
                });
            }
            if (chatUser.equals(selectedGroupChat) && txtGroupMessages != null) {
                Platform.runLater(() -> {
                    txtGroupMessages.appendText(line);
                    groupMsgCount++;
                    if (groupMsgCount >= MAX_BUFFER_LINES) {
                        trimTextArea(txtGroupMessages);
                        groupMsgCount = 0;
                    }
                    txtGroupMessages.setScrollTop(Double.MAX_VALUE);
                });
            }
            // @提醒规则检测
            if (atReminderTracker != null && configManager.getConfig().isAtReminderSwitch()) {
                atReminderTracker.onNewMessage(mm);
            }
            // 日志始终记录
            Platform.runLater(() -> appendLog("📩 [" + mm.getChatDisplayName() + "] " +
                    mm.getSenderDisplayName() + ": " +
                    (mm.getContent() != null && mm.getContent().length() > 50
                            ? mm.getContent().substring(0, 50) + "..."
                            : mm.getContent())));
        });
    }

    /**
     * 显示指定聊天的消息缓冲区到目标 TextArea
     */
    private void showChatBuffer(String username, TextArea textArea) {
        if (username == null || textArea == null) return;
        StringBuilder buffer = chatBuffers.get(username);
        textArea.clear();
        if (buffer != null) {
            synchronized (buffer) {
                textArea.setText(buffer.toString());
            }
            textArea.setScrollTop(Double.MAX_VALUE);
        }
    }

    /**
     * 截断缓冲区，保留最后 MAX_BUFFER_LINES 行
     */
    private void trimBuffer(StringBuilder buffer) {
        int count = 0;
        int idx = buffer.length();
        while (idx > 0) {
            idx = buffer.lastIndexOf("\n", idx - 1);
            count++;
            if (count > MAX_BUFFER_LINES) {
                buffer.delete(0, idx + 1);
                return;
            }
        }
    }

    /**
     * 裁剪 TextArea，保留最后 MAX_BUFFER_LINES 行
     */
    private void trimTextArea(TextArea textArea) {
        String text = textArea.getText();
        int count = 0;
        int idx = text.length();
        while (idx > 0) {
            idx = text.lastIndexOf('\n', idx - 1);
            count++;
            if (count > MAX_BUFFER_LINES) {
                textArea.setText(text.substring(idx + 1));
                return;
            }
        }
    }

    /**
     * 创建@提醒规则面板
     */
    private Node createAtReminderPanel() {
        VBox panel = new VBox(8);
        panel.setPadding(new Insets(8));

        Label desc = new Label("当源群中有人@目标人时开始计时，若目标人在超时时间内未回复，则在目标群发送提醒消息。");
        desc.setWrapText(true);
        desc.getStyleClass().add("sub-title");

        // 总开关（自动保存）
        CheckBox chkAtSwitch = new CheckBox("启用@提醒规则");
        chkAtSwitch.setSelected(configManager.getConfig().isAtReminderSwitch());
        chkAtSwitch.setOnAction(e -> {
            configManager.getConfig().setAtReminderSwitch(chkAtSwitch.isSelected());
            configManager.save();
            appendLog("@提醒总开关: " + (chkAtSwitch.isSelected() ? "开启" : "关闭"));
            if (atReminderTracker != null) {
                atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
            }
        });

        // 规则列表
        Label lblRules = new Label("规则列表:");
        lblRules.getStyleClass().add("sub-title");
        ListView<String> lstRules = new ListView<>();
        lstRules.setPrefHeight(150);
        lstRules.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #f7f7f7;");

        // 规则编辑区域
        Label lblEdit = new Label("规则配置:");
        lblEdit.getStyleClass().add("sub-title");

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(8);

        TextField txtRuleName = createTextField("规则名称", 200);
        ComboBox<String> cbSourceGroup = new ComboBox<>();
        cbSourceGroup.setPromptText("从监听列表中选择源群");
        cbSourceGroup.setPrefWidth(300);
        if (lstMonitoredGroups != null) {
            cbSourceGroup.getItems().addAll(lstMonitoredGroups.getItems());
        }
        cbSourceGroupRef = cbSourceGroup;
        TextField txtTargetGroup = createTextField("目标群 username", 300);
        TextField txtTargetPerson = createTextField("被@的目标人(多人用逗号分隔)", 300);
        TextField txtTimeout = createTextField("超时分钟数", 80);
        txtTimeout.setText("10");
        TextArea txtTemplate = new TextArea();
        txtTemplate.setPromptText("提醒模板: {person} {sourceGroup} {timeout} {message} {sender}");
        txtTemplate.setPrefRowCount(3);
        txtTemplate.setPrefWidth(400);
        txtTemplate.setText("提醒: {person} 在 {sourceGroup} 被@了，已过{timeout}分钟未回复，原始消息: {message}");

        grid.add(new Label("规则名称:"), 0, 0);
        grid.add(txtRuleName, 1, 0);
        grid.add(new Label("源群:"), 0, 1);
        grid.add(cbSourceGroup, 1, 1);
        grid.add(new Label("目标群:"), 0, 2);
        grid.add(txtTargetGroup, 1, 2);
        grid.add(new Label("目标人:"), 0, 3);
        grid.add(txtTargetPerson, 1, 3);
        grid.add(new Label("超时(分钟):"), 0, 4);
        grid.add(txtTimeout, 1, 4);
        grid.add(new Label("提醒模板:"), 0, 5);
        grid.add(txtTemplate, 1, 5);

        // 按钮
        HBox btnBox = new HBox(8);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        Button btnAddRule = new Button("添加规则");
        btnAddRule.getStyleClass().add("btn-success");
        Button btnUpdateRule = new Button("更新选中规则");
        btnUpdateRule.getStyleClass().add("btn-primary");
        Button btnDeleteRule = new Button("删除选中规则");
        btnDeleteRule.getStyleClass().add("btn-danger");
        btnBox.getChildren().addAll(btnAddRule, btnUpdateRule, btnDeleteRule);

        // 刷新规则列表
        Runnable refreshRules = () -> {
            lstRules.getItems().clear();
            for (AtReminderRule rule : configManager.getConfig().getAtReminderRules()) {
                String status = rule.isEnabled() ? "ON" : "OFF";
                lstRules.getItems().add("[" + status + "] " + rule.getName()
                        + " | 源群:" + rule.getSourceGroup()
                        + " -> 目标群:" + rule.getTargetGroup()
                        + " | @" + rule.getTargetPerson()
                        + " | " + rule.getTimeoutMinutes() + "分钟");
            }
        };
        refreshRules.run();

        // 选中规则时填充编辑区
        lstRules.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            int idx2 = lstRules.getSelectionModel().getSelectedIndex();
            if (idx2 >= 0 && idx2 < configManager.getConfig().getAtReminderRules().size()) {
                AtReminderRule r = configManager.getConfig().getAtReminderRules().get(idx2);
                txtRuleName.setText(r.getName());
                // \u6839\u636e username \u627e\u5230\u5bf9\u5e94\u7684\u4e0b\u62c9\u6846\u9879
                String srcUname = r.getSourceGroup();
                for (String item : cbSourceGroup.getItems()) {
                    if (item.contains(srcUname)) {
                        cbSourceGroup.setValue(item);
                        break;
                    }
                }
                txtTargetGroup.setText(r.getTargetGroup());
                txtTargetPerson.setText(r.getTargetPerson());
                txtTimeout.setText(String.valueOf(r.getTimeoutMinutes()));
                txtTemplate.setText(r.getReminderTemplate());
            }
        });

        // 添加规则
        btnAddRule.setOnAction(e -> {
            try {
                AtReminderRule rule = new AtReminderRule();
                rule.setName(txtRuleName.getText().trim());
                String srcDisplay = cbSourceGroup.getValue();
                rule.setSourceGroup(srcDisplay != null ? extractUsername(srcDisplay) : "");
                rule.setTargetGroup(txtTargetGroup.getText().trim());
                rule.setTargetPerson(txtTargetPerson.getText().trim());
                rule.setTimeoutMinutes(Integer.parseInt(txtTimeout.getText().trim()));
                rule.setReminderTemplate(txtTemplate.getText());
                if (rule.getName().isEmpty() || rule.getSourceGroup().isEmpty()
                        || rule.getTargetGroup().isEmpty() || rule.getTargetPerson().isEmpty()) {
                    showError("参数不完整", "请填写所有必填字段");
                    return;
                }
                configManager.getConfig().getAtReminderRules().add(rule);
                configManager.save();
                refreshRules.run();
                appendLog("已添加@提醒规则: " + rule.getName());
                // 更新tracker
                if (atReminderTracker != null) {
                    atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
                }
            } catch (NumberFormatException ex) {
                showError("格式错误", "超时分钟数必须是数字");
            }
        });

        // 更新选中规则
        btnUpdateRule.setOnAction(e -> {
            int idx2 = lstRules.getSelectionModel().getSelectedIndex();
            if (idx2 < 0) {
                showError("未选择", "请先选择要更新的规则");
                return;
            }
            try {
                AtReminderRule rule = configManager.getConfig().getAtReminderRules().get(idx2);
                rule.setName(txtRuleName.getText().trim());
                String srcDisplay2 = cbSourceGroup.getValue();
                rule.setSourceGroup(srcDisplay2 != null ? extractUsername(srcDisplay2) : "");
                rule.setTargetGroup(txtTargetGroup.getText().trim());
                rule.setTargetPerson(txtTargetPerson.getText().trim());
                rule.setTimeoutMinutes(Integer.parseInt(txtTimeout.getText().trim()));
                rule.setReminderTemplate(txtTemplate.getText());
                configManager.save();
                refreshRules.run();
                appendLog("已更新@提醒规则: " + rule.getName());
                if (atReminderTracker != null) {
                    atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
                }
            } catch (NumberFormatException ex) {
                showError("格式错误", "超时分钟数必须是数字");
            }
        });

        // 删除选中规则
        btnDeleteRule.setOnAction(e -> {
            int idx2 = lstRules.getSelectionModel().getSelectedIndex();
            if (idx2 < 0) {
                showError("未选择", "请先选择要删除的规则");
                return;
            }
            String name = configManager.getConfig().getAtReminderRules().get(idx2).getName();
            configManager.getConfig().getAtReminderRules().remove(idx2);
            configManager.save();
            refreshRules.run();
            appendLog("已删除@提醒规则: " + name);
            if (atReminderTracker != null) {
                atReminderTracker.updateRules(configManager.getConfig().getAtReminderRules());
            }
        });

        panel.getChildren().addAll(desc, chkAtSwitch, lblRules, lstRules,
                lblEdit, grid, btnBox);
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

    private TextField createTextField(String prompt, double prefWidth) {
        TextField tf = new TextField();
        tf.setPromptText(prompt);
        tf.setPrefWidth(prefWidth);
        return tf;
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

    /** 重置一键初始化按钮状态 */
    private void resetButton(Button btn) {
        btn.setDisable(false);
        btn.setText("🚀 一键初始化");
    }

    /**
     * 显示带"重试"按钮的错误对话框，点击重试将重新触发一键初始化
     */
    private void showRetryDialog(String title, String content, Button btnOneClick) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content != null ? content : "未知错误");

        ButtonType retryBtn = new ButtonType("🔄 重试", javafx.scene.control.ButtonBar.ButtonData.OK_DONE);
        ButtonType closeBtn = new ButtonType("关闭", javafx.scene.control.ButtonBar.ButtonData.CANCEL_CLOSE);
        alert.getButtonTypes().setAll(retryBtn, closeBtn);

        java.util.Optional<ButtonType> result = alert.showAndWait();
        if (result.isPresent() && result.get() == retryBtn) {
            btnOneClick.fire();
        }
    }

    /**
     * 检测微信窗口是否真正可见（通过 AppleScript 查询 System Events）
     */
    private boolean isWeChatWindowVisible() {
        try {
            // 先检查进程是否存在
            ProcessBuilder pb = new ProcessBuilder("pgrep", "-f", "WeChat_copy");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                if (reader.readLine() == null) {
                    p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);
                    return false; // 进程不存在
                }
            }
            p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS);

            // 进程存在，再通过 AppleScript 检查是否有可见窗口
            pb = new ProcessBuilder("osascript", "-e",
                    "tell application \"System Events\" to return (count of windows of (first process whose name is \"WeChat\")) > 0");
            p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
                return "true".equalsIgnoreCase(line != null ? line.trim() : "");
            }
        } catch (Exception e) {
            // 检测失败时返回 false，不影响主流程
            return false;
        }
    }
}





