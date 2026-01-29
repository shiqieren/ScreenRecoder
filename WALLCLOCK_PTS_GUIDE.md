# 墙钟 PTS 方案 - 画面优先策略

## 核心理念

**优先级**：画面时间准确 > 音频完整性

**核心策略**：
- ❌ 旧方案：PTS 基于"已编码的音频字节数/采样数"
- ✅ 新方案：PTS 基于"真实经过的时间（墙钟/单调时钟）"

**效果**：
- ✅ 音频数据可以丢失，但时间轴不会压缩
- ✅ 视频播放速度准确，不会加速
- ✅ 音频缺失部分可以用静音填充
- ✅ 适合资源不足的老设备

---

## 问题背景

### 旧方案的问题

**PTS 计算方式**：
```java
// 基于"采样数"计算帧时长
long frameUs = samples * 1000_000 / sampleRate;
// 累加到上一帧的时间戳
currentPTS = lastPTS + frameUs;
```

**问题**：
1. 如果音频数据丢失，`samples` 变少
2. `frameUs` 变小，PTS 增长变慢
3. 时间轴被压缩
4. **最终表现：视频加速播放**

### 新方案的解决

**PTS 计算方式**：
```java
// 直接使用真实经过的时间
long elapsedNanos = SystemClock.elapsedRealtimeNanos() - recordingStartTimeNanos;
long ptsUs = elapsedNanos / 1000;  // 转换为微秒
```

**优势**：
1. PTS 只跟真实时间走，不依赖音频采样数
2. 即使音频数据丢失，PTS 仍然按照真实时间增长
3. 时间轴保持连续
4. **视频播放速度准确，不会加速**

---

## 实现细节

### 1. 添加墙钟时间戳（MicRecorder.java:100-107）

```java
// ===== 墙钟时间戳（主时钟）=====
// 核心策略：PTS 只跟真实时间走，不依赖音频采样数
// 优先保证画面时间准确，允许音频丢失
private long mRecordingStartTimeNanos = 0;  // 录制开始的纳秒时间戳（单调时钟）
private boolean mUseWallClockPTS = true;    // 是否使用墙钟作为 PTS（默认启用）
```

### 2. 初始化时间戳（MicRecorder.java:403-406）

```java
// 在录制开始时（MSG_PREPARE 成功后）
mRecordingStartTimeNanos = SystemClock.elapsedRealtimeNanos();
Log.i(TAG, "★★★ WALL CLOCK PTS INITIALIZED ★★★ startTimeNanos=" + mRecordingStartTimeNanos);
```

### 3. 修改 PTS 计算（MicRecorder.java:1157-1173）

```java
private long calculateFrameTimestamp(int totalBits) {
    // ===== 墙钟模式：PTS 只跟真实时间走，不依赖音频采样数 =====
    if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
        // 计算从录制开始到现在经过的真实时间（微秒）
        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
        long elapsedNanos = currentTimeNanos - mRecordingStartTimeNanos;
        long ptsUs = elapsedNanos / 1000;  // 转换为微秒

        if (VERBOSE && mFrameCount % 100 == 0) {
            Log.d(TAG, String.format(Locale.US,
                "[WALL_CLOCK_PTS] frame=%d, elapsedMs=%.2f, ptsUs=%d",
                mFrameCount, elapsedNanos / 1_000_000.0, ptsUs));
        }

        return ptsUs;
    }

    // ===== 原有的基于采样数的 PTS 计算（兼容模式）=====
    // ... 保留原有逻辑 ...
}
```

### 4. 添加配置方法（MicRecorder.java:1688-1697）

```java
/**
 * 设置是否使用墙钟作为 PTS（主时钟）
 * @param useWallClock true=使用墙钟（推荐），false=使用采样数计算
 */
public void setUseWallClockPTS(boolean useWallClock) {
    this.mUseWallClockPTS = useWallClock;
    Log.i(TAG, String.format("★★★ WALL CLOCK PTS MODE ★★★ enabled=%b (true=画面优先, false=采样数计算)",
        useWallClock));
}
```

### 5. 配置加载（ScreenRecorderHelper.kt:611-615）

```kotlin
// ===== 读取墙钟 PTS 模式（画面优先）=====
val useWallClockStr = getSystemProperty("debug.screenrecord.use_wallclock_pts", "1")
val useWallClock = useWallClockStr == "1" || useWallClockStr.equals("true", ignoreCase = true)

// 设置墙钟 PTS 模式
mRecorder?.setUseWallClockPTS(useWallClock)
Log.i(TAG, "★★★ WALL CLOCK PTS MODE ★★★ enabled=$useWallClock (true=画面优先/允许音频丢失, false=采样数计算)")
```

---

## 测试方法

### 1. 安装 APK

```bash
# 编译
cd D:\gerrit_workspace\oem-screenRecordbk\screen-recorder-oem
./gradlew assembleDebug

# 安装
adb install -r app/build/outputs/apk/v100/debug/app-v100-debug.apk
```

