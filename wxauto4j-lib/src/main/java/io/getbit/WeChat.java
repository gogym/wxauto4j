package io.getbit;

import io.getbit.elements.Message;
import io.getbit.internal.WxLayout;
import io.getbit.internal.WxParams;
import io.getbit.internal.languages.MainLanguage;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.EditControl;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.enums.ControlType;
import io.getbit.uiautomation.win.WinAutomation;
import io.getbit.uiautomation.win.com.IUIAutomationElement;

import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.util.ArrayList;
import java.util.List;

/**
 * 微信自动化主类
 *
 * <p>对标 wxautox4 (Python) 的核心 WeChat 类，提供微信消息发送、接收等自动化操作接口。
 * 基于 uiautomation4j-win 框架，通过 Windows UIAutomation 操控微信 PC 客户端。</p>
 *
 * <p><b>仅适用于微信 4.0.5 版本客户端</b></p>
 *
 * <h3>使用示例：</h3>
 * <pre>
 * // 创建微信实例（默认中文）
 * WeChat wx = new WeChat();
 *
 * // 发送消息给当前聊天窗口
 * wx.SendMsg("你好世界！", null);
 *
 * // 发送消息给指定联系人
 * wx.SendMsg("你好！", "文件传输助手");
 *
 * // 获取当前聊天所有消息
 * List&lt;Message&gt; messages = wx.GetAllMessage();
 * for (Message msg : messages) {
 *     System.out.println(msg);
 * }
 * </pre>
 */
public class WeChat {

    /** 微信客户端支持的目标版本号 */
    public static final String VERSION = "4.0.5";

    /** UIAutomation 主窗口控件 */
    private WindowControl mainWindow;

    /** 微信窗口布局 */
    private WxLayout layout;

    /** 当前微信客户端语言 */
    private String language;

    /** 当前登录用户昵称 */
    private String nickname;

    /** 已使用过的消息 RuntimeId 集合（用于增量消息检测） */
    private List<String> usedmsgid = new ArrayList<>();

    /**
     * 创建微信自动化实例（默认简体中文）
     *
     * <p>初始化流程：</p>
     * <ol>
     *   <li>初始化 Windows UIAutomation 后端</li>
     *   <li>定位微信主窗口（类名 WeChatMainWndForPC）</li>
     *   <li>解析窗口布局（NavigationBox / SessionBox / ChatBox）</li>
     * </ol>
     *
     * @throws IllegalStateException 如果微信未启动或窗口不可见
     */
    public WeChat() {
        this(MainLanguage.LANG_CN);
    }

    /**
     * 创建微信自动化实例（指定语言）
     *
     * @param language 微信客户端语言版本（"cn" 简体中文 / "en" 英文）
     * @throws IllegalStateException 如果微信未启动或窗口不可见
     */
    public WeChat(String language) {
        this.language = language;
        init();
    }

    // ==================== 公开 API ====================

    /**
     * 发送文本消息
     *
     * <p>向指定联系人或当前聊天窗口发送文本消息。
     * 如果 who 为 null，则向当前已打开的聊天窗口发送。</p>
     *
     * @param msg 要发送的文本消息内容
     * @param who 目标联系人/群名称，null 表示当前聊天窗口
     */
    public void SendMsg(String msg, String who) {
        SendMsg(msg, who, null);
    }

