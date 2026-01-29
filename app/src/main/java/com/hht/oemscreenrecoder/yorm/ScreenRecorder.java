package com.hht.oemscreenrecoder.yorm;

import static android.media.MediaFormat.MIMETYPE_AUDIO_AAC;
import static android.media.MediaFormat.MIMETYPE_VIDEO_AVC;

import android.hardware.display.VirtualDisplay;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.media.projection.MediaProjection;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @author Yrom
 */
public class ScreenRecorder {
    private static final String TAG = "ScreenRecorder";
    private static final boolean VERBOSE = false;
    private static final int INVALID_INDEX = -1;
    public static final String VIDEO_AVC = MIMETYPE_VIDEO_AVC; // H.264 Advanced Video Coding
    public static final String AUDIO_AAC = MIMETYPE_AUDIO_AAC; // H.264 Advanced Audio Coding
    private String mDstPath;
    private VideoEncoder mVideoEncoder;
    private MicRecorder mAudioEncoder;

    private MediaFormat mVideoOutputFormat = null, mAudioOutputFormat = null;
    private int mVideoTrackIndex = INVALID_INDEX, mAudioTrackIndex = INVALID_INDEX;
    private MediaMuxer mMuxer;
    private boolean mMuxerStarted = false;
    private AtomicBoolean mPauseMux = new AtomicBoolean(false);
    private AtomicLong pauseDelayTime = new AtomicLong();
    private AtomicLong oncePauseTime = new AtomicLong();
    /**
     * previous presentationTimeUs for writing
     */
    private AtomicLong prevOutputPTSUs = new AtomicLong();
    private AtomicBoolean mForceQuit = new AtomicBoolean(false);
    private AtomicBoolean mIsRunning = new AtomicBoolean(false);
    private VirtualDisplay mVirtualDisplay;

    // ===== Muxer 统计（用于调试） =====
    private long mVideoFramesWritten = 0;
    private long mAudioFramesWritten = 0;
    private long mVideoBytesWritten = 0;
    private long mAudioBytesWritten = 0;
    private long mLastMuxerLogTimeMs = 0;
    private static final long MUXER_LOG_INTERVAL_MS = 5000; // 每5秒打印一次

    // ===== 墙钟时间戳（统一起点）=====
    // 核心策略：在录制真正开始时初始化，音频和视频共用同一个起点
    private long mRecordingStartTimeNanos = 0;  // 录制开始的纳秒时间戳（单调时钟）
    private boolean mUseWallClockPTS = true;    // 是否使用墙钟作为 PTS（默认启用）

    private HandlerThread mWorker;
    private CallbackHandler mHandler;

    private Callback mCallback;
    private LinkedList<Integer> mPendingVideoEncoderBufferIndices = new LinkedList<>();
    private LinkedList<Integer> mPendingAudioEncoderBufferIndices = new LinkedList<>();
    private LinkedList<MediaCodec.BufferInfo> mPendingAudioEncoderBufferInfos = new LinkedList<>();
    private LinkedList<MediaCodec.BufferInfo> mPendingVideoEncoderBufferInfos = new LinkedList<>();

    /**
     * @param display for {@link VirtualDisplay#setSurface(Surface)}
     * @param dstPath saving path
     */
    public ScreenRecorder(VideoEncodeConfig video,
                          AudioEncodeConfig audio,
                          VirtualDisplay display,
                          String dstPath) {
        mVirtualDisplay = display;
        mDstPath = dstPath;
        mVideoEncoder = new VideoEncoder(video);
        mAudioEncoder = audio == null ? null : new MicRecorder(audio);

    }


    public void setMediaProject(MediaProjection mediaProjection) {
        if (mAudioEncoder != null) {
            mAudioEncoder.setMediaProjection(mediaProjection);
        }
    }

    /**
     * 设置音频静音填充配置
     * @param config 配置对象
     */
    public void setAudioSilentFillConfig(AudioSilentFillConfig config) {
        if (mAudioEncoder != null) {
            mAudioEncoder.setAudioSilentFillConfig(config);
        }
    }

