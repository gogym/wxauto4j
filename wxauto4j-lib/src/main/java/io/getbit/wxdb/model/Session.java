package io.getbit.wxdb.model;

/**
 * 微信会话
 */
public class Session {
    private String username;
    private int type;
    private int unreadCount;
    private String summary;
    private String draft;
    private long lastTimestamp;
    private long sortTimestamp;

    public Session() {}

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public int getType() { return type; }
    public void setType(int type) { this.type = type; }

    public int getUnreadCount() { return unreadCount; }
    public void setUnreadCount(int unreadCount) { this.unreadCount = unreadCount; }

    public String getSummary() { return summary; }
    public void setSummary(String summary) { this.summary = summary; }

    public String getDraft() { return draft; }
    public void setDraft(String draft) { this.draft = draft; }

    public long getLastTimestamp() { return lastTimestamp; }
    public void setLastTimestamp(long lastTimestamp) { this.lastTimestamp = lastTimestamp; }

    public long getSortTimestamp() { return sortTimestamp; }
    public void setSortTimestamp(long sortTimestamp) { this.sortTimestamp = sortTimestamp; }

    /**
     * 是否为群聊
     */
    public boolean isChatroom() {
        return username != null && username.endsWith("@chatroom");
    }

    @Override
    public String toString() {
        return "Session{username='" + username + "', summary='" + summary +
                "', unread=" + unreadCount + ", ts=" + lastTimestamp + "}";
    }
}
