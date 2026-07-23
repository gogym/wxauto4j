package io.getbit.wxdb;

import io.getbit.wxdb.model.ChatMessage;
import io.getbit.wxdb.model.Contact;
import io.getbit.wxdb.model.Session;
import io.getbit.wxdb.query.ContactQuery;
import io.getbit.wxdb.query.MessageQuery;
import io.getbit.wxdb.query.SessionQuery;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信数据库主入口（门面类）
 * <p>
 * 使用 sqlite-jdbc-crypt 驱动直接连接加密数据库，无需解密步骤。
 * <p>
 * 使用示例：
 * <pre>
 * WeChatDBConfig config = WeChatDBConfig.fromRawKey(rawKeyHex)
 *         .wechatDataDir(dataDir);
 * WeChatDB db = new WeChatDB(config);
 * db.init();
 *
 * // 查询会话
 * List&lt;Session&gt; sessions = db.getRecentSessions(20);
 *
 * // 查询联系人
 * List&lt;Contact&gt; contacts = db.searchContacts("张三");
 *
 * // 查询消息
 * List&lt;ChatMessage&gt; msgs = db.getRecentMessages("wxid_xxx", 50);
 * </pre>
 */
public class WeChatDB {

    private final WeChatDBConfig config;

    private SessionQuery sessionQuery;
    private ContactQuery contactQuery;
    private MessageQuery messageQuery;

    public WeChatDB(WeChatDBConfig config) {
        this.config = config;
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("sqlite-jdbc-crypt driver not found in classpath", e);
        }
    }

    /**
     * 初始化所有数据库查询（直接连接加密 DB）
     */
    public void init() {
        // Session
        String sessionUrl = config.getEncryptedDbJdbcUrl("session", "session.db");
        String sessionKey = config.getDerivedKey("session", "session.db");
        sessionQuery = new SessionQuery(sessionUrl, sessionKey);

        // Contact
        String contactUrl = config.getEncryptedDbJdbcUrl("contact", "contact.db");
        String contactKey = config.getDerivedKey("contact", "contact.db");
        contactQuery = new ContactQuery(contactUrl, contactKey);

        // Messages
        initMessageQuery();
    }

    // ========== 会话查询 ==========

    public List<Session> getSessions() {
        ensureSessionQuery();
        return sessionQuery.getAll();
    }

    public List<Session> getRecentSessions(int limit) {
        ensureSessionQuery();
        return sessionQuery.getRecent(limit);
    }

    public Session getSession(String username) {
        ensureSessionQuery();
        return sessionQuery.getByUsername(username);
    }

    public List<Session> getUnreadSessions() {
        ensureSessionQuery();
        return sessionQuery.getUnread();
    }

    public List<Session> searchSessions(String keyword) {
        ensureSessionQuery();
        return sessionQuery.search(keyword);
    }

    // ========== 联系人查询 ==========

    public List<Contact> getContacts() {
        ensureContactQuery();
        return contactQuery.getAll();
    }

    public List<Contact> getRecentContacts(int limit) {
        ensureContactQuery();
        return contactQuery.getRecent(limit);
    }

    public Contact getContact(String username) {
        ensureContactQuery();
        return contactQuery.getByUsername(username);
    }

    public List<Contact> searchContacts(String keyword) {
        ensureContactQuery();
        return contactQuery.search(keyword);
    }

    public List<Contact> getChatrooms() {
        ensureContactQuery();
        return contactQuery.getChatrooms();
    }

    public List<Contact> getOfficialAccounts() {
        ensureContactQuery();
        return contactQuery.getOfficialAccounts();
    }

    public int getContactCount() {
        ensureContactQuery();
        return contactQuery.count();
    }

    // ========== 消息查询 ==========

    public List<ChatMessage> getRecentMessages(String username, int limit) {
        ensureMessageQuery();
        return messageQuery.getRecentMessages(username, limit);
    }

    public List<ChatMessage> getMessages(String username, long startTime, long endTime) {
        ensureMessageQuery();
        return messageQuery.getMessages(username, startTime, endTime);
    }

    public List<ChatMessage> getAllMessages(String username) {
        ensureMessageQuery();
        return messageQuery.getAllMessages(username);
    }

    public List<ChatMessage> searchMessages(String username, String keyword) {
        ensureMessageQuery();
        return messageQuery.searchMessages(username, keyword);
    }

    public List<ChatMessage> searchAllMessages(String keyword) {
        ensureMessageQuery();
        return messageQuery.searchAllMessages(keyword);
    }

    public int getMessageCount(String username) {
        ensureMessageQuery();
        return messageQuery.countMessages(username);
    }

    public List<ChatMessage> getMessagesNewerThan(String username, long afterLocalId) {
        ensureMessageQuery();
        return messageQuery.getMessagesNewerThan(username, afterLocalId);
    }

    public List<ChatMessage> getMessagesSince(String username, long sinceTime, long afterLocalId) {
        ensureMessageQuery();
        return messageQuery.getMessagesSince(username, sinceTime, afterLocalId);
    }

    public long getMaxLocalId(String username) {
        ensureMessageQuery();
        return messageQuery.getMaxLocalId(username);
    }

    public String resolveSenderUsername(int senderRowId) {
        ensureMessageQuery();
        return messageQuery.resolveSenderUsername(senderRowId);
    }

    // ========== 内部方法 ==========

    private void initMessageQuery() {
        List<String> urls = new ArrayList<>();
        List<String> keys = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            String dbName = "message_" + i + ".db";
            String encPath = config.getEncryptedDbPath("message", dbName);
            if (new File(encPath).exists()) {
                urls.add(config.getEncryptedDbJdbcUrl("message", dbName));
                keys.add(config.getDerivedKey("message", dbName));
            }
        }
        if (!urls.isEmpty()) {
            messageQuery = new MessageQuery(urls, keys);
        }
    }

    private void ensureSessionQuery() {
        if (sessionQuery == null) {
            String sessionUrl = config.getEncryptedDbJdbcUrl("session", "session.db");
            String sessionKey = config.getDerivedKey("session", "session.db");
            sessionQuery = new SessionQuery(sessionUrl, sessionKey);
        }
    }

    private void ensureContactQuery() {
        if (contactQuery == null) {
            String contactUrl = config.getEncryptedDbJdbcUrl("contact", "contact.db");
            String contactKey = config.getDerivedKey("contact", "contact.db");
            contactQuery = new ContactQuery(contactUrl, contactKey);
        }
    }

    private void ensureMessageQuery() {
        if (messageQuery == null) {
            initMessageQuery();
        }
        if (messageQuery == null) {
            throw new RuntimeException("No message databases found.");
        }
    }

    public WeChatDBConfig getConfig() {
        return config;
    }

    /**
     * 关闭所有数据库连接
     */
    public void close() {
        if (sessionQuery != null) sessionQuery.close();
        if (contactQuery != null) contactQuery.close();
        if (messageQuery != null) messageQuery.close();
    }
}
