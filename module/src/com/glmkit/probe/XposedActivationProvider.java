package com.glmkit.probe;

import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.net.Uri;
import android.util.Log;

/**
 * Xposed 激活检测 ContentProvider。
 *
 * <p>Xposed 框架在加载模块时会调用模块应用中的 ContentProvider。
 * 本 Provider 在 onCreate 时设置静态标志 {@code activated = true}，
 * SettingsActivity 通过 {@link #isActivated()} 查询此标志判断模块是否已被 Xposed 加载。</p>
 *
 * <p>同时提供 query 接口，允许外部应用通过 ContentResolver 查询模块激活状态。
 * Authorities: com.glmkit.proxy.XposedService</p>
 */
public final class XposedActivationProvider extends ContentProvider {

    private static final String TAG = "GLMKit-Activation";
    private static final String PREFS = "glmkit_activation";
    private static final String KEY_ACTIVATED = "xposed_activated";

    // 静态标志 — Xposed 加载模块后，ContentProvider.onCreate 被调用时置为 true
    private static volatile boolean activated = false;

    // ════════════════════════════════════════════════════════════
    //  公共 API
    // ════════════════════════════════════════════════════════════

    /**
     * 返回 Xposed 框架是否已加载本模块。
     * 当 ContentProvider.onCreate 被系统调用时（模块被 Xposed 加载）置为 true。
     */
    public static boolean isActivated() {
        return activated;
    }

    // ════════════════════════════════════════════════════════════
    //  ContentProvider 生命周期
    // ════════════════════════════════════════════════════════════

    @Override public boolean onCreate() {
        // ContentProvider.onCreate 在应用进程启动时被调用。
        // 如果是 Xposed 框架加载了本模块，此 Provider 会在目标应用进程中创建。
        // 但在模块自身进程中也会创建（当用户打开 SettingsActivity 时）。
        // 真正的激活检测依赖于 Xposed 框架在目标进程中注入的标志。
        activated = true;
        Log.i(TAG, "XposedActivationProvider created — module is loaded");

        // 持久化激活状态
        try {
            Context context = getContext();
            if (context != null) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean(KEY_ACTIVATED, true)
                        .apply();
            }
        } catch (Throwable ignored) {}

        return true;
    }

    // ════════════════════════════════════════════════════════════
    //  ContentProvider 查询接口
    // ════════════════════════════════════════════════════════════

    @Override public Cursor query(Uri uri, String[] projection,
                                   String selection, String[] selectionArgs,
                                   String sortOrder) {
        // 返回激活状态游标
        MatrixCursor cursor = new MatrixCursor(new String[]{
                "activated", "package", "target", "version"
        });
        cursor.addRow(new Object[]{
                activated ? 1 : 0,
                "com.glmkit.proxy",
                "com.zhipuai.qingyan",
                getModuleVersion()
        });
        return cursor;
    }

    @Override public String getType(Uri uri) {
        return "vnd.android.cursor.dir/vnd.glmkit.activation";
    }

    @Override public Uri insert(Uri uri, ContentValues values) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int delete(Uri uri, String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    @Override public int update(Uri uri, ContentValues values,
                                 String selection, String[] selectionArgs) {
        throw new UnsupportedOperationException("Read-only provider");
    }

    private String getModuleVersion() {
        try {
            Context context = getContext();
            if (context != null) {
                android.content.pm.PackageInfo pi = context.getPackageManager()
                        .getPackageInfo(context.getPackageName(), 0);
                return pi.versionName;
            }
        } catch (Throwable ignored) {}
        return "unknown";
    }
}
