package io.getbit.app.config;

/**
 * AI 接口配置项
 *
 * <p>对标 SiverWXbot_plus 的 api_configs 数组中的每一项。</p>
 */
public class ApiConfig {

    /** SDK 类型（目前仅支持 "OpenAI SDK"） */
    private String sdk = "OpenAI SDK";

    /** API Key */
    private String key = "";

    /** API Base URL（如 https://api.openai.com/v1） */
    private String url = "";

    /** 模型名称（如 gpt-4o, deepseek-chat） */
    private String model = "";

    public ApiConfig() {}

    public ApiConfig(String sdk, String key, String url, String model) {
        this.sdk = sdk;
        this.key = key;
        this.url = url;
        this.model = model;
    }

    public String getSdk() { return sdk; }
    public void setSdk(String sdk) { this.sdk = sdk; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getUrl() { return url; }
    public void setUrl(String url) { this.url = url; }

    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
}
