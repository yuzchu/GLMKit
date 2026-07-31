package com.glmkit.probe;

import android.app.Application;
import java.io.File;

/**
 * 动态解析宿主 App 的真实数据目录。
 *
 * 分身/多开环境下，App 的 dataDir 不是 /data/data/com.zhipuai.qingyan/，
 * 而是 /data/user/999/com.zhipuai.qingyan/ 之类的路径。
 * 原代码把所有文件路径硬编码为 /data/data/com.zhipuai.qingyan/files/...，
 * 导致分身环境下写入失败，报"私有目录暂时不可写"。
 *
 * 本类在 onPackageLoaded 时调用 init() 尝试获取真实路径，
 * 并在 Application.onCreate 后再次调用 init() 重新初始化。
 * getFilesDir() 等方法也会懒重试，确保最终拿到正确路径。
 */
final class DataPaths {

    private static volatile String dataDir  = "/data/data/com.zhipuai.qingyan";
    private static volatile String filesDir = dataDir + "/files";
    private static volatile String cacheDir = dataDir + "/cache";
    private static volatile String dbDir    = dataDir + "/databases";
    /** true 表示已从 Application 获取到真实路径；false 表示仍在用 fallback */
    private static volatile boolean realInit = false;

    private DataPaths() {}

    /**
     * 尝试从当前进程的 Application 获取真实数据目录。
     * 如果 Application 还不可用（onPackageLoaded 时机太早），
     * 则保持现有路径不变，等后续再次调用。
     *
     * @return true 如果本次成功从 Application 获取了真实路径
     */
    static boolean init(String pkgName) {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Application app = (Application) atClass.getMethod("currentApplication").invoke(null);
            if (app == null) {
                // ActivityThread.currentActivityThread().getApplication() 作为备选
                Object at = atClass.getMethod("currentActivityThread").invoke(null);
                if (at != null) {
                    try {
                        app = (Application) atClass.getMethod("getApplication").invoke(at);
                    } catch (Throwable ignored) {}
                }
            }
            if (app != null) {
                File f = app.getFilesDir();
                File c = app.getCacheDir();
                if (f != null && c != null) {
                    filesDir = f.getAbsolutePath();
                    cacheDir = c.getAbsolutePath();
                    File parent = f.getParentFile();
                    if (parent != null) {
                        dataDir = parent.getAbsolutePath();
                    }
                    dbDir = dataDir + "/databases";
                    realInit = true;
                    return true;
                }
            }
        } catch (Throwable t) {
            // ignore
        }
        // Application 不可用，如果之前也没初始化过，用 fallback
        if (!realInit) {
            String fallback = "/data/data/" + pkgName;
            dataDir  = fallback;
            filesDir = fallback + "/files";
            cacheDir = fallback + "/cache";
            dbDir    = fallback + "/databases";
        }
        return false;
    }

    /** 懒重试：如果还没拿到真实路径，尝试再初始化一次 */
    private static void ensureRealInit() {
        if (!realInit) {
            init("com.zhipuai.qingyan");
        }
    }

    /** files 目录下的文件完整路径 */
    static String files(String name) {
        ensureRealInit();
        return filesDir + "/" + name;
    }

    /** cache 目录下的文件完整路径 */
    static String cache(String name) {
        ensureRealInit();
        return cacheDir + "/" + name;
    }

    /** databases 目录下的文件完整路径 */
    static String db(String name) {
        ensureRealInit();
        return dbDir + "/" + name;
    }

    static String getDataDir()   { ensureRealInit(); return dataDir; }
    static String getFilesDir()  { ensureRealInit(); return filesDir; }
    static String getCacheDir()  { ensureRealInit(); return cacheDir; }
    static String getDbDir()     { ensureRealInit(); return dbDir; }
    static boolean isRealInit()  { return realInit; }
}
