# 音频录制智能静音填充开关 - 使用指南

## 开关说明

已实现一个**可控状态开关**，用于对比测试两种音频处理策略：

### 策略A：纯静音合并（enabled=0）
- **行为**：静音时直接feed全0数据
- **特点**：可能导致视频加速
- **用途**：作为对照组，验证问题是否存在

### 策略B：智能静音填充（enabled=1，默认）
- **行为**：静音时填充极低幅度噪声
- **特点**：避免视频加速
- **用途**：作为实验组，验证解决方案效果

---

## 快速开始

### 1. 安装APK

```bash
# 编译
cd D:\gerrit_workspace\oem-screenRecordbk\screen-recorder-oem
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/v100/debug/app-v100-debug.apk
```

### 2. 切换开关

```bash
# ===== 启用智能静音填充（默认，推荐） =====
adb shell setprop debug.screenrecord.silent_fill_enabled 1

# ===== 禁用智能填充（纯静音合并，用于对比测试） =====
adb shell setprop debug.screenrecord.silent_fill_enabled 0
```

### 3. 查看当前状态

```bash
# 查看开关状态
adb shell getprop debug.screenrecord.silent_fill_enabled

# 查看完整配置
adb logcat -s ScreenRecordHelper | grep "LOADED AUDIO SILENT FILL CONFIG"
```

---

## 对比测试方案

### 测试场景：全程静音30秒

#### 第一组：纯静音合并（对照组）

```bash
# 1. 禁用智能填充
adb shell setprop debug.screenrecord.silent_fill_enabled 0

# 2. 重启应用（确保配置生效）
adb shell am force-stop com.hht.oemscreenrecoder
adb shell am start -n com.hht.oemscreenrecoder/.MainActivity

# 3. 开始录制
# - 保持环境安静
# - 录制30秒
# - 停止录制

# 4. 检查视频时长
adb pull /storage/emulated/0/Screen\ Record/ScreenRecord_*.mp4 video_disabled.mp4
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_disabled.mp4

# 预期结果：如果 < 29秒，说明有加速问题
```

#### 第二组：智能静音填充（实验组）

```bash
# 1. 启用智能填充
adb shell setprop debug.screenrecord.silent_fill_enabled 1
adb shell setprop debug.screenrecord.silent_mode 1
adb shell setprop debug.screenrecord.silent_amplitude 3

# 2. 重启应用
adb shell am force-stop com.hht.oemscreenrecoder
adb shell am start -n com.hht.oemscreenrecoder/.MainActivity

# 3. 开始录制
# - 保持环境安静
# - 录制30秒
# - 停止录制

# 4. 检查视频时长
adb pull /storage/emulated/0/Screen\ Record/ScreenRecord_*.mp4 video_enabled.mp4
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_enabled.mp4

# 预期结果：应该接近30秒（30秒 ± 0.5秒）
```

#### 对比结果

```bash
# 对比两个视频的时长
echo "纯静音合并（disabled）："
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_disabled.mp4

echo "智能静音填充（enabled）："
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_enabled.mp4
```

---

## 详细测试矩阵

| 测试场景 | enabled=0（纯静音） | enabled=1（智能填充） | 预期差异 |
|---------|-------------------|---------------------|---------|
| 全程静音30秒 | 可能 < 29秒（加速） | 约30秒（正常） | 时长差异明显 |
| 开始静音10秒，后有声音 | 前10秒可能加速 | 全程正常 | 前段时长差异 |
| 开始有声音，后静音10秒 | 后10秒可能加速 | 全程正常 | 后段时长差异 |
| 交替静音/有声音 | 静音段加速 | 全程正常 | 多段时长差异 |
| 全程有声音 | 正常 | 正常 | 无差异 |

---

## 日志分析

### 启用智能填充时的日志

```bash
# 查看配置加载
adb logcat -s ScreenRecordHelper | grep "LOADED AUDIO SILENT FILL CONFIG"
# 输出示例：
# ★★★ LOADED AUDIO SILENT FILL CONFIG ★★★ enabled=true, mode=LOW_AMPLITUDE_NOISE, amplitude=3

# 查看实时检测
adb logcat -s MicRecorder_SilentFill
# 输出示例：
# [FRAME_10] INTERNAL: maxValue=0, isSilent=true, consecutiveSilent=10, mode=LOW_AMPLITUDE_NOISE
# [SILENT_FILL] Applied mode=LOW_AMPLITUDE_NOISE, consecutiveSilent=50, bytes=4096
# ★★★ AUDIO RESUMED ★★★ After 50 silent frames
```

### 禁用智能填充时的日志

```bash
# 查看配置加载
adb logcat -s ScreenRecordHelper | grep "LOADED AUDIO SILENT FILL CONFIG"
# 输出示例：
# ★★★ LOADED AUDIO SILENT FILL CONFIG ★★★ enabled=false, mode=LOW_AMPLITUDE_NOISE, amplitude=3

# 查看处理逻辑
adb logcat -s MicRecorder | grep "Silent fill DISABLED"
# 输出示例：
# feedAudioEncoder [MIC_AND_INTERNAL]: Silent fill DISABLED, normal processing
# feedAudioEncoder [INTERNAL]: Silent fill DISABLED, normal processing
# feedAudioEncoder [MIC]: Silent fill DISABLED, normal processing
```

