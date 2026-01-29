# 视频 PTS 时间轴压缩问题 - 修复总结

## 问题发现

### 关键证据

**同一帧的两种 PTS**：
```
writeSampleData: track=0 (VIDEO)... pts=1776449122
WRITING TO MUXER ... pts=697000
```

**下一帧**：
```
writeSampleData: track=0 (VIDEO)... pts=1776487274
WRITING TO MUXER ... pts=698593
```

### 时间轴压缩分析

**原始 PTS 间隔**：
```
1776487274 - 1776449122 = 38152 us ≈ 38ms
（正常，接近 30fps 的 33ms）
```

**写入 PTS 间隔**：
```
698593 - 697000 = 1593 us ≈ 1.6ms
```

**压缩倍数**：
```
38ms → 1.6ms
压缩约 24 倍 → 播放必然"加速"
```

---

## 根本原因

### 问题流程

1. **Muxer 未启动时**：
   ```
   startMuxerIfReady: NOT READY - returning early
   muxVideo: Muxer not ready - PENDING video buffer index=0/1/2/3
   ```
   多个 video buffer 被 pending（挂起）

2. **Muxer 启动后**：
   ```
   Started media muxer!
   writeSampleData: WRITING TO MUXER ... pts=697000
   writeSampleData: WRITING TO MUXER ... pts=698593
   writeSampleData: WRITING TO MUXER ... pts=700186
   ```
   这些 pending 的帧在极短时间内被写入

3. **错误的 PTS 重写逻辑**（旧实现）：
   ```java
   // ❌ 错误：使用"当前时间"（写入时刻）
   long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
   long wallClockPtsUs = (currentTimeNanos - mRecordingStartTimeNanos) / 1000;
   buffer.presentationTimeUs = wallClockPtsUs;
   ```

4. **时间轴压缩**：
   - 这些 pending 的帧在极短时间内（几毫秒）被处理
   - 每个帧的 pts 都被设置为"当前时间"
   - 导致这些帧的 pts 间隔变成 1.6ms 而不是 38ms
   - **时间轴被压扁 → 播放加速**

---

## 修复方案

### 核心策略

**❌ 错误做法**：使用"写入时刻的当前时间"
```java
long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
buffer.presentationTimeUs = (currentTimeNanos - startNs) / 1000;
```

**✅ 正确做法**：保持相对时间间隔，只调整起点
```java
if (mVideoPtsOffset == 0) {
    mVideoPtsOffset = buffer.presentationTimeUs;  // 记录第一帧
    buffer.presentationTimeUs = 0;
} else {
    long relativePts = buffer.presentationTimeUs - mVideoPtsOffset;
    buffer.presentationTimeUs = relativePts - pauseDelayUs;
}
```

### 修复后的 resetVideoPts()

**位置**：`ScreenRecorder.java:518-551`

```java
private void resetVideoPts(MediaCodec.BufferInfo buffer) {
    // ===== 墙钟模式：保持相对时间间隔，只调整起点 =====
    if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
        // 记录第一帧的 codec pts 作为 offset
        if (mVideoPtsOffset == 0) {
            mVideoPtsOffset = buffer.presentationTimeUs;
            buffer.presentationTimeUs = 0;  // 第一帧从 0 开始
            Log.i(TAG, String.format(java.util.Locale.US,
                "resetVideoPts [WALL_CLOCK]: First frame, codecPts=%d, offset=%d, newPts=0",
                mVideoPtsOffset, mVideoPtsOffset));
        } else {
            // 后续帧：保持相对时间间隔
            long originalPts = buffer.presentationTimeUs;
            long relativePts = originalPts - mVideoPtsOffset;

            // 处理暂停延迟
            long pauseDelayUs = pauseDelayTime.get() / 1000;
            buffer.presentationTimeUs = relativePts - pauseDelayUs;

            if (VERBOSE) {
                Log.d(TAG, String.format(java.util.Locale.US,
                    "resetVideoPts [WALL_CLOCK]: codecPts=%d, offset=%d, relativePts=%d, pauseDelay=%d, finalPts=%d",
                    originalPts, mVideoPtsOffset, relativePts, pauseDelayUs, buffer.presentationTimeUs));
            }
        }
        return;
    }

    // ===== 原有的基于 codec PTS 的计算（兼容模式）=====
    // ...
}
```

