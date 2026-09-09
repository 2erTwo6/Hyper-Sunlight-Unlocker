package sbo;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Hyper-Sunlight-Unlocker 模块设置界面（无资源文件，全部代码构建）：
 *  - 滑块（nit 模式）：直接以 nit 选择亮度上限，刻度来自本机厂商标定表（dumpsys mBacklight/mNits，
 *    分段线性插值，非估算）；解析不到标定表的机型自动回落为倍率模式
 *  - 读数面板：实时 DBV（brightness_clone）+ 逻辑上限 + 皮肤温度 + 当前生效 float/nit
 *  - 软重启按钮（重启 system_server，让新上限生效）
 */
public class MainActivity extends Activity {
    private static final String PREFS = "sbo_prefs";
    private static final String KEY_PCT = "multiplier_pct";      // 旧倍率模式（无标定表兜底）
    private static final String KEY_TARGET_PM = "target_pm";     // 绝对目标 float × 1e6
    private static final String KEY_NIT = "target_nit";          // 绝对目标 nit（UI 记忆）
    private static final int DEFAULT_PCT = 1068;
    private static final String PROP_TARGET = "persist.sunlightboost.target";
    private static final String PROP_PCT = "persist.sunlightboost.pct";
    private static final Pattern BOOST_RE = Pattern.compile("mMaxManualBoostBrightness=([0-9.eE+-]+)");
    private static final Pattern BL_RE = Pattern.compile("mBacklight=\\[([^\\]]*)\\]");
    private static final Pattern NITS_RE = Pattern.compile("mNits=\\[([^\\]]*)\\]");
    private static final Pattern STOCK_RE =
            Pattern.compile("SBOLSP: (?:stock[^=]*|fallback to field) = ([0-9.eE+-]+)");

    private LinearLayout sliderBox;
    private TextView sliderLabel, info;
    private String sliderMode = null;        // "nit" / "pct"
    private float[] tblBL, tblNit;           // 厂商标定表
    private float appliedFloat = -1f;
    private float stockFloat = -1f;   // 出厂阳光上限（运行时读取）
    private String stockSrc = "未读到";
    private String sliderSig = null;      // 滑块签名，变化才重建

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        int pad = dp(16);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        TextView title = new TextView(this);
        title.setText("Hyper-Sunlight-Unlocker · HyperOS3 阳光上限");
        title.setTextSize(20);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        info = new TextView(this);
        info.setTypeface(Typeface.MONOSPACE);
        info.setTextSize(13);
        info.setPadding(0, dp(12), 0, dp(12));
        info.setText("读取中…（需要 root 授权）");
        root.addView(info);

        sliderLabel = new TextView(this);
        sliderLabel.setTypeface(Typeface.DEFAULT_BOLD);
        sliderLabel.setPadding(0, dp(8), 0, dp(4));
        root.addView(sliderLabel);

        sliderBox = new LinearLayout(this);
        sliderBox.setOrientation(LinearLayout.VERTICAL);
        root.addView(sliderBox);

