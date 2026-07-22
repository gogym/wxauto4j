package io.getbit.app.ai;

import java.util.List;

/**
 * AI 客户端接口
 *
 * <p>定义与 AI 平台交互的标准接口。
 * 当前实现为 {@link OpenAiClient}（兼容所有 OpenAI 格式的 API）。</p>
 */
public interface AiClient {

    /**
     * 发送聊天请求
     *
     * @param systemPrompt 系统提示词（Prompt 内容）
     * @param history      对话历史
     * @param userMessage  用户当前消息
     * @return AI 响应
     */
    AiResponse chat(String systemPrompt, List<MemoryMessage> history, String userMessage);

    /**
     * 测试接口是否正常
     *
     * @param testContent 测试内容
     * @return 是否成功
     */
    boolean test(String testContent);

    /**
     * 获取当前配置的模型名称
     */
    String getModelName();
}
