package io.getbit.app;

import io.getbit.WeChat;
import io.getbit.elements.Message;

import java.util.List;

/**
 * wxauto4j 运行入口
 *
 * <p>演示微信自动化的基本用法。运行前请确保：</p>
 * <ol>
 *   <li>微信 PC 客户端已启动并登录</li>
 *   <li>当前为 Windows 系统（UIAutomation 仅支持 Windows）</li>
 * </ol>
 *
 * <h3>运行方式：</h3>
 * <pre>
 * # 在项目根目录执行
 * mvn compile exec:java -pl wxauto4j-app
 * </pre>
 */
public class Main {

    public static void main(String[] args) {
        System.out.println("=== wxauto4j 微信自动化 ===");

        try {
            // 1. 创建微信实例（自动定位微信窗口）
            System.out.println("正在初始化微信...");
            WeChat wx = new WeChat();
            System.out.println("微信初始化成功！昵称: " + wx.getNickname());

            // 2. 获取当前聊天消息
            System.out.println("\n--- 获取当前聊天消息 ---");
            List<Message> messages = wx.GetAllMessage();
            for (Message msg : messages) {
                System.out.println(msg);
            }

            // 3. 发送消息示例（取消注释即可使用）
            // wx.SendMsg("Hello from wxauto4j!", "文件传输助手");
            // System.out.println("消息已发送到 文件传输助手");

        } catch (IllegalStateException e) {
            System.err.println("初始化失败: " + e.getMessage());
            System.err.println("请确认微信已启动并登录。");
        } catch (Exception e) {
            System.err.println("运行异常: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
