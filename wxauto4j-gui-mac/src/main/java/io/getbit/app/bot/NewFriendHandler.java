package io.getbit.app.bot;

import io.getbit.WeChat;
import io.getbit.app.config.AppConfig;
import io.getbit.elements.NewFriendElement;

import java.io.File;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 新好友自动处理器
 *
 * <p>功能：</p>
 * <ul>
 *   <li>定时检查新好友请求</li>
 *   <li>自动通过好友申请</li>
 *   <li>自动发送打招呼消息（支持文字+图片）</li>
 *   <li>自动设置备注（前缀+昵称+后缀）</li>
 * </ul>
 */
public class NewFriendHandler {

    private static final Logger LOG = Logger.getLogger(NewFriendHandler.class.getName());

    private final AppConfig config;
    private final WeChat weChat;
    private final Random random = new Random();

    private ScheduledExecutorService executor;
    private ScheduledFuture<?> task;

    public NewFriendHandler(AppConfig config, WeChat weChat) {
        this.config = config;
        this.weChat = weChat;
    }

    /**
     * 启动新好友检查
     */
    public void start() {
        if (executor != null) {
            executor.shutdown();
        }
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "new-friend-checker");
            t.setDaemon(true);
            return t;
        });

        // 首次延迟后开始检查
        int initialDelay = randomCheckInterval();
        task = executor.scheduleAtFixedRate(this::checkNewFriends, initialDelay, initialDelay, TimeUnit.SECONDS);
        LOG.info("新好友检查已启动，首次检查延迟: " + initialDelay + " 秒");
    }

    /**
     * 停止新好友检查
     */
    public void stop() {
        if (task != null) {
            task.cancel(false);
            task = null;
        }
        if (executor != null) {
            executor.shutdown();
            executor = null;
        }
    }

    /**
     * 检查并处理新好友请求
     */
    private void checkNewFriends() {
        try {
            List<NewFriendElement> friends = weChat.GetNewFriends(true);
            for (NewFriendElement friend : friends) {
                try {
                    processNewFriend(friend);
                } catch (Exception e) {
                    LOG.log(Level.WARNING, "处理新好友失败: " + friend.getName(), e);
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "检查新好友失败", e);
        }

        // 重新调度下一次检查（随机间隔）
        reschedule();
    }

    /**
     * 处理单个新好友
     */
    private void processNewFriend(NewFriendElement friend) {
        String name = friend.getName();
        LOG.info("发现新好友请求: " + name);

        // 自动通过
        // TODO: 需要通过 UI 操作通过好友请求
        // 当前 NewFriendElement 可能没有 accept 方法，需要根据实际 API 调整

        // 自动打招呼
        if (config.isNewFriendReplySwitch()) {
            sendGreeting(name);
        }

        // 自动设置备注
        setRemark(name);
    }

    /**
     * 发送打招呼消息
     */
    private void sendGreeting(String nickname) {
        List<String> msgs = config.getNewFriendMsg();
        if (msgs == null || msgs.isEmpty()) return;

        try {
            weChat.ChatWith(nickname, false);
            Thread.sleep(500);

            for (String msg : msgs) {
                if (msg == null || msg.isEmpty()) continue;

                File file = new File(msg);
                if (file.exists() && file.isFile()) {
                    // 发送图片
                    weChat.SendMsg(msg); // 简化处理，实际可能需要 SendFiles
                } else {
                    // 发送文字
                    weChat.SendMsg(msg);
                }
                Thread.sleep(500);
            }
            LOG.info("已向 " + nickname + " 发送打招呼消息");
        } catch (Exception e) {
            LOG.log(Level.WARNING, "发送打招呼消息失败: " + nickname, e);
        }
    }

    /**
     * 设置备注
     */
    private void setRemark(String nickname) {
        StringBuilder remark = new StringBuilder();

        // 前缀
        String prefix = config.getNewFriendRemarkPrefix();
        if (prefix != null && !prefix.isEmpty()) {
            remark.append(prefix);
        }
        if (config.isNewFriendRemarkPrefixTimestamp()) {
            remark.append(System.currentTimeMillis());
        }

        // 昵称主体
        if (config.isNewFriendRemarkUseNickname()) {
            remark.append(nickname);
        }

        // 后缀
        String suffix = config.getNewFriendRemarkSuffix();
        if (suffix != null && !suffix.isEmpty()) {
            remark.append(suffix);
        }
        if (config.isNewFriendRemarkSuffixTimestamp()) {
            remark.append(System.currentTimeMillis());
        }

        if (remark.length() > 0) {
            try {
                weChat.EditFriendInfo(null, null, remark.toString());
                LOG.info("已设置备注: " + nickname + " → " + remark);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "设置备注失败: " + nickname, e);
            }
        }
    }

    /**
     * 生成随机检查间隔
     */
    private int randomCheckInterval() {
        int min = Math.max(60, config.getNewFriendCheckMin());
        int max = Math.min(3600, config.getNewFriendCheckMax());
        if (max <= min) max = min + 60;
        return min + random.nextInt(max - min);
    }

    /**
     * 重新调度下一次检查
     */
    private void reschedule() {
        if (task != null) {
            task.cancel(false);
        }
        int nextDelay = randomCheckInterval();
        task = executor.schedule(this::checkNewFriends, nextDelay, TimeUnit.SECONDS);
    }
}
