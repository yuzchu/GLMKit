package com.glmkit.probe;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileWriter;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * GLMKit Xposed 模块入口。
 *
 * 目标应用：com.zhipuai.qingyan（智谱清言）
 * 功能：Hook OkHttp 网络层，捕获 GLM 认证信息和 API 端点，
 *       在本地启动 OpenAI 兼容的 API 反代网关。
 *
 * 核心方案（参考 Deekseep）：
 *   1. 先尝试标准 OkHttp 类名 hook
 *   2. 若失败，用 listDexClasses() 枚举所有 dex 类，
 *      按结构签名找到混淆后的 OkHttp Request/Client/Call 类
 *   3. Hook 找到的混淆类捕获认证和请求
 *   4. HttpURLConnection 兜底
 */
public class Main implements IXposedHookLoadPackage {

    static final String TARGET_PACKAGE = "com.zhipuai.qingyan";
    static final String TAG = "GLMKit";

    private static volatile Main INSTANCE;
    private static volatile ClassLoader hostClassLoader;

    // v1.0.75: hook 诊断状态
    public static volatile String hookStatus = "未启动";
    public static volatile String okHttpHookDetail = "";
    public static volatile int captureRequestCount = 0;

    // 捕获的 GLM 网络信息
    private volatile GlmCapture capture;
    private static volatile Context appContext;
    private final AtomicBoolean realCallHooked = new AtomicBoolean(false);

    // 混淆 OkHttp 类缓存（结构扫描结果）
    private volatile Class<?> obfClientClass;
    private volatile Class<?> obfRequestClass;
    private volatile Class<?> obfHeadersClass;

    // ════════════════════════════════════════════════════════════
    //  策略6: SSL Socket 层捕获 — 系统类 hook，不受混淆影响
    // ════════════════════════════════════════════════════════════
    private static final Map<Object, String> glmSockets = new ConcurrentHashMap<>();
    private static final Set<Object> glmStreams = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Class<?>> hookedSocketGetOS = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Class<?>> hookedStreamWrite = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Object, ByteArrayOutputStream> streamBuffers = new ConcurrentHashMap<>();
    // v1.0.59: 诊断日志计数器 — 限制 URL 诊断日志数量
    private static final AtomicInteger diagUrlLogCount = new AtomicInteger(0);
    private static final AtomicInteger diagBodyLogCount = new AtomicInteger(0);

