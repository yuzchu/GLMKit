package com.glmkit.probe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 保活控制 Activity — 用户界面开关本地 API 前台保活服务。
 *
 * <p>显示当前网关状态、保活服务状态，提供开关按钮。
 * 在 Xposed 管理器（如 LSPosed）中可从模块信息页跳转到此 Activity。</p>
 */
public final class LocalApiKeepAliveActivity extends Activity {

    private static final String TAG = "GLMKit-KeepAliveActivity";
    private static final long REFRESH_MS = 2_000L;

    private final Handler handler = new Handler(Looper.getMainLooper());

    private Switch keepAliveSwitch;
    private TextView statusText;
    private TextView errorText;
    private Button openTargetButton;
    private Button refreshButton;

    private boolean refreshing = false;

    // ════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GLM 本地 API 保活");
        buildUi();
        refreshStatus();
    }

    @Override protected void onResume() {
        super.onResume();
        handler.postDelayed(refreshLoop, REFRESH_MS);
    }

    @Override protected void onPause() {
        super.onPause();
        handler.removeCallbacks(refreshLoop);
    }

    // ════════════════════════════════════════════════════════════
    //  UI 构建
    // ════════════════════════════════════════════════════════════

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);
        root.setGravity(Gravity.CENTER_HORIZONTAL);

        // 标题
        TextView title = new TextView(this);
        title.setText("GLM 本地 API 反代");
        title.setTextSize(20f);
        title.setPadding(0, 0, 0, 24);
        root.addView(title);

        // 说明
        TextView desc = new TextView(this);
        desc.setText("通过 Xposed 注入智谱清言，在本地启动 OpenAI 兼容 API 网关。\n"
                + "开启保活后，目标应用进程将保持活跃，确保 API 持续可用。\n\n"
                + "默认监听端口：8765\n"
                + "API 端点：http://127.0.0.1:8765/v1/chat/completions\n"
                + "模型列表：http://127.0.0.1:8765/v1/models");
        desc.setTextSize(14f);
        desc.setPadding(0, 0, 0, 32);
        root.addView(desc);

        // 保活开关
        LinearLayout switchRow = new LinearLayout(this);
        switchRow.setOrientation(LinearLayout.HORIZONTAL);
        switchRow.setGravity(Gravity.CENTER_VERTICAL);
        switchRow.setPadding(0, 0, 0, 16);

        keepAliveSwitch = new Switch(this);
        keepAliveSwitch.setText("启用前台保活");
        keepAliveSwitch.setOnCheckedChangeListener((view, checked) -> {
            boolean ok = LocalApiKeepAliveService.setEnabled(this, checked);
            if (!ok) {
                keepAliveSwitch.setChecked(!checked);
                Toast.makeText(this, "操作失败，请检查权限",
                        Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        });
        switchRow.addView(keepAliveSwitch);
        root.addView(switchRow);

        // 状态显示
        statusText = new TextView(this);
        statusText.setTextSize(14f);
        statusText.setPadding(0, 16, 0, 16);
        statusText.setText("正在查询状态...");
        root.addView(statusText);

        // 错误显示
        errorText = new TextView(this);
        errorText.setTextSize(13f);
        errorText.setTextColor(0xFFFF6600);
        errorText.setPadding(0, 8, 0, 16);
        root.addView(errorText);

        // 按钮行
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, 16, 0, 0);

        openTargetButton = new Button(this);
        openTargetButton.setText("打开智谱清言");
        openTargetButton.setOnClickListener(v -> openTargetApp());
        buttonRow.addView(openTargetButton);

        refreshButton = new Button(this);
        refreshButton.setText("刷新状态");
        refreshButton.setOnClickListener(v -> refreshStatus());
        LinearLayout.LayoutParams refreshParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        refreshParams.leftMargin = 24;
        buttonRow.addView(refreshButton, refreshParams);
        root.addView(buttonRow);

        // 底部提示
        TextView footer = new TextView(this);
        footer.setText("\n提示：请确保在 LSPosed/Xposed 中已勾选智谱清言作为作用域。");
        footer.setTextSize(12f);
        footer.setTextColor(0xFF666666);
        footer.setPadding(0, 32, 0, 0);
        root.addView(footer);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ════════════════════════════════════════════════════════════
    //  状态刷新
    // ════════════════════════════════════════════════════════════

    private final Runnable refreshLoop = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, REFRESH_MS);
        }
    };

    private void refreshStatus() {
        if (refreshing) return;
        refreshing = true;

        // 从服务获取状态
        Bundle status = new Bundle();
        LocalApiKeepAliveService.putStatus(status);

        boolean running = status.getBoolean("running", false);
        boolean gatewayRunning = status.getBoolean("gateway_running", false);
        long broadcastAge = status.getLong("last_broadcast_age_ms", -1L);
        long ackAge = status.getLong("last_ack_age_ms", -1L);
        String error = status.getString("error", "");

        // 更新开关状态（避免触发 listener）
        keepAliveSwitch.setOnCheckedChangeListener(null);
        keepAliveSwitch.setChecked(running);
        keepAliveSwitch.setOnCheckedChangeListener((view, checked) -> {
            boolean ok = LocalApiKeepAliveService.setEnabled(this, checked);
            if (!ok) {
                keepAliveSwitch.setChecked(!checked);
                Toast.makeText(this, "操作失败，请检查权限",
                        Toast.LENGTH_SHORT).show();
            }
            refreshStatus();
        });

        // 构建状态文本
        StringBuilder sb = new StringBuilder();
        sb.append("保活服务：").append(running ? "✅ 运行中" : "❌ 已停止").append('\n');
        sb.append("本地网关：").append(gatewayRunning ? "✅ 监听中" : "⚠️ 未启动").append('\n');
        sb.append("心跳广播：").append(formatAge(broadcastAge)).append('\n');
        sb.append("最近确认：").append(formatAge(ackAge));
        statusText.setText(sb.toString());

        // 错误显示
        if (TextUtils.isEmpty(error)) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(error);
            errorText.setVisibility(View.VISIBLE);
        }

        refreshing = false;
    }

    // ════════════════════════════════════════════════════════════
    //  操作
    // ════════════════════════════════════════════════════════════

    private void openTargetApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(
                    LocalApiKeepAliveService.TARGET_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                Toast.makeText(this, "未找到智谱清言应用",
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ════════════════════════════════════════════════════════════
    //  工具
    // ════════════════════════════════════════════════════════════

    private static String formatAge(long ageMs) {
        if (ageMs < 0L) return "从未";
        if (ageMs < 1_000L) return "刚刚";
        if (ageMs < 60_000L) return (ageMs / 1000L) + " 秒前";
        return (ageMs / 60_000L) + " 分钟前";
    }
}
