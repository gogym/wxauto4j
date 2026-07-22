package io.getbit.app.bot;

import io.getbit.Chat;
import io.getbit.WeChat;
import io.getbit.app.ai.AiClient;
import io.getbit.app.ai.AiResponse;
import io.getbit.app.ai.MemoryMessage;
import io.getbit.app.config.AppConfig;
import io.getbit.app.prompt.PromptManager;
import io.getbit.elements.Message;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * 消息处理链
 *
 * <p>处理流程：</p>
 * <ol>
 *   <li>消息去重（通过 runtimeId）</li>
 *   <li>写入对话记忆</li>
 *   <li>关键词匹配 → 命中则直接回复</li>
 *   <li>AI 智能回复（携带 Prompt + 记忆）</li>
 *   <li>随机延迟（模拟人工）</li>
 *   <li>超长文本分段发送</li>
 *   <li>接口错误兜底回复</li>
 * </ol>
 */
public class MessageHandler {

    private static final Logger LOG = Logger.getLogger(MessageHandler.class.getName());
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss");
    private static final int MAX_MSG_LENGTH = 2000;

    private final AppConfig config;
    private final WeChat weChat;
    private final MemoryManager memoryManager;
    private final PromptManager promptManager;
    private final KeywordMatcher keywordMatcher;
    private final Random random = new Random();

    /** 已处理的消息 runtimeId 集合（去重用） */
    private final Set<String> processedMsgIds = ConcurrentHashMap.newKeySet();

    /** 接口错误已回复过的用户集合（apiErrorReplyOnce 用） */
    private final Set<String> errorRepliedUsers = ConcurrentHashMap.newKeySet();

    /** AI 客户端提供者（支持按聊天窗口选择不同接口） */
    private AiClientProvider aiClientProvider;

    /**
     * AI 客户端提供者接口
     */
    public interface AiClientProvider {
        /**
         * 根据聊天窗口获取 AI 客户端
         *
         * @param chatName 聊天窗口名称
         * @param isGroup  是否为群聊
         * @return AI 客户端实例
         */
        AiClient getClient(String chatName, boolean isGroup);
    }

    public MessageHandler(AppConfig config, WeChat weChat, MemoryManager memoryManager,
                          PromptManager promptManager) {
        this.config = config;
        this.weChat = weChat;
        this.memoryManager = memoryManager;
        this.promptManager = promptManager;
        this.keywordMatcher = new KeywordMatcher();
    }

    public void setAiClientProvider(AiClientProvider provider) {
        this.aiClientProvider = provider;
    }

    /**
     * 处理一条新消息
     *
     * @param msg      消息对象
     * @param chatName 聊天窗口名称
     * @param chat     聊天窗口实例（可为 null）
     * @param isGroup  是否为群聊
     */
    public void handle(Message msg, String chatName, Chat chat, boolean isGroup) {
        // 1. 消息去重
        String rid = msg.getRuntimeId();
        if (rid != null && !processedMsgIds.add(rid)) {
            return; // 已处理过
        }

        // 只处理对方消息
        if (!msg.isFriend()) {
            return;
        }

        String content = msg.getContent();
        if (content == null || content.isEmpty()) {
            return;
        }

        String sender = msg.getSender();
        String now = LocalDateTime.now().format(TIME_FMT);

        // 2. 写入对话记忆
        if (isGroup && sender != null) {
            memoryManager.addMessage(chatName, MemoryMessage.friend(now, sender, content));
        } else {
            memoryManager.addMessage(chatName, MemoryMessage.friend(now, chatName, content));
        }

        // 3. 检查是否只监听不回复
        if (isGroup && config.isGroupListenOnly()) {
            return;
        }
        if (!isGroup && config.isChatListenOnly()) {
            return;
        }

        // 4. 关键词匹配
        boolean keywordSwitch = isGroup ? config.isGroupKeywordSwitch() : config.isChatKeywordSwitch();
        if (keywordSwitch) {
            // 群聊关键词 @ only 检查
            if (isGroup && config.isGroupKeywordAtOnly()) {
                // TODO: 需要检查消息是否 @了机器人，当前简化处理
            }
            String keywordReply = keywordMatcher.match(content, config.getKeywordDict());
            if (keywordReply != null) {
                applyDelay();
                sendReply(chat, chatName, keywordReply, isGroup, sender);
                memoryManager.addMessage(chatName, MemoryMessage.self(now, keywordReply));
                return;
            }
        }

        // 5. AI 智能回复
        if (aiClientProvider == null) {
            LOG.warning("AI 客户端未配置，跳过 AI 回复");
            return;
        }

        AiClient aiClient = aiClientProvider.getClient(chatName, isGroup);
        if (aiClient == null) {
            LOG.warning("未找到可用的 AI 客户端: " + chatName);
            return;
        }

        // 获取 Prompt
        Map<String, String> promptMap = isGroup ? config.getGroupPromptMap() : config.getChatPromptMap();
        String prompt = promptManager.resolvePrompt(chatName, promptMap, config.getDefaultPrompt());

        // 获取历史记忆
        List<MemoryMessage> history = memoryManager.getContextHistory(chatName);

        // 调用 AI
        AiResponse aiResponse = aiClient.chat(prompt, history, content);

        if (aiResponse.isSuccess()) {
            String reply = aiResponse.getContent();
            if (reply != null && !reply.isEmpty()) {
                // 清理  思考过程
                if (config.isCleanAiReplySwitch()) {
                    reply = cleanThinkingContent(reply);
                }

                applyDelay();
                sendReply(chat, chatName, reply, isGroup, sender);
                memoryManager.addMessage(chatName, MemoryMessage.self(now, reply));
            }
        } else {
            // 接口错误兜底回复
            handleApiError(chat, chatName, isGroup);
        }
    }

