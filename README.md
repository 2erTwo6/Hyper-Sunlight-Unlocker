# Hyper-Sunlight-Unlocker

HyperOS **3（OS3）专用**：阳光模式手动亮度上限解锁。滑块直接以 **nit** 选择上限，刻度来自本机厂商标定表，全部参数运行时读取、零硬编码，多机型开箱即用。

- 适配范围：`ro.mi.os.version.name` 以 `OS3` 开头的 MIUI/HyperOS 系统
- 非 OS3 系统模块**自动不 Hook**（只打日志），未启用该功能的机型自动 no-op
- 参照机型 Redmi Turbo 5 Max (`dash`)：默认 100%（原厂 800 nit），出厂标定峰值 3500 nit

## 原理

手动亮度上限是 system_server 里 `DisplayPowerControllerImpl` 的字段 `mMaxManualBoostBrightness`，在 `init()` 中从框架资源读取：

```java
// miui-services.jar, com.android.server.display.DisplayPowerControllerImpl
this.mMaxManualBoostBrightness = this.mContext.getResources().getFloat(0x1107002f);
// 0x1107002f = android.miui:dimen/config_max_manual_brt_boost
```

该字段是**面板满量程 DBV 的比例值**（DBV = float × 16383，实测锚点精确吻合），并与厂商标定的亮度表逐点对应。本模块是 LSPosed 模块（作用域 = Android 系统），hook `DisplayPowerControllerImpl.init()` 返回点：

1. 运行时从 `android.miui:dimen/config_max_manual_brt_boost` 读出**出厂值**（查不到则退回字段值）
2. 目标值三级解析：`persist.sunlightboost.target`（GUI nit 模式写入的**绝对目标 float**）→ 旧倍率链 → 默认 106.8%，clamp 到 ≤1.0（面板满量程）
3. **只改这一个字段**，其余一概不碰

## GUI（SunlightBoost）

模块 APK 可从桌面直接打开：

- **滑块（nit 模式）**：GUI 从 `dumpsys display` 解析本机厂商标定表（`mBacklight`/`mNits`，分段线性），滑块刻度直接以 nit 显示。**下限 = 阳光模式原厂上限**（出厂值经标定表换算，各机型各自对齐），**上限 = 面板峰值**。读取不到标定表时自动回落为倍率模式（100%–200%）
- **读数面板**：实时 DBV（`brightness_clone`，与滑条同步的镜像节点）+ 逻辑上限 + 皮肤温度 + 当前生效 float/DBV/nit + 出厂上限（含读取来源标注）
- **软重启按钮**：重启 system_server（短暂黑屏，等效重启但更快），新上限在此时生效

注意：DBV 与 nit 不是线性关系（低亮区压缩、峰值区饱和），倍率 ×2 ≠ 亮度 ×2——这正是滑块要按标定表换算的原因。

## 配置传递通道

GUI 与 hook（system_server）之间按优先级：

1. `persist.sunlightboost.target`：绝对目标 float × 1e6（nit 模式，GUI 保存时经 su 写入；system_server 可读、重启不丢）
2. `persist.sunlightboost.pct` / prefs `multiplier_pct`：相对出厂的倍率 ×0.1%（旧倍率模式兜底）
3. 都没有 → 默认 106.8%

为什么不直接读 GUI 的 prefs：模块数据目录被 SELinux per-app 分类隔离，system_server 读不到（实测 EACCES）；XSharedPreferences 各版本行为不一。persist 属性是 root 玩家场景下最稳的通道。

## 门卫与安全设计

- **OS 门卫**：非 OS3 一律不 Hook（连类查找都不做）
- **无效出厂值守卫**：出厂值 ≤ 0（OS4 的 -1.0 哨兵）或 > 1.0 → 不修改
- **不抛异常**：hook 回调全 try/catch，异常只进 LSPosed 日志
- **出厂值优先读资源**：init 重入不会把已改值当出厂值叠加
- 失败分层日志：OS 不符 / 类找不到 / 资源查不到 / 出厂值无效 / 配置来源，各打一条
- 自动亮度逻辑不受影响（该字段只在阳光模式 + 非自动亮度的手动路径参与）

