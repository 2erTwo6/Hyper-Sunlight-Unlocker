package sbo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * HyperOS 3 专用：阳光模式手动亮度上限解锁（GUI 可调倍率版）。
 *
 * 门卫策略（按用户要求）：
 *  - 非 OS3（ro.mi.os.version.name 不以 "OS3" 开头）→ 完全不 Hook，只打一条日志；
 *  - 出厂值 ≤ 0（如 OS4 的 -1.0 哨兵）或 > 1.0 → 判定功能未启用，不修改；
 *  - 全部回调 try/catch，异常只进 LSPosed 日志。
 *
 * 出厂值运行时从 android.miui:dimen/config_max_manual_brt_boost 读取（兜底退回字段值），
 * 任何机型无需硬编码；目标值 = min(出厂 × 倍率, 1.0)，倍率由模块 GUI 写入 SharedPreferences。
 */
public class Hook implements IXposedHookLoadPackage {
    private static final String TAG = "SBOLSP";
    private static final String MODULE_PKG = "com.sunlightboost.lsp";
    private static final String PREFS = "sbo_prefs";
    private static final String KEY = "multiplier_pct";   // int，单位 0.1%，1068 = 106.8%
    private static final int DEFAULT_PCT = 1068;          // dash 出厂 0.593761 → 0.634171 ≈ 800→1000 nit
    private static final String OS_PREFIX = "OS3";
    private static final String RES_NAME = "config_max_manual_brt_boost";
    private static final String PROP_PCT = "persist.sunlightboost.pct";
    private static final String PROP_TARGET = "persist.sunlightboost.target"; // 绝对目标 float × 1e6
    private static final String KEY_TARGET_PM = "target_pm";
    private static final Pattern PCT_RE = Pattern.compile(KEY + "\" value=\"(\\d+)\"");
    private static final Pattern TARGET_RE = Pattern.compile(KEY_TARGET_PM + "\" value=\"(\\d+)\"");

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName)) return;
        try {
            String os = prop(lpp.classLoader, "ro.mi.os.version.name");
            if (!os.startsWith(OS_PREFIX)) {
                XposedBridge.log(TAG + ": os=" + os + " 非 " + OS_PREFIX + "，按要求不 Hook");
                return;
            }
            Class<?> dpc = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerControllerImpl", lpp.classLoader);
            XposedBridge.hookAllMethods(dpc, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        float stock = readStock(self);
                        if (stock <= 0f || stock > 1.0f) {
                            XposedBridge.log(TAG + ": stock=" + stock + " 无效（功能未启用/哨兵值），不修改");
                            return;
                        }
                        float target = resolveTarget(stock);
                        if (target <= 0f || target > 1.0f) {
                            XposedBridge.log(TAG + ": target=" + target + " 无效，不修改");
                            return;
                        }
                        if (Math.abs(target - stock) < 1e-7f) {
                            XposedBridge.log(TAG + ": 目标等于出厂值，无需修改");
                            return;
                        }
                        float applied = Math.min(target, 1.0f);
                        Object disp = XposedHelpers.getObjectField(self, "mDisplayId");
                        XposedHelpers.setFloatField(self, "mMaxManualBoostBrightness", applied);
                        XposedBridge.log(TAG + ": display " + disp + " stock=" + stock
                            + " -> " + applied
                            + (applied >= 0.999999f ? " (clamped 1.0)" : ""));
                    } catch (Throwable t) {
                        XposedBridge.log(TAG + ": " + t);
                    }
                }
            });
            XposedBridge.log(TAG + ": " + os + ", hooked DisplayPowerControllerImpl.init");
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": hook failed " + t);
        }
    }

    /** 读系统属性（反射，避免桩类依赖 android.jar）。 */
    private static String prop(ClassLoader cl, String name) {
        try {
            Class<?> sp = XposedHelpers.findClass("android.os.SystemProperties", cl);
            return (String) XposedHelpers.callStaticMethod(sp, "get", name, "");
        } catch (Throwable t) {
            return "";
        }
    }

    /**
     * 出厂值：优先读资源（init 重入时字段可能已被本模块改过，资源永远是出厂真值），
     * 资源查不到再退回字段值（此时若 init 重复执行理论上可能叠加，但已 clamp ≤1.0 兜底）。
     */
    private static float readStock(Object self) {
        try {
            Object ctx = XposedHelpers.getObjectField(self, "mContext");
            Object res = XposedHelpers.callMethod(ctx, "getResources");
            for (String pkg : new String[]{"android.miui", "android"}) {
                try {
                    Object idObj = XposedHelpers.callMethod(res, "getIdentifier", RES_NAME, "dimen", pkg);
                    int id = ((Number) idObj).intValue();
                    if (id != 0) {
                        float v = ((Number) XposedHelpers.callMethod(res, "getFloat", id)).floatValue();
                        XposedBridge.log(TAG + ": stock from " + pkg + " resource = " + v);
                        return v;
                    }
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": resource lookup failed: " + t);
        }
        try {
            float f = XposedHelpers.getFloatField(self, "mMaxManualBoostBrightness");
            XposedBridge.log(TAG + ": fallback to field = " + f);
            return f;
        } catch (Throwable t) {
            XposedBridge.log(TAG + ": field fallback failed: " + t);
            return -1f;   // 触发 stock<=0 分支 → 安全 no-op
        }
    }

    /** 目标解析：0) prop/prefs 绝对目标（GUI nit 模式写入） 1) 倍率链（旧 GUI 兼容） 2) 默认倍率 */
    private static float resolveTarget(float stock) {
        Integer v = propInt(PROP_TARGET);
        if (v != null && v > 0 && v <= 1000000) {
            XposedBridge.log(TAG + ": target from prop " + PROP_TARGET + " = " + v);
            return v / 1000000f;
        }
        v = prefsInt(KEY_TARGET_PM, TARGET_RE);
        if (v != null && v > 0 && v <= 1000000) {
            XposedBridge.log(TAG + ": target from prefs " + KEY_TARGET_PM + " = " + v);
            return v / 1000000f;
        }
        Integer pct = propInt(PROP_PCT);
        if (pct == null || pct < 1000 || pct > 2000) {
            pct = prefsInt(KEY, PCT_RE);
        }
        if (pct != null && pct >= 1000 && pct <= 2000) {
            XposedBridge.log(TAG + ": pct = " + pct + " (multiplier mode)");
            return stock * (pct / 1000.0f);
        }
        XposedBridge.log(TAG + ": no config found, default pct " + DEFAULT_PCT);
        return stock * (DEFAULT_PCT / 1000.0f);
    }

    /** 读系统属性为 int（反射，避免桩类依赖 android.jar）。 */
    private static Integer propInt(String name) {
        try {
            Class<?> sp = Class.forName("android.os.SystemProperties");
            String s = ((String) sp.getMethod("get", String.class, String.class)
                    .invoke(null, name, "")).trim();
            if (!s.isEmpty()) return Integer.parseInt(s);
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** 读模块 prefs 里的 int：先 XSharedPreferences（LSPosed），再直接读文件。 */
    private static Integer prefsInt(String key, Pattern valueRe) {
        try {
            XSharedPreferences sp = new XSharedPreferences(MODULE_PKG, PREFS);
            sp.reload();
            int v = sp.getInt(key, -1);
            if (v >= 0) return v;
        } catch (Throwable ignored) {
        }
        try {
            for (String base : new String[]{"/data/data/", "/data/user/0/", "/data/user_de/0/"}) {
                File f = new File(base + MODULE_PKG + "/shared_prefs/" + PREFS + ".xml");
                if (!f.canRead()) continue;
                StringBuilder sb = new StringBuilder();
                try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(f)))) {
                    String line;
                    while ((line = r.readLine()) != null) sb.append(line).append('\n');
                }
                Matcher m = valueRe.matcher(sb.toString());
                if (m.find()) return Integer.parseInt(m.group(1));
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}