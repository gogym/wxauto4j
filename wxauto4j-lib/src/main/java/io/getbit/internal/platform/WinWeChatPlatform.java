package io.getbit.internal.platform;

import io.getbit.internal.WxLayout;
import io.getbit.internal.WxParams;
import io.getbit.uiautomation.control.Control;
import io.getbit.uiautomation.control.WindowControl;
import io.getbit.uiautomation.condition.SearchCondition;

import java.util.logging.Logger;

/**
 * Windows 平台实现
 *
 * <p>Windows 下通过窗口类名 {@link WxParams#WX_CLASS_NAME} 查找微信主窗口，
 * 布局解析采用 {@link WxLayout#parse(WindowControl)}。</p>
 */
public class WinWeChatPlatform implements WeChatPlatform {

    private static final Logger LOG = Logger.getLogger(WinWeChatPlatform.class.getName());

    /** 微信登录窗口类名（未登录时存在） */
    private static final String WX_LOGIN_WND_CLASS = "WeChatLoginWndForPC";

    @Override
    public void preInitCheck() {
        // Windows 无需额外前置检查
    }

    @Override
    public WindowControl initWindow() {
        WindowControl mainWindow = Control.window()
                .className(WxParams.WX_CLASS_NAME)
                .searchDepth(1)
                .findWindow();

        if (mainWindow == null || !mainWindow.exists(2)) {
            // 主窗口未找到，检查是否是因为未登录（存在登录窗口）
            checkLoginState();
            // 没有登录窗口，说明微信根本没启动
            throw new IllegalStateException(
                    "未找到微信窗口，请确认微信已启动并登录。窗口类名: " + WxParams.WX_CLASS_NAME);
        }
        return mainWindow;
    }

    @Override
    public void checkLoginState() {
        // 检查是否存在微信登录窗口
        try {
            WindowControl loginWnd = Control.window()
                    .className(WX_LOGIN_WND_CLASS)
                    .searchDepth(1)
                    .findWindow();
            if (loginWnd != null && loginWnd.exists(1)) {
                throw new IllegalStateException(
                        "微信已打开但尚未登录！\n" +
                        "请先在微信窗口中完成登录（扫码或账号密码登录），然后再尝试连接。");
            }
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            LOG.fine("检查登录窗口时出错: " + e.getMessage());
        }
    }

    @Override
    public WxLayout parseLayout(WindowControl mainWindow) {
        return WxLayout.parse(mainWindow);
    }
}
