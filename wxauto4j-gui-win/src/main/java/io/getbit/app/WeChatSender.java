package io.getbit.app;

import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.StringSelection;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 通过 PowerShell + Java Robot 向微信发送文本消息（Windows 版）。
 * <p>
 * 流程：PowerShell 激活微信窗口 → Ctrl+F 搜索联系人 → 粘贴联系人名 → 回车选中
 * → Robot 点击输入框确保焦点 → 粘贴消息 → 回车发送。
 * <p>
 * 注意：发送期间微信窗口会被切到前台，用户不能同时操作微信。
 */
public class WeChatSender {

    /** 输入框在窗口中的 Y 比例（输入框在窗口底部约 92% 处） */
    private static final double INPUT_Y_RATIO = 0.92;
    /** 输入框在窗口中的 X 比例（输入框在窗口右侧约 75% 处） */
    private static final double INPUT_X_RATIO = 0.75;

    /**
     * 向指定联系人/群聊发送文本消息。
     *
     * @param chatName 聊天对象的显示名称（昵称或群名，用于搜索定位）
     * @param message  要发送的文本内容
     * @return 发送结果描述，成功返回 "ok"，失败返回错误信息
     */
    public static String sendTextMessage(String chatName, String message) {
        if (chatName == null || chatName.trim().isEmpty()) {
            return "聊天对象名称不能为空";
        }
        if (message == null || message.trim().isEmpty()) {
            return "消息内容不能为空";
        }

        try {
            // 步骤1：激活微信窗口并搜索联系人
            activateWeChatWindow();
            Thread.sleep(500);

            // Ctrl+F 打开搜索
            Robot robot = new Robot();
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_F);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(500);

            // 粘贴联系人名
            setClipboard(chatName.trim());
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(2500);

            // 回车选中第一个搜索结果
            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);
            Thread.sleep(1500);

            // 步骤2：获取微信窗口位置和大小
            int[] winRect = getWeChatWindowRect();
            if (winRect == null || winRect.length < 4) {
                return "无法获取微信窗口位置";
            }
            int winX = winRect[0];
            int winY = winRect[1];
            int winW = winRect[2];
            int winH = winRect[3];

            // 步骤3：Java Robot 点击输入框区域确保焦点
            int clickX = winX + (int) (winW * INPUT_X_RATIO);
            int clickY = winY + (int) (winH * INPUT_Y_RATIO);

            robot.mouseMove(clickX, clickY);
            Thread.sleep(100);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(300);

            // 步骤4：粘贴消息并发送
            setClipboard(message.trim());
            robot.keyPress(KeyEvent.VK_CONTROL);
            robot.keyPress(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_V);
            robot.keyRelease(KeyEvent.VK_CONTROL);
            Thread.sleep(500);

            robot.keyPress(KeyEvent.VK_ENTER);
            robot.keyRelease(KeyEvent.VK_ENTER);

            return "ok";
        } catch (Exception e) {
            return "发送异常: " + e.getMessage();
        }
    }

    /**
     * 设置系统剪贴板内容
     */
    private static void setClipboard(String text) {
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        clipboard.setContents(new StringSelection(text), null);
    }

    /**
     * 激活微信窗口（使用 PowerShell）
     */
    private static void activateWeChatWindow() throws Exception {
        String script =
                "# 微信 4.x: 通过进程名查找主窗口\n" +
                "$proc = Get-Process -Name 'Weixin' -ErrorAction SilentlyContinue | Select-Object -First 1\n" +
                "if ($proc -and $proc.MainWindowHandle -ne [IntPtr]::Zero) {\n" +
                "    $hwnd = $proc.MainWindowHandle\n" +
                "} else {\n" +
                "    $hwnd = [IntPtr]::Zero\n" +
                "}\n" +
                "if ($hwnd -ne [IntPtr]::Zero) {\n" +
                "    Add-Type @\"\n" +
                "using System;\n" +
                "using System.Runtime.InteropServices;\n" +
                "public class WinAPI {\n" +
                "    [DllImport(\"user32.dll\")] public static extern bool SetForegroundWindow(IntPtr hWnd);\n" +
                "    [DllImport(\"user32.dll\")] public static extern bool ShowWindow(IntPtr hWnd, int nCmdShow);\n" +
                "}\n" +
                "\"@\n" +
                "    [WinAPI]::ShowWindow($hwnd, 9)\n" +
                "    [WinAPI]::SetForegroundWindow($hwnd)\n" +
                "    Write-Output 'OK'\n" +
                "} else {\n" +
                "    Write-Output 'NOT_FOUND'\n" +
                "}";

        String result = runPowerShell(script);
        if (result.contains("NOT_FOUND")) {
            throw new RuntimeException("未找到微信窗口，请确保微信已启动");
        }
    }

    /**
     * 获取微信窗口矩形 [x, y, width, height]
     */
    private static int[] getWeChatWindowRect() {
        try {
            String script =
                    "# 微信 4.x: 通过进程名查找主窗口\n" +
                    "$proc = Get-Process -Name 'Weixin' -ErrorAction SilentlyContinue | Select-Object -First 1\n" +
                    "if ($proc -and $proc.MainWindowHandle -ne [IntPtr]::Zero) {\n" +
                    "    $hwnd = $proc.MainWindowHandle\n" +
                    "    Add-Type @\"\n" +
                    "using System;\n" +
                    "using System.Runtime.InteropServices;\n" +
                    "public class WinAPI2 {\n" +
                    "    [StructLayout(LayoutKind.Sequential)] public struct RECT { public int Left, Top, Right, Bottom; }\n" +
                    "    [DllImport(\"user32.dll\")] public static extern bool GetWindowRect(IntPtr hWnd, out RECT lpRect);\n" +
                    "}\n" +
                    "\"@\n" +
                    "    $rect = New-Object WinAPI2+RECT\n" +
                    "    [WinAPI2]::GetWindowRect($hwnd, [ref]$rect)\n" +
                    "    Write-Output \"$($rect.Left),$($rect.Top),$($rect.Right - $rect.Left),$($rect.Bottom - $rect.Top)\"\n" +
                    "}";

            String result = runPowerShell(script).trim();
            String[] parts = result.split(",");
            if (parts.length >= 4) {
                return new int[]{
                        Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()),
                        Integer.parseInt(parts[2].trim()),
                        Integer.parseInt(parts[3].trim())
                };
            }
        } catch (Exception e) {
            System.err.println("[WeChatSender] 获取窗口矩形失败: " + e.getMessage());
        }
        return null;
    }

    /**
     * 执行 PowerShell 脚本并返回输出
     */
    private static String runPowerShell(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                "-Command", script);
        pb.redirectErrorStream(true);
        Process process = pb.start();

        StringBuilder output = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                output.append(line).append("\n");
            }
        }
        process.waitFor();
        return output.toString().trim();
    }
}
