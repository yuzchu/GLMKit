package com.glmkit.probe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.util.Log;

/**
 * v1.0.41: 前台保活服务 — 在 GLMKit APP 自身进程中启动并运行本地 API 网关。
 *
 * <p>架构变更：
 * <ul>
 *   <li>旧架构：网关运行在智谱清言进程 → 切回 APP 时进程被杀 → 网关死</li>
 *   <li>新架构：网关运行在 GLMKit APP 的本 Service 中 → 前台 Service 保活 → 不被杀</li>
 * </ul>
 *
 * <p>模块（运行在智谱清言进程）只负责捕获 auth → 写 /sdcard/glmkit_auth.json → 广播通知。
 * 本 Service 读取该文件获取 auth，通过 GlmBackend 发起请求。</p>
 */
public final class LocalApiKeepAliveService extends Service {

    static final String TARGET_PACKAGE = "com.zhipuai.qingyan";
    static final String ACTION_START = "com.glmkit.probe.action.START_LOCAL_API_KEEPALIVE";
    static final String EXTRA_CONTROL_TOKEN = "glmkit_control_token";
    static final String CONTROL_TOKEN = "glmkit-local-api-keepalive-v1";

    private static final String PREFS = "glmkit_local_api_keepalive";
    private static final String KEY_REQUESTED = "requested";
    private static final String CHANNEL_ID = "glmkit_local_api";
    private static final int NOTIFICATION_ID = 0x614D;
    private static final long HEARTBEAT_MS = 10_000L;
    private static final String TAG = "GLMKit-KeepAlive";

    private static volatile boolean running;
    private static volatile int gatewayPort;
    private static volatile String lastError = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;
    private GlmCapture capture;
    private GlmBackend backend;