    /**
     * 发送文本消息并@指定人
     *
     * <p>在群聊中发送消息并@指定成员。@功能通过输入 @昵称 触发联系人选择弹窗实现。</p>
     *
     * @param msg 要发送的文本消息内容
     * @param who 目标联系人/群名称，null 表示当前聊天窗口
     * @param at  要@的人列表，可以为 null
     */
    public void SendMsg(String msg, String who, String[] at) {
        // 1. 切换到目标聊天（如果指定了 who）
        if (who != null) {
            ChatWith(who);
        }

        // 2. 确保微信窗口可见
        _show();

        // 3. 定位并清空输入框
        EditControl editBox = getEditBox();
        editBox.click();
        editBox.sendKeys("^a");
        editBox.sendKeys("{DELETE}");

        // 4. 处理 @功能
        if (at != null && at.length > 0) {
            for (String person : at) {
                editBox.sendKeys("@" + person);
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
                // 等待联系人选择弹窗出现并确认
                Control atWnd = Control.getBackend().findControl(
                        SearchCondition.builder()
                                .className("ChatContactMenu")
                                .build());
                if (atWnd.exists(1)) {
                    editBox.sendKeys("{ENTER}");
                }
            }
            // @后追加消息内容时，先换行
            if (msg != null && !msg.isEmpty() && !msg.startsWith("\n")) {
                msg = "\n" + msg;
            }
        }

        // 5. 通过剪贴板粘贴消息内容
        if (msg != null && !msg.isEmpty()) {
            setClipboard(msg);
            editBox.sendKeys("^v");
        }

        // 6. 回车发送
        editBox.sendKeys("{ENTER}");
    }

    /**
     * 获取当前聊天窗口的所有消息
     *
     * <p>遍历当前聊天窗口的消息列表，解析每条消息的类型、发送者和内容。</p>
     *
     * <p>消息类型包括：</p>
     * <ul>
     *   <li>friend - 对方发来的消息</li>
     *   <li>self - 自己发出的消息</li>
     *   <li>sys - 系统消息</li>
     *   <li>time - 时间分隔</li>
     *   <li>recall - 撤回提示</li>
     * </ul>
     *
     * @return 消息列表，按消息在聊天窗口中的显示顺序排列
     */
    public List<Message> GetAllMessage() {
        List<Message> messages = new ArrayList<>();
        _show();

        // 获取 ChatBox 控件
        Control chatBox = Control.getBackend().findControl(layout.getChatBoxCondition());

        // 获取 ChatBox 内的消息列表（ListControl）
        Control listControl = chatBox.findList();

        // 获取消息列表的直接子元素并遍历
        Object listNative = listControl.getNativeElement();
        if (!(listNative instanceof IUIAutomationElement)) {
            return messages;
        }
        IUIAutomationElement listElement = (IUIAutomationElement) listNative;

        // 获取聊天区域的边界矩形，用于判断消息方向
        int[] chatRect = chatBox.getBackend().findControl(layout.getChatBoxCondition())
                .getBackend().findControl(layout.getChatBoxCondition())
                .getBackend().findControl(layout.getChatBoxCondition()).getName() != null
                ? getBoundingRect(chatBox) : null;

        // 通过 TreeWalker 遍历消息列表的直接子元素
        IUIAutomationElement child = listElement.getFirstChild();
        while (child != null) {
            Message msg = parseMessageItem(child, chatRect);
            if (msg != null) {
                messages.add(msg);
            }
            child = child.getNextSibling();
        }

        return messages;
    }

    /**
     * 切换到指定聊天窗口
     *
     * <p>通过搜索框输入联系人名称，定位并切换到对应的聊天窗口。
     * 优先在已有会话列表中查找，找不到则通过搜索框搜索。</p>
     *
     * @param who 要切换到的联系人/群名称（最好完整匹配，优先使用备注名）
     */
    public void ChatWith(String who) {
        _show();

        // 尝试在会话列表中直接查找
        Control sessionBox = Control.getBackend().findControl(layout.getSessionBoxCondition());
        SearchCondition itemCondition = SearchCondition.builder()
                .controlType(ControlType.ListItem)
                .name(who)
                .searchFrom(sessionBox.getSearchCondition())
                .build();

        if (Control.getBackend().exists(itemCondition, 1)) {
            Control item = Control.getBackend().findControl(itemCondition);
            item.click();
            return;
        }

        // 在会话列表中未找到，通过搜索框搜索
        Control searchEdit = sessionBox.findEdit(
                SearchCondition.builder()
                        .name(_lang("搜索"))
                        .build());
        searchEdit.click();
        searchEdit.sendKeys("^a");
        searchEdit.sendKeys("{DELETE}");
        setClipboard(who);
        searchEdit.sendKeys("^v");

        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        // 点击搜索结果中的第一个匹配项
        SearchCondition resultCondition = SearchCondition.builder()
                .controlType(ControlType.ListItem)
                .searchFrom(sessionBox.getSearchCondition())
                .build();
        Control result = Control.getBackend().findControl(resultCondition);
        result.click();
    }

