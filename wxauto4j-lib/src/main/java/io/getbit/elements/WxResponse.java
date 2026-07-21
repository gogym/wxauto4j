package io.getbit.elements;

/**
 * 微信操作执行结果
 *
 * <p>对标 wxautox4 的 WxResponse 类，封装操作执行结果。</p>
 */
public class WxResponse {

    /** 操作是否成功 */
    private final boolean success;

    /** 结果消息/描述 */
    private final String message;

    /** 关联的数据（可选） */
    private final Object data;

    /** 停止信号常量（用于回调中终止操作） */
    public static final String CALLBACK_STOP_SIGN = "stop";

    public WxResponse(boolean success, String message) {
        this(success, message, null);
    }

    public WxResponse(boolean success, String message, Object data) {
        this.success = success;
        this.message = message;
        this.data = data;
    }

    public boolean isSuccess() {
        return success;
    }

    public String getMessage() {
        return message;
    }

    public Object getData() {
        return data;
    }

    /**
     * 创建成功结果
     */
    public static WxResponse ok(String message) {
        return new WxResponse(true, message);
    }

    /**
     * 创建成功结果（带数据）
     */
    public static WxResponse ok(String message, Object data) {
        return new WxResponse(true, message, data);
    }

    /**
     * 创建失败结果
     */
    public static WxResponse fail(String message) {
        return new WxResponse(false, message);
    }

    @Override
    public String toString() {
        return "WxResponse{success=" + success + ", message='" + message + "'}";
    }
}
