package com.glmkit.probe;

/**
 * 存储从宿主应用网络层捕获的 GLM 认证信息和 API 端点。
 * 线程安全，所有字段 volatile。
 */
public class GlmCapture {

    private volatile Object okHttpClient;  // okhttp3.OkHttpClient 实例
    private volatile String baseUrl;       // Retrofit base URL
    private volatile String apiUrl;        // 最后捕获的 GLM API URL
    private volatile String authToken;     // Authorization 头
    private volatile String apiKey;        // API Key
    private volatile String cookie;        // Cookie 头
    private volatile String deviceId;      // 设备 ID

    // ── OkHttp 客户端 ─────────────────────────────────────────
    public Object getOkHttpClient() { return okHttpClient; }
    public void setOkHttpClient(Object client) {
        if (this.okHttpClient == null && client != null) {
            this.okHttpClient = client;
        }
    }

    // ── Base URL ──────────────────────────────────────────────
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String url) {
        if (url != null && !url.isEmpty()) {
            this.baseUrl = url;
        }
    }

    // ── API URL ───────────────────────────────────────────────
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String url) {
        if (url != null && !url.isEmpty()) {
            this.apiUrl = url;
        }
    }

    // ── Auth Token ────────────────────────────────────────────
    public String getAuthToken() { return authToken; }
    public void setAuthToken(String token) {
        if (token != null && !token.isEmpty()) {
            this.authToken = token;
        }
    }

    // ── API Key ───────────────────────────────────────────────
    public String getApiKey() { return apiKey; }
    public void setApiKey(String key) {
        if (key != null && !key.isEmpty()) {
            this.apiKey = key;
        }
    }

    // ── Cookie ────────────────────────────────────────────────
    public String getCookie() { return cookie; }
    public void setCookie(String cookie) {
        if (cookie != null && !cookie.isEmpty()) {
            this.cookie = cookie;
        }
    }

    // ── Device ID ─────────────────────────────────────────────
    public String getDeviceId() { return deviceId; }
    public void setDeviceId(String id) {
        if (id != null && !id.isEmpty()) {
            this.deviceId = id;
        }
    }

    // ── 就绪检查 ──────────────────────────────────────────────
    public boolean isReady() {
        return okHttpClient != null &&
               (authToken != null || apiKey != null || cookie != null);
    }

    public String readinessDetail() {
        StringBuilder sb = new StringBuilder();
        if (okHttpClient == null) sb.append("等待 OkHttp 客户端; ");
        if (authToken == null && apiKey == null && cookie == null) {
            sb.append("等待认证信息; ");
        }
        if (baseUrl == null && apiUrl == null) {
            sb.append("等待 API 端点; ");
        }
        if (sb.length() == 0) {
            sb.append("就绪");
        }
        return sb.toString();
    }

    /** 获取最佳可用的认证头值 */
    public String getBestAuth() {
        if (authToken != null) return authToken;
        if (apiKey != null) return apiKey;
        if (cookie != null) return cookie;
        return null;
    }

    /** 获取最佳可用的 API base URL */
    public String getBestBaseUrl() {
        if (baseUrl != null) return baseUrl;
        if (apiUrl != null) {
            // 从完整 URL 提取 base
            // 1. 去除 /chat/completions 后缀
            int idx = apiUrl.indexOf("/chat/completions");
            if (idx > 0) return apiUrl.substring(0, idx);
            // 2. 查找 GLM 标准路径 /api/paas/v4
            idx = apiUrl.indexOf("/api/paas/v4");
            if (idx > 0) return apiUrl.substring(0, idx + 14);
            // 3. 查找通用 /v1 或 /v4 版本路径
            idx = apiUrl.indexOf("/v4/");
            if (idx > 0) return apiUrl.substring(0, idx + 3);
            idx = apiUrl.indexOf("/v1/");
            if (idx > 0) return apiUrl.substring(0, idx + 3);
            // 4. 查找 /api 并保留
            idx = apiUrl.indexOf("/api");
            if (idx > 0) {
                // 保留 /api 及后续路径段（如 /api/paas/v4）
                int nextSlash = apiUrl.indexOf('/', idx + 5);
                if (nextSlash > 0) {
                    int vIdx = apiUrl.indexOf("/v", nextSlash);
                    if (vIdx > 0) return apiUrl.substring(0, vIdx + 3);
                }
                return apiUrl.substring(0, idx + 4);
            }
        }
        return "https://open.bigmodel.cn/api/paas/v4";
    }

    @Override
    public String toString() {
        return "GlmCapture{" +
               "client=" + (okHttpClient != null ? "yes" : "no") +
               ", baseUrl=" + baseUrl +
               ", apiUrl=" + apiUrl +
               ", authToken=" + (authToken != null ? "***" : "null") +
               ", apiKey=" + (apiKey != null ? "***" : "null") +
               ", cookie=" + (cookie != null ? "***" : "null") +
               '}';
    }
}
