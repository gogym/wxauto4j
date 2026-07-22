package io.getbit.internal.platform;

import io.getbit.internal.WxLayout;
import io.getbit.uiautomation.control.WindowControl;

/**
 * 微信平台抽象接口
 *
 * <p>封装不同操作系统下微信自动化的平台差异，包括：
 * <ul>
 *   <li>窗口定位方式（Windows 用类名查找，macOS 用 PID + Accessibility API）</li>
 *   <li>布局解析方式（两个平台的微信 UI 结构不同）</li>
 *   <li>前置检查（如 macOS 需检查辅助功能权限）</li>
 * </ul>
 *
 * <p>{@link io.getbit.WeChat} 通过此接口与平台解耦，
 * 具体实现由 {@link WinWeChatPlatform} 和 {@link MacWeChatPlatform} 提供。</p>
 */
public interface WeChatPlatform {

    /**
     * 初始化前的平台检查
     *
     * <p>如 macOS 需检查辅助功能权限，Windows 无需额外检查。</p>
     */
    void preInitCheck();

    /**
     * 初始化并定位微信主窗口
     *
     * @return 已定位的微信主窗口控件
     */
    WindowControl initWindow();

    /**
     * 解析微信主窗口布局
     *
     * @param mainWindow 已定位的微信主窗口
     * @return 解析完成的布局对象
     */
    WxLayout parseLayout(WindowControl mainWindow);

    /**
     * 检查微信是否已登录
     *
     * <p>微信打开但未登录时（如显示二维码登录页面），应抛出异常提示用户先完成登录。</p>
     *
     * @throws IllegalStateException 如果微信未登录
     */
    default void checkLoginState() {
        // 默认不检查，由各平台按需实现
    }
}
