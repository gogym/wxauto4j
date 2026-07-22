package io.getbit.app.bot;

import io.getbit.WeChat;
import io.getbit.app.config.AppConfig;
import io.getbit.app.config.ForwardRule;
import io.getbit.elements.Message;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 自定义规则转发引擎
 *
 * <p>支持三种触发类型：关键词转发、固定发送人转发、无差别转发。
 * 支持全部来源模式和多目标逐一转发。</p>
 */
public class ForwardEngine {

    private static final Logger LOG = Logger.getLogger(ForwardEngine.class.getName());

    private final AppConfig config;
    private final WeChat weChat;

    public ForwardEngine(AppConfig config, WeChat weChat) {
        this.config = config;
        this.weChat = weChat;
    }

    /**
     * 处理一条消息，检查是否匹配转发规则
     *
     * @param msg      消息对象
     * @param chatName 来源聊天窗口名称
     * @param isGroup  是否为群聊
     */
    public void process(Message msg, String chatName, boolean isGroup) {
        if (!config.isCustomForwardSwitch()) return;
        if (msg == null || !msg.isFriend()) return;

        String content = msg.getContent();
        String sender = msg.getSender();

        for (ForwardRule rule : config.getCustomForwardList()) {
            try {
                if (matchesRule(rule, chatName, content, sender)) {
                    executeForward(rule, chatName, sender, content);
                }
            } catch (Exception e) {
                LOG.log(Level.WARNING, "转发规则执行失败: " + rule.getId(), e);
            }
        }
    }

    /**
     * 检查消息是否匹配转发规则
     */
    private boolean matchesRule(ForwardRule rule, String chatName, String content, String sender) {
        // 检查来源
        if (!rule.isAllSources()) {
            if (!rule.getSources().contains(chatName)) {
                return false;
            }
        }

        // 检查触发类型
        switch (rule.getType()) {
            case "keyword":
                if (content == null) return false;
                for (String keyword : rule.getKeywords()) {
                    if (keyword != null && !keyword.isEmpty() && content.contains(keyword)) {
                        return true;
                    }
                }
                return false;

            case "sender":
                return sender != null && rule.getSenders().contains(sender);

            case "all":
                return true;

            default:
                return false;
        }
    }

    /**
     * 执行转发
     */
    private void executeForward(ForwardRule rule, String sourceName, String sender, String content) {
        List<String> targets = rule.getTargets();
        if (targets == null || targets.isEmpty()) {
            LOG.warning("转发规则无目标: " + rule.getId());
            return;
        }

        // 构建转发内容
        StringBuilder forwardContent = new StringBuilder();
        if (rule.isForwardWithSource()) {
            forwardContent.append("来源窗口：").append(sourceName);
            if (sender != null && !sender.isEmpty()) {
                forwardContent.append("，发送人：").append(sender);
            }
            forwardContent.append("\n---\n");
        }
        forwardContent.append(content);

        // 逐一转发，每次间隔 1 秒
        for (int i = 0; i < targets.size(); i++) {
            String target = targets.get(i);
            try {
                weChat.ChatWith(target, false);
                Thread.sleep(300);
                weChat.SendMsg(forwardContent.toString());
                LOG.info("转发成功: " + sourceName + " → " + target);
            } catch (Exception e) {
                LOG.log(Level.WARNING, "转发到 " + target + " 失败", e);
            }

            // 多目标间隔 1 秒
            if (i < targets.size() - 1) {
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        }
    }
}
