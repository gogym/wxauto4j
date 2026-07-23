package io.getbit.wxdb.monitor;

import io.getbit.wxdb.WeChatDB;
import io.getbit.wxdb.model.ChatMessage;
import io.getbit.wxdb.model.Contact;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 好友消息监听器
 * <p>
 * 通过轮询已解密的消息数据库，检测指定联系人的新消息。
 * 支持启动/停止监听、增量检测新消息、解析发送者信息。
 */
public class MessageMonitor {

    private static final Logger LOG = Logger.getLogger(MessageMonitor.class.getName());
    private static final DateTimeFormatter TIME_FMT =
            DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());

    private final WeChatDB db;
    private final ScheduledExecutorService scheduler;
    private final Map<String, Long> lastLocalIds = new ConcurrentHashMap<>();
    private final Map<String, Long> monitorStartTimes = new ConcurrentHashMap<>();
    private final Set<String> monitoredUsernames = ConcurrentHashMap.newKeySet();
    private final Map<String, Contact> contactCache = new ConcurrentHashMap<>();
    private Consumer<MonitoredMessage> onNewMessage;
    private ScheduledFuture<?> pollTask;

    public MessageMonitor(WeChatDB db) {
        this.db = db;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "msg-monitor");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 设置新消息回调
     */
    public void setOnNewMessage(Consumer<MonitoredMessage> callback) {
        this.onNewMessage = callback;
    }

    /**
     * 开始监听指定联系人
     *
     * @param username 联系人 username
     */
    public void startMonitoring(String username) {
        if (monitoredUsernames.contains(username)) {
            return;
        }
        monitoredUsernames.add(username);
        // 记录监听开始时间（Unix 秒），只查该时间之后的消息，避免加载历史消息
        long startTime = System.currentTimeMillis() / 1000;
        monitorStartTimes.put(username, startTime);
        lastLocalIds.put(username, 0L);
        System.out.println("[监听] " + username + " 开始监听，起始时间=" + startTime);
        LOG.info("开始监听: " + username + " (起始时间=" + startTime + ")");

        // 确保轮询任务在运行
        ensurePolling();
    }

    /**
     * 停止监听指定联系人
     */
    public void stopMonitoring(String username) {
        monitoredUsernames.remove(username);
        lastLocalIds.remove(username);
        monitorStartTimes.remove(username);
        LOG.info("停止监听: " + username);

        if (monitoredUsernames.isEmpty() && pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /**
     * 停止所有监听
     */
    public void stopAll() {
        monitoredUsernames.clear();
        lastLocalIds.clear();
        monitorStartTimes.clear();
        if (pollTask != null) {
            pollTask.cancel(false);
            pollTask = null;
        }
    }

    /**
     * 是否正在监听指定联系人
     */
    public boolean isMonitoring(String username) {
        return monitoredUsernames.contains(username);
    }

    /**
     * 获取当前监听列表
     */
    public Set<String> getMonitoredUsernames() {
        return Collections.unmodifiableSet(monitoredUsernames);
    }

    /**
     * 手动拉取一次指定联系人的最新消息（不依赖轮询）
     *
     * @param username 联系人
     * @param limit    最多返回条数
     * @return 消息列表（已解析发送者名称）
     */
    public List<MonitoredMessage> fetchRecentMessages(String username, int limit) {
        List<ChatMessage> msgs = db.getRecentMessages(username, limit);
        List<MonitoredMessage> result = new ArrayList<>();
        for (ChatMessage m : msgs) {
            result.add(toMonitoredMessage(m, username));
        }
        // 按时间正序返回
        Collections.reverse(result);
        return result;
    }

    /**
     * 搜索联系人
     */
    public List<Contact> searchContacts(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return db.getRecentContacts(50);
        }
        return db.searchContacts(keyword.trim());
    }

    /**
     * 搜索群聊
     */
    public List<Contact> searchChatrooms(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return db.getChatrooms();
        }
        return db.searchChatrooms(keyword.trim());
    }

    // ========== 内部方法 ==========

    private void ensurePolling() {
        if (pollTask != null && !pollTask.isDone()) {
            System.out.println("[监听] 轮询任务已在运行");
            return;
        }
        System.out.println("[监听] 创建轮询任务，间隔5秒");
        pollTask = scheduler.scheduleWithFixedDelay(this::pollNewMessages, 1, 5, TimeUnit.SECONDS);
    }

    private void pollNewMessages() {
        System.out.println("[轮询心跳] 监听列表=" + monitoredUsernames + ", lastLocalIds=" + lastLocalIds);
        for (String username : monitoredUsernames) {
            try {
                Long lastId = lastLocalIds.get(username);
                Long startTime = monitorStartTimes.get(username);
                if (lastId == null || startTime == null) {
                    System.out.println("[轮询] " + username + " lastId或startTime为null，跳过");
                    continue;
                }

                System.out.println("[轮询] " + username + " 准备查询 startTime=" + startTime + " lastId=" + lastId + " ...");
                long t0 = System.currentTimeMillis();
                List<ChatMessage> newMsgs = db.getMessagesSince(username, startTime, lastId);
                long elapsed = System.currentTimeMillis() - t0;
                System.out.println("[轮询] " + username + " 查询完成，查到 " + newMsgs.size() + " 条 (耗时" + elapsed + "ms)");

                if (!newMsgs.isEmpty()) {
                    // 先更新 lastLocalId，防止处理异常时下次重复查询
                    long maxId = newMsgs.get(newMsgs.size() - 1).getLocalId();
                    lastLocalIds.put(username, maxId);

                    for (ChatMessage m : newMsgs) {
                        try {
                            System.out.println("[轮询] 处理消息 localId=" + m.getLocalId() + " type=" + m.getLocalType());
                            MonitoredMessage mm = toMonitoredMessage(m, username);
                            System.out.println("[轮询] 消息解析完成: " + mm.toDisplayString());
                            if (onNewMessage != null) {
                                onNewMessage.accept(mm);
                                System.out.println("[轮询] 回调完成");
                            }
                        } catch (Exception ex) {
                            System.err.println("[轮询] 处理单条消息异常 localId=" + m.getLocalId() + ": " + ex.getMessage());
                            ex.printStackTrace();
                        }
                    }
                }
            } catch (Exception e) {
                System.err.println("[轮询] 消息异常 " + username + ": " + e.getMessage());
                e.printStackTrace();
            }
        }
        System.out.println("[轮询心跳] 本轮结束");
    }

    private MonitoredMessage toMonitoredMessage(ChatMessage m, String chatUsername) {
        MonitoredMessage mm = new MonitoredMessage();
        mm.setLocalId(m.getLocalId());
        mm.setCreateTime(m.getCreateTime());
        mm.setLocalType(m.getLocalType());
        mm.setChatUsername(chatUsername);

        // 解析发送者
        String senderUsername = null;
        String content = m.getMessageContent();

        if (m.getSenderId() != null && !m.getSenderId().isEmpty()) {
            try {
                int senderId = Integer.parseInt(m.getSenderId());
                senderUsername = db.resolveSenderUsername(senderId);
            } catch (NumberFormatException ignored) {
                senderUsername = m.getSenderId();
            }
        }

        // 对于群聊，message_content 格式是 "sender_username:\n实际内容"
        if (chatUsername.endsWith("@chatroom") && content != null && content.contains(":\n")) {
            int colonIdx = content.indexOf(":\n");
            String prefixUser = content.substring(0, colonIdx);
            String actualContent = content.substring(colonIdx + 2);
            if (senderUsername == null || senderUsername.startsWith("unknown_")) {
                senderUsername = prefixUser;
            }
            content = actualContent;
        }

        mm.setSenderUsername(senderUsername);
        mm.setContent(content);

        // 解析联系人显示名（使用缓存）
        Contact contact = getContactCached(chatUsername);
        if (contact != null) {
            mm.setChatDisplayName(contact.getDisplayName());
        }

        // 解析发送者显示名（使用缓存）
        if (senderUsername != null) {
            Contact senderContact = getContactCached(senderUsername);
            if (senderContact != null) {
                mm.setSenderDisplayName(senderContact.getDisplayName());
            }
        }

        return mm;
    }

    /**
     * 带缓存的联系人查询，避免每条消息都查数据库
     */
    private Contact getContactCached(String username) {
        return contactCache.computeIfAbsent(username, u -> {
            try {
                return db.getContact(u);
            } catch (Exception e) {
                LOG.log(Level.FINE, "查询联系人失败: " + u, e);
                return null;
            }
        });
    }

    /**
     * 监听到的消息
     */
    public static class MonitoredMessage {
        private long localId;
        private long createTime;
        private int localType;
        private String chatUsername;
        private String chatDisplayName;
        private String senderUsername;
        private String senderDisplayName;
        private String content;

        public long getLocalId() { return localId; }
        public void setLocalId(long localId) { this.localId = localId; }

        public long getCreateTime() { return createTime; }
        public void setCreateTime(long createTime) { this.createTime = createTime; }

        public int getLocalType() { return localType; }
        public void setLocalType(int localType) { this.localType = localType; }

        public String getChatUsername() { return chatUsername; }
        public void setChatUsername(String chatUsername) { this.chatUsername = chatUsername; }

        public String getChatDisplayName() { return chatDisplayName; }
        public void setChatDisplayName(String chatDisplayName) { this.chatDisplayName = chatDisplayName; }

        public String getSenderUsername() { return senderUsername; }
        public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }

        public String getSenderDisplayName() { return senderDisplayName; }
        public void setSenderDisplayName(String senderDisplayName) { this.senderDisplayName = senderDisplayName; }

        public String getContent() { return content; }
        public void setContent(String content) { this.content = content; }

        /**
         * 获取消息类型描述
         */
        public String getTypeDescription() {
            switch (localType) {
                case 1: return "文本";
                case 3: return "图片";
                case 34: return "语音";
                case 42: return "名片";
                case 43: return "视频";
                case 47: return "表情";
                case 48: return "位置";
                case 49: return "链接/文件";
                case 10000: return "系统消息";
                case 10002: return "撤回消息";
                default: return "类型" + localType;
            }
        }

        /**
         * 格式化的时间
         */
        public String getFormattedTime() {
            if (createTime <= 0) return "";
            return TIME_FMT.format(Instant.ofEpochSecond(createTime));
        }

        /**
         * 用于显示的单行文本
         */
        public String toDisplayString() {
            String time = getFormattedTime();
            String sender = senderDisplayName != null ? senderDisplayName :
                    (senderUsername != null ? senderUsername : "未知");
            String typeTag = localType != 1 ? "[" + getTypeDescription() + "] " : "";
            String text = content != null ? content : "";
            if (text.length() > 200) text = text.substring(0, 200) + "...";
            return String.format("[%s] %s: %s%s", time, sender, typeTag, text);
        }

        @Override
        public String toString() {
            return toDisplayString();
        }
    }
}