### 2. 配置测试模式

#### 启用墙钟 PTS（默认，推荐）

```bash
# 启用墙钟 PTS（画面优先）
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 重启应用
adb shell am force-stop com.hht.oemscreenrecoder
adb shell am start -n com.hht.oemscreenrecoder/.MainActivity
```

#### 禁用墙钟 PTS（对比测试）

```bash
# 禁用墙钟 PTS（使用采样数计算）
adb shell setprop debug.screenrecord.use_wallclock_pts 0

# 重启应用
adb shell am force-stop com.hht.oemscreenrecoder
adb shell am start -n com.hht.oemscreenrecoder/.MainActivity
```

### 3. 启用详细日志

```bash
# 启用 MicRecorder 日志
adb shell setprop log.tag.MicRecorder VERBOSE

# 实时查看日志
adb logcat -s MicRecorder ScreenRecordHelper
```

### 4. 测试场景

#### 场景1：正常录制（基准测试）

**目的**：验证墙钟 PTS 在正常情况下工作正常

```bash
# 1. 启用墙钟 PTS
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 2. 开始录制
# 3. 录制 60 秒
# 4. 停止录制

# 5. 检查日志
adb logcat -d | grep "WALL_CLOCK_PTS"

# 6. 验证视频时长
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video.mp4

# 预期结果：
# - 日志中有 "WALL_CLOCK_PTS INITIALIZED"
# - 视频时长准确（60秒 ± 0.5秒）
```

#### 场景2：高负载录制（压力测试）

**目的**：模拟音频数据丢失的情况

```bash
# 1. 启用墙钟 PTS
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 2. 同时运行高负载应用（游戏、视频播放）
# 3. 开始录制
# 4. 录制 60 秒
# 5. 停止录制

# 6. 检查日志
adb logcat -d | grep "BUFFER NOT AVAILABLE\|Frames dropped"

# 7. 验证视频时长
ffprobe video.mp4

# 预期结果：
# - 可能有音频数据丢失（"BUFFER NOT AVAILABLE"）
# - 但视频时长仍然准确（60秒 ± 0.5秒）
# - 音频可能有缺失，但画面不会加速
```

#### 场景3：对比测试（墙钟 vs 采样数）

**目的**：验证墙钟 PTS 的优势

```bash
# ===== 第一组：采样数模式（旧方案）=====
adb shell setprop debug.screenrecord.use_wallclock_pts 0
adb shell am force-stop com.hht.oemscreenrecoder

# 高负载录制 60 秒
# 检查视频时长
ffprobe video_sampling.mp4
# 可能结果：< 58秒（视频加速）

# ===== 第二组：墙钟模式（新方案）=====
adb shell setprop debug.screenrecord.use_wallclock_pts 1
adb shell am force-stop com.hht.oemscreenrecoder

# 高负载录制 60 秒
# 检查视频时长
ffprobe video_wallclock.mp4
# 预期结果：60秒 ± 0.5秒（时长准确）

# ===== 对比结果 =====
echo "采样数模式："
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_sampling.mp4

echo "墙钟模式："
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video_wallclock.mp4
```

#### 场景4：极端压力测试（老设备）

**目的**：验证在资源极度不足的情况下，画面时间仍然准确

```bash
# 1. 启用墙钟 PTS
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 2. 同时运行多个高负载应用
# 3. 降低设备性能（省电模式）
# 4. 开始录制
# 5. 录制 120 秒
# 6. 停止录制

# 7. 检查统计
adb logcat -d | grep "BUFFER QUEUE STATS"

# 8. 验证视频时长
ffprobe video.mp4

# 预期结果：
# - 音频可能有大量丢失（Frames dropped > 0）
# - 但视频时长仍然准确（120秒 ± 1秒）
# - 音频有缺失，但画面播放速度正常
```

---

## 关键日志标识

### 初始化日志

```
★★★ WALL CLOCK PTS INITIALIZED ★★★ startTimeNanos=123456789
★★★ WALL CLOCK PTS MODE ★★★ enabled=true (true=画面优先, false=采样数计算)
```

### 运行时日志（每 100 帧打印一次）

```
[WALL_CLOCK_PTS] frame=100, elapsedMs=2000.00, ptsUs=2000000
[WALL_CLOCK_PTS] frame=200, elapsedMs=4000.00, ptsUs=4000000
```

### 音频丢失日志（如果发生）

```
encode: ★★★ BUFFER NOT AVAILABLE ★★★ Queueing 8192 bytes
encode: Frame queued successfully (queueSize=3, maxDepth=3)
```

---

## 验证指标

### 1. 视频时长准确性（最重要）

```bash
# 录制 60 秒视频后，检查实际时长
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video.mp4

# 预期：
# - 墙钟模式：60秒 ± 0.5秒（准确）
# - 采样数模式：可能 < 58秒（加速）
```

### 2. 音频完整性

