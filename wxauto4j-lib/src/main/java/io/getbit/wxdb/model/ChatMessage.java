package io.getbit.wxdb.model;

/**
 * 微信聊天消息
 * <p>
 * 对应 Msg_&lt;md5(username)&gt; 表，每个聊天一个独立表。
 * 通过 realSenderId 关联 Name2Id.rowid 获取发送者 username。
 */
public class ChatMessage {
    // ===== 核心字段 =====
    private long localId;              // INTEGER PRIMARY KEY AUTOINCREMENT
    private long serverId;             // INTEGER - 服务端消息ID
    private int localType;             // INTEGER - 1=文本, 3=图片, 49=文件/链接等
    private long sortSeq;              // INTEGER - 排序序号
    private long realSenderId;         // INTEGER - 发送者在 Name2Id 表中的 rowid
    private String senderId;           // 解析后的发送者 username（由 realSenderId 关联查询得到）
    private long createTime;           // INTEGER - unix timestamp (seconds)
    private int status;                // INTEGER - 消息状态

    // ===== 扩展字段 =====
    private int uploadStatus;          // INTEGER - 上传状态
    private int downloadStatus;        // INTEGER - 下载状态
    private long serverSeq;            // INTEGER - 服务端序列号
    private int originSource;          // INTEGER - 消息来源
    private String source;             // TEXT - 消息来源信息
    private String messageContent;     // TEXT - 消息内容
    private String compressContent;    // TEXT - 压缩消息内容
    private byte[] packedInfoData;     // BLOB - 打包的消息附加信息

    // ===== WCDB 压缩标记 =====
    private Integer wcdbCtMessageContent; // INTEGER - message_content 压缩类型
    private Integer wcdbCtSource;         // INTEGER - source 压缩类型

    public ChatMessage() {}

    // ===== getter/setter =====

    public long getLocalId() { return localId; }
    public void setLocalId(long localId) { this.localId = localId; }

    public long getServerId() { return serverId; }
    public void setServerId(long serverId) { this.serverId = serverId; }

    public int getLocalType() { return localType; }
    public void setLocalType(int localType) { this.localType = localType; }

    public long getSortSeq() { return sortSeq; }
    public void setSortSeq(long sortSeq) { this.sortSeq = sortSeq; }

    public long getRealSenderId() { return realSenderId; }
    public void setRealSenderId(long realSenderId) { this.realSenderId = realSenderId; }

    public String getSenderId() { return senderId; }
    public void setSenderId(String senderId) { this.senderId = senderId; }

    public long getCreateTime() { return createTime; }
    public void setCreateTime(long createTime) { this.createTime = createTime; }

    public int getStatus() { return status; }
    public void setStatus(int status) { this.status = status; }

    public int getUploadStatus() { return uploadStatus; }
    public void setUploadStatus(int uploadStatus) { this.uploadStatus = uploadStatus; }

    public int getDownloadStatus() { return downloadStatus; }
    public void setDownloadStatus(int downloadStatus) { this.downloadStatus = downloadStatus; }

    public long getServerSeq() { return serverSeq; }
    public void setServerSeq(long serverSeq) { this.serverSeq = serverSeq; }

    public int getOriginSource() { return originSource; }
    public void setOriginSource(int originSource) { this.originSource = originSource; }

    public String getSource() { return source; }
    public void setSource(String source) { this.source = source; }

    public String getMessageContent() { return messageContent; }
    public void setMessageContent(String content) { this.messageContent = content; }

    public String getCompressContent() { return compressContent; }
    public void setCompressContent(String compressContent) { this.compressContent = compressContent; }

    public byte[] getPackedInfoData() { return packedInfoData; }
    public void setPackedInfoData(byte[] packedInfoData) { this.packedInfoData = packedInfoData; }

    public Integer getWcdbCtMessageContent() { return wcdbCtMessageContent; }
    public void setWcdbCtMessageContent(Integer wcdbCtMessageContent) { this.wcdbCtMessageContent = wcdbCtMessageContent; }

    public Integer getWcdbCtSource() { return wcdbCtSource; }
    public void setWcdbCtSource(Integer wcdbCtSource) { this.wcdbCtSource = wcdbCtSource; }

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
        return "ChatMessage{localId=" + localId + ", serverId=" + serverId +
                ", type=" + getTypeDescription() +
                ", realSenderId=" + realSenderId +
                ", sender='" + senderId + "'" +
                ", time=" + createTime +
                ", status=" + status +
                ", uploadStatus=" + uploadStatus +
                ", downloadStatus=" + downloadStatus +
                ", content='" + (messageContent != null && messageContent.length() > 50
                ? messageContent.substring(0, 50) + "..." : messageContent) + "'}";
    }
}
