package com.hht.oemscreenrecoder.screenrecorder

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Point
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.util.Log
import android.view.*
import android.widget.TextView
import android.widget.Toast
import androidx.annotation.RequiresApi
import androidx.core.content.FileProvider
import com.blankj.utilcode.util.PathUtils
import com.hht.oemscreenrecoder.MainActivity
import com.hht.oemscreenrecoder.R
import com.hht.oemscreenrecoder.Settings
import com.hht.oemscreenrecoder.widgets.SaveActivity
import com.hht.oemscreenrecoder.yorm.AudioEncodeConfig
import com.hht.oemscreenrecoder.yorm.AudioSilentFillConfig
import com.hht.oemscreenrecoder.yorm.ScreenRecorder
import com.hht.oemscreenrecoder.yorm.ScreenRecorder.AUDIO_AAC
import com.hht.oemscreenrecoder.yorm.ScreenRecorder.VIDEO_AVC
import com.hht.oemscreenrecoder.yorm.Utils
import com.hht.oemscreenrecoder.yorm.VideoEncodeConfig
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class ScreenRecordHelper constructor(
    private var context: Context,
    private val listener: OnVideoRecordListener?,
    private var mediaProjection: MediaProjection?
) {
    private val settings: Settings by lazy { Settings.getInstance(context) }
    private var mediaProjectionManager: MediaProjectionManager? = null
    //    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var source: ScreenRecordingAudioSource? = null
    private var mRecorder: ScreenRecorder? = null
    private var file: File? = null
    private var isAgan: Boolean? = false
    // 用户切换标记：用户切换时跳过保存弹窗，直接保留默认路径的文件
    private var isUserSwitching: Boolean = false
    // 录制停止原因：0-正常停止，1-时长限制，2-空间不足
    private var stopReason: Int = Settings.STOP_REASON_NORMAL
    init {
        Log.d(TAG, "init: ScreenRecordHelper")
//        mediaProjectionManager =
//            context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager
//        mediaProjection = mediaProjectionManager?.getMediaProjection(RESULT_OK, data!!)
    }

    fun startRecord(source: ScreenRecordingAudioSource) {
        this.source = source
        try {
//            if (mediaProjectionManager == null) {
//                Log.d(TAG, "mediaProjectionManager == null，当前装置不支持录屏")
//                showToast(R.string.device_not_support_screen_record)
//                return
//            }
            Log.d(TAG, "startRecord: ScreenRecordingAudioSource-->$source")
            startCapturing(mediaProjection!!, source)
            // 移除立即调用 onStartRecord，改为在录制真正开始后调用
            // listener?.onStartRecord() 现在在 ScreenRecorder 的回调中调用
        } catch (e: Exception) {
            Log.e(TAG, "startRecord:error", e)
            // 录制启动失败，通知上层
            listener?.onCancelRecord()
        }
    }

    private fun showToast(resId: Int) {
        val inflater: LayoutInflater = LayoutInflater.from(context)
        val layout: View = inflater.inflate(R.layout.optoma_toast, null as ViewGroup?)
        val textView: TextView = layout.findViewById(R.id.toast_text)
        textView.setText(resId)
        with(Toast.makeText(context, context.getString(resId), Toast.LENGTH_SHORT)) {
            setGravity(Gravity.BOTTOM or Gravity.END, 0, 0)
            view = layout
            setMargin(0f, 0f)
            show()
        }
    }

    /**
     * 退出应用释放资源
     */
    fun clearAll() {
        virtualDisplay?.release()
        virtualDisplay = null
        mediaProjection?.stop()
        mediaProjection = null
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun resume() {
        mRecorder?.resume()
        listener?.onStartRecord()
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun pause() {
        mRecorder?.pause()
        listener?.onPauseRecord()
    }

    private fun getVideoSizeWidth(): Int {
        if (settings.getResolutionData() == Settings.RESOLUTION_1920_1080) {
            return VIDEO_SIZE_MAX_WIDTH_1920
        } else if (settings.getResolutionData() == Settings.RESOLUTION_1280_720) {
            return VIDEO_SIZE_MAX_WIDTH_1280
        } else if (settings.getResolutionData() == Settings.RESOLUTION_3840_2160) {
            return VIDEO_SIZE_MAX_WIDTH_3840
        }
        return VIDEO_SIZE_MAX_WIDTH_1280
    }

    private fun getVideoSizeHeight(): Int {
        if (settings.getResolutionData() == Settings.RESOLUTION_1920_1080) {
            return VIDEO_SIZE_MAX_HEIGHT_1080
        } else if (settings.getResolutionData() == Settings.RESOLUTION_1280_720) {
            return VIDEO_SIZE_MAX_HEIGHT_720
        } else if (settings.getResolutionData() == Settings.RESOLUTION_3840_2160) {
            return VIDEO_SIZE_MAX_HEIGHT_2160
        }
        return VIDEO_SIZE_MAX_HEIGHT_720
    }

    private fun newRecorder(
        mediaProjection: MediaProjection, video: VideoEncodeConfig,
        audio: AudioEncodeConfig?, output: File
    ): ScreenRecorder {
        val display = getOrCreateVirtualDisplay(mediaProjection, video)
        val r = ScreenRecorder(video, audio, display, output.absolutePath)
        r.setMediaProject(mediaProjection)

        // ===== 设置音频静音填充配置 =====
        if (audio != null) {
            val silentFillConfig = loadAudioSilentFillConfig()
            r.setAudioSilentFillConfig(silentFillConfig)
            Log.i(TAG, "★★★ AUDIO SILENT FILL CONFIG SET ★★★ $silentFillConfig")
        }

        r.setCallback(object : ScreenRecorder.Callback {
            override fun onStop(message: Any?) {
                if (message != null && message is Throwable) {
                    message.printStackTrace()
                    output.mkdir()
                    // 录制过程中出现错误，通知上层
                    listener?.onCancelRecord()
                } else if (message != null && message is String) {
                    if (isUserSwitching) {
                        // 用户切换场景：跳过保存弹窗，文件已保存在默认路径
                        Log.d(TAG, "onStop: 用户切换中，跳过保存弹窗，文件已保存: ${output.absolutePath}")
                        listener?.onEndRecord()
                        // 重置标记
                        isUserSwitching = false
                        stopReason = Settings.STOP_REASON_NORMAL
                    } else if (isAgan == false) {
                        // 正常停止录制：显示保存选择界面
                        val intent = Intent(context, SaveActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        intent.putExtra("filePath", output.absolutePath)
                        // 传递停止原因给 SaveActivity
                        intent.putExtra("stopReason", stopReason)
                        Log.d(TAG, "onStop: 启动SaveActivity，stopReason=$stopReason")
                        context.startActivity(intent)
                        listener?.onEndRecord()
                        // 重置停止原因
                        stopReason = Settings.STOP_REASON_NORMAL
                    } else {
                        // 重新录制场景
                        listener?.onEndRecord()
                        stopReason = Settings.STOP_REASON_NORMAL
                    }
                }
            }

            override fun onStart() {
                // 录制真正开始后才调用 onStartRecord
                Log.d(TAG, "ScreenRecorder onStart - 录制真正开始")
                listener?.onStartRecord()
            }

            override fun onRecording(presentationTimeUs: Long) {}

            override fun onInternalAudioNotAvailable(audioType: Int) {
                Log.w(TAG, "★★★ ScreenRecorderHelper: Internal audio not available (audioType=$audioType), notifying listener ★★★")
                // 通知上层（ScreenRecordService）显示Toast
                listener?.onInternalAudioNotAvailable(audioType)
            }
        })
        return r
    }

    /**
     * 录屏文件拷贝到U盘
     */
//    fun copyVideoToUsb(position:Int) {
//        Log.d(TAG, "copyVideoToUsbsaveFile--------: $newFile")
//        usbFile = File(UsbFlashUtil.getUsbPath().get(position -1), "Screen Record/ScreenRecord_${fileName}.mp4")
//
//        if (newFile!!.exists()) {
//            val outF: FileChannel
//            try {
//                outF = FileOutputStream(usbFile).channel
//                FileInputStream(newFile).channel.transferTo(0, newFile!!.length(), outF)
//            } catch (e: FileNotFoundException) {
//                e.printStackTrace()
//            } catch (e: IOException) {
//                e.printStackTrace()
//            }
//        }
//        deleteFile()
//        newFile?.delete()
//    }


    private fun startCapturing(
        mediaProjection: MediaProjection,
        source: ScreenRecordingAudioSource
    ) {
        val video = createVideoConfig()
        val audio = createAudioConfig(source) // audio can be null
        val format = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US)
        val fileDirectory = File(PathUtils.getExternalStoragePath() + "/Screen Record")
        if (!fileDirectory.exists()) {
            fileDirectory.mkdir()
        }
        file = File(
            fileDirectory.absolutePath,
            "ScreenRecord_" + format.format(Date())
                    + "_" + video.width + "x" + video.height + ".mp4"
        )

        Log.i(TAG, "╔═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "║ 🎬 STARTING SCREEN RECORDING")
        Log.i(TAG, "╠═══════════════════════════════════════════════════════════════")
        Log.i(TAG, "║ Output file:      ${file!!.absolutePath}")
        Log.i(TAG, "║ Video config:     ${video.width}x${video.height} @ ${video.framerate}fps, ${video.bitrate/1000}kbps")
        Log.i(TAG, "║ Audio source:     $source")
        if (audio != null) {
            Log.i(TAG, "║ Audio config:     $audio")
        } else {
            Log.w(TAG, "║ Audio config:     DISABLED (video-only recording)")
        }
        Log.i(TAG, "╚═══════════════════════════════════════════════════════════════")

//        fileName = getFormatTime(System.currentTimeMillis()) +"_"+ settings.getResolutionData()
        mRecorder = newRecorder(mediaProjection, video, audio, file!!)
        mRecorder!!.start()
    }

    private fun getFormatTime(time: Long): String? {
        val format = SimpleDateFormat("yyyyMMddHHMMSS", Locale.getDefault())
        val d1 = Date(time)
        return format.format(d1)
    }


    fun deleteFile() {
        file?.delete()
    }
//只能调用一次，多次会异常
//您的应用必须在每个媒体投影会话之前请求用户同意。答 是对 createVirtualDisplay() 的单次调用。一个 MediaProjection 令牌 只能使用一次进行调用。
//在 Android 14 或更高版本中，createVirtualDisplay() 方法会抛出 SecurityException（如果您的 应用会执行以下任一操作：
//将从 createScreenCaptureIntent() 返回的 Intent 实例多次传递到 getMediaProjection()
//对同一个 MediaProjection 多次调用 createVirtualDisplay() 实例

    private fun getOrCreateVirtualDisplay(
        mediaProjection: MediaProjection,
        config: VideoEncodeConfig
    ): VirtualDisplay {
        if (virtualDisplay == null) {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "ScreenRecorder-display",
                config.width, config.height, 1 /*dpi*/,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                null /*surface*/, null, null
            )
        } else {
            // resize if size not matched
            val size = Point()
            virtualDisplay!!.display.getSize(size)
            if (size.x != config.width || size.y != config.height) {
                virtualDisplay!!.resize(config.width, config.height, 1)
            }
        }
        return virtualDisplay!!
    }

    fun isAgan() {
        isAgan = true
    }

    fun startAgan() {
        isAgan = false
    }

    /**
     * 设置用户切换标记
     * 用户切换时调用此方法，停止录制时将跳过保存弹窗，直接保留默认路径的文件
     */
    fun setUserSwitching(switching: Boolean) {
        isUserSwitching = switching
        Log.d(TAG, "setUserSwitching: $switching")
    }

    /**
     * 设置录制停止原因
     * @param reason 停止原因：Settings.STOP_REASON_NORMAL/TIME_LIMIT/LOW_SPACE
     */
    fun setStopReason(reason: Int) {
        stopReason = reason
        Log.d(TAG, "setStopReason: $reason")
    }

    fun stopRecorder() {
        try {
            if (mRecorder != null) {
                mRecorder!!.quit()
            }
            mRecorder = null
        } catch (e: java.lang.Exception) {
            Log.e(TAG, "stopRecorder: error-->${e.printStackTrace()}")
        }finally {
            listener?.onEndRecord()
        }
    }

    private fun createAudioConfig(source: ScreenRecordingAudioSource): AudioEncodeConfig? {
        try {
            when (source) {
                ScreenRecordingAudioSource.NONE -> return null
                else -> {
                    // 增强音频编码器配置的错误处理
                    if (Utils.mAacCodecInfos.isEmpty()) {
                        Log.w(TAG, "No AAC codec available, falling back to video-only recording")
                        return null
                    }

                    // ===== 智能编解码器选择 - 修复硬编码索引[1]的致命问题 =====
                    var selectedCodec: MediaCodecInfo? = null
                    var selectedCapabilities: CodecCapabilities? = null
                    var selectedProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC // 默认使用最兼容的AAC-LC
                    var selectedSampleRate = 44100 // 默认采样率
                    var selectedChannelCount = 1 // 默认单声道

                    // 遍历所有可用的AAC编解码器，寻找最兼容的
                    for (codecInfo in Utils.mAacCodecInfos) {
                        try {
                            val caps = codecInfo.getCapabilitiesForType(AUDIO_AAC)
                            val audioCaps = caps.audioCapabilities

                            if (audioCaps == null) {
                                Log.d(TAG, "Codec ${codecInfo.name} has no audio capabilities, skipping")
                                continue
                            }

                            // 验证采样率支持
                            val supportedSampleRates = audioCaps.supportedSampleRates
                            var sampleRateSupported = false
                            var fallbackSampleRate = -1

                            if (supportedSampleRates != null && supportedSampleRates.isNotEmpty()) {
                                for (rate in supportedSampleRates) {
                                    if (rate == 44100) {
                                        sampleRateSupported = true
                                        break
                                    }
                                    // 记录第一个可用的采样率作为备选
                                    if (fallbackSampleRate == -1 && (rate == 48000 || rate == 32000 || rate == 16000)) {
                                        fallbackSampleRate = rate
                                    }
                                }
                            } else {
                                // 没有明确列表时，尝试验证44100
                                sampleRateSupported = audioCaps.isSampleRateSupported(44100)
                                if (!sampleRateSupported && audioCaps.isSampleRateSupported(48000)) {
                                    fallbackSampleRate = 48000
                                }
                            }

                            if (!sampleRateSupported && fallbackSampleRate == -1) {
                                Log.d(TAG, "Codec ${codecInfo.name} does not support 44100Hz or any fallback rate, skipping")
                                continue
                            }

                            // 验证通道数支持
                            val maxChannels = audioCaps.maxInputChannelCount
                            if (maxChannels < 1) {
                                Log.d(TAG, "Codec ${codecInfo.name} does not support mono audio (maxChannels=$maxChannels), skipping")
                                continue
                            }

                            // 查找支持的Profile (优先AAC-LC，最兼容)
                            val profileLevels = caps.profileLevels
                            var supportedProfile = -1

                            if (profileLevels != null && profileLevels.isNotEmpty()) {
                                // 优先选择AAC-LC (Low Complexity) - 硬件编解码器通常只支持这个
                                for (pl in profileLevels) {
                                    if (pl.profile == MediaCodecInfo.CodecProfileLevel.AACObjectLC) {
                                        supportedProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
                                        break
                                    }
                                }

                                // 如果没有AAC-LC，尝试其他Profile
                                if (supportedProfile == -1) {
                                    supportedProfile = profileLevels[0].profile
                                    Log.d(TAG, "Codec ${codecInfo.name} does not support AAC-LC, using profile ${supportedProfile}")
                                }
                            } else {
                                // 没有明确的Profile信息，使用AAC-LC作为默认值
                                supportedProfile = MediaCodecInfo.CodecProfileLevel.AACObjectLC
                                Log.d(TAG, "Codec ${codecInfo.name} has no profile info, defaulting to AAC-LC")
                            }

                            // 编解码器通过所有验证 - 现在进行优先级选择
                            val isHardwareCodec = codecInfo.name.startsWith("OMX.") ||
                                                 codecInfo.name.startsWith("c2.qcom") ||
                                                 codecInfo.name.startsWith("c2.mtk") ||
                                                 codecInfo.name.startsWith("c2.exynos")

                            val isSoftwareCodec = codecInfo.name.contains("google") ||
                                                 codecInfo.name.contains("sw")

                            Log.d(TAG, "Valid codec found: ${codecInfo.name} (hardware=$isHardwareCodec, profile=$supportedProfile, sampleRate=${if (sampleRateSupported) 44100 else fallbackSampleRate})")

                            // 优先选择硬件编解码器（性能更好）
                            if (isHardwareCodec && selectedCodec == null) {
                                selectedCodec = codecInfo
                                selectedCapabilities = caps
                                selectedProfile = supportedProfile
                                selectedSampleRate = if (sampleRateSupported) 44100 else fallbackSampleRate
                                selectedChannelCount = Math.min(maxChannels, 1)
                                Log.d(TAG, "Selected hardware codec: ${codecInfo.name}")
                                break // 找到硬件编解码器后直接使用
                            }

                            // 如果没找到硬件编解码器，降级到软件编解码器
                            if (isSoftwareCodec && selectedCodec == null) {
                                selectedCodec = codecInfo
                                selectedCapabilities = caps
                                selectedProfile = supportedProfile
                                selectedSampleRate = if (sampleRateSupported) 44100 else fallbackSampleRate
                                selectedChannelCount = Math.min(maxChannels, 1)
                                Log.d(TAG, "Selected software codec as fallback: ${codecInfo.name}")
                            }

                            // 如果既不是硬件也不是软件（未知类型），但是第一个可用的编解码器
                            if (selectedCodec == null) {
                                selectedCodec = codecInfo
                                selectedCapabilities = caps
                                selectedProfile = supportedProfile
                                selectedSampleRate = if (sampleRateSupported) 44100 else fallbackSampleRate
                                selectedChannelCount = Math.min(maxChannels, 1)
                                Log.d(TAG, "Selected unknown-type codec as last resort: ${codecInfo.name}")
                            }

                        } catch (e: Exception) {
                            Log.w(TAG, "Failed to check codec ${codecInfo.name}: ${e.message}")
                            continue
                        }
                    }

                    // 如果没有找到任何兼容的编解码器，降级为纯视频录制
                    if (selectedCodec == null || selectedCapabilities == null) {
                        Log.e(TAG, "No compatible AAC codec found! Falling back to video-only recording")
                        return null
                    }

                    val codec: String = selectedCodec.name
                    val bitrate: Int = Utils.resetAudioBitrateAdapter(selectedCapabilities, -1) * 1000

                    Log.i(TAG, "Final audio config: codec=$codec, profile=$selectedProfile, sampleRate=$selectedSampleRate, channels=$selectedChannelCount, bitrate=$bitrate")

                    return when (source) {
                        ScreenRecordingAudioSource.MIC -> AudioEncodeConfig(
                            codec,
                            ScreenRecorder.AUDIO_AAC,
                            bitrate,
                            selectedSampleRate,
                            selectedChannelCount,
                            selectedProfile,
                            0
                        )
                        ScreenRecordingAudioSource.MIC_AND_INTERNAL -> AudioEncodeConfig(
                            codec,
                            ScreenRecorder.AUDIO_AAC,
                            bitrate,
                            selectedSampleRate,
                            selectedChannelCount,
                            selectedProfile,
                            2
                        )
                        ScreenRecordingAudioSource.INTERNAL -> AudioEncodeConfig(
                            codec,
                            ScreenRecorder.AUDIO_AAC,
                            bitrate,
                            selectedSampleRate,
                            selectedChannelCount,
                            selectedProfile,
                            1
                        )
                        else -> null
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create audio config, falling back to video-only recording", e)
            return null
        }
    }

    private fun createVideoConfig(): VideoEncodeConfig {
        val codec: String = Utils.mAvcCodecInfos[0].getName()//选择编码器
        val capabilities: CodecCapabilities = Utils.mAvcCodecInfos[0].getCapabilitiesForType(VIDEO_AVC)
        val profile: String = Utils.resetAvcProfileLevelAdapter(capabilities,1)
        val profileLevel: CodecProfileLevel =Utils.toProfileLevel(profile)
        return VideoEncodeConfig(
            getVideoSizeWidth(), getVideoSizeHeight(), 5*getVideoSizeWidth()*getVideoSizeHeight(),
            30, 1, codec, ScreenRecorder.VIDEO_AVC,
            profileLevel
        )
    }

    /**
     * 注意:
     * 系统应用 android:sharedUserId="android.uid.system" FileProvider 做了限制分享不了给普通应用
     * 保存文件到FileCommander
     * 其实已经保存到 /storage/emulated/0/Vote 这个目录下 把Uri传到FileCommander 指定目录保存起来
     * @param path such as: /storage/emulated/0/Screen Record
     */
    fun saveFileToFileCommander(context: Context, path: String) {
        val packageManager: PackageManager = context.packageManager
        val intent = Intent()
        intent.action = Intent.ACTION_SEND
        intent.type = "*/*"
        val file = File(path)
        intent.type = "*/*"
        val activities = packageManager.queryIntentActivities(intent, 0)
        for (resolveInfo in activities) {
            if ("com.mobisystems.fileman" == resolveInfo.activityInfo.packageName) {
                val targetIntent = Intent()
                targetIntent.action = Intent.ACTION_SEND
                targetIntent.type = "*/*"
                targetIntent.setPackage(resolveInfo.activityInfo.packageName.toLowerCase(Locale.ROOT))
                targetIntent.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                targetIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val component = ComponentName(
                    resolveInfo.activityInfo.applicationInfo.packageName,
                    resolveInfo.activityInfo.name
                )
                val uri = FileProvider.getUriForFile(
                    context,
                    "com.hht.screenrecoder",
                    file
                )
                Log.d(ScreenRecordService.TAG, "saveFileToFileCommander: $uri")
                val intent1 = Intent(targetIntent)
                intent1.component = component
                intent1.putExtra(Intent.EXTRA_STREAM, uri)
                intent1.type = "*/*"
                intent1.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                intent1.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                intent1.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent1)
            }
        }
    }


    /**
     * 打开分享应用
     * @param path 文件地址
     */
    fun startShare(context: Context, path: String) {
        val packageName = "tw.com.hitevision.sharer.MULTIPLE_FILES"
        val intent = Intent()
        intent.component =
            ComponentName("tw.com.hitevision.sharer", "tw.com.hitevision.sharer.MainActivity")
        intent.action = "android.intent.action.MAIN"
        intent.putExtra(packageName, arrayListOf(path))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    /**
     * 加载音频静音填充配置
     * 通过系统属性动态配置，便于测试和调试
     */
    private fun loadAudioSilentFillConfig(): AudioSilentFillConfig {
        val config = AudioSilentFillConfig()

        // ===== 读取开关状态（最重要的配置） =====
        val enabledStr = getSystemProperty("debug.screenrecord.silent_fill_enabled", "1")
        config.isEnabled = enabledStr == "1" || enabledStr.equals("true", ignoreCase = true)
        config.isEnabled = true //开启智能静音填充

        // ===== 读取墙钟 PTS 模式（画面优先）=====
        val useWallClockStr = getSystemProperty("debug.screenrecord.use_wallclock_pts", "1")
        val useWallClock = useWallClockStr == "1" || useWallClockStr.equals("true", ignoreCase = true)

        // 设置墙钟 PTS 模式
        mRecorder?.setUseWallClockPTS(useWallClock)
        Log.i(TAG, "★★★ WALL CLOCK PTS MODE ★★★ enabled=$useWallClock (true=画面优先/允许音频丢失, false=采样数计算)")

        // 读取系统属性配置模式（使用反射访问隐藏API）
        val modeStr = getSystemProperty("debug.screenrecord.silent_mode", "1")

        val mode = when (modeStr.toIntOrNull() ?: 1) {
            1 -> AudioSilentFillConfig.SilentFillMode.LOW_AMPLITUDE_NOISE
            2 -> AudioSilentFillConfig.SilentFillMode.FIXED_LOW_VALUE
            3 -> AudioSilentFillConfig.SilentFillMode.REDUCED_SAMPLE_RATE
            4 -> AudioSilentFillConfig.SilentFillMode.ZERO_WITH_PTS_COMPENSATION
            5 -> AudioSilentFillConfig.SilentFillMode.HYBRID
            else -> AudioSilentFillConfig.SilentFillMode.LOW_AMPLITUDE_NOISE
        }
        config.mode = mode

        // 读取其他参数
        config.noiseAmplitude = getSystemProperty("debug.screenrecord.silent_amplitude", "3").toIntOrNull() ?: 3
        config.skipInterval = getSystemProperty("debug.screenrecord.silent_skip_interval", "5").toIntOrNull() ?: 5
        config.initialPeriodMs = getSystemProperty("debug.screenrecord.silent_initial_period", "10000").toLongOrNull() ?: 10000

        Log.i(TAG, "★★★ LOADED AUDIO SILENT FILL CONFIG ★★★ enabled=${config.isEnabled}, mode=$mode, amplitude=${config.noiseAmplitude}, skipInterval=${config.skipInterval}, initialPeriod=${config.initialPeriodMs}ms")
        return config
    }

    /**
     * 使用反射获取系统属性
     */
    private fun getSystemProperty(key: String, defaultValue: String): String {
        return try {
            val clazz = Class.forName("android.os.SystemProperties")
            val method = clazz.getMethod("get", String::class.java, String::class.java)
            method.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read system property $key, using default: $defaultValue", e)
            defaultValue
        }
    }

    /**
     * 设置音频静音填充模式（用于测试）
     * @param mode 填充模式
     * @param amplitude 噪声幅度（仅模式1使用）
     */
    fun setAudioSilentFillMode(mode: AudioSilentFillConfig.SilentFillMode, amplitude: Int = 3) {
        val config = AudioSilentFillConfig()
        config.mode = mode
        config.noiseAmplitude = amplitude
        mRecorder?.setAudioSilentFillConfig(config)
        Log.i(TAG, "★★★ AUDIO SILENT FILL MODE SET ★★★ mode=$mode, amplitude=$amplitude")
    }

    companion object {
        private const val VIDEO_SIZE_MAX_WIDTH_1920 = 1920
        private const val VIDEO_SIZE_MAX_HEIGHT_1080 = 1080
        private const val VIDEO_SIZE_MAX_WIDTH_1280 = 1280
        private const val VIDEO_SIZE_MAX_HEIGHT_720 = 720
        private const val VIDEO_SIZE_MAX_WIDTH_3840 = 3840
        private const val VIDEO_SIZE_MAX_HEIGHT_2160 = 2160
        private const val VIDEO_SIZE_MAX_WIDTH_960 = 960
        private const val TAG = "ScreenRecordHelper"
    }

    interface OnVideoRecordListener {
        fun onBeforeRecord()
        fun onStartRecord()
        fun onPauseRecord()
        fun onCancelRecord()
        fun onEndRecord()

        /**
         * 当检测到系统不支持内置声音录制时调用
         * @param audioType 音频类型：0=MIC, 1=INTERNAL, 2=MIC_AND_INTERNAL
         */
        fun onInternalAudioNotAvailable(audioType: Int) {
            // 默认空实现
        }
    }

}

