package io.getbit.app;

import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ConfigManager;
import io.getbit.wxdb.WeChatDB;
import io.getbit.wxdb.WeChatDBConfig;
import io.getbit.app.WinFridaKeyExtractor;
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
    private StackPane contentPane;

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
            contentPane.getChildren().setAll(panel);
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

        Label desc = new Label("点击「一键初始化」，自动完成：启动微信 → 提取密钥 → 保存配置");
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
                Label content = new Label("将通过 Frida 扫描微信进程内存，\n自动提取数据库密钥。\n请确保微信已登录。");
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
                    WinFridaKeyExtractor.cleanupFridaProcesses();
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
                    // ===== 步骤1：检查微信安装 =====
                    if (cancelled[0]) { resetButton(btnOneClick); return; }
                    String wechatPath = findWeChatWindows();
                    if (wechatPath == null) {
                        Platform.runLater(() -> {
                            if (waitingStage[0] != null) waitingStage[0].close();
                            btnOneClick.setDisable(false);
                            btnOneClick.setText("🚀 一键初始化");
                            appendLog("❌ 未找到微信安装");
                            showError("初始化失败", "未找到微信安装，请确保已安装微信");
                        });
                        return;
                    }
                    Platform.runLater(() -> appendLog("✅ [1/4] 已找到微信安装: " + wechatPath));

                    if (cancelled[0]) return;

                    // ===== 步骤2：通过内存扫描提取密钥 =====
                    if (cancelled[0]) { resetButton(btnOneClick); return; }
                    Platform.runLater(() -> {
                        appendLog("🔍 [2/4] 正在通过 Frida 扫描微信进程内存提取密钥...");
                        if (waitingHeader[0] != null) {
                            waitingHeader[0].setText("正在扫描微信进程内存...");
                            waitingContent[0].setText("Frida 将 attach 到运行中的微信进程，\n扫描内存提取数据库密钥。\n请稍候...");
                        }
                    });

                    WinFridaKeyExtractor extractor = new WinFridaKeyExtractor();
                    extractor.setTimeout(120);
                    String key = extractor.extractKey();
                    String fridaError = extractor.getLastError();

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
                        appendLog("✅ [2/4] 密钥提取成功: " + key.substring(0, 8) + "...");
                    });

                    // ===== 步骤3：保存密钥到配置 =====
                    configManager.getConfig().setWxRawKey(key);
                    configManager.save();
                    Platform.runLater(() -> appendLog("✅ [3/4] 密钥已保存到配置文件"));

                    // ===== 步骤4：初始化数据库 =====
                    Platform.runLater(() -> appendLog("🔓 [4/4] 正在初始化数据库连接..."));
                    try {
                        AppConfig cfg = configManager.getConfig();
                        WeChatDBConfig dbConfig = WeChatDBConfig.fromRawKey(key)
                                .wechatDataDir(cfg.getWxDataDir());
                        weChatDB = new WeChatDB(dbConfig);
                        weChatDB.init();
                        messageMonitor = new MessageMonitor(weChatDB);
                        setupMessageCallback();
                        Platform.runLater(() -> appendLog("✅ [4/4] 数据库连接已就绪"));
                    } catch (Exception ex) {
                        Platform.runLater(() -> appendLog("⚠️ [4/4] 数据库初始化失败（可稍后手动初始化）: " + ex.getMessage()));
                    }

                    // ===== 步骤4：完成 =====
                    Platform.runLater(() -> appendLog("✅ 流程完成"));

                    Platform.runLater(() -> {
                        btnOneClick.setDisable(false);
                        btnOneClick.setText("🚀 一键初始化");
                        appendLog("🎉 一键初始化完成！密钥已保存，数据库已就绪。");
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

        Button btnLaunchWeChat = new Button("💬 启动微信");
        btnLaunchWeChat.getStyleClass().add("btn-default");
        btnLaunchWeChat.setOnAction(e -> {
            appendLog("🚀 正在启动微信...");
            try {
                // Windows 上直接启动微信
                String wechatPath = findWeChatWindows();
                if (wechatPath != null) {
                    new ProcessBuilder("cmd", "/c", "start", "", wechatPath).start();
                    appendLog("✅ 微信已启动");
                } else {
                    showError("启动失败", "未找到微信安装路径");
                }
            } catch (Exception ex) {
                appendLog("❌ 启动微信失败: " + ex.getMessage());
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
        panel.setPadding(new Insets(16));

        Label title = new Label("群聊消息监听");
        title.getStyleClass().add("section-title");

        // ===== 搜索群聊区域 =====
        Label lblSearch = new Label("搜索群聊:");
        lblSearch.getStyleClass().add("sub-title");
        HBox searchBox = new HBox(8);
        searchBox.setAlignment(Pos.CENTER_LEFT);
        TextField txtSearchGroup = createTextField("输入群名搜索", 250);
        Button btnSearch = new Button("🔍 搜索");
        btnSearch.getStyleClass().add("btn-default");
        searchBox.getChildren().addAll(txtSearchGroup, btnSearch);

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
        Label lblMonitored = new Label("监听中的群聊（可多选停止）:");
        lblMonitored.getStyleClass().add("sub-title");
        lstMonitoredGroups = new ListView<>();
        lstMonitoredGroups.setPrefHeight(120);
        lstMonitoredGroups.getSelectionModel().setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        lstMonitoredGroups.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #f7f7f7;");

        // ===== 消息显示区域 =====
        Label lblMessages = new Label("消息记录:");
        lblMessages.getStyleClass().add("sub-title");
        txtGroupMessages = new TextArea();
        txtGroupMessages.setEditable(false);
        txtGroupMessages.setWrapText(true);
        txtGroupMessages.setPrefRowCount(12);
        VBox.setVgrow(txtGroupMessages, javafx.scene.layout.Priority.ALWAYS);

        // ===== 发送消息区域 =====
        Label lblSend = new Label("发送消息:");
        lblSend.getStyleClass().add("sub-title");
        TextField txtGroupSendMessage = createTextField("输入消息内容", 400);
        Button btnGroupSendMessage = new Button("📤 发送");
        btnGroupSendMessage.getStyleClass().add("btn-primary");
        HBox sendBox = new HBox(8, txtGroupSendMessage, btnGroupSendMessage);
        sendBox.setAlignment(Pos.CENTER_LEFT);

        // ===== 事件绑定 =====
        btnSearch.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            String keyword = txtSearchGroup.getText().trim();
            List<Contact> groups = messageMonitor.searchChatrooms(keyword);
            lstSearchResults.getItems().clear();
            for (Contact c : groups) {
                String display = c.getDisplayName() + " (" + c.getUsername() + ")";
                lstSearchResults.getItems().add(display);
            }
            appendLog("搜索到 " + groups.size() + " 个群聊");
        });

        btnAddMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            var selectedItems = lstSearchResults.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("未选择", "请先搜索并选择一个或多个群聊");
                return;
            }
            var toAdd = new java.util.ArrayList<>(selectedItems);
            for (String selected : toAdd) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.startMonitoring(username);
                    chatBuffers.putIfAbsent(username, new StringBuilder());
                    appendLog("✅ 开始监听群聊: " + selected);
                }
            }
            refreshMonitoredGroupList();
            if (!toAdd.isEmpty() && !lstMonitoredGroups.getItems().isEmpty()) {
                skipGroupHistoryLoad = true;
                lstMonitoredGroups.getSelectionModel().select(lstMonitoredGroups.getItems().size() - 1);
            }
            appendLog("✅ 已添加 " + toAdd.size() + " 个群聊到监听列表");
        });

        btnStopMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            var selectedItems = lstMonitoredGroups.getSelectionModel().getSelectedItems();
            if (selectedItems == null || selectedItems.isEmpty()) {
                showError("未选择", "请先选择要停止监听的群聊");
                return;
            }
            var toStop = new java.util.ArrayList<>(selectedItems);
            for (String selected : toStop) {
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.stopMonitoring(username);
                    appendLog("⏹ 停止监听群聊: " + selected);
                }
            }
            refreshMonitoredGroupList();
        });

        btnRefresh.setOnAction(e -> {
            if (weChatDB == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            String selected = lstMonitoredGroups.getSelectionModel().getSelectedItem();
            if (selected == null) {
                selected = lstSearchResults.getSelectionModel().getSelectedItem();
            }
            if (selected != null) {
                String username = extractUsername(selected);
                if (username != null) {
                    appendLog("📜 正在加载群聊历史消息...");
                    loadGroupHistoryMessages(username);
                }
            } else {
                appendLog("❗ 请先选择群聊");
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

        // 发送消息按钮
        btnGroupSendMessage.setOnAction(e -> {
            String msg = txtGroupSendMessage.getText().trim();
            if (msg.isEmpty()) {
                showError("未输入", "请输入要发送的消息内容");
                return;
            }
            String selectedItem = lstMonitoredGroups.getSelectionModel().getSelectedItem();
            if (selectedItem == null) {
                showError("未选择", "请先在监听列表中选择一个群聊");
                return;
            }
            String chatName = extractDisplayName(selectedItem);
            appendLog("📤 正在发送消息到群聊 " + chatName + "...");
            btnGroupSendMessage.setDisable(true);
            executor.submit(() -> {
                String result = WeChatSender.sendTextMessage(chatName, msg);
                Platform.runLater(() -> {
                    btnGroupSendMessage.setDisable(false);
                    if ("ok".equals(result)) {
                        appendLog("✅ 消息已发送到群聊 " + chatName);
                        txtGroupSendMessage.clear();
                    } else {
                        appendLog("❗ 发送失败: " + result);
                    }
                });
            });
        });

        panel.getChildren().addAll(title, lblSearch, searchBox, lstSearchResults,
                controlBox, lblMonitored, lstMonitoredGroups, lblMessages, txtGroupMessages,
                lblSend, sendBox);
        return panel;
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
     * 检测微信窗口是否可见（通过 PowerShell 查询 Win32 API）
     */
    private boolean isWeChatWindowVisible() {
        try {
            // 微信 4.x (Weixin): 通过进程名查找可见主窗口
            // Qt 应用 MainWindowHandle 可能为0，进程存在即视为已启动
            String script =
                    "$procs = Get-Process -Name 'Weixin' -ErrorAction SilentlyContinue\n" +
                    "if ($procs) {\n" +
                    "    foreach ($p in $procs) {\n" +
                    "        if ($p.MainWindowHandle -ne [IntPtr]::Zero) { 'true'; exit }\n" +
                    "    }\n" +
                    "    # Qt 应用 MainWindowHandle 可能为0，进程存在即视为已启动\n" +
                    "    'true'; exit\n" +
                    "}\n" +
                    "'false'";

            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-Command", script);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                return "true".equalsIgnoreCase(line != null ? line.trim() : "");
            }
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * 在 Windows 上查找微信可执行文件路径
     */
    private static String findWeChatWindows() {
        // 微信 4.x 常见安装路径
        String[] candidates = {
                System.getenv("ProgramFiles") + "\\Tencent\\Weixin\\Weixin.exe",
                System.getenv("ProgramFiles(x86)") + "\\Tencent\\Weixin\\Weixin.exe",
                "C:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
                "C:\\Program Files (x86)\\Tencent\\Weixin\\Weixin.exe",
                "D:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
                "D:\\Program Files (x86)\\Tencent\\Weixin\\Weixin.exe",
                "E:\\Program Files\\Tencent\\Weixin\\Weixin.exe",
        };
        for (String path : candidates) {
            boolean exists = path != null && new java.io.File(path).exists();
            System.out.println("[findWeChatWindows] 检查: " + path + " -> " + exists);
            if (exists) {
                return path;
            }
        }

        // 通过注册表查找
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-ItemProperty 'HKLM:\\SOFTWARE\\WOW6432Node\\Microsoft\\Windows\\CurrentVersion\\Uninstall\\Weixin' -ErrorAction SilentlyContinue).InstallLocation");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty()) {
                    String installDir = line.trim().replace("\"", "");
                    String exePath = installDir + "\\Weixin.exe";
                    if (new java.io.File(exePath).exists()) {
                        return exePath;
                    }
                }
            }
        } catch (Exception ignored) {}

        // 通过 where 命令查找
        try {
            ProcessBuilder pb = new ProcessBuilder("where", "Weixin.exe");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty() && new java.io.File(line.trim()).exists()) {
                    return line.trim();
                }
            }
        } catch (Exception ignored) {}

        // 通过运行中的 Weixin 进程反查路径（最可靠的兜底方式）
        try {
            ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-Command", "(Get-Process Weixin -ErrorAction SilentlyContinue | Select-Object -First 1).Path");
            Process p = pb.start();
            try (java.io.BufferedReader reader = new java.io.BufferedReader(new java.io.InputStreamReader(p.getInputStream()))) {
                String line = reader.readLine();
                p.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
                if (line != null && !line.trim().isEmpty()) {
                    String exePath = line.trim().replace("\"", "");
                    System.out.println("[findWeChatWindows] 从运行进程找到: " + exePath);
                    if (new java.io.File(exePath).exists()) {
                        return exePath;
                    }
                }
            }
        } catch (Exception e) {
            System.out.println("[findWeChatWindows] 进程查找失败: " + e.getMessage());
        }

        System.out.println("[findWeChatWindows] 所有方式均未找到微信");
        return null;
    }
}





