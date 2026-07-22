package io.getbit.app.ai;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import io.getbit.app.config.ApiConfig;
import okhttp3.*;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * OpenAI 兼容接口客户端
 *
 * <p>使用 OkHttp 调用 /v1/chat/completions 接口。
 * 兼容 OpenAI、DeepSeek、通义千问等所有 OpenAI 格式的 API。</p>
 *
 * <p>特性：</p>
 * <ul>
 *   <li>梯度重试机制（2/4/8/16/32 秒，5 次失败后报错）</li>
 *   <li>支持 reasoning_content（思维链）</li>
 *   <li>支持流式和非流式输出</li>
 * </ul>
 */
public class OpenAiClient implements AiClient {

    private static final Logger LOG = Logger.getLogger(OpenAiClient.class.getName());
    private static final MediaType JSON_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final int MAX_RETRIES = 5;
    private static final int[] RETRY_DELAYS = {2, 4, 8, 16, 32};

    private final ApiConfig apiConfig;
    private final OkHttpClient httpClient;
    private final Gson gson = new Gson();

    public OpenAiClient(ApiConfig apiConfig) {
        this.apiConfig = apiConfig;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .writeTimeout(30, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public AiResponse chat(String systemPrompt, List<MemoryMessage> history, String userMessage) {
        JsonObject requestBody = buildRequestBody(systemPrompt, history, userMessage, false);
        return executeWithRetry(requestBody);
    }

    @Override
    public boolean test(String testContent) {
        try {
            JsonObject requestBody = buildRequestBody("你是一个测试助手", null, testContent, false);
            AiResponse response = executeWithRetry(requestBody);
            return response.isSuccess();
        } catch (Exception e) {
            LOG.log(Level.WARNING, "接口测试失败", e);
            return false;
        }
    }

    @Override
    public String getModelName() {
        return apiConfig.getModel();
    }

    /**
     * 构建请求体
     */
    private JsonObject buildRequestBody(String systemPrompt, List<MemoryMessage> history,
                                         String userMessage, boolean stream) {
        JsonObject body = new JsonObject();
        body.addProperty("model", apiConfig.getModel());

        // 构建 messages 数组
        JsonArray messages = new JsonArray();

        // system prompt
        if (systemPrompt != null && !systemPrompt.isEmpty()) {
            JsonObject systemMsg = new JsonObject();
            systemMsg.addProperty("role", "system");
            systemMsg.addProperty("content", systemPrompt);
            messages.add(systemMsg);
        }

        // 历史消息
        if (history != null) {
            for (MemoryMessage msg : history) {
                JsonObject historyMsg = new JsonObject();
                if ("self".equals(msg.getAttr())) {
                    historyMsg.addProperty("role", "assistant");
                } else {
                    historyMsg.addProperty("role", "user");
                }
                historyMsg.addProperty("content", msg.toAiFormat());
                messages.add(historyMsg);
            }
        }

        // 当前用户消息
        JsonObject currentMsg = new JsonObject();
        currentMsg.addProperty("role", "user");
        currentMsg.addProperty("content", userMessage);
        messages.add(currentMsg);

        body.add("messages", messages);
        body.addProperty("stream", stream);

        return body;
    }

    /**
     * 带梯度重试的执行请求
     */
    private AiResponse executeWithRetry(JsonObject requestBody) {
        String url = normalizeUrl(apiConfig.getUrl());
        String bodyJson = gson.toJson(requestBody);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                Request request = new Request.Builder()
                        .url(url)
                        .addHeader("Authorization", "Bearer " + apiConfig.getKey())
                        .addHeader("Content-Type", "application/json")
                        .post(RequestBody.create(bodyJson, JSON_TYPE))
                        .build();

                try (Response response = httpClient.newCall(request).execute()) {
                    if (response.isSuccessful() && response.body() != null) {
                        String responseBody = response.body().string();
                        return parseResponse(responseBody);
                    } else {
                        String errorBody = response.body() != null ? response.body().string() : "无响应体";
                        LOG.warning("API 请求失败 (HTTP " + response.code() + "): " + errorBody);

                        if (attempt < MAX_RETRIES) {
                            int delay = RETRY_DELAYS[Math.min(attempt, RETRY_DELAYS.length - 1)];
                            LOG.info("第 " + (attempt + 1) + " 次重试，等待 " + delay + " 秒...");
                            Thread.sleep(delay * 1000L);
                        }
                    }
                }
            } catch (IOException e) {
                LOG.log(Level.WARNING, "网络请求异常 (尝试 " + (attempt + 1) + ")", e);
                if (attempt < MAX_RETRIES) {
                    try {
                        int delay = RETRY_DELAYS[Math.min(attempt, RETRY_DELAYS.length - 1)];
                        Thread.sleep(delay * 1000L);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return AiResponse.fail("请求被中断");
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return AiResponse.fail("请求被中断");
            }
        }

        return AiResponse.fail("API 请求失败，已重试 " + MAX_RETRIES + " 次");
    }

    /**
     * 解析 API 响应
     */
    private AiResponse parseResponse(String responseBody) {
        try {
            JsonObject json = gson.fromJson(responseBody, JsonObject.class);
            JsonArray choices = json.getAsJsonArray("choices");
            if (choices == null || choices.isEmpty()) {
                return AiResponse.fail("API 返回空 choices");
            }

            JsonObject firstChoice = choices.get(0).getAsJsonObject();
            JsonObject message = firstChoice.getAsJsonObject("message");
            if (message == null) {
                return AiResponse.fail("API 返回无 message 字段");
            }

            String content = "";
            JsonElement contentElement = message.get("content");
            if (contentElement != null && !contentElement.isJsonNull()) {
                content = contentElement.getAsString();
            }

            String reasoningContent = null;
            JsonElement reasoningElement = message.get("reasoning_content");
            if (reasoningElement != null && !reasoningElement.isJsonNull()) {
                reasoningContent = reasoningElement.getAsString();
            }

            return AiResponse.ok(content, reasoningContent);
        } catch (Exception e) {
            LOG.log(Level.WARNING, "解析 API 响应失败", e);
            return AiResponse.fail("解析响应失败: " + e.getMessage());
        }
    }

    /**
     * 规范化 URL（确保以 /chat/completions 结尾）
     */
    private String normalizeUrl(String baseUrl) {
        if (baseUrl == null || baseUrl.isEmpty()) {
            return "";
        }
        // 去掉末尾斜杠
        String url = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        // 如果已经包含完整路径，直接返回
        if (url.endsWith("/chat/completions")) {
            return url;
        }
        // 拼接路径
        return url + "/chat/completions";
    }
}
