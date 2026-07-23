package io.getbit.app;

import java.awt.Robot;
import java.awt.event.InputEvent;
import java.io.BufferedReader;
import java.io.InputStreamReader;

/**
 * 通过 AppleScript + Java Robot 向微信发送文本消息。
 * <p>
 * 流程：激活微信 → Cmd+F 搜索联系人/群名 → 回车选中 → Robot 点击输入框确保焦点 → 粘贴消息 → 回车发送。
 * <p>
 * 注意：发送期间微信窗口会被切到前台，用户不能同时操作微信。
 * 使用 Robot 鼠标点击而非键盘导航（Escape/Tab）来聚焦输入框，
 * 因为微信在"当前聊天就是目标"时 Escape 行为不一致。
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

        // 转义 AppleScript 中的特殊字符（双引号和反斜杠）
        String escapedName = escapeAppleScript(chatName.trim());
        String escapedMsg = escapeAppleScript(message.trim());

        try {
            // 步骤1：激活微信 + 搜索选中联系人
            String searchScript = String.format("""
                    tell application "WeChat" to activate
                    delay 0.5
                    tell application "System Events"
                        tell process "WeChat"
                            set frontmost to true
                        end tell
                        delay 0.5
                        -- Cmd+F 打开搜索
                        keystroke "f" using command down
                        delay 0.5
                        -- 清空搜索框并粘贴联系人名
                        keystroke "a" using command down
                        delay 0.1
                        set the clipboard to "%s"
                        keystroke "v" using command down
                        delay 2.5
                        -- 回车选中第一个搜索结果
                        key code 36
                        delay 1.5
                    end tell
                    """, escapedName);
            runAppleScript(searchScript);

            // 步骤2：获取微信窗口位置和大小
            String posScript = """
                    tell application "System Events"
                        tell process "WeChat"
                            set winPos to position of window 1
                            set winSize to size of window 1
                            return ("" & (item 1 of winPos)) & "," & ("" & (item 2 of winPos)) & "," & ("" & (item 1 of winSize)) & "," & ("" & (item 2 of winSize))
                        end tell
                    end tell
                    """;
            String posResult = runAppleScript(posScript).trim();
            String[] parts = posResult.split(",");
            if (parts.length < 4) {
                return "无法获取微信窗口位置: " + posResult;
            }
            int winX = Integer.parseInt(parts[0].trim());
            int winY = Integer.parseInt(parts[1].trim());
            int winW = Integer.parseInt(parts[2].trim());
            int winH = Integer.parseInt(parts[3].trim());

            // 步骤3：Java Robot 点击输入框区域确保焦点
            int clickX = winX + (int) (winW * INPUT_X_RATIO);
            int clickY = winY + (int) (winH * INPUT_Y_RATIO);

            Robot robot = new Robot();
            robot.mouseMove(clickX, clickY);
            Thread.sleep(100);
            robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(50);
            robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
            Thread.sleep(300);

            // 步骤4：粘贴消息并发送
            String sendScript = String.format("""
                    tell application "System Events"
                        set the clipboard to "%s"
                        keystroke "v" using command down
                        delay 0.5
                        key code 36
                    end tell
                    """, escapedMsg);
            runAppleScript(sendScript);

            return "ok";
        } catch (Exception e) {
            return "发送异常: " + e.getMessage();
        }
    }

    /**
     * 执行 AppleScript 脚本并返回输出。
     */
    private static String runAppleScript(String script) throws Exception {
        ProcessBuilder pb = new ProcessBuilder("osascript", "-e", script);
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

    /**
     * 转义 AppleScript 字符串中的特殊字符。
     */
    private static String escapeAppleScript(String input) {
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
