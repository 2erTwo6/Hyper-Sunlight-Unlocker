package sbo;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class Hook implements IXposedHookLoadPackage {
    private static final float OLD = 0.593761f;   // 原厂 800 nit
    private static final float NEW = 0.634171f;   // 1000 nit (DBV 10390)

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpp) {
        if (!"android".equals(lpp.packageName)) return;
        try {
            Class<?> dpc = XposedHelpers.findClass(
                "com.android.server.display.DisplayPowerControllerImpl", lpp.classLoader);
            XposedBridge.hookAllMethods(dpc, "init", new XC_MethodHook() {
                @Override
                protected void afterHookedMethod(MethodHookParam param) {
                    try {
                        Object self = param.thisObject;
                        float cur = XposedHelpers.getFloatField(self, "mMaxManualBoostBrightness");
                        if (Math.abs(cur - OLD) < 1e-6f) {
                            XposedHelpers.setFloatField(self, "mMaxManualBoostBrightness", NEW);
                            Object disp = XposedHelpers.getObjectField(self, "mDisplayId");
                            XposedBridge.log("SBOLSP: display " + disp + " " + cur + " -> " + NEW);
                        } else if (Math.abs(cur - NEW) > 1e-6f) {
                            XposedBridge.log("SBOLSP: field=" + cur + " unexpected, no-op");
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("SBOLSP: " + t);
                    }
                }
            });
            XposedBridge.log("SBOLSP: hooked DisplayPowerControllerImpl.init");
        } catch (Throwable t) {
            XposedBridge.log("SBOLSP: hook failed " + t);
        }
    }
}
