package com.glmkit.probe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;

import android.util.Log;

/**
 * GlmBackend — 通过反射使用目标应用内捕获的 OkHttp 客户端发起 GLM API 请求，
 * 将 OpenAI 兼容请求转换为 GLM chat/completions 格式，解析响应（含 SSE 流式）。
 *
 * 依赖 GlmCapture 提供的认证信息和 OkHttp 客户端实例。
 */
public class GlmBackend implements LocalApiGateway.Backend {

    private static final String TAG = "GLM-Backend";
    private static final String DEFAULT_GLM_MODEL = "glm-4";
    private static final String GLM_API_PATH = "/backend-api/assistant/stream";

    private final GlmCapture capture;
    private volatile String lastError = null;
    private volatile String lastConversationId = null; // v1.0.68: 从 SSE 响应捕获

    public GlmBackend(GlmCapture capture) {
        this.capture = capture;
    }

    // ════════════════════════════════════════════════════════════
    //  Backend 接口实现
    // ════════════════════════════════════════════════════════════

    @Override
    public boolean isReady() {
        // v1.0.49 Deekseep 方案：OkHttpClient 捕获即就绪（auth 由 client 拦截器处理）
        if (capture.getOkHttpClient() != null) return true;
        // 兜底：auth 捕获即可用 HttpURLConnection 发请求
        return capture.getBestAuth() != null;
    }

    /** 从共享文件重载 auth（模块可能刚捕获到 auth） */
    public void reloadAuth() {
        if (!isReady()) {
            capture.loadFromSharedFile();
        }
    }

    @Override
    public String readinessDetail() {
        StringBuilder sb = new StringBuilder();
        sb.append("client=").append(capture.getOkHttpClient() != null ?
            capture.getOkHttpClient().getClass().getName() : "no(fallback)");
        sb.append(", baseUrl=").append(capture.getBestBaseUrl() != null ? "yes" : "no");
        sb.append(", auth=").append(capture.getBestAuth() != null ? "yes" : "no");
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
        sb.append("capturedModel: ").append(capture.getCapturedModel() != null ? capture.getCapturedModel() : "not captured").append("\n");
        if (lastError != null) {
            sb.append("lastError: ").append(lastError).append("\n");
        }
        return sb.toString();
    }

    @Override
    public String getCapturedModel() {
        return capture.getCapturedModel();
    }

