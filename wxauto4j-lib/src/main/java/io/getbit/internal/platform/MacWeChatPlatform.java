package io.getbit.internal.platform;

import io.getbit.internal.WxLayout;
import io.getbit.uiautomation.condition.SearchCondition;
import io.getbit.uiautomation.control.WindowControl;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.List;
import java.util.logging.Logger;

/**
 * macOS 平台实现
 *
 * <p>macOS 下通过 PID + Accessibility API 定位微信主窗口。
 * 布局解析采用 {@link WxLayout#parseMac(WindowControl)}。</p>
 *
 * <p>macOS 的 Accessibility API 不支持 className 过滤，
 * 因此使用 {@code AXUIElementCreateApplication(pid)} 直接获取微信应用节点，
 * 再从其子元素中查找 AXWindow。</p>
 */
public class MacWeChatPlatform implements WeChatPlatform {

    private static final Logger LOG = Logger.getLogger(MacWeChatPlatform.class.getName());

    /** 微信进程 PID（initWindow 时设置） */
    private int wechatPid = -1;

    @Override
    public void preInitCheck() {
        checkAccessibilityPermission();
    }
    
    @Override
    public WindowControl initWindow() {
        log("[wxauto4j] === macOS 窗口定位开始 ===");
    
        // 1. 获取微信进程 Pid
        log("[wxauto4j] 正在查找微信进程...");
        int pid = findPidByProcessName("WeChat");
        if (pid <= 0) {
            pid = findPidByProcessName("wechat");
        }
        if (pid <= 0) {
            throw new IllegalStateException(
                    "未找到微信进程，请确认微信已启动。尝试了进程名: WeChat, wechat");
        }
        log("[wxauto4j] 找到微信进程 PID=" + pid);
        this.wechatPid = pid;
    
        // 2. 通过反射调用 AXUIElementCreateApplication(pid) 获取应用级元素
        log("[wxauto4j] 正在通过 PID 创建 AXUIElement...");
        Object appElement;
        try {
            Class<?> axClass = Class.forName("io.getbit.uiautomation.mac.ax.AXUIElement");
            Class<?> appServicesClass = Class.forName("io.getbit.uiautomation.mac.ax.ApplicationServices");
            Object instance = appServicesClass.getField("INSTANCE").get(null);
            java.lang.reflect.Method createAppMethod = appServicesClass.getMethod(
                    "AXUIElementCreateApplication", int.class);
            Object ref = createAppMethod.invoke(instance, pid);
            if (ref == null) {
                throw new IllegalStateException("AXUIElementCreateApplication 返回 null");
            }
            java.lang.reflect.Constructor<?> ctor = null;
            for (java.lang.reflect.Constructor<?> c : axClass.getConstructors()) {
                Class<?>[] paramTypes = c.getParameterTypes();
                if (paramTypes.length == 2 && paramTypes[1] == boolean.class) {
                    ctor = c;
                    break;
                }
            }
            if (ctor == null) {
                throw new IllegalStateException("未找到 AXUIElement 构造函数");
            }
            appElement = ctor.newInstance(ref, true);
            log("[wxauto4j] 应用级 AXUIElement 创建成功");
        } catch (Exception e) {
            throw new IllegalStateException("通过 PID 创建微信 AXUIElement 失败: " + e.getMessage(), e);
        }
    
        // 3. 从应用节点搜索子窗口（AXRole == "AXWindow"）
        log("[wxauto4j] 正在搜索 AXWindow 子元素...");
        Object windowElement = findMacWindowChild(appElement);
        if (windowElement == null) {
            log("[wxauto4j] 未找到 AXWindow 子元素，使用应用级节点");
            windowElement = appElement;
        } else {
            log("[wxauto4j] 找到 AXWindow 子元素");
        }
    
        // 4. 包装为 WindowControl
        SearchCondition cond = SearchCondition.builder().name("微信").build();
        WindowControl mainWindow = new WindowControl(cond);
        mainWindow.setNativeElement(windowElement);
        mainWindow.setElementFound(true);
    
        log("[wxauto4j] 微信窗口已定位 (PID=" + pid + ")");
    
        // 5. 诊断：打印 Accessibility 树结构
        log("[wxauto4j] === Accessibility 树结构（深度8） ===");
        dumpMacAccessibilityTree(windowElement, 0, 8);
        log("[wxauto4j] === Accessibility 树输出结束 ===");
    
        // 注意：Mac 版微信的 AX 树不暴露内部 UI 元素（只有窗口控制按钮），
        // 因此无法通过子元素检测登录状态，跳过 checkLoginState。
    
        return mainWindow;
    }
    
    @Override
    public void checkLoginState() {
        // macOS 版微信的 Accessibility API 不暴露窗口内部 UI 元素，
        // 无法通过 AX 子元素判断登录状态，暂不实现。
        log("[wxauto4j] macOS 平台暂不支持登录状态检测（AX 树不暴露内部 UI）");
    }

    @Override
    public WxLayout parseLayout(WindowControl mainWindow) {
        return WxLayout.parseMac(mainWindow);
    }

