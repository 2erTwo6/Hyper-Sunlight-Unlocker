# Hyper-Sunlight-Unlocker

HyperOS 阳光模式增强：把**手动亮度上限**从 800 nit 提升到 1000 nit（DBV 9728 → 10390）。

适配 Redmi Turbo 5 Max (`dash`) / HyperOS 3.0.305 (Android 16)。其他机型按「原理」一节自行核对后改值。

## 原理

手动亮度上限是 system_server 里 `DisplayPowerControllerImpl` 的字段 `mMaxManualBoostBrightness`，在 `init()` 中从框架资源读取：

```java
// miui-services.jar, com.android.server.display.DisplayPowerControllerImpl
this.mMaxManualBoostBrightness = this.mContext.getResources().getFloat(0x1107002f);
// 0x1107002f = android.miui:dimen/config_max_manual_brt_boost
```

- 原厂值 `0.593761` → 滑条最大 DBV 9728 ≈ 800 nit
- 目标值 `0.634171` → DBV 10390 ≈ 1000 nit（由面板 nit-DBV 换算表得出）

本模块是 LSPosed 模块（作用域 = Android 系统），hook `DisplayPowerControllerImpl.init()` 的返回点，把该字段改写为 `0.634171`。**只改这一个字段**，其余一概不碰。

设计约束：

- **值守卫**：仅当当前值恰为 `0.593761` 时才改写；其他值只记日志不动手——ROM 更新改了默认值时自动降级为 no-op
- **不抛异常**：hook 回调全 try/catch，异常只进 LSPosed 日志，永不进入 system_server
- **为什么不用 RRO/overlay**：静态 RRO 的资源裁决优先级不可控（扫描顺序决定胜负），且为了取胜需要改包名/文件名触发包管理 churn，实测导致 MIUI 桌面开机崩溃循环（UI 完全不可用）。LSPosed 运行时 hook 零挂载、零包注册、零资源覆盖，出问题关开关重启即恢复
- 自动亮度逻辑不受影响（该字段只在阳光模式 + 非自动亮度的手动路径参与）

## 构建

需要 JDK 17、python3、curl。`./build.sh`（自动下载 r8/D8 工具链、编译、打包、生成 `signing.keystore` 并签名）。

产物：`SunlightBoostLSP.apk`。

### GitHub Actions 自动构建

仓库已配置 CI（`.github/workflows/build.yml`），无需本地环境：

- **触发时机**：push 到 `main`、PR、打 `v*` tag、或手动 `workflow_dispatch`
- **产物**：每次构建的 APK 上传为 Actions artifact（`SunlightBoostLSP-apk`）
- **发布**：打 `v1.2.3` 这类 tag 时自动创建 GitHub Release 并附带 APK，版本号取自 tag（`versionName=v1.2.3`，`versionCode=10203`）
- **签名稳定**：CI 缓存 `signing.keystore`，所有构建共用同一把钥匙，用户可直接覆盖安装更新

若要在其他 ROM 上重新推导 `ATTR` 表（manifest 属性 → framework 资源 id）：

```sh
python3 extract_attr_ids.py <任意系统apk路径>   # 从其 AndroidManifest.xml 提取
```

## 安装

```sh
adb push SunlightBoostLSP.apk /data/local/tmp/
adb shell pm install /data/local/tmp/SunlightBoostLSP.apk
```

然后：LSPosed 管理器 → 模块 → **SunlightBoost** → 启用，作用域勾选 **Android 系统**，重启。

## 验证

```sh
adb shell dumpsys display | grep mMaxManualBoostBrightness
# mMaxManualBoostBrightness=0.634171
```

手动亮度（自动亮度关闭时）滑条拉到底 = DBV 10390 ≈ 1000 nit。

## 回滚 / 卸载

- 临时：LSPosed 关闭模块开关 + 重启 → 恢复原厂 800 nit
- 彻底：`pm uninstall com.sunlightboost.lsp`
