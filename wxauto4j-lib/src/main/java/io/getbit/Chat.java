package io.getbit;

import io.getbit.elements.Message;
import io.getbit.elements.WxResponse;
import io.getbit.internal.WxLayout;
import io.getbit.internal.WxParams;
import io.getbit.internal.languages.MainLanguage;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.EditControl;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.enums.ControlType;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.datatransfer.Transferable;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 聊天窗口基类
 *
 * <p>对标 wxautox4 的 Chat 类。代表一个独立的聊天窗口实例，
 * 提供发送消息、获取消息、群管理等操作。</p>
 *
 * <p>{@link WeChat} 继承此类，代表微信主窗口；
 * 子窗口（独立聊天窗口）也通过此类封装。</p>
 */
public class Chat {

    /** 聊天对象名称（主窗口时为 null） */
    protected String who;

    /** 聊天窗口类型：friend/group/service/official */
    protected String chatType;

    /** 窗口控件 */
    protected WindowControl window;

    /** 窗口布局 */
    protected WxLayout layout;

    /** 语言 */
    protected String language;

    /**
     * 默认构造（子类调用）
     */
    protected Chat() {
    }

    /**
     * 创建聊天实例
     *
     * @param who      聊天对象名称（可为 null）
     * @param window   窗口控件
     * @param language 语言
     */
    public Chat(String who, WindowControl window, String language) {
        this.who = who;
        this.window = window;
        this.language = language;
        if (window != null) {
            this.layout = WxLayout.parse(window);
        }
    }

    // ==================== 属性 ====================

    /**
     * 获取聊天对象名
     */
    public String getWho() {
        return who;
    }

    /**
     * 获取聊天窗口类型
     *
     * @return friend/group/service/official
     */
    public String getChatType() {
        return chatType;
    }

    /**
     * 获取窗口控件
     */
    public WindowControl getWindow() {
        return window;
    }

    // ==================== 窗口操作 ====================

    /**
     * 显示窗口
     */
    public void Show() {
        _show();
    }

    /**
     * 获取聊天窗口信息
     *
     * @return 聊天信息字典
     */
    public java.util.Map<String, String> ChatInfo() {
        java.util.Map<String, String> info = new java.util.LinkedHashMap<>();
        if (chatType != null) {
            info.put("chat_type", chatType);
        }
        if (who != null) {
            info.put("chat_name", who);
        }
        return info;
    }

    /**
     * 关闭窗口
     */
    public void Close() {
        try {
            if (window != null) {
                window.close();
            }
        } catch (Exception e) {
            // 忽略
        }
    }

    // ==================== 消息发送 ====================

    /**
     * 发送文本消息
     *
     * @param msg 消息内容
     * @return 操作结果
     */
    public WxResponse SendMsg(String msg) {
        return SendMsg(msg, null, null, true, false);
    }

    /**
     * 发送文本消息并@指定人
     *
     * @param msg 消息内容
     * @param at  @的人列表
     * @return 操作结果
     */
    public WxResponse SendMsg(String msg, List<String> at) {
        return SendMsg(msg, at != null ? at.toArray(new String[0]) : null, true, false);
    }