    /**
     * 获取当前登录用户昵称
     *
     * @return 用户昵称
     */
    public String getNickname() {
        return nickname;
    }

    /**
     * 获取当前语言设置
     *
     * @return 语言标识（"cn" 或 "en"）
     */
    public String getLanguage() {
        return language;
    }

    // ==================== 内部方法 ====================

    /**
     * 初始化微信窗口
     */
    private void init() {
        // 1. 初始化 UIAutomation Windows 后端
        WinAutomation.init();

        // 2. 定位微信主窗口
        mainWindow = Control.window()
                .className(WxParams.WX_CLASS_NAME)
                .searchDepth(1)
                .findWindow();

        if (mainWindow == null || !mainWindow.exists(2)) {
            throw new IllegalStateException(
                    "未找到微信窗口，请确认微信已启动并登录。窗口类名: " + WxParams.WX_CLASS_NAME);
        }

        // 3. 解析窗口布局
        layout = WxLayout.parse(mainWindow);

        // 4. 尝试获取用户昵称（从导航栏底部区域）
        try {
            Control navBox = Control.getBackend().findControl(layout.getNavigationBoxCondition());
            // 昵称通常在导航栏底部的 TextControl 中
            // TODO: 需要根据实际微信 4.0.5 UI 结构调整定位方式
            this.nickname = "Unknown";
        } catch (Exception e) {
            this.nickname = "Unknown";
        }
    }

    /**
     * 确保微信窗口可见并处于前台
     */
    private void _show() {
        if (mainWindow == null) {
            return;
        }
        try {
            // 如果窗口最小化，先恢复
            if (mainWindow.getWindowPattern().getVisualState() == 2) {
                mainWindow.getWindowPattern().restore();
            }
            // 尝试将窗口置前
            mainWindow.click();
        } catch (Exception e) {
            // 忽略，继续执行
        }
    }

    /**
     * 获取聊天输入框控件
     *
     * @return 聊天输入框 EditControl
     */
    private EditControl getEditBox() {
        Control chatBox = Control.getBackend().findControl(layout.getChatBoxCondition());
        // 在 ChatBox 中查找 EditControl（消息输入框）
        Control edit = chatBox.findEdit();
        if (edit instanceof EditControl) {
            return (EditControl) edit;
        }
        // 如果直接查找失败，使用 layout 中保存的条件
        Control editFromLayout = Control.getBackend().findControl(layout.getEditBoxCondition());
        if (editFromLayout instanceof EditControl) {
            return (EditControl) editFromLayout;
        }
        throw new IllegalStateException("无法定位聊天输入框");
    }

