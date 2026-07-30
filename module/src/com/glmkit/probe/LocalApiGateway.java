package com.glmkit.probe;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 本地 API 网关 — OpenAI 兼容的 HTTP 服务器。
 *
 * 路由：
 *   GET  /healthz              — 健康检查
 *   GET  /v1/models            — 模型列表
 *   POST /v1/chat/completions  — Chat Completions（支持 SSE 流式）
 *   POST /shutdown             — 停止网关
 *   POST /restart              — 重启网关
 *
 * 监听 127.0.0.1:16766（默认），仅本地访问。
 */
public class LocalApiGateway {

    private static final String TAG = "GLMKit-Gateway";
    private static final int DEFAULT_PORT = 16766;
    private static final int SOCKET_BACKLOG = 16;
    private static final int MAX_HEADER_BYTES = 65536;

    private static final Object LOCK = new Object();
    private static volatile ServerSocket serverSocket;
    private static volatile Thread acceptThread;
    private static volatile Backend backend;
    private static volatile Context context;
    private static final AtomicBoolean running = new AtomicBoolean(false);
    private static final AtomicInteger activeConnections = new AtomicInteger(0);
    private static final ExecutorService workerPool =
            Executors.newCachedThreadPool(r -> {
                Thread t = new Thread(r, "glmkit-worker");
                t.setDaemon(true);
                return t;
            });
    private static volatile int listenPort = DEFAULT_PORT;
    private static volatile String gatewayApiKey = null;  // 自定义 API Key (null 表示不验证)

    // ════════════════════════════════════════════════════════════
    //  Backend 接口
    // ════════════════════════════════════════════════════════════
    public interface Backend {
        boolean isReady();
        /** 从共享文件重载 auth */
        void reloadAuth();
        String readinessDetail();

        /**
         * 执行 chat completion。
         *
         * @param request  请求参数
         * @param sink     流式回调（null 表示非流式）
         * @return         完成结果
         */
        CompletionResult complete(CompletionRequest request, DeltaSink sink) throws Exception;

        /** 返回最近错误信息（用于诊断端点），无错误返回 null */
        default String lastError() { return null; }

        /** 返回详细诊断信息（用于诊断端点） */
        default String diagnosticInfo() { return readinessDetail(); }
        /** v1.0.53: 返回从 APP 拦截到的真实模型 ID */
        default String getCapturedModel() { return null; }
    }

    public interface DeltaSink {
        /** 流式文本增量 */
        boolean onText(String delta) throws Exception;
        /** 流式推理增量（reasoning content） */
        boolean onReasoning(String delta) throws Exception;
        /** 流式函数调用增量（tool_calls delta JSON） */
        default boolean onToolCalls(String deltaJson) throws Exception { return true; }
        /** 客户端是否已断开 */
        boolean isCancelled();
    }

    // ════════════════════════════════════════════════════════════
    //  请求/结果类
    // ════════════════════════════════════════════════════════════
    public static final class CompletionRequest {
        public final String requestId;
        public final String model;
        public final JSONArray messages;
        public final boolean stream;
        public final double temperature;
        public final int maxTokens;
        public final double topP;
        public final String[] stop;
        public final JSONObject rawRequest;  // 原始请求 JSON，用于透传额外参数

        public CompletionRequest(String requestId, String model, JSONArray messages,
                                 boolean stream, double temperature, int maxTokens,
                                 double topP, String[] stop, JSONObject rawRequest) {
            this.requestId = requestId;
            this.model = model;
            this.messages = messages;
            this.stream = stream;
            this.temperature = temperature;
            this.maxTokens = maxTokens;
            this.topP = topP;
            this.stop = stop;
            this.rawRequest = rawRequest;
        }
    }

    public static final class CompletionResult {
        public final String content;
        public final String reasoning;
        public final String finishReason;
        public final int promptTokens;
        public final int completionTokens;
        public final JSONArray toolCalls;  // GLM 函数调用 (null 表示无)

        public CompletionResult(String content, String reasoning, String finishReason,
                                int promptTokens, int completionTokens) {
            this(content, reasoning, finishReason, promptTokens, completionTokens, null);
        }

        public CompletionResult(String content, String reasoning, String finishReason,
                                int promptTokens, int completionTokens, JSONArray toolCalls) {
            this.content = content;
            this.reasoning = reasoning;
            this.finishReason = finishReason;
            this.promptTokens = promptTokens;
            this.completionTokens = completionTokens;
            this.toolCalls = toolCalls;
        }
    }

    public static class GatewayException extends Exception {
        public final int httpStatus;
        public final String errorCode;