    /**
     * 发送回复（处理超长分段和拆分）
     */
    private void sendReply(Chat chat, String chatName, String reply, boolean isGroup, String sender) {
        if (chat == null) {
            LOG.warning("聊天窗口实例为 null，无法发送回复: " + chatName);
            return;
        }

        // 超长文本分段（超 2000 字）
        if (reply.length() > MAX_MSG_LENGTH) {
            sendInSegments(chat, reply, MAX_MSG_LENGTH);
            return;
        }

        // 拆分多条回复
        boolean splitSwitch = isGroup ? config.isGroupSplitReplySwitch() : config.isChatSplitReplySwitch();
        if (splitSwitch) {
            int maxChars = isGroup ? config.getGroupSplitMaxChars() : config.getChatSplitMaxChars();
            int maxCount = isGroup ? config.getGroupSplitMaxCount() : config.getChatSplitMaxCount();
            sendSplitReply(chat, reply, maxChars, maxCount, isGroup, sender);
            return;
        }

        // 普通发送
        chat.SendMsg(reply);
    }

    /**
     * 超长文本按固定长度分段发送
     */
    private void sendInSegments(Chat chat, String text, int segmentLength) {
        int offset = 0;
        while (offset < text.length()) {
            int end = Math.min(offset + segmentLength, text.length());
            String segment = text.substring(offset, end);
            chat.SendMsg(segment);
            applyDelay();
            offset = end;
        }
    }

    /**
     * 拆分多条回复（按换行符拆分）
     */
    private void sendSplitReply(Chat chat, String reply, int maxChars, int maxCount,
                                 boolean isGroup, String sender) {
        String[] lines = reply.split("\n");
        int count = 0;
        StringBuilder current = new StringBuilder();

        for (String line : lines) {
            if (count >= maxCount) {
                // 超出最大条数，剩余内容合并到最后一条
                if (current.length() > 0) current.append("\n");
                current.append(line);
                continue;
            }

            if (current.length() + line.length() + 1 > maxChars && current.length() > 0) {
                chat.SendMsg(current.toString());
                applyDelay();
                current = new StringBuilder(line);
                count++;
            } else {
                if (current.length() > 0) current.append("\n");
                current.append(line);
            }
        }

        if (current.length() > 0) {
            chat.SendMsg(current.toString());
        }
    }

    /**
     * 处理 API 错误兜底回复
     */
    private void handleApiError(Chat chat, String chatName, boolean isGroup) {
        String errorReply = config.getApiErrorReply();
        if (errorReply == null || errorReply.isEmpty()) {
            return;
        }

        if (config.isApiErrorReplyOnce()) {
            if (!errorRepliedUsers.add(chatName)) {
                return; // 已经回复过
            }
        }

        applyDelay();
        if (chat != null) {
            chat.SendMsg(errorReply);
        }
        LOG.warning("AI 接口调用失败，已发送兜底回复: " + chatName);
    }

    /**
     * 应用随机延迟
     */
    private void applyDelay() {
        if (!config.isReplyDelaySwitch()) return;

        int min = config.getReplyDelayMin();
        int max = config.getReplyDelayMax();
        if (max <= min) {
            max = min + 1;
        }
        int delay = min + random.nextInt(max - min);
        try {
            Thread.sleep(delay * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 清理 AI 回复中的  思考过程
     */
    private String cleanThinkingContent(String text) {
        if (text == null) return null;
        // 移除  ...  包裹的内容
        return text.replaceAll("(?s).*?", "").trim();
    }

    /**
     * 清除已处理消息 ID 缓存（防止内存泄漏）
     */
    public void clearProcessedCache() {
        processedMsgIds.clear();
    }
}