```bash
# 提取音频
ffmpeg -i video.mp4 -vn -acodec copy audio.aac

# 检查音频波形
ffmpeg -i audio.aac -filter_complex "showwavespic=s=1920x1080" -frames:v 1 waveform.png

# 分析：
# - 墙钟模式：可能有静音段（音频丢失）
# - 但视频时长准确
```

### 3. 音视频同步

```bash
# 检查音视频 PTS 差异
ffmpeg -i video.mp4 -vf "showinfo" -af "ashowinfo" -f null - 2>&1 | grep "pts_time"

# 分析：
# - 墙钟模式：音频 PTS 可能有跳跃（丢失部分）
# - 但视频 PTS 连续，播放速度正常
```

---

## 常见问题

### Q1: 墙钟 PTS 会导致音视频不同步吗？

**A**: 不会导致播放速度问题，但可能有以下情况：

1. **音频缺失**：如果音频数据丢失，音频轨道会有静音段
2. **PTS 跳跃**：音频 PTS 可能有跳跃，但视频 PTS 连续
3. **播放正常**：视频播放速度准确，不会加速

**解决方案**：
- 结合智能静音填充（`silent_fill_enabled=1`）
- 在音频缺失处填充静音，保持音频轨道连续

### Q2: 什么时候应该使用墙钟 PTS？

**A**: 推荐在以下情况使用：

✅ **应该使用**：
- 资源不足的老设备
- 高负载录制场景
- 优先保证画面时间准确
- 可以接受音频有缺失

❌ **不应该使用**：
- 音频质量要求极高的场景
- 需要完美音视频同步的场景
- 设备性能充足的情况

### Q3: 如何判断墙钟 PTS 是否生效？

**A**: 检查日志：

```bash
# 查看初始化日志
adb logcat -d | grep "WALL CLOCK PTS INITIALIZED"

# 查看运行时日志
adb logcat -d | grep "WALL_CLOCK_PTS"

# 如果看到这些日志，说明墙钟 PTS 已启用
```

### Q4: 墙钟 PTS 和缓冲队列可以同时使用吗？

**A**: 可以，而且推荐同时使用：

- **缓冲队列**：尽量减少音频数据丢失
- **墙钟 PTS**：即使音频丢失，也保证时间轴准确

**配置**：
```bash
# 启用缓冲队列（默认启用）
# 启用墙钟 PTS
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 启用智能静音填充
adb shell setprop debug.screenrecord.silent_fill_enabled 1
```

### Q5: 如何恢复默认配置？

**A**: 清除系统属性：

```bash
adb shell setprop debug.screenrecord.use_wallclock_pts ""
```

---

## 性能影响

- **CPU 占用**：无明显增加（仅改变 PTS 计算方式）
- **内存占用**：无增加
- **编码延迟**：无影响
- **电池消耗**：无影响

---

## 技术原理

### 为什么使用 SystemClock.elapsedRealtimeNanos()？

1. **单调时钟**：不受系统时间调整影响
2. **高精度**：纳秒级精度
3. **不会回退**：即使系统时间被修改，也保持单调递增
4. **适合计时**：Android 官方推荐用于计时场景

### PTS 计算公式

```
PTS (微秒) = (当前时间 - 录制开始时间) / 1000

其中：
- 当前时间：SystemClock.elapsedRealtimeNanos()
- 录制开始时间：mRecordingStartTimeNanos
- 除以 1000：纳秒转换为微秒
```

### 与采样数计算的对比

| 方式 | PTS 计算 | 优点 | 缺点 |
|------|---------|------|------|
| **采样数** | `PTS = samples * 1000000 / sampleRate` | 音频完整时精确 | 音频丢失时时间轴压缩 |
| **墙钟** | `PTS = (now - start) / 1000` | 时间轴始终准确 | 音频丢失时可能不同步 |

---

## 总结

### 核心优势

✅ **画面优先**：保证视频播放速度准确，不会加速
✅ **时间轴准确**：PTS 只跟真实时间走，不依赖音频采样数
✅ **允许音频丢失**：音频数据丢失不影响视频时长
✅ **适合老设备**：资源不足时优先保证画面质量

### 适用场景

- 资源不足的老设备
- 高负载录制场景
- 长时间录制（> 1 小时）
- 优先保证画面时间准确的场景

### 推荐配置

```bash
# 启用墙钟 PTS（画面优先）
adb shell setprop debug.screenrecord.use_wallclock_pts 1

# 启用智能静音填充（填补音频缺失）
adb shell setprop debug.screenrecord.silent_fill_enabled 1

# 启用缓冲队列（减少音频丢失）
# （默认启用，无需配置）
```

---

## 下一步优化

1. **自适应模式**：根据设备性能自动选择墙钟或采样数模式
2. **静音填充增强**：在音频缺失处自动填充静音，保持音频轨道连续
3. **音视频同步补偿**：检测音视频 PTS 差异，自动调整
4. **降级机制**：如果墙钟模式出现问题，自动切换到采样数模式
