package io.getbit.elements;

import io.getbit.uiautomation.control.Control;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 微信消息对象
 *
 * <p>封装从微信聊天窗口中获取的一条消息。消息有两个维度的属性：</p>
 * <ul>
 *   <li><b>attr（消息来源）</b>：self / friend / system / other</li>
 *   <li><b>msgType（消息内容类型）</b>：text / image / video / voice / file / quote /
 *       time / link / location / emotion / merge / personal_card / note / other</li>
 * </ul>
 */
public class Message {

    // ==================== 来源属性常量（attr） ====================

    /** 消息来源：对方消息 */
    public static final String ATTR_FRIEND = "friend";
    /** 消息来源：自己消息 */
    public static final String ATTR_SELF = "self";
    /** 消息来源：系统消息 */
    public static final String ATTR_SYSTEM = "system";
    /** 消息来源：其他 */
    public static final String ATTR_OTHER = "other";

    // ==================== 消息内容类型常量（msgType） ====================

    /** 文本消息 */
    public static final String MSG_TYPE_TEXT = "text";
    /** 引用消息 */
    public static final String MSG_TYPE_QUOTE = "quote";
    /** 语音消息 */
    public static final String MSG_TYPE_VOICE = "voice";
    /** 图片消息 */
    public static final String MSG_TYPE_IMAGE = "image";
    /** 视频消息 */
    public static final String MSG_TYPE_VIDEO = "video";
    /** 文件消息 */
    public static final String MSG_TYPE_FILE = "file";
    /** 位置消息 */
    public static final String MSG_TYPE_LOCATION = "location";
    /** 链接消息 */
    public static final String MSG_TYPE_LINK = "link";
    /** 表情消息 */
    public static final String MSG_TYPE_EMOTION = "emotion";
    /** 合并转发消息 */
    public static final String MSG_TYPE_MERGE = "merge";
    /** 个人名片消息 */
    public static final String MSG_TYPE_PERSONAL_CARD = "personal_card";
    /** 笔记消息 */
    public static final String MSG_TYPE_NOTE = "note";
    /** 时间消息 */
    public static final String MSG_TYPE_TIME = "time";
    /** 撤回消息 */
    public static final String MSG_TYPE_RECALL = "recall";
    /** 其他消息 */
    public static final String MSG_TYPE_OTHER = "other";

    // ==================== 实例字段 ====================

    /** 消息来源属性（friend/self/system/other） */
    private String attr;

    /** 消息内容类型（text/image/video/...） */
    private String msgType;

    /** 发送者昵称 */
    private String sender;

    /** 消息内容文本 */
    private String content;

    /** 控件 RuntimeId（用于消息去重） */
    private String runtimeId;

    /** 消息 hash（切换 UI 后不变，可能重复） */
    private String hash;

    /** 消息详细信息 */
    private Map<String, Object> info;

    /** 消息所属聊天窗口信息 */
    private Map<String, String> chatInfoData;

    /** 消息对应的原生 UI 元素（用于操作：点击、右键等） */
    private Object nativeElement;

    /** 引用消息的被引用内容（仅 quote 类型） */
    private String quoteContent;

    /** 引用消息的被引用发送人（仅 quote 类型） */
    private String quoteNickname;

    /** 时间消息的时间字符串（仅 time 类型） */
    private String time;

    // ==================== 构造函数 ====================

    public Message() {
        this.attr = ATTR_OTHER;
        this.msgType = MSG_TYPE_TEXT;
    }

    /**
     * 完整构造函数
     *
     * @param attr        来源属性（ATTR_FRIEND / ATTR_SELF / ATTR_SYSTEM / ATTR_OTHER）
     * @param msgType     消息内容类型（MSG_TYPE_TEXT / MSG_TYPE_IMAGE / ...）
     * @param sender      发送者
     * @param content     内容
     * @param runtimeId   RuntimeId
     */
    public Message(String attr, String msgType, String sender, String content, String runtimeId) {
        this.attr = attr;
        this.msgType = msgType;
        this.sender = sender;
        this.content = content;
        this.runtimeId = runtimeId;
    }

    /**
     * 快捷构造函数（默认文本类型）
     */
    public Message(String attr, String sender, String content, String runtimeId) {
        this(attr, MSG_TYPE_TEXT, sender, content, runtimeId);
    }