    /**
     * 解析单个消息项
     *
     * <p>根据控件高度判断消息类型，并提取消息内容。</p>
     *
     * @param item     消息列表中的单个 ListItemControl 的原生元素
     * @param chatRect 聊天区域的边界矩形 [left, top, right, bottom]，用于判断消息方向
     * @return 解析后的 Message 对象，解析失败返回 null
     */
    private Message parseMessageItem(IUIAutomationElement item, int[] chatRect) {
        String name = item.getName();
        int[] rect = item.getBoundingRectangle();
        int height = rect[3] - rect[1]; // bottom - top
        String runtimeId = buildRuntimeId(item);

        // 根据高度判断消息类型
        if (height == WxParams.SYS_TEXT_HEIGHT) {
            // 系统消息、时间分隔、撤回提示的高度相同，通过内容进一步区分
            if (name != null && name.contains("撤回")) {
                return new Message(Message.TYPE_RECALL, null, name, runtimeId);
            }
            // 时间消息通常包含时间相关关键词
            if (isTimeMessage(name)) {
                return new Message(Message.TYPE_TIME, null, name, runtimeId);
            }
            return new Message(Message.TYPE_SYS, null, name, runtimeId);
        }

        if (height == WxParams.TIME_TEXT_HEIGHT && isTimeMessage(name)) {
            return new Message(Message.TYPE_TIME, null, name, runtimeId);
        }

        if (height == WxParams.RECALL_TEXT_HEIGHT && name != null && name.contains("撤回")) {
            return new Message(Message.TYPE_RECALL, null, name, runtimeId);
        }

        // 普通消息：判断是对方消息还是自己消息
        String type;
        String sender = null;

        if (chatRect != null && rect.length >= 4) {
            // 通过消息控件的左边界位置判断消息方向
            // 对方消息靠左，自己消息靠右
            int chatCenterX = chatRect[0] + (chatRect[2] - chatRect[0]) / 2;
            int msgCenterX = rect[0] + (rect[2] - rect[0]) / 2;
            if (msgCenterX < chatCenterX) {
                type = Message.TYPE_FRIEND;
            } else {
                type = Message.TYPE_SELF;
            }
        } else {
            // 无法判断方向时，默认标记为 friend
            type = Message.TYPE_FRIEND;
        }

        // 提取发送者名称和内容
        // TODO: 需要根据实际微信 4.0.5 UI 结构调整子控件解析逻辑
        // 当前使用控件 Name 属性作为消息内容
        String content = name != null ? name : "";

        return new Message(type, sender, content, runtimeId);
    }

    /**
     * 判断文本是否为时间消息
     */
    private boolean isTimeMessage(String text) {
        if (text == null || text.isEmpty()) {
            return false;
        }
        // 时间消息的特征：包含时间关键词或纯时间格式
        return text.contains("昨天") || text.contains("今天") || text.contains("星期")
                || text.contains("上午") || text.contains("下午")
                || text.matches("\\d{1,2}:\\d{2}.*")
                || text.contains("Monday") || text.contains("Tuesday")
                || text.contains("Yesterday") || text.contains("Today");
    }

    /**
     * 构建元素的 RuntimeId 字符串（用于消息去重）
     */
    private String buildRuntimeId(IUIAutomationElement element) {
        try {
            int[] ids = element.getRuntimeId();
            if (ids != null && ids.length > 0) {
                StringBuilder sb = new StringBuilder();
                for (int id : ids) {
                    sb.append(id).append(",");
                }
                return sb.toString();
            }
        } catch (Exception e) {
            // 忽略
        }
        // 回退方案：使用 Name + 位置 作为唯一标识
        int[] rect = element.getBoundingRectangle();
        String name = element.getName();
        return (name != null ? name : "") + "_"
                + rect[0] + "_" + rect[1] + "_" + rect[2] + "_" + rect[3];
    }

    /**
     * 获取控件的边界矩形
     */
    private int[] getBoundingRect(Control control) {
        Object nativeEl = control.getNativeElement();
        if (nativeEl instanceof IUIAutomationElement) {
            return ((IUIAutomationElement) nativeEl).getBoundingRectangle();
        }
        return null;
    }

    /**
     * 设置系统剪贴板文本
     *
     * @param text 要复制到剪贴板的文本
     */
    private void setClipboard(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    /**
     * 获取指定 key 的本地化文本
     *
     * @param key 内部标识（中文）
     * @return 当前语言对应的 UI 文本
     */
    private String _lang(String key) {
        return MainLanguage.get(key, language);
    }
}
