package io.getbit.internal;

/**
 * 微信相关常量定义
 *
 * <p>包含微信窗口类名、消息控件高度阈值等常量。
 * 这些值基于微信 4.0.5 版本的 UI 结构，可能因 DPI 缩放或版本更新而变化。</p>
 */
public class WxParams {

    /** 微信主窗口类名 */
    public static final String WX_CLASS_NAME = "WeChatMainWndForPC";

    /**
     * 系统消息控件高度（像素）
     * <p>用于判断消息类型：系统通知消息（如"你已添加了xxx为好友"）</p>
     */
    public static final int SYS_TEXT_HEIGHT = 34;

    /**
     * 时间分隔控件高度（像素）
     * <p>用于判断消息类型：时间分隔线（如"昨天 12:30"）</p>
     */
    public static final int TIME_TEXT_HEIGHT = 34;

    /**
     * 撤回消息控件高度（像素）
     * <p>用于判断消息类型：撤回提示（如"xxx撤回了一条消息"）</p>
     */
    public static final int RECALL_TEXT_HEIGHT = 34;

    /** 独立聊天窗口类名 */
    public static final String CHAT_WND_CLASS = "ChatWnd";

    private WxParams() {
        // 工具类，禁止实例化
    }
}
