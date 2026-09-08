# 样本收集表（酷安志愿者数据）

目的：确认 `DisplayPowerControllerImpl` + `mMaxManualBoostBrightness` 机制跨机型/版本成立，并收集各机型出厂值用于逐机标定。

换算关系（dash 验证）：字段值 × 面板满量程 DBV(16383) = 滑条最大 DBV。nit 换算需各面板曲线（ddc.xml）。

| 机型 | 代号 | ROM 构建 | SUPPORT | 出厂值 mMaxManualBoostBrightness | 对应 DBV（估） | 备注 |
|------|------|----------|---------|------|------|------|
| Redmi Turbo 5 Max | dash | OS3.0.305.0.WPLCNXM | true | 0.593761（参照） | 9728 ≈ 800 nit | 模块原始适配对象；实测模块生效后 0.634171 |
| Redmi K80 Ultra | dali | OS3.0.0.305.0.WONCNXM | true | 0.66682947 | ≈10925 | 待补 ddc.xml；dump 字段名与 dash 不同（`mManualBoostBrightnessEnable=false`，待确认阳光模式开关状态）；类名需 probe 验证 |
| Xiaomi 15 | dada | OS4.0.0.6.XOCCNXM | **false** | **-1.0（哨兵值，功能默认禁用）** | — | HyperOS 4.0：字段仍在但功能关闭；新增 mDynamicBoost*/Cine Look/Privacy Screen 段落；需确认阳光模式开关状态 |

## dash 标定对照表（有据，来源 = 手机 dumpsys mBacklight/mNits 厂商标定 + DBV 线性标度实测）

- float = backlight（mBrightnessToBacklightSpline 恒等映射）；DBV = float × 16383（实测锚点 0.634137→10389、1.0→16383）
- nit 五点为厂商标定（mBacklight=[0.000855, 0.499939, 0.593761, 0.836223, 1.0] / mNits=[2, 600, 800, 2000, 3500]），段内 LinearSpline 线性
- 段斜率（DBV/nit）：13.67 → 7.69 → 3.31 → 1.79；HBM transition=0.499938（600 nit）
- 3500 nit 为峰值规格（小窗口），全屏持续输出受 APL/热限流

| 滑块 | float | DBV | nit |
|---|---|---|---|
| 最低锚点 | 0.000855 | 14 | 2 |
| HBM 启动 | 0.499939 | 8190 | 600 |
| 100%（原厂）| 0.593761 | 9727 | 800 |
| 106.8%（默认）| 0.634157 | 10389 | 1000 |
| 125% | 0.742201 | 12158 | ≈1535 |
| ≈141% | 0.836223 | 13697 | 2000 |
| 150% | 0.890642 | 14588 | ≈2498 |
| 200% | 1.0 | 16383 | 3500 |

## 待办

- [ ] dali：收 display.txt + ddc.xml，换算 nit
- [ ] dali：确认阳光模式开关状态（`mManualBoostBrightnessEnable=false`）
- [ ] dada：确认显示设置里阳光模式/高亮模式开关状态，开启后复测 grep（看 SUPPORT 是否翻 true）
- [ ] 构建 probe APK（只打日志不改值），验证跨版本类名/方法名
- [ ] 通用版必须处理：出厂值 ≤ 0（-1.0 哨兵）→ 视为不适用，no-op + 明确日志；可选实验分支：强写 SUPPORT=true + 开关 + 字段（能否生效取决于面板能力，不承诺）
- [ ] 更多样本：覆盖不同系统大版本（MIUI 14 / HyperOS 1/2）与非同面板机型