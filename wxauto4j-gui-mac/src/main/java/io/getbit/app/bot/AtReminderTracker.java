package io.getbit.app.bot;

import io.getbit.app.WeChatSender;
import io.getbit.app.config.AtReminderRule;
import io.getbit.wxdb.WeChatDB;
import io.getbit.wxdb.model.Contact;
import io.getbit.wxdb.monitor.MessageMonitor;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;

/**
 * @提醒未回复追踪器
 *
 * <p>监听群消息，检测@特定人的消息。如果被@的人在超时时间内未在该群发言，
 * 则在指定的目标群发送提醒消息。</p>
 */
public class AtReminderTracker {

    private static final Logger LOG = Logger.getLogger(AtReminderTracker.class.getName());

    private final WeChatDB db;
    private final Consumer<String> logCallback;
    private final ScheduledExecutorService scheduler;

    /** 当前活跃的规则列表 */
    private volatile List<AtReminderRule> rules = new ArrayList<>();

    /**
     * 待触发的提醒：key = ruleIndex + "|" + atMessageLocalId
     * value = 定时任务（超时后发送提醒）
     */
    private final Map<String, ScheduledFuture<?>> pendingReminders = new ConcurrentHashMap<>();

    /**
     * 记录每个规则中，被@的人最后一次发言的时间戳（秒）
     * key = ruleIndex, value = Map&lt;targetPerson, lastSpeakTime&gt;
     */
    private final Map<Integer, Map<String, Long>> lastSpeakTimes = new ConcurrentHashMap<>();

    /** 用于发送消息的回调：(chatName, message) -> result */
    private volatile MessageSender messageSender;

    /** 群 username -> 显示名称 缓存 */
    private final Map<String, String> groupNameCache = new ConcurrentHashMap<>();

    public AtReminderTracker(WeChatDB db, Consumer<String> logCallback) {
        this.db = db;
        this.logCallback = logCallback;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "at-reminder");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 设置消息发送器
     */
    public void setMessageSender(MessageSender sender) {
        this.messageSender = sender;
    }