    /**
     * 设置是否使用墙钟作为音频 PTS（主时钟）
     * @param useWallClock true=使用墙钟（画面优先），false=使用采样数计算
     */
    public void setUseWallClockPTS(boolean useWallClock) {
        this.mUseWallClockPTS = useWallClock;
        if (mAudioEncoder != null) {
            mAudioEncoder.setUseWallClockPTS(useWallClock);
        }
    }

    /**
     * 获取录制开始时间戳（纳秒）
     * @return 录制开始的纳秒时间戳
     */
    public long getRecordingStartTimeNanos() {
        return mRecordingStartTimeNanos;
    }

    /**
     * stop task
     */
    public final void quit() {
        mForceQuit.set(true);
        if (!mIsRunning.get()) {
            release();
        } else {
            Log.d(TAG, "quit: signalStop (false)");
            signalStop(false);
        }

    }

    public void start() {
        if (mWorker != null) {
            throw new IllegalStateException();
        }
        mWorker = new HandlerThread(TAG);
        mWorker.start();
        mHandler = new CallbackHandler(mWorker.getLooper());
        mHandler.sendEmptyMessage(MSG_START);
    }
    protected final Object mSync = new Object();
    public void resume() {
//        synchronized (mSync) {
            oncePauseTime.set(System.nanoTime() - oncePauseTime.get());
            pauseDelayTime.set(pauseDelayTime.get() + oncePauseTime.get());
            if (mHandler!=null) {
                mHandler.sendEmptyMessage(MSG_RESUME);
            }
//            mSync.notifyAll();
//        }

    }

    public void pause() {
//        synchronized (mSync){
            oncePauseTime.set(System.nanoTime());
            if (mHandler!=null) {
                mHandler.sendEmptyMessage(MSG_PAUSE);
            }
//            mSync.notifyAll();
//        }

    }

    public void setCallback(Callback callback) {
        mCallback = callback;
    }

    public String getSavedPath() {
        return mDstPath;
    }

    public interface Callback {
        void onStop(Object error);

        void onStart();

        void onRecording(long presentationTimeUs);

        /**
         * 当检测到系统不支持内置声音录制时调用
         * @param audioType 音频类型：0=MIC, 1=INTERNAL, 2=MIC_AND_INTERNAL
         */
        default void onInternalAudioNotAvailable(int audioType) {
            // 默认空实现
        }
    }

    private static final int MSG_START = 0;
    private static final int MSG_STOP = 1;
    private static final int MSG_ERROR = 2;
    private static final int MSG_PAUSE = 3;
    private static final int MSG_RESUME = 4;
    private static final int STOP_WITH_EOS = 1;

