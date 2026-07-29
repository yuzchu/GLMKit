package com.glmkit.probe;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
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
    private static final int DEFAULT_PORT = 8765;
    private static final String TARGET_PACKAGE = "com.zhipuai.qingyan";

    private TextView activationStatus;
    private TextView targetStatus;
    private TextView gatewayStatus;
    private EditText portInput;
    private Switch keepAliveSwitch;
    private TextView errorText;

    // ════════════════════════════════════════════════════════════
    //  生命周期
    // ════════════════════════════════════════════════════════════

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("GLMKit 设置");
        buildUi();
        refreshStatus();
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
        title.setPadding(0, 0, 0, 8);
        root.addView(title);

        TextView subtitle = new TextView(this);
        subtitle.setText("Xposed 增强模块 for 智谱清言");
        subtitle.setTextSize(14f);
        subtitle.setTextColor(0xFF888888);
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
        apiLabel.setTextColor(0xFF888888);
        apiLabel.setPadding(16, 8, 0, 0);
        root.addView(apiLabel);

        TextView apiUrl = new TextView(this);
        apiUrl.setText("http://127.0.0.1:" + getSavedPort() + "/v1/chat/completions");
        apiUrl.setTextSize(13f);
        apiUrl.setTextColor(0xFF0066CC);
        apiUrl.setPadding(16, 0, 0, 16);
        apiUrl.setId(View.generateViewId());
        apiUrl.setTag("api_url");
        root.addView(apiUrl);

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
        keepAliveDesc.setTextColor(0xFF888888);
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
              + "支持的 API 路由：\n"
              + "  POST /v1/chat/completions  — 对话补全（支持流式）\n"
              + "  GET  /v1/models            — 模型列表\n"
              + "  GET  /healthz              — 健康检查\n\n"
              + "兼容 OpenAI API 格式，可直接用于 ChatGPT 客户端、\n"
              + "LangChain、OpenAI SDK 等工具。");
        helpText.setTextSize(13f);
        helpText.setPadding(16, 8, 0, 16);
        root.addView(helpText);

        // ── 关于 ──
        root.addView(sectionLabel("关于"));
        TextView aboutText = new TextView(this);
        aboutText.setText("GLMKit v1.0.8\n"
                + "GLM (智谱清言) 本地 API 反代增强模块\n"
                + "基于 Xposed 框架\n"
                + "模块包名：com.glmkit.proxy\n"
                + "目标应用：com.zhipuai.qingyan");
        aboutText.setTextSize(12f);
        aboutText.setTextColor(0xFF888888);
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

        // 检查网关状态
        Bundle status = new Bundle();
        LocalApiKeepAliveService.putStatus(status);
        boolean gatewayRunning = status.getBoolean("gateway_running", false);
        boolean keepAliveRunning = status.getBoolean("running", false);
        gatewayStatus.setText("本地网关：" + (gatewayRunning
                ? "✅ 监听中 (端口 " + getSavedPort() + ")"
                : "⚠️ 未运行") + (keepAliveRunning ? "  |  保活：✅" : ""));

        // 错误
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
                    "http://127.0.0.1:" + getSavedPort() + "/v1/chat/completions");
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

    // ════════════════════════════════════════════════════════════
    //  状态检测
    // ════════════════════════════════════════════════════════════

    /**
     * 检测 Xposed 是否已激活注入。
     * 通过尝试加载 Xposed API 类或检查已知标记来判断。
     */
    private boolean isXposedActive() {
        // 方法1：检查 XposedActivationProvider 是否被 Xposed 调用过
        if (XposedActivationProvider.isActivated()) return true;

        // 方法2：检查 de.robv.android.xposed.XposedBridge 是否可加载
        try {
            Class.forName("de.robv.android.xposed.XposedBridge");
            return true;
        } catch (ClassNotFoundException ignored) {}

        // 方法3：检查模块自身的 hook 标记
        return getPrefs().getBoolean("xposed_hooked", false);
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
        label.setPadding(0, 16, 0, 4);
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