---

## 配置参数说明

### 主开关（必须配置）

```bash
# 启用/禁用智能填充
debug.screenrecord.silent_fill_enabled = 0 或 1
# 0 = 禁用（纯静音合并）
# 1 = 启用（智能填充，默认）
```

### 填充模式（仅enabled=1时生效）

```bash
# 填充模式
debug.screenrecord.silent_mode = 1~5
# 1 = 极低幅度噪声（默认，推荐）
# 2 = 固定低值填充
# 3 = 降低采样率
# 4 = 全0 + PTS补偿
# 5 = 混合策略

# 噪声幅度（模式1使用）
debug.screenrecord.silent_amplitude = 1~10
# 默认：3（推荐）
# 越小越接近静音，越大越明显

# 跳帧间隔（模式3使用）
debug.screenrecord.silent_skip_interval = 2~10
# 默认：5

# 初始期（模式5使用）
debug.screenrecord.silent_initial_period = 5000~15000
# 默认：10000（10秒）
```

---

## 推荐测试流程

### 第一步：验证问题存在

```bash
# 1. 禁用智能填充
adb shell setprop debug.screenrecord.silent_fill_enabled 0

# 2. 录制全程静音30秒视频
# 3. 检查视频时长
# 4. 如果 < 29秒，说明问题存在
```

### 第二步：验证解决方案

```bash
# 1. 启用智能填充（模式1，amplitude=3）
adb shell setprop debug.screenrecord.silent_fill_enabled 1
adb shell setprop debug.screenrecord.silent_mode 1
adb shell setprop debug.screenrecord.silent_amplitude 3

# 2. 录制全程静音30秒视频
# 3. 检查视频时长
# 4. 应该接近30秒
```

### 第三步：调整参数（如果需要）

```bash
# 如果仍然加速，增加噪声幅度
adb shell setprop debug.screenrecord.silent_amplitude 5

# 如果听到明显噪声，降低幅度
adb shell setprop debug.screenrecord.silent_amplitude 1

# 尝试其他模式
adb shell setprop debug.screenrecord.silent_mode 2  # 固定低值
adb shell setprop debug.screenrecord.silent_mode 5  # 混合策略
```

---

## 常见问题

### Q1: 修改配置后不生效？
**A**: 需要重启应用：
```bash
adb shell am force-stop com.hht.oemscreenrecoder
adb shell am start -n com.hht.oemscreenrecoder/.MainActivity
```

### Q2: 如何确认配置已生效？
**A**: 查看日志：
```bash
adb logcat -s ScreenRecordHelper | grep "LOADED AUDIO SILENT FILL CONFIG"
```

### Q3: 两种策略的视频时长差异不明显？
**A**: 可能的原因：
1. 测试场景不够极端（建议全程静音30秒）
2. 平台本身没有加速问题
3. 配置未生效（检查日志）

### Q4: 启用智能填充后仍然加速？
**A**: 尝试：
1. 增加噪声幅度：`adb shell setprop debug.screenrecord.silent_amplitude 5`
2. 切换到模式2：`adb shell setprop debug.screenrecord.silent_mode 2`
3. 使用混合策略：`adb shell setprop debug.screenrecord.silent_mode 5`

### Q5: 如何恢复默认配置？
**A**: 清除系统属性：
```bash
adb shell setprop debug.screenrecord.silent_fill_enabled ""
adb shell setprop debug.screenrecord.silent_mode ""
adb shell setprop debug.screenrecord.silent_amplitude ""
```

---

## 测试报告模板

```markdown
## 对比测试报告

### 测试环境
- 设备型号：[填写]
- Android版本：[填写]
- 测试日期：[填写]

### 测试场景：全程静音30秒

#### 纯静音合并（enabled=0）
- 配置：`debug.screenrecord.silent_fill_enabled=0`
- 视频时长：[填写] 秒
- 是否加速：[是/否]
- 备注：[填写]

#### 智能静音填充（enabled=1）
- 配置：
  - `debug.screenrecord.silent_fill_enabled=1`
  - `debug.screenrecord.silent_mode=1`
  - `debug.screenrecord.silent_amplitude=3`
- 视频时长：[填写] 秒
- 是否加速：[是/否]
- 音频质量：[有无明显噪声]
- 备注：[填写]

### 结论
- 时长差异：[填写] 秒
- 智能填充是否有效：[是/否]
- 推荐配置：[填写]
```

---

## 总结

**开关的核心作用**：
- **enabled=0**：用于验证问题（视频加速）是否存在
- **enabled=1**：用于验证解决方案（智能填充）是否有效

**推荐测试顺序**：
1. 先测试 enabled=0，确认问题存在
2. 再测试 enabled=1，验证解决方案
3. 对比两个视频的时长差异
4. 根据结果调整参数

**关键指标**：
- 视频时长准确性（30秒 ± 0.5秒）
- 音频质量（静音段无明显噪声）
- 音视频同步（PTS差异 < 100ms）