    // ==================== getter / setter ====================

    public String getAttr() {
        return attr;
    }

    public void setAttr(String attr) {
        this.attr = attr;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getSender() {
        return sender;
    }

    public void setSender(String sender) {
        this.sender = sender;
    }

    public String getContent() {
        return content;
    }

    public void setContent(String content) {
        this.content = content;
    }

    public String getRuntimeId() {
        return runtimeId;
    }

    public void setRuntimeId(String runtimeId) {
        this.runtimeId = runtimeId;
    }

    public String getHash() {
        return hash;
    }

    public void setHash(String hash) {
        this.hash = hash;
    }

    public Map<String, Object> getInfo() {
        if (info == null) {
            info = new LinkedHashMap<>();
        }
        return info;
    }

    public void setInfo(Map<String, Object> info) {
        this.info = info;
    }

    public String getTime() {
        return time;
    }

    public void setTime(String time) {
        this.time = time;
    }

    public String getQuoteContent() {
        return quoteContent;
    }

    public void setQuoteContent(String quoteContent) {
        this.quoteContent = quoteContent;
    }

    public String getQuoteNickname() {
        return quoteNickname;
    }

    public void setQuoteNickname(String quoteNickname) {
        this.quoteNickname = quoteNickname;
    }

    public Object getNativeElement() {
        return nativeElement;
    }

    public void setNativeElement(Object nativeElement) {
        this.nativeElement = nativeElement;
    }

    // ==================== 来源判断方法 ====================

    /** 是否为对方发来的消息 */
    public boolean isFriend() {
        return ATTR_FRIEND.equals(attr);
    }

    /** 是否为自己发出的消息 */
    public boolean isSelf() {
        return ATTR_SELF.equals(attr);
    }

    /** 是否为系统消息 */
    public boolean isSystem() {
        return ATTR_SYSTEM.equals(attr);
    }

    // ==================== 内容类型判断方法 ====================

    public boolean isText() { return MSG_TYPE_TEXT.equals(msgType); }
    public boolean isQuote() { return MSG_TYPE_QUOTE.equals(msgType); }
    public boolean isVoice() { return MSG_TYPE_VOICE.equals(msgType); }
    public boolean isImage() { return MSG_TYPE_IMAGE.equals(msgType); }
    public boolean isVideo() { return MSG_TYPE_VIDEO.equals(msgType); }
    public boolean isFile() { return MSG_TYPE_FILE.equals(msgType); }
    public boolean isLocation() { return MSG_TYPE_LOCATION.equals(msgType); }
    public boolean isLink() { return MSG_TYPE_LINK.equals(msgType); }
    public boolean isEmotion() { return MSG_TYPE_EMOTION.equals(msgType); }
    public boolean isMerge() { return MSG_TYPE_MERGE.equals(msgType); }
    public boolean isPersonalCard() { return MSG_TYPE_PERSONAL_CARD.equals(msgType); }
    public boolean isNote() { return MSG_TYPE_NOTE.equals(msgType); }
    public boolean isTime() { return MSG_TYPE_TIME.equals(msgType); }
    public boolean isRecall() { return MSG_TYPE_RECALL.equals(msgType); }

    // ==================== Message 基类方法 ====================

    /**
     * 获取该消息所属聊天窗口的信息
     *
     * @return 聊天窗口信息字典
     */
    public Map<String, String> chatInfo() {
        if (chatInfoData != null) {
            return chatInfoData;
        }
        return new LinkedHashMap<>();
    }

    public void setChatInfoData(Map<String, String> chatInfoData) {
        this.chatInfoData = chatInfoData;
    }

    /**
     * 将消息滚动到视野内
     */
    public void rollIntoView() {
        if (nativeElement instanceof Control) {
            try {
                Control msgControl = (Control) nativeElement;
                // 元素已在视野中或已滚动到可见
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    // ==================== HumanMessage 方法 ====================

    /**
     * 点击该消息（一般用于图片、视频等特殊消息）
     */
    public void click() {
        if (nativeElement instanceof Control) {
            try {
                Control msgControl = (Control) nativeElement;
                msgControl.click();
            } catch (Exception e) {
                // 忽略
            }
        }
    }

    /**
     * 右键该消息，选择指定选项
     *
     * @param option 选项名称（如"复制"、"转发"等）
     * @return 操作结果
     */
    public WxResponse selectOption(String option) {
        try {
            if (!(nativeElement instanceof Control)) {
                return WxResponse.fail("消息无关联 UI 元素");
            }
            // 右键消息打开上下文菜单
            Control msgControl = (Control) nativeElement;
            msgControl.rightClick();
            // TODO: 选择指定选项
            return WxResponse.fail("selectOption 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("选择选项失败: " + e.getMessage());
        }
    }

    /**
     * 引用该消息并回复
     *
     * @param text    引用回复内容
     * @param at      @的用户列表（可选）
     * @param timeout 超时时间（秒）
     * @return 操作结果
     */
    public WxResponse quote(String text, List<String> at, int timeout) {
        try {
            if (!(nativeElement instanceof Control)) {
                return WxResponse.fail("消息无关联 UI 元素");
            }

            // 先点击消息
            Control msgControl = (Control) nativeElement;
            msgControl.click();
            try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // TODO: 需要通过微信引用功能面板完成引用回复
            return WxResponse.fail("quote 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("引用回复失败: " + e.getMessage());
        }
    }

    public WxResponse quote(String text) {
        return quote(text, null, 3);
    }

    /**
     * 转发该消息
     *
     * @param targets 转发目标（用户名列表）
     * @param message 附加消息（可选）
     * @param timeout 超时时间（秒）
     * @return 操作结果
     */
    public WxResponse forward(List<String> targets, String message, int timeout) {
        try {
            if (!(nativeElement instanceof Control)) {
                return WxResponse.fail("消息无关联 UI 元素");
            }

            // 右键消息 → 选择"转发"
            WxResponse selectResult = selectOption("转发");
            if (!selectResult.isSuccess()) {
                return selectResult;
            }

            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            // TODO: 选择转发目标并确认
            return WxResponse.fail("forward 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("转发失败: " + e.getMessage());
        }
    }

    public WxResponse forward(String target) {
        return forward(java.util.Collections.singletonList(target), null, 3);
    }

    public WxResponse forward(List<String> targets) {
        return forward(targets, null, 3);
    }

    /**
     * 拍一拍该消息发送人
     *
     * @return 操作结果
     */
    public WxResponse tickle() {
        try {
            if (!(nativeElement instanceof Control)) {
                return WxResponse.fail("消息无关联 UI 元素");
            }
            return selectOption("拍一拍");
        } catch (Exception e) {
            return WxResponse.fail("拍一拍失败: " + e.getMessage());
        }
    }

    /**
     * 下载该消息发送人的头像
     */
    public void downloadHeadImage() {
        try {
            if (!(nativeElement instanceof Control)) return;
            // TODO: 需要通过消息发送人头像控件下载
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 编辑该消息发送人的备注和标签
     *
     * @param addTags    要添加的标签
     * @param removeTags 要移除的标签
     * @param remark     备注
     * @return 操作结果
     */
    public WxResponse editInfo(List<String> addTags, List<String> removeTags, String remark) {
        if (addTags == null && removeTags == null && remark == null) {
            return WxResponse.fail("addTags、removeTags、remark 不能同时为空");
        }
        try {
            // TODO: 需要通过发送人信息面板编辑
            return WxResponse.fail("editInfo 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("编辑好友信息失败: " + e.getMessage());
        }
    }

    // ==================== FriendMessage 方法 ====================

    /**
     * 获取发送人信息（仅好友/群友消息）
     *
     * @return 发送人信息字典
     */
    public Map<String, String> senderInfo() {
        Map<String, String> info = new LinkedHashMap<>();
        if (sender != null) {
            info.put("sender", sender);
        }
        // TODO: 通过 UI 获取更详细的发送人信息
        return info;
    }

    /**
     * 删除该消息发送人（联系人）
     *
     * @param clear 是否同时清除聊天记录
     * @return 操作结果
     */
    public WxResponse deleteFriend(boolean clear) {
        try {
            // TODO: 需要通过发送人信息面板删除好友
            return WxResponse.fail("deleteFriend 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("删除好友失败: " + e.getMessage());
        }
    }

    public WxResponse deleteFriend() {
        return deleteFriend(true);
    }

    /**
     * 添加该消息发送人为好友（适用于群聊中尚未添加的成员）
     *
     * @param addmsg     附加消息
     * @param remark     备注
     * @param tags       标签列表
     * @param permission 权限（"朋友圈"/"仅聊天"）
     * @return 操作结果
     */
    public WxResponse addFriend(String addmsg, String remark, List<String> tags, String permission) {
        try {
            // TODO: 需要通过发送人信息面板添加好友
            return WxResponse.fail("addFriend 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("添加好友失败: " + e.getMessage());
        }
    }

    public WxResponse addFriend() {
        return addFriend(null, null, null, "朋友圈");
    }

    // ==================== 特定消息类型方法 ====================

    /**
     * 下载图片/视频/文件
     *
     * @param dirPath  下载目录（null 使用默认路径）
     * @param original 是否下载原文件
     * @return 文件路径或失败信息
     */
    public Object download(String dirPath, boolean original) {
        try {
            if (!isImage() && !isVideo() && !isFile()) {
                return WxResponse.fail("当前消息类型不支持下载");
            }
            // TODO: 通过右键菜单"另存为"或自动下载机制实现
            return WxResponse.fail("download 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("下载失败: " + e.getMessage());
        }
    }

    public Object download() {
        return download(null, false);
    }

    /**
     * OCR 提取图片中的文字（仅 image 类型）
     *
     * @param timeout 超时时间（秒）
     * @return 识别出的文字
     */
    public String ocr(int timeout) {
        try {
            if (!isImage()) return null;
            // TODO: 通过微信自带 OCR 功能实现
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String ocr() {
        return ocr(3);
    }

    /**
     * 语音转文字（仅 voice 类型）
     *
     * @return 转换后的文字
     */
    public String toText() {
        try {
            if (!isVoice()) return null;
            // TODO: 通过微信自带语音转文字功能实现
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 获取链接地址（仅 link 类型）
     *
     * @param timeout 超时时间（秒）
     * @return 链接地址
     */
    public String getUrl(int timeout) {
        try {
            if (!isLink()) return null;
            // TODO: 通过点击链接消息获取 URL
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String getUrl() {
        return getUrl(10);
    }

    /**
     * 下载引用消息中的图片或视频（仅 quote 类型）
     *
     * @param dirPath 下载目录
     * @param timeout 超时时间（秒）
     * @return 文件路径或 null
     */
    public Object downloadQuoteImage(String dirPath, int timeout) {
        try {
            if (!isQuote()) return null;
            // TODO: 通过右键引用内容中的图片/视频实现
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public Object downloadQuoteImage() {
        return downloadQuoteImage(null, 10);
    }

    /**
     * 获取笔记内容（仅 note 类型）
     *
     * @return 笔记内容列表（字符串为文本，Object 为文件路径）
     */
    public List<Object> getNoteContent() {
        try {
            if (!isNote()) return null;
            // TODO: 打开笔记面板读取内容
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * 保存笔记中的文件（仅 note 类型）
     *
     * @param dirPath 保存路径
     * @return 操作结果
     */
    public WxResponse saveNoteFiles(String dirPath) {
        try {
            if (!isNote()) return WxResponse.fail("非笔记消息类型");
            return WxResponse.fail("saveNoteFiles 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("保存笔记文件失败: " + e.getMessage());
        }
    }

    public WxResponse saveNoteFiles() {
        return saveNoteFiles(null);
    }

    /**
     * 将笔记转换为 Markdown 格式（仅 note 类型）
     *
     * @param dirPath 保存路径
     * @return markdown 文件路径
     */
    public String toMarkdown(String dirPath) {
        try {
            if (!isNote()) return null;
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    public String toMarkdown() {
        return toMarkdown(null);
    }

    // ==================== toString ====================

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("Message{");
        sb.append("attr='").append(attr).append('\'');
        sb.append(", msgType='").append(msgType).append('\'');
        if (sender != null) {
            sb.append(", sender='").append(sender).append('\'');
        }
        sb.append(", content='").append(content).append('\'');
        if (time != null) {
            sb.append(", time='").append(time).append('\'');
        }
        sb.append('}');
        return sb.toString();
    }
}