    // ════════════════════════════════════════════════════════════
    //  日志缓冲区 + 文件写入
    // ════════════════════════════════════════════════════════════
    private static final int MAX_LOG_ENTRIES = 500;
    private static final List<String> logBuffer =
            Collections.synchronizedList(new ArrayList<>(MAX_LOG_ENTRIES));
    private static volatile File logFile = null;
    private static volatile PrintWriter logWriter = null;
    private static final SimpleDateFormat logDateFormat =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);

    private static final AtomicBoolean moduleNotified = new AtomicBoolean(false);
    private static final AtomicBoolean gatewayStarted = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        INSTANCE = this;
        hostClassLoader = lpparam.classLoader;
        log("★★★ GLMKit 模块已加载到进程: " + lpparam.packageName + " ★★★");
        log("进程 PID: " + android.os.Process.myPid() + " UID: " + android.os.Process.myUid());

        // 1. Hook Application.attachBaseContext（最早的 Context 入口）
        hookApplicationAttach(lpparam.classLoader);

        // 2. Hook Application.onCreate（备用入口）
        hookApplicationOnCreate(lpparam.classLoader);

        // 3. Hook Activity.onCreate（最后兜底入口）
        hookActivityOnCreate(lpparam.classLoader);

        // 4. 尝试 hook OkHttp（标准名 → 结构扫描 → HttpURLConnection 兜底）
        hookOkHttp(lpparam.classLoader);

        // 5. Hook Retrofit 构建 捕获 base URL
        hookRetrofitBuilder(lpparam.classLoader);

        // 6. SSL Socket 层捕获（最可靠，不依赖任何类名/方法名）
        hookSslSocket(lpparam.classLoader);

        // 7. v1.0.53: Hook RequestBody.create 拦截请求体，提取真实 model ID
        hookRequestBodyCreate(lpparam.classLoader);

        // 8. v1.0.62: Hook WebSocket — 聊天可能用 WebSocket 而非 HTTP
        hookWebSocket(lpparam.classLoader);

        // 9. v1.0.62: Hook RequestBody.writeTo — 直接拦截请求体写入
        hookRequestBodyWriteTo(lpparam.classLoader);

        log("所有 hook 安装完成");
    }

    // ════════════════════════════════════════════════════════════
    //  Application.attachBaseContext — 最早的 Context 入口
    // ════════════════════════════════════════════════════════════
    private void hookApplicationAttach(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", cl, "attachBaseContext", Context.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (appContext == null) {
                                appContext = (Context) param.thisObject;
                                log("✓ Application.attachBaseContext — 获取 Context");
                            }
                            notifyModuleLoaded();
                        } catch (Throwable t) {
                            log("attachBaseContext hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("已 hook Application.attachBaseContext");
        } catch (Throwable t) {
            log("hook Application.attachBaseContext 失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Activity.onCreate — 最后兜底入口
    // ════════════════════════════════════════════════════════════
    private void hookActivityOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Activity", cl, "onCreate", android.os.Bundle.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (appContext == null) {
                                appContext = ((android.app.Activity) param.thisObject).getApplicationContext();
                                log("✓ Activity.onCreate — 获取 Context (兜底入口)");
                            }
                            notifyModuleLoaded();
                        } catch (Throwable t) {
                            log("Activity.onCreate hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("已 hook Activity.onCreate (兜底)");
        } catch (Throwable t) {
            log("hook Activity.onCreate 失败: " + t.getMessage());
        }
    }

    /** 尝试启动网关（确保只启动一次） */
    /**
     * v1.0.49: Deekseep 方案 — 网关直接在智谱清言进程内启动。
     * 用 APP 自己的 OkHttpClient 发请求，auth 由拦截器自动处理。
     */
    private void notifyModuleLoaded() {
        if (moduleNotified.compareAndSet(false, true)) {
            log(">>> 模块已加载，在智谱清言进程内启动网关 <<<");
            showToast("GLMKit 已注入智谱清言");
            broadcastActivation("com.glmkit.proxy.HOOK_STARTED");

            // 初始化日志文件
            if (appContext != null) {
                initLogFile(appContext);
            }

            // v1.0.49: 在宿主进程内启动网关 (Deekseep 方案)
            startGatewayInHost();
        }
    }

    /** v1.0.49: 在智谱清言进程内启动 API 网关 */
    private void startGatewayInHost() {
        if (!gatewayStarted.compareAndSet(false, true)) return;
        if (appContext == null) {
            log("无法启动网关：appContext 为 null");
            gatewayStarted.set(false);
            return;
        }

        // v1.0.73: 端口优先级: 自定义端口 > user ID 偏移 > 默认 16766
        int userId = android.os.Process.myUid() / 100000;
        String processName = readProcessName();
        String pkgName = appContext.getPackageName();

        // 1) 先读 SharedPreferences 自定义端口（用户在设置页配置的）
        //    注意: SettingsActivity 是模块自己的 Activity，SharedPreferences 存在 com.glmkit.probe 包下
        //    必须用模块包名创建 context 才能读到，不能用宿主 appContext
        int customPort = 0;
        String portSource = "默认";
        try {
            android.content.Context modCtx = appContext.createPackageContext("com.glmkit.probe", android.content.Context.CONTEXT_IGNORE_SECURITY);
            SharedPreferences prefs = modCtx.getSharedPreferences("glmkit_settings", android.content.Context.MODE_PRIVATE);
            customPort = prefs.getInt("port", 0);
            log("读取模块 SharedPreferences: port=" + customPort + " (com.glmkit.probe)");
        } catch (Throwable t) {
            log("读取模块 SharedPreferences 失败: " + t.getMessage());
        }

        int port;
        if (customPort >= 1024 && customPort <= 65535) {
            // 用户设了自定义端口，直接用
            port = customPort;
            portSource = "自定义";
        } else {
            // 没设自定义端口，按 user ID 偏移
            int portOffset = userId;
            String processSuffix = null;
            if (processName != null && !processName.equals(pkgName) && processName.startsWith(pkgName + ":")) {
                processSuffix = processName.substring(pkgName.length() + 1);
                int suffixHash = Math.abs(processSuffix.hashCode()) % 1000;
                portOffset += 10000 + suffixHash;
            }
            port = 16766 + portOffset;
            portSource = userId > 0 ? "user偏移" : "默认";
        }

        LocalApiGateway.setListenPort(port);
        log("╔══ 分身诊断 ═══════════════════════════════");
        log("║ UID=" + android.os.Process.myUid() + " | userId=" + userId);
        log("║ 进程名=" + processName);
        log("║ 包名=" + pkgName);
        log("║ 自定义端口=" + (customPort > 0 ? customPort : "未设置"));
        log("║ 最终端口=" + port + " (" + portSource + ")");
        log("╚══════════════════════════════════════════════");

        try {
            GlmBackend backend = new GlmBackend(getCapture());
            int actualPort = LocalApiGateway.start(appContext, backend);
            if (actualPort > 0) {
                log("★★★ 网关已启动 端口:" + actualPort + " (userId=" + userId + ") ★★★");
                showToast("GLMKit 网关已启动 (端口 " + actualPort + ")");
                // v1.0.73: 写端口文件到 /sdcard/，方便查找分身端口
                try {
                    String portFile = "/sdcard/glmkit_port_" + actualPort + ".txt";
                    java.io.FileWriter fw = new java.io.FileWriter(portFile);
                    fw.write("port=" + actualPort + "\nuserId=" + userId + "\nprocess=" + processName + "\ntime=" + System.currentTimeMillis() + "\n");
                    fw.close();
                    log("端口文件已写入: " + portFile);
                } catch (Throwable t) {
                    log("写端口文件失败: " + t.getMessage());
                }
            } else {
                log("网关启动失败，端口 <= 0");
                gatewayStarted.set(false);
            }
        } catch (Throwable t) {
            log("启动网关异常: " + t.getMessage());
            gatewayStarted.set(false);
        }
    }

    /** 读取当前进程名 (兼容 API 24) */
    private String readProcessName() {
        try {
            // 方法1: ApplicationInfo
            if (appContext != null) {
                return appContext.getApplicationInfo().processName;
            }
        } catch (Throwable ignored) {}
        try {
            // 方法2: /proc/self/cmdline
            java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream("/proc/self/cmdline")));
            String line = br.readLine();
            br.close();
            if (line != null) return line.trim();
        } catch (Throwable ignored) {}
        return "unknown";
    }

    // ════════════════════════════════════════════════════════════
    //  Application.onCreate — 获取宿主 Context
    // ════════════════════════════════════════════════════════════
    private void hookApplicationOnCreate(ClassLoader cl) {
        try {
            XposedHelpers.findAndHookMethod(
                "android.app.Application", cl, "onCreate",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            if (appContext == null) {
                                appContext = (Context) param.thisObject;
                                log("✓ Application.onCreate — 获取 Context");
                            }
                            notifyModuleLoaded();
                        } catch (Throwable t) {
                            log("Application.onCreate hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("已 hook Application.onCreate");
        } catch (Throwable t) {
            log("hook Application.onCreate 失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  OkHttp Hook — 多策略递进
    // ════════════════════════════════════════════════════════════
    private void hookOkHttp(ClassLoader cl) {
        log("hookOkHttp 开始, classLoader=" + cl.getClass().getName());
        // v1.0.49 策略0: 直接 hook 已知混淆类名 (智谱清言 v3.7.0 OkHttp 映射)
        // 最快最可靠 — 不需要 dex 扫描
        if (hookObfuscatedOkHttpDirect(cl)) { hookStatus = "策略0:混淆直hook"; return; }
        // 策略1: okhttp3.OkHttpClient$Builder (标准 OkHttp 3.x/4.x)
        if (tryHookOkHttp3Builder(cl)) { hookStatus = "策略1:Builder.build"; return; }
        // 策略2: okhttp3.OkHttpClient.newCall() 直接 hook
        if (tryHookOkHttp3NewCall(cl)) { hookStatus = "策略2:newCall"; return; }
        // 策略3: com.squareup.okhttp (OkHttp 2.x)
        if (tryHookOkHttp2(cl)) { hookStatus = "策略3:okhttp2"; return; }
        hookStatus = "策略5:URLConn(降级)";
        // 策略5: HttpURLConnection 兜底（立即安装，非阻塞）
        hookUrlConnection(cl);
        // 策略4: dex 结构扫描（异步执行，避免 ANR）
        new Thread(() -> {
            try {
                tryHookObfuscatedOkHttp(cl);
            } catch (Throwable t) {
                log("策略4 异步扫描异常: " + t.getMessage());
            }
        }, "glmkit-dex-scan").start();
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.49 策略0: 直接 hook 已知混淆类名 (智谱清言 v3.7.0)
    //  nu.OkHttpClient.b(nu.Request) → newCall(Request)
    //  Deekseep 方案：捕获 client 实例，用其拦截器自动处理 auth
    // ════════════════════════════════════════════════════════════

    private boolean hookObfuscatedOkHttpDirect(ClassLoader cl) {
        try {
            log("策略0: 尝试加载 nu.OkHttpClient / nu.Request...");
            Class<?> clientClass = cl.loadClass("nu.OkHttpClient");
            Class<?> requestClass = cl.loadClass("nu.Request");
            log("策略0: 类加载成功, client=" + clientClass.getName() + " request=" + requestClass.getName());

            XposedHelpers.findAndHookMethod(
                clientClass, "b", requestClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        // 捕获 OkHttpClient 实例
                        if (getCapture().getOkHttpClient() == null) {
                            getCapture().setOkHttpClient(param.thisObject);
                            log("✓✓ 捕获混淆 OkHttpClient (nu.OkHttpClient) — Deekseep 方案 ★★");
                            showToast("GLMKit 已捕获网络层");
                            broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                            // v1.0.55: 策略0也要安装 RealCall hook（捕获 auth 从 Response.request）
                            installCaptureInterceptor(param.thisObject, cl);
                        }
                        // 提取请求 URL（用于确定 API 端点）
                        try {
                            extractObfuscatedRequestUrl(param.args[0]);
                        } catch (Throwable ignored) {}
                    }
                });
            log("✓ 策略0: Hook nu.OkHttpClient.b(nu.Request) 成功");
            return true;
        } catch (Throwable t) {
            log("策略0: Hook nu.OkHttpClient.b 失败: " + t.getMessage());
            return false;
        }
    }

    /** 从混淆 Request 提取 URL 和 auth: nu.Request.k() → nu.u, nu.Request.d(String) → String */
    private void extractObfuscatedRequestUrl(Object request) {
        try {
            // nu.Request.k() → nu.u (HttpUrl)
            Method urlMethod = request.getClass().getMethod("k");
            Object httpUrl = urlMethod.invoke(request);
            if (httpUrl == null) return;
            String urlStr = httpUrl.toString();

            // v1.0.75: 记录所有请求 URL（前20条，帮助诊断）
            if (captureRequestCount < 20) {
                log("请求 URL: " + urlStr);
            }

            if (isGlmApiUrl(urlStr)) {
                captureRequestCount++;
                // v1.0.59: 接受所有 GLM 相关域名作为 API URL (bigmodel, chatglm, zhipuai, qingyan)
                getCapture().setApiUrl(urlStr);
                if (getCapture().getBaseUrl() == null) {
                    log("捕获 GLM API URL (混淆): " + urlStr);
                }
                // v1.0.59: 从所有 GLM API 请求提取 auth (不再限制 bigmodel)
                extractAuthFromObfuscatedRequest(request);
            }
        } catch (Throwable ignored) {}
    }

    /** v1.0.51: 从混淆 Request 提取 Authorization / x-api-key / Cookie */
    private void extractAuthFromObfuscatedRequest(Object request) {
        try {
            // nu.Request.d(String) → String (读取头)
            Method headerMethod = request.getClass().getMethod("d", String.class);

            String auth = (String) headerMethod.invoke(request, "Authorization");
            if (auth != null && !auth.isEmpty()) {
                String old = getCapture().getAuthToken();
                getCapture().setAuthToken(auth);
                if (old == null || !old.equals(auth)) {
                    log("✓ 捕获 Authorization (混淆 Request): " + auth.substring(0, Math.min(30, auth.length())) + "...");
                }
            } else {
                log("⚠ Request 无 Authorization 头 (d 方法返回 null)");
            }

            String apiKey = (String) headerMethod.invoke(request, "x-api-key");
            if (apiKey != null && !apiKey.isEmpty()) {
                getCapture().setApiKey(apiKey);
                log("✓ 捕获 x-api-key (混淆 Request)");
            }

            String cookie = (String) headerMethod.invoke(request, "Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                getCapture().setCookie(cookie);
            }
        } catch (Throwable t) {
            log("⚠ extractAuth 异常: " + t.getMessage());
        }
    }

    private boolean tryHookOkHttp3Builder(ClassLoader cl) {
        try {
            Class<?> builderClass = cl.loadClass("okhttp3.OkHttpClient$Builder");
            XposedHelpers.findAndHookMethod(
                builderClass, "build",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object client = param.result;
                        if (client == null) return;
                        getCapture().setOkHttpClient(client);
                        log("捕获 OkHttpClient 实例 (Builder.build)");
                        showToast("GLMKit 已捕获网络层");
                        broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                        installCaptureInterceptor(client, cl);
                    }
                });
            log("Hook OkHttpClient.Builder.build() 成功 (策略1)");
            return true;
        } catch (Throwable t) {
            log("策略1失败 (okhttp3.Builder): " + t.getMessage());
            return false;
        }
    }

    /** 策略2: hook okhttp3.OkHttpClient.newCall() 直接捕获请求 */
    private boolean tryHookOkHttp3NewCall(ClassLoader cl) {
        try {
            Class<?> clientClass = cl.loadClass("okhttp3.OkHttpClient");
            Class<?> requestClass = cl.loadClass("okhttp3.Request");
            XposedHelpers.findAndHookMethod(
                clientClass, "newCall", requestClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (getCapture().getOkHttpClient() == null) {
                            getCapture().setOkHttpClient(param.thisObject);
                            log("捕获 OkHttpClient 实例 (newCall)");
                            showToast("GLMKit 已捕获网络层");
                            broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                        }
                        try {
                            extractRequestDetails(param.args[0], cl);
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook OkHttpClient.newCall() 成功 (策略2)");
            return true;
        } catch (Throwable t) {
            log("策略2失败 (okhttp3.newCall): " + t.getMessage());
            return false;
        }
    }

    /** 策略3: OkHttp 2.x (com.squareup.okhttp) */
    private boolean tryHookOkHttp2(ClassLoader cl) {
        try {
            Class<?> clientClass = cl.loadClass("com.squareup.okhttp.OkHttpClient");
            Class<?> requestClass = cl.loadClass("com.squareup.okhttp.Request");
            XposedHelpers.findAndHookMethod(
                clientClass, "newCall", requestClass,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        if (getCapture().getOkHttpClient() == null) {
                            getCapture().setOkHttpClient(param.thisObject);
                            log("捕获 OkHttp2 客户端 (newCall)");
                            showToast("GLMKit 已捕获网络层");
                            broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                        }
                        try {
                            Object request = param.args[0];
                            Method urlMethod = request.getClass().getMethod("url");
                            Object urlObj = urlMethod.invoke(request);
                            String urlStr = urlObj.toString();
                            if (isGlmApiUrl(urlStr)) {
                                getCapture().setApiUrl(urlStr);
                                log("捕获 GLM API URL: " + urlStr);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook OkHttp2.newCall() 成功 (策略3)");
            return true;
        } catch (Throwable t) {
            log("策略3失败 (okhttp2): " + t.getMessage());
            return false;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  策略4: dex 结构扫描 — 找混淆后的 OkHttp 类（核心新增！）
    //  参考 Deekseep 的 listDexClasses() + findTransportByStructure()
    // ════════════════════════════════════════════════════════════

    /**
     * 枚举所有 dex 类名（参考 Deekseep）。
     * 通过反射 BaseDexClassLoader.pathList.dexElements[].dexFile.entries() 获取。
     */
    @SuppressWarnings("unchecked")
    private List<String> listDexClasses(ClassLoader cl) throws Exception {
        ArrayList<String> out = new ArrayList<>();
        Class<?> bdcl = Class.forName("dalvik.system.BaseDexClassLoader");
        Field plF = bdcl.getDeclaredField("pathList");
        plF.setAccessible(true);
        Object pl = plF.get(cl);
        Field deF = pl.getClass().getDeclaredField("dexElements");
        deF.setAccessible(true);
        Object[] els = (Object[]) deF.get(pl);
        for (Object el : els) {
            Field dfF = el.getClass().getDeclaredField("dexFile");
            dfF.setAccessible(true);
            Object df = dfF.get(el);
            if (df == null) continue;
            Method entries = df.getClass().getDeclaredMethod("entries");
            entries.setAccessible(true);
            Enumeration<String> en = (Enumeration<String>) entries.invoke(df);
            while (en.hasMoreElements()) out.add(en.nextElement());
        }
        return out;
    }

    /**
     * 按结构签名找混淆后的 OkHttp 类。
     *
     * OkHttp 结构签名：
     * - Request 类：有 url() 和 headers() 方法，headers() 返回类型有 size()/name(int)/value(int)
     * - OkHttpClient 类：有 newCall(Request) 方法返回非 void
     *
     * R8 混淆后类名变了，但方法名通常保留（OkHttp 公共 API）。
     * 即使方法名也被混淆，我们通过参数/返回值结构匹配。
     */
    private boolean tryHookObfuscatedOkHttp(ClassLoader cl) {
        log("策略4: 开始 dex 结构扫描找混淆 OkHttp 类...");
        showToast("GLMKit: 扫描 dex 类结构中...");

        try {
            List<String> allClasses = listDexClasses(cl);
            log("dex 类总数: " + allClasses.size());

            Class<?> requestClass = null;
            Class<?> headersClass = null;
            Class<?> clientClass = null;
            Method newCallMethod = null;

            // 第一步：找 Headers 类 — 有 size() 返回 int, name(int) 返回 String, value(int) 返回 String
            for (String className : allClasses) {
                if (className == null || className.startsWith("android.")
                        || className.startsWith("java.") || className.startsWith("kotlin")
                        || className.startsWith("org.json") || className.startsWith("com.google")
                        || className.startsWith("com.android")) continue;

                Class<?> c;
                try {
                    c = Class.forName(className, false, cl);
                } catch (Throwable t) { continue; }

                if (isHeadersClass(c)) {
                    headersClass = c;
                    log("[SCAN] 找到 Headers 类: " + className);
                    break;
                }
            }

            // 第二步：找 Request 类 — 有 url() 和 headers() 方法
            if (headersClass != null) {
                for (String className : allClasses) {
                    if (className == null || className.startsWith("android.")
                            || className.startsWith("java.") || className.startsWith("kotlin")) continue;

                    Class<?> c;
                    try {
                        c = Class.forName(className, false, cl);
                    } catch (Throwable t) { continue; }

                    if (isRequestClass(c, headersClass)) {
                        requestClass = c;
                        log("[SCAN] 找到 Request 类: " + className);
                        break;
                    }
                }
            }

            // 第三步：找 OkHttpClient 类 — 有 newCall(Request) 方法
            if (requestClass != null) {
                for (String className : allClasses) {
                    if (className == null || className.startsWith("android.")
                            || className.startsWith("java.") || className.startsWith("kotlin")) continue;

                    Class<?> c;
                    try {
                        c = Class.forName(className, false, cl);
                    } catch (Throwable t) { continue; }

                    Method newCall = findNewCallMethod(c, requestClass);
                    if (newCall != null) {
                        clientClass = c;
                        newCallMethod = newCall;
                        log("[SCAN] 找到 OkHttpClient 类: " + className
                                + " newCall: " + newCall.getName());
                        break;
                    }
                }
            }

            // 如果通过 Headers→Request→Client 链未找到，尝试直接找 newCall 方法
            if (clientClass == null) {
                log("[SCAN] 链式扫描未找到，尝试直接扫描 newCall 方法...");
                for (String className : allClasses) {
                    if (className == null || className.startsWith("android.")
                            || className.startsWith("java.") || className.startsWith("kotlin")
                            || className.startsWith("org.json") || className.startsWith("com.google")) continue;

                    Class<?> c;
                    try {
                        c = Class.forName(className, false, cl);
                    } catch (Throwable t) { continue; }

                    // 找 newCall(X) 方法，X 有 url() 和 headers() 方法
                    for (Method m : c.getDeclaredMethods()) {
                        if (m.getParameterTypes().length != 1) continue;
                        Class<?> paramType = m.getParameterTypes()[0];
                        if (m.getReturnType() == void.class) continue;

                        // 检查参数类型是否有 url() 和 headers() 方法
                        if (hasUrlAndHeaders(paramType)) {
                            clientClass = c;
                            requestClass = paramType;
                            newCallMethod = m;
                            log("[SCAN] 直接扫描找到: " + className + "."
                                    + m.getName() + "(" + paramType.getName() + ")");
                            break;
                        }
                    }
                    if (clientClass != null) break;
                }
            }

            if (clientClass != null && requestClass != null && newCallMethod != null) {
                // 找到了！Hook 混淆的 OkHttp 类
                obfClientClass = clientClass;
                obfRequestClass = requestClass;
                if (headersClass != null) obfHeadersClass = headersClass;

                hookFoundOkHttp(clientClass, requestClass, newCallMethod, cl);
                return true;
            }

            log("策略4: dex 结构扫描未找到 OkHttp 类");
            return false;

        } catch (Throwable t) {
            log("策略4 扫描失败: " + t.getMessage());
            return false;
        }
    }

    /** 检查类是否是 OkHttp Headers：有 size()->int, name(int)->String, value(int)->String */
    private boolean isHeadersClass(Class<?> c) {
        try {
            Method size = null, name = null, value = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("size") && m.getParameterTypes().length == 0
                        && m.getReturnType() == int.class) size = m;
                if (m.getName().equals("name") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class
                        && m.getReturnType() == String.class) name = m;
                if (m.getName().equals("value") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class
                        && m.getReturnType() == String.class) value = m;
            }
            return size != null && name != null && value != null;
        } catch (Throwable t) { return false; }
    }

    /** 检查类是否是 OkHttp Request：有 url() 和 headers() 方法，headers() 返回 Headers 类型 */
    private boolean isRequestClass(Class<?> c, Class<?> headersClass) {
        try {
            Method urlMethod = null, headersMethod = null;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("url") && m.getParameterTypes().length == 0) urlMethod = m;
                if (m.getName().equals("headers") && m.getParameterTypes().length == 0
                        && m.getReturnType() == headersClass) headersMethod = m;
            }
            return urlMethod != null && headersMethod != null;
        } catch (Throwable t) { return false; }
    }

    /** 检查类是否有 url() 和 headers() 方法（不检查 headers 返回类型） */
    private boolean hasUrlAndHeaders(Class<?> c) {
        try {
            boolean hasUrl = false, hasHeaders = false;
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("url") && m.getParameterTypes().length == 0) hasUrl = true;
                if (m.getName().equals("headers") && m.getParameterTypes().length == 0) hasHeaders = true;
            }
            return hasUrl && hasHeaders;
        } catch (Throwable t) { return false; }
    }

    /** 在类中找 newCall(Request) 方法 */
    private Method findNewCallMethod(Class<?> c, Class<?> requestClass) {
        try {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals("newCall") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == requestClass
                        && m.getReturnType() != void.class) {
                    return m;
                }
            }
        } catch (Throwable t) {}
        return null;
    }

    /** Hook 找到的混淆 OkHttp 类的 newCall 方法 */
    private void hookFoundOkHttp(Class<?> clientClass, Class<?> requestClass,
                                  Method newCallMethod, ClassLoader cl) {
        try {
            Class<?>[] ncParamTypes = newCallMethod.getParameterTypes();
            Object[] ncHookArgs = new Object[ncParamTypes.length + 1];
            for (int i = 0; i < ncParamTypes.length; i++) ncHookArgs[i] = ncParamTypes[i];
            ncHookArgs[ncParamTypes.length] = new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    // 捕获 client 实例
                    if (getCapture().getOkHttpClient() == null) {
                        getCapture().setOkHttpClient(param.thisObject);
                        log("捕获混淆 OkHttpClient 实例: " + clientClass.getName());
                        showToast("GLMKit 已捕获网络层（结构扫描）");
                        broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                    }
                    // 捕获请求详情
                    try {
                        extractRequestDetailsGeneric(param.args[0]);
                    } catch (Throwable ignored) {}
                }
            };
            XposedHelpers.findAndHookMethod(clientClass, newCallMethod.getName(), ncHookArgs);
            log("✓ 策略4成功: Hook 混淆 OkHttp newCall: "
                    + clientClass.getName() + "." + newCallMethod.getName());
        } catch (Throwable t) {
            log("Hook 混淆 OkHttp 失败: " + t.getMessage());
        }
    }

    /** 策略5: HttpURLConnection 兜底 — 捕获 URL 和认证头 */
    private void hookUrlConnection(ClassLoader cl) {
        log("使用策略5: HttpURLConnection 兜底");
        showToast("GLMKit: OkHttp 未找到，使用备用捕获方案");

        try {
            XposedHelpers.findAndHookMethod(
                "java.net.HttpURLConnection", cl, "setRequestProperty",
                String.class, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        try {
                            String name = (String) param.args[0];
                            String value = (String) param.args[1];
                            if (name == null) return;
                            String ln = name.toLowerCase();

                            HttpURLConnection conn = (HttpURLConnection) param.thisObject;
                            String urlStr = conn.getURL().toString();
                            if (!isGlmApiUrl(urlStr)) return;

                            getCapture().setApiUrl(urlStr);
                            log("捕获 GLM API URL (HttpURLConnection): " + urlStr);

                            if ("authorization".equals(ln)) {
                                getCapture().setAuthToken(value);
                                log("捕获 Authorization (HttpURLConnection)");
                                saveAuthAndNotify();
                            } else if (ln.contains("token") || ln.contains("api-key") || ln.contains("apikey")) {
                                getCapture().setApiKey(value);
                                log("捕获 API Key (HttpURLConnection)");
                                saveAuthAndNotify();
                            } else if (ln.contains("cookie")) {
                                getCapture().setCookie(value);
                                log("捕获 Cookie (HttpURLConnection)");
                                saveAuthAndNotify();
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook HttpURLConnection.setRequestProperty 成功");
        } catch (Throwable t) {
            log("Hook HttpURLConnection 失败: " + t.getMessage());
        }

        try {
            XposedHelpers.findAndHookMethod(
                "java.net.URL", cl, "openConnection",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String urlStr = param.thisObject.toString();
                            if (isGlmApiUrl(urlStr)) {
                                getCapture().setApiUrl(urlStr);
                                log("捕获 GLM API URL (URL.openConnection): " + urlStr);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook URL.openConnection 成功");
        } catch (Throwable t) {
            log("Hook URL.openConnection 失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  Retrofit.Builder.build() — 捕获 Base URL
    // ════════════════════════════════════════════════════════════
    private void hookRetrofitBuilder(ClassLoader cl) {
        try {
            Class<?> retrofitBuilderClass = cl.loadClass("retrofit2.Retrofit$Builder");
            XposedHelpers.findAndHookMethod(
                retrofitBuilderClass, "build",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object retrofit = param.result;
                        if (retrofit == null) return;
                        try {
                            Method baseUrlMethod = retrofit.getClass().getMethod("baseUrl");
                            Object httpUrl = baseUrlMethod.invoke(retrofit);
                            if (httpUrl != null) {
                                String url = httpUrl.toString();
                                getCapture().setBaseUrl(url);
                                log("捕获 Retrofit Base URL: " + url);
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook Retrofit.Builder.build() 成功");
        } catch (Throwable t) {
            log("hook Retrofit Builder 失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  策略6: SSL Socket 层捕获 — 从原始 HTTP 请求字节提取 auth
    //  最可靠的捕获方式：hook 系统类 javax.net.ssl / java.net.Socket
    //  不依赖任何混淆类名/方法名，直接在 SSL 层拦截原始 HTTP 请求
    // ════════════════════════════════════════════════════════════

    private void hookSslSocket(ClassLoader cl) {
        log("策略6: 安装 SSL Socket 层捕获...");

        // 6a. Hook SSLSocketFactory.createSocket(Socket, String, int, boolean)
        //     OkHttp 用此方法创建 SSL 连接，host 参数是目标主机名
        try {
            XposedHelpers.findAndHookMethod(
                "javax.net.ssl.SSLSocketFactory", cl, "createSocket",
                Socket.class, String.class, int.class, boolean.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String host = (String) param.args[1];
                            if (host != null && isGlmHost(host)) {
                                Socket socket = (Socket) param.result;
                                glmSockets.put(socket, host);
                                log("[SSL] 捕获 GLM API SSL 连接: " + host);
                                hookSocketGetOutputStream(socket.getClass(), cl);
                            }
                        } catch (Throwable t) {
                            log("[SSL] createSocket hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook SSLSocketFactory.createSocket(Socket,String,int,boolean) 成功");
        } catch (Throwable t) {
            log("[SSL] Hook createSocket(Socket,String,int,boolean) 失败: " + t.getMessage());
        }

        // 6b. Hook SSLSocketFactory.createSocket(String, int) — 无包装版本
        try {
            XposedHelpers.findAndHookMethod(
                "javax.net.ssl.SSLSocketFactory", cl, "createSocket",
                String.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String host = (String) param.args[0];
                            if (host != null && isGlmHost(host)) {
                                Socket socket = (Socket) param.result;
                                glmSockets.put(socket, host);
                                log("[SSL] 捕获 GLM API SSL 连接(直连): " + host);
                                hookSocketGetOutputStream(socket.getClass(), cl);
                            }
                        } catch (Throwable t) {
                            log("[SSL] createSocket(String,int) hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook SSLSocketFactory.createSocket(String,int) 成功");
        } catch (Throwable t) {
            log("[SSL] Hook createSocket(String,int) 失败: " + t.getMessage());
        }

        // 6c. Hook Socket.connect(SocketAddress, int) — 通用兜底
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.Socket", cl, "connect",
                SocketAddress.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Socket socket = (Socket) param.thisObject;
                            SocketAddress addr = (SocketAddress) param.args[0];
                            if (addr instanceof InetSocketAddress) {
                                InetSocketAddress inet = (InetSocketAddress) addr;
                                String host = inet.getHostName();
                                if (host != null && isGlmHost(host)) {
                                    glmSockets.put(socket, host);
                                    log("[SSL] 捕获 GLM API Socket.connect: " + host);
                                    hookSocketGetOutputStream(socket.getClass(), cl);
                                }
                            }
                        } catch (Throwable t) {
                            log("[SSL] connect hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook Socket.connect(addr,int) 成功");
        } catch (Throwable t) {
            log("[SSL] Hook Socket.connect(addr,int) 失败: " + t.getMessage());
        }

        // 6d. Hook Socket.connect(SocketAddress) — 无超时版本
        try {
            XposedHelpers.findAndHookMethod(
                "java.net.Socket", cl, "connect",
                SocketAddress.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Socket socket = (Socket) param.thisObject;
                            SocketAddress addr = (SocketAddress) param.args[0];
                            if (addr instanceof InetSocketAddress) {
                                InetSocketAddress inet = (InetSocketAddress) addr;
                                String host = inet.getHostName();
                                if (host != null && isGlmHost(host)) {
                                    glmSockets.put(socket, host);
                                    log("[SSL] 捕获 GLM API Socket.connect(无超时): " + host);
                                    hookSocketGetOutputStream(socket.getClass(), cl);
                                }
                            }
                        } catch (Throwable t) {
                            log("[SSL] connect(无超时) hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook Socket.connect(addr) 成功");
        } catch (Throwable t) {
            log("[SSL] Hook Socket.connect(addr) 失败: " + t.getMessage());
        }

        // 6e. Hook Socket.getOutputStream() — 基类（兜底）
        hookSocketGetOutputStream(Socket.class, cl);

        log("策略6: SSL Socket 层捕获安装完成");
    }

    /** 动态 hook Socket.getOutputStream() — 在具体 socket 实现类上 */
    private void hookSocketGetOutputStream(Class<?> socketClass, ClassLoader cl) {
        if (!hookedSocketGetOS.add(socketClass)) return;
        try {
            XposedHelpers.findAndHookMethod(
                socketClass, "getOutputStream",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object socket = param.thisObject;
                            if (!glmSockets.containsKey(socket)) return;

                            OutputStream os = (OutputStream) param.result;
                            if (os == null) return;
                            if (glmStreams.add(os)) {
                                String host = glmSockets.get(socket);
                                log("[SSL] 获取 GLM API OutputStream: " + host
                                    + " osClass=" + os.getClass().getName());
                                hookOutputStreamWrite(os.getClass(), cl);
                            }
                        } catch (Throwable t) {
                            log("[SSL] getOutputStream hook 异常: " + t.getMessage());
                        }
                    }
                });
            if (socketClass != Socket.class) {
                log("[SSL] Hook getOutputStream on " + socketClass.getName() + " 成功");
            }
        } catch (Throwable t) {
            if (socketClass != Socket.class) {
                log("[SSL] Hook getOutputStream on " + socketClass.getName() + " 失败: " + t.getMessage());
            }
        }
    }

    /** 动态 hook OutputStream.write() — 在具体 OutputStream 实现类上 */
    private void hookOutputStreamWrite(Class<?> osClass, ClassLoader cl) {
        if (!hookedStreamWrite.add(osClass)) return;

        // Hook write(byte[], int, int)
        try {
            XposedHelpers.findAndHookMethod(
                osClass, "write", byte[].class, int.class, int.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object os = param.thisObject;
                            if (!glmStreams.contains(os)) return;
                            byte[] data = (byte[]) param.args[0];
                            int off = (int) param.args[1];
                            int len = (int) param.args[2];
                            captureHttpHeaders(os, data, off, len);
                        } catch (Throwable t) {
                            log("[SSL] write(b[],int,int) hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook write(byte[],int,int) on " + osClass.getName() + " 成功");
        } catch (Throwable t) {
            log("[SSL] Hook write(byte[],int,int) on " + osClass.getName() + " 失败: " + t.getMessage());
        }

        // Hook write(byte[])
        try {
            XposedHelpers.findAndHookMethod(
                osClass, "write", byte[].class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            Object os = param.thisObject;
                            if (!glmStreams.contains(os)) return;
                            byte[] data = (byte[]) param.args[0];
                            captureHttpHeaders(os, data, 0, data.length);
                        } catch (Throwable t) {
                            log("[SSL] write(byte[]) hook 异常: " + t.getMessage());
                        }
                    }
                });
            log("[SSL] Hook write(byte[]) on " + osClass.getName() + " 成功");
        } catch (Throwable t) {
            // write(byte[]) 可能未被覆写，忽略
        }
    }

    /** 缓冲 OutputStream 数据，检测 HTTP 请求头结束标记 \r\n\r\n */
    private void captureHttpHeaders(Object os, byte[] data, int off, int len) {
        if (len <= 0) return;

        ByteArrayOutputStream buf = streamBuffers.get(os);
        if (buf == null) {
            buf = new ByteArrayOutputStream();
            streamBuffers.put(os, buf);
        }

        buf.write(data, off, len);

        byte[] all = buf.toByteArray();
        if (all.length > 65536) {
            streamBuffers.remove(os);
            return;
        }

        // 检测 HTTP 头结束标记 \r\n\r\n
        String text = new String(all, 0, all.length, StandardCharsets.UTF_8);
        int headerEnd = text.indexOf("\r\n\r\n");
        if (headerEnd < 0) return;

        // 找到完整头部，解析
        streamBuffers.remove(os);
        parseHttpRequestHeaders(text.substring(0, headerEnd));
    }

    /** 从原始 HTTP 请求头提取 auth 信息 */
    private void parseHttpRequestHeaders(String headers) {
        String[] lines = headers.split("\r\n");
        if (lines.length == 0) return;

        String requestLine = lines[0];
        log("[SSL] ★ HTTP 请求: " + requestLine);

        String path = null;
        String[] parts = requestLine.split(" ");
        if (parts.length >= 2) path = parts[1];

        String host = null;
        String authorization = null;
        String cookie = null;
        String apiKey = null;

        for (int i = 1; i < lines.length; i++) {
            String line = lines[i];
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase();
            String value = line.substring(colon + 1).trim();

            switch (name) {
                case "host": host = value; break;
                case "authorization": authorization = value; break;
                case "cookie": cookie = value; break;
                case "x-api-key":
                case "apikey":
                    apiKey = value; break;
            }
        }

        // 构建 API URL
        if (host != null && path != null) {
            String fullUrl = "https://" + host + path;
            getCapture().setApiUrl(fullUrl);
            log("[SSL] 捕获 API URL: " + fullUrl);
        }

        // 保存 auth
        boolean authChanged = false;
        if (authorization != null && !authorization.isEmpty()) {
            getCapture().setAuthToken(authorization);
            log("[SSL] ✓✓ 捕获 Authorization (len=" + authorization.length() + ") ★★★");
            authChanged = true;
        }
        if (apiKey != null && !apiKey.isEmpty()) {
            getCapture().setApiKey(apiKey);
            log("[SSL] ✓✓ 捕获 API Key ★★★");
            authChanged = true;
        }
        if (cookie != null && !cookie.isEmpty()) {
            getCapture().setCookie(cookie);
            log("[SSL] ✓✓ 捕获 Cookie ★★★");
            authChanged = true;
        }

        if (authChanged) {
            saveAuthAndNotify();
            broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
        }
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.62: Hook WebSocket — 聊天可能用 WebSocket 而非 HTTP
    // ════════════════════════════════════════════════════════════
    private void hookWebSocket(ClassLoader cl) {
        // hook RealWebSocket.send(String) — 拦截发出的 WebSocket 文本消息
        for (String className : new String[]{
                "okhttp3.internal.ws.RealWebSocket",
                "nu.aq", "nu.ar", "nu.as", "nu.at", "nu.au"}) {
            try {
                Class<?> wsClass = cl.loadClass(className);
                XposedHelpers.findAndHookMethod(wsClass, "send", String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void beforeHookedMethod(MethodHookParam param) {
                            try {
                                String msg = (String) param.args[0];
                                if (msg == null || msg.length() < 5) return;
                                if (diagBodyLogCount.getAndIncrement() < 10) {
                                    log("[DIAG] WebSocket.send (" + Math.min(300, msg.length()) + " chars): " +
                                        msg.substring(0, Math.min(300, msg.length())));
                                }
                                // 提取 assistant_id 或 model
                                String model = extractModelFromJson(msg);
                                if (model != null && !model.isEmpty()) {
                                    String old = getCapture().getCapturedModel();
                                    getCapture().setCapturedModel(model);
                                    if (old == null || !old.equals(model)) {
                                        log("★★★ 捕获模型 ID (WebSocket.send): " + model);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                log("✓ 安装 WebSocket.send hook (" + className + ")");
                break;
            } catch (Throwable ignored) {}
        }

        // hook WebSocketListener.onMessage — 拦截收到的 WebSocket 消息
        for (String className : new String[]{
                "okhttp3.WebSocketListener",
                "nu.ap", "nu.aq", "nu.ar"}) {
            try {
                Class<?> listenerClass = cl.loadClass(className);
                Class<?> wsInterface = null;
                for (String wsName : new String[]{"okhttp3.WebSocket", "nu.aq", "nu.ar"}) {
                    try { wsInterface = cl.loadClass(wsName); break; } catch (Throwable ignored) {}
                }
                if (wsInterface != null) {
                    XposedHelpers.findAndHookMethod(listenerClass, "onMessage",
                        wsInterface, String.class,
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) {
                                try {
                                    String msg = (String) param.args[1];
                                    if (msg == null || msg.length() < 5) return;
                                    if (diagBodyLogCount.getAndIncrement() < 10) {
                                        log("[DIAG] WebSocket.onMessage (" + Math.min(300, msg.length()) + " chars): " +
                                            msg.substring(0, Math.min(300, msg.length())));
                                    }
                                    String model = extractModelFromJson(msg);
                                    if (model != null && !model.isEmpty()) {
                                        String old = getCapture().getCapturedModel();
                                        getCapture().setCapturedModel(model);
                                        if (old == null || !old.equals(model)) {
                                            log("★★★ 捕获模型 ID (WebSocket.onMessage): " + model);
                                        }
                                    }
                                } catch (Throwable ignored) {}
                            }
                        });
                    log("✓ 安装 WebSocket.onMessage hook (" + className + ")");
                    break;
                }
            } catch (Throwable ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.62: Hook RequestBody.writeTo — 直接拦截请求体写入网络
    //  这是最可靠的方式，无论请求体如何创建都能拦截
    // ════════════════════════════════════════════════════════════
    private void hookRequestBodyWriteTo(ClassLoader cl) {
        // hook okhttp3.RequestBody.writeTo(BufferedSink)
        for (String className : new String[]{
                "okhttp3.RequestBody",
                "nu.r", "nu.s", "nu.t", "nu.u", "nu.v", "nu.w", "nu.x"}) {
            try {
                Class<?> bodyClass = cl.loadClass(className);
                // 找 writeTo 方法 — 参数是 BufferedSink
                Method writeToMethod = null;
                for (Method m : bodyClass.getDeclaredMethods()) {
                    if (m.getName().equals("writeTo") && m.getParameterTypes().length == 1) {
                        writeToMethod = m;
                        break;
                    }
                }
                if (writeToMethod == null) continue;

                XposedHelpers.findAndHookMethod(bodyClass, "writeTo", writeToMethod.getParameterTypes()[0],
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                // 获取写入的 sink，尝试读取内容
                                Object sink = param.args[0];
                                if (sink == null) return;

                                // 尝试从 sink 读取 — 如果是 Buffer 可以直接 readUtf8
                                String bodyStr = null;
                                try {
                                    // 如果 sink 本身是 Buffer
                                    Method readUtf8 = sink.getClass().getMethod("readUtf8");
                                    bodyStr = (String) readUtf8.invoke(sink);
                                } catch (Throwable ignored) {}

                                if (bodyStr == null || bodyStr.length() < 5) return;

                                // 诊断日志（前 10 条）
                                if (diagBodyLogCount.getAndIncrement() < 10) {
                                    log("[DIAG] RequestBody.writeTo (" + Math.min(300, bodyStr.length()) + " chars): " +
                                        bodyStr.substring(0, Math.min(300, bodyStr.length())));
                                }

                                // 提取 assistant_id 或 model
                                String model = extractModelFromJson(bodyStr);
                                if (model != null && !model.isEmpty()) {
                                    String old = getCapture().getCapturedModel();
                                    getCapture().setCapturedModel(model);
                                    if (old == null || !old.equals(model)) {
                                        log("★★★ 捕获模型 ID (writeTo): " + model);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                log("✓ 安装 RequestBody.writeTo hook (" + className + ")");
                break;
            } catch (Throwable ignored) {}
        }
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.53: Hook RequestBody.create — 拦截请求体，提取真实 model ID
    // ════════════════════════════════════════════════════════════
    private void hookRequestBodyCreate(ClassLoader cl) {
        try {
            Class<?> requestBodyClass = cl.loadClass("nu.z");
            Class<?> mediaTypeClass = cl.loadClass("nu.w");

            // v1.0.60: 诊断计数器
            final AtomicInteger diagBodyCount = new AtomicInteger(0);

            XposedHelpers.findAndHookMethod(requestBodyClass, "create",
                mediaTypeClass, String.class,
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        try {
                            String body = (String) param.args[1];
                            if (body == null || body.length() < 10) return;

                            // v1.0.60: 诊断日志 — 记录前 10 条请求体摘要
                            if (diagBodyCount.getAndIncrement() < 10) {
                                boolean isGw = GlmCapture.isGatewayRequest();
                                boolean hasModel = body.contains("\"model\"") || body.contains("\"assistant_id\"");
                                log("[DIAG] RequestBody.create: len=" + body.length()
                                    + ", isGateway=" + isGw + ", hasModel=" + hasModel
                                    + ", preview=" + body.substring(0, Math.min(80, body.length())));
                            }

                            // v1.0.57: 跳过网关自身请求，防止 model 捕获反馈循环
                            if (GlmCapture.isGatewayRequest()) return;

                            // v1.0.67: 快速检查 — GLM API 用 assistant_id/meta_data，非 "model"
                            if (!body.contains("\"model\"") && !body.contains("\"assistant_id\"") && !body.contains("\"meta_data\"")) return;

                            // v1.0.67: 使用 extractModelFromJson 统一提取 (支持 assistant_id + chat_mode)
                            String model = extractModelFromJson(body);
                            if (model != null && !model.isEmpty()) {
                                String old = getCapture().getCapturedModel();
                                getCapture().setCapturedModel(model);
                                if (old == null || !old.equals(model)) {
                                    log("★★★ 捕获模型 ID: " + model);
                                    log("  请求体: model=" + model + ", len=" + body.length());
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                });
            log("Hook RequestBody.create() 成功 (v1.0.53)");

            // v1.0.60: 也 hook byte[] 重载 (APP 可能用 byte[] 而非 String 创建请求体)
            try {
                XposedHelpers.findAndHookMethod(requestBodyClass, "create",
                    mediaTypeClass, byte[].class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                byte[] bytes = (byte[]) param.args[1];
                                if (bytes == null || bytes.length < 10) return;
                                String body = new String(bytes, 0, Math.min(bytes.length, 8192), StandardCharsets.UTF_8);
                                // v1.0.67: GLM API 用 assistant_id/meta_data，非 "model"
                                if (!body.contains("\"model\"") && !body.contains("\"assistant_id\"") && !body.contains("\"meta_data\"")) return;
                                if (GlmCapture.isGatewayRequest()) return;
                                String model = extractModelFromJson(body);
                                if (model != null && !model.isEmpty()) {
                                    String old = getCapture().getCapturedModel();
                                    getCapture().setCapturedModel(model);
                                    if (old == null || !old.equals(model)) {
                                        log("★★★ 捕获模型 ID (byte[]): " + model);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                log("Hook RequestBody.create(byte[]) 成功 (v1.0.60)");
            } catch (Throwable t) {
                log("Hook RequestBody.create(byte[]) 失败: " + t.getMessage());
            }

            // v1.0.60: 也 hook okhttp3.RequestBody (非混淆类名) 作为后备
            try {
                Class<?> okHttpRequestBodyClass = cl.loadClass("okhttp3.RequestBody");
                XposedHelpers.findAndHookMethod(okHttpRequestBodyClass, "create",
                    cl.loadClass("okhttp3.MediaType"), String.class,
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) {
                            try {
                                String body = (String) param.args[1];
                                if (body == null || body.length() < 10) return;
                                // v1.0.67: GLM API 用 assistant_id/meta_data，非 "model"
                                if (!body.contains("\"model\"") && !body.contains("\"assistant_id\"") && !body.contains("\"meta_data\"")) return;
                                if (GlmCapture.isGatewayRequest()) return;
                                String model = extractModelFromJson(body);
                                if (model != null && !model.isEmpty()) {
                                    String old = getCapture().getCapturedModel();
                                    getCapture().setCapturedModel(model);
                                    if (old == null || !old.equals(model)) {
                                        log("★★★ 捕获模型 ID (okhttp3): " + model);
                                    }
                                }
                            } catch (Throwable ignored) {}
                        }
                    });
                log("Hook okhttp3.RequestBody.create() 成功 (v1.0.60)");
            } catch (Throwable t) {
                log("Hook okhttp3.RequestBody.create() 跳过: " + t.getMessage());
            }
        } catch (Throwable t) {
            log("Hook RequestBody.create() 失败: " + t.getMessage());
        }
    }

    /** 检查主机名是否是 GLM API (v1.0.59: 接受所有 GLM 相关域名，不仅 bigmodel) */
    private boolean isGlmHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase();
        return lower.contains("bigmodel") ||
               lower.contains("chatglm") ||
               lower.contains("zhipuai") ||
               lower.contains("qingyan") ||
               lower.contains("glm");
    }

    // ════════════════════════════════════════════════════════════
    //  捕获拦截器 — 通过 Hook Call.execute/enqueue 捕获请求头
    // ════════════════════════════════════════════════════════════
    private void installCaptureInterceptor(Object client, ClassLoader cl) {
        if (!realCallHooked.compareAndSet(false, true)) return;
        Class<?> realCallClass;
        try {
            realCallClass = cl.loadClass("okhttp3.internal.connection.RealCall");
        } catch (Throwable t) {
            realCallHooked.set(false);
            log("安装 RealCall 拦截器失败 (类未找到): " + t.getMessage());
            return;
        }

        // v1.0.55: 核心修复 — hook getResponseWithInterceptorChain$okhttp() 的返回值
        // auth 由 OkHttp Interceptor 在拦截器链中注入，originalRequest 没有 auth。
        // getResponseWithInterceptorChain 是 execute() 和 enqueue() 的共同出口，
        // 返回的 Response.request() 包含拦截器添加的所有头（含 Authorization）。
        try {
            XposedHelpers.findAndHookMethod(
                realCallClass, "getResponseWithInterceptorChain$okhttp",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        captureAuthFromResponse(param.result);
                    }
                });
            log("✓ 安装 getResponseWithInterceptorChain auth 捕获 hook");
        } catch (Throwable t) {
            log("⚠ getResponseWithInterceptorChain hook 失败: " + t.getMessage());
        }

        // hook execute() — 同步请求，afterHookedMethod 获取 Response 提取 auth
        try {
            XposedHelpers.findAndHookMethod(
                realCallClass, "execute",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureRequest(param.thisObject, cl);
                    }
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        captureAuthFromResponse(param.result);
                    }
                });
            log("✓ 安装 execute() hook");
        } catch (Throwable t) {
            log("⚠ execute() hook 失败: " + t.getMessage());
        }

        // hook enqueue() — 异步请求，Callback 类被混淆为 nu.e（不是 okhttp3.Callback！）
        try {
            Class<?> callbackClass = cl.loadClass("nu.e");
            XposedHelpers.findAndHookMethod(
                realCallClass, "enqueue", callbackClass,
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureRequest(param.thisObject, cl);
                    }
                });
            log("✓ 安装 enqueue() hook (nu.e)");
        } catch (Throwable t) {
            log("⚠ enqueue() hook 失败: " + t.getMessage());
        }

        log("安装请求捕获拦截器完成 (v1.0.55)");
    }

    /**
     * v1.0.55: 从 Response.request() 提取 auth。
     * Response.request() 返回最终请求（含拦截器添加的 Authorization 头）。
     * 这是捕获 auth 的唯一可靠方法，因为 auth 不在 originalRequest 中。
     */
    private void captureAuthFromResponse(Object response) {
        if (response == null) return;
        // v1.0.59: 移除 ThreadLocal 检查 — 网关响应中的 auth 是 APP 拦截器注入的，是真实 auth
        // ThreadLocal 仅保留在 hookRequestBodyCreate 中防止 model 反馈循环
        try {
            // v1.0.58: 记录 response code 用于诊断
            int responseCode = -1;
            try {
                Method codeMethod = response.getClass().getMethod("code");
                responseCode = (Integer) codeMethod.invoke(response);
            } catch (Throwable ignored) {}

            // okhttp3.Response.request() → nu.Request (最终请求，含 auth)
            Method requestMethod = response.getClass().getMethod("request");
            Object finalRequest = requestMethod.invoke(response);
            if (finalRequest == null) return;

            // nu.Request.k() → nu.u (HttpUrl)
            Method urlMethod = finalRequest.getClass().getMethod("k");
            Object httpUrl = urlMethod.invoke(finalRequest);
            if (httpUrl == null) return;
            String urlStr = httpUrl.toString();

            // v1.0.59: 诊断日志 — 记录所有响应 URL（前 20 条），帮助确认 APP 使用哪个域名
            if (diagUrlLogCount.getAndIncrement() < 20) {
                log("[DIAG] Response URL (code=" + responseCode + "): " + urlStr);
            }

            // v1.0.59: 放宽 URL 过滤 — 接受所有 GLM 相关域名 (不再只限 bigmodel)
            if (!isGlmApiUrl(urlStr)) return;

            // nu.Request.d(String) → String (读取头)
            Method headerMethod = finalRequest.getClass().getMethod("d", String.class);

            String auth = (String) headerMethod.invoke(finalRequest, "Authorization");
            if (auth != null && !auth.isEmpty()) {
                String old = getCapture().getAuthToken();
                getCapture().setAuthToken(auth);
                if (old == null || !old.equals(auth)) {
                    log("✓✓ 捕获 Authorization (Response.request, code=" + responseCode + ", url=" + urlStr + "): " + auth.substring(0, Math.min(30, auth.length())) + "...");
                }
            }

            String apiKey = (String) headerMethod.invoke(finalRequest, "x-api-key");
            if (apiKey != null && !apiKey.isEmpty()) {
                getCapture().setApiKey(apiKey);
                log("✓✓ 捕获 x-api-key (Response.request)");
            }

            String cookie = (String) headerMethod.invoke(finalRequest, "Cookie");
            if (cookie != null && !cookie.isEmpty()) {
                getCapture().setCookie(cookie);
            }

            // 同时更新 API URL（确保是最新的）
            getCapture().setApiUrl(urlStr);

            // v1.0.61: 从响应体提取 model（peekBody 不消耗 body）
            tryExtractModelFromResponse(response, urlStr);

            // v1.0.61: 尝试从请求体提取 model
            tryExtractModelFromRequest(finalRequest, urlStr);

        } catch (Throwable t) {
            log("⚠ captureAuthFromResponse 异常: " + t.getMessage());
        }
    }

    /**
     * v1.0.61: 从响应体提取 model。
     * 使用 peekBody() 安全读取响应体前 N 字节，不消耗原始 body。
     * 支持 JSON 和 SSE (data: {...}) 格式。
     */
    private void tryExtractModelFromResponse(Object response, String urlStr) {
        try {
            // v1.0.62: 诊断 — 确认方法被调用
            if (diagBodyLogCount.get() < 10) {
                log("[DIAG] tryExtractModelFromResponse: " + urlStr);
            }
            // peekBody(long) — 读取响应体副本（不消耗原始 body）
            Method peekBodyMethod = null;
            for (Method m : response.getClass().getMethods()) {
                if (m.getName().equals("peekBody") && m.getParameterTypes().length == 1) {
                    peekBodyMethod = m;
                    break;
                }
            }
            if (peekBodyMethod == null) {
                if (diagUrlLogCount.get() < 3) {
                    log("[DIAG] peekBody 方法未找到");
                }
                return;
            }

            Object peekedBody = peekBodyMethod.invoke(response, 8192L);
            if (peekedBody == null) return;

            // 读取 string
            Method stringMethod = null;
            for (Method m : peekedBody.getClass().getMethods()) {
                if (m.getName().equals("string") && m.getParameterTypes().length == 0) {
                    stringMethod = m;
                    break;
                }
            }
            if (stringMethod == null) return;

            String bodyStr = (String) stringMethod.invoke(peekedBody);
            if (bodyStr == null || bodyStr.length() < 5) return;

            // 诊断日志（前 5 条响应体摘要）
            if (diagBodyLogCount.getAndIncrement() < 5) {
                log("[DIAG] ResponseBody peek (" + Math.min(200, bodyStr.length()) + " chars): " +
                    bodyStr.substring(0, Math.min(200, bodyStr.length())));
            }

            // 提取 model
            String model = extractModelFromJson(bodyStr);
            if (model != null && !model.isEmpty()) {
                String old = getCapture().getCapturedModel();
                getCapture().setCapturedModel(model);
                if (old == null || !old.equals(model)) {
                    log("★★★ 捕获模型 ID (response body): " + model + " (url=" + urlStr + ")");
                }
            }
        } catch (Throwable t) {
            if (diagBodyLogCount.get() < 3) {
                log("[DIAG] tryExtractModelFromResponse: " + t.getMessage());
            }
        }
    }

    /**
     * v1.0.61: 尝试从请求体提取 model。
     * 使用 writeTo(buffer) 读取请求体内容。
     * 注意：这可能消耗 one-shot body，但 JSON 请求体通常是可重复的。
     */
    private void tryExtractModelFromRequest(Object request, String urlStr) {
        try {
            // v1.0.62: 诊断 — 确认方法被调用
            if (diagBodyLogCount.get() < 10) {
                log("[DIAG] tryExtractModelFromRequest: " + urlStr);
            }
            // 获取 body
            Method bodyMethod = null;
            for (Method m : request.getClass().getMethods()) {
                if (m.getName().equals("body") && m.getParameterTypes().length == 0) {
                    bodyMethod = m;
                    break;
                }
            }
            if (bodyMethod == null) return;
            Object body = bodyMethod.invoke(request);
            if (body == null) return;

            // 检查 isOneShot — 如果是一次性 body，跳过（避免消耗）
            try {
                Method isOneShot = null;
                for (Method m : body.getClass().getMethods()) {
                    if (m.getName().equals("isOneShot") && m.getParameterTypes().length == 0) {
                        isOneShot = m;
                        break;
                    }
                }
                if (isOneShot != null && (Boolean) isOneShot.invoke(body)) {
                    return; // 一次性 body，跳过
                }
            } catch (Throwable ignored) {}

            // 找 writeTo 方法
            Method writeToMethod = null;
            for (Method m : body.getClass().getMethods()) {
                if (m.getName().equals("writeTo") && m.getParameterTypes().length == 1) {
                    writeToMethod = m;
                    break;
                }
            }
            if (writeToMethod == null) return;

            // 创建 Buffer — 尝试多种类名
            Class<?> bufferClass = null;
            for (String name : new String[]{"okio.Buffer", "nu.e", "nu.f", "nu.g", "nu.h"}) {
                try {
                    Class<?> c = Class.forName(name);
                    c.getMethod("readUtf8");
                    // 检查是否可赋值给 writeTo 参数
                    Class<?> sinkType = writeToMethod.getParameterTypes()[0];
                    if (sinkType.isAssignableFrom(c)) {
                        bufferClass = c;
                        break;
                    }
                } catch (Throwable ignored) {}
            }
            if (bufferClass == null) return;

            Object buffer = bufferClass.getDeclaredConstructor().newInstance();
            writeToMethod.invoke(body, buffer);

            Method readUtf8 = bufferClass.getMethod("readUtf8");
            String bodyStr = (String) readUtf8.invoke(buffer);
            if (bodyStr == null || bodyStr.length() < 5) return;

            // 诊断日志
            if (diagBodyLogCount.getAndIncrement() < 5) {
                log("[DIAG] RequestBody writeTo (" + Math.min(200, bodyStr.length()) + " chars): " +
                    bodyStr.substring(0, Math.min(200, bodyStr.length())));
            }

            // 提取 model
            String model = extractModelFromJson(bodyStr);
            if (model != null && !model.isEmpty()) {
                String old = getCapture().getCapturedModel();
                getCapture().setCapturedModel(model);
                if (old == null || !old.equals(model)) {
                    log("★★★ 捕获模型 ID (request body): " + model + " (url=" + urlStr + ")");
                }
            }
        } catch (Throwable t) {
            if (diagBodyLogCount.get() < 3) {
                log("[DIAG] tryExtractModelFromRequest: " + t.getMessage());
            }
        }
    }

    /**
     * v1.0.61: 从 JSON 字符串提取 "model" 字段值。
     * 支持:
     * - 标准 JSON: {"model": "glm-4", ...}
     * - SSE 格式: data: {"model": "glm-4", ...}
     * - 嵌套 JSON
     */
    private String extractModelFromJson(String str) {
        if (str == null) return null;

        // v1.0.66: GLM API 用 assistant_id + meta_data.chat_mode
        // 优先查找 "assistant_id"
        String assistantId = extractJsonValue(str, "assistant_id");
        if (assistantId != null && !assistantId.isEmpty()) {
            // v1.0.66: chat_mode 嵌套在 meta_data 里，不是顶层
            String chatMode = extractChatModeFromMeta(str);
            // 映射 chat_mode → 用户友好后缀
            // "zero" → "thinking", "" → "fast", "deep_research" → "deep_research"
            String suffix = chatModeToSuffix(chatMode);
            log("[DIAG] 捕获 assistant_id=" + assistantId + " chat_mode=" + chatMode + " → suffix=" + suffix);
            return assistantId + ":" + suffix;
        }

        // 回退到 "model" 字段（OpenAI 兼容格式）
        String model = extractJsonValue(str, "model");
        if (model != null && !model.isEmpty()) return model;

        return null;
    }

    /** v1.0.66: 从 meta_data 对象提取 chat_mode（嵌套字段） */
    private String extractChatModeFromMeta(String str) {
        if (str == null) return "";
        // 尝试标准 JSON 解析 meta_data.chat_mode
        try {
            JSONObject json = new JSONObject(str);
            if (json.has("meta_data")) {
                JSONObject metaData = json.getJSONObject("meta_data");
                return metaData.optString("chat_mode", "");
            }
        } catch (Throwable ignored) {}
        // 尝试 SSE 格式
        try {
            String[] lines = str.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("data:") && line.contains("\"meta_data\"")) {
                    String jsonPart = line.substring(5).trim();
                    if (jsonPart.startsWith("{")) {
                        JSONObject json = new JSONObject(jsonPart);
                        if (json.has("meta_data")) {
                            JSONObject metaData = json.getJSONObject("meta_data");
                            return metaData.optString("chat_mode", "");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        // 正则提取 meta_data 中的 chat_mode
        try {
            int metaIdx = str.indexOf("\"meta_data\"");
            if (metaIdx >= 0) {
                int chatIdx = str.indexOf("\"chat_mode\"", metaIdx);
                if (chatIdx >= 0) {
                    int colonIdx = str.indexOf(":", chatIdx + 11);
                    if (colonIdx >= 0) {
                        int startQuote = str.indexOf("\"", colonIdx + 1);
                        if (startQuote >= 0) {
                            int endQuote = str.indexOf("\"", startQuote + 1);
                            if (endQuote >= 0) {
                                return str.substring(startQuote + 1, endQuote);
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return "";
    }

    /** v1.0.66: chat_mode 值 ↔ 用户友好后缀映射 */
    private String chatModeToSuffix(String chatMode) {
        if (chatMode == null) return "fast";
        switch (chatMode) {
            case "zero":          return "thinking";
            case "deep_research": return "deep_research";
            case "":              return "fast";
            default:              return chatMode; // 未知值原样保留
        }
    }

    /** 从 JSON 字符串提取指定字段的字符串值（支持标准 JSON、SSE、正则） */
    private String extractJsonValue(String str, String key) {
        if (str == null || !str.contains("\"" + key + "\"")) return null;
        String searchKey = "\"" + key + "\"";

        // 尝试标准 JSON 解析
        try {
            JSONObject json = new JSONObject(str);
            String val = json.optString(key, null);
            if (val != null && !val.isEmpty()) return val;
        } catch (Throwable ignored) {}

        // 尝试 SSE 格式 — 每行 "data: {...}"
        try {
            String[] lines = str.split("\n");
            for (String line : lines) {
                line = line.trim();
                if (line.startsWith("data:") && line.contains(searchKey)) {
                    String jsonPart = line.substring(5).trim();
                    if (jsonPart.startsWith("{")) {
                        JSONObject json = new JSONObject(jsonPart);
                        String val = json.optString(key, null);
                        if (val != null && !val.isEmpty()) return val;
                    }
                }
            }
        } catch (Throwable ignored) {}

        // 正则提取
        try {
            int idx = str.indexOf(searchKey);
            if (idx >= 0) {
                int colonIdx = str.indexOf(":", idx + searchKey.length());
                if (colonIdx >= 0) {
                    int startQuote = str.indexOf("\"", colonIdx + 1);
                    if (startQuote >= 0) {
                        int endQuote = str.indexOf("\"", startQuote + 1);
                        if (endQuote >= 0) {
                            return str.substring(startQuote + 1, endQuote);
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}

        return null;
    }

    private void captureRequest(Object call, ClassLoader cl) {
        // v1.0.57: 跳过网关自身请求
        if (GlmCapture.isGatewayRequest()) return;
        try {
            Object request = XposedHelpers.getObjectField(call, "originalRequest");
            if (request == null) {
                try {
                    Method m = call.getClass().getMethod("getOriginalRequest");
                    request = m.invoke(call);
                } catch (Throwable ignored) {}
            }
            if (request != null) {
                extractRequestDetails(request, cl);
            }
        } catch (Throwable ignored) {}
    }

    // ════════════════════════════════════════════════════════════
    //  请求捕获 — 提取 URL、认证头
    // ════════════════════════════════════════════════════════════

    /** 标准 OkHttp Request 提取（类名未混淆时） */
    private void extractRequestDetails(Object request, ClassLoader cl) {
        try {
            Method urlMethod = request.getClass().getMethod("url");
            Object httpUrl = urlMethod.invoke(request);
            String urlStr = httpUrl.toString();

            if (isGlmApiUrl(urlStr)) {
                // v1.0.59: 接受所有 GLM 相关域名
                getCapture().setApiUrl(urlStr);
                log("捕获 GLM API 请求 URL: " + urlStr);
                // v1.0.59: 从所有 GLM API 请求提取 auth
                Method headersMethod = request.getClass().getMethod("headers");
                Object headers = headersMethod.invoke(request);
                extractAuthFromHeaders(headers, cl);
            }
        } catch (Throwable ignored) {}
    }

    /**
     * 通用 Request 提取（混淆类，通过反射找 url()/headers() 方法）。
     * 不依赖固定类名，通过方法名匹配。
     */
    private void extractRequestDetailsGeneric(Object request) {
        try {
            Class<?> reqClass = request.getClass();

            // 找 url() 方法
            Method urlMethod = null;
            for (Method m : reqClass.getMethods()) {
                if (m.getName().equals("url") && m.getParameterTypes().length == 0) {
                    urlMethod = m;
                    break;
                }
            }
            if (urlMethod == null) return;

            Object httpUrl = urlMethod.invoke(request);
            if (httpUrl == null) return;
            String urlStr = httpUrl.toString();

            if (!isGlmApiUrl(urlStr)) return;

            // v1.0.59: 接受所有 GLM 相关域名 (不再只限 bigmodel)
            getCapture().setApiUrl(urlStr);
            log("捕获 GLM API 请求 URL (混淆): " + urlStr);

            // 找 headers() 方法
            Method headersMethod = null;
            for (Method m : reqClass.getMethods()) {
                if (m.getName().equals("headers") && m.getParameterTypes().length == 0) {
                    headersMethod = m;
                    break;
                }
            }
            if (headersMethod == null) return;

            Object headers = headersMethod.invoke(request);
            if (headers == null) return;

            // 通用 headers 遍历：找 size(), name(int), value(int) 方法
            extractAuthFromHeadersGeneric(headers);

        } catch (Throwable ignored) {}
    }

    /** 标准 Headers 提取 */
    private void extractAuthFromHeaders(Object headers, ClassLoader cl) {
        try {
            Method sizeMethod = headers.getClass().getMethod("size");
            int size = (int) sizeMethod.invoke(headers);
            Method nameMethod = headers.getClass().getMethod("name", int.class);
            Method valueMethod = headers.getClass().getMethod("value", int.class);

            for (int i = 0; i < size; i++) {
                String name = (String) nameMethod.invoke(headers, i);
                String value = (String) valueMethod.invoke(headers, i);
                processHeader(name, value);
            }
        } catch (Throwable ignored) {}
    }

    /** 通用 Headers 提取（混淆类，通过反射找 size/name/value 方法） */
    private void extractAuthFromHeadersGeneric(Object headers) {
        try {
            Class<?> hClass = headers.getClass();
            Method sizeMethod = null, nameMethod = null, valueMethod = null;

            for (Method m : hClass.getMethods()) {
                if (m.getName().equals("size") && m.getParameterTypes().length == 0
                        && m.getReturnType() == int.class) sizeMethod = m;
                if (m.getName().equals("name") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class
                        && m.getReturnType() == String.class) nameMethod = m;
                if (m.getName().equals("value") && m.getParameterTypes().length == 1
                        && m.getParameterTypes()[0] == int.class
                        && m.getReturnType() == String.class) valueMethod = m;
            }
            if (sizeMethod == null || nameMethod == null || valueMethod == null) return;

            int size = (int) sizeMethod.invoke(headers);
            for (int i = 0; i < size; i++) {
                String name = (String) nameMethod.invoke(headers, i);
                String value = (String) valueMethod.invoke(headers, i);
                processHeader(name, value);
            }
        } catch (Throwable ignored) {}
    }

    /** 处理单个 header，捕获认证信息 */
    private void processHeader(String name, String value) {
        if (name == null || value == null) return;
        String ln = name.toLowerCase();

        boolean authChanged = false;
        if ("authorization".equals(ln)) {
            getCapture().setAuthToken(value);
            log("捕获 Authorization 头");
            authChanged = true;
        } else if (ln.contains("token") || ln.contains("api-key") || ln.contains("apikey")) {
            getCapture().setApiKey(value);
            log("捕获 API Key 头: " + name);
            authChanged = true;
        } else if (ln.contains("cookie")) {
            getCapture().setCookie(value);
            log("捕获 Cookie 头");
            authChanged = true;
        } else if ("x-device-id".equals(ln) || ln.contains("device")) {
            getCapture().setDeviceId(value);
        }

        if (authChanged) {
            saveAuthAndNotify();
        }
    }

    private boolean isGlmApiUrl(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase();
        return lower.contains("chatglm") ||
               lower.contains("zhipuai") ||
               lower.contains("bigmodel") ||
               lower.contains("qingyan") ||
               lower.contains("glm") ||
               lower.contains("open.bigmodel");
    }

    // ════════════════════════════════════════════════════════════
    //  v1.0.41: 保存 auth 到共享文件 + 通知 GLMKit APP
    // ════════════════════════════════════════════════════════════
    private static final AtomicBoolean authSaved = new AtomicBoolean(false);

    /**
     * 将捕获的 auth 信息写入 /sdcard/glmkit_auth.json 并广播通知 GLMKit APP。
     * 网关运行在 GLMKit APP 进程中，通过此文件获取 auth。
     */
    void saveAuthAndNotify() {
        GlmCapture cap = getCapture();
        if (cap.getBestAuth() == null) return;

        boolean saved = cap.saveToSharedFile();
        log("auth 写入共享文件: " + (saved ? "成功" : "失败") + " → " + GlmCapture.SHARED_AUTH_FILE);

        // 广播通知 GLMKit APP
        try {
            Intent intent = new Intent("com.glmkit.proxy.AUTH_CAPTURED");
            intent.setPackage("com.glmkit.proxy");
            if (appContext != null) {
                appContext.sendBroadcast(intent);
                log("发送 AUTH_CAPTURED 广播");
            }
        } catch (Throwable t) {
            log("发送 AUTH_CAPTURED 广播失败: " + t.getMessage());
        }

        if (authSaved.compareAndSet(false, true)) {
            showToast("GLMKit 已捕获认证信息");
        }
    }

    // ════════════════════════════════════════════════════════════
    //  激活广播
    // ════════════════════════════════════════════════════════════
    private void broadcastActivation(String action) {
        try {
            Intent intent = new Intent(action);
            intent.setPackage("com.glmkit.proxy");
            if (appContext != null) {
                appContext.sendBroadcast(intent);
                log("发送激活广播: " + action);
            }
        } catch (Throwable t) {
            log("发送激活广播失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  辅助方法
    // ════════════════════════════════════════════════════════════
    private GlmCapture getCapture() {
        if (capture == null) {
            synchronized (this) {
                if (capture == null) {
                    capture = new GlmCapture();
                }
            }
        }
        return capture;
    }

    /** v1.0.75: 静态方法供 LocalApiGateway 诊断端点使用 */
    public static GlmCapture getCaptureStatic() {
        if (INSTANCE != null) return INSTANCE.capture;
        return null;
    }

    static Main getInstance() { return INSTANCE; }
    static ClassLoader getHostClassLoader() { return hostClassLoader; }

    static void log(String msg) {
        String timestamped = logDateFormat.format(new Date()) + " " + msg;
        String full = "[" + TAG + "] " + timestamped;
        try { XposedBridge.log(full); } catch (Throwable ignored) {}

        // 写入内存缓冲区
        synchronized (logBuffer) {
            if (logBuffer.size() >= MAX_LOG_ENTRIES) {
                logBuffer.remove(0);
            }
            logBuffer.add(timestamped);
        }

        // 写入文件（best effort）
        try {
            if (logWriter != null) {
                logWriter.println(timestamped);
                logWriter.flush();
            }
        } catch (Throwable ignored) {}
    }

    /** 获取日志缓冲区内容（用于 /v1/logs 端点） */
    static String getLogBuffer() {
        synchronized (logBuffer) {
            StringBuilder sb = new StringBuilder();
            for (String line : logBuffer) {
                sb.append(line).append('\n');
            }
            return sb.toString();
        }
    }

    /** 初始化日志文件写入 */
    static void initLogFile(Context ctx) {
        if (logWriter != null) return;
        try {
            // 尝试多个位置写入
            File f = null;

            // 位置1: /sdcard/glmkit_debug.log（需要存储权限）
            File sdcard = new File("/sdcard/glmkit_debug.log");
            try {
                FileWriter fw = new FileWriter(sdcard, true);
                fw.write("--- GLMKit log session " + logDateFormat.format(new Date()) + " ---\n");
                fw.flush();
                fw.close();
                f = sdcard;
            } catch (Throwable ignored) {}

            // 位置2: 应用缓存目录（始终可写）
            if (f == null && ctx != null) {
                File cacheDir = ctx.getExternalCacheDir();
                if (cacheDir == null) cacheDir = ctx.getCacheDir();
                if (cacheDir != null) {
                    File lf = new File(cacheDir, "glmkit_debug.log");
                    FileWriter fw = new FileWriter(lf, true);
                    fw.write("--- GLMKit log session " + logDateFormat.format(new Date()) + " ---\n");
                    fw.flush();
                    fw.close();
                    f = lf;
                }
            }

            if (f != null) {
                logFile = f;
                logWriter = new PrintWriter(new FileWriter(f, true), true);
                log("日志文件: " + f.getAbsolutePath());
            }
        } catch (Throwable ignored) {}
    }

    /** 获取日志文件路径（用于诊断） */
    static String getLogFilePath() {
        return logFile != null ? logFile.getAbsolutePath() : "未初始化";
    }

    static void showToast(final String msg) {
        try {
            new Handler(Looper.getMainLooper()).post(() -> {
                try {
                    Toast.makeText(Main.appContext, msg, Toast.LENGTH_LONG).show();
                } catch (Throwable ignored) {}
            });
        } catch (Throwable ignored) {}
    }
}