    private class CallbackHandler extends Handler {
        CallbackHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_START:
                    try {
                        record();
                        if (mCallback != null) {
                            mCallback.onStart();
                        }
                        break;
                    } catch (Exception e) {
                        Log.e(TAG, "Failed to start recording", e);
                        msg.obj = e;
                        // 录制启动失败，发送错误消息
                        Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                    }
                    break;
                case MSG_RESUME:
                    resumeRecord();
                    break;
                case MSG_PAUSE:
                    pauseEncoders();
                    break;
                case MSG_STOP:
                case MSG_ERROR:
                    stopEncoders();
                    if (msg.arg1 != STOP_WITH_EOS) {
                        Log.d(TAG, "handleMessage: MSG_ERROR-->msg.arg1==" + msg.arg1);
                        signalEndOfStream();
                    }
                    if (mCallback != null) {
                        if (msg.obj == null) {
                            msg.obj = mDstPath;
                        }
                        mCallback.onStop(msg.obj);
                    }
                    release();
                    break;
            }
        }
    }

    private void signalEndOfStream() {
        MediaCodec.BufferInfo eos = new MediaCodec.BufferInfo();
        ByteBuffer buffer = ByteBuffer.allocate(0);
        eos.set(0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM);
        if (VERBOSE) {
            Log.i(TAG, "Signal EOS to muxer ");
        }
        if (mVideoTrackIndex != INVALID_INDEX) {
            writeSampleData(mVideoTrackIndex, eos, buffer);
        }
        if (mAudioTrackIndex != INVALID_INDEX) {
            writeSampleData(mAudioTrackIndex, eos, buffer);
        }
        mVideoTrackIndex = INVALID_INDEX;
        mAudioTrackIndex = INVALID_INDEX;
    }

    private void record() {
        if (mIsRunning.get() || mForceQuit.get()) {
            throw new IllegalStateException();
        }
        if (mVirtualDisplay == null) {
            throw new IllegalStateException("maybe release");
        }
        mIsRunning.set(true);

        // ===== 初始化墙钟时间戳（统一起点）=====
        // 关键：在录制真正开始时就初始化，音频和视频共用同一个起点
        mRecordingStartTimeNanos = android.os.SystemClock.elapsedRealtimeNanos();
        Log.i(TAG, "★★★ WALL CLOCK PTS INITIALIZED (UNIFIED) ★★★ startTimeNanos=" + mRecordingStartTimeNanos);

        // ===== 重置Muxer统计变量 =====
        mVideoFramesWritten = 0;
        mAudioFramesWritten = 0;
        mVideoBytesWritten = 0;
        mAudioBytesWritten = 0;
        mLastMuxerLogTimeMs = System.currentTimeMillis();
        mVideoPtsOffset = 0;
        mAudioPtsOffset = 0;
        mLastVideoPtsUs = 0;
        Log.d(TAG, "record: Muxer statistics reset - starting recording");

        try {
            // create muxer
            mMuxer = new MediaMuxer(mDstPath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            // create encoder and input surface
            prepareVideoEncoder();
            // 增强音频编码器准备的错误处理
            try {
                prepareAudioEncoder();
                // ===== 将统一的 startNs 传递给音频编码器 =====
                if (mAudioEncoder != null) {
                    mAudioEncoder.setRecordingStartTimeNanos(mRecordingStartTimeNanos);
                    mAudioEncoder.setUseWallClockPTS(mUseWallClockPTS);
                    Log.i(TAG, "★★★ PASSED startNs TO AUDIO ENCODER ★★★ startTimeNanos=" + mRecordingStartTimeNanos);
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to prepare audio encoder, continuing with video-only recording", e);
                // 音频编码器准备失败，设置为null以继续纯视频录制
                mAudioEncoder = null;
            }
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        // "turn on" VirtualDisplay after VideoEncoder prepared
        mVirtualDisplay.setSurface(mVideoEncoder.getInputSurface());
        if (VERBOSE) {
            Log.d(TAG, "set surface to display: " + mVirtualDisplay.getDisplay());
        }
    }

    private void resumeRecord() {
        mPauseMux.set(false);
    }

    private void pauseEncoders() {
        mPauseMux.set(true);
    }

    private void muxVideo(int index, MediaCodec.BufferInfo buffer) {
        if (!mIsRunning.get()) {
            Log.w(TAG, "muxVideo: Already stopped!");
            return;
        }
        if (!mMuxerStarted || mVideoTrackIndex == INVALID_INDEX) {
            Log.d(TAG, "muxVideo: Muxer not ready - PENDING video buffer index=" + index +
                    ", mMuxerStarted=" + mMuxerStarted + ", mVideoTrackIndex=" + mVideoTrackIndex);
            mPendingVideoEncoderBufferIndices.add(index);
            mPendingVideoEncoderBufferInfos.add(buffer);
            return;
        }
        ByteBuffer encodedData = mVideoEncoder.getOutputBuffer(index);
        writeSampleData(mVideoTrackIndex, buffer, encodedData);
        mVideoEncoder.releaseOutputBuffer(index);
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            Log.d(TAG, "Stop encoder and muxer, since the buffer has been marked with EOS");
            // send release msg
            mVideoTrackIndex = INVALID_INDEX;
            signalStop(true);
        }
    }


    private void muxAudio(int index, MediaCodec.BufferInfo buffer) {
        Log.d(TAG, "muxAudio: ★★★ CALLED ★★★ index=" + index + ", size=" + buffer.size +
                ", mIsRunning=" + mIsRunning.get() + ", mMuxerStarted=" + mMuxerStarted +
                ", mAudioTrackIndex=" + mAudioTrackIndex);
        if (!mIsRunning.get()) {
            Log.w(TAG, "muxAudio: Already stopped!");
            return;
        }
        if (!mMuxerStarted || mAudioTrackIndex == INVALID_INDEX) {
            Log.d(TAG, "muxAudio: Muxer not ready - PENDING audio buffer index=" + index +
                    ", mMuxerStarted=" + mMuxerStarted + ", mAudioTrackIndex=" + mAudioTrackIndex);
            mPendingAudioEncoderBufferIndices.add(index);
            mPendingAudioEncoderBufferInfos.add(buffer);
            return;

        }
        Log.d(TAG, "muxAudio: Getting output buffer from encoder...");
        ByteBuffer encodedData = mAudioEncoder.getOutputBuffer(index);
        Log.d(TAG, "muxAudio: Got buffer, size=" + (encodedData != null ? encodedData.remaining() : "null") +
                ", calling writeSampleData...");
        writeSampleData(mAudioTrackIndex, buffer, encodedData);
        Log.d(TAG, "muxAudio: writeSampleData returned, releasing buffer...");
        mAudioEncoder.releaseOutputBuffer(index);
        Log.d(TAG, "muxAudio: Buffer released");
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
            if (VERBOSE) {
                Log.d(TAG, "Stop encoder and muxer, since the buffer has been marked with EOS");
            }
            mAudioTrackIndex = INVALID_INDEX;
            signalStop(true);
        }
        Log.d(TAG, "muxAudio: ★★★ EXIT ★★★");
    }

    private void writeSampleData(int track, MediaCodec.BufferInfo buffer, ByteBuffer encodedData) {
        String trackType = (track == mVideoTrackIndex) ? "VIDEO" : (track == mAudioTrackIndex) ? "AUDIO" : "UNKNOWN";
        Log.d(TAG, "writeSampleData: ★★★ CALLED ★★★ track=" + track + " (" + trackType + ")" +
                ", size=" + buffer.size + ", pts=" + buffer.presentationTimeUs +
                ", flags=" + buffer.flags + ", mPauseMux=" + mPauseMux.get());

        if (mPauseMux.get()){
            Log.w(TAG, "writeSampleData: SKIPPED due to mPauseMux=true");
            return;
        }
        if ((buffer.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
            // The codec config data was pulled out and fed to the muxer when we got
            // the INFO_OUTPUT_FORMAT_CHANGED status.
            // Ignore it.
            if (VERBOSE) {
                Log.d(TAG, "Ignoring BUFFER_FLAG_CODEC_CONFIG");
            }
            buffer.size = 0;
        }
        boolean eos = (buffer.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
        if (buffer.size == 0 && !eos) {
            Log.w(TAG, "writeSampleData: SKIPPED - buffer.size == 0 and not EOS");
            if (VERBOSE) {
                Log.d(TAG, "info.size == 0, drop it.");
            }
            encodedData = null;
        } else {
            if (buffer.presentationTimeUs != 0) { // maybe 0 if eos
                if (track == mVideoTrackIndex) {
                    resetVideoPts(buffer);
                } else if (track == mAudioTrackIndex) {
                    resetAudioPts(buffer);
                }
            }
            if (VERBOSE) {
                Log.d(TAG, "[" + Thread.currentThread().getId() + "] Got buffer, track=" + track
                        + ", info: size=" + buffer.size
                        + ", presentationTimeUs=" + buffer.presentationTimeUs);
            }
            if (!eos && mCallback != null) {
                mCallback.onRecording(buffer.presentationTimeUs);
            }
        }
        if (encodedData != null) {
            encodedData.position(buffer.offset);
            encodedData.limit(buffer.offset + buffer.size);
            Log.i(TAG, "writeSampleData: ★★★ WRITING TO MUXER ★★★ track=" + track + " (" + trackType + ")" +
                    ", size=" + buffer.size + ", pts=" + buffer.presentationTimeUs);
            mMuxer.writeSampleData(track, encodedData, buffer);
            Log.d(TAG, "writeSampleData: Muxer write completed successfully");

            // ===== 统计Muxer写入数据 =====
            if (track == mVideoTrackIndex) {
                mVideoFramesWritten++;
                mVideoBytesWritten += buffer.size;
            } else if (track == mAudioTrackIndex) {
                mAudioFramesWritten++;
                mAudioBytesWritten += buffer.size;
                Log.i(TAG, "writeSampleData: AUDIO frame written! Total audio frames=" + mAudioFramesWritten +
                        ", total bytes=" + mAudioBytesWritten);
            }

            if (VERBOSE) {
                Log.i(TAG, "Sent " + buffer.size + " bytes to MediaMuxer on track " + track);
            }

            // ===== 定期打印Muxer统计 =====
            printMuxerStatsIfNeeded();
        } else {
            Log.w(TAG, "writeSampleData: SKIPPED - encodedData is null");
        }
        Log.d(TAG, "writeSampleData: ★★★ EXIT ★★★");
    }

    /**
     * 定期打印Muxer统计信息，用于诊断音视频写入状态
     */
    private void printMuxerStatsIfNeeded() {
        long currentTimeMs = System.currentTimeMillis();
        if (currentTimeMs - mLastMuxerLogTimeMs >= MUXER_LOG_INTERVAL_MS) {
            long elapsedTimeMs = currentTimeMs - mLastMuxerLogTimeMs;
            if (elapsedTimeMs == 0) elapsedTimeMs = 1;

            double videoFps = (mVideoFramesWritten * 1000.0) / elapsedTimeMs;
            double audioFps = (mAudioFramesWritten * 1000.0) / elapsedTimeMs;
            double videoKbps = (mVideoBytesWritten * 8.0 / 1024.0) * 1000.0 / elapsedTimeMs;
            double audioKbps = (mAudioBytesWritten * 8.0 / 1024.0) * 1000.0 / elapsedTimeMs;

            Log.i(TAG, "╔═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ 📹 MUXER STATS (last " + (elapsedTimeMs / 1000) + "s)");
            Log.i(TAG, "╠═══════════════════════════════════════════════════════════════");
            Log.i(TAG, "║ VIDEO:");
            Log.i(TAG, "║   Frames:       " + mVideoFramesWritten + " (" + String.format("%.2f", videoFps) + " fps)");
            Log.i(TAG, "║   Bytes:        " + mVideoBytesWritten + " (" + (mVideoBytesWritten / 1024) + " KB)");
            Log.i(TAG, "║   Bitrate:      " + String.format("%.2f", videoKbps) + " Kbps");
            Log.i(TAG, "║ AUDIO:");
            Log.i(TAG, "║   Frames:       " + mAudioFramesWritten);
            Log.i(TAG, "║   Bytes:        " + mAudioBytesWritten + " (" + (mAudioBytesWritten / 1024) + " KB)");
            Log.i(TAG, "║   Bitrate:      " + String.format("%.2f", audioKbps) + " Kbps");
            Log.i(TAG, "╚═══════════════════════════════════════════════════════════════");

            mLastMuxerLogTimeMs = currentTimeMs;
        }
    }

    private long mVideoPtsOffset, mAudioPtsOffset;
    private long mLastVideoPtsUs = 0;

    private void resetAudioPts(MediaCodec.BufferInfo buffer) {
        if (VERBOSE) {
            Log.d(TAG, "resetAudioPts, mAudioPtsOffset == "+mAudioPtsOffset);
        }
//        synchronized (mSync){
            if (mAudioPtsOffset == 0) {
                mAudioPtsOffset = buffer.presentationTimeUs;
                buffer.presentationTimeUs = 0;
            } else {
                long pauseDelayUs = mAudioPtsOffset*1000 + pauseDelayTime.get();
                long resetUs= buffer.presentationTimeUs - pauseDelayUs/1000;
                if (resetUs < prevOutputPTSUs.get()){
                    Log.e(TAG, "resetAudioPts, timestamp went backwards, adding 1000us offset");
                    buffer.presentationTimeUs =  prevOutputPTSUs.get()+1000;
                }else {
                    buffer.presentationTimeUs = resetUs;
                }
                if (VERBOSE) {
                    Log.d(TAG, "resetAudioPts, pauseDelayUs == "+pauseDelayTime.get() +" PTS=="+buffer.presentationTimeUs);
                }
            }
            prevOutputPTSUs.set(buffer.presentationTimeUs);
//            mSync.notifyAll();
//        }
    }

    private void resetVideoPts(MediaCodec.BufferInfo buffer) {
        // ===== 墙钟模式：保持相对时间间隔，只调整起点 =====
        if (mUseWallClockPTS && mRecordingStartTimeNanos > 0) {
            long elapsedNanos = android.os.SystemClock.elapsedRealtimeNanos() - mRecordingStartTimeNanos;
            long pauseDelayUs = pauseDelayTime.get() / 1000;
            long ptsUs = Math.max(0, (elapsedNanos / 1000) - pauseDelayUs);

            if (ptsUs < mLastVideoPtsUs) {
                Log.w(TAG, "resetVideoPts [WALL_CLOCK]: timestamp went backwards, clamping");
                ptsUs = mLastVideoPtsUs + 1000;
            }
            mLastVideoPtsUs = ptsUs;
            buffer.presentationTimeUs = ptsUs;

            if (VERBOSE) {
                Log.d(TAG, String.format(java.util.Locale.US,
                    "resetVideoPts [WALL_CLOCK]: elapsedNs=%d, pauseDelayUs=%d, finalPts=%d",
                    elapsedNanos, pauseDelayUs, buffer.presentationTimeUs));
            }
            return;
        }

        // ===== 原有的基于 codec PTS 的计算（兼容模式）=====
        if (VERBOSE) {
            Log.d(TAG, "resetVideoPts, mVideoPtsOffset == "+mVideoPtsOffset);
        }
        if (mVideoPtsOffset == 0) {
            mVideoPtsOffset = buffer.presentationTimeUs;
            buffer.presentationTimeUs = 0;
        } else {
            long pauseDelayUs = mVideoPtsOffset + pauseDelayTime.get()/1000;
            buffer.presentationTimeUs -= pauseDelayUs;
            if (VERBOSE) {
                Log.d(TAG, "resetVideoPts, pauseDelayUs == "+pauseDelayTime.get() +" PTS=="+buffer.presentationTimeUs);
            }
        }
    }

    private void resetVideoOutputFormat(MediaFormat newFormat) {
        // should happen before receiving buffers, and should only happen once
        if (mVideoTrackIndex >= 0 || mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        Log.i(TAG, "★ Video output format changed! format: " + newFormat.toString());
        mVideoOutputFormat = newFormat;
    }

    private void resetAudioOutputFormat(MediaFormat newFormat) {
        // should happen before receiving buffers, and should only happen once
        if (mAudioTrackIndex >= 0 || mMuxerStarted) {
            throw new IllegalStateException("output format already changed!");
        }
        Log.i(TAG, "★ Audio output format changed! format: " + newFormat.toString());
        mAudioOutputFormat = newFormat;
    }

    private void startMuxerIfReady() {
        Log.d(TAG, "startMuxerIfReady: mMuxerStarted=" + mMuxerStarted +
                ", mVideoOutputFormat=" + (mVideoOutputFormat != null ? "SET" : "NULL") +
                ", mAudioEncoder=" + (mAudioEncoder != null ? "SET" : "NULL") +
                ", mAudioOutputFormat=" + (mAudioOutputFormat != null ? "SET" : "NULL"));
        if (mMuxerStarted || mVideoOutputFormat == null
                || (mAudioEncoder != null && mAudioOutputFormat == null)) {
            Log.d(TAG, "startMuxerIfReady: NOT READY - returning early");
            return;
        }

        try {
            // ===== 添加视频轨道（必需） =====
            mVideoTrackIndex = mMuxer.addTrack(mVideoOutputFormat);
            Log.i(TAG, "Video track added successfully, index=" + mVideoTrackIndex);

            // ===== 添加音频轨道（可选，失败时降级为纯视频录制） =====
            if (mAudioEncoder != null) {
                try {
                    mAudioTrackIndex = mMuxer.addTrack(mAudioOutputFormat);
                    Log.i(TAG, "Audio track added successfully, index=" + mAudioTrackIndex);
                } catch (IllegalArgumentException e) {
                    // 音频格式不被MP4 Muxer支持 - 降级为纯视频录制
                    Log.e(TAG, "Failed to add audio track - format not supported by muxer", e);
                    Log.e(TAG, "Audio format was: " + mAudioOutputFormat.toString());
                    Log.w(TAG, "FALLBACK: Switching to video-only recording");

                    // 停止音频编码器
                    if (mAudioEncoder != null) {
                        try {
                            mAudioEncoder.stop();
                        } catch (Exception stopEx) {
                            Log.w(TAG, "Error stopping audio encoder during fallback", stopEx);
                        }
                        mAudioEncoder = null;
                    }

                    mAudioTrackIndex = INVALID_INDEX;
                    mAudioOutputFormat = null;

                    // 只记录错误日志，不停止录制（视频继续）
                    // TODO: 未来可以考虑添加Toast通知用户音频录制失败
                    Log.w(TAG, "Audio track will be skipped, continuing with video-only recording");
                } catch (IllegalStateException e) {
                    // Muxer状态异常
                    Log.e(TAG, "Failed to add audio track - muxer in illegal state", e);
                    Log.w(TAG, "FALLBACK: Switching to video-only recording");

                    if (mAudioEncoder != null) {
                        try {
                            mAudioEncoder.stop();
                        } catch (Exception stopEx) {
                            Log.w(TAG, "Error stopping audio encoder during fallback", stopEx);
                        }
                        mAudioEncoder = null;
                    }

                    mAudioTrackIndex = INVALID_INDEX;
                    mAudioOutputFormat = null;

                    Log.w(TAG, "Audio track skipped due to muxer state error, continuing with video-only");
                }
            } else {
                mAudioTrackIndex = INVALID_INDEX;
            }

            // ===== 启动Muxer =====
            mMuxer.start();
            mMuxerStarted = true;
            Log.i(TAG, "★★★ Started media muxer! videoIndex=" + mVideoTrackIndex + ", audioIndex=" + mAudioTrackIndex);

        } catch (IllegalArgumentException e) {
            // 视频轨道添加失败 - 这是致命错误，无法降级
            Log.e(TAG, "FATAL: Failed to add video track - cannot continue recording", e);
            Log.e(TAG, "Video format was: " + mVideoOutputFormat.toString());

            if (mCallback != null) {
                mCallback.onStop(new Exception("视频格式不支持，无法录制。错误: " + e.getMessage()));
            }

            // 清理资源
            signalStop(false);
            return;

        } catch (IllegalStateException e) {
            // Muxer状态异常 - 致命错误
            Log.e(TAG, "FATAL: Muxer in illegal state - cannot continue recording", e);

            if (mCallback != null) {
                mCallback.onStop(new Exception("Muxer状态异常，无法录制。错误: " + e.getMessage()));
            }

            signalStop(false);
            return;
        }

        // ===== 处理pending buffers =====
        if (mPendingVideoEncoderBufferIndices.isEmpty() && mPendingAudioEncoderBufferIndices.isEmpty()) {
            return;
        }
        if (VERBOSE) {
            Log.i(TAG, "Mux pending video output buffers...");
        }
        MediaCodec.BufferInfo info;
        while ((info = mPendingVideoEncoderBufferInfos.poll()) != null) {
            int index = mPendingVideoEncoderBufferIndices.poll();
            muxVideo(index, info);
        }
        if (mAudioEncoder != null) {
            while ((info = mPendingAudioEncoderBufferInfos.poll()) != null) {
                int index = mPendingAudioEncoderBufferIndices.poll();
                muxAudio(index, info);
            }
        }
        if (VERBOSE) {
            Log.i(TAG, "Mux pending video output buffers done.");
        }
    }

    // @WorkerThread
    private void prepareVideoEncoder() throws IOException {
        VideoEncoder.Callback callback = new VideoEncoder.Callback() {
            boolean ranIntoError = false;

            @Override
            public void onOutputBufferAvailable(BaseEncoder codec, int index, MediaCodec.BufferInfo info) {
                if (VERBOSE) {
                    Log.i(TAG, "VideoEncoder output buffer available: index=" + index);
                }
                try {
                    muxVideo(index, info);
                } catch (Exception e) {
                    Log.e(TAG, "Muxer encountered an error! ", e);
                    Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                }
            }

            @Override
            public void onError(Encoder codec, Exception e) {
                ranIntoError = true;
                Log.e(TAG, "VideoEncoder ran into an error! ", e);
                Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
            }

            @Override
            public void onOutputFormatChanged(BaseEncoder codec, MediaFormat format) {
                resetVideoOutputFormat(format);
                startMuxerIfReady();
            }
        };
        mVideoEncoder.setCallback(callback);
        mVideoEncoder.prepare();
    }

    private void prepareAudioEncoder() throws IOException {
        final MicRecorder micRecorder = mAudioEncoder;
        if (micRecorder == null) {
            return;
        }
        AudioEncoder.Callback callback = new AudioEncoder.Callback() {
            boolean ranIntoError = false;

            @Override
            public void onOutputBufferAvailable(BaseEncoder codec, int index, MediaCodec.BufferInfo info) {
                Log.i(TAG, "★★★ AudioEncoder.Callback.onOutputBufferAvailable CALLED ★★★ index=" + index +
                        ", size=" + info.size + ", pts=" + info.presentationTimeUs + ", flags=" + info.flags);
                if (VERBOSE) {
                    Log.i(TAG, "[" + Thread.currentThread().getId() + "] AudioEncoder output buffer available: index=" + index);
                }
                try {
                    Log.d(TAG, "AudioEncoder.Callback: Calling muxAudio...");
                    muxAudio(index, info);
                    Log.d(TAG, "AudioEncoder.Callback: muxAudio returned");
                } catch (Exception e) {
                    Log.e(TAG, "Muxer encountered an error! ", e);
                    Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
                }
            }

            @Override
            public void onOutputFormatChanged(BaseEncoder codec, MediaFormat format) {
                Log.i(TAG, "★★★ AudioEncoder.Callback.onOutputFormatChanged CALLED ★★★");
                Log.i(TAG, "Audio format: " + format.toString());
                if (VERBOSE) {
                    Log.d(TAG, "[" + Thread.currentThread().getId() + "] AudioEncoder returned new format " + format);
                }
                resetAudioOutputFormat(format);
                Log.d(TAG, "AudioEncoder.Callback: Calling startMuxerIfReady...");
                startMuxerIfReady();
                Log.d(TAG, "AudioEncoder.Callback: startMuxerIfReady returned");
            }

            @Override
            public void onError(Encoder codec, Exception e) {
                ranIntoError = true;
                Log.e(TAG, "MicRecorder ran into an error! ", e);
                Message.obtain(mHandler, MSG_ERROR, e).sendToTarget();
            }

            @Override
            public void onInternalAudioNotAvailable(int audioType) {
                Log.w(TAG, "★★★ ScreenRecorder: Internal audio not available (audioType=" + audioType + "), notifying callback ★★★");
                // 通知上层（ScreenRecorderHelper）显示Toast
                if (mCallback != null) {
                    mCallback.onInternalAudioNotAvailable(audioType);
                }
            }


        };
        micRecorder.setCallback(callback);
        micRecorder.prepare();
    }

    private void signalStop(boolean stopWithEOS) {
        Message msg = Message.obtain(mHandler, MSG_STOP, stopWithEOS ? STOP_WITH_EOS : 0, 0);
        mHandler.sendMessageAtFrontOfQueue(msg);
    }

    private void stopEncoders() {
        mIsRunning.set(false);
        mPendingAudioEncoderBufferInfos.clear();
        mPendingAudioEncoderBufferIndices.clear();
        mPendingVideoEncoderBufferInfos.clear();
        mPendingVideoEncoderBufferIndices.clear();
        // maybe called on an error has been occurred
        try {
            if (mVideoEncoder != null) {
                mVideoEncoder.stop();
            }
        } catch (IllegalStateException e) {
            // ignored
        }
        try {
            if (mAudioEncoder != null) {
                mAudioEncoder.stop();
            }
        } catch (IllegalStateException e) {
            // ignored
        }

    }

    private void release() {
        if (mVirtualDisplay != null) {
            mVirtualDisplay.setSurface(null);
            mVirtualDisplay = null;
        }

        mVideoOutputFormat = mAudioOutputFormat = null;
        mVideoTrackIndex = mAudioTrackIndex = INVALID_INDEX;
        mMuxerStarted = false;

        if (mWorker != null) {
            mWorker.quitSafely();
            mWorker = null;
        }
        if (mVideoEncoder != null) {
            mVideoEncoder.release();
            mVideoEncoder = null;
        }
        if (mAudioEncoder != null) {
            mAudioEncoder.release();
            mAudioEncoder = null;
        }

        if (mMuxer != null) {
            try {
                mMuxer.stop();
                mMuxer.release();
            } catch (Exception e) {
                // ignored
            }
            mMuxer = null;
        }
        mHandler = null;
    }

    @Override
    protected void finalize() throws Throwable {
        if (mVirtualDisplay != null) {
            Log.e(TAG, "release() not called!");
            release();
        }
    }

}
