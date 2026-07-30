package com.glmkit.probe;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.InputStreamReader;

/**
 * 存储从宿主应用网络层捕获的 GLM 认证信息和 API 端点。
 * 线程安全，所有字段 volatile。
 *
 * v1.0.41: 网关移到 GLMKit APP 进程运行，auth 通过共享文件传递。
 * 模块在智谱清言进程中捕获 auth → saveToSharedFile() → /sdcard/glmkit_auth.json
 * GLMKit APP 的网关 → loadFromSharedFile() → 读取 auth
 */
public class GlmCapture {

    /** 共享 auth 文件路径 — 模块写入，GLMKit APP 网关读取 */
    public static final String SHARED_AUTH_FILE = "/sdcard/glmkit_auth.json";

    private volatile Object okHttpClient;  // okhttp3.OkHttpClient 实例（v1.0.41 后不再需要）
    private volatile String baseUrl;       // Retrofit base URL
    private volatile String apiUrl;        // 最后捕获的 GLM API URL
    private volatile String authToken;     // Authorization 头
    private volatile String apiKey;        // API Key
    private volatile String cookie;        // Cookie 头
    private volatile String deviceId;      // 设备 ID
    private volatile long captureTimestamp; // 捕获时间戳

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
        // v1.0.52: 只接受 bigmodel 域名 (API 端点)，拒绝 chatglm.cn 等网页域名
        if (url != null && !url.isEmpty() && url.toLowerCase().contains("bigmodel")) {
            this.baseUrl = url;
        }
    }

    // ── API URL ───────────────────────────────────────────────
    public String getApiUrl() { return apiUrl; }
    public void setApiUrl(String url) {
        // v1.0.52: 只接受 bigmodel 域名 (API 端点)，拒绝 chatglm.cn 等网页域名
        if (url != null && !url.isEmpty() && url.toLowerCase().contains("bigmodel")) {
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

    public long getCaptureTimestamp() { return captureTimestamp; }

    // ════════════════════════════════════════════════════════════
    //  共享文件读写 — 跨进程传递 auth
    // ════════════════════════════════════════════════════════════

    /**
     * 将捕获的 auth 信息写入共享文件 /sdcard/glmkit_auth.json。
     * 由模块在智谱清言进程中调用。
     */
    public boolean saveToSharedFile() {
        try {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"authToken\":").append(jsonEscape(authToken)).append(",");
            json.append("\"apiKey\":").append(jsonEscape(apiKey)).append(",");
            json.append("\"baseUrl\":").append(jsonEscape(baseUrl)).append(",");
            json.append("\"apiUrl\":").append(jsonEscape(apiUrl)).append(",");
            json.append("\"cookie\":").append(jsonEscape(cookie)).append(",");
            json.append("\"deviceId\":").append(jsonEscape(deviceId)).append(",");
            json.append("\"timestamp\":").append(System.currentTimeMillis());
            json.append("}");

            File file = new File(SHARED_AUTH_FILE);
            FileWriter writer = new FileWriter(file);
            writer.write(json.toString());
            writer.flush();
            writer.close();

            // 设置文件权限，让其他进程可读
            try {
                file.setReadable(true, false);
                file.setWritable(true, false);
            } catch (Throwable ignored) {}

            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 从共享文件 /sdcard/glmkit_auth.json 读取 auth 信息。
     * 由 GLMKit APP 的网关在自身进程中调用。
     *
     * @return true 如果成功读取且 auth 信息有更新
     */
    public boolean loadFromSharedFile() {
        try {
            File file = new File(SHARED_AUTH_FILE);
            if (!file.exists()) return false;

            StringBuilder sb = new StringBuilder();
            FileInputStream fis = new FileInputStream(file);
            InputStreamReader reader = new InputStreamReader(fis, "UTF-8");
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            reader.close();
            fis.close();

            String json = sb.toString();

            String newAuthToken = jsonExtract(json, "authToken");
            String newApiKey = jsonExtract(json, "apiKey");
            String newBaseUrl = jsonExtract(json, "baseUrl");
            String newApiUrl = jsonExtract(json, "apiUrl");
            String newCookie = jsonExtract(json, "cookie");
            String newDeviceId = jsonExtract(json, "deviceId");

            boolean updated = false;

            // 只有当新值非空且当前值为空时才更新（避免覆盖更新的值）
            if (authToken == null && newAuthToken != null) {
                authToken = newAuthToken;
                updated = true;
            }
            if (apiKey == null && newApiKey != null) {
                apiKey = newApiKey;
                updated = true;
            }
            if (baseUrl == null && newBaseUrl != null) {
                baseUrl = newBaseUrl;
                updated = true;
            }
            if (apiUrl == null && newApiUrl != null) {
                apiUrl = newApiUrl;
                updated = true;
            }
            if (cookie == null && newCookie != null) {
                cookie = newCookie;
                updated = true;
            }
            if (deviceId == null && newDeviceId != null) {
                deviceId = newDeviceId;
                updated = true;
            }

            // 解析时间戳
            String tsStr = jsonExtract(json, "timestamp");
            if (tsStr != null) {
                try { captureTimestamp = Long.parseLong(tsStr); } catch (Throwable ignored) {}
            }

            return updated;
        } catch (Throwable t) {
            return false;
        }
    }

    // ── 就绪检查 ──────────────────────────────────────────────
    /**
     * v1.0.49: OkHttpClient 捕获即就绪 (Deekseep 方案 — auth 由拦截器处理)。
     * 兜底：auth 捕获也可就绪 (HttpURLConnection 方案)。
     */
    public boolean isReady() {
        return okHttpClient != null || authToken != null || apiKey != null || cookie != null;
    }

    public String readinessDetail() {
        StringBuilder sb = new StringBuilder();
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
            if (idx > 0) return apiUrl.substring(0, idx + "/api/paas/v4".length());
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

    // ════════════════════════════════════════════════════════════
    //  简易 JSON 工具方法
    // ════════════════════════════════════════════════════════════

    private static String jsonEscape(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
            }
        }
        sb.append("\"");
        return sb.toString();
    }

    /**
     * 从 JSON 字符串中提取指定 key 的字符串值。
     * 简易解析，不处理嵌套对象/数组。
     */
    private static String jsonExtract(String json, String key) {
        String search = "\"" + key + "\":";
        int idx = json.indexOf(search);
        if (idx < 0) return null;
        idx += search.length();

        // 跳过空格
        while (idx < json.length() && json.charAt(idx) == ' ') idx++;
        if (idx >= json.length()) return null;

        // null 值
        if (json.startsWith("null", idx)) return null;

        // 数字值（如 timestamp）
        if (Character.isDigit(json.charAt(idx))) {
            int end = idx;
            while (end < json.length() && (Character.isDigit(json.charAt(end)) || json.charAt(end) == '.')) end++;
            return json.substring(idx, end);
        }

        // 字符串值
        if (json.charAt(idx) != '"') return null;
        idx++; // 跳过开头引号
        StringBuilder sb = new StringBuilder();
        while (idx < json.length()) {
            char c = json.charAt(idx);
            if (c == '\\' && idx + 1 < json.length()) {
                char next = json.charAt(idx + 1);
                switch (next) {
                    case '"':  sb.append('"'); break;
                    case '\\': sb.append('\\'); break;
                    case 'n':  sb.append('\n'); break;
                    case 'r':  sb.append('\r'); break;
                    case 't':  sb.append('\t'); break;
                    default:   sb.append(next); break;
                }
                idx += 2;
                continue;
            }
            if (c == '"') break;
            sb.append(c);
            idx++;
        }
        return sb.toString();
    }
}