    @Override
    public LocalApiGateway.CompletionResult complete(
            LocalApiGateway.CompletionRequest req,
            LocalApiGateway.DeltaSink sink) throws Exception {

        lastError = null;

        // v1.0.41: 若 auth 未就绪，尝试从共享文件加载
        if (!isReady()) {
            log("auth 未就绪，尝试从共享文件加载...");
            boolean loaded = capture.loadFromSharedFile();
            log("从共享文件加载 auth: " + (loaded ? "成功" : "失败"));
        }

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
        } catch (LocalApiGateway.GatewayException ge) {
            lastError = ge.getMessage();
            log("✗ OkHttp 请求失败: " + ge.getMessage());
            throw ge; // 保留原始状态码
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
        // v1.0.63: GLM API 总是返回 SSE 格式，即使 stream=false
        try {
            LocalApiGateway.CompletionResult result;
            if (req.stream) {
                result = parseStreamResponse(response, req, sink);
            } else {
                // 非流式请求也用流式解析，用 no-op sink
                result = parseStreamResponse(response, req, new LocalApiGateway.DeltaSink() {
                    @Override public boolean onText(String delta) { return true; }
                    @Override public boolean onReasoning(String delta) { return true; }
                    @Override public boolean isCancelled() { return false; }
                });
            }
            // 成功路径也确保关闭 response body
            closeResponseBodyQuietly(response);

            // v1.0.68: 自动删除会话
            tryAutoDeleteConversation();

            return result;
        } catch (Exception e) {
            // 确保异常时关闭 response body 防止连接泄漏
            closeResponseBodyQuietly(response);
            throw e;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.68: 会话自动删除
    // ════════════════════════════════════════════════════════════

    /** 检查设置并在开启时删除会话 */
    private void tryAutoDeleteConversation() {
        String convId = lastConversationId;
        if (convId == null || convId.isEmpty()) {
            log("自动删除: 无 conversation_id，跳过");
            return;
        }
        // 读取设置（通过 LocalApiGateway 静态方法）
        boolean autoDelete = LocalApiGateway.isAutoDeleteConversation();
        if (!autoDelete) {
            log("自动删除: 开关关闭，跳过 (convId=" + truncate(convId, 20) + ")");
            return;
        }
        log("自动删除: 开关开启，删除会话 " + truncate(convId, 20) + "...");
        deleteConversation(convId);
    }

    /** 调用 GLM API 删除指定会话 */
    private void deleteConversation(String conversationId) {
        try {
            // 构造删除 URL: /backend-api/assistant/conversation/{id}
            String base = capture.getBestBaseUrl();
            if (base == null) {
                base = "https://chatglm.cn/chatglm";
            }
            if (base.endsWith("/")) {
                base = base.substring(0, base.length() - 1);
            }
            // 去掉可能的 /backend-api/assistant/stream 后缀
            int apiIdx = base.indexOf("/backend-api/");
            if (apiIdx > 0) {
                base = base.substring(0, apiIdx);
            }
            String deleteUrl = base + "/backend-api/assistant/conversation/" + conversationId;
            log("删除会话 URL: " + deleteUrl);

            Object client = capture.getOkHttpClient();
            if (client == null) {
                log("删除会话: OkHttpClient 为 null，跳过");
                return;
            }

            String clientClassName = client.getClass().getName();
            if (clientClassName.equals("okhttp3.OkHttpClient")
                    || clientClassName.equals("com.squareup.okhttp.OkHttpClient")) {
                executeDeleteStandard(client, deleteUrl);
            } else {
                executeDeleteObfuscated(client, deleteUrl);
            }
        } catch (Throwable t) {
            log("删除会话异常: " + t.getMessage());
        }
    }

    /** 标准 OkHttp DELETE 请求 */
    private void executeDeleteStandard(Object client, String url) throws Exception {
        ClassLoader cl = client.getClass().getClassLoader();
        Class<?> builderClass = cl.loadClass("okhttp3.Request$Builder");
        Class<?> requestClass = cl.loadClass("okhttp3.Request");

        Object requestBuilder = builderClass.getDeclaredConstructor().newInstance();
        Method urlMethod = builderClass.getMethod("url", String.class);
        urlMethod.invoke(requestBuilder, url);

        // DELETE 方法 (无 body)
        Method deleteMethod = builderClass.getMethod("delete");
        deleteMethod.invoke(requestBuilder);

        // 添加认证头
        Method headerMethod = builderClass.getMethod("header", String.class, String.class);
        addAuthHeaders(requestBuilder, headerMethod);

        Method buildMethod = builderClass.getMethod("build");
        Object request = buildMethod.invoke(requestBuilder);

        Method newCallMethod = client.getClass().getMethod("newCall", requestClass);
        Object call = newCallMethod.invoke(client, request);
        Method executeMethod = call.getClass().getMethod("execute");
        Object response = executeMethod.invoke(call);

        int code = getResponseCode(response);
        log("删除会话响应码: " + code);
        closeResponseBodyQuietly(response);
    }

    /** 混淆 OkHttp DELETE 请求 */
    private void executeDeleteObfuscated(Object client, String url) throws Exception {
        ClassLoader cl = client.getClass().getClassLoader();
        // 混淆类名: Request$a → Builder, url→j/g, delete→d, build→b, newCall→b
        Class<?> builderClass = cl.loadClass("okhttp3.Request$a");
        Class<?> requestClass = cl.loadClass("okhttp3.Request");

        Object requestBuilder = builderClass.getDeclaredConstructor().newInstance();

        // url 方法
        Method urlMethod = null;
        for (Method m : builderClass.getMethods()) {
            if (m.getName().equals("j") || m.getName().equals("g")
                    || (m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType() == builderClass)) {
                if (m.getName().length() == 1) { urlMethod = m; break; }
            }
        }
        if (urlMethod == null) {
            throw new RuntimeException("找不到混淆 url 方法");
        }
        urlMethod.invoke(requestBuilder, url);

        // delete 方法 (无参数)
        Method deleteMethod = null;
        for (Method m : builderClass.getMethods()) {
            if (m.getParameterTypes().length == 0 && m.getReturnType() == builderClass
                    && !m.getName().equals("b") && !m.getName().equals("toString")) {
                deleteMethod = m;
                break;
            }
        }
        if (deleteMethod == null) {
            // 回退: 用 method("DELETE", null)
            Method methodMethod = null;
            for (Method m : builderClass.getMethods()) {
                if (m.getParameterTypes().length == 2
                        && m.getParameterTypes()[0] == String.class
                        && m.getReturnType() == builderClass) {
                    methodMethod = m;
                    break;
                }
            }
            if (methodMethod != null) {
                methodMethod.invoke(requestBuilder, "DELETE", null);
            } else {
                throw new RuntimeException("找不到混淆 delete/method 方法");
            }
        } else {
            deleteMethod.invoke(requestBuilder);
        }

        // 添加认证头
        addAuthHeadersObfuscated(requestBuilder);

        // build
        Method buildMethod = builderClass.getMethod("b");
        Object request = buildMethod.invoke(requestBuilder);

        // newCall
        Method newCallMethod = client.getClass().getMethod("b", requestClass);
        Object call = newCallMethod.invoke(client, request);
        Method executeMethod = call.getClass().getMethod("execute");
        Object response = executeMethod.invoke(call);

        int code = getResponseCode(response);
        log("删除会话响应码(混淆): " + code);
        closeResponseBodyQuietly(response);
    }

    /** 混淆版添加认证头 */
    private void addAuthHeadersObfuscated(Object builder) throws Exception {
        // 尝试找到 header 方法 (String, String) → Builder
        Method headerMethod = null;
        for (Method m : builder.getClass().getMethods()) {
            if (m.getParameterTypes().length == 2
                    && m.getParameterTypes()[0] == String.class
                    && m.getParameterTypes()[1] == String.class
                    && m.getReturnType() == builder.getClass()) {
                if (m.getName().length() == 1) { headerMethod = m; break; }
            }
        }
        if (headerMethod == null) {
            log("混淆 header 方法未找到，跳过认证头");
            return;
        }
        addAuthHeaders(builder, headerMethod);
    }

    // ════════════════════════════════════════════════════════════
    //  请求构造 — OpenAI → GLM
    // ════════════════════════════════════════════════════════════

    private JSONObject buildGlmRequestBody(LocalApiGateway.CompletionRequest req) throws Exception {
        JSONObject payload = new JSONObject();

        // v1.0.66: GLM API 格式 — assistant_id + meta_data.chat_mode
        // 模型 ID 格式: "assistant_id:suffix"
        // suffix → chat_mode 映射: "thinking"→"zero", "fast"→"", "deep_research"→"deep_research"
        String capturedModel = capture.getCapturedModel();
        String assistantId = "65940acff94777010aa6b796"; // 默认
        String suffix = "fast"; // 默认后缀

        // 优先从请求的 model 字段解析（用户选择的模型）
        if (req.model != null && !req.model.isEmpty()) {
            int colonIdx = req.model.indexOf(':');
            if (colonIdx > 0) {
                assistantId = req.model.substring(0, colonIdx);
                suffix = req.model.substring(colonIdx + 1);
            } else if (req.model.length() >= 20) {
                // 纯 assistant_id 无后缀
                assistantId = req.model;
            }
        }

        // 如果请求没指定，回退到捕获的模型
        if ((suffix == null || suffix.isEmpty()) && capturedModel != null && !capturedModel.isEmpty()) {
            int colonIdx = capturedModel.indexOf(':');
            if (colonIdx > 0) {
                assistantId = capturedModel.substring(0, colonIdx);
                suffix = capturedModel.substring(colonIdx + 1);
            } else {
                assistantId = capturedModel;
            }
        }

        // 后缀 → 真实 chat_mode 值
        String chatMode = suffixToChatMode(suffix);

        payload.put("assistant_id", assistantId);
        payload.put("conversation_id", "");
        payload.put("project_id", "");
        payload.put("chat_type", "user_chat");

        // 消息转换 — 将 OpenAI messages 转为 GLM 格式
        // GLM 格式: [{"role": "user", "content": [{"type": "text", "text": "..."}]}]
        // 参考项目将所有消息合并为一个 prompt
        JSONArray glmMessages = convertMessagesToGlm(req.messages);
        payload.put("messages", glmMessages);

        // meta_data
        JSONObject metaData = new JSONObject();
        metaData.put("channel", "");
        metaData.put("chat_mode", chatMode);
        metaData.put("draft_id", "");
        metaData.put("if_plus_model", true);
        metaData.put("input_question_type", "xxxx");
        metaData.put("is_networking", false);
        metaData.put("is_test", false);
        metaData.put("platform", "pc");
        metaData.put("quote_log_id", "");
        JSONObject cogview = new JSONObject();
        cogview.put("rm_label_watermark", false);
        metaData.put("cogview", cogview);
        payload.put("meta_data", metaData);

        log("  GLM 请求体: assistant_id=" + assistantId + " chat_mode=" + chatMode
            + " msgs=" + glmMessages.length());

        return payload;
    }

    /** v1.0.66: 用户友好后缀 → 真实 chat_mode 值 */
    private String suffixToChatMode(String suffix) {
        if (suffix == null || suffix.isEmpty()) return "";
        switch (suffix) {
            case "thinking":      return "zero";
            case "fast":          return "";
            case "deep_research": return "deep_research";
            case "zero":          return "zero"; // 兼容直接用 chat_mode 值
            default:              return suffix; // 未知值原样传
        }
    }

    /** v1.0.63: 将 OpenAI messages 转为 GLM 格式 */
    private JSONArray convertMessagesToGlm(JSONArray openaiMessages) throws Exception {
        // 参考项目方案: 将所有消息合并为一个 prompt，用角色前缀
        StringBuilder prompt = new StringBuilder();
        for (int i = 0; i < openaiMessages.length(); i++) {
            JSONObject msg = openaiMessages.getJSONObject(i);
            String role = msg.optString("role", "user");
            String content = msg.optString("content", "");

            // 处理 content 可能是数组的情况
            if (content.isEmpty() || content.equals("null")) {
                Object contentObj = msg.opt("content");
                if (contentObj instanceof JSONArray) {
                    JSONArray arr = (JSONArray) contentObj;
                    for (int j = 0; j < arr.length(); j++) {
                        JSONObject part = arr.optJSONObject(j);
                        if (part != null) {
                            String text = part.optString("text", "");
                            if (!text.isEmpty()) content += text;
                        }
                    }
                }
            }

            if (content.isEmpty()) continue;

            // 角色前缀
            String title;
            switch (role) {
                case "system": title = "System"; break;
                case "assistant": title = "Assistant"; break;
                case "user": title = "User"; break;
                case "tool": title = "User"; break;
                default: title = "User"; break;
            }

            if (prompt.length() > 0) prompt.append("\n\n");
            prompt.append(title).append(": ").append(content);
        }
        prompt.append("\n\nAssistant: ");

        // GLM 格式: 单个 user 消息
        JSONArray glmMessages = new JSONArray();
        JSONObject userMsg = new JSONObject();
        userMsg.put("role", "user");
        JSONArray contentArr = new JSONArray();
        JSONObject textContent = new JSONObject();
        textContent.put("type", "text");
        textContent.put("text", prompt.toString());
        contentArr.put(textContent);
        userMsg.put("content", contentArr);
        glmMessages.put(userMsg);

        return glmMessages;
    }

    private String mapModel(String openaiModel) {
        // v1.0.53: 优先使用从 APP 拦截的真实模型 ID
        String capturedModel = capture.getCapturedModel();

        if (openaiModel == null || openaiModel.isEmpty()) {
            // 客户端未指定模型 → 用拦截到的真实模型，否则默认
            if (capturedModel != null) {
                log("  模型选择: 使用拦截到的真实模型 → " + capturedModel);
                return capturedModel;
            }
            return DEFAULT_GLM_MODEL;
        }
        // 大小写不敏感匹配
        String model = openaiModel.toLowerCase();
        // 已是 GLM 模型名，直接透传 (小写化，GLM API 期望小写)
        if (model.startsWith("glm-") || model.startsWith("codegeex-")) {
            return model;
        }
        // v1.0.53: 客户端发的是 OpenAI/其他模型名，但我们有真实拦截的模型 → 直接用
        if (capturedModel != null) {
            log("  模型映射: " + openaiModel + " → " + capturedModel + " (拦截到的真实模型)");
            return capturedModel;
        }
        // 没有拦截到模型，回退到猜测映射
        // OpenAI 模型名 → GLM 模型名映射
        switch (model) {
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
                if (model.startsWith("gpt-")) return "glm-4";
                if (model.startsWith("claude-")) return "glm-4-plus";
                if (model.startsWith("gemini-")) return "glm-4";
                if (model.startsWith("qwen-")) return "glm-4-flash";
                if (model.startsWith("yi-")) return "glm-4-flash";
                if (model.startsWith("moonshot-")) return "glm-4-flash";
                if (model.startsWith("llama")) return "glm-4-flash";
                if (model.startsWith("mistral") || model.startsWith("mixtral")) return "glm-4-flash";
                if (model.startsWith("deepseek-")) return "glm-4-flash";
                if (model.startsWith("o1")) return "glm-4-plus";
                if (model.startsWith("o3")) return "glm-4-plus";
                // 其他：透传小写化（可能是 GLM 新模型）
                return model;
        }
    }

    private String resolveGlmApiUrl() {
        String base = capture.getBestBaseUrl();
        if (base == null) {
            // v1.0.50: 兜底 — 使用已知 GLM API URL
            base = "https://chatglm.cn/chatglm";
            log("URL 未捕获，使用默认 GLM API URL: " + base);
        }
        // 去除尾部斜杠
        if (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        // 如果 base 已包含完整路径，直接使用
        if (base.contains("/backend-api/assistant/stream")) {
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
            // 兜底：使用 HttpURLConnection
            return executeWithHttpURLConnection(url, body, stream);
        }

        // 检查是否是标准 OkHttp 类（非混淆）
        String clientClassName = client.getClass().getName();
        if (!clientClassName.equals("okhttp3.OkHttpClient")
                && !clientClassName.equals("com.squareup.okhttp.OkHttpClient")) {
            // v1.0.49: 混淆类 — 使用已知混淆类名构造原生请求 (Deekseep 方案)
            // v1.0.51: 同时添加捕获的 auth 头作为兜底
            try {
                return executeWithObfuscatedOkHttp(client, url, body, stream);
            } catch (Throwable t) {
                lastError = "obfuscated OkHttp failed: " + t.getMessage();
                log("混淆 OkHttp 请求失败，回退 HttpURLConnection: " + t.getMessage());
                return executeWithHttpURLConnection(url, body, stream);
            }
        }

        try {
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
        } catch (Throwable t) {
            // OkHttp 反射失败，兜底用 HttpURLConnection
            log("OkHttp 反射调用失败，回退到 HttpURLConnection: " + t.getMessage());
            return executeWithHttpURLConnection(url, body, stream);
        }
    }

    /**
     * v1.0.49: 使用混淆后的 OkHttp 类构造和执行请求 (Deekseep 方案)。
     *
     * 智谱清言 v3.7.0 OkHttp 混淆映射:
     * - nu.OkHttpClient → okhttp3.OkHttpClient, newCall→b
     * - nu.Request$a → Request.Builder, url→j/g, method→e, build→b
     * - nu.z → RequestBody, create(MediaType,String)→create(nu.w,String)
     * - nu.w → MediaType, parse(String)→e(String)
     * - nu.Call.execute() → okhttp3.Response (Response 类名未混淆!)
     * - nu.a0 → ResponseBody, string()/byteStream()/close() 方法名未混淆
     *
     * 关键：不添加 auth 头！OkHttpClient 的拦截器自动处理认证。
     */
    private Object executeWithObfuscatedOkHttp(Object client, String url, String body, boolean stream) throws Exception {
        ClassLoader cl = client.getClass().getClassLoader();

        // 1. 创建 MediaType: nu.w.e("application/json; charset=utf-8")
        Class<?> mediaTypeClass = cl.loadClass("nu.w");
        Object mediaType;
        try {
            Method parseMethod = mediaTypeClass.getMethod("e", String.class);
            mediaType = parseMethod.invoke(null, "application/json; charset=utf-8");
        } catch (NoSuchMethodException nsme) {
            Method parseMethod = mediaTypeClass.getMethod("g", String.class);
            mediaType = parseMethod.invoke(null, "application/json; charset=utf-8");
        }

        // 2. 创建 RequestBody: nu.z.create(mediaType, bodyString)
        // v1.0.57: 标记网关自身请求，防止 hookRequestBodyCreate 拦截到网关自己的 model
        Class<?> requestBodyClass = cl.loadClass("nu.z");
        Method createMethod = requestBodyClass.getMethod("create", mediaTypeClass, String.class);
        GlmCapture.markGatewayRequest();
        Object requestBody;
        try {
            requestBody = createMethod.invoke(null, mediaType, body);
        } finally {
            GlmCapture.unmarkGatewayRequest();
        }

        // 3. 构建 Request: nu.Request$a (Builder)
        Class<?> builderClass = cl.loadClass("nu.Request$a");
        Object builder = builderClass.getDeclaredConstructor().newInstance();

        // 3a. 设置 URL: builder.j(url) 或 builder.g(url)
        try {
            Method urlMethod = builderClass.getMethod("j", String.class);
            urlMethod.invoke(builder, url);
        } catch (NoSuchMethodException nsme) {
            Method urlMethod = builderClass.getMethod("g", String.class);
            urlMethod.invoke(builder, url);
        }

        // 3b. 设置 HTTP 方法和请求体: builder.e("POST", requestBody)
        Method methodMethod = builderClass.getMethod("e", String.class, requestBodyClass);
        methodMethod.invoke(builder, "POST", requestBody);

        // 3c. Accept 头 (流式)
        Method addHeaderMethod = builderClass.getMethod("a", String.class, String.class);
        if (stream) {
            try {
                addHeaderMethod.invoke(builder, "Accept", "text/event-stream");
            } catch (Throwable ignored) {}
        }

        // 3d. v1.0.51: 添加 auth 头 — 兜底 (拦截器可能不加 auth)
        String authToken = capture.getAuthToken();
        String apiKey = capture.getApiKey();
        String cookie = capture.getCookie();
        if (authToken != null) {
            String token = authToken.startsWith("Bearer ") ? authToken : "Bearer " + authToken;
            addHeaderMethod.invoke(builder, "Authorization", token);
            log("添加 Authorization 头 (混淆 OkHttp): " + token.substring(0, Math.min(25, token.length())) + "...");
        } else if (apiKey != null) {
            addHeaderMethod.invoke(builder, "Authorization",
                apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey);
            addHeaderMethod.invoke(builder, "x-api-key", apiKey);
            log("添加 x-api-key 头 (混淆 OkHttp)");
        }
        if (cookie != null) {
            addHeaderMethod.invoke(builder, "Cookie", cookie);
            log("添加 Cookie 头 (混淆 OkHttp)");
        }
        if (authToken == null && apiKey == null && cookie == null) {
            log("⚠ 无 auth 头可用 — 依赖拦截器自动添加");
        }

        // 3e. 构建: builder.b()
        Method buildMethod = builderClass.getMethod("b");
        Object request = buildMethod.invoke(builder);

        // 4. 执行: client.b(request) → Call, then call.execute() → Response
        Class<?> requestClass = cl.loadClass("nu.Request");
        Method newCallMethod = client.getClass().getMethod("b", requestClass);
        Object call = newCallMethod.invoke(client, request);

        // execute() 方法名未混淆
        Method executeMethod = call.getClass().getMethod("execute");
        // v1.0.57: 标记网关自身请求，防止 captureAuthFromResponse 从网关响应捕获 auth
        GlmCapture.markGatewayRequest();
        Object response;
        try {
            response = executeMethod.invoke(call);
        } finally {
            GlmCapture.unmarkGatewayRequest();
        }

        // v1.0.51: 记录响应码
        try {
            int respCode = getResponseCode(response);
            log("✓ 混淆 OkHttp 请求成功 (Deekseep) code=" + respCode + " url=" + url);
        } catch (Throwable ignored) {
            log("✓ 混淆 OkHttp 请求成功 (Deekseep 方案)");
        }
        return response;
    }

    /** HttpURLConnection 兜底请求方法 */
    private Object executeWithHttpURLConnection(String url, String body, boolean stream) throws Exception {
        java.net.URL urlObj = new java.net.URL(url);
        HttpURLConnection conn = (HttpURLConnection) urlObj.openConnection();
        conn.setRequestMethod("POST");
        conn.setDoOutput(true);
        conn.setConnectTimeout(30_000);
        conn.setReadTimeout(120_000);

        // v1.0.63: GLM API 需要签名 + 浏览器头
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("Accept", "text/event-stream");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("App-Name", "chatglm");
        conn.setRequestProperty("Cache-Control", "no-cache");
        conn.setRequestProperty("Origin", "https://chatglm.cn");
        conn.setRequestProperty("Pragma", "no-cache");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
        conn.setRequestProperty("X-App-Fr", "browser_extension");
        conn.setRequestProperty("X-App-Platform", "pc");
        conn.setRequestProperty("X-App-Version", "0.0.1");
        conn.setRequestProperty("X-Lang", "zh");

        // 认证头
        String token = capture.getAuthToken();
        if (token != null) {
            if (!token.startsWith("Bearer ")) token = "Bearer " + token;
            conn.setRequestProperty("Authorization", token);
        }
        String apiKey = capture.getApiKey();
        if (apiKey != null && token == null) {
            conn.setRequestProperty("Authorization",
                apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey);
        }
        String cookie = capture.getCookie();
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }

        // 签名
        String SIGN_SECRET = "8a1317a7468aa3ad86e997d08f3f31cb";
        long nowMs = System.currentTimeMillis();
        String nowStr = String.valueOf(nowMs);
        int[] digits = new int[nowStr.length()];
        int digitSum = 0;
        for (int i = 0; i < nowStr.length(); i++) {
            digits[i] = nowStr.charAt(i) - '0';
            digitSum += digits[i];
        }
        int checksum = (digitSum - digits[nowStr.length() - 2]) % 10;
        if (checksum < 0) checksum += 10;
        String timestamp = nowStr.substring(0, nowStr.length() - 2) + checksum + nowStr.charAt(nowStr.length() - 1);
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String signStr = md5Hex(timestamp + "-" + nonce + "-" + SIGN_SECRET);

        conn.setRequestProperty("X-Sign", signStr);
        conn.setRequestProperty("X-Timestamp", timestamp);
        conn.setRequestProperty("X-Nonce", nonce);
        conn.setRequestProperty("X-Request-Id", java.util.UUID.randomUUID().toString().replace("-", ""));

        String deviceId = capture.getDeviceId();
        conn.setRequestProperty("X-Device-Id", deviceId != null ? deviceId : java.util.UUID.randomUUID().toString().replace("-", ""));

        // 写入请求体
        java.io.OutputStream os = conn.getOutputStream();
        os.write(body.getBytes("UTF-8"));
        os.flush();
        os.close();

        int code = conn.getResponseCode();
        java.io.InputStream is = (code >= 200 && code < 300) ? conn.getInputStream() : conn.getErrorStream();
        return new ResponseWrapper(code, is, conn);
    }

    /** 响应包装类 — 兼容 OkHttp Response 反射调用 */
    public static class ResponseWrapper {
        private final int code;
        private final ResponseBodyWrapper body;

        public ResponseWrapper(int code, java.io.InputStream is, HttpURLConnection conn) {
            this.code = code;
            this.body = new ResponseBodyWrapper(is, conn);
        }

        public int code() { return code; }
        public ResponseBodyWrapper body() { return body; }
    }

    public static class ResponseBodyWrapper {
        private final java.io.InputStream is;
        private final HttpURLConnection conn;

        public ResponseBodyWrapper(java.io.InputStream is, HttpURLConnection conn) {
            this.is = is;
            this.conn = conn;
        }

        public String string() throws Exception {
            if (is == null) return null;
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
            return new String(baos.toByteArray(), "UTF-8");
        }

        public java.io.InputStream byteStream() { return is; }

        public void close() {
            try { if (is != null) is.close(); } catch (Throwable ignored) {}
            try { if (conn != null) conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    private void addAuthHeaders(Object builder, Method headerMethod) throws Exception {
        // v1.0.63: GLM API 需要签名 + 浏览器头

        // Authorization token
        String token = capture.getAuthToken();
        if (token != null) {
            if (!token.startsWith("Bearer ")) {
                token = "Bearer " + token;
            }
            headerMethod.invoke(builder, "Authorization", token);
        }

        // API Key (fallback)
        String apiKey = capture.getApiKey();
        if (apiKey != null && token == null) {
            headerMethod.invoke(builder, "Authorization",
                apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey);
        }

        // Cookie
        String cookie = capture.getCookie();
        if (cookie != null) {
            headerMethod.invoke(builder, "Cookie", cookie);
        }

        // 签名生成 (参考 glm2api/services/glm_auth.py build_sign)
        String SIGN_SECRET = "8a1317a7468aa3ad86e997d08f3f31cb";
        long nowMs = System.currentTimeMillis();
        String nowStr = String.valueOf(nowMs);
        int[] digits = new int[nowStr.length()];
        int digitSum = 0;
        for (int i = 0; i < nowStr.length(); i++) {
            digits[i] = nowStr.charAt(i) - '0';
            digitSum += digits[i];
        }
        int checksum = (digitSum - digits[nowStr.length() - 2]) % 10;
        if (checksum < 0) checksum += 10;
        String timestamp = nowStr.substring(0, nowStr.length() - 2) + checksum + nowStr.charAt(nowStr.length() - 1);
        String nonce = java.util.UUID.randomUUID().toString().replace("-", "");
        String signStr = md5Hex(timestamp + "-" + nonce + "-" + SIGN_SECRET);

        // 签名头
        headerMethod.invoke(builder, "X-Sign", signStr);
        headerMethod.invoke(builder, "X-Timestamp", timestamp);
        headerMethod.invoke(builder, "X-Nonce", nonce);
        headerMethod.invoke(builder, "X-Request-Id", java.util.UUID.randomUUID().toString().replace("-", ""));

        // 设备 ID
        String deviceId = capture.getDeviceId();
        if (deviceId != null) {
            headerMethod.invoke(builder, "X-Device-Id", deviceId);
        } else {
            headerMethod.invoke(builder, "X-Device-Id", java.util.UUID.randomUUID().toString().replace("-", ""));
        }

        // 浏览器头 (参考 glm2api get_browser_headers)
        headerMethod.invoke(builder, "Accept", "text/event-stream");
        headerMethod.invoke(builder, "Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8,en-GB;q=0.7,en-US;q=0.6");
        headerMethod.invoke(builder, "App-Name", "chatglm");
        headerMethod.invoke(builder, "Cache-Control", "no-cache");
        headerMethod.invoke(builder, "Content-Type", "application/json");
        headerMethod.invoke(builder, "Origin", "https://chatglm.cn");
        headerMethod.invoke(builder, "Pragma", "no-cache");
        headerMethod.invoke(builder, "Sec-Ch-Ua", "\"Microsoft Edge\";v=\"143\", \"Chromium\";v=\"143\", \"Not A(Brand\";v=\"24\"");
        headerMethod.invoke(builder, "Sec-Ch-Ua-Mobile", "?0");
        headerMethod.invoke(builder, "Sec-Ch-Ua-Platform", "\"Windows\"");
        headerMethod.invoke(builder, "Sec-Fetch-Dest", "empty");
        headerMethod.invoke(builder, "Sec-Fetch-Mode", "cors");
        headerMethod.invoke(builder, "Sec-Fetch-Site", "same-origin");
        headerMethod.invoke(builder, "User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/143.0.0.0 Safari/537.36 Edg/143.0.0.0");
        headerMethod.invoke(builder, "X-App-Fr", "browser_extension");
        headerMethod.invoke(builder, "X-App-Platform", "pc");
        headerMethod.invoke(builder, "X-App-Version", "0.0.1");
        headerMethod.invoke(builder, "X-Device-Brand", "");
        headerMethod.invoke(builder, "X-Device-Model", "");
        headerMethod.invoke(builder, "X-Lang", "zh");
    }

    /** MD5 哈希 */
    private String md5Hex(String input) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b & 0xff));
            }
            return sb.toString();
        } catch (Exception e) {
            return "";
        }
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
        if ("null".equals(content)) content = "";  // optString returns "null" for JSON null
        String reasoning = message != null ? message.optString("reasoning_content", null) : null;
        if (reasoning != null && (reasoning.isEmpty() || "null".equals(reasoning))) reasoning = null;
        String finishReason = choice.optString("finish_reason", "stop");
        if (finishReason.isEmpty() || "null".equals(finishReason)) finishReason = "stop";
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

        // v1.0.63: GLM SSE 格式 — parts + content 数组
        // data: {"parts": [{"logic_id": "...", "content": [{"type": "text", "text": "..."}, {"type": "think", "think": "..."}]}], "status": "..."}
        // 需要增量提取 text/think 内容
        java.util.Map<String, String> partTexts = new java.util.HashMap<>();
        java.util.Map<String, String> partReasonings = new java.util.HashMap<>();
        java.util.List<String> orderedLogicIds = new java.util.ArrayList<>();
        java.util.Map<String, Integer> textSentLen = new java.util.HashMap<>();
        java.util.Map<String, Integer> reasoningSentLen = new java.util.HashMap<>();

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
                JSONObject event = new JSONObject(data);

                // v1.0.68: 捕获 conversation_id（SSE 事件中包含）
                String convId = event.optString("conversation_id", "");
                if (!convId.isEmpty()) {
                    lastConversationId = convId;
                }

                // GLM 格式: parts 数组
                JSONArray parts = event.optJSONArray("parts");
                if (parts != null) {
                    for (int i = 0; i < parts.length(); i++) {
                        JSONObject part = parts.optJSONObject(i);
                        if (part == null) continue;

                        String logicId = part.optString("logic_id", "");
                        if (logicId.isEmpty()) continue;

                        if (!orderedLogicIds.contains(logicId)) {
                            orderedLogicIds.add(logicId);
                        }

                        JSONArray contentItems = part.optJSONArray("content");
                        if (contentItems == null) continue;

                        StringBuilder partText = new StringBuilder();
                        StringBuilder partReasoning = new StringBuilder();

                        for (int j = 0; j < contentItems.length(); j++) {
                            JSONObject content = contentItems.optJSONObject(j);
                            if (content == null) continue;

                            String type = content.optString("type", "");
                            if ("text".equals(type)) {
                                partText.append(content.optString("text", ""));
                            } else if ("think".equals(type)) {
                                partReasoning.append(content.optString("think", ""));
                            } else if ("code".equals(type)) {
                                partText.append("```python\n")
                                    .append(content.optString("code", ""))
                                    .append("\n```");
                            } else if ("execution_output".equals(type)) {
                                partText.append(content.optString("content", ""));
                            }
                        }

                        String renderedText = partText.toString().trim();
                        String renderedReasoning = partReasoning.toString().trim();

                        if (!renderedText.isEmpty()) {
                            partTexts.put(logicId, renderedText);
                        }
                        if (!renderedReasoning.isEmpty()) {
                            partReasonings.put(logicId, renderedReasoning);
                        }
                    }
                }

                // 计算增量并发送
                for (String logicId : orderedLogicIds) {
                    // 文本增量
                    String fullText = partTexts.getOrDefault(logicId, "");
                    int prevLen = textSentLen.getOrDefault(logicId, 0);
                    if (fullText.length() > prevLen) {
                        String delta = fullText.substring(prevLen);
                        fullContent.append(delta);
                        sink.onText(delta);
                        textSentLen.put(logicId, fullText.length());
                    }

                    // 思考链增量
                    String fullReasoningStr = partReasonings.getOrDefault(logicId, "");
                    int prevReasoningLen = reasoningSentLen.getOrDefault(logicId, 0);
                    if (fullReasoningStr.length() > prevReasoningLen) {
                        String delta = fullReasoningStr.substring(prevReasoningLen);
                        fullReasoning.append(delta);
                        sink.onReasoning(delta);
                        reasoningSentLen.put(logicId, fullReasoningStr.length());
                    }
                }

                // status 判断
                String status = event.optString("status", "");
                if ("finish".equals(status) || "intervene".equals(status)
                        || "stop".equals(status)) {
                    if ("intervene".equals(status)) {
                        finishReason = "content_filter";
                    }
                    // 不立即 break，可能还有数据
                }

            } catch (Exception e) {
                log("解析 GLM SSE 异常: " + e.getMessage() + " line=" + truncate(data, 200));
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
        Log.d(TAG, msg);
    }
}
