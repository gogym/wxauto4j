package io.getbit.elements;

/**
 * 微信消息对象
 *
 * <p>封装从微信聊天窗口中获取的一条消息，包含消息类型、发送者、内容和唯一标识。</p>
 *
 * <h3>消息类型说明：</h3>
 * <ul>
 *   <li>{@code friend} - 对方发来的消息</li>
 *   <li>{@code self} - 自己发出的消息</li>
 *   <li>{@code sys} - 系统消息（如"你已添加了xxx为好友"）</li>
 *   <li>{@code time} - 时间分隔（如"昨天 12:30"）</li>
 *   <li>{@code recall} - 撤回消息提示（如"xxx撤回了一条消息"）</li>
 * </ul>
 */
public class Message {

    /** 消息类型：对方消息 */
    public static final String TYPE_FRIEND = "friend";
    /** 消息类型：自己消息 */
    public static final String TYPE_SELF = "self";
    /** 消息类型：系统消息 */
    public static final String TYPE_SYS = "sys";
    /** 消息类型：时间分隔 */
    public static final String TYPE_TIME = "time";
    /** 消息类型：撤回提示 */
    public static final String TYPE_RECALL = "recall";

    /** 消息类型（friend/self/sys/time/recall） */
    private String type;

    /** 发送者昵称（sys/time/recall 类型时为 null） */
    private String sender;

    /** 消息内容文本 */
    private String content;

    /** 控件 RuntimeId（用于消息去重） */
    private String runtimeId;

    public Message() {
    }

    public Message(String type, String sender, String content, String runtimeId) {
        this.type = type;
        this.sender = sender;
        this.content = content;
        this.runtimeId = runtimeId;
    }

    /**
     * 获取消息类型
     *
     * @return 消息类型字符串（friend/self/sys/time/recall）
     */
    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    /**
     * 获取发送者昵称
     *
     * @return 发送者昵称，sys/time/recall 类型返回 null
     */
    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    /**
     * 获取消息内容
     *
     * @return 消息内容文本
     */
    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    /**
     * 获取控件 RuntimeId
     *
     * @return RuntimeId 字符串，用于消息去重判断
     */
    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    /**
     * 判断是否为对方发来的消息
     */
    public boolean isFriend() {
        return TYPE_FRIEND.equals(type);
    }

    /**
     * 判断是否为自己发出的消息
     */
    public boolean isSelf() {
        return TYPE_SELF.equals(type);
    }

    /**
     * 判断是否为系统消息
     */
    public boolean isSys() {
        return TYPE_SYS.equals(type);
    }

    /**
     * 判断是否为时间分隔
     */
    public boolean isTime() {
        return TYPE_TIME.equals(type);
    }

    /**
     * 判断是否为撤回提示
     */
    public boolean isRecall() {
        return TYPE_RECALL.equals(type);
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Message{");
        sb.append("type='").append(type).append('\'');
        if (sender != null) {
            sb.append(", sender='").append(sender).append('\'');
        }
        sb.append(", content='").append(content).append('\'');
        sb.append('}');
        return sb.toString();
    }
}
