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

        // GLM 特有: 启用思考链 (通过 model 名判断，大小写不敏感)
        if (req.model != null) {
            String modelLower = req.model.toLowerCase();
            if (modelLower.contains("thinking")
                    || modelLower.contains("reasoner")
                    || modelLower.startsWith("o1")) {
                payload.put("thinking", new JSONObject().put("type", "enabled"));
            }
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
            base = "https://open.bigmodel.cn/api/paas/v4";
            log("URL 未捕获，使用默认 GLM API URL: " + base);
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
        conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (stream) {
            conn.setRequestProperty("Accept", "text/event-stream");
        }

        // 添加认证头
        String token = capture.getAuthToken();
        if (token != null) {
            if (!token.startsWith("Bearer ")) token = "Bearer " + token;
            conn.setRequestProperty("Authorization", token);
        }
        String apiKey = capture.getApiKey();
        if (apiKey != null) {
            conn.setRequestProperty("x-api-key", apiKey);
            if (token == null) {
                conn.setRequestProperty("Authorization",
                    apiKey.startsWith("Bearer ") ? apiKey : "Bearer " + apiKey);
            }
        }
        String cookie = capture.getCookie();
        if (cookie != null) {
            conn.setRequestProperty("Cookie", cookie);
        }
        conn.setRequestProperty("User-Agent", "okhttp/4.12.0");

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
                if (contentDelta != null && !contentDelta.isEmpty() && !"null".equals(contentDelta)) {
                    fullContent.append(contentDelta);
                    sink.onText(contentDelta);
                }

                // 思考链增量 (GLM 特有)
                String reasoningDelta = delta.optString("reasoning_content", null);
                if (reasoningDelta != null && !reasoningDelta.isEmpty() && !"null".equals(reasoningDelta)) {
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
        Log.d(TAG, msg);
    }
}
