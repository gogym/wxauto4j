package io.getbit.wxdb.model;

/**
 * 微信聊天消息
 */
public class ChatMessage {
    private long localId;
    private long serverId;
    private int localType;        // 1=文本, 3=图片, 49=文件/链接等
    private long sortSeq;
    private String senderId;
    private long createTime;      // unix timestamp (seconds)
    private int status;
    private String messageContent;
    private String source;

    public ChatMessage() {}

    public long getLocalId() { return localId; }
    public void setLocalId(long localId) { this.localId = localId; }

    public long getServerId() { return serverId; }
    public void setServerId(long serverId) { this.serverId = serverId; }

    public int getLocalType() { return localType; }
    public void setLocalType(int localType) { this.localType = localType; }

    public long getSortSeq() { return sortSeq; }
    public void setSortSeq(long sortSeq) { this.sortSeq = sortSeq; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public String getMessageContent() { return messageContent; }
    public void setMessageContent(String content) { this.messageContent = content; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    /**
     * 消息类型描述
     */
    public String getTypeDescription() {
        return switch (localType) {
            case 1 -> "文本";
            case 3 -> "图片";
            case 34 -> "语音";
            case 43 -> "视频";
            case 47 -> "表情";
            case 48 -> "位置";
            case 49 -> "链接/文件";
            case 50 -> "语音/视频通话";
            case 10000 -> "系统消息";
            case 10002 -> "撤回消息";
            default -> "类型" + localType;
        };
    }

    @Override
    public String toString() {
        return "ChatMessage{id=" + localId + ", type=" + getTypeDescription() +
                ", sender='" + senderId + "', time=" + createTime +
                ", content='" + (messageContent != null && messageContent.length() > 50
                ? messageContent.substring(0, 50) + "..." : messageContent) + "'}";
    }
}
