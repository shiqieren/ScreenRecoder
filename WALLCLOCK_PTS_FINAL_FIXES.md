# 墙钟 PTS 方案 - 最终修复总结

## 根据你的分析完成的关键修复

### 问题1：Truncate 导致时间轴变短 ✅ 已修复

**问题**：
```
processPendingFrames: Frame too large! frameSize=10240, bufferCapacity=2048, will truncate
Truncated 8192 bytes (totalDropped=xxxxx)
```

**根本原因**：
- 当 codec input buffer 拿不到时，我把"剩余音频"攒成一个大块（10240 bytes）
- 等到 later 再喂进去时，codec 的 input buffer capacity 只有 2048 bytes
- 我选择了 truncate（截断），导致时间轴变短

**修复方案**（MicRecorder.java:716-795）：
```java
private void processPendingFrames() {
    synchronized (mPendingFramesQueue) {
        while (!mPendingFramesQueue.isEmpty()) {
            AudioFrame frame = mPendingFramesQueue.peek();  // 不移除，先处理

            // ===== 分片处理大帧，不截断 =====
            int offset = 0;
            while (offset < frame.length) {
                int bufferIndex = mEncoder.getEncoder().dequeueInputBuffer(ENCODER_BUFFER_TIMEOUT_MS);
                if (bufferIndex < 0) {
                    // 没有 buffer，停止处理
                    if (offset > 0) {
                        // 创建新 frame 包含剩余数据
                        byte[] remainingData = new byte[frame.length - offset];
                        System.arraycopy(frame.data, offset, remainingData, 0, remainingData.length);
                        AudioFrame newFrame = new AudioFrame(remainingData, remainingData.length, frame.timestamp);

                        // 移除旧 frame，添加新 frame
                        mPendingFramesQueue.poll();
                        mPendingFramesQueue.addFirst(newFrame);
                    }
                    return;
                }

                ByteBuffer buff = mEncoder.getInputBuffer(bufferIndex);
                int bufferCapacity = buff.capacity();

                // 计算本次写入的字节数（不超过 buffer 容量）
                int bytesToWrite = Math.min(frame.length - offset, bufferCapacity);

                // 写入数据
                buff.put(frame.data, offset, bytesToWrite);
                long pstTs = calculateFrameTimestamp(bytesToWrite << 3);
                mEncoder.queueInputBuffer(bufferIndex, 0, bytesToWrite, pstTs, BUFFER_FLAG_KEY_FRAME);

                offset += bytesToWrite;
            }

            // 整个 frame 处理完毕，从队列移除
            mPendingFramesQueue.poll();
        }
    }
}
```

**关键改进**：
- ✅ 不再 truncate，而是按 buffer capacity 分片
- ✅ 如果处理到一半 buffer 不足，保存剩余数据到队列头部
- ✅ 下次继续处理剩余数据
- ✅ 保证所有数据都被喂入，时间轴不会变短

---

### 问题2：startNs 初始化太晚 ✅ 已修复

**问题**：
```
你日志里能看到：video 在 muxer ready 之前就已经有 PENDING buffers，
而你 WALL CLOCK PTS INITIALIZED 是在后面才打的。
```

**根本原因**：
- 之前在 MicRecorder MSG_PREPARE 中初始化 startNs
- 但录制更早开始，导致前面那段时间被"压扁/裁掉"

**修复方案**：

1. **在 ScreenRecorder.record() 开始时初始化**（ScreenRecorder.java:276-279）：
```java
private void record() {
    // ...
    mIsRunning.set(true);

    // ===== 初始化墙钟时间戳（统一起点）=====
    // 关键：在录制真正开始时就初始化，音频和视频共用同一个起点
    mRecordingStartTimeNanos = android.os.SystemClock.elapsedRealtimeNanos();
    Log.i(TAG, "★★★ WALL CLOCK PTS INITIALIZED (UNIFIED) ★★★ startTimeNanos=" + mRecordingStartTimeNanos);

    // ... 后续的 prepareVideoEncoder() 和 prepareAudioEncoder()
}
```

