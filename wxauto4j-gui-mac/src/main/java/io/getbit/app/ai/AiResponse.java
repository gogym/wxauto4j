package io.getbit.app.ai;

/**
 * AI 响应封装
 */
public class AiResponse {

    /** 是否成功 */
    private final boolean success;

    /** AI 回复内容 */
    private final String content;

    /** 思维链内容（reasoning_content），可为 null */
    private final String reasoningContent;

    /** 错误信息 */
    private final String error;

    private AiResponse(boolean success, String content, String reasoningContent, String error) {
        this.success = success;
        this.content = content;
        this.reasoningContent = reasoningContent;
        this.error = error;
    }

    /**
     * 创建成功响应
     */
    public static AiResponse ok(String content, String reasoningContent) {
        return new AiResponse(true, content, reasoningContent, null);
    }

    public static AiResponse ok(String content) {
        return new AiResponse(true, content, null, null);
    }

    /**
     * 创建失败响应
     */
    public static AiResponse fail(String error) {
        return new AiResponse(false, null, null, error);
    }

    public boolean isSuccess() { return success; }
    public String getContent() { return content; }
    public String getReasoningContent() { return reasoningContent; }
    public String getError() { return error; }
}