    /**
     * 更新规则列表
     */
    public void updateRules(List<AtReminderRule> newRules) {
        this.rules = new ArrayList<>(newRules);
        // 清理已删除规则的待触发提醒
        Set<String> validKeys = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            validKeys.add(i + "|");
        }
        Iterator<Map.Entry<String, ScheduledFuture<?>>> it = pendingReminders.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, ScheduledFuture<?>> entry = it.next();
            boolean valid = false;
            for (String key : validKeys) {
                if (entry.getKey().startsWith(key)) {
                    valid = true;
                    break;
                }
            }
            if (!valid) {
                entry.getValue().cancel(false);
                it.remove();
            }
        }
    }

    /**
     * 处理新消息，检测@和回复（支持多目标人）
     */
    public void onNewMessage(MessageMonitor.MonitoredMessage mm) {
        String chatUsername = mm.getChatUsername();
        if (chatUsername == null || !chatUsername.endsWith("@chatroom")) {
            return; // 只处理群消息
        }

        String senderUsername = mm.getSenderUsername();
        String senderDisplay = mm.getSenderDisplayName();
        String content = mm.getContent();
        if (content == null || content.isEmpty()) return;

        for (int i = 0; i < rules.size(); i++) {
            AtReminderRule rule = rules.get(i);
            if (!rule.isEnabled()) continue;
            if (!chatUsername.equals(rule.getSourceGroup())) continue;

            final int ruleIndex = i;
            // 支持逗号分隔的多个目标人
            String[] persons = splitPersons(rule.getTargetPerson());

            for (String person : persons) {
                // 检查1：这条消息是否@了该目标人
                if (isAtTargetPerson(content, person)) {
                    log("🔔 [@" + person + "检测] 在「" + getGroupName(chatUsername)
                            + "」检测到@" + person + "的消息: "
                            + (content.length() > 50 ? content.substring(0, 50) + "..." : content));

                    // 取消该规则下同一目标人的旧提醒
                    cancelPendingReminder(ruleIndex, person);

                    // 创建新的超时提醒
                    String key = ruleIndex + "|" + person;
                    final String atPerson = person;
                    ScheduledFuture<?> future = scheduler.schedule(() -> {
                        fireReminder(rule, atPerson, content, senderDisplay);
                        pendingReminders.remove(key);
                    }, rule.getTimeoutMinutes(), TimeUnit.MINUTES);
                    pendingReminders.put(key, future);
                }

                // 检查2：这条消息的发送者是否是该目标人（即目标人回复了）
                if (isTargetPersonSpeaking(senderUsername, senderDisplay, person)) {
                    lastSpeakTimes.computeIfAbsent(ruleIndex, k -> new ConcurrentHashMap<>())
                            .put(person, mm.getCreateTime());

                    // 如果目标人发言了，取消该目标人的待触发提醒
                    if (cancelPendingReminder(ruleIndex, person)) {
                        log("✅ [@" + person + "已回复] " + senderDisplay
                                + " 在「" + getGroupName(chatUsername) + "」发言，提醒已取消");
                    }
                }
            }
        }
    }

    /**
     * 拆分逗号分隔的目标人列表（支持中英文逗号）
     */
    private String[] splitPersons(String targetPerson) {
        if (targetPerson == null || targetPerson.isEmpty()) return new String[0];
        String[] parts = targetPerson.split("[,，]");
        List<String> result = new ArrayList<>();
        for (String p : parts) {
            String trimmed = p.trim();
            if (!trimmed.isEmpty()) result.add(trimmed);
        }
        return result.toArray(new String[0]);
    }

    /**
     * 检查消息内容是否@了目标人
     */
    private boolean isAtTargetPerson(String content, String targetPerson) {
        if (content == null || targetPerson == null || targetPerson.isEmpty()) return false;
        // 微信@格式：@昵称 （可能在消息任何位置）
        return content.contains("@" + targetPerson);
    }

    /**
     * 检查发言者是否是目标人（通过 username 或 display name 匹配）
     */
    private boolean isTargetPersonSpeaking(String senderUsername, String senderDisplay, String targetPerson) {
        if (targetPerson == null || targetPerson.isEmpty()) return false;
        // 优先匹配显示名称
        if (senderDisplay != null && senderDisplay.equals(targetPerson)) return true;
        // 也匹配 username
        if (senderUsername != null && senderUsername.equals(targetPerson)) return true;
        return false;
    }

    /**
     * 触发提醒：在目标群发送提醒消息
     * @param atPerson 被@的具体目标人（单个）
     */
    private void fireReminder(AtReminderRule rule, String atPerson, String originalMessage, String atSender) {
        if (messageSender == null) {
            log("❌ [@提醒] 消息发送器未配置，无法发送提醒");
            return;
        }

        String targetGroupName = getGroupName(rule.getTargetGroup());
        String sourceGroupName = getGroupName(rule.getSourceGroup());

        // 构建提醒消息
        String msg = rule.getReminderTemplate()
                .replace("{person}", atPerson)
                .replace("{sourceGroup}", sourceGroupName)
                .replace("{timeout}", String.valueOf(rule.getTimeoutMinutes()))
                .replace("{message}", originalMessage != null && originalMessage.length() > 100
                        ? originalMessage.substring(0, 100) + "..." : originalMessage)
                .replace("{sender}", atSender != null ? atSender : "未知");

        log("📤 [@提醒] 正在向「" + targetGroupName + "」发送提醒...");
        String result = messageSender.send(targetGroupName, msg);
        if ("ok".equals(result)) {
            log("✅ [@提醒] 提醒已发送到「" + targetGroupName + "」");
        } else {
            log("❌ [@提醒] 发送失败: " + result);
        }
    }

    /**
     * 取消指定规则+目标人的待触发提醒
     * @return true 如果确实取消了某个提醒
     */
    private boolean cancelPendingReminder(int ruleIndex, String targetPerson) {
        String key = ruleIndex + "|" + targetPerson;
        ScheduledFuture<?> future = pendingReminders.remove(key);
        if (future != null) {
            future.cancel(false);
            return true;
        }
        return false;
    }

    /**
     * 获取群显示名称（带缓存）
     */
    private String getGroupName(String username) {
        if (username == null) return "未知群";
        return groupNameCache.computeIfAbsent(username, u -> {
            try {
                Contact c = db.getContact(u);
                return c != null ? c.getDisplayName() : u;
            } catch (Exception e) {
                LOG.log(Level.FINE, "查询群名失败: " + u, e);
                return u;
            }
        });
    }

    private void log(String msg) {
        if (logCallback != null) {
            logCallback.accept(msg);
        }
        LOG.info(msg);
    }

    /**
     * 关闭追踪器
     */
    public void shutdown() {
        pendingReminders.values().forEach(f -> f.cancel(false));
        pendingReminders.clear();
        scheduler.shutdownNow();
    }

    /**
     * 消息发送函数接口
     */
    public interface MessageSender {
        /**
         * 发送消息
         * @param chatName 聊天对象显示名称
         * @param message 消息内容
         * @return 成功返回 "ok"，失败返回错误信息
         */
        String send(String chatName, String message);
    }
}
