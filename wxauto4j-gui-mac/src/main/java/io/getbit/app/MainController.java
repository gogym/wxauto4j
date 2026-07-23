package io.getbit.app;

import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ConfigManager;
import io.getbit.app.prompt.PromptManager;
import io.getbit.wxdb.WeChatDB;
import io.getbit.wxdb.WeChatDBConfig;
import io.getbit.wxdb.frida.FridaKeyExtractor;
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
    private PromptManager promptManager;

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

    /** 微信数据库 */
    private WeChatDB weChatDB;

    /** 消息监听器 */
    private MessageMonitor messageMonitor;

    /** 监听面板的消息显示区 */
    private TextArea txtMonitorMessages;

    /** 监听面板的监听列表 */
    private ListView<String> lstMonitoredContacts;

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
        TreeItem<String> dbKeyItem = new TreeItem<>("🔐 数据库密钥");
        TreeItem<String> promptItem = new TreeItem<>("📝 Prompt 管理");
        TreeItem<String> listenItem = new TreeItem<>("👂 私聊监听");
        TreeItem<String> groupItem = new TreeItem<>("👥 群组管理");
        TreeItem<String> keywordItem = new TreeItem<>("🔑 关键词回复");
        TreeItem<String> forwardItem = new TreeItem<>("🔀 自定义转发");
        TreeItem<String> scheduleItem = new TreeItem<>("⏰ 定时消息");
        TreeItem<String> friendItem = new TreeItem<>("🤝 新好友");
        TreeItem<String> momentsItem = new TreeItem<>("🌸 朋友圈");
        TreeItem<String> aiItem = new TreeItem<>("🤖 AI 接口");

        root.getChildren().addAll(
                statusItem, dbKeyItem, promptItem, listenItem, groupItem,
                keywordItem, forwardItem, scheduleItem, friendItem,
                momentsItem, aiItem
        );

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
        panels.put("数据库密钥", createDbKeyPanel());
        panels.put("Prompt 管理", createPromptPanel());
        panels.put("私聊监听", createListenPanel());
        panels.put("群组管理", createGroupPanel());
        panels.put("关键词回复", createKeywordPanel());
        panels.put("自定义转发", createForwardPanel());
        panels.put("定时消息", createSchedulePanel());
        panels.put("新好友", createFriendPanel());
        panels.put("朋友圈", createMomentsPanel());
        panels.put("AI 接口", createAiPanel());
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
     * 创建状态面板
     */
    private Node createStatusPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(16));

        Label title = new Label("运行状态");
        title.getStyleClass().add("section-title");

        Label lblConfigStatus = new Label("配置状态: 已加载");
        Label lblConfigDir = new Label("配置目录: " + configManager.getConfigDir());
        Label lblKeyStatus = new Label();

        // 检查密钥配置状态
        String rawKey = configManager.getConfig().getWxRawKey();
        if (rawKey != null && rawKey.length() == 64) {
            lblKeyStatus.setText("数据库密钥: ✅ 已配置 (" + rawKey.substring(0, 8) + "...)");
        } else {
            lblKeyStatus.setText("数据库密钥: ❌ 未配置（请在「数据库密钥」面板提取）");
        }

        // 刷新按钮
        Button btnRefresh = new Button("刷新状态");
        btnRefresh.getStyleClass().add("btn-default");
        btnRefresh.setOnAction(e -> {
            lblConfigStatus.setText("配置状态: 已加载");
            lblConfigDir.setText("配置目录: " + configManager.getConfigDir());
            String key = configManager.getConfig().getWxRawKey();
            if (key != null && key.length() == 64) {
                lblKeyStatus.setText("数据库密钥: ✅ 已配置 (" + key.substring(0, 8) + "...)");
            } else {
                lblKeyStatus.setText("数据库密钥: ❌ 未配置（请在「数据库密钥」面板提取）");
            }
        });

        panel.getChildren().addAll(title, lblConfigStatus, lblConfigDir, lblKeyStatus, btnRefresh);
        return panel;
    }

    /**
     * 创建数据库密钥面板
     */
    private Node createDbKeyPanel() {
        VBox panel = new VBox(12);
        panel.setPadding(new Insets(16));

        Label title = new Label("微信数据库密钥提取");
        title.getStyleClass().add("section-title");

        Label desc = new Label("操作流程：\n1. 点击「初始化」创建微信无签名副本（首次约需1分钟）\n2. 点击「启动微信并提取密钥」，微信会自动启动，扫码登录后密钥自动提取");
        desc.setWrapText(true);
        desc.getStyleClass().add("sub-title");

        // 当前密钥状态
        HBox statusBox = new HBox(8);
        statusBox.setAlignment(Pos.CENTER_LEFT);
        Label lblKeyStatus = new Label("当前状态: ");
        String currentKey = configManager.getConfig().getWxRawKey();
        if (currentKey != null && currentKey.length() == 64) {
            lblKeyStatus.setText("当前状态: ✅ 已配置 (" + currentKey.substring(0, 8) + "...)");
        } else {
            lblKeyStatus.setText("当前状态: ❌ 未配置");
        }

        // 密钥显示区
        Label lblKey = new Label("Raw Key:");
        TextField txtKey = new TextField();
        txtKey.setPromptText("64位 hex 密钥，可通过 Frida 自动提取或手动填入");
        txtKey.setPrefWidth(500);
        if (currentKey != null && !currentKey.isEmpty()) {
            txtKey.setText(currentKey);
        }

        // 按钮区
        HBox btnBox = new HBox(8);
        btnBox.setAlignment(Pos.CENTER_LEFT);
        Button btnInit = new Button("📦 初始化");
        btnInit.getStyleClass().add("btn-default");
        Button btnStartAndExtract = new Button("🚀 启动微信并提取密钥");
        btnStartAndExtract.getStyleClass().add("btn-primary");
        Button btnSaveKey = new Button("💾 保存密钥");
        btnSaveKey.getStyleClass().add("btn-success");
        Button btnDecrypt = new Button("🔓 初始化数据库");
        btnDecrypt.getStyleClass().add("btn-success");
        Button btnTestKey = new Button("🧪 测试密钥");
        btnTestKey.getStyleClass().add("btn-default");

        // 初始化按钮事件：检查/创建无签名副本
        btnInit.setOnAction(e -> {
            btnInit.setDisable(true);
            btnInit.setText("⏳ 初始化中...");
            appendLog("正在检查微信无签名副本...");

            executor.submit(() -> {
                try {
                    String copyBin = "/tmp/WeChat_copy.app/Contents/MacOS/WeChat";
                    java.io.File copyFile = new java.io.File(copyBin);

                    if (copyFile.exists()) {
                        Platform.runLater(() -> {
                            btnInit.setDisable(false);
                            btnInit.setText("📦 初始化");
                            appendLog("✅ 初始化完成：无签名副本已存在");
                        });
                        return;
                    }

                    // 查找原始微信
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
                            btnInit.setDisable(false);
                            btnInit.setText("📦 初始化");
                            appendLog("❌ 未找到微信安装");
                            showError("初始化失败", "未找到微信安装，请确保已安装微信");
                        });
                        return;
                    }

                    appendLog("正在拷贝微信到 /tmp 并移除签名（约需1分钟）...");

                    // 删除旧副本
                    new ProcessBuilder("rm", "-rf", "/tmp/WeChat_copy.app").start().waitFor(10, java.util.concurrent.TimeUnit.SECONDS);

                    // 拷贝
                    Process cpProc = new ProcessBuilder("cp", "-R", originalApp, "/tmp/WeChat_copy.app").start();
                    cpProc.waitFor(120, java.util.concurrent.TimeUnit.SECONDS);
                    if (cpProc.exitValue() != 0) {
                        throw new RuntimeException("拷贝微信失败");
                    }

                    // 移除签名
                    Process signProc = new ProcessBuilder("codesign", "--remove-signature", "/tmp/WeChat_copy.app").start();
                    signProc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
                    if (signProc.exitValue() != 0) {
                        throw new RuntimeException("移除签名失败");
                    }

                    Platform.runLater(() -> {
                        btnInit.setDisable(false);
                        btnInit.setText("📦 初始化");
                        appendLog("✅ 初始化完成：无签名副本已创建");
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        btnInit.setDisable(false);
                        btnInit.setText("📦 初始化");
                        appendLog("❌ 初始化失败: " + ex.getMessage());
                        showError("初始化失败", ex.getMessage());
                    });
                }
            });
        });

        // 启动微信并提取密钥（spawn 模式：frida 启动微信副本，hook PBKDF，用户扫码登录后自动提取）
        btnStartAndExtract.setOnAction(e -> {
            btnStartAndExtract.setDisable(true);
            btnStartAndExtract.setText("⏳ 启动中...");
            appendLog("⚠️ 将通过 Frida 启动微信并提取密钥，请准备扫码登录...");

            executor.submit(() -> {
                try {
                    FridaKeyExtractor extractor = new FridaKeyExtractor();
                    String key = extractor.extractKey();

                    Platform.runLater(() -> {
                        btnStartAndExtract.setDisable(false);
                        btnStartAndExtract.setText("🚀 启动微信并提取密钥");
                        if (key != null) {
                            txtKey.setText(key);
                            lblKeyStatus.setText("当前状态: ✅ 已提取 (" + key.substring(0, 8) + "...)");
                            appendLog("密钥提取成功: " + key.substring(0, 8) + "...");
                            configManager.getConfig().setWxRawKey(key);
                            configManager.save();
                            appendLog("密钥已自动保存到配置文件");
                        } else {
                            appendLog("密钥提取失败");
                            showError("提取失败", "无法提取密钥，请确保:\n1. 已点击「初始化」创建无签名副本\n2. frida CLI 已安装 (brew install frida-tools)");
                        }
                    });
                } catch (Exception ex) {
                    Platform.runLater(() -> {
                        btnStartAndExtract.setDisable(false);
                        btnStartAndExtract.setText("🚀 启动微信并提取密钥");
                        appendLog("提取异常: " + ex.getMessage());
                        showError("提取异常", ex.getMessage());
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
            lblKeyStatus.setText("当前状态: ✅ 已配置 (" + key.substring(0, 8) + "...)");
            appendLog("密钥已保存到配置文件");
        });

        // 初始化数据库按钮事件（直接连接加密 DB，无需解密）
        btnDecrypt.setOnAction(e -> {
            String key = txtKey.getText().trim();
            if (key.length() != 64) {
                showError("未配置", "请先提取或输入 64 位 hex 密钥");
                return;
            }
            btnDecrypt.setDisable(true);
            btnDecrypt.setText("⏳ 初始化中...");
            appendLog("正在初始化数据库连接...");

            executor.submit(() -> {
                try {
                    AppConfig cfg = configManager.getConfig();
                    WeChatDBConfig dbConfig = WeChatDBConfig.fromRawKey(key)
                            .wechatDataDir(cfg.getWxDataDir());

                    Platform.runLater(() -> appendLog("正在连接加密数据库..."));

                    // 直接初始化 WeChatDB（通过 sqlite-jdbc-crypt 连接加密 DB）
                    weChatDB = new WeChatDB(dbConfig);
                    weChatDB.init();
                    messageMonitor = new MessageMonitor(weChatDB);

                    // 设置新消息回调
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

                    Platform.runLater(() -> {
                        btnDecrypt.setDisable(false);
                        btnDecrypt.setText("🔓 初始化数据库");
                        appendLog("✅ 数据库连接已就绪，可以直接查询和监听消息");
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
                        btnDecrypt.setText("🔓 初始化数据库");
                        appendLog("❌ 初始化失败: " + finalErr);
                        showError("初始化失败", finalErr);
                    });
                }
            });
        });

        // 测试按钮事件
        btnTestKey.setOnAction(e -> {
            String key = txtKey.getText().trim();
            if (key.length() != 64) {
                showError("未配置", "请先提取或输入密钥");
                return;
            }
            appendLog("密钥格式验证通过: " + key.substring(0, 8) + "...");
        });

        btnBox.getChildren().addAll(btnInit, btnStartAndExtract, btnSaveKey, btnDecrypt, btnTestKey);
        panel.getChildren().addAll(title, desc, statusBox, lblKey, txtKey, btnBox);
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
        Button btnStartMonitor = new Button("▶ 开始监听");
        btnStartMonitor.getStyleClass().add("btn-success");
        Button btnStopMonitor = new Button("⏹ 停止监听");
        btnStopMonitor.getStyleClass().add("btn-danger");
        Button btnRefresh = new Button("🔄 刷新消息");
        btnRefresh.getStyleClass().add("btn-default");
        controlBox.getChildren().addAll(btnStartMonitor, btnStopMonitor, btnRefresh);

        // 监听列表
        Label lblMonitored = new Label("监听中的联系人:");
        lblMonitored.getStyleClass().add("sub-title");
        lstMonitoredContacts = new ListView<>();
        lstMonitoredContacts.setPrefHeight(80);

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

        btnStartMonitor.setOnAction(e -> {
            if (weChatDB == null || messageMonitor == null) {
                appendLog("❗ 请先初始化数据库");
                return;
            }
            String selected = lstSearchResults.getSelectionModel().getSelectedItem();
            if (selected == null) {
                showError("未选择", "请先搜索并选择一个联系人");
                return;
            }
            // 提取 username
            String username = extractUsername(selected);
            if (username != null) {
                messageMonitor.startMonitoring(username);
                refreshMonitoredList();
                appendLog("✅ 开始监听: " + selected);
                // 立即拉取一次最新消息
                loadMessagesForContact(username);
            }
        });

        btnStopMonitor.setOnAction(e -> {
            if (messageMonitor == null) return;
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
        });

        btnRefresh.setOnAction(e -> {
            if (messageMonitor == null) return;
            String selected = lstMonitoredContacts.getSelectionModel().getSelectedItem();
            if (selected != null) {
                String username = extractUsername(selected);
                if (username != null) {
                    loadMessagesForContact(username);
                }
            }
        });

        // 点击监听列表时加载消息
        lstMonitoredContacts.getSelectionModel().selectedItemProperty().addListener((obs, oldVal, newVal) -> {
            if (newVal != null && messageMonitor != null) {
                String username = extractUsername(newVal);
                if (username != null) {
                    loadMessagesForContact(username);
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
     * 加载指定联系人的最新消息
     */
    private void loadMessagesForContact(String username) {
        if (messageMonitor == null) return;
        executor.submit(() -> {
            try {
                var messages = messageMonitor.fetchRecentMessages(username, 50);
                Platform.runLater(() -> {
                    txtMonitorMessages.clear();
                    for (var mm : messages) {
                        txtMonitorMessages.appendText(mm.toDisplayString() + "\n");
                    }
                    txtMonitorMessages.setScrollTop(Double.MAX_VALUE);
                });
            } catch (Exception e) {
                Platform.runLater(() -> appendLog("❗ 加载消息失败: " + e.getMessage()));
            }
        });
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
     * 创建关键词回复面板
     */
    private Node createKeywordPanel() {
        VBox panel = new VBox(10);
        panel.setPadding(new Insets(16));

        Label title = new Label("关键词回复配置");
        title.getStyleClass().add("section-title");

        CheckBox chkChatKeyword = new CheckBox("开启私聊关键词回复");
        CheckBox chkGroupKeyword = new CheckBox("开启群聊关键词回复");
        CheckBox chkGroupAtOnly = new CheckBox("群聊关键词回复是否仅 @ 时触发");

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