    // ════════════════════════════════════════════════════════════
    //  心跳 Runnable — 定期检查网关状态并更新通知
    // ════════════════════════════════════════════════════════════

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!isRequested(LocalApiKeepAliveService.this)) {
                stopSelf();
                return;
            }

            // 检查网关状态
            if (!LocalApiGateway.isRunning()) {
                Log.w(TAG, "网关未运行，尝试重启...");
                startGateway();
            }

            // 尝试从共享文件刷新 auth
            if (capture != null && capture.getBestAuth() == null) {
                boolean loaded = capture.loadFromSharedFile();
                if (loaded) {
                    Log.i(TAG, "从共享文件加载 auth 成功");
                    updateNotification();
                }
            }

            handler.postDelayed(this, HEARTBEAT_MS);
        }
    };

    // ════════════════════════════════════════════════════════════
    //  静态控制接口
    // ════════════════════════════════════════════════════════════

    static boolean setEnabled(Context context, boolean enabled) {
        if (context == null) {
            lastError = "模块上下文不可用";
            return false;
        }
        Context app = context.getApplicationContext();
        requestedPrefs(app).edit().putBoolean(KEY_REQUESTED, enabled).apply();
        Intent intent = new Intent(app, LocalApiKeepAliveService.class)
                .setAction(ACTION_START)
                .putExtra(EXTRA_CONTROL_TOKEN, CONTROL_TOKEN);
        try {
            if (enabled) {
                if (Build.VERSION.SDK_INT >= 26) app.startForegroundService(intent);
                else app.startService(intent);
            } else {
                app.stopService(intent);
            }
            return true;
        } catch (Throwable t) {
            lastError = (enabled ? "启动" : "停止") + "前台保活失败：" + safeMessage(t);
            Log.w(TAG, lastError, t);
            return false;
        }
    }

    static void putStatus(android.os.Bundle result) {
        result.putBoolean("running", running);
        result.putBoolean("requested", running);
        result.putBoolean("gateway_running", LocalApiGateway.isRunning());
        result.putInt("gateway_port", gatewayPort);
        result.putString("error", lastError == null ? "" : lastError);
    }

    // ════════════════════════════════════════════════════════════
    //  Service 生命周期
    // ════════════════════════════════════════════════════════════

    @Override public void onCreate() {
        super.onCreate();
        try {
            createNotificationChannel();
            startForeground(NOTIFICATION_ID, buildNotification());
            acquireWakeLock();
            running = true;
            lastError = "";

            // 创建 GlmCapture 并从共享文件加载 auth
            capture = new GlmCapture();
            boolean loaded = capture.loadFromSharedFile();
            Log.i(TAG, "初始加载 auth: " + (loaded ? "成功" : "失败（等待模块捕获）"));

            // 创建 GlmBackend
            backend = new GlmBackend(capture);

            // 启动网关
            startGateway();

            handler.removeCallbacks(heartbeat);
            handler.post(heartbeat);
            Log.i(TAG, "GLMKit 网关服务已启动（自身进程）");
        } catch (Throwable t) {
            lastError = "服务初始化失败：" + safeMessage(t);
            Log.e(TAG, lastError, t);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean systemRestart = intent == null && isRequested(this);
        if (!systemRestart && (intent == null || !ACTION_START.equals(intent.getAction())
                || !CONTROL_TOKEN.equals(intent.getStringExtra(EXTRA_CONTROL_TOKEN)))) {
            lastError = "拒绝了无效的启动请求";
            requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, false).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, true).apply();
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        handler.removeCallbacks(heartbeat);
        releaseWakeLock();
        try { LocalApiGateway.stop(); } catch (Throwable ignored) {}
        try { stopForeground(true); } catch (Throwable ignored) {}
        Log.i(TAG, "GLMKit 网关服务已停止");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }

    // ════════════════════════════════════════════════════════════
    //  网关启动
    // ════════════════════════════════════════════════════════════

    private void startGateway() {
        try {
            // 读取配置
            SharedPreferences settings = getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE);
            int port = settings.getInt("port", 8765);
            String apiKey = settings.getString("api_key", null);

            LocalApiGateway.setListenPort(port);
            LocalApiGateway.setApiKey(apiKey);
            Log.i(TAG, "配置端口: " + port + ", API Key: " + (apiKey != null && !apiKey.isEmpty() ? "已启用" : "未启用"));

            // 启动网关
            int actualPort = LocalApiGateway.start(this, backend);
            gatewayPort = actualPort;

            if (LocalApiGateway.isRunning()) {
                Log.i(TAG, "✓ 网关已启动，端口: " + actualPort);
                lastError = "";

                // 保存端口到 SharedPreferences
                settings.edit()
                        .putBoolean("gateway_running", true)
                        .putInt("gateway_port", actualPort)
                        .apply();

                updateNotification();
            } else {
                lastError = "网关启动失败（端口被占用）";
                Log.e(TAG, lastError);
            }
        } catch (Throwable t) {
            lastError = "网关启动异常: " + safeMessage(t);
            Log.e(TAG, lastError, t);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  通知与 WakeLock
    // ════════════════════════════════════════════════════════════

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager manager = (NotificationManager)
                getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                "GLM 本地 API", NotificationManager.IMPORTANCE_LOW);
        channel.setDescription("GLMKit 本地 API 网关运行中");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = new Intent(this, SettingsActivity.class);
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        int flags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
        PendingIntent pending = PendingIntent.getActivity(this, 0, launch, flags);

        String content = LocalApiGateway.isRunning()
                ? "本地 API 网关运行中 (端口 " + gatewayPort + ")"
                : "正在启动网关...";

        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("GLMKit 网关")
                .setContentText(content)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false)
                .setContentIntent(pending);
        return builder.build();
    }

    private void updateNotification() {
        try {
            NotificationManager nm = (NotificationManager)
                    getSystemService(Context.NOTIFICATION_SERVICE);
            if (nm != null) nm.notify(NOTIFICATION_ID, buildNotification());
        } catch (Throwable ignored) {}
    }

    private void acquireWakeLock() {
        try {
            PowerManager power = (PowerManager) getSystemService(Context.POWER_SERVICE);
            if (power == null) return;
            wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,
                    "GLMKit:LocalApiKeepAlive");
            wakeLock.setReferenceCounted(false);
            wakeLock.acquire();
        } catch (Throwable t) {
            lastError = "CPU 保活不可用：" + safeMessage(t);
            Log.w(TAG, lastError, t);
        }
    }

    private void releaseWakeLock() {
        try {
            if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        } catch (Throwable ignored) {}
        wakeLock = null;
    }

    // ════════════════════════════════════════════════════════════
    //  工具方法
    // ════════════════════════════════════════════════════════════

    private static SharedPreferences requestedPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isRequested(Context context) {
        return requestedPrefs(context).getBoolean(KEY_REQUESTED, false);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.length() == 0
                ? t.getClass().getSimpleName() : message;
    }
}
