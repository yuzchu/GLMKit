package com.glmkit.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;

import de.robv.android.xposed.XposedBridge;

/**
 * GlmBackend — 通过反射使用目标应用内捕获的 OkHttp 客户端发起 GLM API 请求，
 * 将 OpenAI 兼容请求转换为 GLM chat/completions 格式，解析响应（含 SSE 流式）。
 *
 * 依赖 GlmCapture 提供的认证信息和 OkHttp 客户端实例。
 */
public class GlmBackend implements LocalApiGateway.Backend {

    private static final String TAG = "GLM-Backend";
    private static final String DEFAULT_GLM_MODEL = "glm-4";
    private static final String GLM_API_PATH = "/chat/completions";

    private final GlmCapture capture;
    private volatile String lastError = null;

    public GlmBackend(GlmCapture capture) {
        this.capture = capture;
    }

    // ════════════════════════════════════════════════════════════
    //  Backend 接口实现
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean isReady() {
        return capture.getOkHttpClient() != null && capture.getBestBaseUrl() != null;
    }

    @Override
    public String readinessDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("client=").append(capture.getOkHttpClient() != null ? "yes" : "no");
        sb.append(", baseUrl=").append(capture.getBaseUrl() != null ? "yes" : "no");
        sb.append(", token=").append(capture.getAuthToken() != null ? "yes" : "no");
        sb.append(", apiKey=").append(capture.getApiKey() != null ? "yes" : "no");
        sb.append(", cookie=").append(capture.getCookie() != null ? "yes" : "no");
        if (lastError != null) {
            sb.append(", lastError=").append(lastError);
        }
        return sb.toString();
    }

    @Override
    public String lastError() {
        return lastError;
    }

    @Override
    public String diagnosticInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append("okHttpClient: ").append(capture.getOkHttpClient() != null ? "captured" : "waiting").append("\n");
        sb.append("baseUrl: ").append(capture.getBaseUrl() != null ? capture.getBaseUrl() : "not captured").append("\n");
        sb.append("apiUrl: ").append(capture.getApiUrl() != null ? capture.getApiUrl() : "not captured").append("\n");
        sb.append("resolvedBaseUrl: ").append(capture.getBestBaseUrl()).append("\n");
        sb.append("authToken: ").append(capture.getAuthToken() != null ? "captured (len=" + capture.getAuthToken().length() + ")" : "not captured").append("\n");
        sb.append("apiKey: ").append(capture.getApiKey() != null ? "captured" : "not captured").append("\n");
        sb.append("cookie: ").append(capture.getCookie() != null ? "captured" : "not captured").append("\n");
        sb.append("deviceId: ").append(capture.getDeviceId() != null ? "captured" : "not captured").append("\n");
        if (lastError != null) {
            sb.append("lastError: ").append(lastError).append("\n");
        }
        return sb.toString();
    }

    @Override
    public LocalApiGateway.CompletionResult complete(
            LocalApiGateway.CompletionRequest req,
            LocalApiGateway.DeltaSink sink) throws Exception {

        lastError = null;

        // 构造 GLM API 请求体
        JSONObject glmReq = buildGlmRequestBody(req);
        String bodyStr = glmReq.toString();
        log("→ GLM 请求: model=" + req.model + ", stream=" + req.stream
            + ", msgs=" + req.messages.length() + ", body=" + truncate(bodyStr, 300));

        // 解析 GLM API URL
        String apiUrl = resolveGlmApiUrl();
        log("→ GLM API URL: " + apiUrl);

        // 通过反射调用 OkHttp 发起请求
        Object response;
        try {
            response = executeOkHttpRequest(apiUrl, bodyStr, req.stream);
        } catch (Exception e) {
            lastError = e.getMessage();
            log("✗ OkHttp 请求失败: " + e.getMessage());
            throw new LocalApiGateway.GatewayException(502, "upstream_error",
                "GLM API 请求失败: " + e.getMessage());
        }

        // 检查响应码
        int code;
        try {
            code = getResponseCode(response);
        } catch (Exception e) {
            closeResponseBodyQuietly(response);
            lastError = "getResponseCode failed: " + e.getMessage();
            throw new LocalApiGateway.GatewayException(502, "response_error",
                "读取 GLM 响应码失败: " + e.getMessage());
        }
        log("← GLM 响应码: " + code);

        if (code != 200) {
            String errBody = readResponseBodyString(response);
            lastError = "HTTP " + code + ": " + truncate(errBody, 500);
            log("✗ GLM 错误响应: " + lastError);
            throw new LocalApiGateway.GatewayException(
                mapHttpStatus(code), "glm_error",
                "GLM API 返回错误 " + code + ": " + truncate(errBody, 200));
        }

        // 解析响应
        try {
            LocalApiGateway.CompletionResult result;
            if (req.stream) {
                result = parseStreamResponse(response, req, sink);
            } else {
                result = parseNonStreamResponse(response, req);
            }
            // 成功路径也确保关闭 response body
            closeResponseBodyQuietly(response);
            return result;
        } catch (Exception e) {
            // 确保异常时关闭 response body 防止连接泄漏
            closeResponseBodyQuietly(response);
            throw e;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  请求构造 — OpenAI → GLM
    // ════════════════════════════════════════════════════════════

    private JSONObject buildGlmRequestBody(LocalApiGateway.CompletionRequest req) throws Exception {
        JSONObject payload = new JSONObject();
        String glmModel = mapModel(req.model);
        payload.put("model", glmModel);
        if (!glmModel.equals(req.model)) {
            log("  模型映射: " + req.model + " → " + glmModel);
        }
        payload.put("stream", req.stream);

        // 消息转换 — JSONArray 透传 (已是 OpenAI 格式)
        payload.put("messages", req.messages);

        // 可选参数
        if (req.temperature >= 0) {
            payload.put("temperature", req.temperature);
        }
        if (req.maxTokens > 0) {
            payload.put("max_tokens", req.maxTokens);
        }
        if (req.topP >= 0) {
            payload.put("top_p", req.topP);
        }
        if (req.stop != null && req.stop.length > 0) {
            JSONArray stopArr = new JSONArray();
            for (String s : req.stop) stopArr.put(s);
            payload.put("stop", stopArr);
        }

        // GLM 特有: 启用思考链 (通过 model 名判断)
        if (req.model != null && (req.model.contains("thinking")
                || req.model.contains("reasoner")
                || req.model.startsWith("o1"))) {
            payload.put("thinking", new JSONObject().put("type", "enabled"));
        }

        // 请求 ID
        payload.put("request_id", "glmkit-" + System.currentTimeMillis());

        // 透传 GLM 兼容的额外参数
        if (req.rawRequest != null) {
            // tools / tool_choice (函数调用)
            JSONArray tools = req.rawRequest.optJSONArray("tools");
            if (tools != null) payload.put("tools", tools);
            // tool_choice 可能是 String("auto"/"none") 或 JSONObject
            if (req.rawRequest.has("tool_choice")) {
                Object tc = req.rawRequest.get("tool_choice");
                payload.put("tool_choice", tc);
            }
            // response_format (JSON 模式)
            JSONObject respFormat = req.rawRequest.optJSONObject("response_format");
            if (respFormat != null) payload.put("response_format", respFormat);
            // seed (可复现输出)
            if (req.rawRequest.has("seed")) payload.put("seed", req.rawRequest.get("seed"));
            // user (用户标识)
            String user = req.rawRequest.optString("user", null);
            if (user != null) payload.put("user", user);
            // frequency_penalty / presence_penalty
            if (req.rawRequest.has("frequency_penalty")) {
                payload.put("frequency_penalty", req.rawRequest.get("frequency_penalty"));
            }
            if (req.rawRequest.has("presence_penalty")) {
                payload.put("presence_penalty", req.rawRequest.get("presence_penalty"));
            }
            // logit_bias (token 偏置)
            if (req.rawRequest.has("logit_bias")) {
                payload.put("logit_bias", req.rawRequest.get("logit_bias"));
            }
            // n (生成数量)
            if (req.rawRequest.has("n")) {
                payload.put("n", req.rawRequest.get("n"));
            }
            // stream_options (流式 usage 统计)
            if (req.rawRequest.has("stream_options")) {
                payload.put("stream_options", req.rawRequest.get("stream_options"));
            }
            // logprobs / top_logprobs (对数概率)
            if (req.rawRequest.has("logprobs")) {
                payload.put("logprobs", req.rawRequest.get("logprobs"));
            }
            if (req.rawRequest.has("top_logprobs")) {
                payload.put("top_logprobs", req.rawRequest.get("top_logprobs"));
            }
        }

        return payload;
    }

    private String mapModel(String openaiModel) {
        if (openaiModel == null || openaiModel.isEmpty()) {
            return DEFAULT_GLM_MODEL;
        }
        // 已是 GLM 模型名，直接透传
        if (openaiModel.startsWith("glm-") || openaiModel.startsWith("codegeex-")) {
            return openaiModel;
        }
        // OpenAI 模型名 → GLM 模型名映射
        switch (openaiModel) {
            // OpenAI o1 系列 (推理模型 → GLM + thinking)
            case "o1-preview":
            case "o1":
                return "glm-4-plus";
            case "o1-mini":
                return "glm-4-flash";
            // GPT-4 系列
            case "gpt-4":
            case "gpt-4-1106-preview":
            case "gpt-4-0125-preview":
            case "gpt-4-vision-preview":
                return "glm-4";
            case "gpt-4-turbo":
            case "gpt-4-turbo-preview":
            case "gpt-4-2024-04-09":
                return "glm-4-plus";
            case "gpt-4o":
                return "glm-4-plus";
            case "gpt-4o-mini":
            case "gpt-4-mini":
                return "glm-4-flash";
            // GPT-3.5 系列
            case "gpt-3.5-turbo":
            case "gpt-3.5":
            case "gpt-3.5-turbo-1106":
            case "gpt-3.5-turbo-0125":
                return "glm-4-flash";
            // Claude 系列
            case "claude-3-opus":
            case "claude-3-sonnet":
                return "glm-4-plus";
            case "claude-3-haiku":
            case "claude-3-5-haiku":
                return "glm-4-flash";
            case "claude-3-5-sonnet":
                return "glm-4-plus";
            // Gemini 系列
            case "gemini-pro":
            case "gemini-1.5-pro":
                return "glm-4-plus";
            case "gemini-1.5-flash":
                return "glm-4-flash";
            // DeepSeek 系列
            case "deepseek-chat":
            case "deepseek-coder":
                return "glm-4-flash";
            // Qwen 系列 (阿里通义千问)
            case "qwen-turbo":
            case "qwen-plus":
                return "glm-4-flash";
            case "qwen-max":
            case "qwen-max-longcontext":
                return "glm-4-plus";
            // Yi 系列 (零一万物)
            case "yi-large":
                return "glm-4-plus";
            case "yi-medium":
            case "yi-spark":
                return "glm-4-flash";
            // Moonshot (月之暗面)
            case "moonshot-v1-8k":
            case "moonshot-v1-32k":
                return "glm-4-flash";
            case "moonshot-v1-128k":
                return "glm-4-long";
            // Llama 系列 (Meta)
            case "llama3-70b":
            case "llama-3-70b":
            case "llama3-8b":
            case "llama-3-8b":
                return "glm-4-flash";
            // Mistral 系列
            case "mistral-large":
                return "glm-4-plus";
            case "mistral-7b":
            case "mixtral-8x7b":
                return "glm-4-flash";
            default:
                // 未知 gpt-* 模型 → glm-4
                if (openaiModel.startsWith("gpt-")) return "glm-4";
                if (openaiModel.startsWith("claude-")) return "glm-4-plus";
                if (openaiModel.startsWith("gemini-")) return "glm-4";
                if (openaiModel.startsWith("qwen-")) return "glm-4-flash";
                if (openaiModel.startsWith("yi-")) return "glm-4-flash";
                if (openaiModel.startsWith("moonshot-")) return "glm-4-flash";
                if (openaiModel.startsWith("llama")) return "glm-4-flash";
                if (openaiModel.startsWith("mistral") || openaiModel.startsWith("mixtral")) return "glm-4-flash";
                if (openaiModel.startsWith("deepseek-")) return "glm-4-flash";
                if (openaiModel.startsWith("o1")) return "glm-4-plus";
                if (openaiModel.startsWith("o3")) return "glm-4-plus";
                // 其他：透传（可能是 GLM 新模型）
                return openaiModel;
        }
    }

    private String resolveGlmApiUrl() {
        String base = capture.getBestBaseUrl();
        if (base == null) {
            throw new IllegalStateException("API base URL 未捕获");
        }
        // 去除尾部斜杠
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 如果 base 已包含完整路径，直接使用
        if (base.contains("/chat/completions")) {
            return base;
        }
        // 拼接标准路径
        return base + GLM_API_PATH;
    }

    // ════════════════════════════════════════════════════════════
    //  OkHttp 反射调用
    // ════════════════════════════════════════════════════════════

    private Object executeOkHttpRequest(String url, String body, boolean stream) throws Exception {
        Object client = capture.getOkHttpClient();
        if (client == null) {
            throw new LocalApiGateway.GatewayException(503, "no_client",
                "OkHttp 客户端未捕获");
        }

        ClassLoader cl = client.getClass().getClassLoader();

        // 加载 OkHttp 类
        Class<?> builderClass = cl.loadClass("okhttp3.Request$Builder");
        Class<?> mediaTypeClass = cl.loadClass("okhttp3.MediaType");
        Class<?> requestBodyClass = cl.loadClass("okhttp3.RequestBody");
        Class<?> requestClass = cl.loadClass("okhttp3.Request");

        // 创建 MediaType
        Method parseMethod = mediaTypeClass.getMethod("parse", String.class);
        Object mediaType = parseMethod.invoke(null, "application/json; charset=utf-8");

        // 创建 RequestBody
        Method createBodyMethod = requestBodyClass.getMethod("create",
            mediaTypeClass, String.class);
        Object requestBody = createBodyMethod.invoke(null, mediaType, body);

        // 构建 Request
        Object requestBuilder = builderClass.getDeclaredConstructor().newInstance();
        Method urlMethod = builderClass.getMethod("url", String.class);
        urlMethod.invoke(requestBuilder, url);

        Method postMethod = builderClass.getMethod("post", requestBodyClass);
        postMethod.invoke(requestBuilder, requestBody);

        // 添加认证头
        Method headerMethod = builderClass.getMethod("header", String.class, String.class);
        addAuthHeaders(requestBuilder, headerMethod);

        // Accept 头
        if (stream) {
            headerMethod.invoke(requestBuilder, "Accept", "text/event-stream");
        }

        Method buildMethod = builderClass.getMethod("build");
        Object request = buildMethod.invoke(requestBuilder);

        // 执行请求
        Method newCallMethod = client.getClass().getMethod("newCall", requestClass);
        Object call = newCallMethod.invoke(client, request);

        Method executeMethod = call.getClass().getMethod("execute");
        return executeMethod.invoke(call);
    }

    private void addAuthHeaders(Object builder, Method headerMethod) throws Exception {
        // 优先使用 Authorization token
        String token = capture.getAuthToken();
        if (token != null) {
            if (!token.startsWith("Bearer ")) {
                token = "Bearer " + token;
            }
            headerMethod.invoke(builder, "Authorization", token);
        }

        // API Key
        String apiKey = capture.getApiKey();
        if (apiKey != null) {
            headerMethod.invoke(builder, "x-api-key", apiKey);
            if (token == null) {
                headerMethod.invoke(builder, "Authorization",
                    apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey);
            }
        }

        // Cookie
        String cookie = capture.getCookie();
        if (cookie != null) {
            headerMethod.invoke(builder, "Cookie", cookie);
        }

        // 设备 ID
        String deviceId = capture.getDeviceId();
        if (deviceId != null) {
            headerMethod.invoke(builder, "x-device-id", deviceId);
        }

        // User-Agent
        headerMethod.invoke(builder, "User-Agent", "okhttp/4.12.0");
    }

    // ════════════════════════════════════════════════════════════
    //  响应解析 — 非流式
    // ════════════════════════════════════════════════════════════

    private LocalApiGateway.CompletionResult parseNonStreamResponse(
            Object response, LocalApiGateway.CompletionRequest req) throws Exception {

        String bodyStr = readResponseBodyString(response);
        if (bodyStr == null || bodyStr.isEmpty()) {
            throw new LocalApiGateway.GatewayException(502, "empty_response",
                "GLM API 返回空响应");
        }

        log("← GLM 非流式响应: " + truncate(bodyStr, 500));

        JSONObject resp = new JSONObject(bodyStr);
        JSONArray choices = resp.optJSONArray("choices");
        if (choices == null || choices.length() == 0) {
            throw new LocalApiGateway.GatewayException(502, "no_choices",
                "GLM 响应无 choices");
        }

        JSONObject choice = choices.getJSONObject(0);
        JSONObject message = choice.optJSONObject("message");
        String content = message != null ? message.optString("content", "") : "";
        String reasoning = message != null ? message.optString("reasoning_content", null) : null;
        String finishReason = choice.optString("finish_reason", "stop");
        JSONArray toolCalls = message != null ? message.optJSONArray("tool_calls") : null;

        // usage
        int promptTokens = 0, completionTokens = 0;
        JSONObject usage = resp.optJSONObject("usage");
        if (usage != null) {
            promptTokens = usage.optInt("prompt_tokens", 0);
            completionTokens = usage.optInt("completion_tokens", 0);
        }

        return new LocalApiGateway.CompletionResult(
            content, reasoning, finishReason, promptTokens, completionTokens, toolCalls);
    }

    // ════════════════════════════════════════════════════════════
    //  响应解析 — 流式 SSE
    // ════════════════════════════════════════════════════════════

    private LocalApiGateway.CompletionResult parseStreamResponse(
            Object response, LocalApiGateway.CompletionRequest req,
            LocalApiGateway.DeltaSink sink) throws Exception {

        InputStream is = readResponseBodyStream(response);
        if (is == null) {
            throw new LocalApiGateway.GatewayException(502, "no_stream",
                "GLM 流式响应无 body stream");
        }

        BufferedReader reader = new BufferedReader(
            new InputStreamReader(is, StandardCharsets.UTF_8));

        StringBuilder fullContent = new StringBuilder();
        StringBuilder fullReasoning = new StringBuilder();
        String finishReason = "stop";
        int promptTokens = 0, completionTokens = 0;

        String line;
        while ((line = reader.readLine()) != null) {
            if (sink.isCancelled()) {
                log("客户端取消流式连接");
                break;
            }

            // SSE 格式: data: {...}
            if (!line.startsWith("data:")) continue;
            String data = line.substring(5).trim();
            if (data.isEmpty()) continue;
            if ("[DONE]".equals(data)) break;

            try {
                JSONObject chunk = new JSONObject(data);
                JSONArray choices = chunk.optJSONArray("choices");
                if (choices == null || choices.length() == 0) continue;

                JSONObject choice = choices.getJSONObject(0);
                JSONObject delta = choice.optJSONObject("delta");
                if (delta == null) continue;

                // 内容增量
                String contentDelta = delta.optString("content", null);
                if (contentDelta != null && !contentDelta.isEmpty()) {
                    fullContent.append(contentDelta);
                    sink.onText(contentDelta);
                }

                // 思考链增量 (GLM 特有)
                String reasoningDelta = delta.optString("reasoning_content", null);
                if (reasoningDelta != null && !reasoningDelta.isEmpty()) {
                    fullReasoning.append(reasoningDelta);
                    sink.onReasoning(reasoningDelta);
                }

                // 函数调用增量
                JSONArray toolCallsDelta = delta.optJSONArray("tool_calls");
                if (toolCallsDelta != null && toolCallsDelta.length() > 0) {
                    sink.onToolCalls(toolCallsDelta.toString());
                }

                // finish_reason
                String fr = choice.optString("finish_reason", null);
                if (fr != null && !fr.isEmpty() && !fr.equals("null")) {
                    finishReason = fr;
                }

                // usage (可能在最后一个 chunk)
                JSONObject usage = chunk.optJSONObject("usage");
                if (usage != null) {
                    promptTokens = usage.optInt("prompt_tokens", promptTokens);
                    completionTokens = usage.optInt("completion_tokens", completionTokens);
                }

            } catch (Exception e) {
                log("解析 SSE chunk 异常: " + e.getMessage() + " line=" + truncate(data, 200));
            }
        }

        try { reader.close(); } catch (Throwable ignored) {}

        log("← GLM 流式完成: content=" + fullContent.length()
            + " chars, reasoning=" + fullReasoning.length() + " chars");

        return new LocalApiGateway.CompletionResult(
            fullContent.toString(),
            fullReasoning.length() > 0 ? fullReasoning.toString() : null,
            finishReason, promptTokens, completionTokens);
    }

    // ════════════════════════════════════════════════════════════
    //  OkHttp 响应读取工具
    // ════════════════════════════════════════════════════════════

    private int getResponseCode(Object response) throws Exception {
        Method codeMethod = response.getClass().getMethod("code");
        return (int) codeMethod.invoke(response);
    }

    private String readResponseBodyString(Object response) throws Exception {
        Method bodyMethod = response.getClass().getMethod("body");
        Object body = bodyMethod.invoke(response);
        if (body == null) return null;
        Method stringMethod = body.getClass().getMethod("string");
        return (String) stringMethod.invoke(body);
    }

    private InputStream readResponseBodyStream(Object response) throws Exception {
        Method bodyMethod = response.getClass().getMethod("body");
        Object body = bodyMethod.invoke(response);
        if (body == null) return null;
        Method byteStreamMethod = body.getClass().getMethod("byteStream");
        return (InputStream) byteStreamMethod.invoke(body);
    }

    private void closeResponseBodyQuietly(Object response) {
        try {
            Method bodyMethod = response.getClass().getMethod("body");
            Object body = bodyMethod.invoke(response);
            if (body != null) {
                Method closeMethod = body.getClass().getMethod("close");
                closeMethod.invoke(body);
            }
        } catch (Throwable ignored) {}
    }

    // ════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════

    private int mapHttpStatus(int code) {
        if (code == 401 || code == 403) return 401;
        if (code == 429) return 429;
        if (code >= 400 && code < 500) return 400;
        return 502;
    }

    private String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private void log(String msg) {
        try {
            XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {}
    }
}