        Button refresh = new Button(this);
        refresh.setText("刷新读数");
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                refresh();
            }
        });
        root.addView(refresh);

        Button restart = new Button(this);
        restart.setText("软重启（重启 system_server）");
        restart.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                new AlertDialog.Builder(MainActivity.this)
                        .setTitle("软重启")
                        .setMessage("将重启 system_server：屏幕短暂黑屏、所有应用重新加载，"
                                + "等效于重启但更快。新上限在此时生效。\n\n继续？")
                        .setPositiveButton("重启", (d, w) -> su("killall system_server"))
                        .setNegativeButton("取消", null)
                        .show();
            }
        });
        root.addView(restart);

        TextView note = new TextView(this);
        note.setTextSize(12);
        note.setTextColor(0xFF888888);
        note.setPadding(0, dp(16), 0, 0);
        note.setText("出厂值运行时读取自 android.miui:config_max_manual_brt_boost；"
                + "实时 DBV 节点：/sys/class/mi_display/disp-DSI-0/brightness_clone（滑条同步镜像）；"
                + "nit 刻度来自本机厂商标定表（dumpsys mBacklight/mNits，分段线性插值）。"
                + "滑块下限 = 阳光模式原厂上限（出厂值运行时读取，各机型各自对齐）。"
                + "目标值经 persist.sunlightboost.target 属性传递（保存时需 root 授权，一次即可）。"
                + "非 OS3 系统模块自动不 Hook。");
        root.addView(note);

        setContentView(root);
        installSlider();   // 先装兜底模式，refresh 拿到标定表后自动切换
        refresh();
    }

    // ---------- 滑块 ----------

    private void installSlider() {
        boolean hasTable = tblBL != null && tblNit != null
                && tblBL.length == tblNit.length && tblBL.length >= 2;
        String mode = hasTable ? "nit" : "pct";
        int nitLo = 0, nitHi = 0;
        if (hasTable) {
            float stockNit = (stockFloat > 0f) ? floatToNit(stockFloat) : -1f;
            nitLo = (stockNit > 0) ? Math.round(stockNit) : Math.round(tblNit[1]);
            nitHi = Math.round(tblNit[tblNit.length - 1]);
        }
        String sig = mode + ":" + nitLo + ":" + nitHi;
        if (sig.equals(sliderSig)) return;   // 没变化不重建，避免打断拖动
        sliderSig = sig;
        sliderMode = mode;
        sliderBox.removeAllViews();

        if (nitMode()) {
            if (nitHi - nitLo < 10) {   // 出厂值已接近面板峰值，无可调空间
                sliderLabel.setText("出厂上限已接近面板峰值，无可调空间");
                TextView t = new TextView(this);
                t.setTextSize(12);
                t.setTextColor(0xFF888888);
                t.setText("出厂阳光上限 ≈ " + nitLo + " nit");
                sliderBox.addView(t);
                return;
            }
            final int fNitLo = nitLo, fNitHi = nitHi;
            SeekBar bar = new SeekBar(this);
            bar.setMax(fNitHi - fNitLo);
            int cur = loadNit(fNitLo, fNitHi);
            bar.setProgress(cur - nitLo);
            sliderLabel.setText(nitLabel(cur));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    sliderLabel.setText(nitLabel(fNitLo + p));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    int nit = fNitLo + s.getProgress();
                    saveTargetFloat(nitToFloat(nit), nit);
                    Toast.makeText(MainActivity.this,
                            "已保存：上限 ≈ " + nit + " nit，软重启后生效",
                            Toast.LENGTH_SHORT).show();
                    refresh();
                }
            });
            sliderBox.addView(bar);
        } else {
            SeekBar bar = new SeekBar(this);
            bar.setMax(1000);                            // 100.0% ~ 200.0%
            bar.setProgress(loadPct() - 1000);
            sliderLabel.setText(pctLabel(loadPct()));
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override
                public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                    sliderLabel.setText(pctLabel(1000 + p));
                }

                @Override
                public void onStartTrackingTouch(SeekBar s) {
                }

                @Override
                public void onStopTrackingTouch(SeekBar s) {
                    int pct = 1000 + s.getProgress();
                    savePct(pct);
                    Toast.makeText(MainActivity.this,
                            "已保存 " + (pct / 10) + "." + (pct % 10) + "%，软重启后生效",
                            Toast.LENGTH_SHORT).show();
                }
            });
            sliderBox.addView(bar);
        }
    }

    private boolean nitMode() {
        return "nit".equals(sliderMode);
    }

    private static String nitLabel(int nit) {
        return "亮度上限：≈ " + nit + " nit";
    }

    private static String pctLabel(int pct) {
        return "增幅倍率：" + (pct / 10) + "." + (pct % 10) + "%（本机未读到标定表）";
    }

    // ---------- prefs ----------

    private int loadPct() {
        try {
            int v = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_PCT, DEFAULT_PCT);
            if (v >= 1000 && v <= 2000) return v;
        } catch (Throwable ignored) {
        }
        return DEFAULT_PCT;
    }

    private int loadNit(int lo, int hi) {
        try {
            int v = getSharedPreferences(PREFS, MODE_PRIVATE).getInt(KEY_NIT, lo);
            if (v >= lo && v <= hi) return v;
        } catch (Throwable ignored) {
        }
        return lo;   // 起步 = 阳光模式原厂上限
    }

    private void saveTargetFloat(float f, int nit) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putInt(KEY_TARGET_PM, Math.round(f * 1000000f))
                    .putInt(KEY_NIT, nit).commit();
        } catch (Throwable ignored) {
        }
        makePrefsWorldReadable();
        // 主通道：经 su 写 persist 属性（hook 在 system_server 里读它；直接读 app 数据被 SELinux 拦）
        final String cmd = "setprop " + PROP_TARGET + " " + Math.round(f * 1000000f);
        new Thread(new Runnable() {
            @Override
            public void run() {
                su(cmd);
            }
        }).start();
    }

    private void savePct(int pct) {
        try {
            getSharedPreferences(PREFS, MODE_PRIVATE).edit().putInt(KEY_PCT, pct).commit();
        } catch (Throwable ignored) {
        }
        makePrefsWorldReadable();
        final String cmd = "setprop " + PROP_PCT + " " + pct;
        new Thread(new Runnable() {
            @Override
            public void run() {
                su(cmd);
            }
        }).start();
    }

    /** 尽量把 prefs 文件设为全局可读，兼容老式 XSharedPreferences 直接读文件的实现。 */
    private void makePrefsWorldReadable() {
        try {
            File base = new File(getApplicationInfo().dataDir);
            base.setReadable(true, false);
            base.setExecutable(true, false);
            File sp = new File(base, "shared_prefs");
            sp.setReadable(true, false);
            sp.setExecutable(true, false);
            new File(sp, PREFS + ".xml").setReadable(true, false);
        } catch (Throwable ignored) {
        }
    }

    /** GUI 侧直接读同一个出厂资源（无需 root）；失败再靠 hook 日志兜底。 */
    private float readStockFromResource() {
        // MIUI 会把 android.miui 扩展资源表挂进每个 app 的 AssetManager
        try {
            int id = getResources().getIdentifier("config_max_manual_brt_boost", "dimen", "android.miui");
            if (id != 0) {
                float v = getResources().getFloat(id);
                if (v > 0f && v <= 1.0f) return v;
            }
        } catch (Throwable ignored) {
        }
        return -1f;
    }

    // ---------- 标定表换算（厂商分段线性，非估算） ----------

    private float nitToFloat(int nit) {
        float n = Math.max(tblNit[0], Math.min(tblNit[tblNit.length - 1], nit));
        for (int i = 0; i < tblNit.length - 1; i++) {
            if (n <= tblNit[i + 1]) {
                float t = (n - tblNit[i]) / (tblNit[i + 1] - tblNit[i]);
                return tblBL[i] + t * (tblBL[i + 1] - tblBL[i]);
            }
        }
        return tblBL[tblBL.length - 1];
    }

    private int floatToNit(float f) {
        float x = Math.max(tblBL[0], Math.min(tblBL[tblBL.length - 1], f));
        for (int i = 0; i < tblBL.length - 1; i++) {
            if (x <= tblBL[i + 1]) {
                float t = (x - tblBL[i]) / (tblBL[i + 1] - tblBL[i]);
                return Math.round(tblNit[i] + t * (tblNit[i + 1] - tblNit[i]));
            }
        }
        return Math.round(tblNit[tblNit.length - 1]);
    }

    // ---------- su 读数 ----------

    private void refresh() {
        info.setText("读取中…（需要 root 授权）");
        new Thread(new Runnable() {
            @Override
            public void run() {
                String out = su(
                        "getprop ro.mi.os.version.name; "
                                + "echo ---CUR---; cat /sys/class/mi_display/disp-DSI-0/brightness_clone; "
                                + "echo ---CUR2---; cat /sys/class/mi_display/disp-DSI-0/panel_brightness; "
                                + "echo ---MAX---; cat /sys/class/leds/lcd-backlight/max_brightness; "
                                + "echo ---LOGIC---; cat /sys/class/leds/lcd-backlight/logic_max_brightness; "
                                + "echo ---SKIN---; cat /sys/class/thermal/thermal_message/board_sensor_temp; "
                                + "echo ---BOOST---; dumpsys display | "
                                + "grep -E 'mMaxManualBoostBrightness|mBacklight=\\['");
                show(parse(out));
            }
        }).start();
    }

    private String parse(String out) {
        String os = segment(out, null, "---CUR---");
        String curRaw = firstNonEmpty(segment(out, "---CUR---", "---CUR2---"),
                segment(out, "---CUR2---", "---MAX---"));
        long cur = num(curRaw);
        long max = num(segment(out, "---MAX---", "---LOGIC---"));
        if (max <= 0) max = 16383;
        long logic = num(segment(out, "---LOGIC---", "---SKIN---"));
        long skin = num(segment(out, "---SKIN---", "---BOOST---"));
        String boost = segment(out, "---BOOST---", null);

        appliedFloat = -1f;
        stockFloat = readStockFromResource();
        stockSrc = (stockFloat > 0f) ? "resource" : "未读到";
        String stockLog = segment(out, "---STOCKLOG---", null);
        if (stockFloat <= 0f && stockLog != null) {
            Matcher ms = STOCK_RE.matcher(stockLog);
            while (ms.find()) {
                try {
                    float v = Float.parseFloat(ms.group(1));
                    if (v > 0f && v <= 1.0f) {
                        stockFloat = v;
                        stockSrc = "hook日志";
                    }
                } catch (Throwable ignored) {
                }
            }
        }
        if (boost != null) {
            Matcher m = BOOST_RE.matcher(boost);
            if (m.find()) {
                try {
                    appliedFloat = Float.parseFloat(m.group(1));
                } catch (Throwable ignored) {
                }
            }
        }
        try {
            Matcher mb = BL_RE.matcher(boost == null ? "" : boost);
            Matcher mn = NITS_RE.matcher(boost == null ? "" : boost);
            if (mb.find() && mn.find()) {
                float[] bl = parseFloatArray(mb.group(1));
                float[] ni = parseFloatArray(mn.group(1));
                if (bl != null && ni != null && bl.length == ni.length && bl.length >= 2) {
                    tblBL = bl;
                    tblNit = ni;
                }
            }
        } catch (Throwable ignored) {
        }

        StringBuilder sb = new StringBuilder();
        sb.append("系统：").append(os.isEmpty() ? "?" : os).append('\n');
        if (cur >= 0) {
            sb.append("当前 DBV：").append(cur).append(" / ").append(max)
              .append("（").append(cur * 100 / max).append("%，实时镜像，随滑条同步）\n");
        } else {
            sb.append("当前 DBV：读取失败（brightness_clone 节点不存在？）\n");
        }
        if (logic > 0) {
            sb.append("逻辑上限：").append(logic);
            if (logic < max) sb.append("（低于满量程，被某层钳制）");
            sb.append('\n');
        }
        if (skin > -100000) {
            sb.append("皮肤温度：").append(skin / 1000).append(".")
              .append((skin % 1000) / 100).append(" °C\n");
        }
        float stockNit = (stockFloat > 0f && tblBL != null) ? floatToNit(stockFloat) : -1f;
        if (stockNit > 0) {
            sb.append("出厂阳光上限：≈ ").append(Math.round(stockNit)).append(" nit（float ")
              .append(stockFloat).append("，来源=").append(stockSrc).append("）\n");
        }
        if (appliedFloat > 0f) {
            sb.append("当前生效 float：").append(appliedFloat)
              .append(" → DBV ≈ ").append(Math.round(appliedFloat * max));
            if (nitMode()) sb.append("（≈ ").append(floatToNit(appliedFloat)).append(" nit）");
            sb.append('\n');
        } else {
            sb.append("dumpsys 未读到 mMaxManualBoostBrightness（root 授权了吗？/ 机型可能不适用）");
        }
        return sb.toString();
    }

    private static float[] parseFloatArray(String s) {
        try {
            String[] parts = s.split(",\\s*");
            float[] out = new float[parts.length];
            for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].trim());
            for (int i = 0; i < out.length - 1; i++) {
                if (out[i + 1] <= out[i]) return null;   // 必须单调递增
            }
            return out;
        } catch (Throwable t) {
            return null;
        }
    }

    private void show(final String text) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                info.setText(text);
                installSlider();   // 拿到标定表后切换到 nit 模式
            }
        });
    }

    private static String firstNonEmpty(String a, String b) {
        return (a != null && !a.isEmpty()) ? a : b;
    }

    private static String segment(String out, String start, String end) {
        if (out == null) return "";
        int a = 0;
        if (start != null) {
            a = out.indexOf(start);
            if (a < 0) return "";
            a += start.length();
        }
        int b = (end == null) ? out.length() : out.indexOf(end, a);
        if (b < 0) b = out.length();
        return out.substring(a, b).trim();
    }

    private static long num(String s) {
        try {
            s = s.split("\n")[0].trim();
            if (!s.isEmpty()) return Long.parseLong(s);
        } catch (Throwable ignored) {
        }
        return -999999;
    }

    private static String su(String cmd) {
        try {
            Process p = Runtime.getRuntime().exec(new String[]{"su", "-c", cmd});
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            p.waitFor();
            p.destroy();
            return sb.toString();
        } catch (Throwable t) {
            return null;
        }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}