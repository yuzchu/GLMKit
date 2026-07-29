package com.glmkit.probe;

import android.content.Context;

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
 *
 * 监听 127.0.0.1:8765（默认），仅本地访问。
 */
public class LocalApiGateway {

    private static final String TAG = "GLMKit-Gateway";
    private static final int DEFAULT_PORT = 8765;
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

    // ════════════════════════════════════════════════════════════
    //  Backend 接口
    // ════════════════════════════════════════════════════════════
    public interface Backend {
        boolean isReady();
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
        if (port > 1024 && port < 65535) {
            listenPort = port;
        }
    }

    public static void start(Context ctx, Backend b) {
        synchronized (LOCK) {
            if (running.get()) {
                log("网关已在运行");
                return;
            }
            context = ctx.getApplicationContext();
            backend = b;
            running.set(true);

            acceptThread = new Thread(() -> {
                try {
                    serverSocket = new ServerSocket();
                    serverSocket.bind(new InetSocketAddress("127.0.0.1", listenPort),
                                      SOCKET_BACKLOG);
                    log("网关监听 127.0.0.1:" + listenPort);

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
                    log("✗ 端口 " + listenPort + " 已被占用，网关启动失败: " + be.getMessage());
                    // 尝试使用备用端口
                    for (int altPort = listenPort + 1; altPort <= listenPort + 10; altPort++) {
                        try {
                            serverSocket = new ServerSocket();
                            serverSocket.bind(new InetSocketAddress("127.0.0.1", altPort),
                                              SOCKET_BACKLOG);
                            listenPort = altPort;
                            log("✓ 网关在备用端口 " + altPort + " 启动成功");
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
                    running.set(false);
                    log("网关线程退出");
                }
            }, "glmkit-accept");
            acceptThread.setDaemon(true);
            acceptThread.start();
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

        // 健康检查
        if ("GET".equals(method) && "/healthz".equals(path)) {
            JSONObject health = new JSONObject();
            try {
                health.put("status", "ok");
                health.put("running", isRunning());
                health.put("endpoint", endpoint());
                health.put("activeConnections", activeConnections.get());
                if (backend != null) {
                    health.put("backendReady", backend.isReady());
                    health.put("backendDetail", backend.readinessDetail());
                }
            } catch (Exception ignored) {}
            sendResponse(os, 200, "OK", "application/json", health.toString());
            return;
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
    //  /v1/models
    // ════════════════════════════════════════════════════════════
    private static void handleListModels(OutputStream os) throws IOException {
        JSONObject resp = new JSONObject();
        try {
            resp.put("object", "list");
            JSONArray data = new JSONArray();

            String[] models = {
                "glm-4", "glm-4-flash", "glm-4-flashx", "glm-4-plus", "glm-4-long",
                "glm-4-air", "glm-4-airx", "glm-4v", "glm-4v-flash", "glm-4v-plus",
                "glm-4-0520", "codegeex-4", "glm-4-alltools"
            };

            for (String m : models) {
                JSONObject model = new JSONObject();
                model.put("id", m);
                model.put("object", "model");
                model.put("created", System.currentTimeMillis() / 1000);
                model.put("owned_by", "zhipu");
                data.put(model);
            }
            resp.put("data", data);
        } catch (Exception ignored) {}
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

            if (backend != null) {
                resp.put("backend_ready", backend.isReady());
                resp.put("backend_detail", backend.readinessDetail());
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
    //  /v1/chat/completions
    // ════════════════════════════════════════════════════════════
    private static void handleChatCompletions(ConcurrentHashMap<String, String> headers,
                                              byte[] body, OutputStream os) throws IOException {
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
                android.content.pm.PackageInfo pi = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                return pi.versionName;
            } catch (Throwable ignored) {}
        }
        return "unknown";
    }

    private static void log(String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log("[" + TAG + "] " + msg);
        } catch (Throwable ignored) {}
    }
}