2. **传递给音频编码器**（ScreenRecorder.java:296-302）：
```java
prepareAudioEncoder();
// ===== 将统一的 startNs 传递给音频编码器 =====
if (mAudioEncoder != null) {
    mAudioEncoder.setRecordingStartTimeNanos(mRecordingStartTimeNanos);
    mAudioEncoder.setUseWallClockPTS(mUseWallClockPTS);
    Log.i(TAG, "★★★ PASSED startNs TO AUDIO ENCODER ★★★");
}
```

3. **移除 MicRecorder 中的重复初始化**（MicRecorder.java:403-409）：
```java
// ===== startNs 已在 ScreenRecorder.record() 中初始化并传递过来 =====
// 不再在这里重复初始化，避免 startNs 定得太晚
if (mRecordingStartTimeNanos == 0) {
    Log.w(TAG, "★★★ WARNING ★★★ startNs not set from ScreenRecorder, using fallback");
    mRecordingStartTimeNanos = SystemClock.elapsedRealtimeNanos();
}
```

**关键改进**：
- ✅ startNs 在 ScreenRecorder.record() 开始时就初始化
- ✅ 音频和视频共用同一个 startNs
- ✅ 避免前面那段时间被压扁

---

### 问题3：视频 PTS 也要墙钟 ✅ 已修复

**问题**：
```
在写 muxer 前，把 video 的 BufferInfo.presentationTimeUs 改成"墙钟时间轴"（单调递增），
不要完全信 codec 给的 pts。
```

**根本原因**：
- 之前只改了音频 PTS 使用墙钟
- 视频 PTS 还是用 codec 给的值
- 导致音视频时间轴不统一

**修复方案**（ScreenRecorder.java:518-548）：
```java
private void resetVideoPts(MediaCodec.BufferInfo buffer) {
    // ===== 墙钟模式：视频 PTS 也使用墙钟，不依赖 codec 给的 PTS =====
    if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
        // 直接使用真实经过的时间作为 PTS
        long currentTimeNanos = android.os.SystemClock.elapsedRealtimeNanos();
        long elapsedNanos = currentTimeNanos - mRecordingStartTimeNanos;
        long wallClockPtsUs = elapsedNanos / 1000;  // 转换为微秒

        if (VERBOSE) {
            Log.d(TAG, String.format(java.util.Locale.US,
                "resetVideoPts [WALL_CLOCK]: codecPts=%d, wallClockPts=%d, diff=%d",
                buffer.presentationTimeUs, wallClockPtsUs, wallClockPtsUs - buffer.presentationTimeUs));
        }

        // 使用墙钟 PTS，忽略 codec 给的 PTS
        buffer.presentationTimeUs = wallClockPtsUs;
        return;
    }

    // ===== 原有的基于 codec PTS 的计算（兼容模式）=====
    // ...
}
```

**关键改进**：
- ✅ 视频 PTS 也使用墙钟，不依赖 codec 给的值
- ✅ 音频和视频 PTS 都基于同一个 startNs
- ✅ 时间轴统一，单调递增

---

## 核心策略总结

### 优先级
**画面时间准确 > 音频完整性**

### PTS 计算方式

**音频 PTS**（MicRecorder.java:1157-1173）：
```java
if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
    long currentTimeNanos = SystemClock.elapsedRealtimeNanos();
    long elapsedNanos = currentTimeNanos - mRecordingStartTimeNanos;
    long ptsUs = elapsedNanos / 1000;  // 转换为微秒
    return ptsUs;
}
```

**视频 PTS**（ScreenRecorder.java:521-534）：
```java
if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
    long currentTimeNanos = android.os.SystemClock.elapsedRealtimeNanos();
    long elapsedNanos = currentTimeNanos - mRecordingStartTimeNanos;
    long wallClockPtsUs = elapsedNanos / 1000;
    buffer.presentationTimeUs = wallClockPtsUs;
    return;
}
```

**关键特点**：
- ✅ 音频和视频都使用墙钟
- ✅ 共用同一个 startNs
- ✅ PTS 只跟真实时间走，不依赖采样数或 codec
- ✅ 即使音频数据丢失，时间轴也不会压缩

---

## 测试验证

### 安装新版本
```bash
adb install -r app/build/outputs/apk/v100/debug/app-v100-debug.apk
```

