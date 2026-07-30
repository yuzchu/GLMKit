package com.glmkit.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.XSharedPreferences;
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

    // 捕获的 GLM 网络信息
    private volatile GlmCapture capture;
    private static volatile Context appContext;
    private final AtomicBoolean realCallHooked = new AtomicBoolean(false);

    // 混淆 OkHttp 类缓存（结构扫描结果）
    private volatile Class<?> obfClientClass;
    private volatile Class<?> obfRequestClass;
    private volatile Class<?> obfHeadersClass;

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
    private static volatile PowerManager.WakeLock wakeLock = null;

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        INSTANCE = this;
        hostClassLoader = lpparam.classLoader;
        log("GLMKit 模块加载，目标: " + lpparam.packageName);

        // 1. Hook Application.onCreate 获取 Context
        hookApplicationOnCreate(lpparam.classLoader);

        // 2. 尝试 hook OkHttp（标准名 → 结构扫描 → HttpURLConnection 兜底）
        hookOkHttp(lpparam.classLoader);

        // 3. Hook Retrofit 构建 捕获 base URL
        hookRetrofitBuilder(lpparam.classLoader);
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
                        appContext = (Context) param.thisObject;
                        log("宿主 Application.onCreate 完成，获取 Context");
                        showToast("GLMKit 已注入智谱清言");

                        // 通知模块自身进程：hook 已启动
                        broadcastActivation("com.glmkit.proxy.HOOK_STARTED");

                        // 延迟启动网关，等待 OkHttp 捕获
                        startGatewayWhenReady();
                    }
                });
        } catch (Throwable t) {
            log("hook Application.onCreate 失败: " + t.getMessage());
        }
    }

    // ════════════════════════════════════════════════════════════
    //  OkHttp Hook — 多策略递进
    // ════════════════════════════════════════════════════════════
    private void hookOkHttp(ClassLoader cl) {
        // 策略1: okhttp3.OkHttpClient$Builder (标准 OkHttp 3.x/4.x)
        if (tryHookOkHttp3Builder(cl)) return;
        // 策略2: okhttp3.OkHttpClient.newCall() 直接 hook
        if (tryHookOkHttp3NewCall(cl)) return;
        // 策略3: com.squareup.okhttp (OkHttp 2.x)
        if (tryHookOkHttp2(cl)) return;
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
                                showToast("GLMKit 已捕获认证信息");
                                broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                            } else if (ln.contains("token") || ln.contains("api-key") || ln.contains("apikey")) {
                                getCapture().setApiKey(value);
                                log("捕获 API Key (HttpURLConnection)");
                                showToast("GLMKit 已捕获认证信息");
                                broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");
                            } else if (ln.contains("cookie")) {
                                getCapture().setCookie(value);
                                log("捕获 Cookie (HttpURLConnection)");
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
    //  捕获拦截器 — 通过 Hook Call.execute/enqueue 捕获请求头
    // ════════════════════════════════════════════════════════════
    private void installCaptureInterceptor(Object client, ClassLoader cl) {
        if (!realCallHooked.compareAndSet(false, true)) return;
        try {
            Class<?> realCallClass = cl.loadClass("okhttp3.internal.connection.RealCall");
            XposedHelpers.findAndHookMethod(
                realCallClass, "execute",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureRequest(param.thisObject, cl);
                    }
                });
            XposedHelpers.findAndHookMethod(
                realCallClass, "enqueue", cl.loadClass("okhttp3.Callback"),
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureRequest(param.thisObject, cl);
                    }
                });
            log("安装请求捕获拦截器成功");
        } catch (Throwable t) {
            realCallHooked.set(false);
            log("安装 RealCall 拦截器失败: " + t.getMessage());
        }
    }

    private void captureRequest(Object call, ClassLoader cl) {
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
                getCapture().setApiUrl(urlStr);
                log("捕获 GLM API 请求 URL: " + urlStr);

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

        if ("authorization".equals(ln)) {
            getCapture().setAuthToken(value);
            log("捕获 Authorization 头");
            showToast("GLMKit 已捕获认证信息");
        } else if (ln.contains("token") || ln.contains("api-key") || ln.contains("apikey")) {
            getCapture().setApiKey(value);
            log("捕获 API Key 头: " + name);
        } else if (ln.contains("cookie")) {
            getCapture().setCookie(value);
            log("捕获 Cookie 头");
        } else if ("x-device-id".equals(ln) || ln.contains("device")) {
            getCapture().setDeviceId(value);
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
    //  网关启动
    // ════════════════════════════════════════════════════════════
    private void startGatewayWhenReady() {
        new Thread(() -> {
            try {
                // 初始化日志文件
                if (appContext != null) {
                    initLogFile(appContext);
                }

                // 短暂等待 OkHttp 捕获（最多 3s），然后立即启动网关
                int waited = 0;
                while (getCapture().getOkHttpClient() == null && waited < 3_000) {
                    Thread.sleep(200);
                    waited += 200;
                }

                if (getCapture().getOkHttpClient() != null) {
                    log("OkHttp 客户端已捕获，启动网关");
                } else {
                    log("OkHttp 未捕获，网关以备用模式启动 (HttpURLConnection)");
                }

                if (appContext == null) {
                    log("Context 为空，无法启动网关");
                    return;
                }

                Context ctx = appContext.getApplicationContext();

                int port = 8765;
                try {
                    XSharedPreferences xPrefs = new XSharedPreferences("com.glmkit.proxy", "glmkit_settings");
                    xPrefs.reload();
                    xPrefs.makeReadable();
                    port = xPrefs.getInt("port", 8765);
                    LocalApiGateway.setListenPort(port);
                    String apiKey = xPrefs.getString("api_key", null);
                    LocalApiGateway.setApiKey(apiKey);
                    log("配置监听端口: " + port + " (从模块偏好读取)");
                    log("API Key 验证: " + (apiKey != null && !apiKey.isEmpty() ? "已启用" : "未启用"));
                } catch (Throwable ignored) {}

                GlmBackend backend = new GlmBackend(getCapture());
                int actualPort = LocalApiGateway.start(ctx, backend);

                if (!LocalApiGateway.isRunning()) {
                    log("✗ 网关启动失败，所有端口均被占用");
                    showToast("GLMKit 网关启动失败（端口被占用）");
                    return;
                }

                log("本地 API 网关已启动，实际端口: " + actualPort);
                log("日志文件路径: " + getLogFilePath());
                showToast("GLMKit 网关已启动，端口: " + actualPort);

                // 启动前台通知保活 — 防止切换应用时进程被杀死
                startForegroundKeepAlive(ctx);

                Intent gatewayIntent = new Intent("com.glmkit.proxy.GATEWAY_STARTED");
                gatewayIntent.setPackage("com.glmkit.proxy");
                gatewayIntent.putExtra("port", actualPort);
                try {
                    ctx.sendBroadcast(gatewayIntent);
                    log("发送网关启动广播");
                } catch (Throwable ignored) {}

                // 添加关闭钩子，记录进程退出
                Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                    log("⚠️ 进程即将退出（shutdown hook）");
                    if (wakeLock != null && wakeLock.isHeld()) {
                        try { wakeLock.release(); } catch (Throwable ignored) {}
                    }
                }, "glmkit-shutdown-hook"));

            } catch (Throwable t) {
                log("启动网关失败: " + t.getMessage());
            }
        }, "glmkit-gateway-init").start();
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

    /** 启动前台通知保活 — 防止目标应用进程被系统杀死 */
    static void startForegroundKeepAlive(Context ctx) {
        if (ctx == null) return;
        try {
            // 使用 WakeLock 防止 CPU 休眠
            if (wakeLock == null) {
                PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GLMKit:gateway");
                wakeLock.setReferenceCounted(false);
                wakeLock.acquire();
                log("已获取 WakeLock (PARTIAL_WAKE_LOCK)");
            }

            // 显示常驻通知（提高进程优先级，减少被杀概率）
            String channelId = "glmkit_gateway";
            NotificationManager nm = (NotificationManager)
                    ctx.getSystemService(Context.NOTIFICATION_SERVICE);

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                NotificationChannel channel = new NotificationChannel(
                        channelId, "GLMKit 网关",
                        NotificationManager.IMPORTANCE_LOW);
                channel.setDescription("本地 API 网关运行中");
                channel.setShowBadge(false);
                nm.createNotificationChannel(channel);
            }

            Notification notification;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                notification = new Notification.Builder(ctx, channelId)
                        .setContentTitle("GLMKit 网关运行中")
                        .setContentText("本地 API 反代服务正在运行 (端口 " + LocalApiGateway.getListenPort() + ")")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setOngoing(true)
                        .build();
            } else {
                notification = new Notification.Builder(ctx)
                        .setContentTitle("GLMKit 网关运行中")
                        .setContentText("本地 API 反代服务正在运行 (端口 " + LocalApiGateway.getListenPort() + ")")
                        .setSmallIcon(android.R.drawable.ic_dialog_info)
                        .setOngoing(true)
                        .build();
            }

            nm.notify(1, notification);
            log("已显示常驻通知（提高进程优先级）");

            // 启动一个后台线程定期检查网关状态，保持进程活跃
            new Thread(() -> {
                while (LocalApiGateway.isRunning()) {
                    try {
                        Thread.sleep(30_000); // 每 30 秒检查一次
                        if (LocalApiGateway.isRunning()) {
                            log("保活心跳: 网关运行中, 连接数=" +
                                    LocalApiGateway.connectionInfo());
                        }
                    } catch (InterruptedException e) {
                        break;
                    } catch (Throwable t) {
                        log("保活心跳异常: " + t.getMessage());
                    }
                }
                log("保活心跳线程退出（网关已停止）");
                // 释放 WakeLock
                if (wakeLock != null && wakeLock.isHeld()) {
                    try { wakeLock.release(); } catch (Throwable ignored) {}
                    log("已释放 WakeLock");
                }
                // 取消通知
                try { nm.cancel(1); } catch (Throwable ignored) {}
            }, "glmkit-keepalive-heartbeat").start();

        } catch (Throwable t) {
            log("前台保活启动失败: " + t.getMessage());
        }
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
