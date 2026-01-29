/*
 * Copyright (c) 2017 Yrom Wang <http://www.yrom.net>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.hht.oemscreenrecoder.yorm;

import android.annotation.TargetApi;
import android.media.AudioAttributes;
import android.media.AudioFormat;

import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaRecorder;
import android.media.projection.MediaProjection;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.SystemClock;
import android.util.Log;
import android.util.SparseLongArray;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Locale;
import java.util.Objects;
import java.util.Random;
import java.util.concurrent.atomic.AtomicBoolean;

import static android.media.MediaCodec.BUFFER_FLAG_END_OF_STREAM;
import static android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME;
import static android.media.MediaCodec.INFO_OUTPUT_FORMAT_CHANGED;
import static android.os.Build.VERSION_CODES.N;

/**
 * @author yrom
 * @version 2017/12/4
 */
class MicRecorder implements Encoder {
    private static final String TAG = "MicRecorder";
    private static final String TAG_SILENT_FILL = "MicRecorder_SilentFill";
    private static final boolean VERBOSE = false;
    private static final boolean VERBOSE_SILENT_FILL = true;  // 详细日志开关

    private final AudioEncoder mEncoder;
    private final HandlerThread mRecordThread;
    private RecordHandler mRecordHandler;
    private AudioRecord mMic; // access in mRecordThread only!
    private int mSampleRate;
    private int mChannelConfig;
    private int mFormat = AudioFormat.ENCODING_PCM_16BIT;

    private AtomicBoolean mForceStop = new AtomicBoolean(false);
    private BaseEncoder.Callback mCallback;
    private CallbackDelegate mCallbackDelegate;
    private int mChannelsSampleRate;

    // ===== 运行时静音检测 =====
    // 连续静音帧计数器（用于检测录制过程中的静音问题）
    private int mConsecutiveSilentFrames = 0;
    private static final int SILENT_FRAMES_WARNING_THRESHOLD = 10;  // 连续10帧静音则警告
    private static final int SILENT_FRAME_MAX_VALUE_THRESHOLD = 50; // 最大值<50视为静音帧
    private boolean mSilenceWarningLogged = false; // 避免重复警告刷屏

    // ===== 音频数据统计（用于诊断） =====
    private long mTotalFramesProcessed = 0;      // 总共处理的音频帧数
    private long mTotalBytesEncoded = 0;         // 总共编码的字节数
    private long mLastLogTimeMs = 0;             // 上次打印统计日志的时间
    private static final long LOG_INTERVAL_MS = 5000; // 每5秒打印一次统计信息

    public void setMediaProjection(MediaProjection mediaProjection) {
        this.mediaProjection = mediaProjection;
    }

    private int audioType;
    private MediaProjection mediaProjection;

    // ===== 智能静音填充配置 =====
    private AudioSilentFillConfig mSilentFillConfig = new AudioSilentFillConfig();  // 默认配置
    private long mRecordingStartTime = 0;  // 录制开始时间（用于混合策略）
    private int mSilentFrameSkipCount = 0;  // 静音帧跳过计数（用于降低采样率模式）
    private Random mNoiseRandom = new Random();  // 随机数生成器（用于噪声填充）
    private int mFrameCount = 0;  // 总帧数计数（用于日志）

    // ===== 墙钟时间戳（主时钟）=====
    // 核心策略：PTS 只跟真实时间走，不依赖音频采样数
    // 优先保证画面时间准确，允许音频丢失
    private long mRecordingStartTimeNanos = 0;  // 录制开始的纳秒时间戳（单调时钟）
    private boolean mUseWallClockPTS = true;    // 是否使用墙钟作为 PTS（默认启用）

    // ===== 缓冲队列机制（解决编码器 buffer 不足导致数据丢弃问题） =====
    private static class AudioFrame {
        byte[] data;
        int length;
        long timestamp;

        AudioFrame(byte[] data, int length, long timestamp) {
            this.data = data;
            this.length = length;
            this.timestamp = timestamp;
        }
    }

    private final LinkedList<AudioFrame> mPendingFramesQueue = new LinkedList<>();
    private static final int MAX_PENDING_FRAMES = 10;  // 队列上限（10帧）
    private static final int ENCODER_BUFFER_TIMEOUT_MS = 50;  // 编码器 buffer 超时时间（50ms）

    // 统计信息
    private long mTotalBufferNotAvailableCount = 0;  // buffer 不足次数
    private long mTotalFramesQueued = 0;             // 放入队列的帧数
    private long mTotalFramesDropped = 0;            // 丢弃的帧数（队列满时）
    private long mMaxQueueDepth = 0;                 // 最大队列深度
    private long mTotalDroppedBytes = 0;             // 总共丢弃的字节数

    MicRecorder(AudioEncodeConfig config) {
        mEncoder = new AudioEncoder(config);
        Log.d(TAG, "MicRecorder() called with: config = [" + config + "]");
        audioType = config.audioType;
        mSampleRate = config.sampleRate;
        mChannelsSampleRate = mSampleRate * config.channelCount;
        if (VERBOSE) Log.i(TAG, "in bitrate " + mChannelsSampleRate * 16 /* PCM_16BIT*/);
        mChannelConfig = config.channelCount == 2 ? AudioFormat.CHANNEL_IN_STEREO : AudioFormat.CHANNEL_IN_MONO;
        mRecordThread = new HandlerThread(TAG);
    }

    @Override
    public void setCallback(Callback callback) {
        this.mCallback = (BaseEncoder.Callback) callback;
    }

    public void setCallback(BaseEncoder.Callback callback) {
        this.mCallback = callback;
    }

    @Override
    public void prepare() throws IOException {
        Looper myLooper = Objects.requireNonNull(Looper.myLooper(), "Should prepare in HandlerThread");
        // run callback in caller thread
        mCallbackDelegate = new CallbackDelegate(myLooper, mCallback);
        mRecordThread.start();
        mRecordHandler = new RecordHandler(mRecordThread.getLooper());

        // ===== 重置统计变量 =====
        mTotalFramesProcessed = 0;
        mTotalBytesEncoded = 0;
        mLastLogTimeMs = System.currentTimeMillis();
        mConsecutiveSilentFrames = 0;
        mSilenceWarningLogged = false;

        // ===== 重置智能静音填充变量 =====
        mRecordingStartTime = System.currentTimeMillis();
        mSilentFrameSkipCount = 0;
        mFrameCount = 0;

        Log.d(TAG, "prepare: Audio statistics reset - ready to record with silent fill enabled: " + mSilentFillConfig.isEnabled());

        mRecordHandler.sendEmptyMessage(MSG_PREPARE);
    }

    @Override
    public void stop() {
        if (mCallbackDelegate != null) {
            // clear callback queue
            mCallbackDelegate.removeCallbacksAndMessages(null);
        }
        mForceStop.set(true);
        if (mRecordHandler != null) mRecordHandler.sendEmptyMessage(MSG_STOP);
    }