    /**
     * 发送文本消息
     *
     * @param msg   消息内容
     * @param at    @的人数组
     * @param clear 发送后是否清空输入框
     * @param exact 是否精确匹配
     * @return 操作结果
     */
    public WxResponse SendMsg(String msg, String[] at, boolean clear, boolean exact) {
        try {
            _show();
            EditControl editBox = getEditBox();
            editBox.click();
            editBox.sendKeys("^a");
            editBox.sendKeys("{DELETE}");

            // 处理 @功能
            if (at != null && at.length > 0) {
                for (String person : at) {
                    editBox.sendKeys("@" + person);
                    try { Thread.sleep(300); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                    Control atWnd = Control.getBackend().findControl(
                            SearchCondition.builder().className("ChatContactMenu").build());
                    if (atWnd.exists(1)) {
                        editBox.sendKeys("{ENTER}");
                    }
                }
                if (msg != null && !msg.isEmpty() && !msg.startsWith("\n")) {
                    msg = "\n" + msg;
                }
            }

            if (msg != null && !msg.isEmpty()) {
                setClipboard(msg);
                editBox.sendKeys("^v");
            }

            editBox.sendKeys("{ENTER}");

            if (clear) {
                try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
                editBox.sendKeys("^a");
                editBox.sendKeys("{DELETE}");
            }

            return WxResponse.ok("发送成功");
        } catch (Exception e) {
            return WxResponse.fail("发送消息失败: " + e.getMessage());
        }
    }

    /**
     * 发送文本消息（完整参数）
     */
    public WxResponse SendMsg(String msg, String who, String[] at, boolean clear, boolean exact) {
        return SendMsg(msg, at, clear, exact);
    }

    /**
     * @所有人
     *
     * @param msg 消息内容
     * @return 操作结果
     */
    public WxResponse AtAll(String msg) {
        return AtAll(msg, null, false);
    }

    /**
     * @所有人（指定发送对象）
     *
     * @param msg   消息内容
     * @param who   发送给谁（可选）
     * @param exact 是否精确匹配（可选）
     * @return 操作结果
     */
    public WxResponse AtAll(String msg, String who, boolean exact) {
        try {
            _show();
            EditControl editBox = getEditBox();
            editBox.click();
            editBox.sendKeys("^a");
            editBox.sendKeys("{DELETE}");

            // @所有人
            editBox.sendKeys("@所有人");
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            Control atWnd = Control.getBackend().findControl(
                    SearchCondition.builder().className("ChatContactMenu").build());
            if (atWnd.exists(2)) {
                editBox.sendKeys("{ENTER}");
            }

            if (msg != null && !msg.isEmpty() && !msg.startsWith("\n")) {
                msg = "\n" + msg;
            }
            if (msg != null && !msg.isEmpty()) {
                setClipboard(msg);
                editBox.sendKeys("^v");
            }
            editBox.sendKeys("{ENTER}");

            return WxResponse.ok("@所有人发送成功");
        } catch (Exception e) {
            return WxResponse.fail("@所有人失败: " + e.getMessage());
        }
    }

    // ==================== 文件发送 ====================

    /**
     * 发送文件
     *
     * @param filepath 文件绝对路径
     * @return 操作结果
     */
    public WxResponse SendFiles(String filepath) {
        return SendFiles(java.util.Collections.singletonList(filepath));
    }

    /**
     * 发送多个文件
     *
     * @param filepaths 文件路径列表
     * @return 操作结果
     */
    public WxResponse SendFiles(List<String> filepaths) {
        try {
            _show();

            List<File> files = filepaths.stream()
                    .map(File::new)
                    .filter(File::exists)
                    .collect(Collectors.toList());

            if (files.isEmpty()) {
                return WxResponse.fail("未找到有效的文件路径");
            }

            setClipboardFiles(files);

            EditControl editBox = getEditBox();
            editBox.click();
            editBox.sendKeys("^v");

            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }

            editBox.sendKeys("{ENTER}");
            return WxResponse.ok("文件发送成功");
        } catch (Exception e) {
            return WxResponse.fail("发送文件失败: " + e.getMessage());
        }
    }

    // ==================== 语音发送 ====================

    /**
     * 发送语音消息 [Beta]
     *
     * <p>注意：该方法为 Beta 功能，需微信 4.1.9+ 版本客户端，
     * 且可能需要额外配置，当前可能不稳定。</p>
     *
     * @param filepath 音频文件路径
     * @return 操作结果
     */
    public WxResponse SendAudio(String filepath) {
        return SendAudio(filepath, null, 0, null, false, 3);
    }