### 启用日志
```bash
adb shell setprop log.tag.MicRecorder VERBOSE
adb shell setprop log.tag.ScreenRecorder VERBOSE
```

### 验证关键日志

#### 1. startNs 初始化时机
```
ScreenRecorder: ★★★ WALL CLOCK PTS INITIALIZED (UNIFIED) ★★★ startTimeNanos=...
ScreenRecorder: ★★★ PASSED startNs TO AUDIO ENCODER ★★★
MicRecorder: ★★★ RECEIVED startNs FROM ScreenRecorder ★★★
```

**预期**：这些日志应该在录制开始时立即打印，在任何 PENDING buffers 之前。

#### 2. 分片处理（不再 truncate）
```
processPendingFrames: Processing queued frame (bytes=10240, ...)
processPendingFrames: Wrote 2048 bytes (offset=2048/10240)
processPendingFrames: Wrote 2048 bytes (offset=4096/10240)
processPendingFrames: Wrote 2048 bytes (offset=6144/10240)
processPendingFrames: Wrote 2048 bytes (offset=8192/10240)
processPendingFrames: Wrote 2048 bytes (offset=10240/10240)
processPendingFrames: Frame fully processed (10240 bytes)
```

**预期**：不再看到 "Frame too large! will truncate" 或 "Truncated XXX bytes"。

#### 3. 墙钟 PTS（音频和视频）
```
[WALL_CLOCK_PTS] frame=100, elapsedMs=2000.00, ptsUs=2000000
resetVideoPts [WALL_CLOCK]: codecPts=..., wallClockPts=..., diff=...
```

**预期**：音频和视频都使用墙钟 PTS。

### 验证视频时长

```bash
# 高负载录制 60 秒
# 检查视频时长
ffprobe -v error -show_entries format=duration -of default=noprint_wrappers=1:nokey=1 video.mp4

# 预期：60秒 ± 0.5秒（不会加速）
```

---

## 修改的文件清单

| 文件 | 修改内容 |
|------|---------|
| `ScreenRecorder.java:60-65` | 添加墙钟时间戳变量 |
| `ScreenRecorder.java:276-279` | 在 record() 开始时初始化 startNs |
| `ScreenRecorder.java:296-302` | 将 startNs 传递给音频编码器 |
| `ScreenRecorder.java:107-115` | 添加 getRecordingStartTimeNanos() 方法 |
| `ScreenRecorder.java:518-548` | 修改 resetVideoPts() 使用墙钟 |
| `MicRecorder.java:100-107` | 添加墙钟时间戳变量 |
| `MicRecorder.java:403-409` | 移除重复初始化，使用传递的 startNs |
| `MicRecorder.java:716-795` | 修改 processPendingFrames() 为分片处理 |
| `MicRecorder.java:1157-1173` | 修改 calculateFrameTimestamp() 使用墙钟 |
| `MicRecorder.java:1698-1707` | 添加 setRecordingStartTimeNanos() 方法 |

---

## 预期效果

### 修改前
```
音频数据丢失
  ↓
samples 变少 或 truncate
  ↓
PTS 增长变慢 或 时间轴变短
  ↓
视频加速（60秒 → 55秒）
```

### 修改后
```
音频数据丢失
  ↓
分片处理，不 truncate
  ↓
PTS 仍按真实时间增长（墙钟）
  ↓
时间轴保持连续
  ↓
视频时长准确（60秒 → 60秒）
  ↓
音频可能有缺失，但画面不加速
```

---

## 关键改进总结

✅ **不 truncate**：大帧按 buffer capacity 分片处理
✅ **startNs 统一**：在 ScreenRecorder.record() 开始时初始化
✅ **音频墙钟 PTS**：不依赖采样数
✅ **视频墙钟 PTS**：不依赖 codec 给的值
✅ **时间轴统一**：音频和视频共用同一个 startNs
✅ **画面优先**：即使音频丢失，视频时长也准确

---

## 下一步

请安装新版本 APK 并测试：
1. 验证 startNs 初始化时机（日志）
2. 验证不再 truncate（日志）
3. 验证视频时长准确（ffprobe）
4. 验证高负载场景下视频不加速

如果仍有问题，请提供完整的 logcat 日志。