    @Override
    public void release() {
        Log.d(TAG, "release: START - ensuring AudioRecord resources are freed");

        // ===== 关键修复：确保 MSG_RELEASE 被处理后再退出线程 =====
        if (mRecordHandler != null) {
            // 使用 CountDownLatch 确保释放完成
            final java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);

            mRecordHandler.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        // 直接在这里释放资源，而不是通过 MSG_RELEASE
                        Log.d(TAG, "release: Releasing AudioRecord resources...");

                        if (mMic != null) {
                            Log.d(TAG, "release: Releasing mMic");
                            try {
                                mMic.stop();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error stopping mMic", e);
                            }
                            try {
                                mMic.release();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error releasing mMic", e);
                            }
                            mMic = null;
                        }

                        if (mAudioRecordMic != null) {
                            Log.d(TAG, "release: Releasing mAudioRecordMic");
                            try {
                                mAudioRecordMic.stop();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error stopping mAudioRecordMic", e);
                            }
                            try {
                                mAudioRecordMic.release();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error releasing mAudioRecordMic", e);
                            }
                            mAudioRecordMic = null;
                        }

                        if (mAudioRecord != null) {
                            Log.d(TAG, "release: Releasing mAudioRecord (INTERNAL - AudioPlaybackCapture)");
                            try {
                                mAudioRecord.stop();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error stopping mAudioRecord", e);
                            }
                            try {
                                mAudioRecord.release();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error releasing mAudioRecord", e);
                            }
                            mAudioRecord = null;
                        }

                        if (mEncoder != null) {
                            Log.d(TAG, "release: Releasing encoder");
                            try {
                                mEncoder.release();
                            } catch (Exception e) {
                                Log.w(TAG, "release: Error releasing encoder", e);
                            }
                        }

                        Log.d(TAG, "release: All AudioRecord resources released successfully");
                    } finally {
                        latch.countDown();
                    }
                }
            });

            // 等待释放完成（最多等待2秒）
            try {
                boolean completed = latch.await(2, java.util.concurrent.TimeUnit.SECONDS);
                if (!completed) {
                    Log.e(TAG, "release: Timeout waiting for AudioRecord release!");
                }
            } catch (InterruptedException e) {
                Log.e(TAG, "release: Interrupted while waiting for AudioRecord release", e);
            }
        }

        // 现在可以安全退出线程了
        if (mRecordThread != null) {
            mRecordThread.quitSafely();
            Log.d(TAG, "release: HandlerThread quit");
        }

        Log.d(TAG, "release: END");
    }

    void releaseOutputBuffer(int index) {
        if (VERBOSE) Log.d(TAG, "audio encoder released output buffer index=" + index);
        Message.obtain(mRecordHandler, MSG_RELEASE_OUTPUT, index, 0).sendToTarget();
    }


    ByteBuffer getOutputBuffer(int index) {
        return mEncoder.getOutputBuffer(index);
    }


    private static class CallbackDelegate extends Handler {
        private BaseEncoder.Callback mCallback;

        CallbackDelegate(Looper l, BaseEncoder.Callback callback) {
            super(l);
            this.mCallback = callback;
        }


        void onError(Encoder encoder, Exception exception) {
            Message.obtain(this, () -> {
                if (mCallback != null) {
                    mCallback.onError(encoder, exception);
                }
            }).sendToTarget();
        }

        void onOutputFormatChanged(BaseEncoder encoder, MediaFormat format) {
            Message.obtain(this, () -> {
                if (mCallback != null) {
                    mCallback.onOutputFormatChanged(encoder, format);
                }
            }).sendToTarget();
        }

        void onOutputBufferAvailable(BaseEncoder encoder, int index, MediaCodec.BufferInfo info) {
            Message.obtain(this, () -> {
                if (mCallback != null) {
                    mCallback.onOutputBufferAvailable(encoder, index, info);
                }
            }).sendToTarget();
        }

        void onInternalAudioNotAvailable(int audioType) {
            Message.obtain(this, () -> {
                if (mCallback != null) {
                    mCallback.onInternalAudioNotAvailable(audioType);
                }
            }).sendToTarget();
        }

    }

    private static final int MSG_PREPARE = 0;
    private static final int MSG_FEED_INPUT = 1;
    private static final int MSG_DRAIN_OUTPUT = 2;
    private static final int MSG_RELEASE_OUTPUT = 3;
    private static final int MSG_STOP = 4;
    private static final int MSG_RELEASE = 5;
    private AudioRecord mAudioRecord;
    private AudioRecord mAudioRecordMic;

    private class RecordHandler extends Handler {

        private LinkedList<MediaCodec.BufferInfo> mCachedInfos = new LinkedList<>();
        private LinkedList<Integer> mMuxingOutputBufferIndices = new LinkedList<>();
        private int mPollRate = 2048_000 / mSampleRate; // poll per 2048 samples

        RecordHandler(Looper l) {
            super(l);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_PREPARE:
                    AudioRecord r = createAudioRecord(mSampleRate, mChannelConfig, mFormat);
                    if (r == null) {
                        Log.e(TAG, "create audio record failure");
                        mCallbackDelegate.onError(MicRecorder.this, new IllegalArgumentException("Failed to create AudioRecord"));
                        break;
                    } else {
                        boolean startSuccess = false;
                        if (mAudioRecord != null && mAudioRecordMic != null) {
                            // MIC + INTERNAL 混合模式
                            Log.d(TAG, "MSG_PREPARE: MIC+INTERNAL mode - starting both AudioRecords");
                            startSuccess = startRecordingWithVerify(mAudioRecordMic, "MIC") &&
                                           startRecordingWithVerify(mAudioRecord, "INTERNAL");

                            // 如果一个失败，降级到单源模式
                            if (!startSuccess) {
                                Log.w(TAG, "MSG_PREPARE: Not all sources started, attempting degraded mode");
                                if (mAudioRecordMic != null && mAudioRecordMic.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                    startSuccess = true;
                                    mAudioRecord = null;
                                    Log.w(TAG, "MSG_PREPARE: Degraded to MIC-only mode");
                                } else if (mAudioRecord != null && mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_RECORDING) {
                                    startSuccess = true;
                                    mAudioRecordMic = null;
                                    Log.w(TAG, "MSG_PREPARE: Degraded to INTERNAL-only mode");
                                }
                            }
                        } else {
                            // 单源模式
                            Log.d(TAG, "MSG_PREPARE: SINGLE mode - mAudioRecord=" + mAudioRecord +
                                    ", mAudioRecordMic=" + mAudioRecordMic + ", r=" + r);
                            startSuccess = startRecordingWithVerify(r, "SINGLE");
                        }

                        if (!startSuccess) {
                            Log.e(TAG, "MSG_PREPARE: Failed to start any audio recording!");
                            mCallbackDelegate.onError(MicRecorder.this,
                                    new IllegalStateException("Audio recording failed to start"));
                            break;
                        }

                        mMic = r;
                        Log.d(TAG, "MSG_PREPARE: mMic assigned = " + mMic);

                        // ===== startNs 已在 ScreenRecorder.record() 中初始化并传递过来 =====
                        // 不再在这里重复初始化，避免 startNs 定得太晚
                        if (mRecordingStartTimeNanos == 0) {
                            Log.w(TAG, "★★★ WARNING ★★★ startNs not set from ScreenRecorder, using fallback");
                            mRecordingStartTimeNanos = SystemClock.elapsedRealtimeNanos();
                        }
                    }
                    try {
                        mEncoder.prepare();
                    } catch (Exception e) {
                        Log.e(TAG, "MSG_PREPARE: Encoder prepare failed", e);
                        mCallbackDelegate.onError(MicRecorder.this, e);
                        break;
                    }
                case MSG_FEED_INPUT:
                    if (!mForceStop.get()) {
                        int index = pollInput();
                        if (VERBOSE)
                            Log.d(TAG, "audio encoder returned input buffer index=" + index);
                        if (index >= 0) {
                            feedAudioEncoder(index);
                        } else {
                            // ★★★ 关键修复：即使无法feed input，也要drain output ★★★
                            Log.w(TAG, "MSG_FEED_INPUT: No input buffer available, but will drain output anyway");
                        }
                        // ★★★ 无论feed是否成功，都要drain output ★★★
                        if (!mForceStop.get()) {
                            sendEmptyMessage(MSG_DRAIN_OUTPUT);
                        }
                        // ★★★ 如果无法feed，延迟重试 ★★★
                        if (index < 0 && !mForceStop.get()) {
                            sendEmptyMessageDelayed(MSG_FEED_INPUT, mPollRate);
                        }
                    }
                    break;
                case MSG_DRAIN_OUTPUT:
                    Log.d(TAG, "handleMessage: ★★★ MSG_DRAIN_OUTPUT received ★★★");
                    offerOutput();
                    pollInputIfNeed();
                    Log.d(TAG, "handleMessage: MSG_DRAIN_OUTPUT completed");
                    break;
                case MSG_RELEASE_OUTPUT:
                    mEncoder.releaseOutputBuffer(msg.arg1);
                    mMuxingOutputBufferIndices.poll(); // Nobody care what it exactly is.
                    if (VERBOSE) Log.d(TAG, "audio encoder released output buffer index="
                            + msg.arg1 + ", remaining=" + mMuxingOutputBufferIndices.size());
                    pollInputIfNeed();
                    break;
                case MSG_STOP:
                    if (mMic != null) {
                        mMic.stop();
                    }
                    if (mAudioRecordMic != null) {
                        mAudioRecordMic.stop();
                    }

                    if (mAudioRecord != null) {
                        mAudioRecord.stop();
                    }
                    mEncoder.stop();
                    break;
                case MSG_RELEASE:
                    if (mMic != null) {
                        mMic.release();
                        mMic = null;
                    }
                    if (mAudioRecordMic != null) {
                        mAudioRecordMic.release();
                        mAudioRecordMic = null;
                    }
                    if (mAudioRecord != null) {
                        mAudioRecord.release();
                        mAudioRecord = null;
                    }
                    mEncoder.release();
                    break;
            }
        }

        private void offerOutput() {
            Log.d(TAG, "offerOutput: ★★★ CALLED ★★★ mForceStop=" + mForceStop.get());
            int loopCount = 0;
            while (!mForceStop.get()) {
                loopCount++;
                MediaCodec.BufferInfo info = mCachedInfos.poll();
                if (info == null) {
                    info = new MediaCodec.BufferInfo();
                }
                int index = mEncoder.getEncoder().dequeueOutputBuffer(info, 1);
                Log.d(TAG, "offerOutput: [Loop " + loopCount + "] dequeueOutputBuffer returned index=" + index +
                        " (-1=TRY_AGAIN, -2=OUTPUT_FORMAT_CHANGED, -3=OUTPUT_BUFFERS_CHANGED)");
                if (index == INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat format = mEncoder.getEncoder().getOutputFormat();
                    Log.i(TAG, "offerOutput: ★★★ INFO_OUTPUT_FORMAT_CHANGED ★★★ format=" + format);
                    mCallbackDelegate.onOutputFormatChanged(mEncoder, format);
                }
                if (index < 0) {
                    info.set(0, 0, 0, 0);
                    mCachedInfos.offer(info);
                    Log.d(TAG, "offerOutput: [Loop " + loopCount + "] No more buffers, breaking. Total loops=" + loopCount);
                    break;
                }
                Log.i(TAG, "offerOutput: ★★★ GOT AUDIO OUTPUT BUFFER ★★★ index=" + index + ", size=" + info.size +
                        ", flags=" + info.flags + ", pts=" + info.presentationTimeUs);
                mMuxingOutputBufferIndices.offer(index);
                Log.d(TAG, "offerOutput: Calling onOutputBufferAvailable callback...");
                mCallbackDelegate.onOutputBufferAvailable(mEncoder, index, info);
                Log.d(TAG, "offerOutput: onOutputBufferAvailable callback returned");
            }
            Log.d(TAG, "offerOutput: ★★★ EXIT ★★★ totalLoops=" + loopCount);
        }

        private int pollInput() {
            return mEncoder.getEncoder().dequeueInputBuffer(0);
        }

        private void pollInputIfNeed() {
            Log.d(TAG, "pollInputIfNeed: mMuxingOutputBufferIndices.size()=" + mMuxingOutputBufferIndices.size() +
                    ", mForceStop=" + mForceStop.get());
            if (mMuxingOutputBufferIndices.size() <= 1 && !mForceStop.get()) {
                // need fresh data, right now!
                removeMessages(MSG_FEED_INPUT);
                sendEmptyMessageDelayed(MSG_FEED_INPUT, 0);
                Log.d(TAG, "pollInputIfNeed: Scheduled MSG_FEED_INPUT");
            }
        }
    }

    /**
     * NOTE: Should waiting all output buffer disappear queue input buffer
     */

    private short[] scaleValues(short[] buff, int len, float scale) {
        for (int i = 0; i < len; i++) {
            int oldValue = buff[i];
            int newValue = (int) (buff[i] * scale);
            if (newValue > Short.MAX_VALUE) {
                newValue = Short.MAX_VALUE;
            } else if (newValue < Short.MIN_VALUE) {
                newValue = Short.MIN_VALUE;
            }
            buff[i] = (short) (newValue);
        }
        return buff;
    }
    /**
     * 添加和转换缓冲区 - 参考 SystemUI 的实现
     * 将两个 short[] 音频缓冲区混合并转换为 byte[]
     */
    private byte[] addAndConvertBuffers(short[] src1, int size1, short[] src2, int size2) {
        int sizeShorts = Math.min(size1, size2);
        if (sizeShorts <= 0) return new byte[0];

        if (buffer == null || buffer.length < sizeShorts * 2) {
            buffer = new byte[sizeShorts * 2];
        }

        for (int i = 0; i < sizeShorts; i++) {
            // 混合两个音频源，使用钳位防止溢出
            int sum = (int) src1[i] + (int) src2[i];
            if (sum > Short.MAX_VALUE) sum = Short.MAX_VALUE;
            if (sum < Short.MIN_VALUE) sum = Short.MIN_VALUE;

            int byteIndex = i * 2;
            buffer[byteIndex] = (byte) (sum & 0xff);
            buffer[byteIndex + 1] = (byte) ((sum >> 8) & 0xff);
        }
        return buffer;
    }

    /**
     * 将 short[] 转换为 byte[]（用于单音频源）
     */
    private byte[] convertToBytes(short[] src, int size) {
        if (size <= 0) return new byte[0];

        if (buffer == null || buffer.length < size * 2) {
            buffer = new byte[size * 2];
        }

        for (int i = 0; i < size; i++) {
            int byteIndex = i * 2;
            buffer[byteIndex] = (byte) (src[i] & 0xff);
            buffer[byteIndex + 1] = (byte) ((src[i] >> 8) & 0xff);
        }
        return buffer;
    }

    /**
     * 单音频源处理辅助方法
     * @param index 编码器缓冲区索引
     * @param record AudioRecord 实例
     * @param shortBuffer short[] 缓冲区
     * @param isMic 是否是麦克风源（用于音量缩放）
     */
    private void feedSingleSourceAudio(int index, AudioRecord record, short[] shortBuffer, boolean isMic) {
        final boolean eos = record.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED;
        if (eos) {
            Log.w(TAG, "feedSingleSourceAudio: EOS detected");
            final ByteBuffer frame = mEncoder.getInputBuffer(index);
            int offset = frame.position();
            long pstTs = calculateFrameTimestamp(0);
            mEncoder.queueInputBuffer(index, offset, 0, pstTs, BUFFER_FLAG_END_OF_STREAM);
            return;
        }

        int readShorts = record.read(shortBuffer, 0, shortBuffer.length);
        Log.d(TAG, "feedSingleSourceAudio: readShorts=" + readShorts + ", isMic=" + isMic);

        if (readShorts > 0) {
            // 如果是麦克风，应用音量缩放
            if (isMic) {
                scaleValues(shortBuffer, readShorts, MIC_VOLUME_SCALE);
            }

            // 将 short[] 转换为 byte[]
            int readBytes = readShorts * 2;
            byte[] tempBuffer = new byte[readBytes];
            for (int i = 0; i < readShorts; i++) {
                short value = shortBuffer[i];
                tempBuffer[i * 2] = (byte) (value & 0xff);
                tempBuffer[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
            }
            encode(index, tempBuffer, readBytes);
        } else if (readShorts < 0) {
            Log.e(TAG, "feedSingleSourceAudio: READ ERROR code=" + readShorts +
                    " (-1=ERROR, -2=BAD_VALUE, -3=INVALID_OPERATION, -6=DEAD_OBJECT)");
        } else {
            Log.w(TAG, "feedSingleSourceAudio: READ 0 shorts - no data!");
        }
    }

    short[] bufferInternal = null;
    short[] bufferMic = null;
    byte[] buffer = null;
    private static final int TIMEOUT = 500;

    private void encode(int bufferIndex, byte[] buffer, int readBytes) {
        int offset = 0;
        while (readBytes > 0) {
            int totalBytesRead = 0;

            ByteBuffer buff = mEncoder.getInputBuffer(bufferIndex);

            int bufferSize = buff.capacity();
            int bytesToRead = readBytes > bufferSize ? bufferSize : readBytes;
            totalBytesRead += bytesToRead;

            readBytes -= bytesToRead;
            buff.put(buffer, offset, bytesToRead);
            offset += bytesToRead;

            long pstTs = calculateFrameTimestamp(bytesToRead << 3);
     /*       mEncoder.queueInputBuffer(bufferIndex, 0, bytesToRead, mPresentationTime, 0);


            long pstTs = calculateFrameTimestamp(bytesToRead << 3);*/
            int flags = BUFFER_FLAG_KEY_FRAME;

            // ===== 音频编码统计日志 =====
            mTotalBytesEncoded += bytesToRead;
            mTotalFramesProcessed++;

            mEncoder.queueInputBuffer(bufferIndex, 0, bytesToRead, pstTs, flags);
            Log.d(TAG, "encode: Queued audio frame #" + mTotalFramesProcessed +
                    ", bytesToRead=" + bytesToRead + ", pstTs=" + pstTs + ", flags=" + flags);

            if (readBytes>0){
                // ===== 使用更长的超时时间，给编码器更多处理时间 =====
                bufferIndex = mEncoder.getEncoder().dequeueInputBuffer(ENCODER_BUFFER_TIMEOUT_MS);
                Log.d(TAG, "encode: Requested next buffer, index=" + bufferIndex + ", remainingBytes=" + readBytes);

                if (bufferIndex<0){
                    // ===== buffer 不足，将剩余数据放入队列而不是丢弃 =====
                    mTotalBufferNotAvailableCount++;
                    Log.w(TAG, String.format(Locale.US,
                        "encode: ★★★ BUFFER NOT AVAILABLE ★★★ Queueing %d bytes (count=%d, queueSize=%d)",
                        readBytes, mTotalBufferNotAvailableCount, mPendingFramesQueue.size()));

                    // 将剩余数据放入队列
                    byte[] remainingData = new byte[readBytes];
                    System.arraycopy(buffer, offset, remainingData, 0, readBytes);
                    AudioFrame frame = new AudioFrame(remainingData, readBytes, System.currentTimeMillis());

                    synchronized (mPendingFramesQueue) {
                        if (mPendingFramesQueue.size() < MAX_PENDING_FRAMES) {
                            mPendingFramesQueue.offer(frame);
                            mTotalFramesQueued++;
                            mMaxQueueDepth = Math.max(mMaxQueueDepth, mPendingFramesQueue.size());
                            Log.i(TAG, String.format(Locale.US,
                                "encode: Frame queued successfully (queueSize=%d, maxDepth=%d)",
                                mPendingFramesQueue.size(), mMaxQueueDepth));
                        } else {
                            // 队列已满，记录丢弃
                            mTotalFramesDropped++;
                            mTotalDroppedBytes += readBytes;
                            Log.e(TAG, String.format(Locale.US,
                                "encode: ★★★ QUEUE FULL ★★★ Dropping frame! (dropped=%d, droppedBytes=%d)",
                                mTotalFramesDropped, mTotalDroppedBytes));
                        }
                    }
                    break;
                }
            }
  /*          mTotalBytes += totalBytesRead;
            mPresentationTime = 1000000L * (mTotalBytes / 2) / mConfig.sampleRate;

            writeOutput();*/
        }

        // ===== 定期打印统计信息 =====
        printAudioStatsIfNeeded();
    }

    private static final float MIC_VOLUME_SCALE = 1.4f;

    /**
     * 处理队列中的积压音频帧
     * 在处理新数据前，优先处理队列中的积压数据
     * 关键修复：大帧分片处理，不截断
     */
    private void processPendingFrames() {
        synchronized (mPendingFramesQueue) {
            while (!mPendingFramesQueue.isEmpty()) {
                // 从队列取出一帧（但不移除，先处理）
                AudioFrame frame = mPendingFramesQueue.peek();
                if (frame == null) break;

                Log.i(TAG, String.format(Locale.US,
                    "processPendingFrames: Processing queued frame (bytes=%d, age=%dms, remainingQueue=%d)",
                    frame.length, System.currentTimeMillis() - frame.timestamp, mPendingFramesQueue.size()));

                // ===== 分片处理大帧，不截断 =====
                int offset = 0;
                while (offset < frame.length) {
                    // 尝试获取 buffer
                    int bufferIndex = mEncoder.getEncoder().dequeueInputBuffer(ENCODER_BUFFER_TIMEOUT_MS);
                    if (bufferIndex < 0) {
                        // 没有 buffer，停止处理
                        Log.d(TAG, String.format(Locale.US,
                            "processPendingFrames: No buffer available, processed %d/%d bytes, queue size=%d",
                            offset, frame.length, mPendingFramesQueue.size()));

                        // 如果已经处理了部分数据，更新 frame
                        if (offset > 0) {
                            // 创建新 frame 包含剩余数据
                            byte[] remainingData = new byte[frame.length - offset];
                            System.arraycopy(frame.data, offset, remainingData, 0, remainingData.length);
                            AudioFrame newFrame = new AudioFrame(remainingData, remainingData.length, frame.timestamp);

                            // 移除旧 frame，添加新 frame
                            mPendingFramesQueue.poll();
                            mPendingFramesQueue.addFirst(newFrame);

                            Log.i(TAG, String.format(Locale.US,
                                "processPendingFrames: Partial processed, remaining %d bytes re-queued",
                                remainingData.length));
                        }
                        return;
                    }

                    // 获取 buffer 容量
                    ByteBuffer buff = mEncoder.getInputBuffer(bufferIndex);
                    int bufferCapacity = buff.capacity();

                    // 计算本次写入的字节数
                    int bytesToWrite = Math.min(frame.length - offset, bufferCapacity);

                    // 写入数据
                    buff.put(frame.data, offset, bytesToWrite);
                    long pstTs = calculateFrameTimestamp(bytesToWrite << 3);
                    mEncoder.queueInputBuffer(bufferIndex, 0, bytesToWrite, pstTs, BUFFER_FLAG_KEY_FRAME);

                    mTotalBytesEncoded += bytesToWrite;
                    mTotalFramesProcessed++;
                    offset += bytesToWrite;

                    Log.d(TAG, String.format(Locale.US,
                        "processPendingFrames: Wrote %d bytes (offset=%d/%d)",
                        bytesToWrite, offset, frame.length));
                }

                // 整个 frame 处理完毕，从队列移除
                mPendingFramesQueue.poll();
                Log.i(TAG, String.format(Locale.US,
                    "processPendingFrames: Frame fully processed (%d bytes), remaining queue=%d",
                    frame.length, mPendingFramesQueue.size()));
            }

            // 队列处理完毕
            if (mPendingFramesQueue.isEmpty() && mTotalFramesQueued > 0) {
                Log.i(TAG, String.format(Locale.US,
                    "processPendingFrames: ★★★ QUEUE CLEARED ★★★ (totalQueued=%d, totalDropped=%d)",
                    mTotalFramesQueued, mTotalFramesDropped));
            }
        }
    }

    private void feedAudioEncoder(int index) {
        if (index < 0 || mForceStop.get()) return;

        // ===== 优先处理队列中的积压数据 =====
        processPendingFrames();
        if (mAudioRecordMic != null && mAudioRecord != null) {
            // MIC + INTERNAL 混合模式
            Log.d(TAG, "════════════════════════════════════════════════════════");
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: START - index=" + index);
            int readShortsInternal = 0;
            int readShortsMic = 0;

            readShortsInternal = mAudioRecord.read(bufferInternal, 0, bufferInternal.length);
            readShortsMic = mAudioRecordMic.read(bufferMic, 0, bufferMic.length);
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: Read results - internal=" + readShortsInternal + ", mic=" + readShortsMic);

            // ★★★ 关键修复：参考 SystemUI 的错误处理策略 ★★★
            // 如果两个都失败，结束录制
            if (readShortsInternal < 0 && readShortsMic < 0) {
                Log.e(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: ★★★ BOTH SOURCES FAILED ★★★");
                Log.e(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: internal=" + readShortsInternal + ", mic=" + readShortsMic);
                final ByteBuffer frame = mEncoder.getInputBuffer(index);
                int offset = frame.position();
                long pstTs = calculateFrameTimestamp(0);
                mEncoder.queueInputBuffer(index, offset, 0, pstTs, BUFFER_FLAG_END_OF_STREAM);
                return;
            }

            // 如果一个失败，用静音填充并继续（参考 SystemUI 的做法）
            if (readShortsInternal < 0) {
                Log.w(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: INTERNAL FAILED (" + readShortsInternal +
                        "), filling with SILENCE");
                readShortsInternal = readShortsMic;
                java.util.Arrays.fill(bufferInternal, (short) 0);
            }
            if (readShortsMic < 0) {
                Log.w(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: MIC FAILED (" + readShortsMic +
                        "), filling with SILENCE");
                readShortsMic = readShortsInternal;
                java.util.Arrays.fill(bufferMic, (short) 0);
            }

            // ===== 诊断日志：分析每个源的数据 =====
            int internalMaxValue = 0;
            int micMaxValue = 0;
            boolean internalAllZeros = true;
            boolean micAllZeros = true;

            for (int i = 0; i < Math.min(readShortsInternal, bufferInternal.length); i++) {
                int absVal = Math.abs(bufferInternal[i]);
                if (bufferInternal[i] != 0) internalAllZeros = false;
                internalMaxValue = Math.max(internalMaxValue, absVal);
            }

            for (int i = 0; i < Math.min(readShortsMic, bufferMic.length); i++) {
                int absVal = Math.abs(bufferMic[i]);
                if (bufferMic[i] != 0) micAllZeros = false;
                micMaxValue = Math.max(micMaxValue, absVal);
            }

            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: ┌─ INTERNAL DATA ─────────────");
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: │  allZeros=" + internalAllZeros + ", maxValue=" + internalMaxValue);
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: └─────────────────────────────");
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: ┌─ MIC DATA ──────────────────");
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: │  allZeros=" + micAllZeros + ", maxValue=" + micMaxValue);
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: └─────────────────────────────");

            // ★★★ 根据开关状态选择策略 ★★★
            byte[] finalBuffer;
            int readBytes;

            if (mSilentFillConfig.isEnabled()) {
                // ===== 启用智能静音填充 =====
                mFrameCount++;
                boolean internalIsSilent = isSilentFrame(bufferInternal, readShortsInternal);
                boolean micIsSilent = isSilentFrame(bufferMic, readShortsMic);

                // 详细日志（每10帧打印一次）
                if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                    Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                        "[FRAME_%d] INTERNAL: maxValue=%d, isSilent=%b | MIC: maxValue=%d, isSilent=%b | consecutiveSilent=%d, mode=%s",
                        mFrameCount, internalMaxValue, internalIsSilent, micMaxValue, micIsSilent,
                        mConsecutiveSilentFrames, mSilentFillConfig.getMode()));
                }

                boolean bothSilent = internalIsSilent && micIsSilent;

                if (bothSilent) {
                    // 两个源都静音，应用智能填充策略
                    finalBuffer = applySilentFillStrategy(bufferInternal, Math.min(readShortsInternal, readShortsMic));

                    if (finalBuffer == null) {
                        // 跳帧模式返回null，跳过这一帧
                        if (VERBOSE_SILENT_FILL) {
                            Log.d(TAG_SILENT_FILL, "[SKIP_FRAME] Skipped silent frame in REDUCED_SAMPLE_RATE mode");
                        }
                        return;
                    }

                    readBytes = finalBuffer.length;

                    if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                        Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                            "[SILENT_FILL] Applied mode=%s, consecutiveSilent=%d, bytes=%d",
                            mSilentFillConfig.getMode(), mConsecutiveSilentFrames, readBytes));
                    }
                } else {
                    // 至少有一个源有声音，正常混合
                    if (mConsecutiveSilentFrames > 0) {
                        Log.i(TAG_SILENT_FILL, String.format(Locale.US,
                            "★★★ AUDIO RESUMED ★★★ After %d silent frames", mConsecutiveSilentFrames));
                    }

                    int minShorts = Math.min(readShortsInternal, readShortsMic);
                    scaleValues(bufferMic, minShorts, MIC_VOLUME_SCALE);
                    finalBuffer = addAndConvertBuffers(bufferInternal, minShorts, bufferMic, minShorts);
                    readBytes = minShorts * 2;

                    int combinedMaxValue = Math.max(internalMaxValue, (int)(micMaxValue * MIC_VOLUME_SCALE));
                    checkAndWarnSilentFrame(combinedMaxValue, "MIC_AND_INTERNAL");
                }
            } else {
                // ===== 未启用智能填充，正常处理（包括feed全0静音数据） =====
                Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: Silent fill DISABLED, normal processing");

                int combinedMaxValue = Math.max(internalMaxValue, (int)(micMaxValue * MIC_VOLUME_SCALE));
                checkAndWarnSilentFrame(combinedMaxValue, "MIC_AND_INTERNAL");

                int minShorts = Math.min(readShortsInternal, readShortsMic);
                scaleValues(bufferMic, minShorts, MIC_VOLUME_SCALE);
                finalBuffer = addAndConvertBuffers(bufferInternal, minShorts, bufferMic, minShorts);
                readBytes = minShorts * 2;
            }

            // Feed到编码器
            final boolean eos = (mAudioRecord.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED &&
                    mAudioRecordMic.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED);
            if (!eos && readBytes > 0) {
                encode(index, finalBuffer, readBytes);
            } else {
                return;
            }
            Log.d(TAG, "feedAudioEncoder [MIC_AND_INTERNAL]: END");
            Log.d(TAG, "════════════════════════════════════════════════════════");
        } else if (audioType == 2 && (mAudioRecordMic != null || mAudioRecord != null)) {
            // 混合模式但只有一个源可用 - 降级处理
            Log.w(TAG, "feedAudioEncoder [MIC_AND_INTERNAL_DEGRADED]: Only one source available");
            AudioRecord availableRecord = mAudioRecordMic != null ? mAudioRecordMic : mAudioRecord;
            short[] availableBuffer = mAudioRecordMic != null ? bufferMic : bufferInternal;
            feedSingleSourceAudio(index, availableRecord, availableBuffer, mAudioRecordMic != null);
        } else {
            Log.d(TAG, "feedAudioEncoder [SINGLE]: Entering single-source mode, audioType=" + audioType);
            final AudioRecord r = Objects.requireNonNull(mMic, "maybe release");
            Log.d(TAG, "feedAudioEncoder [SINGLE]: AudioRecord=" + r +
                    ", recordingState=" + r.getRecordingState() + " (1=STOPPED, 3=RECORDING)");
            final boolean eos = r.getRecordingState() == AudioRecord.RECORDSTATE_STOPPED;
            Log.d(TAG, "feedAudioEncoder [SINGLE]: eos=" + eos);

            // 关键修复：对于INTERNAL模式(audioType=1)，使用与MIC_AND_INTERNAL相同的read(short[])方式
            // 因为某些设备对AudioPlaybackCaptureConfiguration的read(ByteBuffer)支持有问题
            if (audioType == 1 && bufferInternal != null) {
                Log.d(TAG, "feedAudioEncoder [INTERNAL]: Using read(short[]) method for better compatibility");
                if (!eos) {
                    int readShorts = r.read(bufferInternal, 0, bufferInternal.length);
                    Log.d(TAG, "feedAudioEncoder [INTERNAL]: readShorts=" + readShorts);
                    if (readShorts > 0) {
                        // 诊断日志：检查数据是否为静音
                        boolean allZeros = true;
                        int maxValue = 0;
                        for (int i = 0; i < readShorts; i++) {
                            if (bufferInternal[i] != 0) allZeros = false;
                            maxValue = Math.max(maxValue, Math.abs(bufferInternal[i]));
                        }
                        Log.d(TAG, "feedAudioEncoder [INTERNAL]: AUDIO_DATA_CHECK allZeros=" + allZeros
                                + " maxValue=" + maxValue);

                        // ★★★ 根据开关状态选择策略 ★★★
                        byte[] finalBuffer;
                        int readBytes;

                        if (mSilentFillConfig.isEnabled()) {
                            // ===== 启用智能静音填充 =====
                            mFrameCount++;
                            boolean isSilent = isSilentFrame(bufferInternal, readShorts);

                            // 详细日志（每10帧打印一次）
                            if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                                Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                                    "[FRAME_%d] INTERNAL: maxValue=%d, isSilent=%b, consecutiveSilent=%d, mode=%s",
                                    mFrameCount, maxValue, isSilent, mConsecutiveSilentFrames, mSilentFillConfig.getMode()));
                            }

                            if (isSilent) {
                                // 静音帧，应用智能填充策略
                                finalBuffer = applySilentFillStrategy(bufferInternal, readShorts);

                                if (finalBuffer == null) {
                                    // 跳帧模式返回null，跳过这一帧
                                    if (VERBOSE_SILENT_FILL) {
                                        Log.d(TAG_SILENT_FILL, "[SKIP_FRAME] Skipped silent frame in REDUCED_SAMPLE_RATE mode");
                                    }
                                    return;
                                }

                                readBytes = finalBuffer.length;

                                if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                                    Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                                        "[SILENT_FILL] Applied mode=%s, consecutiveSilent=%d, bytes=%d",
                                        mSilentFillConfig.getMode(), mConsecutiveSilentFrames, readBytes));
                                }
                            } else {
                                // 有声音，正常编码
                                if (mConsecutiveSilentFrames > 0) {
                                    Log.i(TAG_SILENT_FILL, String.format(Locale.US,
                                        "★★★ AUDIO RESUMED ★★★ After %d silent frames", mConsecutiveSilentFrames));
                                }

                                checkAndWarnSilentFrame(maxValue, "INTERNAL");

                                // 将short[]转换为byte[]
                                readBytes = readShorts * 2;
                                finalBuffer = new byte[readBytes];
                                for (int i = 0; i < readShorts; i++) {
                                    short value = bufferInternal[i];
                                    finalBuffer[i * 2] = (byte) (value & 0xff);
                                    finalBuffer[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                                }
                            }
                        } else {
                            // ===== 未启用智能填充，正常处理（包括feed全0静音数据） =====
                            Log.d(TAG, "feedAudioEncoder [INTERNAL]: Silent fill DISABLED, normal processing");
                            checkAndWarnSilentFrame(maxValue, "INTERNAL");

                            // 将short[]转换为byte[]
                            readBytes = readShorts * 2;
                            finalBuffer = new byte[readBytes];
                            for (int i = 0; i < readShorts; i++) {
                                short value = bufferInternal[i];
                                finalBuffer[i * 2] = (byte) (value & 0xff);
                                finalBuffer[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                            }
                        }

                        // Feed到编码器
                        encode(index, finalBuffer, readBytes);
                    } else if (readShorts < 0) {
                        Log.e(TAG, "feedAudioEncoder [INTERNAL]: READ ERROR code=" + readShorts);
                    } else {
                        Log.w(TAG, "feedAudioEncoder [INTERNAL]: READ 0 shorts - no data!");
                    }
                } else {
                    Log.w(TAG, "feedAudioEncoder [INTERNAL]: EOS detected");
                    final ByteBuffer frame = mEncoder.getInputBuffer(index);
                    int offset = frame.position();
                    long pstTs = calculateFrameTimestamp(0);
                    mEncoder.queueInputBuffer(index, offset, 0, pstTs, BUFFER_FLAG_END_OF_STREAM);
                }
            } else if (audioType == 0 && bufferMic != null) {
                // ★★★ 关键修复：MIC单独模式也使用read(short[])方式，与MIC_AND_INTERNAL保持一致 ★★★
                Log.d(TAG, "feedAudioEncoder [MIC]: Using read(short[]) method for better compatibility");
                if (!eos) {
                    int readShorts = r.read(bufferMic, 0, bufferMic.length);
                    Log.d(TAG, "feedAudioEncoder [MIC]: readShorts=" + readShorts);
                    if (readShorts > 0) {
                        // 诊断日志：检查数据是否为静音
                        boolean allZeros = true;
                        int maxValue = 0;
                        for (int i = 0; i < readShorts; i++) {
                            if (bufferMic[i] != 0) allZeros = false;
                            maxValue = Math.max(maxValue, Math.abs(bufferMic[i]));
                        }
                        Log.d(TAG, "feedAudioEncoder [MIC]: AUDIO_DATA_CHECK allZeros=" + allZeros
                                + " maxValue=" + maxValue);

                        // ★★★ 根据开关状态选择策略 ★★★
                        byte[] finalBuffer;
                        int readBytes;

                        if (mSilentFillConfig.isEnabled()) {
                            // ===== 启用智能静音填充 =====
                            mFrameCount++;
                            boolean isSilent = isSilentFrame(bufferMic, readShorts);

                            // 详细日志（每10帧打印一次）
                            if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                                Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                                    "[FRAME_%d] MIC: maxValue=%d, isSilent=%b, consecutiveSilent=%d, mode=%s",
                                    mFrameCount, maxValue, isSilent, mConsecutiveSilentFrames, mSilentFillConfig.getMode()));
                            }

                            if (isSilent) {
                                // 静音帧，应用智能填充策略
                                finalBuffer = applySilentFillStrategy(bufferMic, readShorts);

                                if (finalBuffer == null) {
                                    // 跳帧模式返回null，跳过这一帧
                                    if (VERBOSE_SILENT_FILL) {
                                        Log.d(TAG_SILENT_FILL, "[SKIP_FRAME] Skipped silent frame in REDUCED_SAMPLE_RATE mode");
                                    }
                                    return;
                                }

                                readBytes = finalBuffer.length;

                                if (VERBOSE_SILENT_FILL && mFrameCount % 10 == 0) {
                                    Log.d(TAG_SILENT_FILL, String.format(Locale.US,
                                        "[SILENT_FILL] Applied mode=%s, consecutiveSilent=%d, bytes=%d",
                                        mSilentFillConfig.getMode(), mConsecutiveSilentFrames, readBytes));
                                }
                            } else {
                                // 有声音，正常编码
                                if (mConsecutiveSilentFrames > 0) {
                                    Log.i(TAG_SILENT_FILL, String.format(Locale.US,
                                        "★★★ AUDIO RESUMED ★★★ After %d silent frames", mConsecutiveSilentFrames));
                                }

                                checkAndWarnSilentFrame(maxValue, "MIC");

                                // 将short[]转换为byte[]
                                readBytes = readShorts * 2;
                                finalBuffer = new byte[readBytes];
                                for (int i = 0; i < readShorts; i++) {
                                    short value = bufferMic[i];
                                    finalBuffer[i * 2] = (byte) (value & 0xff);
                                    finalBuffer[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                                }
                            }
                        } else {
                            // ===== 未启用智能填充，正常处理（包括feed全0静音数据） =====
                            Log.d(TAG, "feedAudioEncoder [MIC]: Silent fill DISABLED, normal processing");
                            checkAndWarnSilentFrame(maxValue, "MIC");

                            // 将short[]转换为byte[]
                            readBytes = readShorts * 2;
                            finalBuffer = new byte[readBytes];
                            for (int i = 0; i < readShorts; i++) {
                                short value = bufferMic[i];
                                finalBuffer[i * 2] = (byte) (value & 0xff);
                                finalBuffer[i * 2 + 1] = (byte) ((value >> 8) & 0xff);
                            }
                        }

                        // Feed到编码器
                        encode(index, finalBuffer, readBytes);
                    } else if (readShorts < 0) {
                        Log.e(TAG, "feedAudioEncoder [MIC]: READ ERROR code=" + readShorts +
                                " (-1=ERROR, -2=BAD_VALUE, -3=INVALID_OPERATION, -6=DEAD_OBJECT)");
                    } else {
                        Log.w(TAG, "feedAudioEncoder [MIC]: READ 0 shorts - no data available!");
                    }
                } else {
                    Log.w(TAG, "feedAudioEncoder [MIC]: EOS detected, AudioRecord is STOPPED");
                    final ByteBuffer frame = mEncoder.getInputBuffer(index);
                    int offset = frame.position();
                    long pstTs = calculateFrameTimestamp(0);
                    mEncoder.queueInputBuffer(index, offset, 0, pstTs, BUFFER_FLAG_END_OF_STREAM);
                }
            } else {
                // 兜底逻辑：使用原有的read(ByteBuffer)方式
                Log.w(TAG, "feedAudioEncoder [FALLBACK]: Using read(ByteBuffer) method, audioType=" + audioType);
                final ByteBuffer frame = mEncoder.getInputBuffer(index);
                int offset = frame.position();
                int limit = frame.limit();
                int read = 0;
                if (!eos) {
                    read = r.read(frame, limit);
                    Log.d(TAG, "feedAudioEncoder [FALLBACK]: read=" + read + " bytes, offset=" + offset + ", limit=" + limit);
                    if (read < 0) {
                        Log.e(TAG, "feedAudioEncoder [FALLBACK]: READ ERROR code=" + read +
                                " (-1=ERROR, -2=BAD_VALUE, -3=INVALID_OPERATION, -6=DEAD_OBJECT)");
                        read = 0;
                    } else if (read == 0) {
                        Log.w(TAG, "feedAudioEncoder [FALLBACK]: READ 0 bytes - no data available!");
                    }
                } else {
                    Log.w(TAG, "feedAudioEncoder [FALLBACK]: EOS detected, AudioRecord is STOPPED");
                }

                long pstTs = calculateFrameTimestamp(read << 3);
                int flags = BUFFER_FLAG_KEY_FRAME;

                if (eos) {
                    flags = BUFFER_FLAG_END_OF_STREAM;
                }
                // feed frame to encoder
                if (VERBOSE) Log.d(TAG, "Feed codec index=" + index + ", presentationTimeUs="
                        + pstTs + ", flags=" + flags);
                mEncoder.queueInputBuffer(index, offset, read, pstTs, flags);
            }
        }
    }

    private static final int LAST_FRAME_ID = -1;
    private SparseLongArray mFramesUsCache = new SparseLongArray(2);

    /**
     * Gets presentation time (us) of polled frame.
     * 1 sample = 16 bit
     */
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
        int samples = totalBits >> 4;
        long frameUs = mFramesUsCache.get(samples, -1);
        /*        Log.d(TAG, "calculateFrameTimestamp() called with: totalBits = [" + totalBits + "]");*/
        if (frameUs == -1) {
            frameUs = samples * 1000_000 / mChannelsSampleRate;
            mFramesUsCache.put(samples, frameUs);
        }
        long timeUs = SystemClock.elapsedRealtimeNanos() / 1000;
        // accounts the delay of polling the audio sample data
        timeUs -= frameUs;
        long currentUs;
        long lastFrameUs = mFramesUsCache.get(LAST_FRAME_ID, -1);
        if (lastFrameUs == -1) { // it's the first frame
            currentUs = timeUs;
        } else {
            currentUs = lastFrameUs;
        }
        if (VERBOSE)
            Log.i(TAG, "count samples pts: " + currentUs + ", time pts: " + timeUs + ", samples: " + samples);
        // maybe too late to acquire sample data
        if (timeUs - currentUs >= (frameUs << 1)) {
            // reset
            currentUs = timeUs;
        }
        mFramesUsCache.put(LAST_FRAME_ID, currentUs + frameUs);
        return currentUs;
    }

    @TargetApi(Build.VERSION_CODES.M)
    private AudioRecord createAudioRecord(int sampleRateInHz, int channelConfig, int audioFormat) {
        int minBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat);
        if (minBytes <= 0) {
            Log.e(TAG, String.format(Locale.US, "Bad arguments: getMinBufferSize(%d, %d, %d)",
                    sampleRateInHz, channelConfig, audioFormat));
            return null;
        }
        // 增大缓冲区以提高稳定性 (参考 SystemUI 的做法)
        int bufferSize = minBytes * 2;
        AudioRecord record = null;
        int type = audioType;
        if (type == 0) {
            // MIC 单独模式
            bufferMic = new short[bufferSize / 2];
            Log.d(TAG, "createAudioRecord [MIC]: bufferMic initialized, size=" + bufferMic.length);

            // 关键修复1: 使用 VOICE_COMMUNICATION 替代 CAMCORDER，与 SystemUI 保持一致
            // VOICE_COMMUNICATION 对麦克风录音更可靠，有更好的降噪处理
            record = createMicAudioRecord(sampleRateInHz, channelConfig, audioFormat, bufferSize);
            if (record != null) {
                mAudioRecordMic = record;
            }
        } else if (type == 1) {
            // 内置声音模式
            Log.d(TAG, "=== INTERNAL MODE INIT START ===");
            Log.d(TAG, "createAudioRecord: mediaProjection=" + mediaProjection + ", minBytes=" + minBytes);
            bufferInternal = new short[bufferSize / 2];
            Log.d(TAG, "createAudioRecord: bufferInternal initialized, size=" + bufferInternal.length);

            record = createInternalAudioRecord(sampleRateInHz, audioFormat, bufferSize);
            if (record != null) {
                mAudioRecord = record;
            }
            Log.d(TAG, "=== INTERNAL MODE INIT END ===");
        } else {
            // MIC + 内置声音混合模式
            Log.d(TAG, "=== MIC_AND_INTERNAL MODE INIT START ===");
            bufferInternal = new short[bufferSize / 2];
            bufferMic = new short[bufferSize / 2];

            // 创建 MIC AudioRecord
            mAudioRecordMic = createMicAudioRecord(sampleRateInHz, channelConfig, audioFormat, bufferSize);

            // 创建 Internal AudioRecord
            mAudioRecord = createInternalAudioRecord(sampleRateInHz, audioFormat, bufferSize);

            // 返回 MIC 的 record 作为主 record
            record = mAudioRecordMic;
            Log.d(TAG, "=== MIC_AND_INTERNAL MODE INIT END ===");
        }

        // 验证 AudioRecord 状态
        if (mAudioRecord != null && mAudioRecord.getState() == AudioRecord.STATE_UNINITIALIZED) {
            Log.e(TAG, String.format(Locale.US, "Internal AudioRecord UNINITIALIZED: %d, %d, %d",
                    sampleRateInHz, channelConfig, audioFormat));
            mAudioRecord.release();
            mAudioRecord = null;
        }
        if (mAudioRecordMic != null && mAudioRecordMic.getState() == AudioRecord.STATE_UNINITIALIZED) {
            Log.e(TAG, String.format(Locale.US, "Mic AudioRecord UNINITIALIZED: %d, %d, %d",
                    sampleRateInHz, channelConfig, audioFormat));
            mAudioRecordMic.release();
            mAudioRecordMic = null;
        }

        // 对于混合模式，确保至少有一个可用
        if (type == 2 && mAudioRecord == null && mAudioRecordMic == null) {
            Log.e(TAG, "Both audio records failed to initialize!");
            return null;
        }

        if (VERBOSE) {
            Log.i(TAG, "created AudioRecord " + record + ", MinBufferSize= " + minBytes);
            if (Build.VERSION.SDK_INT >= N) {
                Log.d(TAG, " size in frame " + (record != null ? record.getBufferSizeInFrames() : "null"));
            }
        }
        return record;
    }

    /**
     * 创建麦克风 AudioRecord，尝试多种音频源以提高兼容性
     *
     * 关键说明：
     * - SystemUI 对纯 MIC 模式使用 AudioSource.DEFAULT（见 ScreenMediaRecorder.java:132）
     * - SystemUI 对 MIC+INTERNAL 混合模式才使用 VOICE_COMMUNICATION（见 ScreenInternalAudioRecorder.java:115）
     * - VOICE_COMMUNICATION 会启用降噪/回声消除，在某些OEM设备上可能导致静音
     * - 因此优先使用 MIC 或 DEFAULT，最后才尝试 VOICE_COMMUNICATION
     */
    private AudioRecord createMicAudioRecord(int sampleRateInHz, int channelConfig, int audioFormat, int bufferSize) {
        // 按优先级尝试不同的音频源
        // ⚠️ 重要修复: 移除 VOICE_COMMUNICATION 音源
        // VOICE_COMMUNICATION 设计用于语音通话，会启用激进的降噪/回声消除算法
        // 在安静环境下(如屏幕录制场景)，这些算法会将小信号判定为噪声并静音
        // 导致用户录制的视频完全没有声音
        int[] audioSources = {
            MediaRecorder.AudioSource.MIC,                   // 标准麦克风 - 最可靠，优先使用
            MediaRecorder.AudioSource.DEFAULT,               // 系统默认 - 备选方案
            MediaRecorder.AudioSource.CAMCORDER              // 摄像机麦克风 - 最后备选
            // ❌ 已移除: MediaRecorder.AudioSource.VOICE_COMMUNICATION
            //    原因: 降噪算法在屏幕录制场景下会导致静音问题
        };

        Log.d(TAG, "createMicAudioRecord: Starting audio source selection, will try " + audioSources.length + " sources");

        for (int i = 0; i < audioSources.length; i++) {
            int audioSource = audioSources[i];
            try {
                Log.d(TAG, "createMicAudioRecord: [" + (i+1) + "/" + audioSources.length + "] Trying audioSource=" + audioSource +
                        " (" + getAudioSourceName(audioSource) + ")");

                AudioRecord record = new AudioRecord(audioSource,
                        sampleRateInHz,
                        channelConfig,
                        audioFormat,
                        bufferSize);

                Log.d(TAG, "createMicAudioRecord: AudioRecord created, state=" + record.getState() +
                        " (0=UNINITIALIZED, 1=INITIALIZED)");

                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    // 关键：验证是否能真正读取到有效数据
                    // 注意：verifyAudioRecordWorks 会启动录制，成功后保持录制状态
                    if (verifyAudioRecordWorks(record, audioSource)) {
                        Log.d(TAG, "createMicAudioRecord: SUCCESS with audioSource=" + audioSource +
                                " (" + getAudioSourceName(audioSource) + ")");
                        // 停止录制，让 MSG_PREPARE 重新启动（保持原有流程）
                        record.stop();
                        return record;
                    } else {
                        Log.w(TAG, "createMicAudioRecord: audioSource=" + audioSource +
                                " initialized but produces silent data, trying next...");
                        // verifyAudioRecordWorks 失败时已经处理了 stop
                        record.release();
                    }
                } else {
                    Log.w(TAG, "createMicAudioRecord: audioSource=" + audioSource +
                            " FAILED to initialize, state=" + record.getState());
                    record.release();
                }
            } catch (IllegalArgumentException e) {
                Log.e(TAG, "createMicAudioRecord: IllegalArgumentException with audioSource=" + audioSource +
                        " - " + e.getMessage());
            } catch (SecurityException e) {
                Log.e(TAG, "createMicAudioRecord: SecurityException (permission denied?) with audioSource=" + audioSource +
                        " - " + e.getMessage());
            } catch (Exception e) {
                Log.e(TAG, "createMicAudioRecord: Exception with audioSource=" + audioSource, e);
            }
        }

        Log.e(TAG, "createMicAudioRecord: All audio sources failed with signal verification!");

        // 备选方案：如果所有音频源都通过了验证但信号太弱，
        // 尝试返回一个至少能初始化的 AudioRecord（不验证信号强度）
        // 这样用户至少可以录制，即使音频可能很弱
        Log.w(TAG, "createMicAudioRecord: Trying fallback without signal verification...");
        for (int audioSource : audioSources) {
            try {
                AudioRecord record = new AudioRecord(audioSource,
                        sampleRateInHz,
                        channelConfig,
                        audioFormat,
                        bufferSize);

                if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                    Log.w(TAG, "createMicAudioRecord: FALLBACK SUCCESS with audioSource=" + audioSource +
                            " (" + getAudioSourceName(audioSource) + ") - signal may be weak!");
                    return record;
                } else {
                    record.release();
                }
            } catch (Exception e) {
                // 忽略
            }
        }

        Log.e(TAG, "createMicAudioRecord: All audio sources failed completely!");
        return null;
    }

    /**
     * 验证 AudioRecord 是否能真正读取到有效（非静音）数据
     * 某些设备上某些音频源虽然能初始化成功，但读取的数据全是0
     *
     * 注意：此方法会启动录制，调用者需要在验证后决定是否 stop()
     */
    private boolean verifyAudioRecordWorks(AudioRecord record, int audioSource) {
        try {
            record.startRecording();
            if (record.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
                Log.w(TAG, "verifyAudioRecordWorks: Failed to start recording for audioSource=" + audioSource);
                return false;
            }

            // 读取几帧数据来验证
            short[] testBuffer = new short[1024];
            int totalNonZero = 0;
            int maxValue = 0;
            int totalSamples = 0;
            long sumAbsValue = 0;  // 用于计算平均幅度

            // 读取5次来检验（给麦克风更多时间稳定，提高检测准确性）
            for (int i = 0; i < 5; i++) {
                int read = record.read(testBuffer, 0, testBuffer.length);
                if (read > 0) {
                    totalSamples += read;
                    for (int j = 0; j < read; j++) {
                        int absVal = Math.abs(testBuffer[j]);
                        if (testBuffer[j] != 0) {
                            totalNonZero++;
                            sumAbsValue += absVal;
                        }
                        maxValue = Math.max(maxValue, absVal);
                    }
                }
                // 短暂等待让麦克风稳定
                try { Thread.sleep(30); } catch (InterruptedException ignored) {}
            }

            // 计算平均幅度（只计算非零样本）
            int avgValue = totalNonZero > 0 ? (int)(sumAbsValue / totalNonZero) : 0;
            int nonZeroPercentage = totalSamples > 0 ? (totalNonZero * 100 / totalSamples) : 0;

            Log.d(TAG, "verifyAudioRecordWorks: audioSource=" + audioSource +
                    " (" + getAudioSourceName(audioSource) + ")" +
                    ", totalNonZero=" + totalNonZero + "/" + totalSamples +
                    " (" + nonZeroPercentage + "%)" +
                    ", maxValue=" + maxValue + ", avgValue=" + avgValue);

            // ===== 改进的阈值验证 =====
            // 阈值从50提升到200，避免极微弱的噪声通过验证
            // 正常语音信号幅度应该在几百到几千（16位PCM范围是-32768到32767）
            //
            // 验证条件：
            // 1. 最大值必须 >= 200（排除极弱信号和静音）
            // 2. 或者：非零样本占比 >= 30% 且平均值 >= 50（说明有持续的音频活动）
            //
            // 这个双重条件可以同时处理：
            // - 安静环境下的偶发声音（高峰值但低占比）
            // - 持续的低音量背景音（低峰值但高占比）

            boolean passedMaxThreshold = maxValue >= 200;
            boolean passedAvgThreshold = nonZeroPercentage >= 30 && avgValue >= 50;

            if (passedMaxThreshold || passedAvgThreshold) {
                Log.d(TAG, "verifyAudioRecordWorks: PASSED - audioSource=" + audioSource +
                        " (passedMax=" + passedMaxThreshold + ", passedAvg=" + passedAvgThreshold + ")");
                // 验证成功，保持录制状态（调用者会决定是否 stop）
                return true;
            } else {
                Log.w(TAG, "verifyAudioRecordWorks: FAILED - audioSource=" + audioSource +
                        " produces too weak signal (maxValue=" + maxValue + " < 200, avgValue=" + avgValue + ")");
                // 失败时停止录制
                try { record.stop(); } catch (Exception ignored) {}
                return false;
            }
        } catch (Exception e) {
            Log.e(TAG, "verifyAudioRecordWorks: Exception for audioSource=" + audioSource, e);
            try { record.stop(); } catch (Exception ignored) {}
            return false;
        }
    }

    /**
     * 定期打印音频统计信息，用于诊断录制状态
     * 每5秒打印一次，避免日志过多
     */
    private void printAudioStatsIfNeeded() {
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - mLastLogTimeMs >= LOG_INTERVAL_MS) {
            // 计算速率
            long elapsedTimeMs = currentTimeMs - mLastLogTimeMs;
            if (elapsedTimeMs == 0) elapsedTimeMs = 1; // 避免除零

            double framesPerSec = (mTotalFramesProcessed * 1000.0) / elapsedTimeMs;
            double kbytesPerSec = (mTotalBytesEncoded / 1024.0) * 1000.0 / elapsedTimeMs;

            // 计算丢帧率
            double dropRate = 0.0;
            if (mTotalFramesProcessed > 0) {
                dropRate = (mTotalFramesDropped * 100.0) / (mTotalFramesProcessed + mTotalFramesDropped);
            }

            Log.i(TAG, "╔═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ 🎵 AUDIO RECORDING STATS (last " + (elapsedTimeMs / 1000) + "s)");
            Log.i(TAG, "╠═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ Total frames:     " + mTotalFramesProcessed);
            Log.i(TAG, "║ Total bytes:      " + mTotalBytesEncoded + " (" + (mTotalBytesEncoded / 1024) + " KB)");
            Log.i(TAG, "║ Frames/sec:       " + String.format("%.2f", framesPerSec));
            Log.i(TAG, "║ Data rate:        " + String.format("%.2f", kbytesPerSec) + " KB/s");
            Log.i(TAG, "║ Silent frames:    " + mConsecutiveSilentFrames + " consecutive");
            Log.i(TAG, "║ Audio mode:       " + getAudioModeString());
            Log.i(TAG, "╠═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ 📊 BUFFER QUEUE STATS");
            Log.i(TAG, "╠═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ Buffer unavailable: " + mTotalBufferNotAvailableCount + " times");
            Log.i(TAG, "║ Frames queued:      " + mTotalFramesQueued);
            Log.i(TAG, "║ Frames dropped:     " + mTotalFramesDropped + " (" + String.format("%.2f", dropRate) + "%)");
            Log.i(TAG, "║ Dropped bytes:      " + mTotalDroppedBytes + " (" + (mTotalDroppedBytes / 1024) + " KB)");
            Log.i(TAG, "║ Current queue size: " + mPendingFramesQueue.size());
            Log.i(TAG, "║ Max queue depth:    " + mMaxQueueDepth);
            Log.i(TAG, "╚═══════════════════════════════════════════════════════════════");

            mLastLogTimeMs = currentTimeMs;
        }
    }

    /**
     * 获取当前音频模式的字符串描述
     */
    private String getAudioModeString() {
        if (mAudioRecordMic != null && mAudioRecord != null) {
            return "MIC + INTERNAL (Mixed)";
        } else if (audioType == 0) {
            return "MIC only";
        } else if (audioType == 1) {
            return "INTERNAL only";
        } else {
            return "UNKNOWN (audioType=" + audioType + ")";
        }
    }

    /**
     * 运行时静音检测
     * 检测连续的静音帧并发出警告，帮助诊断音频录制问题
     *
     * @param maxValue 当前帧的最大音频幅度值
     * @param source 音频源标识（用于日志）
     */
    private void checkAndWarnSilentFrame(int maxValue, String source) {
        if (maxValue < SILENT_FRAME_MAX_VALUE_THRESHOLD) {
            // 当前帧是静音
            mConsecutiveSilentFrames++;

            if (mConsecutiveSilentFrames >= SILENT_FRAMES_WARNING_THRESHOLD && !mSilenceWarningLogged) {
                // 连续静音帧达到阈值，发出警告
                Log.e(TAG, "★★★ SILENCE WARNING ★★★ [" + source + "]: " +
                        "Detected " + mConsecutiveSilentFrames + " consecutive silent frames! " +
                        "Audio source may not be working properly. " +
                        "maxValue=" + maxValue + " (threshold=" + SILENT_FRAME_MAX_VALUE_THRESHOLD + ")");
                Log.e(TAG, "Possible causes: " +
                        "1) Microphone permission revoked during recording; " +
                        "2) Another app is using the microphone; " +
                        "3) Hardware/driver issue; " +
                        "4) System audio source has no playback");

                mSilenceWarningLogged = true;  // 避免重复警告刷屏

                // TODO: 未来可以通过callback通知上层UI，让用户知道音频可能有问题
                // 例如：显示一个Toast或状态栏通知
            }
        } else {
            // 当前帧有有效音频，重置计数器
            if (mConsecutiveSilentFrames > 0) {
                if (mConsecutiveSilentFrames >= 3) {
                    Log.d(TAG, "checkAndWarnSilentFrame [" + source + "]: " +
                            "Audio recovered after " + mConsecutiveSilentFrames + " silent frames");
                }
                mConsecutiveSilentFrames = 0;
                mSilenceWarningLogged = false;  // 恢复后可以再次警告
            }
        }
    }

    /**
     * 获取音频源名称，用于日志
     */
    private String getAudioSourceName(int audioSource) {
        switch (audioSource) {
            case MediaRecorder.AudioSource.DEFAULT: return "DEFAULT";
            case MediaRecorder.AudioSource.MIC: return "MIC";
            case MediaRecorder.AudioSource.VOICE_COMMUNICATION: return "VOICE_COMMUNICATION";
            case MediaRecorder.AudioSource.CAMCORDER: return "CAMCORDER";
            case MediaRecorder.AudioSource.VOICE_RECOGNITION: return "VOICE_RECOGNITION";
            default: return "UNKNOWN(" + audioSource + ")";
        }
    }

    /**
     * 创建内置声音 AudioRecord
     * 使用 AudioPlaybackCaptureConfiguration 捕获系统音频
     */
    private AudioRecord createInternalAudioRecord(int sampleRateInHz, int audioFormat, int bufferSize) {
        if (mediaProjection == null) {
            Log.e(TAG, "createInternalAudioRecord: mediaProjection is null!");
            return null;
        }

        try {
            // 关键修复2: 内置音频捕获使用 CHANNEL_OUT_MONO/STEREO，不是 CHANNEL_IN_*
            // 这与 SystemUI 的实现保持一致
            int channelOutMask = (mChannelConfig == AudioFormat.CHANNEL_IN_STEREO)
                    ? AudioFormat.CHANNEL_OUT_STEREO
                    : AudioFormat.CHANNEL_OUT_MONO;

            AudioFormat format = new AudioFormat.Builder()
                    .setEncoding(audioFormat)
                    .setSampleRate(sampleRateInHz)
                    .setChannelMask(channelOutMask)
                    .build();
            Log.d(TAG, "createInternalAudioRecord: AudioFormat created - sampleRate=" + sampleRateInHz
                    + ", channelMask=" + channelOutMask);

            AudioPlaybackCaptureConfiguration playbackConfig = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build();
            Log.d(TAG, "createInternalAudioRecord: AudioPlaybackCaptureConfiguration created successfully");

            AudioRecord record = new AudioRecord.Builder()
                    .setAudioFormat(format)
                    .setBufferSizeInBytes(bufferSize)
                    .setAudioPlaybackCaptureConfig(playbackConfig)
                    .build();

            Log.d(TAG, "createInternalAudioRecord: AudioRecord created - state=" + record.getState() +
                    " (1=INITIALIZED, 0=UNINITIALIZED)");

            if (record.getState() == AudioRecord.STATE_INITIALIZED) {
                return record;
            } else {
                record.release();
                return null;
            }
        } catch (Exception e) {
            Log.e(TAG, "createInternalAudioRecord: FAILED", e);
            return null;
        }
    }

    /**
     * 启动录制并验证状态 - 参考 SystemUI 的实现
     * SystemUI 在 start() 中会验证 getRecordingState() == RECORDSTATE_RECORDING
     *
     * @param record AudioRecord 实例
     * @param tag 日志标签
     * @return 是否成功启动
     */
    private boolean startRecordingWithVerify(AudioRecord record, String tag) {
        if (record == null) {
            Log.e(TAG, "startRecordingWithVerify [" + tag + "]: AudioRecord is null!");
            return false;
        }

        try {
            record.startRecording();

            // 验证录制状态 - 关键检查，参考 SystemUI
            int recordingState = record.getRecordingState();
            if (recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e(TAG, "startRecordingWithVerify [" + tag + "]: FAILED! recordingState=" +
                        recordingState + " (expected 3=RECORDING, got 1=STOPPED)");
                return false;
            }

            Log.d(TAG, "startRecordingWithVerify [" + tag + "]: SUCCESS, recordingState=" + recordingState);
            return true;
        } catch (IllegalStateException e) {
            Log.e(TAG, "startRecordingWithVerify [" + tag + "]: IllegalStateException", e);
            return false;
        } catch (Exception e) {
            Log.e(TAG, "startRecordingWithVerify [" + tag + "]: Exception", e);
            return false;
        }
    }

    // ===== 智能静音填充方法 =====

    /**
     * 设置音频静音填充配置
     * @param config 配置对象
     */
    public void setAudioSilentFillConfig(AudioSilentFillConfig config) {
        if (config != null) {
            this.mSilentFillConfig = config;
            Log.i(TAG_SILENT_FILL, "★★★ CONFIG SET ★★★ " + config.toString());
        }
    }

    /**
     * 设置是否使用墙钟作为 PTS（主时钟）
     * @param useWallClock true=使用墙钟（推荐），false=使用采样数计算
     */
    public void setUseWallClockPTS(boolean useWallClock) {
        this.mUseWallClockPTS = useWallClock;
        Log.i(TAG, String.format("★★★ WALL CLOCK PTS MODE ★★★ enabled=%b (true=画面优先, false=采样数计算)",
            useWallClock));
    }

    /**
     * 设置录制开始时间戳（从 ScreenRecorder 传递）
     * @param startTimeNanos 录制开始的纳秒时间戳
     */
    public void setRecordingStartTimeNanos(long startTimeNanos) {
        this.mRecordingStartTimeNanos = startTimeNanos;
        Log.i(TAG, String.format("★★★ RECEIVED startNs FROM ScreenRecorder ★★★ startTimeNanos=%d", startTimeNanos));
    }

    /**
     * 实时检测音频帧是否为静音
     * @param buffer 音频数据缓冲区
     * @param length 有效数据长度
     * @return true表示静音，false表示有声音
     */
    private boolean isSilentFrame(short[] buffer, int length) {
        if (buffer == null || length == 0) return true;

        boolean allZeros = true;
        int maxValue = 0;

        for (int i = 0; i < length; i++) {
            if (buffer[i] != 0) allZeros = false;
            maxValue = Math.max(maxValue, Math.abs(buffer[i]));
        }

        boolean isSilent = allZeros || maxValue < SILENT_FRAME_MAX_VALUE_THRESHOLD;

        if (isSilent) {
            mConsecutiveSilentFrames++;
        } else {
            mConsecutiveSilentFrames = 0;
        }

        return isSilent;
    }

    /**
     * 应用静音填充策略
     * @param buffer 原始音频数据
     * @param length 数据长度
     * @return 填充后的字节数组，如果返回null表示跳过这一帧
     */
    private byte[] applySilentFillStrategy(short[] buffer, int length) {
        AudioSilentFillConfig.SilentFillMode mode = mSilentFillConfig.getMode();

        switch (mode) {
            case LOW_AMPLITUDE_NOISE:
                return fillLowAmplitudeNoise(buffer, length, mSilentFillConfig.getNoiseAmplitude());

            case FIXED_LOW_VALUE:
                return fillFixedLowValue(buffer, length);

            case REDUCED_SAMPLE_RATE:
                if (shouldSkipSilentFrame()) {
                    return null;  // 跳过这一帧
                }
                return fillFixedLowValue(buffer, length);

            case ZERO_WITH_PTS_COMPENSATION:
                return new byte[length * 2];  // 全0

            case HYBRID:
                if (isInInitialPeriod()) {
                    return convertToBytes(buffer, length);  // 前N秒正常feed
                } else {
                    return fillLowAmplitudeNoise(buffer, length, mSilentFillConfig.getNoiseAmplitude());
                }

            default:
                return convertToBytes(buffer, length);
        }
    }

    /**
     * 模式1：填充极低幅度白噪声
     */
    private byte[] fillLowAmplitudeNoise(short[] buffer, int length, int amplitude) {
        if (length <= 0) return new byte[0];

        if (this.buffer == null || this.buffer.length < length * 2) {
            this.buffer = new byte[length * 2];
        }

        for (int i = 0; i < length; i++) {
            // 生成±amplitude范围内的随机噪声
            short noise = (short)(mNoiseRandom.nextInt(amplitude * 2 + 1) - amplitude);
            int byteIndex = i * 2;
            this.buffer[byteIndex] = (byte)(noise & 0xFF);
            this.buffer[byteIndex + 1] = (byte)((noise >> 8) & 0xFF);
        }

        return this.buffer;
    }

    /**
     * 模式2：填充固定低值（交替±1）
     */
    private byte[] fillFixedLowValue(short[] buffer, int length) {
        if (length <= 0) return new byte[0];

        if (this.buffer == null || this.buffer.length < length * 2) {
            this.buffer = new byte[length * 2];
        }

        for (int i = 0; i < length; i++) {
            // 交替填充±1
            short value = (short)((i % 2 == 0) ? 1 : -1);
            int byteIndex = i * 2;
            this.buffer[byteIndex] = (byte)(value & 0xFF);
            this.buffer[byteIndex + 1] = (byte)((value >> 8) & 0xFF);
        }

        return this.buffer;
    }

    /**
     * 模式3：判断是否应该跳过当前静音帧
     */
    private boolean shouldSkipSilentFrame() {
        mSilentFrameSkipCount++;
        if (mSilentFrameSkipCount >= mSilentFillConfig.getSkipInterval()) {
            mSilentFrameSkipCount = 0;
            return false;  // 不跳过，feed这一帧
        }
        return true;  // 跳过这一帧
    }

    /**
     * 模式5：判断是否在初始期内
     */
    private boolean isInInitialPeriod() {
        if (mRecordingStartTime == 0) {
            mRecordingStartTime = System.currentTimeMillis();
        }
        return (System.currentTimeMillis() - mRecordingStartTime) < mSilentFillConfig.getInitialPeriodMs();
    }

}