## dash 标定对照表（dumpsys 厂商数据 + 实测锚点，完整分析见 samples.md）

| 滑块 | float | DBV | nit |
|---|---|---|---|
| 原厂（100%）| 0.593761 | 9727 | 800 |
| 默认（106.8%）| 0.634157 | 10389 | 1000 |
| 141% | 0.836223 | 13697 | 2000 |
| 200% | 1.0 | 16383 | 3500（峰值规格）|

## 样本数据（酷安收集，见 samples.md）

| 机型 | 代号 | 系统 | 机制 | 出厂值 | 结论 |
|------|------|------|------|--------|------|
| Redmi Turbo 5 Max | dash | OS3.0.305 | ✓ | 0.593761 | 参照机，全链路实测通过 |
| Redmi K80 Ultra | dali | OS3.0.0.305 | ✓ | 0.66682947 | 运行时读取即适配 |
| Xiaomi 15 | dada | OS4.0.0.6 | 字段在但功能禁用 | -1.0（哨兵） | 自动判定不适用 |

## 构建

需要 JDK 17、python3、curl。`./build.sh`（自动下载 r8/D8 工具链 + 官方 android.jar 编译期 classpath，编译、打包、生成 `signing.keystore` 并签名）。

产物：`SunlightBoostLSP.apk`。

### GitHub Actions 自动构建

`.github/workflows/build.yml`：push 到 `main`、PR、`v*` tag、手动触发均构建；打 tag 时自动发 Release。**注意：本地构建的 `signing.keystore` 与 CI 缓存的钥匙不同，跨来源覆盖安装需先卸载。**

## 安装

```sh
adb push SunlightBoostLSP.apk /data/local/tmp/
adb shell pm install /data/local/tmp/SunlightBoostLSP.apk
```

然后：LSPosed 管理器 → 模块 → **SunlightBoost** → 启用，作用域勾选 **Android 系统**，重启（或用模块内软重启按钮）。首次在 GUI 里保存时会弹 root 授权，允许一次即可。

## 验证

```sh
adb shell dumpsys display | grep mMaxManualBoostBrightness
# 应为 GUI 选定的目标值（≤1.0）
```

LSPosed 日志中 `SBOLSP:` 行显示出厂值 / 目标来源 / 最终值。

## 回滚 / 卸载

- 滑块拖回下限（原厂上限）保存 + 软重启 → 等效关闭
- LSPosed 关闭模块开关 + 重启 → 恢复出厂值
- 彻底：`pm uninstall com.sunlightboost.lsp`

## 已知边界

- OS4（如 Xiaomi 15 dada）上 `SUPPORT_MANUAL_BRIGHTNESS_BOOST=false`、出厂值为 -1.0 哨兵：模块自动不修改。强行支持需实验分支强写能力开关（未实现）
- 非 MIUI/HyperOS 系统：`DisplayPowerControllerImpl` 不存在，hook 失败仅留日志
- MIUI 14（`V14`）未验证，OS 门卫会拒绝 Hook
- 峰值 nit 为厂商标定（小窗口规格），全屏持续输出受 APL 与热限流约束

## 更新日志

- **1.3（2026-09-08）**：滑块改 nit 直选（厂商标定表解析，下限=原厂上限，无表回落倍率）；hook 支持绝对目标；`xposedsharedprefs` 声明 + `<queries>`；配置改 persist 属性通道；读数面板加出厂上限来源标注；prefs 全局可读兜底
- **1.1**：配置改 persist 属性通道（SELinux 拦截 prefs 直读的解法）
- **1.0**：首版，dash 专用硬编码（0.593761 → 0.634171）