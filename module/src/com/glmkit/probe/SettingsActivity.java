package com.glmkit.probe;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 模块设置 Activity — 主入口界面。
 *
 * <p>显示：
 * <ul>
 *   <li>模块激活状态（Xposed 是否已注入）</li>
 *   <li>目标应用安装状态</li>
 *   <li>本地 API 端口配置</li>
 *   <li>保活服务开关</li>
 *   <li>使用说明</li>
 * </ul></p>
 */
public final class SettingsActivity extends Activity {

    private static final String PREFS = "glmkit_settings";
    private static final String KEY_PORT = "port";
    private static final String KEY_KEEPALIVE = "keepalive";
    private static final String KEY_API_KEY = "api_key";
    private static final int DEFAULT_PORT = 8765;
    private static final String TARGET_PACKAGE = "com.zhipuai.qingyan";

    private TextView activationStatus;
    private TextView targetStatus;
    private TextView gatewayStatus;
    private EditText portInput;
    private Switch keepAliveSwitch;
    private TextView errorText;
    private TextView diagResultText;

    // ════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GLMKit 设置");
        requestNotificationPermission();
        buildUi();
        refreshStatus();
    }

    /**
     * Android 13+ 需要运行时请求 POST_NOTIFICATIONS 权限，
     * 否则前台 Service 通知不显示，Service 可能被系统杀死。
     */
    private void requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            String perm = "android.permission.POST_NOTIFICATIONS";
            if (checkSelfPermission(perm) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{perm}, 1001);
            }
        }
    }

    @Override protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    // ════════════════════════════════════════════════════════════
    //  UI 构建
    // ════════════════════════════════════════════════════════════

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(48, 48, 48, 48);

        // ── 标题 ──
        TextView title = new TextView(this);
        title.setText("GLMKit — GLM 本地 API 反代");
        title.setTextSize(22f);
        title.setTextColor(0xFF1A1A1A);
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Xposed 增强模块 for 智谱清言");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF666666);
        subtitle.setPadding(0, 0, 0, 24);
        root.addView(subtitle);

        // ── 激活状态 ──
        root.addView(sectionLabel("模块状态"));
        activationStatus = new TextView(this);
        activationStatus.setTextSize(14f);
        activationStatus.setPadding(16, 8, 0, 8);
        root.addView(activationStatus);

        // ── 目标应用状态 ──
        targetStatus = new TextView(this);
        targetStatus.setTextSize(14f);
        targetStatus.setPadding(16, 0, 0, 8);
        root.addView(targetStatus);

        // ── 网关状态 ──
        gatewayStatus = new TextView(this);
        gatewayStatus.setTextSize(14f);
        gatewayStatus.setPadding(16, 0, 0, 16);
        root.addView(gatewayStatus);

        // ── 端口配置 ──
        root.addView(sectionLabel("本地 API 端口"));
        LinearLayout portRow = new LinearLayout(this);
        portRow.setOrientation(LinearLayout.HORIZONTAL);
        portRow.setGravity(Gravity.CENTER_VERTICAL);
        portRow.setPadding(16, 8, 0, 8);

        portInput = new EditText(this);
        portInput.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        portInput.setHint("端口号 (默认 8765)");
        portInput.setText(String.valueOf(getSavedPort()));
        LinearLayout.LayoutParams portParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        portInput.setLayoutParams(portParams);
        portRow.addView(portInput);

        Button savePortBtn = new Button(this);
        savePortBtn.setText("保存");
        savePortBtn.setOnClickListener(v -> savePort());
        LinearLayout.LayoutParams btnParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        btnParams.leftMargin = 16;
        portRow.addView(savePortBtn, btnParams);
        root.addView(portRow);

        // ── API 地址显示 ──
        TextView apiLabel = new TextView(this);
        apiLabel.setText("API 端点：");
        apiLabel.setTextSize(13f);
        apiLabel.setTextColor(0xFF666666);
        apiLabel.setPadding(16, 8, 0, 0);
        root.addView(apiLabel);

        TextView apiUrl = new TextView(this);
        apiUrl.setText("http://127.0.0.1:" + getSavedPort() + "/v1/chat/completions");
        apiUrl.setTextSize(13f);
        apiUrl.setTextColor(0xFF0066CC);
        apiUrl.setPadding(16, 0, 0, 0);
        apiUrl.setId(View.generateViewId());
        apiUrl.setTag("api_url");
        root.addView(apiUrl);

        Button copyAddrBtn = new Button(this);
        copyAddrBtn.setText("📋 复制 API 地址");
        copyAddrBtn.setOnClickListener(v -> {
            String addr = "http://127.0.0.1:" + getSavedPort() + "/v1";
            copyToClipboard("API 地址", addr);
        });
        LinearLayout.LayoutParams copyAddrParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        copyAddrParams.leftMargin = 16;
        // Use a horizontal row for the copy button
        LinearLayout addrRow = new LinearLayout(this);
        addrRow.setOrientation(LinearLayout.HORIZONTAL);
        addrRow.setGravity(Gravity.CENTER_VERTICAL);
        addrRow.setPadding(16, 4, 0, 16);
        addrRow.addView(copyAddrBtn);
        root.addView(addrRow);

        // ── 自定义 API Key ──
        root.addView(sectionLabel("API Key（可选）"));
        LinearLayout keyRow = new LinearLayout(this);
        keyRow.setOrientation(LinearLayout.HORIZONTAL);
        keyRow.setGravity(Gravity.CENTER_VERTICAL);
        keyRow.setPadding(16, 8, 0, 8);

        EditText apiKeyInput = new EditText(this);
        apiKeyInput.setHint("设置自定义 Key（留空则不验证）");
        String savedKey = getSavedApiKey();
        if (!TextUtils.isEmpty(savedKey)) {
            apiKeyInput.setText(savedKey);
        }
        LinearLayout.LayoutParams keyInputParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        apiKeyInput.setLayoutParams(keyInputParams);
        keyRow.addView(apiKeyInput);

        Button saveKeyBtn = new Button(this);
        saveKeyBtn.setText("保存");
        saveKeyBtn.setOnClickListener(v -> {
            String key = apiKeyInput.getText().toString().trim();
            saveApiKey(key);
        });
        LinearLayout.LayoutParams saveKeyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        saveKeyParams.leftMargin = 16;
        keyRow.addView(saveKeyBtn, saveKeyParams);
        root.addView(keyRow);

        // 复制 Key 按钮 + 说明
        LinearLayout keyActionRow = new LinearLayout(this);
        keyActionRow.setOrientation(LinearLayout.HORIZONTAL);
        keyActionRow.setGravity(Gravity.CENTER_VERTICAL);
        keyActionRow.setPadding(16, 0, 0, 8);

        Button copyKeyBtn = new Button(this);
        copyKeyBtn.setText("📋 复制 Key");
        copyKeyBtn.setOnClickListener(v -> {
            String key = getSavedApiKey();
            if (TextUtils.isEmpty(key)) {
                Toast.makeText(this, "请先设置并保存 API Key", Toast.LENGTH_SHORT).show();
            } else {
                copyToClipboard("API Key", key);
            }
        });
        keyActionRow.addView(copyKeyBtn);

        Button clearKeyBtn = new Button(this);
        clearKeyBtn.setText("清除");
        clearKeyBtn.setOnClickListener(v -> {
            apiKeyInput.setText("");
            saveApiKey("");
            Toast.makeText(this, "API Key 已清除，网关不再验证", Toast.LENGTH_SHORT).show();
        });
        LinearLayout.LayoutParams clearKeyParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        clearKeyParams.leftMargin = 16;
        keyActionRow.addView(clearKeyBtn, clearKeyParams);
        root.addView(keyActionRow);

        TextView keyDesc = new TextView(this);
        keyDesc.setText("设置后，请求需携带 Authorization: Bearer <key>。\n"
                + "留空则不验证（任何本地应用均可访问）。\n"
                + "修改后点击「重启网关」生效。");
        keyDesc.setTextSize(12f);
        keyDesc.setTextColor(0xFF666666);
        keyDesc.setPadding(16, 0, 0, 16);
        root.addView(keyDesc);

        // ── 保活开关 ──
        root.addView(sectionLabel("保活服务"));
        LinearLayout keepAliveRow = new LinearLayout(this);
        keepAliveRow.setOrientation(LinearLayout.HORIZONTAL);
        keepAliveRow.setGravity(Gravity.CENTER_VERTICAL);
        keepAliveRow.setPadding(16, 8, 0, 8);

        keepAliveSwitch = new Switch(this);
        keepAliveSwitch.setText("启用前台保活");
        keepAliveSwitch.setChecked(getSavedKeepAlive());
        keepAliveSwitch.setOnCheckedChangeListener((view, checked) -> {
            saveKeepAlive(checked);
            boolean ok = LocalApiKeepAliveService.setEnabled(this, checked);
            if (!ok) {
                keepAliveSwitch.setChecked(!checked);
                Toast.makeText(this, "操作失败", Toast.LENGTH_SHORT).show();
            }
        });
        keepAliveRow.addView(keepAliveSwitch);
        root.addView(keepAliveRow);

        TextView keepAliveDesc = new TextView(this);
        keepAliveDesc.setText("防止智谱清言进程被系统冻结，确保本地 API 持续可用");
        keepAliveDesc.setTextSize(12f);
        keepAliveDesc.setTextColor(0xFF666666);
        keepAliveDesc.setPadding(16, 0, 0, 16);
        root.addView(keepAliveDesc);

        // ── 错误显示 ──
        errorText = new TextView(this);
        errorText.setTextSize(13f);
        errorText.setTextColor(0xFFFF6600);
        errorText.setPadding(0, 8, 0, 8);
        errorText.setVisibility(View.GONE);
        root.addView(errorText);

        // ── 操作按钮 ──
        LinearLayout buttonRow = new LinearLayout(this);
        buttonRow.setOrientation(LinearLayout.HORIZONTAL);
        buttonRow.setGravity(Gravity.CENTER);
        buttonRow.setPadding(0, 16, 0, 16);

        Button openTargetBtn = new Button(this);
        openTargetBtn.setText("打开智谱清言");
        openTargetBtn.setOnClickListener(v -> openTargetApp());
        buttonRow.addView(openTargetBtn);

        Button openKeepAliveBtn = new Button(this);
        openKeepAliveBtn.setText("保活管理");
        openKeepAliveBtn.setOnClickListener(v -> {
            Intent intent = new Intent(this, LocalApiKeepAliveActivity.class);
            startActivity(intent);
        });
        LinearLayout.LayoutParams kaParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        kaParams.leftMargin = 16;
        buttonRow.addView(openKeepAliveBtn, kaParams);
        root.addView(buttonRow);

        // ── 网关控制 ──
        root.addView(sectionLabel("网关控制"));
        LinearLayout gwCtrlRow = new LinearLayout(this);
        gwCtrlRow.setOrientation(LinearLayout.HORIZONTAL);
        gwCtrlRow.setGravity(Gravity.CENTER);
        gwCtrlRow.setPadding(0, 8, 0, 8);

        Button startGwBtn = new Button(this);
        startGwBtn.setText("▶ 启动网关");
        startGwBtn.setOnClickListener(v -> startGateway());
        gwCtrlRow.addView(startGwBtn);

        Button stopGwBtn = new Button(this);
        stopGwBtn.setText("⏹ 停止网关");
        stopGwBtn.setOnClickListener(v -> stopGateway());
        LinearLayout.LayoutParams sgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        sgParams.leftMargin = 16;
        gwCtrlRow.addView(stopGwBtn, sgParams);

        Button restartGwBtn = new Button(this);
        restartGwBtn.setText("🔄 重启网关");
        restartGwBtn.setOnClickListener(v -> restartGateway());
        LinearLayout.LayoutParams rgParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rgParams.leftMargin = 16;
        gwCtrlRow.addView(restartGwBtn, rgParams);
        root.addView(gwCtrlRow);

        TextView gwCtrlDesc = new TextView(this);
        gwCtrlDesc.setText("启动：在 GLMKit APP 进程中直接启动网关\n"
                + "停止：关闭网关\n"
                + "重启：停止后重新启动（重新加载端口、API Key）\n"
                + "网关在 GLMKit APP 自身进程中运行，不依赖智谱清言");
        gwCtrlDesc.setTextSize(12f);
        gwCtrlDesc.setTextColor(0xFF666666);
        gwCtrlDesc.setPadding(16, 0, 0, 16);
        root.addView(gwCtrlDesc);

        // ── 诊断测试 ──
        root.addView(sectionLabel("诊断测试"));
        LinearLayout diagRow = new LinearLayout(this);
        diagRow.setOrientation(LinearLayout.HORIZONTAL);
        diagRow.setGravity(Gravity.CENTER);
        diagRow.setPadding(0, 8, 0, 8);

        Button testGatewayBtn = new Button(this);
        testGatewayBtn.setText("测试网关");
        testGatewayBtn.setOnClickListener(v -> testGateway());
        diagRow.addView(testGatewayBtn);

        Button testModelsBtn = new Button(this);
        testModelsBtn.setText("测试模型列表");
        testModelsBtn.setOnClickListener(v -> testModels());
        LinearLayout.LayoutParams tmParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        tmParams.leftMargin = 16;
        diagRow.addView(testModelsBtn, tmParams);

        Button viewLogsBtn = new Button(this);
        viewLogsBtn.setText("📋 查看日志");
        viewLogsBtn.setOnClickListener(v -> viewLogs());
        LinearLayout.LayoutParams vlParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        vlParams.leftMargin = 16;
        diagRow.addView(viewLogsBtn, vlParams);
        root.addView(diagRow);

        diagResultText = new TextView(this);
        diagResultText.setTextSize(12f);
        diagResultText.setTypeface(android.graphics.Typeface.MONOSPACE);
        diagResultText.setPadding(16, 8, 16, 16);
        diagResultText.setVisibility(View.GONE);
        root.addView(diagResultText);

        // ── 使用说明 ──
        root.addView(sectionLabel("使用说明"));
        TextView helpText = new TextView(this);
        helpText.setText(
                "1. 在 LSPosed/Xposed 管理器中激活本模块\n"
              + "2. 勾选智谱清言 (com.zhipuai.qingyan) 作为作用域\n"
              + "3. 重启智谱清言（或重启设备）\n"
              + "4. 打开智谱清言并登录，模块将自动捕获认证信息\n"
              + "5. 使用以下地址作为 OpenAI 兼容 API 端点：\n"
              + "   http://127.0.0.1:" + getSavedPort() + "/v1\n\n"
              + "API Key（可选）：\n"
              + "  设置后请求需携带 Authorization: Bearer <key>\n"
              + "  留空则不验证，修改后需「重启网关」生效\n\n"
              + "支持的 API 路由：\n"
              + "  POST /v1/chat/completions  — 对话补全（支持流式）\n"
              + "  GET  /v1/models            — 模型列表\n"
              + "  GET  /healthz              — 健康检查\n"
              + "  POST /shutdown             — 停止网关\n"
              + "  POST /restart              — 重启网关\n\n"
              + "兼容 OpenAI API 格式，可直接用于 ChatGPT 客户端、\n"
              + "LangChain、OpenAI SDK 等工具。");
        helpText.setTextSize(13f);
        helpText.setPadding(16, 8, 0, 16);
        helpText.setTag("help_text");
        root.addView(helpText);

        // ── 关于 ──
        root.addView(sectionLabel("关于"));
        TextView aboutText = new TextView(this);
        aboutText.setText("GLMKit v" + getModuleVersion() + "\n"
                + "GLM (智谱清言) 本地 API 反代增强模块\n"
                + "基于 Xposed 框架\n"
                + "模块包名：com.glmkit.proxy\n"
                + "目标应用：com.zhipuai.qingyan");
        aboutText.setTextSize(12f);
        aboutText.setTextColor(0xFF666666);
        aboutText.setPadding(16, 8, 0, 16);
        root.addView(aboutText);

        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.addView(root);
        setContentView(scroll);
    }

    // ════════════════════════════════════════════════════════════
    //  状态刷新
    // ════════════════════════════════════════════════════════════

    private void refreshStatus() {
        // 检查 Xposed 激活状态
        boolean xposedActive = isXposedActive();
        activationStatus.setText("Xposed 注入：" + (xposedActive
                ? "✅ 已激活" : "❌ 未激活（请在 LSPosed 中启用）"));

        // 检查目标应用
        boolean targetInstalled = isTargetInstalled();
        String targetVersion = getTargetVersion();
        targetStatus.setText("目标应用：" + (targetInstalled
                ? "✅ 智谱清言 v" + targetVersion
                : "❌ 未安装智谱清言"));

        // 检查网关状态（异步 HTTP 检测，因为网关运行在目标应用进程中）
        gatewayStatus.setText("本地网关：查询中...");
        checkGatewayStatus();

        // 错误（从保活服务状态获取）
        Bundle status = new Bundle();
        LocalApiKeepAliveService.putStatus(status);
        String error = status.getString("error", "");
        if (TextUtils.isEmpty(error)) {
            errorText.setVisibility(View.GONE);
        } else {
            errorText.setText(error);
            errorText.setVisibility(View.VISIBLE);
        }

        // 更新 API URL 显示
        View apiViewChild = findViewByTag(rootView(), "api_url");
        if (apiViewChild instanceof TextView) {
            ((TextView) apiViewChild).setText(
                    "http://127.0.0.1:" + getEffectiveGatewayPort() + "/v1/chat/completions");
        }

        // 更新使用说明中的端口号
        View helpViewChild = findViewByTag(rootView(), "help_text");
        if (helpViewChild instanceof TextView) {
            ((TextView) helpViewChild).setText(
                    "1. 在 LSPosed/Xposed 管理器中激活本模块\n"
                  + "2. 勾选智谱清言 (com.zhipuai.qingyan) 作为作用域\n"
                  + "3. 重启智谱清言（或重启设备）\n"
                  + "4. 打开智谱清言并登录，模块将自动捕获认证信息\n"
                  + "5. 使用以下地址作为 OpenAI 兼容 API 端点：\n"
                  + "   http://127.0.0.1:" + getEffectiveGatewayPort() + "/v1\n\n"
                  + "API Key（可选）：\n"
                  + "  设置后请求需携带 Authorization: Bearer <key>\n"
                  + "  留空则不验证，修改后需「重启网关」生效\n\n"
                  + "支持的 API 路由：\n"
                  + "  POST /v1/chat/completions  — 对话补全（支持流式）\n"
                  + "  GET  /v1/models            — 模型列表\n"
                  + "  GET  /healthz              — 健康检查\n"
                  + "  POST /shutdown             — 停止网关\n"
                  + "  POST /restart              — 重启网关\n\n"
                  + "兼容 OpenAI API 格式，可直接用于 ChatGPT 客户端、\n"
                  + "LangChain、OpenAI SDK 等工具。");
        }
    }

    // ════════════════════════════════════════════════════════════
    //  操作
    // ════════════════════════════════════════════════════════════

    private void savePort() {
        String text = portInput.getText().toString().trim();
        int port;
        try {
            port = Integer.parseInt(text);
        } catch (NumberFormatException e) {
            Toast.makeText(this, "请输入有效端口号", Toast.LENGTH_SHORT).show();
            return;
        }
        if (port < 1024 || port > 65535) {
            Toast.makeText(this, "端口范围：1024-65535", Toast.LENGTH_SHORT).show();
            return;
        }
        getPrefs().edit().putInt(KEY_PORT, port).apply();
        Toast.makeText(this, "端口已保存为 " + port + "\n重启智谱清言后生效",
                Toast.LENGTH_LONG).show();
        refreshStatus();
    }

    private void saveKeepAlive(boolean enabled) {
        getPrefs().edit().putBoolean(KEY_KEEPALIVE, enabled).apply();
    }

    private void saveApiKey(String key) {
        getPrefs().edit().putString(KEY_API_KEY, key).apply();
        Toast.makeText(this, TextUtils.isEmpty(key)
                ? "API Key 已清除" : "API Key 已保存\n点击「重启网关」生效",
                Toast.LENGTH_LONG).show();
    }

    private String getSavedApiKey() {
        return getPrefs().getString(KEY_API_KEY, "");
    }

    private void copyToClipboard(String label, String text) {
        try {
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText(label, text));
            Toast.makeText(this, "已复制：" + label + "\n" + text,
                    Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Toast.makeText(this, "复制失败", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * v1.0.43: 直接在 Activity 进程中启动网关（不依赖前台 Service）。
     * 同时尝试启动 Service 用于后台保活，但网关启动不依赖 Service。
     */
    private void startGateway() {
        Toast.makeText(this, "正在启动网关...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            StringBuilder report = new StringBuilder();
            try {
                int port = getSavedPort();
                String apiKey = getPrefs().getString(KEY_API_KEY, null);
                report.append("配置端口: ").append(port).append('\n');

                LocalApiGateway.setListenPort(port);
                LocalApiGateway.setApiKey(apiKey);

                if (!LocalApiGateway.isRunning()) {
                    report.append("网关未运行，正在启动...\n");
                    GlmCapture capture = new GlmCapture();
                    boolean loaded = capture.loadFromSharedFile();
                    report.append("auth加载: ").append(loaded ? "成功" : "无auth").append('\n');
                    GlmBackend backend = new GlmBackend(capture);
                    port = LocalApiGateway.start(this, backend);
                    report.append("start()返回端口: ").append(port).append('\n');
                    report.append("isRunning(): ").append(LocalApiGateway.isRunning()).append('\n');
                } else {
                    report.append("网关已在运行\n");
                }

                final int finalPort = port;
                try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
                String result = httpGet("http://127.0.0.1:" + finalPort + "/healthz", 2000);
                report.append("healthz: ").append(result != null ? "OK" : "FAIL").append('\n');

                runOnUiThread(() -> {
                    if (result != null) {
                        Toast.makeText(this, "✅ 网关已启动 (端口 " + finalPort + ")",
                                Toast.LENGTH_LONG).show();
                        keepAliveSwitch.setChecked(true);
                        saveKeepAlive(true);
                    } else {
                        // 显示详细诊断信息
                        String logs = LocalApiGateway.getLogBufferText();
                        String diag = "⚠️ 网关启动失败\n" + report + "\n日志:\n" + logs;
                        diagResultText.setText(diag);
                        diagResultText.setTextColor(0xFFF44336);
                        diagResultText.setVisibility(View.VISIBLE);
                        Toast.makeText(this, "⚠️ 启动失败，请查看下方详情",
                                Toast.LENGTH_LONG).show();
                    }
                    refreshStatus();
                });

                // 同时尝试启动前台 Service（用于后台保活，失败不影响网关）
                try { LocalApiKeepAliveService.setEnabled(this, true); } catch (Throwable ignored) {}

            } catch (Throwable t) {
                android.util.Log.e("GLMKit", "启动网关异常", t);
                final String msg = t.getClass().getSimpleName() + ": " + t.getMessage();
                runOnUiThread(() -> {
                    diagResultText.setText("⚠️ 启动异常\n" + report + "\n异常: " + msg);
                    diagResultText.setTextColor(0xFFF44336);
                    diagResultText.setVisibility(View.VISIBLE);
                    Toast.makeText(this, "⚠️ 启动异常: " + msg,
                            Toast.LENGTH_LONG).show();
                    refreshStatus();
                });
            }
        }, "glmkit-start").start();
    }

    /**
     * v1.0.43: 停止网关 — 直接停止 + 停止 Service
     */
    private void stopGateway() {
        new Thread(() -> {
            try { LocalApiGateway.stop(); } catch (Throwable ignored) {}
            try { LocalApiKeepAliveService.setEnabled(this, false); } catch (Throwable ignored) {}
            runOnUiThread(() -> {
                Toast.makeText(this, "✅ 网关已停止", Toast.LENGTH_SHORT).show();
                keepAliveSwitch.setChecked(false);
                saveKeepAlive(false);
                refreshStatus();
            });
        }, "glmkit-stop").start();
    }

    /**
     * v1.0.43: 重启网关 — 直接停止后重新启动
     */
    private void restartGateway() {
        Toast.makeText(this, "正在重启网关...", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try { LocalApiGateway.stop(); } catch (Throwable ignored) {}
            try { Thread.sleep(500); } catch (InterruptedException ignored) {}

            int port = getSavedPort();
            String apiKey = getPrefs().getString(KEY_API_KEY, null);
            LocalApiGateway.setListenPort(port);
            LocalApiGateway.setApiKey(apiKey);

            GlmCapture capture = new GlmCapture();
            capture.loadFromSharedFile();
            GlmBackend backend = new GlmBackend(capture);
            int actualPort = LocalApiGateway.start(this, backend);

            final int finalPort = actualPort;
            try { Thread.sleep(1000); } catch (InterruptedException ignored) {}
            String result = httpGet("http://127.0.0.1:" + finalPort + "/healthz", 2000);
            runOnUiThread(() -> {
                if (result != null) {
                    Toast.makeText(this, "✅ 网关已重启 (端口 " + finalPort + ")",
                            Toast.LENGTH_LONG).show();
                    keepAliveSwitch.setChecked(true);
                    saveKeepAlive(true);
                } else {
                    Toast.makeText(this, "⚠️ 重启失败", Toast.LENGTH_SHORT).show();
                }
                refreshStatus();
            });
        }, "glmkit-restart").start();
    }

    private void openTargetApp() {
        try {
            Intent launch = getPackageManager().getLaunchIntentForPackage(TARGET_PACKAGE);
            if (launch != null) {
                launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(launch);
            } else {
                Toast.makeText(this, "未找到智谱清言应用", Toast.LENGTH_SHORT).show();
            }
        } catch (Throwable t) {
            Toast.makeText(this, "打开失败：" + t.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * 获取网关实际监听端口 — 优先使用广播报告的 gateway_port，
     * 因为端口被占用时网关会回退到备用端口。
     */
    private int getEffectiveGatewayPort() {
        // 网关在同一进程中运行，直接获取实际监听端口
        if (LocalApiGateway.isRunning()) {
            return LocalApiGateway.getListenPort();
        }
        return getSavedPort();
    }

    /**
     * 异步检测网关状态 — 通过 HTTP 请求 /healthz 端点。
     * 网关运行在目标应用进程中，无法通过静态变量检测，必须发 HTTP 请求。
     */
    private void checkGatewayStatus() {
        final int port = getEffectiveGatewayPort();
        final int configuredPort = getSavedPort();
        new Thread(() -> {
            String result = httpGet("http://127.0.0.1:" + port + "/healthz", 2000);
            runOnUiThread(() -> {
                if (result != null) {
                    String msg = "本地网关：✅ 监听中 (端口 " + port + ")";
                    if (port != configuredPort) {
                        msg += "\n⚠️ 端口已回退（配置: " + configuredPort + "）";
                    }
                    gatewayStatus.setText(msg);
                    gatewayStatus.setTextColor(0xFF388E3C);
                } else {
                    gatewayStatus.setText("本地网关：⚠️ 未运行（请点击「启动网关」）");
                    gatewayStatus.setTextColor(0xFFFF6600);
                }
            });
        }, "glmkit-health-check").start();
    }

    /**
     * 测试本地网关 — 请求诊断端点 /v1/diagnostic
     */
    private void testGateway() {
        final int port = getEffectiveGatewayPort();
        diagResultText.setText("正在连接 http://127.0.0.1:" + port + "/v1/diagnostic ...");
        diagResultText.setTextColor(0xFF666666);
        diagResultText.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String result = httpGet("http://127.0.0.1:" + port + "/v1/diagnostic", 4000);
            runOnUiThread(() -> {
                if (result == null) {
                    diagResultText.setText("❌ 连接失败\n\n请点击「▶ 启动网关」按钮");
                    diagResultText.setTextColor(0xFFF44336);
                } else {
                    diagResultText.setText("✅ 网关响应：\n\n" + result);
                    diagResultText.setTextColor(0xFF388E3C);
                }
            });
        }, "glmkit-diag").start();
    }

    /**
     * 测试模型列表 — 请求 /v1/models
     */
    private void testModels() {
        final int port = getEffectiveGatewayPort();
        diagResultText.setText("正在请求 http://127.0.0.1:" + port + "/v1/models ...");
        diagResultText.setTextColor(0xFF666666);
        diagResultText.setVisibility(View.VISIBLE);
        new Thread(() -> {
            String result = httpGet("http://127.0.0.1:" + port + "/v1/models", 4000);
            runOnUiThread(() -> {
                if (result == null) {
                    diagResultText.setText("❌ 连接失败\n\n请点击「▶ 启动网关」按钮");
                    diagResultText.setTextColor(0xFFF44336);
                } else {
                    diagResultText.setText("✅ 模型列表：\n\n" + result);
                    diagResultText.setTextColor(0xFF388E3C);
                }
            });
        }, "glmkit-models").start();
    }

    /**
     * 查看模块日志 — 优先从网关 /v1/logs 获取，其次读取日志文件
     */
    private void viewLogs() {
        final int port = getEffectiveGatewayPort();
        diagResultText.setText("正在获取日志...");
        diagResultText.setTextColor(0xFF666666);
        diagResultText.setVisibility(View.VISIBLE);
        new Thread(() -> {
            // 方式1: 从网关 /v1/logs 端点获取
            String result = httpGet("http://127.0.0.1:" + port + "/v1/logs", 5000);
            String displayText;
            int textColor;

            if (result != null) {
                // 解析 JSON 提取日志
                try {
                    org.json.JSONObject json = new org.json.JSONObject(result);
                    String logs = json.optString("logs", "");
                    String logFile = json.optString("log_file", "");
                    if (logs.isEmpty()) {
                        displayText = "⚠️ 网关在线但日志为空\n\n日志文件: " + logFile;
                        textColor = 0xFFFF9800;
                    } else {
                        displayText = "📋 GLMKit 模块日志（来自网关）\n\n" + logs;
                        textColor = 0xFF333333;
                    }
                } catch (Exception e) {
                    displayText = "📋 网关日志响应:\n\n" + result;
                    textColor = 0xFF333333;
                }
            } else {
                // 方式2: 读取日志文件
                StringBuilder fileLogs = new StringBuilder();
                java.io.File logFile = new java.io.File("/sdcard/glmkit_debug.log");
                if (logFile.exists()) {
                    try {
                        java.io.BufferedReader reader = new java.io.BufferedReader(
                                new java.io.FileReader(logFile));
                        String line;
                        int count = 0;
                        while ((line = reader.readLine()) != null) {
                            fileLogs.append(line).append('\n');
                            count++;
                            if (count > 500) {
                                fileLogs.append("... (日志过长，仅显示最后 500 行)\n");
                                break;
                            }
                        }
                        reader.close();
                        if (fileLogs.length() > 0) {
                            displayText = "📋 GLMKit 日志文件 (" + logFile.getAbsolutePath() + "):\n\n" + fileLogs.toString();
                            textColor = 0xFF333333;
                        } else {
                            displayText = "⚠️ 日志文件为空\n\n路径: " + logFile.getAbsolutePath();
                            textColor = 0xFFFF9800;
                        }
                    } catch (Exception e) {
                        displayText = "❌ 读取日志文件失败: " + e.getMessage() + "\n\n路径: " + logFile.getAbsolutePath();
                        textColor = 0xFFF44336;
                    }
                } else {
                    displayText = "❌ 无法获取日志\n\n" +
                            "网关未运行或无法连接 (http://127.0.0.1:" + port + "/v1/logs)\n" +
                            "日志文件不存在: " + logFile.getAbsolutePath() + "\n\n" +
                            "请尝试:\n" +
                            "1. 点击「▶ 启动网关」按钮\n" +
                            "2. 在 LSPosed 中激活模块并勾选智谱清言\n" +
                            "3. 打开智谱清言（用于捕获 auth）";
                    textColor = 0xFFF44336;
                }
            }

            final String text = displayText;
            final int color = textColor;
            runOnUiThread(() -> {
                diagResultText.setText(text);
                diagResultText.setTextColor(color);
            });
        }, "glmkit-viewlogs").start();
    }

    /**
     * 简单 HTTP GET 请求，返回响应体字符串或 null（失败时）
     */
    private static String httpGet(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("GET");
            int code = conn.getResponseCode();
            if (code != 200) return "HTTP " + code;
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }

    /**
     * 简单 HTTP POST 请求（空 body），返回响应体字符串或 null（失败时）
     */
    private static String httpPost(String urlStr, int timeoutMs) {
        HttpURLConnection conn = null;
        try {
            URL url = new URL(urlStr);
            conn = (HttpURLConnection) url.openConnection();
            conn.setConnectTimeout(timeoutMs);
            conn.setReadTimeout(timeoutMs);
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.getOutputStream().close();
            int code = conn.getResponseCode();
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(conn.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(line);
            }
            reader.close();
            return sb.toString();
        } catch (Throwable ignored) {
            return null;
        } finally {
            if (conn != null) try { conn.disconnect(); } catch (Throwable ignored) {}
        }
    }
    // ════════════════════════════════════════════════════════════
    //  状态检测
    // ════════════════════════════════════════════════════════════

    /**
     * 检测 Xposed 是否已激活注入。
     *
     * 检测策略：
     * 1. 检查 xposed_hooked 标记 — 由 XposedActivationReceiver 在目标进程中
     *    收到 HOOK_STARTED 广播后设置。这是最可靠的检测方式。
     * 2. 检查 auth_captured 标记 — 认证信息已捕获，说明 hook 已生效。
     * 3. 检查 gateway_running 标记 — 网关已启动。
     *
     * 注意：XposedActivationProvider.isActivated() 在模块自身进程中始终为 true
     * （ContentProvider.onCreate 总会被调用），因此不能用于检测 Xposed 是否
     * 真正注入了目标应用。
     */
    private boolean isXposedActive() {
        SharedPreferences prefs = getPrefs();
        // 方法1：检查 hook 启动标记（由广播设置）
        if (prefs.getBoolean("xposed_hooked", false)) return true;
        // 方法2：检查认证捕获标记
        if (prefs.getBoolean("auth_captured", false)) return true;
        // 方法3：检查网关启动标记
        if (prefs.getBoolean("gateway_running", false)) return true;
        return false;
    }

    private boolean isTargetInstalled() {
        try {
            getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    private String getTargetVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(TARGET_PACKAGE, 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    private String getModuleVersion() {
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            return info.versionName;
        } catch (PackageManager.NameNotFoundException e) {
            return "?";
        }
    }

    // ════════════════════════════════════════════════════════════
    //  SharedPreferences
    // ════════════════════════════════════════════════════════════

    private SharedPreferences getPrefs() {
        return getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private int getSavedPort() {
        return getPrefs().getInt(KEY_PORT, DEFAULT_PORT);
    }

    private boolean getSavedKeepAlive() {
        return getPrefs().getBoolean(KEY_KEEPALIVE, false);
    }

    // ════════════════════════════════════════════════════════════
    //  UI 工具
    // ════════════════════════════════════════════════════════════

    private TextView sectionLabel(String text) {
        TextView label = new TextView(this);
        label.setText(text);
        label.setTextSize(16f);
        label.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        label.setTextColor(0xFF333333);
        label.setPadding(0, 20, 0, 6);
        // 添加顶部分隔线
        label.setBackgroundColor(0x11000000);
        return label;
    }

    private View rootView() {
        return getWindow().getDecorView().findViewById(android.R.id.content);
    }

    private static View findViewByTag(View root, Object tag) {
        if (root == null) return null;
        if (tag != null && tag.equals(root.getTag())) return root;
        if (root instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) root;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findViewByTag(group.getChildAt(i), tag);
                if (found != null) return found;
            }
        }
        return null;
    }
}
