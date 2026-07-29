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
 * 前台保活服务 — 防止智谱清言进程被系统冻结，确保本地 API 网关持续可用。
 *
 * <p>通过周期性向目标应用发送轻量 intent 保持其进程不被冻结。
 * 前台通知 + WakeLock 双重保活。</p>
 */
public final class LocalApiKeepAliveService extends Service {

    static final String TARGET_PACKAGE = "com.zhipuai.qingyan";
    static final String ACTION_HEARTBEAT = "com.glmkit.probe.action.LOCAL_API_KEEPALIVE";
    static final String ACTION_START = "com.glmkit.probe.action.START_LOCAL_API_KEEPALIVE";
    static final String EXTRA_CONTROL_TOKEN = "glmkit_control_token";
    static final String CONTROL_TOKEN = "glmkit-local-api-keepalive-v1";

    private static final String PREFS = "glmkit_local_api_keepalive";
    private static final String KEY_REQUESTED = "requested";
    private static final String CHANNEL_ID = "glmkit_local_api";
    private static final int NOTIFICATION_ID = 0x614D;
    private static final long HEARTBEAT_MS = 5_000L;
    private static final long ACK_TIMEOUT_MS = 120_000L;
    private static final String TAG = "GLMKit-KeepAlive";

    private static volatile boolean running;
    private static volatile long startedElapsed;
    private static volatile long lastBroadcastElapsed;
    private static volatile long lastAckElapsed;
    private static volatile boolean lastGatewayRunning;
    private static volatile String lastError = "";

    private final Handler handler = new Handler(Looper.getMainLooper());
    private PowerManager.WakeLock wakeLock;

    // ════════════════════════════════════════════════════════════
    //  心跳 Runnable
    // ════════════════════════════════════════════════════════════

    private final Runnable heartbeat = new Runnable() {
        @Override public void run() {
            if (!isRequested(LocalApiKeepAliveService.this)) {
                stopSelf();
                return;
            }

            long now = SystemClock.elapsedRealtime();

            // 超时检查
            if (startedElapsed > 0L && now - startedElapsed >= ACK_TIMEOUT_MS
                    && (lastAckElapsed <= 0L || now - lastAckElapsed >= ACK_TIMEOUT_MS)) {
                lastError = "智谱清言长时间未确认保活，服务已自动停止";
                Log.w(TAG, lastError);
                requestedPrefs(LocalApiKeepAliveService.this).edit()
                        .putBoolean(KEY_REQUESTED, false).apply();
                stopSelf();
                return;
            }

            // 发送心跳 — 向目标应用发送轻量 intent 保持进程活跃
            try {
                Intent ping = new Intent(ACTION_HEARTBEAT);
                ping.setPackage(TARGET_PACKAGE);
                ping.putExtra(EXTRA_CONTROL_TOKEN, CONTROL_TOKEN);
                ping.addFlags(Intent.FLAG_RECEIVER_FOREGROUND);
                sendBroadcast(ping);
                lastBroadcastElapsed = now;
            } catch (Throwable t) {
                lastError = "发送保活心跳失败：" + safeMessage(t);
                Log.w(TAG, lastError, t);
            }

            // 自确认 — 网关运行在目标应用进程中，通过 HTTP 检测状态
            lastAckElapsed = now;
            lastGatewayRunning = checkGatewayHttp();

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

    static void acknowledge(boolean enabled, boolean gatewayRunning) {
        lastAckElapsed = SystemClock.elapsedRealtime();
        lastGatewayRunning = gatewayRunning;
        if (!enabled) lastError = "智谱清言已关闭本地 API";
    }

    static void putStatus(android.os.Bundle result) {
        long now = SystemClock.elapsedRealtime();
        result.putBoolean("running", running);
        result.putBoolean("requested", running || startedElapsed > 0L);
        result.putBoolean("gateway_running", lastGatewayRunning);
        result.putLong("last_broadcast_age_ms", age(now, lastBroadcastElapsed));
        result.putLong("last_ack_age_ms", age(now, lastAckElapsed));
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
            startedElapsed = SystemClock.elapsedRealtime();
            lastError = "";
            handler.removeCallbacks(heartbeat);
            handler.post(heartbeat);
            Log.i(TAG, "GLM local API foreground keepalive started");
        } catch (Throwable t) {
            lastError = "前台保活初始化失败：" + safeMessage(t);
            Log.e(TAG, lastError, t);
            stopSelf();
        }
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        boolean systemRestart = intent == null && isRequested(this);
        if (!systemRestart && (intent == null || !ACTION_START.equals(intent.getAction())
                || !CONTROL_TOKEN.equals(intent.getStringExtra(EXTRA_CONTROL_TOKEN)))) {
            lastError = "拒绝了无效的保活启动请求";
            requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, false).apply();
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        requestedPrefs(this).edit().putBoolean(KEY_REQUESTED, true).apply();
        if (!isRequested(this)) {
            stopSelf(startId);
            return START_NOT_STICKY;
        }
        handler.removeCallbacks(heartbeat);
        handler.post(heartbeat);
        return START_STICKY;
    }

    @Override public void onDestroy() {
        running = false;
        lastGatewayRunning = false;
        startedElapsed = 0L;
        handler.removeCallbacks(heartbeat);
        releaseWakeLock();
        try { stopForeground(true); } catch (Throwable ignored) {}
        Log.i(TAG, "GLM local API foreground keepalive stopped");
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
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
        channel.setDescription("保持 GLM 本地 API 与 SSE 流在后台可用");
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private Notification buildNotification() {
        Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
        PendingIntent pending = null;
        if (launch != null) {
            int flags = PendingIntent.FLAG_UPDATE_CURRENT;
            if (Build.VERSION.SDK_INT >= 23) flags |= PendingIntent.FLAG_IMMUTABLE;
            pending = PendingIntent.getActivity(this, 0, launch, flags);
        }
        Notification.Builder builder = Build.VERSION.SDK_INT >= 26
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        builder.setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("GLM 本地 API 正在运行")
                .setContentText("正在保持后台监听与流式响应稳定")
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setShowWhen(false);
        if (pending != null) builder.setContentIntent(pending);
        return builder.build();
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

    /**
     * 通过 HTTP /healthz 检测网关状态。
     * 网关运行在目标应用进程中，无法通过静态变量检测。
     */
    private boolean checkGatewayHttp() {
        try {
            int port = getSharedPreferences("glmkit_settings", Context.MODE_PRIVATE)
                    .getInt("port", 8765);
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection)
                new java.net.URL("http://127.0.0.1:" + port + "/healthz").openConnection();
            conn.setConnectTimeout(1000);
            conn.setReadTimeout(1000);
            conn.setRequestMethod("GET");
            boolean ok = (conn.getResponseCode() == 200);
            conn.disconnect();
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static SharedPreferences requestedPrefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static boolean isRequested(Context context) {
        return requestedPrefs(context).getBoolean(KEY_REQUESTED, false);
    }

    private static long age(long now, long value) {
        return value <= 0L ? -1L : Math.max(0L, now - value);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "unknown";
        String message = t.getMessage();
        return message == null || message.length() == 0
                ? t.getClass().getSimpleName() : message;
    }
}
