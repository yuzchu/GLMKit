package com.glmkit.probe;

import io.github.libxposed.api.XposedInterface;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Method;
import java.lang.reflect.Field;

/**
 * XposedCompat — 传统 Xposed API 到现代 libxposed API 的兼容层。
 *
 * 提供 XposedHelpers.findAndHookMethod 和 XC_MethodHook 的等价实现，
 * 使旧的 GLM hook 代码无需重写即可在 libxposed API 102 下工作。
 */
public final class XposedCompat {

    private static XposedInterface xposedInterface;

    public static void init(XposedInterface xi) {
        xposedInterface = xi;
    }

    public static XposedInterface xi() {
        return xposedInterface;
    }

    // ════════════════════════════════════════════════════════════
    //  XC_MethodHook 等价
    // ════════════════════════════════════════════════════════════

    public static abstract class XC_MethodHook {
        protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {}
    }

    public static class MethodHookParam {
        public Object thisObject;
        public Object[] args;
        public Object result;
        public Throwable throwable;
        private boolean resultSet = false;

        public Object getResult() { return result; }
        public void setResult(Object value) { result = value; resultSet = true; }
        public boolean hasResult() { return resultSet; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    // ════════════════════════════════════════════════════════════
    //  XposedHelpers 等价
    // ════════════════════════════════════════════════════════════

    /** findAndHookMethod(Class, methodName, paramTypes..., callback) */
    public static Object findAndHookMethod(Class<?> clazz, String methodName,
            Object... argsAndCallback) {
        // 最后一个参数是 callback
        XC_MethodHook callback = (XC_MethodHook) argsAndCallback[argsAndCallback.length - 1];
        Class<?>[] paramTypes = new Class<?>[argsAndCallback.length - 1];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = (Class<?>) argsAndCallback[i];
        }
        try {
            Method method = clazz.getDeclaredMethod(methodName, paramTypes);
            return hookMethod(method, callback);
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Method not found: " + clazz.getName() + "." + methodName, e);
        }
    }

    /** findAndHookMethod(className, classLoader, methodName, paramTypes..., callback) */
    public static Object findAndHookMethod(String className, ClassLoader classLoader,
            String methodName, Object... argsAndCallback) {
        try {
            Class<?> clazz = Class.forName(className, false, classLoader);
            return findAndHookMethod(clazz, methodName, argsAndCallback);
        } catch (ClassNotFoundException e) {
            throw new RuntimeException("Class not found: " + className, e);
        }
    }

    /** Hook a Method with the given callback */
    private static Object hookMethod(Method method, XC_MethodHook callback) {
        method.setAccessible(true);
        return xposedInterface.hook(method).intercept(new XposedInterface.Hooker() {
            @Override
            public Object intercept(XposedInterface.Chain chain) throws Throwable {
                MethodHookParam param = new MethodHookParam();
                param.thisObject = chain.getThisObject();
                param.args = chain.getArgs().toArray();

                callback.beforeHookedMethod(param);

                if (param.hasResult()) {
                    // beforeHookedMethod called setResult, skip original
                    callback.afterHookedMethod(param);
                    return param.getResult();
                }

                try {
                    param.result = chain.proceed();
                } catch (Throwable t) {
                    param.throwable = t;
                    callback.afterHookedMethod(param);
                    if (param.hasResult()) return param.getResult();
                    throw t;
                }

                callback.afterHookedMethod(param);
                return param.getResult();
            }
        });
    }

    /** Hook a Constructor */
    public static Object findAndHookConstructor(Class<?> clazz,
            Object... argsAndCallback) {
        XC_MethodHook callback = (XC_MethodHook) argsAndCallback[argsAndCallback.length - 1];
        Class<?>[] paramTypes = new Class<?>[argsAndCallback.length - 1];
        for (int i = 0; i < paramTypes.length; i++) {
            paramTypes[i] = (Class<?>) argsAndCallback[i];
        }
        try {
            Constructor<?> ctor = clazz.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return xposedInterface.hook(ctor).intercept(new XposedInterface.Hooker() {
                @Override
                public Object intercept(XposedInterface.Chain chain) throws Throwable {
                    MethodHookParam param = new MethodHookParam();
                    param.thisObject = chain.getThisObject();
                    param.args = chain.getArgs().toArray();

                    callback.beforeHookedMethod(param);

                    if (param.hasResult()) {
                        callback.afterHookedMethod(param);
                        return param.getResult();
                    }

                    try {
                        param.result = chain.proceed();
                    } catch (Throwable t) {
                        param.throwable = t;
                        callback.afterHookedMethod(param);
                        if (param.hasResult()) return param.getResult();
                        throw t;
                    }

                    callback.afterHookedMethod(param);
                    return param.getResult();
                }
            });
        } catch (NoSuchMethodException e) {
            throw new RuntimeException("Constructor not found in " + clazz.getName(), e);
        }
    }

    // ════════════════════════════════════════════════════════════
    //  XposedHelpers 辅助方法
    // ════════════════════════════════════════════════════════════

    public static Object callMethod(Object obj, String methodName, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] != null ? args[i].getClass() : null;
            }
            Method m = obj.getClass().getMethod(methodName, types);
            return m.invoke(obj, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object callStaticMethod(Class<?> clazz, String methodName, Object... args) {
        try {
            Class<?>[] types = new Class<?>[args.length];
            for (int i = 0; i < args.length; i++) {
                types[i] = args[i] != null ? args[i].getClass() : null;
            }
            Method m = clazz.getMethod(methodName, types);
            return m.invoke(null, args);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getObjectField(Object obj, String fieldName) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setObjectField(Object obj, String fieldName, Object value) {
        try {
            Field f = obj.getClass().getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(obj, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Object getStaticObjectField(Class<?> clazz, String fieldName) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            return f.get(null);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static void setStaticObjectField(Class<?> clazz, String fieldName, Object value) {
        try {
            Field f = clazz.getDeclaredField(fieldName);
            f.setAccessible(true);
            f.set(null, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static Field findFieldIfExists(Class<?> clazz, String fieldName) {
        try {
            return clazz.getDeclaredField(fieldName);
        } catch (NoSuchFieldException e) {
            return null;
        }
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(className, false, classLoader);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    public static Method findMethodExactIfExists(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        try {
            return clazz.getDeclaredMethod(methodName, paramTypes);
        } catch (NoSuchMethodException e) {
            return null;
        }
    }
}