    /**
     * 发送语音消息 [Beta]
     *
     * @param filepath  音频文件路径
     * @param duration  发送时长（秒），null 表示使用原文件时长
     * @param start     从第几秒开始播放
     * @param who       发送对象（可选）
     * @param exact     是否精确匹配
     * @param maxRetries 最大重试次数
     * @return 操作结果
     */
    public WxResponse SendAudio(String filepath, Integer duration, int start,
                                 String who, boolean exact, int maxRetries) {
        try {
            File audioFile = new File(filepath);
            if (!audioFile.exists()) {
                return WxResponse.fail("音频文件不存在: " + filepath);
            }

            _show();

            // TODO: Beta 功能，需要微信 4.1.9+ 版本支持
            // 实际实现需要通过微信的语音录制面板操作：
            // 1. 点击语音录制按钮
            // 2. 导入/拖放音频文件
            // 3. 设置起始时间和时长
            // 4. 点击发送
            return WxResponse.fail("SendAudio 为 Beta 功能，需要微信 4.1.9+ 版本，尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("发送语音失败: " + e.getMessage());
        }
    }

    // ==================== 消息获取 ====================

    /**
     * 获取当前聊天窗口的所有消息
     *
     * @return 消息列表
     */
    public List<Message> GetAllMessage() {
        List<Message> messages = new ArrayList<>();
        _show();

        Control chatBox = Control.getBackend().findControl(layout.getChatBoxCondition());
        Control listControl = chatBox.findList();

        int[] chatRect = getBoundingRect(listControl);

        for (Control child : listControl.getChildren()) {
            Message msg = parseMessageItem(child, chatRect);
            if (msg != null) {
                messages.add(msg);
            }
        }

        return messages;
    }

    // ==================== 群管理 ====================

    /**
     * 添加群成员
     *
     * @param members 成员名或成员名列表
     * @return 操作结果
     */
    public WxResponse AddGroupMembers(List<String> members) {
        if (members == null || members.isEmpty()) {
            return WxResponse.fail("至少需要提供一个成员");
        }
        try {
            _show();
            // TODO: 需要通过群信息面板操作添加群成员
            // 实际实现需要打开群信息面板，点击"+"按钮，选择联系人
            return WxResponse.fail("AddGroupMembers 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("添加群成员失败: " + e.getMessage());
        }
    }

    /**
     * 添加群成员（单个）
     */
    public WxResponse AddGroupMembers(String member) {
        return AddGroupMembers(java.util.Collections.singletonList(member));
    }

    /**
     * 修改群聊名称
     *
     * @param value 新群名
     * @return 操作结果
     */
    public WxResponse SetGroupName(String value) {
        try {
            _show();
            // TODO: 需要通过群信息面板修改群名
            return WxResponse.fail("SetGroupName 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("修改群名失败: " + e.getMessage());
        }
    }

    /**
     * 修改群聊备注
     *
     * @param value 群备注
     * @return 操作结果
     */
    public WxResponse SetGroupRemark(String value) {
        try {
            _show();
            // TODO: 需要通过群信息面板修改备注
            return WxResponse.fail("SetGroupRemark 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("修改群备注失败: " + e.getMessage());
        }
    }

    /**
     * 修改群公告
     *
     * @param value 群公告内容
     * @return 操作结果
     */
    public WxResponse SetGroupAnnouncement(String value) {
        try {
            _show();
            // TODO: 需要通过群信息面板发布群公告
            return WxResponse.fail("SetGroupAnnouncement 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("修改群公告失败: " + e.getMessage());
        }
    }

    /**
     * 修改我在群里的昵称
     *
     * @param value 新的群内昵称
     * @return 操作结果
     */
    public WxResponse SetGroupMyNickname(String value) {
        try {
            _show();
            // TODO: 需要通过群信息面板修改群内昵称
            return WxResponse.fail("SetGroupMyNickname 尚未完整实现");
        } catch (Exception e) {
            return WxResponse.fail("修改群昵称失败: " + e.getMessage());
        }
    }

    // ==================== 内部方法 ====================

    /**
     * 确保窗口可见并处于前台
     */
    protected void _show() {
        if (window == null) return;
        try {
            if (window.getWindowPattern().getVisualState() == 2) {
                window.getWindowPattern().restore();
            }
            window.click();
        } catch (Exception e) {
            // 忽略
        }
    }

    /**
     * 获取聊天输入框控件
     */
    protected EditControl getEditBox() {
        Control chatBox = Control.getBackend().findControl(layout.getChatBoxCondition());
        Control edit = chatBox.findEdit();
        if (edit instanceof EditControl) {
            return (EditControl) edit;
        }
        Control editFromLayout = Control.getBackend().findControl(layout.getEditBoxCondition());
        if (editFromLayout instanceof EditControl) {
            return (EditControl) editFromLayout;
        }
        throw new IllegalStateException("无法定位聊天输入框");
    }

    /**
     * 解析单个消息项
     */
    protected Message parseMessageItem(Control item, int[] chatRect) {
        String name = item.getName();
        int[] rect = item.getBoundingRectangle();
        int height = rect[3] - rect[1];
        String runtimeId = buildRuntimeId(item);

        if (height <= WxParams.SYS_TEXT_HEIGHT) {
            if (name != null && name.contains("撤回")) {
                return new Message(Message.ATTR_SYSTEM, Message.MSG_TYPE_RECALL, null, name, runtimeId);
            }
            if (isTimeMessage(name)) {
                Message m = new Message(Message.ATTR_SYSTEM, Message.MSG_TYPE_TIME, null, name, runtimeId);
                m.setTime(name);
                return m;
            }
            return new Message(Message.ATTR_SYSTEM, Message.MSG_TYPE_OTHER, null, name, runtimeId);
        }

        String attr;
        String sender = null;

        if (chatRect != null && rect.length >= 4) {
            int chatCenterX = chatRect[0] + (chatRect[2] - chatRect[0]) / 2;
            int msgCenterX = rect[0] + (rect[2] - rect[0]) / 2;
            attr = msgCenterX < chatCenterX ? Message.ATTR_FRIEND : Message.ATTR_SELF;
        } else {
            attr = Message.ATTR_FRIEND;
        }

        String content = name != null ? name : "";
        return new Message(attr, sender, content, runtimeId);
    }

    /**
     * 判断是否为时间消息
     */
    protected boolean isTimeMessage(String text) {
        if (text == null || text.isEmpty()) return false;
        return text.contains("昨天") || text.contains("今天") || text.contains("星期")
                || text.contains("上午") || text.contains("下午")
                || text.matches("\\d{1,2}:\\d{2}.*")
                || text.contains("Monday") || text.contains("Tuesday")
                || text.contains("Yesterday") || text.contains("Today");
    }

    /**
     * 构建 RuntimeId 字符串
     */
    protected String buildRuntimeId(Control element) {
        try {
            int[] ids = element.getRuntimeId();
            if (ids != null && ids.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int id : ids) { sb.append(id).append(","); }
                return sb.toString();
            }
        } catch (Exception e) { /* ignore */ }
        int[] rect = element.getBoundingRectangle();
        String name = element.getName();
        return (name != null ? name : "") + "_" + rect[0] + "_" + rect[1] + "_" + rect[2] + "_" + rect[3];
    }

    /**
     * 获取控件边界矩形
     */
    protected int[] getBoundingRect(Control control) {
        return control.getBoundingRectangle();
    }

    /**
     * 设置剪贴板文本
     */
    protected void setClipboard(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    /**
     * 将文件列表设置到剪贴板
     */
    protected void setClipboardFiles(List<File> files) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable transferable = new Transferable() {
            @Override
            public DataFlavor[] getTransferDataFlavors() {
                return new DataFlavor[]{DataFlavor.javaFileListFlavor};
            }

            @Override
            public boolean isDataFlavorSupported(DataFlavor flavor) {
                return DataFlavor.javaFileListFlavor.equals(flavor);
            }

            @Override
            public Object getTransferData(DataFlavor flavor) {
                return files;
            }
        };
        clipboard.setContents(transferable, null);
    }

    /**
     * 语言翻译辅助方法
     */
    protected String _lang(String key) {
        return MainLanguage.get(key, language);
    }
}
