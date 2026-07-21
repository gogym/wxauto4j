package io.getbit.app;

import io.getbit.WeChat;
import io.getbit.elements.Message;
import javafx.application.Platform;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.scene.control.*;
import javafx.scene.input.KeyCode;

import java.net.URL;
import java.util.List;
import java.util.ResourceBundle;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 主界面控制器
 *
 * <p>处理 UI 事件，在后台线程中执行微信自动化操作，
 * 通过 {@link Platform#runLater} 将结果回写到 JavaFX 线程。</p>
 */
public class MainController implements Initializable {

    @FXML
    private Button btnConnect;

    @FXML
    private Label lblStatus;

    @FXML
    private ListView<String> listSessions;

    @FXML
    private ListView<String> listMessages;

    @FXML
    private TextField txtRecipient;

    @FXML
    private TextArea txtMessage;

    @FXML
    private Button btnSend;

    @FXML
    private Button btnRefresh;

    /** 微信实例（连接后初始化） */
    private WeChat weChat;

    /** 后台线程池，用于执行耗时的 UIAutomation 操作 */
    private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "wx-worker");
        t.setDaemon(true);
        return t;
    });

    /** 会话列表数据 */
    private final ObservableList<String> sessionItems = FXCollections.observableArrayList();

    /** 消息列表数据 */
    private final ObservableList<String> messageItems = FXCollections.observableArrayList();

    @Override
    public void initialize(URL location, ResourceBundle resources) {
        listSessions.setItems(sessionItems);
        listMessages.setItems(messageItems);

        // 双击会话列表项 → 切换到该聊天
        listSessions.setOnMouseClicked(event -> {
            if (event.getClickCount() == 2) {
                String selected = listSessions.getSelectionModel().getSelectedItem();
                if (selected != null && weChat != null) {
                    txtRecipient.setText(selected);
                    switchChat(selected);
                }
            }
        });

        // Ctrl+Enter 发送消息
        txtMessage.setOnKeyPressed(event -> {
            if (event.getCode() == KeyCode.ENTER && event.isControlDown()) {
                sendMessage();
            }
        });

        updateStatus(false);
    }

    // ==================== 按钮事件 ====================

    /**
     * 连接/断开微信
     */
    @FXML
    private void onConnect() {
        if (weChat != null) {
            // 断开
            weChat = null;
            sessionItems.clear();
            messageItems.clear();
            updateStatus(false);
            return;
        }

        // 连接
        setBusy(true, "正在连接微信...");
        executor.submit(() -> {
            try {
                weChat = new WeChat();
                Platform.runLater(() -> {
                    updateStatus(true);
                    setBusy(false, "");
                    appendSystem("已连接到微信，昵称: " + weChat.getNickname());
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false, "");
                    showError("连接失败", e.getMessage());
                });
            }
        });
    }

    /**
     * 发送消息
     */
    @FXML
    private void onSend() {
        sendMessage();
    }

    /**
     * 刷新当前聊天消息
     */
    @FXML
    private void onRefresh() {
        if (weChat == null) {
            showError("未连接", "请先连接微信");
            return;
        }
        setBusy(true, "正在刷新消息...");
        executor.submit(() -> {
            try {
                List<Message> messages = weChat.GetAllMessage();
                Platform.runLater(() -> {
                    messageItems.clear();
                    for (Message msg : messages) {
                        messageItems.add(formatMessage(msg));
                    }
                    // 滚动到底部
                    if (!messageItems.isEmpty()) {
                        listMessages.scrollTo(messageItems.size() - 1);
                    }
                    setBusy(false, "");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false, "");
                    showError("刷新失败", e.getMessage());
                });
            }
        });
    }

    // ==================== 内部方法 ====================

    private void sendMessage() {
        if (weChat == null) {
            showError("未连接", "请先连接微信");
            return;
        }
        String who = txtRecipient.getText().trim();
        String msg = txtMessage.getText().trim();
        if (msg.isEmpty()) {
            return;
        }
        if (who.isEmpty()) {
            showError("缺少收件人", "请输入联系人或群名称");
            return;
        }

        setBusy(true, "正在发送...");
        executor.submit(() -> {
            try {
                weChat.ChatWith(who);
                weChat.SendMsg(msg);
                Platform.runLater(() -> {
                    appendSystem("→ 已发送给 " + who + ": " + msg);
                    txtMessage.clear();
                    setBusy(false, "");
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false, "");
                    showError("发送失败", e.getMessage());
                });
            }
        });
    }

    private void switchChat(String who) {
        setBusy(true, "正在切换到: " + who);
        executor.submit(() -> {
            try {
                weChat.ChatWith(who);
                Platform.runLater(() -> {
                    setBusy(false, "");
                    appendSystem("已切换到聊天: " + who);
                    // 自动刷新消息
                    onRefresh();
                });
            } catch (Exception e) {
                Platform.runLater(() -> {
                    setBusy(false, "");
                    showError("切换失败", e.getMessage());
                });
            }
        });
    }

    private void updateStatus(boolean connected) {
        if (connected) {
            lblStatus.setText("● 已连接");
            lblStatus.getStyleClass().removeAll("status-disconnected");
            lblStatus.getStyleClass().add("status-connected");
            btnConnect.setText("断开");
            btnSend.setDisable(false);
            btnRefresh.setDisable(false);
        } else {
            lblStatus.setText("○ 未连接");
            lblStatus.getStyleClass().removeAll("status-connected");
            lblStatus.getStyleClass().add("status-disconnected");
            btnConnect.setText("连接微信");
            btnSend.setDisable(true);
            btnRefresh.setDisable(true);
        }
    }

    private void setBusy(boolean busy, String text) {
        if (busy) {
            lblStatus.setText("⏳ " + text);
            btnConnect.setDisable(true);
            btnSend.setDisable(true);
            btnRefresh.setDisable(true);
        } else {
            btnConnect.setDisable(false);
            updateStatus(weChat != null);
        }
    }

    private void appendSystem(String text) {
        messageItems.add("[系统] " + text);
        listMessages.scrollTo(messageItems.size() - 1);
    }

    private String formatMessage(Message msg) {
        String prefix;
        switch (msg.getAttr()) {
            case Message.ATTR_SELF:
                prefix = "[我]";
                break;
            case Message.ATTR_FRIEND:
                prefix = "[" + (msg.getSender() != null ? msg.getSender() : "对方") + "]";
                break;
            case Message.ATTR_SYSTEM:
                if (msg.isTime()) {
                    prefix = "[时间]";
                } else if (msg.isRecall()) {
                    prefix = "[撤回]";
                } else {
                    prefix = "[系统]";
                }
                break;
            default:
                prefix = "[?]";
        }
        return prefix + " " + msg.getContent();
    }

    private void showError(String title, String content) {
        Alert alert = new Alert(Alert.AlertType.WARNING);
        alert.setTitle(title);
        alert.setHeaderText(null);
        alert.setContentText(content != null ? content : "未知错误");
        alert.showAndWait();
    }
}
