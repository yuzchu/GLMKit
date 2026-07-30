package com.glmkit.probe;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

/**
 * Xposed 激活状态广播接收器。
 *
 * <p>监听以下广播：
 * <ul>
 *   <li>{@code com.glmkit.proxy.HOOK_STARTED} — Xposed 在目标进程中成功注入并开始 hook</li>
 *   <li>{@code com.glmkit.proxy.HOOK_SUCCESS} — 认证信息捕获成功</li>
 *   <li>{@code com.glmkit.proxy.GATEWAY_STARTED} — 本地网关已启动</li>
 * </ul></p>
 *
 * <p>接收器将状态持久化到 SharedPreferences，供 SettingsActivity 查询显示。</p>
 */
public final class XposedActivationReceiver extends BroadcastReceiver {

    private static final String TAG = "GLMKit-Receiver";
    private static final String PREFS = "glmkit_settings";

    public static final String ACTION_HOOK_STARTED   = "com.glmkit.proxy.HOOK_STARTED";
    public static final String ACTION_HOOK_SUCCESS   = "com.glmkit.proxy.HOOK_SUCCESS";
    public static final String ACTION_AUTH_CAPTURED  = "com.glmkit.proxy.AUTH_CAPTURED";
    public static final String ACTION_GATEWAY_STARTED = "com.glmkit.proxy.GATEWAY_STARTED";

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null) return;
        String action = intent.getAction();
        if (action == null) return;
        Log.i(TAG, "Received broadcast: " + action);

        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();

        switch (action) {
            case ACTION_HOOK_STARTED:
                editor.putBoolean("xposed_hooked", true)
                      .putLong("hook_started_at", System.currentTimeMillis())
                      .apply();
                Log.i(TAG, "Xposed hook 已启动");
                break;

            case ACTION_HOOK_SUCCESS:
                editor.putBoolean("auth_captured", true)
                      .putLong("auth_captured_at", System.currentTimeMillis())
                      .apply();
                Log.i(TAG, "认证信息已捕获");
                break;

            case ACTION_AUTH_CAPTURED:
                // v1.0.41: 模块已将 auth 写入 /sdcard/glmkit_auth.json
                editor.putBoolean("auth_captured", true)
                      .putLong("auth_captured_at", System.currentTimeMillis())
                      .apply();
                Log.i(TAG, "认证信息已捕获（从共享文件）");
                break;

            case ACTION_GATEWAY_STARTED:
                int port = intent.getIntExtra("port", 8765);
                editor.putBoolean("gateway_running", true)
                      .putInt("gateway_port", port)
                      .putLong("gateway_started_at", System.currentTimeMillis())
                      .apply();
                Log.i(TAG, "本地网关已启动，端口 " + port);
                break;

            default:
                Log.w(TAG, "未知广播: " + action);
        }
    }
}
