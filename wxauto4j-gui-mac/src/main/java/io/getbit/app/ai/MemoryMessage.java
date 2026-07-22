package io.getbit.app.ai;

/**
 * 对话记忆消息
 *
 * <p>用于存储和传递给 AI 的历史消息。</p>
 */
public class MemoryMessage {

    /** 消息时间戳 */
    private String time;

    /** 消息类型：text / image / voice */
    private String type;

    /** 消息来源：friend / self / system */
    private String attr;

    /** 发送者昵称 */
    private String sender;

    /** 消息内容 */
    private String content;

    public MemoryMessage() {}

    public MemoryMessage(String time, String type, String attr, String sender, String content) {
        this.time = time;
        this.type = type;
        this.attr = attr;
        this.sender = sender;
        this.content = content;
    }

    /**
     * 快速创建对方消息
     */
    public static MemoryMessage friend(String time, String sender, String content) {
        return new MemoryMessage(time, "text", "friend", sender, content);
    }

    /**
     * 快速创建自己消息
     */
    public static MemoryMessage self(String time, String content) {
        return new MemoryMessage(time, "text", "self", "我", content);
    }

    /**
     * 格式化为 AI 可读的文本
     */
    public String toAiFormat() {
        if ("self".equals(attr)) {
            return "[" + time + "] 我: " + content;
        } else if ("friend".equals(attr)) {
            return "[" + time + "] " + (sender != null ? sender : "对方") + ": " + content;
        } else {
            return "[" + time + "] [系统] " + content;
        }
    }

    public String getTime() { return time; }
    public void setTime(String time) { this.time = time; }

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getAttr() { return attr; }
    public void setAttr(String attr) { this.attr = attr; }

    public String getSender() { return sender; }
    public void setSender(String sender) { this.sender = sender; }

    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
}