        public GatewayException(int httpStatus, String errorCode, String message) {
            super(message);
            this.httpStatus = httpStatus;
            this.errorCode = errorCode;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════
    public static void setListenPort(int port) {
        if (port >= 1024 && port <= 65535) {
            listenPort = port;
        }
    }

    public static int getListenPort() {
        return listenPort;
    }

    public static void setApiKey(String key) {
        gatewayApiKey = (key != null && !key.isEmpty()) ? key : null;
    }

    public static String getApiKey() {
        return gatewayApiKey;
    }

    public static int start(Context ctx, Backend b) {
        synchronized (LOCK) {
            if (running.get()) {
                log("网关已在运行");
                return listenPort;
            }
            context = ctx.getApplicationContext();
            backend = b;
            running.set(true);

            final java.util.concurrent.CountDownLatch bindLatch = new java.util.concurrent.CountDownLatch(1);
            acceptThread = new Thread(() -> {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.bind(new InetSocketAddress("127.0.0.1", listenPort),
                                      SOCKET_BACKLOG);
                    log("网关监听 127.0.0.1:" + listenPort);
                    bindLatch.countDown();

                    while (running.get() && !serverSocket.isClosed()) {
                        try {
                            Socket client = serverSocket.accept();
                            activeConnections.incrementAndGet();
                            workerPool.submit(() -> handleConnection(client));
                        } catch (IOException e) {
                            if (running.get()) {
                                log("accept 异常: " + e.getMessage());
                            }
                        }
                    }
                } catch (java.net.BindException be) {
                    log("✗ 端口 " + listenPort + " 已被占用");
                    // v1.0.73: 顺序递增尝试，不用随机（方便用户找到端口）
                    for (int altPort = listenPort + 1; altPort <= listenPort + 100; altPort++) {
                        try {
                            serverSocket = new ServerSocket();
                            serverSocket.bind(new InetSocketAddress("127.0.0.1", altPort),
                                              SOCKET_BACKLOG);
                            listenPort = altPort;
                            log("✓ 端口冲突，自动切换到 " + altPort);
                            bindLatch.countDown();
                            while (running.get() && !serverSocket.isClosed()) {
                                try {
                                    Socket client = serverSocket.accept();
                                    activeConnections.incrementAndGet();
                                    workerPool.submit(() -> handleConnection(client));
                                } catch (IOException e) {
                                    if (running.get()) log("accept 异常: " + e.getMessage());
                                }
                            }
                            return;
                        } catch (Throwable ignored) {}
                    }
                } catch (Throwable t) {
                    log("网关启动失败: " + t.getMessage());
                } finally {
                    bindLatch.countDown();
                    // 只当当前线程仍是 acceptThread 时才设 running=false
                    // 避免 restart 场景下旧线程覆盖新线程的 running=true
                    if (acceptThread == Thread.currentThread()) {
                        running.set(false);
                    }
                    log("网关线程退出");
                }
            }, "glmkit-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
            try { bindLatch.await(5, java.util.concurrent.TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            return listenPort;
        }
    }

    public static void stop() {
        synchronized (LOCK) {
            running.set(false);
            if (serverSocket != null) {
                try { serverSocket.close(); } catch (IOException ignored) {}
            }
            if (acceptThread != null) {
                acceptThread.interrupt();
            }
            log("网关已停止");
        }
    }

    public static boolean isRunning() {
        return running.get();
    }

    public static String endpoint() {
        return "http://127.0.0.1:" + listenPort + "/v1";
    }

    public static String connectionInfo() {
        StringBuilder sb = new StringBuilder();
        sb.append(isRunning() ? "运行中" : "已停止");
        sb.append(" | 端口: ").append(listenPort);
        sb.append(" | 活跃连接: ").append(activeConnections.get());
        if (backend != null) {
            sb.append(" | 后端: ").append(backend.isReady() ? "就绪" : "未就绪");
            if (!backend.isReady()) {
                sb.append(" (").append(backend.readinessDetail()).append(")");
            }
        }
        return sb.toString();
    }

    // ════════════════════════════════════════════════════════════
    //  HTTP 连接处理
    // ════════════════════════════════════════════════════════════
    private static void handleConnection(Socket socket) {
        try {
            socket.setSoTimeout(120_000);
            socket.setKeepAlive(true);
            log("收到连接: " + socket.getRemoteSocketAddress());

            InputStream is = socket.getInputStream();
            OutputStream os = socket.getOutputStream();

            // 读取 HTTP 请求行
            String requestLine = readLine(is);
            if (requestLine == null || requestLine.isEmpty()) {
                sendResponse(os, 400, "Bad Request", "text/plain", "Bad Request");
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 3) {
                sendResponse(os, 400, "Bad Request", "text/plain", "Bad Request");
                return;
            }

            String method = parts[0];
            String path = parts[1];
            // 剥离查询参数 (e.g. /v1/models?foo=bar → /v1/models)
            int qIdx = path.indexOf('?');
            if (qIdx >= 0) path = path.substring(0, qIdx);
            String version = parts[2];

            // 读取 headers
            ConcurrentHashMap<String, String> headers = new ConcurrentHashMap<>();
            int headerBytes = 0;
            while (true) {
                String line = readLine(is);
                if (line == null || line.isEmpty()) break;
                headerBytes += line.length() + 2;
                if (headerBytes > MAX_HEADER_BYTES) {
                    sendResponse(os, 431, "Headers Too Large", "text/plain", "Headers Too Large");
                    return;
                }
                int colon = line.indexOf(':');
                if (colon > 0) {
                    String name = line.substring(0, colon).trim().toLowerCase();
                    String value = line.substring(colon + 1).trim();
                    headers.put(name, value);
                }
            }

            // 读取 body（Content-Length 或 chunked）
            String contentLengthStr = headers.get("content-length");
            String transferEncoding = headers.get("transfer-encoding");
            byte[] body = null;
            if (contentLengthStr != null) {
                try {
                    int contentLength = Integer.parseInt(contentLengthStr.trim());
                    if (contentLength < 0) {
                        sendResponse(os, 400, "Bad Request", "text/plain", "Invalid Content-Length");
                        return;
                    }
                    if (contentLength > 10 * 1024 * 1024) {
                        sendResponse(os, 413, "Payload Too Large", "text/plain",
                                     "Body exceeds 10MB limit");
                        return;
                    }
                    if (contentLength > 0) {
                        body = new byte[contentLength];
                        int read = 0;
                        while (read < contentLength) {
                            int n = is.read(body, read, contentLength - read);
                            if (n < 0) break;
                            read += n;
                        }
                    }
                } catch (NumberFormatException nfe) {
                    sendResponse(os, 400, "Bad Request", "text/plain", "Invalid Content-Length");
                    return;
                }
            } else if (transferEncoding != null && transferEncoding.toLowerCase().contains("chunked")) {
                // 读取 chunked transfer encoding body
                body = readChunkedBody(is);
                if (body == null) {
                    sendResponse(os, 400, "Bad Request", "text/plain", "Chunked body read failed");
                    return;
                }
            }

            // 路由
            routeRequest(method, path, headers, body, os);

        } catch (Throwable t) {
            log("连接处理异常: " + t.getMessage());
        } finally {
            activeConnections.decrementAndGet();
            try { socket.close(); } catch (IOException ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════
    //  路由
    // ════════════════════════════════════════════════════════════
    private static void routeRequest(String method, String path,
                                     ConcurrentHashMap<String, String> headers,
                                     byte[] body, OutputStream os) throws IOException {
        String logMsg = method + " " + path;
        log("请求: " + logMsg);

        // CORS preflight
        if ("OPTIONS".equals(method)) {
            StringBuilder cors = new StringBuilder();
            cors.append("HTTP/1.1 204 No Content\r\n");
            cors.append("Access-Control-Allow-Origin: *\r\n");
            cors.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            cors.append("Access-Control-Allow-Headers: Content-Type, Authorization\r\n");
            cors.append("Access-Control-Max-Age: 86400\r\n");
            cors.append("Content-Length: 0\r\n");
            cors.append("\r\n");
            os.write(cors.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
            return;
        }

        // Web 控制面板
        if ("GET".equals(method) && ("/".equals(path) || "/ui".equals(path))) {
            sendResponse(os, 200, "OK", "text/html; charset=utf-8", getWebUI());
            return;
        }

        // 模型管理端点（不需要 API Key 验证）
        if ("POST".equals(method) && "/v1/models/capture".equals(path)) {
            handleCaptureModel(os);
            return;
        }
        if ("POST".equals(method) && "/v1/models/add".equals(path)) {
            handleAddModel(body, os);
            return;
        }
        if ("POST".equals(method) && "/v1/models/delete".equals(path)) {
            handleDeleteModel(body, os);
            return;
        }

        // v1.0.68: 设置端点（不需要 API Key 验证）
        if ("GET".equals(method) && "/v1/settings".equals(path)) {
            handleGetSettings(os);
            return;
        }
        if ("POST".equals(method) && "/v1/settings/auto-delete".equals(path)) {
            handleToggleAutoDelete(body, os);
            return;
        }

        // 健康检查
        if ("GET".equals(method) && "/healthz".equals(path)) {
            JSONObject health = new JSONObject();
            try {
                health.put("status", "ok");
                health.put("running", isRunning());
                health.put("endpoint", endpoint());
                health.put("userId", android.os.Process.myUid() / 100000);
                try { health.put("processName", android.os.Process.myProcessName()); } catch (Throwable ignored) {} // API 28+
                health.put("activeConnections", activeConnections.get());
                if (backend != null) {
                    health.put("backendReady", backend.isReady());
                    health.put("backendDetail", backend.readinessDetail());
                }
            } catch (Exception ignored) {}
            sendResponse(os, 200, "OK", "application/json", health.toString());
            return;
        }

        // 网关控制端点 — /shutdown 和 /restart
        if ("POST".equals(method) && "/shutdown".equals(path)) {
            log("收到 /shutdown 请求，正在停止网关...");
            stop();
            JSONObject resp = new JSONObject();
            try {
                resp.put("status", "shutdown");
                resp.put("message", "网关已停止");
            } catch (Exception ignored) {}
            sendResponse(os, 200, "OK", "application/json", resp.toString());
            return;
        }

        if ("POST".equals(method) && "/restart".equals(path)) {
            log("收到 /restart 请求，正在重启网关...");
            // 重新从 SharedPreferences 读取配置（API Key 等）
            try {
                if (context != null) {
                    SharedPreferences prefs = context.getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
                    String newKey = prefs.getString("api_key", null);
                    setApiKey(newKey);
                    int newPort = prefs.getInt("port", listenPort);
                    if (newPort >= 1024 && newPort <= 65535) {
                        listenPort = newPort;
                    }
                    log("重启时重新加载配置: port=" + listenPort + ", apiKey=" + (newKey != null && !newKey.isEmpty() ? "已设置" : "未设置"));
                }
            } catch (Throwable t) {
                log("重启时读取配置失败: " + t.getMessage());
            }
            stop();
            try { Thread.sleep(200); } catch (InterruptedException ignored) {}
            int restartPort = start(context, backend);
            JSONObject resp = new JSONObject();
            try {
                resp.put("status", "restarted");
                resp.put("message", restartPort > 0 ? "网关已重启" : "重启失败");
                resp.put("port", restartPort);
            } catch (Exception ignored) {}
            sendResponse(os, 200, "OK", "application/json", resp.toString());
            return;
        }

        // API Key 验证 — 保护 /v1/ API 端点（/v1/diagnostic 除外）
        if (gatewayApiKey != null && path.startsWith("/v1/")
                && !"/v1/diagnostic".equals(path)
                && !"/v1/models".equals(path)) {
            String authHeader = headers.get("authorization");
            String providedKey = null;
            if (authHeader != null) {
                authHeader = authHeader.trim();
                if (authHeader.toLowerCase().startsWith("bearer ")) {
                    providedKey = authHeader.substring(7).trim();
                } else {
                    providedKey = authHeader;
                }
            }
            if (providedKey == null || !providedKey.equals(gatewayApiKey)) {
                log("✗ API Key 验证失败");
                JSONObject authErr = new JSONObject();
                try {
                    authErr.put("error", new JSONObject()
                        .put("message", "Invalid API key. Set Authorization: Bearer <your-key>")
                        .put("type", "invalid_api_key")
                        .put("code", 401));
                } catch (Exception ignored) {}
                sendResponse(os, 401, "Unauthorized", "application/json", authErr.toString());
                return;
            }
        }

        // 模型列表
        if ("GET".equals(method) && "/v1/models".equals(path)) {
            handleListModels(os);
            return;
        }

        // 诊断端点
        if ("GET".equals(method) && "/v1/diagnostic".equals(path)) {
            handleDiagnostic(os);
            return;
        }

        // 日志端点 — 返回模块日志缓冲区
        if ("GET".equals(method) && "/v1/logs".equals(path)) {
            handleLogs(os);
            return;
        }

        // Chat Completions
        if ("POST".equals(method) && "/v1/chat/completions".equals(path)) {
            handleChatCompletions(headers, body, os);
            return;
        }

        // 404
        JSONObject err = new JSONObject();
        try {
            err.put("error", new JSONObject()
                .put("message", "Not Found: " + path)
                .put("type", "not_found")
                .put("code", 404));
        } catch (Exception ignored) {}
        sendResponse(os, 404, "Not Found", "application/json", err.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  持久化模型列表 — SharedPreferences
    // ════════════════════════════════════════════════════════════
    private static final String PREF_MODELS_KEY = "custom_models";
    private static final String PREF_AUTO_DELETE_CONV = "auto_delete_conversation";

    /** v1.0.68: 读取自动删除会话设置（默认开启） */
    static boolean isAutoDeleteConversation() {
        if (context == null) return true;
        try {
            SharedPreferences prefs = context.getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
            return prefs.getBoolean(PREF_AUTO_DELETE_CONV, true);
        } catch (Throwable t) {
            log("读取 auto_delete_conversation 失败: " + t.getMessage());
            return true;
        }
    }

    private static java.util.List<String> getPersistentModels() {
        java.util.List<String> models = new java.util.ArrayList<>();
        log("[getPersistentModels] context=" + (context != null ? "ok" : "null"));
        if (context != null) {
            try {
                SharedPreferences prefs = context.getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
                String json = prefs.getString(PREF_MODELS_KEY, null);
                log("[getPersistentModels] stored json=" + (json != null ? json : "null"));
                if (json != null && !json.isEmpty()) {
                    JSONArray arr = new JSONArray(json);
                    for (int i = 0; i < arr.length(); i++) {
                        String m = arr.getString(i);
                        if (m != null && !m.isEmpty() && !models.contains(m)) {
                            models.add(m);
                        }
                    }
                }
            } catch (Throwable t) {
                log("[getPersistentModels] 读取失败: " + t.getMessage());
            }
        }
        // 空列表时返回两个默认模型
        if (models.isEmpty()) {
            models.add("65940acff94777010aa6b796:thinking");
            models.add("65940acff94777010aa6b796:fast");
            log("[getPersistentModels] 空列表 → 返回2个默认模型");
        }
        return models;
    }

    private static void savePersistentModels(java.util.List<String> models) {
        if (context == null) return;
        try {
            JSONArray arr = new JSONArray();
            for (String m : models) {
                if (m != null && !m.isEmpty()) arr.put(m);
            }
            SharedPreferences prefs = context.getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
            prefs.edit().putString(PREF_MODELS_KEY, arr.toString()).apply();
            log("持久化模型列表已保存: " + models.size() + " 个模型");
        } catch (Throwable t) {
            log("保存持久化模型列表失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/models/capture — 捕获当前模型到持久化列表
    // ════════════════════════════════════════════════════════════
    private static void handleCaptureModel(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            String capturedModel = (backend != null) ? backend.getCapturedModel() : null;
            log("[/v1/models/capture] backend=" + (backend != null ? "ok" : "null") + " capturedModel=" + (capturedModel != null ? capturedModel : "null"));
            if (capturedModel == null || capturedModel.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "当前没有捕获到模型。步骤：1) 打开智谱清言APP 2) 发送任意消息 3) 等待hook捕获 4) 再点此按钮");
                sendResponse(os, 400, "Bad Request", "application/json", resp.toString());
                return;
            }
            java.util.List<String> models = getPersistentModels();
            if (!models.contains(capturedModel)) {
                models.add(capturedModel);
                savePersistentModels(models);
                resp.put("success", true);
                resp.put("message", "模型已捕获并添加到列表: " + capturedModel);
            } else {
                resp.put("success", true);
                resp.put("message", "模型已在列表中: " + capturedModel);
            }
            resp.put("model", capturedModel);
            resp.put("models", new JSONArray(models));
        } catch (Exception e) {
            try { resp.put("success", false); resp.put("message", "捕获失败: " + e.getMessage()); } catch (Exception ignored) {}
        }
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/models/add — 手动添加模型
    // ════════════════════════════════════════════════════════════
    private static void handleAddModel(byte[] body, OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            if (body == null || body.length == 0) {
                resp.put("success", false);
                resp.put("message", "请求体为空");
                sendResponse(os, 400, "Bad Request", "application/json", resp.toString());
                return;
            }
            JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String model = req.optString("model", "");
            if (model.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "model 字段为空");
                sendResponse(os, 400, "Bad Request", "application/json", resp.toString());
                return;
            }
            java.util.List<String> models = getPersistentModels();
            if (!models.contains(model)) {
                models.add(model);
                savePersistentModels(models);
                resp.put("success", true);
                resp.put("message", "模型已添加: " + model);
            } else {
                resp.put("success", true);
                resp.put("message", "模型已存在: " + model);
            }
            resp.put("models", new JSONArray(models));
        } catch (Exception e) {
            try { resp.put("success", false); resp.put("message", "添加失败: " + e.getMessage()); } catch (Exception ignored) {}
        }
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/models/delete — 删除模型
    // ════════════════════════════════════════════════════════════
    private static void handleDeleteModel(byte[] body, OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            if (body == null || body.length == 0) {
                resp.put("success", false);
                resp.put("message", "请求体为空");
                sendResponse(os, 400, "Bad Request", "application/json", resp.toString());
                return;
            }
            JSONObject req = new JSONObject(new String(body, StandardCharsets.UTF_8));
            String model = req.optString("model", "");
            if (model.isEmpty()) {
                resp.put("success", false);
                resp.put("message", "model 字段为空");
                sendResponse(os, 400, "Bad Request", "application/json", resp.toString());
                return;
            }
            java.util.List<String> models = getPersistentModels();
            if (models.remove(model)) {
                savePersistentModels(models);
                resp.put("success", true);
                resp.put("message", "模型已删除: " + model);
            } else {
                resp.put("success", false);
                resp.put("message", "模型不在列表中: " + model);
            }
            resp.put("models", new JSONArray(models));
        } catch (Exception e) {
            try { resp.put("success", false); resp.put("message", "删除失败: " + e.getMessage()); } catch (Exception ignored) {}
        }
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/models
    // ════════════════════════════════════════════════════════════
    // ════════════════════════════════════════════════════════════
    //  v1.0.68: 设置端点
    // ════════════════════════════════════════════════════════════
    private static void handleGetSettings(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            resp.put("auto_delete_conversation", isAutoDeleteConversation());
        } catch (Exception ignored) {}
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    private static void handleToggleAutoDelete(byte[] body, OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            // 解析请求体 {enabled: true/false}
            boolean enabled = false;
            if (body != null && body.length > 0) {
                String bodyStr = new String(body, StandardCharsets.UTF_8).trim();
                if (!bodyStr.isEmpty()) {
                    JSONObject req = new JSONObject(bodyStr);
                    enabled = req.optBoolean("enabled", false);
                }
            }
            // 写入 SharedPreferences
            if (context != null) {
                SharedPreferences prefs = context.getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
                prefs.edit().putBoolean(PREF_AUTO_DELETE_CONV, enabled).apply();
                log("auto_delete_conversation 设置为: " + enabled);
            }
            resp.put("success", true);
            resp.put("auto_delete_conversation", enabled);
            resp.put("message", enabled ? "已开启自动删除会话" : "已关闭自动删除会话");
        } catch (Exception e) {
            try {
                resp.put("success", false);
                resp.put("message", "设置失败: " + e.getMessage());
            } catch (Exception ignored) {}
            sendResponse(os, 500, "Internal Server Error", "application/json", resp.toString());
            return;
        }
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    private static void handleListModels(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            resp.put("object", "list");
            JSONArray data = new JSONArray();

            java.util.List<String> models = getPersistentModels();
            log("[/v1/models] 返回 " + models.size() + " 个模型: " + models);
            for (String m : models) {
                JSONObject model = new JSONObject();
                model.put("id", m);
                model.put("object", "model");
                model.put("created", System.currentTimeMillis() / 1000);
                model.put("owned_by", "zhipu");
                data.put(model);
            }
            resp.put("data", data);
        } catch (Exception e) {
            log("[/v1/models] 错误: " + e.getMessage());
        }
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/diagnostic
    // ════════════════════════════════════════════════════════════
    private static void handleDiagnostic(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            resp.put("module", "GLMKit");
            resp.put("version", getModuleVersion());
            resp.put("gateway_running", isRunning());
            resp.put("listen_port", listenPort);
            resp.put("active_connections", activeConnections.get());
            resp.put("endpoint", endpoint());
            resp.put("api_key_required", gatewayApiKey != null);

            if (backend != null) {
                resp.put("backend_ready", backend.isReady());
                resp.put("backend_detail", backend.readinessDetail());
                String cm = backend.getCapturedModel();
                if (cm != null) resp.put("captured_model", cm);
                String err = backend.lastError();
                if (err != null) resp.put("last_error", err);
                resp.put("diagnostic_info", backend.diagnosticInfo());
            } else {
                resp.put("backend_ready", false);
                resp.put("backend_detail", "后端未初始化");
            }

            // 提示
            JSONArray tips = new JSONArray();
            if (backend == null || !backend.isReady()) {
                tips.put("请确保智谱清言已打开并登录，模块需要捕获认证信息");
                tips.put("尝试在智谱清言中发起一次对话，触发 API 请求捕获");
            }
            if (isRunning() && (backend == null || !backend.isReady())) {
                tips.put("网关已启动但后端未就绪，等待 OkHttp 客户端捕获中...");
            }
            if (tips.length() > 0) resp.put("tips", tips);

        } catch (Exception ignored) {}
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/logs
    // ════════════════════════════════════════════════════════════
    private static void handleLogs(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            resp.put("module", "GLMKit");
            resp.put("log_file", LOG_FILE_PATH);
            resp.put("logs", getLogBufferText());
            resp.put("running", isRunning());
            resp.put("port", listenPort);
        } catch (Exception ignored) {}
        sendResponse(os, 200, "OK", "application/json", resp.toString());
    }

    // ════════════════════════════════════════════════════════════
    //  /v1/chat/completions
    // ════════════════════════════════════════════════════════════
    private static void handleChatCompletions(ConcurrentHashMap<String, String> headers,
                                              byte[] body, OutputStream os) throws IOException {
        // 每次请求前先尝试从共享文件重载 auth（auth 可能刚被模块捕获）
        if (backend != null && !backend.isReady()) {
            try { backend.reloadAuth(); } catch (Throwable ignored) {}
            log("chat 请求: auth 重载后 isReady=" + backend.isReady());
        }

        // 检查后端就绪
        if (backend == null || !backend.isReady()) {
            String detail = (backend != null) ? backend.readinessDetail() : "后端未初始化";
            sendError(os, 503, "backend_not_ready", "后端未就绪: " + detail);
            return;
        }

        if (body == null || body.length == 0) {
            sendError(os, 400, "invalid_request", "请求体为空");
            return;
        }

        // 解析请求 JSON
        JSONObject req;
        try {
            req = new JSONObject(new String(body, StandardCharsets.UTF_8));
        } catch (Exception e) {
            sendError(os, 400, "invalid_json", "JSON 解析失败: " + e.getMessage());
            return;
        }

        String model = req.optString("model", "glm-4");
        JSONArray messages = req.optJSONArray("messages");
        if (messages == null || messages.length() == 0) {
            sendError(os, 400, "invalid_request", "messages 字段为空");
            return;
        }

        boolean stream = req.optBoolean("stream", false);
        double temperature = req.optDouble("temperature", 0.7);
        int maxTokens = req.optInt("max_tokens", 0);
        // OpenAI 新版 API 使用 max_completion_tokens 替代 max_tokens
        if (maxTokens == 0) {
            maxTokens = req.optInt("max_completion_tokens", 0);
        }
        double topP = req.optDouble("top_p", 1.0);

        String[] stop = null;
        // OpenAI allows stop as a string or array of strings
        JSONArray stopArr = req.optJSONArray("stop");
        if (stopArr != null && stopArr.length() > 0) {
            stop = new String[stopArr.length()];
            for (int i = 0; i < stopArr.length(); i++) {
                stop[i] = stopArr.optString(i);
            }
        } else {
            String stopStr = req.optString("stop", null);
            if (stopStr != null && !stopStr.isEmpty()) {
                stop = new String[]{stopStr};
            }
        }

        String requestId = "chatcmpl-" + System.currentTimeMillis()
                + "-" + (int)(Math.random() * 100000);

        CompletionRequest completionReq = new CompletionRequest(
            requestId, model, messages, stream, temperature, maxTokens, topP, stop, req);

        if (stream) {
            handleStreamCompletion(completionReq, os);
        } else {
            handleNonStreamCompletion(completionReq, os);
        }
    }

    // ── 非流式 ──────────────────────────────────────────────────
    private static void handleNonStreamCompletion(CompletionRequest req, OutputStream os)
            throws IOException {
        try {
            CompletionResult result = backend.complete(req, null);

            JSONObject resp = new JSONObject();
            resp.put("id", req.requestId);
            resp.put("object", "chat.completion");
            resp.put("created", System.currentTimeMillis() / 1000);
            resp.put("model", req.model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject message = new JSONObject();
            message.put("role", "assistant");
            message.put("content", result.content != null ? result.content : "");
            if (result.reasoning != null && !result.reasoning.isEmpty()) {
                message.put("reasoning_content", result.reasoning);
            }
            if (result.toolCalls != null && result.toolCalls.length() > 0) {
                message.put("tool_calls", result.toolCalls);
            }
            choice.put("message", message);
            choice.put("finish_reason", result.finishReason != null && !result.finishReason.isEmpty()
                ? result.finishReason : "stop");
            choices.put(choice);
            resp.put("choices", choices);

            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", result.promptTokens);
            usage.put("completion_tokens", result.completionTokens);
            usage.put("total_tokens", result.promptTokens + result.completionTokens);
            resp.put("usage", usage);

            sendResponse(os, 200, "OK", "application/json", resp.toString());

        } catch (GatewayException e) {
            sendError(os, e.httpStatus, e.errorCode, e.getMessage());
        } catch (Exception e) {
            log("非流式完成异常: " + e.getMessage());
            sendError(os, 500, "internal_error", "内部错误: " + e.getMessage());
        }
    }

    // ── 流式 SSE ────────────────────────────────────────────────
    private static void handleStreamCompletion(CompletionRequest req, OutputStream os)
            throws IOException {
        // 发送 SSE headers
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 200 OK\r\n");
        header.append("Content-Type: text/event-stream; charset=utf-8\r\n");
        header.append("Cache-Control: no-cache\r\n");
        header.append("Connection: keep-alive\r\n");
        header.append("Access-Control-Allow-Origin: *\r\n");
        header.append("\r\n");
        os.write(header.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();

        // 发送首 chunk: role=assistant (OpenAI 兼容)
        try {
            JSONObject firstChunk = new JSONObject();
            firstChunk.put("id", req.requestId);
            firstChunk.put("object", "chat.completion.chunk");
            firstChunk.put("created", System.currentTimeMillis() / 1000);
            firstChunk.put("model", req.model);
            JSONArray firstChoices = new JSONArray();
            JSONObject firstChoice = new JSONObject();
            firstChoice.put("index", 0);
            JSONObject firstDelta = new JSONObject();
            firstDelta.put("role", "assistant");
            firstDelta.put("content", "");
            firstChoice.put("delta", firstDelta);
            firstChoice.put("finish_reason", JSONObject.NULL);
            firstChoices.put(firstChoice);
            firstChunk.put("choices", firstChoices);
            writeSseEvent(os, firstChunk.toString());
        } catch (Exception e) {
            log("发送首 chunk 异常: " + e.getMessage());
        }

        final OutputStream sos = os;
        DeltaSink sink = new DeltaSink() {
            @Override
            public boolean onText(String delta) throws Exception {
                JSONObject chunk = new JSONObject();
                chunk.put("id", req.requestId);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", System.currentTimeMillis() / 1000);
                chunk.put("model", req.model);

                JSONArray choices = new JSONArray();
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                JSONObject d = new JSONObject();
                d.put("content", delta);
                choice.put("delta", d);
                choice.put("finish_reason", JSONObject.NULL);
                choices.put(choice);
                chunk.put("choices", choices);

                return writeSseEvent(sos, chunk.toString());
            }

            @Override
            public boolean onReasoning(String delta) throws Exception {
                JSONObject chunk = new JSONObject();
                chunk.put("id", req.requestId);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", System.currentTimeMillis() / 1000);
                chunk.put("model", req.model);

                JSONArray choices = new JSONArray();
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                JSONObject d = new JSONObject();
                d.put("reasoning_content", delta);
                choice.put("delta", d);
                choice.put("finish_reason", JSONObject.NULL);
                choices.put(choice);
                chunk.put("choices", choices);

                return writeSseEvent(sos, chunk.toString());
            }

            @Override
            public boolean onToolCalls(String deltaJson) throws Exception {
                JSONObject chunk = new JSONObject();
                chunk.put("id", req.requestId);
                chunk.put("object", "chat.completion.chunk");
                chunk.put("created", System.currentTimeMillis() / 1000);
                chunk.put("model", req.model);

                JSONArray choices = new JSONArray();
                JSONObject choice = new JSONObject();
                choice.put("index", 0);
                JSONObject d = new JSONObject();
                d.put("tool_calls", new JSONArray(deltaJson));
                choice.put("delta", d);
                choice.put("finish_reason", JSONObject.NULL);
                choices.put(choice);
                chunk.put("choices", choices);

                return writeSseEvent(sos, chunk.toString());
            }

            @Override
            public boolean isCancelled() {
                return !running.get();
            }
        };

        try {
            CompletionResult result = backend.complete(req, sink);

            // 发送最终 chunk
            JSONObject finalChunk = new JSONObject();
            finalChunk.put("id", req.requestId);
            finalChunk.put("object", "chat.completion.chunk");
            finalChunk.put("created", System.currentTimeMillis() / 1000);
            finalChunk.put("model", req.model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);
            choice.put("delta", new JSONObject());
            choice.put("finish_reason",
                result != null && result.finishReason != null && !result.finishReason.isEmpty()
                    ? result.finishReason : "stop");
            choices.put(choice);
            finalChunk.put("choices", choices);

            if (result != null && result.promptTokens > 0) {
                JSONObject usage = new JSONObject();
                usage.put("prompt_tokens", result.promptTokens);
                usage.put("completion_tokens", result.completionTokens);
                usage.put("total_tokens", result.promptTokens + result.completionTokens);
                finalChunk.put("usage", usage);
            }

            writeSseEvent(os, finalChunk.toString());

            // 发送 [DONE]
            os.write("data: [DONE]\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();

        } catch (GatewayException e) {
            sendSseError(os, e.errorCode, e.getMessage());
        } catch (Exception e) {
            log("流式完成异常: " + e.getMessage());
            sendSseError(os, "internal_error", e.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  HTTP 工具方法
    // ════════════════════════════════════════════════════════════
    private static String readLine(InputStream is) throws IOException {
        StringBuilder sb = new StringBuilder();
        int c;
        while ((c = is.read()) != -1) {
            if (c == '\r') {
                int next = is.read();
                if (next == '\n') break;
                sb.append((char) c);
                if (next != -1) sb.append((char) next);
            } else if (c == '\n') {
                break;
            } else {
                sb.append((char) c);
            }
            if (sb.length() > MAX_HEADER_BYTES) break;
        }
        return sb.toString();
    }

    /**
     * 读取 HTTP/1.1 chunked transfer encoding body。
     * 格式: <hex-size>\r\n<data>\r\n ... 0\r\n\r\n
     */
    private static byte[] readChunkedBody(InputStream is) throws IOException {
        java.io.ByteArrayOutputStream buf = new java.io.ByteArrayOutputStream();
        while (true) {
            String sizeLine = readLine(is);
            if (sizeLine == null) return null;
            // 去除 chunk extension (分号后的内容)
            String sizeStr = sizeLine.trim();
            int semi = sizeStr.indexOf(';');
            if (semi >= 0) sizeStr = sizeStr.substring(0, semi);
            int chunkSize;
            try {
                chunkSize = Integer.parseInt(sizeStr, 16);
            } catch (NumberFormatException e) {
                return null;
            }
            if (chunkSize == 0) {
                // 读取 trailing headers (空行结束)
                while (true) {
                    String trailer = readLine(is);
                    if (trailer == null || trailer.isEmpty()) break;
                }
                break;
            }
            if (chunkSize < 0 || buf.size() + chunkSize > 10 * 1024 * 1024) {
                return null; // 过大
            }
            byte[] chunk = new byte[chunkSize];
            int read = 0;
            while (read < chunkSize) {
                int n = is.read(chunk, read, chunkSize - read);
                if (n < 0) return null;
                read += n;
            }
            buf.write(chunk);
            // 读取 chunk 后的 \r\n
            readLine(is);
        }
        return buf.toByteArray();
    }

    // ════════════════════════════════════════════════════════════
    //  Web UI 控制面板
    // ════════════════════════════════════════════════════════════
    private static String getWebUI() {
        String apiKey = (gatewayApiKey != null) ? gatewayApiKey : "";
        return "<!DOCTYPE html><html lang=\"zh\"><head><meta charset=\"utf-8\">"
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<title>GLMKit 控制面板</title><style>"
            + "*{box-sizing:border-box;margin:0;padding:0}"
            + "body{font-family:system-ui,sans-serif;background:#1a1a2e;color:#eee;padding:16px;max-width:800px;margin:0 auto}"
            + "h1{text-align:center;color:#0f0;margin:10px 0;font-size:20px}"
            + "h2{color:#0af;margin:14px 0 6px;font-size:16px;border-bottom:1px solid #333;padding-bottom:4px}"
            + ".card{background:#16213e;border-radius:8px;padding:12px;margin:8px 0}"
            + "button{background:#0f3460;color:#fff;border:none;border-radius:4px;padding:8px 16px;cursor:pointer;font-size:14px}"
            + "button:hover{background:#16537e}button:disabled{opacity:.5;cursor:default}"
            + "button.danger{background:#e94560}button.danger:hover{background:#c81e3a}"
            + "button.success{background:#0f0;color:#000}button.success:hover{background:#0c0}"
            + "input,textarea{width:100%;background:#0a0f1e;color:#eee;border:1px solid #333;border-radius:4px;padding:8px;font-size:14px}"
            + "input[type=checkbox]{width:18px;height:18px;accent-color:#0f0;margin:0 6px 0 0;padding:0;border:none;background:auto;cursor:pointer;vertical-align:middle}"
            + "textarea{resize:vertical;min-height:60px}"
            + ".row{display:flex;gap:8px;align-items:center;margin:6px 0}"
            + ".row button{white-space:nowrap}"
            + ".model-item{display:flex;justify-content:space-between;align-items:center;padding:6px 8px;background:#0a0f1e;border-radius:4px;margin:4px 0}"
            + ".model-item span{word-break:break-all;font-size:13px}"
            + "#chatLog{background:#0a0f1e;border-radius:4px;padding:8px;min-height:120px;max-height:300px;overflow-y:auto;font-size:13px;white-space:pre-wrap}"
            + ".status{font-size:13px;color:#888;margin:4px 0}"
            + ".ok{color:#0f0}.err{color:#e94560}.warn{color:#fa0}"
            + "</style></head><body>"
            + "<h1>GLMKit 控制面板</h1>"
            + "<div class=\"status\" style=\"text-align:center;color:#666;font-size:12px\">v1.0.73</div>"

            // 状态区
            + "<div class=\"card\"><h2>状态</h2>"
            + "<div class=\"status\" id=\"status\">加载中...</div>"
            + "<div class=\"status\" id=\"capturedModel\">当前捕获模型: 加载中...</div>"
            + "<div class=\"row\"><button class=\"success\" onclick=\"captureModel()\">📥 捕获当前模型</button></div>"
            + "</div>"

            // 设置区 v1.0.68
            + "<div class=\"card\"><h2>设置</h2>"
            + "<div class=\"row\"><label style=\"cursor:pointer;font-size:14px\">"
            + "<input type=\"checkbox\" id=\"autoDeleteConv\" style=\"width:auto;margin-right:6px\""
            + " onchange=\"toggleAutoDelete()\">对话后自动删除会话"
            + "</label></div>"
            + "<div class=\"status\" id=\"autoDeleteStatus\"></div>"
            + "</div>"

            // 模型列表
            + "<div class=\"card\"><h2>模型列表</h2>"
            + "<div id=\"modelList\"></div>"
            + "<div class=\"row\" style=\"margin-top:8px\">"
            + "<input id=\"newModel\" placeholder=\"手动输入模型 ID\">"
            + "<button onclick=\"addModel()\">➕ 添加</button>"
            + "</div></div>"

            // 聊天测试
            + "<div class=\"card\"><h2>聊天测试</h2>"
            + "<div class=\"row\"><input id=\"chatModel\" placeholder=\"模型 ID（留空用第一个）\"></div>"
            + "<textarea id=\"chatInput\" placeholder=\"输入消息...\" rows=\"3\"></textarea>"
            + "<div class=\"row\" style=\"margin-top:6px\">"
            + "<button onclick=\"sendChat()\">📤 发送</button>"
            + "<button onclick=\"document.getElementById('chatLog').innerHTML=''\">🗑️ 清空</button>"
            + "</div>"
            + "<div id=\"chatLog\" style=\"margin-top:8px\"></div>"
            + "</div>"

            + "<script>"
            + "const B=document.baseURI.replace(/\\/$/, '');"
            + "const AK='" + apiKey + "';"
            + "function auth(h){if(AK){h['Authorization']='Bearer '+AK;}return h;}"

            // 加载状态
            + "function loadStatus(){"
            + "fetch(B+'/healthz').then(r=>r.json()).then(d=>{"
            + "document.getElementById('status').innerHTML="
            + "'网关: <span class=ok>'+(d.running?'运行中':'已停止')+'</span>'"
            + "+' | 端口: '+d.endpoint"
            + "+' | 用户: '+d.userId"
            + "+' | 后端: '+(d.backendReady?'<span class=ok>就绪</span>':'<span class=err>未就绪</span>');"
            + "}).catch(e=>{document.getElementById('status').innerHTML='<span class=err>无法连接网关</span>';});"

            + "fetch(B+'/v1/diagnostic').then(r=>r.json()).then(d=>{"
            + "let cm=d.captured_model||'';"
            + "document.getElementById('capturedModel').innerHTML="
            + "'当前捕获模型: '+(cm?'<span class=ok>'+cm+'</span>':'<span class=warn>未捕获</span>');"
            + "}).catch(e=>{});"

            + "loadModels();"
            + "loadSettings();"
            + "}"

            // v1.0.68: 加载设置
            + "function loadSettings(){"
            + "fetch(B+'/v1/settings').then(r=>r.json()).then(d=>{"
            + "let cb=document.getElementById('autoDeleteConv');"
            + "cb.checked=!!d.auto_delete_conversation;"
            + "let st=document.getElementById('autoDeleteStatus');"
            + "st.innerHTML=cb.checked?'<span class=warn>⚠️ 已开启：每次对话后自动删除会话</span>':'<span class=status>已关闭：对话后保留会话</span>';"
            + "}).catch(e=>{});"
            + "}"

            // v1.0.68: 切换自动删除
            + "function toggleAutoDelete(){"
            + "let cb=document.getElementById('autoDeleteConv');"
            + "fetch(B+'/v1/settings/auto-delete',{method:'POST',headers:auth({'Content-Type':'application/json'}),"
            + "body:JSON.stringify({enabled:cb.checked})}).then(r=>r.json()).then(d=>{"
            + "let st=document.getElementById('autoDeleteStatus');"
            + "st.innerHTML=cb.checked?'<span class=warn>⚠️ 已开启：每次对话后自动删除会话</span>':'<span class=status>已关闭：对话后保留会话</span>';"
            + "}).catch(e=>{alert('设置失败: '+e);cb.checked=!cb.checked;});"
            + "}"

            // 加载模型列表
            + "function loadModels(){"
            + "fetch(B+'/v1/models',{headers:auth({})}).then(r=>r.json()).then(d=>{"
            + "let el=document.getElementById('modelList');"
            + "if(!d.data||d.data.length===0){el.innerHTML='<div class=status>暂无模型，点击上方捕获按钮添加</div>';return;}"
            + "el.innerHTML=d.data.map(m=>"
            + "'<div class=model-item><span>'+m.id+'</span>"
            + "<button class=danger onclick=\\'delModel(\"'+m.id+'\")\\'>删除</button></div>'"
            + ").join('');"
            + "}).catch(e=>{document.getElementById('modelList').innerHTML='<span class=err>加载失败</span>';});"
            + "}"

            // 捕获模型
            + "function captureModel(){"
            + "fetch(B+'/v1/models/capture',{method:'POST',headers:auth({})}).then(r=>r.json()).then(d=>{"
            + "alert(d.message||'操作完成');loadStatus();"
            + "}).catch(e=>{alert('捕获失败: '+e);});"
            + "}"

            // 添加模型
            + "function addModel(){"
            + "let m=document.getElementById('newModel').value.trim();"
            + "if(!m)return;"
            + "fetch(B+'/v1/models/add',{method:'POST',headers:auth({'Content-Type':'application/json'}),"
            + "body:JSON.stringify({model:m})}).then(r=>r.json()).then(d=>{"
            + "alert(d.message||'操作完成');document.getElementById('newModel').value='';loadModels();"
            + "}).catch(e=>{alert('添加失败: '+e);});"
            + "}"

            // 删除模型
            + "function delModel(m){"
            + "if(!confirm('确认删除模型: '+m+'?'))return;"
            + "fetch(B+'/v1/models/delete',{method:'POST',headers:auth({'Content-Type':'application/json'}),"
            + "body:JSON.stringify({model:m})}).then(r=>r.json()).then(d=>{"
            + "alert(d.message||'操作完成');loadModels();"
            + "}).catch(e=>{alert('删除失败: '+e);});"
            + "}"

            // 发送聊天
            + "function sendChat(){"
            + "let msg=document.getElementById('chatInput').value.trim();"
            + "if(!msg)return;"
            + "let model=document.getElementById('chatModel').value.trim();"
            + "let log=document.getElementById('chatLog');"
            + "log.innerHTML+='<span class=warn>我: </span>'+msg+'\\n';"
            + "let btn=event.target;btn.disabled=true;btn.textContent='发送中...';"
            + "fetch(B+'/v1/chat/completions',{method:'POST',headers:auth({'Content-Type':'application/json'}),"
            + "body:JSON.stringify({model:model||undefined,messages:[{role:'user',content:msg}],stream:false})})"
            + ".then(r=>r.json()).then(d=>{"
            + "if(d.error){log.innerHTML+='<span class=err>错误: '+d.error.message+'</span>\\n';}"
            + "else if(d.choices&&d.choices[0]){log.innerHTML+='<span class=ok>AI: </span>'+d.choices[0].message.content+'\\n';}"
            + "else{log.innerHTML+='<span class=err>未知响应: '+JSON.stringify(d)+'</span>\\n';}"
            + "}).catch(e=>{log.innerHTML+='<span class=err>请求失败: '+e+'</span>\\n';})"
            + ".finally(()=>{btn.disabled=false;btn.textContent='📤 发送';});"
            + "}"

            + "loadStatus();setInterval(loadStatus,5000);"
            + "</script></body></html>";
    }

    private static void sendResponse(OutputStream os, int status, String reason,
                                     String contentType, String body) throws IOException {
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
        StringBuilder sb = new StringBuilder();
        sb.append("HTTP/1.1 ").append(status).append(" ").append(reason).append("\r\n");
        sb.append("Content-Type: ").append(contentType).append("; charset=utf-8\r\n");
        sb.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
        sb.append("Access-Control-Allow-Origin: *\r\n");
        sb.append("Connection: close\r\n");
        sb.append("\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.write(bodyBytes);
        os.flush();
    }

    private static void sendError(OutputStream os, int status, String code, String message)
            throws IOException {
        JSONObject err = new JSONObject();
        try {
            JSONObject error = new JSONObject();
            error.put("message", message);
            error.put("type", code);
            error.put("code", status);
            err.put("error", error);
        } catch (Exception ignored) {}
        sendResponse(os, status, "Error", "application/json", err.toString());
    }

    private static boolean writeSseEvent(OutputStream os, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("data: ").append(data).append("\r\n\r\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
        return true;
    }

    private static void sendSseError(OutputStream os, String code, String message)
            throws IOException {
        JSONObject err = new JSONObject();
        try {
            err.put("error", new JSONObject()
                .put("message", message)
                .put("type", code));
        } catch (Exception ignored) {}
        writeSseEvent(os, err.toString());
        os.write("data: [DONE]\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String getModuleVersion() {
        if (context != null) {
            try {
                // 使用模块自身的包名获取版本（context 是目标应用的 Context）
                android.content.pm.PackageInfo pi = context.getPackageManager()
                        .getPackageInfo("com.glmkit.proxy", 0);
                return pi.versionName;
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    private static final java.util.List<String> logBuffer =
            java.util.Collections.synchronizedList(new java.util.ArrayList<String>(200));
    private static final String LOG_FILE_PATH = "/sdcard/glmkit_debug.log";

    private static void log(String msg) {
        String ts = new java.text.SimpleDateFormat("HH:mm:ss.SSS",
                java.util.Locale.US).format(new java.util.Date());
        String line = "[" + ts + "] " + msg;
        Log.d(TAG, line);
        // 添加到内存缓冲区（保留最近 200 条）
        synchronized (logBuffer) {
            logBuffer.add(line);
            while (logBuffer.size() > 200) {
                logBuffer.remove(0);
            }
        }
        // 写入文件（best-effort，失败不影响运行）
        try {
            java.io.FileWriter fw = new java.io.FileWriter(LOG_FILE_PATH, true);
            fw.write(line + "\n");
            fw.flush();
            fw.close();
        } catch (Throwable ignored) {}
    }

    static String getLogBufferText() {
        synchronized (logBuffer) {
            if (logBuffer.isEmpty()) return "";
            StringBuilder sb = new StringBuilder();
            for (String l : logBuffer) {
                sb.append(l).append('\n');
            }
            return sb.toString();
        }
    }
}