    // ==================== macOS 专有方法 ====================

    /**
     * 检查辅助功能权限
     *
     * <p>macOS 要求用户在"系统设置 → 隐私与安全 → 辅助功能"中授权应用。
     * 如果未授权，所有 AX 调用都会返回权限错误，无法进行任何 UI 自动化操作。</p>
     */
    private void checkAccessibilityPermission() {
        log("[wxauto4j] 正在检查辅助功能权限...");
        try {
            Class<?> appServicesClass = Class.forName("io.getbit.uiautomation.mac.ax.ApplicationServices");
            Object instance = appServicesClass.getField("INSTANCE").get(null);
            java.lang.reflect.Method isTrustedMethod = appServicesClass.getMethod("AXIsProcessTrusted");
            boolean trusted = (Boolean) isTrustedMethod.invoke(instance);
            if (!trusted) {
                throw new IllegalStateException(
                        "macOS 辅助功能权限未授予！\n" +
                        "请按以下步骤操作：\n" +
                        "1. 打开 系统设置 → 隐私与安全 → 辅助功能\n" +
                        "2. 点击左下角锁图标解锁\n" +
                        "3. 点击 + 号，添加 IntelliJ IDEA（或你的 IDE）\n" +
                        "4. 确保开关已打开\n" +
                        "5. 重启 IDE 后再次尝试");
            }
            log("[wxauto4j] 辅助功能权限已授予");
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            log("[wxauto4j] 检查权限时出错: " + e.getMessage());
        }
    }

    /**
     * 通过进程名查找 PID
     */
    private int findPidByProcessName(String processName) {
        try {
            ProcessBuilder pb = new ProcessBuilder("pgrep", "-x", processName);
            Process proc = pb.start();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(proc.getInputStream()));
            String line = reader.readLine();
            proc.waitFor();
            if (line != null && !line.isEmpty()) {
                return Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            log("[wxauto4j] 查找进程 " + processName + " 失败: " + e.getMessage());
        }
        return -1;
    }

    /**
     * 从应用级 AXUIElement 的子元素中查找 AXWindow
     */
    private Object findMacWindowChild(Object appElement) {
        try {
            java.lang.reflect.Method getChildrenMethod =
                    appElement.getClass().getMethod("getChildren");
            @SuppressWarnings("unchecked")
            List<?> children = (List<?>) getChildrenMethod.invoke(appElement);
            log("[wxauto4j] AXApplication 子元素数量: " + children.size());
            for (Object child : children) {
                java.lang.reflect.Method getRoleMethod = child.getClass().getMethod("getRole");
                String role = (String) getRoleMethod.invoke(child);
                java.lang.reflect.Method getNameMethod = child.getClass().getMethod("getName");
                String name = (String) getNameMethod.invoke(child);
                log("[wxauto4j]   子元素: role=" + role + ", name=" + name);
                if ("AXWindow".equals(role)) {
                    return child;
                }
            }
            // 直接子元素中未找到，递归搜索
            log("[wxauto4j] 直接子元素中未找到 AXWindow，进行递归搜索...");
            for (Object child : children) {
                Object found = findMacWindowRecursive(child);
                if (found != null) return found;
            }
        } catch (Exception e) {
            log("[wxauto4j] 搜索微信窗口子元素失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 递归查找 AXWindow
     */
    private Object findMacWindowRecursive(Object element) {
        try {
            java.lang.reflect.Method getRoleMethod = element.getClass().getMethod("getRole");
            String role = (String) getRoleMethod.invoke(element);
            if ("AXWindow".equals(role)) return element;

            java.lang.reflect.Method getChildrenMethod = element.getClass().getMethod("getChildren");
            @SuppressWarnings("unchecked")
            List<?> children = (List<?>) getChildrenMethod.invoke(element);
            for (Object child : children) {
                Object found = findMacWindowRecursive(child);
                if (found != null) return found;
            }
        } catch (Exception e) { /* ignore */ }
        return null;
    }

    /**
     * 诊断用 - 打印 Accessibility UI 树结构
     */
    private void dumpMacAccessibilityTree(Object element, int depth, int maxDepth) {
        if (depth > maxDepth) return;
        try {
            java.lang.reflect.Method getRoleMethod = element.getClass().getMethod("getRole");
            java.lang.reflect.Method getNameMethod = element.getClass().getMethod("getName");
            java.lang.reflect.Method getChildrenMethod = element.getClass().getMethod("getChildren");

            String role = (String) getRoleMethod.invoke(element);
            String name = (String) getNameMethod.invoke(element);
            String indent = "  ".repeat(depth);

            log(indent + "[" + role + "] " + (name != null ? name : "(no title)"));

            @SuppressWarnings("unchecked")
            List<?> children = (List<?>) getChildrenMethod.invoke(element);
            for (Object child : children) {
                dumpMacAccessibilityTree(child, depth + 1, maxDepth);
            }
        } catch (Exception e) {
            log("  ".repeat(depth) + "(error: " + e.getMessage() + ")");
        }
    }

    private void log(String msg) {
        LOG.info(msg);
    }
}
