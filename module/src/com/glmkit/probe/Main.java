package com.glmkit.probe;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Method;
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
 * 包名 com.glmkit.proxy（与参考项目 com.dsmod.probe 不同）
 */
public class Main implements IXposedHookLoadPackage {

    static final String TARGET_PACKAGE = "com.zhipuai.qingyan";
    static final String TAG = "GLMKit";

    private static volatile Main INSTANCE;
    private static volatile ClassLoader hostClassLoader;

    // 捕获的 GLM 网络信息
    private volatile GlmCapture capture;
    private volatile Context appContext;
    private final AtomicBoolean realCallHooked = new AtomicBoolean(false);

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!TARGET_PACKAGE.equals(lpparam.packageName)) return;

        INSTANCE = this;
        hostClassLoader = lpparam.classLoader;
        log("GLMKit 模块加载，目标: " + lpparam.packageName);

        // 1. Hook Application.onCreate 获取 Context
        hookApplicationOnCreate(lpparam.classLoader);

        // 2. Hook OkHttpClient.Builder.build() 捕获客户端和拦截请求
        hookOkHttpBuilder(lpparam.classLoader);

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
    //  OkHttpClient.Builder.build() — 捕获 OkHttpClient 实例
    // ════════════════════════════════════════════════════════════
    private void hookOkHttpBuilder(ClassLoader cl) {
        try {
            Class<?> builderClass = cl.loadClass("okhttp3.OkHttpClient$Builder");
            XposedHelpers.findAndHookMethod(
                builderClass, "build",
                new XC_MethodHook() {
                    @Override
                    protected void afterHookedMethod(MethodHookParam param) {
                        Object client = param.result;
                        if (client == null) return;

                        // 保存捕获的 OkHttpClient
                        getCapture().setOkHttpClient(client);
                        log("捕获 OkHttpClient 实例");

                        // 通知模块自身进程：认证信息捕获成功
                        broadcastActivation("com.glmkit.proxy.HOOK_SUCCESS");

                        // 尝试添加网络拦截器来捕获请求详情
                        installCaptureInterceptor(client, cl);
                    }
                });
            log("Hook OkHttpClient.Builder.build() 成功");
        } catch (Throwable t) {
            log("hook OkHttp Builder 失败: " + t.getMessage());
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
                            // Retrofit.baseUrl() 返回 HttpUrl
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
        if (!realCallHooked.compareAndSet(false, true)) {
            return; // 已安装过 RealCall hook，避免重复捕获
        }
        try {
            // Hook RealCall.execute() 和 enqueue() 来捕获请求
            Class<?> realCallClass = cl.loadClass("okhttp3.internal.connection.RealCall");

            // Hook execute()
            XposedHelpers.findAndHookMethod(
                realCallClass, "execute",
                new XC_MethodHook() {
                    @Override
                    protected void beforeHookedMethod(MethodHookParam param) {
                        captureRequest(param.thisObject, cl);
                    }
                });

            // Hook enqueue()
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
            realCallHooked.set(false); // 重置标志，允许后续重试
            // RealCall 可能不存在或路径不同，尝试备用方案
            log("安装 RealCall 拦截器失败，尝试 Interceptor 方案: " + t.getMessage());
            installInterceptorChain(client, cl);
        }
    }

    private void installInterceptorChain(Object client, ClassLoader cl) {
        try {
            // 尝试通过 OkHttpClient.interceptors() 添加拦截器
            Method interceptorsMethod = client.getClass().getMethod("interceptors");
            Object interceptors = interceptorsMethod.invoke(client);
            if (interceptors instanceof java.util.List) {
                @SuppressWarnings("unchecked")
                java.util.List<Object> list = (java.util.List<Object>) interceptors;
                // 创建一个动态拦截器代理
                Object interceptorProxy = createInterceptorProxy(cl);
                if (interceptorProxy != null) {
                    list.add(0, interceptorProxy);
                    log("通过 interceptors() 添加捕获拦截器成功");
                }
            }
        } catch (Throwable t) {
            log("Interceptor 方案也失败: " + t.getMessage());
        }
    }

    private Object createInterceptorProxy(ClassLoader cl) {
        try {
            // 使用 Proxy 创建 Interceptor 动态代理
            Class<?> interceptorClass = cl.loadClass("okhttp3.Interceptor");
            return java.lang.reflect.Proxy.newProxyInstance(
                cl, new Class<?>[]{interceptorClass},
                (proxy, method, args) -> {
                    if ("intercept".equals(method.getName())) {
                        Object chain = args[0];
                        try {
                            // chain.request() 获取 Request
                            Method requestMethod = chain.getClass().getMethod("request");
                            Object request = requestMethod.invoke(chain);
                            extractRequestDetails(request, cl);
                            // chain.proceed(request) 继续请求
                            Method proceedMethod = chain.getClass().getMethod("proceed",
                                cl.loadClass("okhttp3.Request"));
                            return proceedMethod.invoke(chain, request);
                        } catch (Throwable ignored) {}
                    }
                    return null;
                });
        } catch (Throwable t) {
            log("创建拦截器代理失败: " + t.getMessage());
            return null;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  请求捕获 — 提取 URL、认证头
    // ════════════════════════════════════════════════════════════
    private void captureRequest(Object call, ClassLoader cl) {
        try {
            // RealCall.originalRequest 字段
            Object request = XposedHelpers.getObjectField(call, "originalRequest");
            if (request == null) {
                // 尝试 getOriginalRequest() 方法
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

    private void extractRequestDetails(Object request, ClassLoader cl) {
        try {
            // request.url() → HttpUrl
            Method urlMethod = request.getClass().getMethod("url");
            Object httpUrl = urlMethod.invoke(request);
            String urlStr = httpUrl.toString();

            // 只捕获 GLM 相关的 API 请求
            if (isGlmApiUrl(urlStr)) {
                getCapture().setApiUrl(urlStr);
                log("捕获 GLM API 请求 URL: " + urlStr);

                // request.headers() → Headers
                Method headersMethod = request.getClass().getMethod("headers");
                Object headers = headersMethod.invoke(request);

                // 遍历 headers 捕获认证信息
                extractAuthFromHeaders(headers, cl);
            }
        } catch (Throwable ignored) {}
    }

    private void extractAuthFromHeaders(Object headers, ClassLoader cl) {
        try {
            // Headers.size() / Headers.name(i) / Headers.value(i)
            Method sizeMethod = headers.getClass().getMethod("size");
            int size = (int) sizeMethod.invoke(headers);
            Method nameMethod = headers.getClass().getMethod("name", int.class);
            Method valueMethod = headers.getClass().getMethod("value", int.class);

            for (int i = 0; i < size; i++) {
                String name = (String) nameMethod.invoke(headers, i);
                String value = (String) valueMethod.invoke(headers, i);
                if (name == null) continue;

                String ln = name.toLowerCase();
                if ("authorization".equals(ln)) {
                    getCapture().setAuthToken(value);
                    log("捕获 Authorization 头");
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
        } catch (Throwable ignored) {}
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
                // 等待 OkHttp 客户端捕获
                int waited = 0;
                while (getCapture().getOkHttpClient() == null && waited < 30_000) {
                    Thread.sleep(500);
                    waited += 500;
                }

                if (getCapture().getOkHttpClient() == null) {
                    log("⚠️ 等待 OkHttp 客户端超时 (30s)，网关将以未就绪状态启动");
                }

                if (appContext == null) {
                    log("Context 为空，无法启动网关");
                    return;
                }

                Context ctx = appContext.getApplicationContext();

                // 读取配置的端口 — 使用 XSharedPreferences 读取模块自身的偏好
                // (模块运行在目标应用进程中，普通 SharedPreferences 读到的是目标应用的偏好)
                int port = 8765;
                try {
                    XSharedPreferences xPrefs = new XSharedPreferences("com.glmkit.proxy", "glmkit_settings");
                    xPrefs.reload();
                    xPrefs.makeReadable();
                    port = xPrefs.getInt("port", 8765);
                    LocalApiGateway.setListenPort(port);
                    log("配置监听端口: " + port + " (从模块偏好读取)");
                } catch (Throwable ignored) {}

                GlmBackend backend = new GlmBackend(getCapture());
                int actualPort = LocalApiGateway.start(ctx, backend);

                if (!LocalApiGateway.isRunning()) {
                    log("✗ 网关启动失败，所有端口均被占用，不发送启动广播");
                    return;
                }

                log("本地 API 网关已启动，实际端口: " + actualPort);

                // 通知模块自身进程：网关已启动
                Intent gatewayIntent = new Intent("com.glmkit.proxy.GATEWAY_STARTED");
                gatewayIntent.setPackage("com.glmkit.proxy");
                gatewayIntent.putExtra("port", actualPort);
                try {
                    ctx.sendBroadcast(gatewayIntent);
                    log("发送网关启动广播");
                } catch (Throwable ignored) {}

            } catch (Throwable t) {
                log("启动网关失败: " + t.getMessage());
            }
        }, "glmkit-gateway-init").start();
    }

    // ════════════════════════════════════════════════════════════
    //  激活广播 — 通知模块自身进程记录激活状态
    // ════════════════════════════════════════════════════════════

    /**
     * 向模块自身包发送显式广播，通知 XposedActivationReceiver 记录状态。
     * 广播从目标应用进程发出，由模块自身进程的 Receiver 接收。
     */
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
        XposedBridge.log("[" + TAG + "] " + msg);
    }
}