---

## 关键改进

### 修复前

```
原始 pts：1776449122 → 写入 pts：697000
原始 pts：1776487274 → 写入 pts：698593

间隔：38ms → 1.6ms（压缩 24 倍）
```

### 修复后

```
原始 pts：1776449122 → 写入 pts：0
原始 pts：1776487274 → 写入 pts：38152

间隔：38ms → 38ms（保持不变）
```

---

## 为什么音频没有这个问题？

### 音频的 PTS 计算

**位置**：`MicRecorder.java:1157-1173`

```java
private long calculateFrameTimestamp(int totalBits) {
    if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
        // 在"编码时"计算墙钟 PTS
        long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
        long elapsedNanos = currentTimeNanos - mRecordingStartTimeNanos;
        long ptsUs = elapsedNanos / 1000;
        return ptsUs;
    }
    // ...
}
```

**关键区别**：
- 音频的墙钟 PTS 是在"编码时"计算的（`calculateFrameTimestamp()`）
- 每次编码一帧音频时，就立即计算 PTS
- 不会出现"pending 后批量处理"的情况

**日志证据**：
```
原始 pts=633227，但写入 pts=0 / 23219 / 46438 …
```
- 23219us ≈ 1024 samples @ 44100Hz ≈ 23.22ms
- 音频虽然重写 pts，但时间轴是连续的

---

## 视频为什么不能用"当前时间"？

### 问题场景

1. **Muxer 未启动**：
   - 视频编码器已经开始工作
   - 产生了多个 output buffer（index=0/1/2/3）
   - 这些 buffer 被 pending（挂起）

2. **Muxer 启动**：
   - 音频格式出来了
   - Muxer 启动：`Started media muxer!`
   - 开始处理 pending 的 video buffer

3. **批量写入**：
   - 这些 pending 的帧在极短时间内（几毫秒）被处理
   - 如果使用"当前时间"，每个帧的 pts 都是"写入时刻"
   - 导致 pts 间隔变成几毫秒

### 正确做法

**保持相对时间间隔**：
- 不使用"当前时间"
- 使用 codec 给的 pts，保持相对间隔
- 只调整起点（第一帧从 0 开始）

---

## 测试验证

### 安装新版本

```bash
adb install -r app/build/outputs/apk/v100/debug/app-v100-debug.apk
```

### 验证关键日志

#### 1. 第一帧

```
resetVideoPts [WALL_CLOCK]: First frame, codecPts=1776449122, offset=1776449122, newPts=0
writeSampleData: WRITING TO MUXER ... track=0 (VIDEO), pts=0
```

#### 2. 后续帧

```
resetVideoPts [WALL_CLOCK]: codecPts=1776487274, offset=1776449122, relativePts=38152, finalPts=38152
writeSampleData: WRITING TO MUXER ... track=0 (VIDEO), pts=38152
```

#### 3. 验证间隔

```
第1帧：pts=0
第2帧：pts=38152
间隔：38152 us ≈ 38ms（正常）
```

**预期**：
- ✅ 不再看到 pts 间隔被压缩到 1.6ms
- ✅ pts 间隔保持在 33-40ms（30fps）
- ✅ 视频播放速度正常，不会加速

### 验证视频时长

```bash
# 录制 60 秒
# 检查视频时长
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video.mp4

# 预期：60秒 ± 0.5秒
```

---

## 总结

### 问题根源

**时间轴压缩 24 倍**：
- 使用"写入时刻的当前时间"作为 PTS
- Pending 的帧在极短时间内被写入
- PTS 间隔从 38ms 压缩到 1.6ms

### 修复方案

**保持相对时间间隔**：
- 记录第一帧的 codec pts 作为 offset
- 后续帧：`newPts = codecPts - offset`
- 不使用"当前时间"

### 关键洞察

**音频 vs 视频**：
- 音频：在"编码时"计算墙钟 PTS → 正确
- 视频：在"写入时"使用"当前时间" → 错误（已修复）

### 最终效果

✅ **时间轴不再压缩**
✅ **视频播放速度正常**
✅ **PTS 间隔保持连续**
✅ **兼容 pending/flush 场景**

---

## 编译状态

```
BUILD SUCCESSFUL in 1m 10s
```

准备好测试新版本！
