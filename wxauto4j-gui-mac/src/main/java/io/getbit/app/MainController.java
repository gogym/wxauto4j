package io.getbit.app;

import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ConfigManager;
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

    /** 密钥输入框 */
    private TextField txtKey;

    /** 密钥状态标签 */
    private Label lblDbKeyStatus;

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
        TreeItem<String> groupItem = new TreeItem<>("👥 群组管理");

        root.getChildren().addAll(statusItem, listenItem, groupItem);

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

        Label desc = new Label("点击「一键初始化」，自动完成：创建微信无签名副本 → 启动微信 → 扫码登录 → 提取密钥 → 保存配置 → 清理副本");
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
                        messageMonitor.setOnNewMessage(mm -> Platform.runLater(() -> {
                            if (txtMonitorMessages != null) {
                                txtMonitorMessages.appendText(mm.toDisplayString() + "\n");
                                txtMonitorMessages.setScrollTop(Double.MAX_VALUE);
                            }
                            appendLog("📩 [" + mm.getChatDisplayName() + "] " +
                                    mm.getSenderDisplayName() + ": " +
                                    (mm.getContent() != null && mm.getContent().length() > 50
                                            ? mm.getContent().substring(0, 50) + "..."
                                            : mm.getContent()));
                        }));
                        Platform.runLater(() -> appendLog("✅ [4/5] 数据库连接已就绪"));
                    } catch (Exception ex) {
                        Platform.runLater(() -> appendLog("⚠️ [4/5] 数据库初始化失败（可稍后手动初始化）: " + ex.getMessage()));
                    }

                    // ===== 步骤5：删除无签名副本 =====
                    Platform.runLater(() -> appendLog("🗑 [5/5] 正在清理微信无签名副本..."));
                    try {
                        new ProcessBuilder("rm", "-rf", "/tmp/WeChat_copy.app").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                        Platform.runLater(() -> appendLog("✅ [5/5] 副本已清理"));
                    } catch (Exception ex) {
                        Platform.runLater(() -> appendLog("⚠️ [5/5] 清理副本失败（可手动删除 /tmp/WeChat_copy.app）: " + ex.getMessage()));
                    }

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

        btnBox.getChildren().addAll(btnOneClick, btnSaveKey, btnDecrypt);
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

        // 搜索结果列表
        ListView<String> lstSearchResults = new ListView<>();
        lstSearchResults.setPrefHeight(120);

        // ===== 监听控制区域 =====
        HBox controlBox = new HBox(8);
        controlBox.setAlignment(Pos.CENTER_LEFT);
        Button btnToggleMonitor = new Button("▶ 开始监听");
        btnToggleMonitor.getStyleClass().add("btn-success");
        Button btnRefresh = new Button("📜 加载历史消息");
        btnRefresh.getStyleClass().add("btn-default");
        controlBox.getChildren().addAll(btnToggleMonitor, btnRefresh);

        // 监听列表
        Label lblMonitored = new Label("监听中的联系人:");
        lblMonitored.getStyleClass().add("sub-title");
        lstMonitoredContacts = new ListView<>();
        lstMonitoredContacts.setPrefHeight(120);
        lstMonitoredContacts.setStyle("-fx-border-color: #d0d0d0; -fx-background-color: #f7f7f7;");

        // ===== 消息显示区域 =====
        Label lblMessages = new Label("消息记录:");
        lblMessages.getStyleClass().add("sub-title");
        txtMonitorMessages = new TextArea();
        txtMonitorMessages.setEditable(false);
        txtMonitorMessages.setWrapText(true);
        txtMonitorMessages.setPrefRowCount(15);
        VBox.setVgrow(txtMonitorMessages, javafx.scene.layout.Priority.ALWAYS);

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

        btnToggleMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            boolean isMonitoring = btnToggleMonitor.getText().contains("停止");
            if (isMonitoring) {
                // 当前是监听状态 → 停止监听
                String selected = lstMonitoredContacts.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    showError("未选择", "请先选择要停止监听的联系人");
                    return;
                }
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.stopMonitoring(username);
                    refreshMonitoredList();
                    appendLog("⏹ 停止监听: " + selected);
                }
                btnToggleMonitor.setText("▶ 开始监听");
                btnToggleMonitor.getStyleClass().removeAll("btn-danger");
                btnToggleMonitor.getStyleClass().add("btn-success");
            } else {
                // 当前未监听 → 开始监听
                String selected = lstSearchResults.getSelectionModel().getSelectedItem();
                if (selected == null) {
                    showError("未选择", "请先搜索并选择一个联系人");
                    return;
                }
                String username = extractUsername(selected);
                if (username != null) {
                    messageMonitor.startMonitoring(username);
                    refreshMonitoredList();
                    appendLog("✅ 开始监听: " + selected);
                    // 自动选中刚添加的联系人，确保回调能正常工作
                    for (int i = 0; i < lstMonitoredContacts.getItems().size(); i++) {
                        if (lstMonitoredContacts.getItems().get(i).contains(username)) {
                            lstMonitoredContacts.getSelectionModel().select(i);
                            break;
                        }
                    }
                    // 设置新消息回调，自动追加新消息
                    messageMonitor.setOnNewMessage(mm -> {
                        Platform.runLater(() -> {
                            txtMonitorMessages.appendText(mm.toDisplayString() + "\n");
                            txtMonitorMessages.setScrollTop(Double.MAX_VALUE);
                        });
                    });
                    appendLog("✅ 已开始监听新消息（不含历史）");
                }
                btnToggleMonitor.setText("⏹ 停止监听");
                btnToggleMonitor.getStyleClass().removeAll("btn-success");
                btnToggleMonitor.getStyleClass().add("btn-danger");
            }
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

        // 点击监听列表时加载历史消息
        lstMonitoredContacts.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && weChatDB != null) {
                String username = extractUsername(newVal);
                if (username != null) {
                    loadHistoryMessages(username);
                }
            }
        });

        panel.getChildren().addAll(title, lblSearch, searchBox, lstSearchResults,
                controlBox, lblMonitored, lstMonitoredContacts, lblMessages, txtMonitorMessages);
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
            // 按 localId 去重（保留最后出现的）
            java.util.LinkedHashMap<Long, ChatMessage> deduped = new java.util.LinkedHashMap<>();
            for (var m : msgs) {
                deduped.put(m.getLocalId(), m);
            }
            var unique = new java.util.ArrayList<>(deduped.values());
            // 按 create_time 正序排列
            unique.sort((a, b) -> Long.compare(a.getCreateTime(), b.getCreateTime()));
            // 解析发送者昵称并格式化
            java.util.Map<String, String> nameCache = new java.util.HashMap<>();
            txtMonitorMessages.clear();
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
                txtMonitorMessages.appendText(String.format("[%s] %s: %s%s\n", time, senderDisplay, typeTag, content != null ? content : ""));
            }
            txtMonitorMessages.setScrollTop(Double.MAX_VALUE);
            appendLog("📩 已加载 " + unique.size() + " 条历史消息（去重后）");
        } catch (Exception e) {
            appendLog("❗ 加载历史消息失败: " + e.getMessage());
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





