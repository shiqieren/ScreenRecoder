package com.hht.oemscreenrecoder.screenrecorder

//import com.hht.oemscreenrecoder.ysgscreenrecoder.ScreenRecorderHelperNew

import android.annotation.SuppressLint
import android.app.*
import android.content.*
import android.graphics.BitmapFactory
import android.graphics.PixelFormat
import android.media.AudioDeviceInfo.TYPE_USB_DEVICE
import android.media.AudioManager
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.*
import android.util.DisplayMetrics
import android.util.Log
import android.view.*
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.blankj.utilcode.util.ColorUtils
import com.hht.oemscreenrecoder.MainActivity
import com.hht.oemscreenrecoder.R
import com.hht.oemscreenrecoder.Settings
import com.hht.oemscreenrecoder.Settings.Companion.RESOLUTION_1280_720
import com.hht.oemscreenrecoder.Settings.Companion.RESOLUTION_1920_1080
import com.hht.oemscreenrecoder.Settings.Companion.RESOLUTION_3840_2160
import com.hht.oemscreenrecoder.adapter.RecycleAdapter
import com.hht.oemscreenrecoder.adapter.RecycleAdapter.ItemCLickListener
import com.hht.oemscreenrecoder.adapter.RecycleGridBean
import com.hht.oemscreenrecoder.utils.HHTTouchHelper
import com.hht.oemscreenrecoder.utils.MediaProjectionUtil
import com.hht.oemscreenrecoder.utils.OpsTouchRect
import com.hht.oemscreenrecoder.widgets.FloatingLayout
import com.hht.oemscreenrecoder.widgets.StorageUtils
import com.hht.oemscreenrecoder.widgets.UsbFlashUtil
import com.hht.oemscreenrecoder.yorm.Utils
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*


/**
 * 要求录制的视频不能有录屏的控制悬浮窗。(可以实现、需要供应商配合)
 * 录屏方案使用的是MediaRecorder、MediaProjection、VirtualDisplay。
 * Android支持多个屏幕：主显、外显、虚显，虚显就是VirtualDisplay。VirtualDisplay的使用场景很多，比如录屏，WED显示等。
 * 其作用就是抓取屏幕上显示的内容。录屏时通过加载SurfaceFlinger画布，通过加载中形成的虚显而形成的，所以我们需要在画布虚显中
 * 将悬浮窗的View图层去掉。
 * 首先需要定义悬浮窗View的名字，也就是window的title，可以通过WindowManager.LayoutParams的setTitle()设置。
 * mWindowParams.setTitle("tw.com.newline.screenrecoder");修改 frameworks/native /services/surfaceflinger
 * /SurfaceFlinger.cpp
 *
 * 注意： mediaProjectionManager.getMediaProjection(resultCode,clonedIntent)第一次录屏没有问题，但我无法第二次录屏
 * 1、Intent数据是不能重用的，解决方案也很简单，就是使用完媒体投影后不要关闭即可。
 * 2、因此：mediaProjection.stop 可以在退出Service onDestroy调用
 *
 * MediaRecorder.AudioSource.REMOTE_SUBMIX 可以实现内录功能，有两点比较麻烦 (可以实现、需要供应商配合)
 * （1）需要系统权限
 * （2）会截走扬声器和耳机的声音，也就是说再录音时本地无法播放声音对于系统权限
 *
 * 对于截走扬声器和耳机的声音问题
 * 修改 frameworks下av/services/audiopolicy/AudioPolicyManager.cpp
 *
 * 使用USB麦克风设备首先需要正常识别到该USB-AUDIO设备
 * 1、其次Android系统会自动切换MIC录音源
 * 2、如果没有插入USB麦克风设备，则Android系统使用主板上模拟MIC
 * 3、如果插入USB麦克风设备，则Android系统就会切换到USB麦克风
 *
 * 更换实现方式：
 * 1、使用AudioRecorder 录音（两个AudioRecorder一个 录麦克风、一个录 系统声音）
 * 2、MediaRecorder  录制屏幕
 * 3、MediaMuxer 将两个文件合并
 *
 * Service 注意事项：
 * service中某个方法由于空指针导致程序挂掉，接着触发程序的保活机制触发程序重启、
 * 但是这个异常service先启动访问未初始化资源导致程序连续循环重启
 * 1、出现空指针异常：service被系统kill掉，那么会重新调用onStartCommand()来重新运行
 *
 *
 * 问题：
 * .录制视频过长，保存录制文件时间慢（保存超两小时的录制文件需13分钟）
 * 发哥建议边录边合并数据，demo https://github.com/yrom/ScreenRecorder/tree/32c00541299e6ff56763e8f2254983008f03b24a
 *
 * 方案二：
 * 使用MediaProjectionManager, VirtualDisplay, AudioRecord, MediaCodec 以及 MediaMuxer 等API
 * 通过 `MediaProjectionManager` 取得的 `MediaProjection` 创建  `VirtualDisplay`，`VirtualDisplay` 会将图
 * 像渲染到 `Surface`中，而这个`Surface`是由`MediaCodec`所创建的 ，`MediaMuxer` 将从 `MediaCodec` 得到的图像元数据封装并输出到MP4文件中
 */

class ScreenRecordService : Service() {

    companion object {
        // handler case
        private var isuserSwitch: Boolean? = false
        const val UPDATE_RECORD_DURATION_UI = 1
        const val SHOW_LOW_SPACE_DIALOG = 2
        const val SHOW_STOP_RECORD_DIALOG = 3
        const val STOP_RECORD_TIME_LIMIT = 4      // 达到1小时时长限制
        const val STOP_RECORD_SPACE_LIMIT = 5     // 存储空间不足500MB
        const val TAG = "ScreenRecordService"
        const val SCREEN_RECORDER = "tw.com.newline.screenrecoder"
        val USER_ID_ACTION = "com.hht.alauncher.logout"

        // 用户切换相关广播Action
        const val ACTION_USER_SWITCHED = "android.intent.action.USER_SWITCHED"
        const val ACTION_USER_BACKGROUND = "android.intent.action.USER_BACKGROUND"
        const val ACTION_USER_FOREGROUND = "android.intent.action.USER_FOREGROUND"
    }

    private lateinit var screenRecordHelper: ScreenRecordHelper
    private val binder = MyBinder()
    private val settings: Settings by lazy {
        Settings.getInstance(this)
    }


    private var permissionData: Intent? = null
    private var usbPath: String? = null

    private lateinit var mWindowManager: WindowManager
    private lateinit var mInflater: LayoutInflater
    private lateinit var screenRecorderLayoutParams: WindowManager.LayoutParams
    private lateinit var screenRecordingLayoutParams: WindowManager.LayoutParams
    private lateinit var menuLayoutParams: WindowManager.LayoutParams
    private lateinit var screenRecordLayout: FloatingLayout
    private lateinit var screenRecordingLayout: FloatingLayout
    private lateinit var screenSaveLayout: FloatingLayout

    private var lowSpaceMenu: ConstraintLayout? = null

    // 防透传相关对象
    private var screenRecordLayoutOpsTouchRect: OpsTouchRect? = null
    private var screenRecordingLayoutOpsTouchRect: OpsTouchRect? = null
    private var lowSpaceMenuOpsTouchRect: OpsTouchRect? = null
    private var mAdapter: RecycleAdapter? = null
    private var mediaProjection: MediaProjection? = null

    private var qualityImage: ImageView? = null
    private var recordPage: ConstraintLayout? = null
    private var recordTime: TextView? = null
    private var recordTimeTwo: TextView? = null
    private var source: ScreenRecordingAudioSource? = null

    private lateinit var metrics: DisplayMetrics
    private var timer: Timer? = null
    private var imgShowTimer: Timer? = null
    private var second = 0
    private var minute = 0
    private var hour = 0
    private var diffX = 0
    private var diffY = 0

    private var diffXM = 0
    private var diffYM = 0
    private var isNeedToShowLowSpace = false
    private var isNeedToUpdateDurationTime = false
    private var screenRecordStatus = RecordStatus.Idle.ordinal

    private var fileName: String? = null

    private var userSwitchReceiver: UserSwitchReceiver? = null

    // MIC硬件是否可用
    private var isMicHardwareAvailable: Boolean = true

    enum class RecordStatus {
        Idle,
        Recording,
        Pause
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }

    override fun onUnbind(intent: Intent?): Boolean {
        return super.onUnbind(intent)
    }

    inner class MyBinder : Binder() {
        val service: ScreenRecordService
            get() = this@ScreenRecordService
    }

    /**
     * 拖动更新主页面窗口位置
     */
    private val floatingLayoutListener = object : FloatingLayout.OnFloatingLayoutListener {
        override fun notifyLayoutTouchDown(x: Int, y: Int) {
            fixBoundary(screenRecorderLayoutParams, screenRecordLayout)
            diffX = x - screenRecorderLayoutParams.x
            diffY = y - screenRecorderLayoutParams.y
            // 拖动开始时禁用旧的防透传区域
            disableAntiPenetration(screenRecordLayoutOpsTouchRect)
        }

        override fun notifyPositionUpdate(x: Int, y: Int) {
            screenRecorderLayoutParams.x = x - diffX
            screenRecorderLayoutParams.y = y - diffY
            fixBoundary(screenRecorderLayoutParams, screenRecordLayout)
            mWindowManager.updateViewLayout(screenRecordLayout, screenRecorderLayoutParams)
            // 拖动过程中不设置防透传，已通过 setOpsTouch(false) 全局禁用
        }

        override fun notifyLayoutTouchUp(x: Int, y: Int) {
            // 拖动结束后重新设置防透传区域
            screenRecordLayoutOpsTouchRect = enableAntiPenetration(screenRecordLayout, "win_title_screenrecoder_toolbar")
        }
    }

    /**
     * 拖动更新时间窗口位置
     */
    private val floatingRecordingLayoutListener = object : FloatingLayout.OnFloatingLayoutListener {
        override fun notifyLayoutTouchDown(x: Int, y: Int) {
            fixBoundary(screenRecordingLayoutParams, screenRecordingLayout)
            diffXM = x - screenRecordingLayoutParams.x
            diffYM = y - screenRecordingLayoutParams.y
            // 拖动开始时禁用旧的防透传区域
            disableAntiPenetration(screenRecordingLayoutOpsTouchRect)
        }

        override fun notifyPositionUpdate(x: Int, y: Int) {
            screenRecordingLayoutParams.x = x - diffXM
            screenRecordingLayoutParams.y = y - diffYM
            fixBoundary(screenRecordingLayoutParams, screenRecordingLayout)
            mWindowManager.updateViewLayout(screenRecordingLayout, screenRecordingLayoutParams)
            // 拖动过程中不设置防透传，已通过 setOpsTouch(false) 全局禁用
        }

        override fun notifyLayoutTouchUp(x: Int, y: Int) {
            // 拖动结束后重新设置防透传区域
            screenRecordingLayoutOpsTouchRect = enableAntiPenetration(screenRecordingLayout, SCREEN_RECORDER)
        }
    }

    private fun fixBoundary(layoutParams: WindowManager.LayoutParams, floating: FloatingLayout) {
        if (layoutParams.x > metrics.widthPixels - floating.width) {
            layoutParams.x = metrics.widthPixels - floating.width
        }
        if (layoutParams.y > metrics.heightPixels - floating.height) {
            layoutParams.y = metrics.heightPixels - floating.height
        }
        if (layoutParams.x < 0) {
            layoutParams.x = 0
        }
        if (layoutParams.y < 0) {
            layoutParams.y = 0
        }
    }

    /**
     * 分辨率弹窗
     */
    @SuppressLint("InflateParams")
    private fun initMenuQuality(it: View) {
        val view = mInflater.inflate(R.layout.setting_menu_quality, null) as ConstraintLayout?
        val micViewRadioGroup: RadioGroup = view!!.findViewById(R.id.quality_choose)
        when (settings.getResolutionData()) {
            RESOLUTION_1920_1080 -> {
                micViewRadioGroup?.check(R.id.quality_1080)
//                qualityImage!!.setImageResource(R.mipmap.ic_1080_px)
            }

            RESOLUTION_1280_720 -> {
                micViewRadioGroup.check(R.id.quality_720)
//                qualityImage!!.setImageResource(R.mipmap.ic_720_px)
            }
        }
        micViewRadioGroup.setOnCheckedChangeListener { _: RadioGroup?, checkedId: Int ->
            when (checkedId) {
                R.id.quality_1080 -> {
                    settings.saveResolutionData(RESOLUTION_1920_1080)
//                    qualityImage!!.setImageResource(R.mipmap.ic_1080_px)
                }

                R.id.quality_720 -> {
                    settings.saveResolutionData(RESOLUTION_1280_720)
//                    qualityImage!!.setImageResource(R.mipmap.ic_720_px)
                }
            }
        }
        val popupWindow = PopupWindow(view, 320, 224)  //参数为1.View 2.宽度 3.高度
        popupWindow.isOutsideTouchable = true
        popupWindow.showAsDropDown(it, -320, 8)
    }


    override fun onCreate() {
        super.onCreate()

        Log.d(TAG, "onCreate: 服务创建")

        // 注册用户切换广播接收器
        registerUserSwitchReceiver()
    }

    /**
     * 注册用户切换相关的广播接收器
     */
    private fun registerUserSwitchReceiver() {
        try {
            userSwitchReceiver = UserSwitchReceiver()
            val filter = IntentFilter().apply {
                // 系统用户切换广播
                addAction(ACTION_USER_SWITCHED)
                addAction(ACTION_USER_BACKGROUND)
                addAction(ACTION_USER_FOREGROUND)
                // 自定义的用户切换广播（兼容旧逻辑）
                addAction(USER_ID_ACTION)
            }
            registerReceiver(userSwitchReceiver, filter)
            Log.d(TAG, "registerUserSwitchReceiver: 用户切换广播注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "registerUserSwitchReceiver: 注册广播失败", e)
        }
    }

    /**
     * 注销用户切换广播接收器
     */
    private fun unregisterUserSwitchReceiver() {
        try {
            userSwitchReceiver?.let {
                unregisterReceiver(it)
                Log.d(TAG, "unregisterUserSwitchReceiver: 用户切换广播注销成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unregisterUserSwitchReceiver: 注销广播失败", e)
        }
        userSwitchReceiver = null
    }
    @SuppressLint("InflateParams")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createNotificationChannel()
        Utils.init()
        fileName = getFormatTime(System.currentTimeMillis())
        val mResultCode = intent?.extras?.getInt("code")
        if (permissionData == null && mResultCode == AppCompatActivity.RESULT_OK) {
            permissionData = intent?.getParcelableExtra("data")
            usbPath = intent?.getStringExtra("usbPath")
            Log.d(TAG, "onStartCommand: -----" + usbPath)
        }

        permissionData = intent?.getParcelableExtra<Intent>("data")
        val mediaProjectionManager =
            getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

        if (permissionData == null) {
            Log.d("Hogan", "permissionData  初始化为空，重新初始化")
            permissionData = mediaProjectionManager.createScreenCaptureIntent()
        } else {
            Log.d("Hogan", "permissionData  初始化成功")
        }
        mediaProjection = mediaProjectionManager?.getMediaProjection(
            Activity.RESULT_OK, permissionData!!
        )
        if (mediaProjection == null) {
            Log.d("Hogan", "mediaProjection  初始化为空，重新初始化")
            mediaProjection = MediaProjectionUtil.getInstance().getMediaProjection(this.applicationContext)
        } else {
            Log.d("Hogan", "mediaProjection  初始化成功")
        }

        handler.postDelayed({
            initScreenRecordHelper()
        }, 100)
        metrics = this.resources.displayMetrics
        mWindowManager = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        mInflater = LayoutInflater.from(this)

        screenRecordLayout =
            mInflater.inflate(R.layout.screen_record_tool_new, null) as FloatingLayout
        screenRecordingLayout =
            mInflater.inflate(R.layout.layout_retract, null) as FloatingLayout
        screenSaveLayout =
            mInflater.inflate(R.layout.layout_save_load, null) as FloatingLayout

        screenRecordLayout.registerListener(floatingLayoutListener)
        screenRecordLayout.setScreenSize(metrics.widthPixels, metrics.heightPixels)

        screenRecordingLayout.registerListener(floatingRecordingLayoutListener)
        screenRecordingLayout.setScreenSize(metrics.widthPixels, metrics.heightPixels)

//        qualityImage = screenRecordLayout.findViewById(R.id.imv_quality) as ImageView
//        qualityImage?.apply {
//            if (settings.getResolutionData() == RESOLUTION_1920_1080) {
//                setImageResource(R.mipmap.ic_1080_px)
//            } else {
//                setImageResource(R.mipmap.ic_720_px)
//            }
//        }

//        val qualityView: ImageView = screenRecordLayout.findViewById(R.id.imv_arrow_down_qua)
//        qualityView.setOnClickListener {
//            initMenuQuality(it)
//        }

        val audioManager = this.getSystemService(AUDIO_SERVICE) as AudioManager

//        val micPhone: ImageView = screenRecordLayout.findViewById(R.id.imv_mic_phone)

        //判断是否插上USB MIC
        try {
            var isUsbMic = false
            for (info in audioManager.microphones) {
                Log.d("mzfss", "onCreate: " + info.description + "-->type-->" + info.type)
                if (info.type == TYPE_USB_DEVICE) {
                    isUsbMic = true
                }
            }
//            micPhone.isEnabled = isUsbMic
//            if (!isUsbMic) micPhone.setImageResource(R.drawable.ic_ic_mic_dis)
        } catch (e: IOException) {
            e.printStackTrace()
        }

//        micPhone.setOnClickListener {
//            if (it.isSelected) {
//                it.isSelected = false
//                settings.setMic(false)
//                micPhone.setImageResource(R.drawable.ic_ic_mic_off)
//            } else {
//                it.isSelected = true
//                settings.setMic(true)
//                micPhone.setImageResource(R.drawable.ic_ic_mic_on)
//            }
//        }

//        val audioSystem: ImageView = screenRecordLayout.findViewById(R.id.imv_system_audio)
//        audioSystem.setOnClickListener {
////            micPhone.isEnabled = true
//            if (it.isSelected) {
//                it.isSelected = false
//                settings.setSystemAudio(false)
//            } else {
//                it.isSelected = true
//                settings.setSystemAudio(true)
//            }
//        }
//        audioSystem.isSelected = settings.getSystemAudio()


        val recView: Button = screenRecordLayout.findViewById(R.id.ic_start)//开始录制
        val unstartView: Button = screenRecordLayout.findViewById(R.id.ic_unstart)//无法录制
        val setTingView: Button = screenRecordLayout.findViewById(R.id.ic_setting)//设置
        val closeView: Button = screenRecordLayout.findViewById(R.id.ic_recorder_exit)//退出
        val movableView: Button = screenRecordLayout.findViewById(R.id.ic_movable)//左侧收起菜单栏

        val recorderStopView: Button = screenRecordLayout.findViewById(R.id.ic_recorder_stop)//停止录制
        val suspendView: Button = screenRecordLayout.findViewById(R.id.ic_suspend)//暂停录制
        val continueView: Button = screenRecordLayout.findViewById(R.id.ic_continue)//重新开始录制
//        val againView: Button = screenRecordLayout.findViewById(R.id.recorder_again)//重新录制

        recorderStopView?.visibility = View.GONE
        suspendView?.visibility = View.GONE
        continueView?.visibility = View.GONE
//        againView?.visibility = View.GONE

        if (!StorageUtils.checkFreeSpace()) {
//            showFreeSpaceMenu()
            unstartView?.visibility = View.VISIBLE
            recView?.visibility = View.GONE
        }
        //因为内存不够,按钮颜色为暗色,点击出现弹窗
        unstartView.setOnClickListener {
            lowSpaceMenu = null
            showFreeSpaceMenu()
        }
        // 关键修复：增加硬件级MIC检测
        isMicHardwareAvailable = checkMicrophoneHardware()

        // 如果MIC硬件不可用，自动切换到System Audio
        if (!isMicHardwareAvailable) {
            Log.w(TAG, "MIC hardware not available, switching to System Audio only")
            settings.setMic(false)
            settings.setSystemAudio(true)
        }

        //点击录制按钮
        recView.setOnClickListener {
            recordTime = screenRecordLayout.findViewById(R.id.record_time)
            recordTime?.visibility = View.VISIBLE
            recView?.visibility = View.GONE
            setTingView?.visibility = View.GONE
//            closeView?.visibility = View.GONE

            recorderStopView?.visibility = View.VISIBLE
            suspendView?.visibility = View.VISIBLE
//            againView?.visibility = View.VISIBLE

            if (!settings.getMic() && !settings.getSystemAudio()) { //没有麦克风、没有声音
                source = ScreenRecordingAudioSource.NONE
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.NONE)
            } else if (!settings.getMic() && settings.getSystemAudio()) { //没有麦克风、有声音
                source = ScreenRecordingAudioSource.INTERNAL
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.INTERNAL)
            } else if (settings.getMic() && settings.getSystemAudio()) { //有麦克风、有声音
                source = ScreenRecordingAudioSource.MIC_AND_INTERNAL
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.MIC_AND_INTERNAL)
            } else if (settings.getMic() && !settings.getSystemAudio()) { //有麦克风、没有声音
                source = ScreenRecordingAudioSource.MIC
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.MIC)
            }
            isNeedToShowLowSpace = true
            screenRecordStatus = RecordStatus.Recording.ordinal
            setSystemProperty("sys.skg.recorder.state", "recording")
            val recorderState = getSystemProperty("sys.skg.recorder.state", "default_state")
            Log.d(TAG, "onStartCommand: recording++++开始录屏----" + recorderState)

            Log.d(TAG, "onStartCommand: $source")
        }
//        recordTime = screenRecordingLayout.findViewById(R.id.record_time)
//        val stopView: ImageView = screenRecordingLayout.findViewById(R.id.imv_recording)
        //停止录制按钮事件
        recorderStopView.setOnClickListener {
            Log.d(TAG, "onStartCommand: recorderStopView.setOnClickListener ")
            lowSpaceMenu = null
            stopTimer()
            stopImgShowTimer()
            recordTime?.visibility = View.GONE
            recView?.visibility = View.VISIBLE
            setTingView?.visibility = View.VISIBLE
            closeView?.visibility = View.VISIBLE

            suspendView?.visibility = View.GONE
            continueView?.visibility = View.GONE
//            againView?.visibility = View.GONE
            recorderStopView?.visibility = View.GONE

            screenRecordHelper.apply {
                if (screenRecordStatus == RecordStatus.Recording.ordinal || screenRecordStatus == RecordStatus.Pause.ordinal) {
                    Log.d(TAG, "onStartCommand: stopView-->onCLick")
                    screenRecordHelper.startAgan()
//                    screenRecordHelper.unUserSwaitch()
                    stopRecorder()
//                    mWindowManager.removeView(screenRecordingLayout)
//                    mWindowManager.addView(screenRecordLayout, screenRecorderLayoutParams)
                    screenRecordStatus = RecordStatus.Idle.ordinal
                }
            }
            setSystemProperty("sys.skg.recorder.state", "prepare")
            val recorderState = getSystemProperty("sys.skg.recorder.state", "default_state")
            Log.d(TAG, "onStartCommand: recording++++停止录屏----" + recorderState)
//            screenRecordHelper?.apply {
//                if (screenRecordStatus == RecordStatus.Recording.ordinal || screenRecordStatus == RecordStatus.Pause.ordinal) {
//                    Log.d(TAG, "onStartCommand: stopView-->onCLick")
//                    stopRecord()
//                    screenRecordStatus = RecordStatus.Idle.ordinal
//                }
//                Log.d(TAG, "onStartCommand: screenRecordHelper not null ")
//                saveFile(true, source!!, { str ->
//                    Log.d(TAG, "saveFile: Str = $str")
//                }, { endCode ->
//                    lowSpaceMenu = null
//                        var hander = Handler(Looper.getMainLooper())
//                        hander.post {
//                            saveDialog(endCode,setTingView,closeView,recView,movableView)
//                        }
//                    Log.d(TAG, "saveFile: Bool = $endCode")
//                });
//                if (source == ScreenRecordingAudioSource.NONE || source == ScreenRecordingAudioSource.MIC) {
//                    saveDialog(2,setTingView,closeView,recView,movableView)
//                }
//            }
        }

        //暂停录制
        suspendView.setOnClickListener {
            continueView?.visibility = View.VISIBLE
            suspendView?.visibility = View.GONE
            stopTimer()
            stopImgShowTimer()
            screenRecordHelper?.pause()
            screenRecordStatus = RecordStatus.Pause.ordinal
        }
        //结束暂停
        continueView.setOnClickListener {
            continueView?.visibility = View.GONE
            suspendView?.visibility = View.VISIBLE
            screenRecordHelper?.resume()
            screenRecordStatus = RecordStatus.Recording.ordinal
        }
        //重新录制
      /*  againView.setOnClickListener {

            if (screenRecordStatus == RecordStatus.Recording.ordinal || screenRecordStatus == RecordStatus.Pause.ordinal) {

                screenRecordHelper.isAgan()
                Log.d(TAG, "onStartCommand: stopView-->onCLick")
                screenRecordHelper.stopRecorder()
//                screenRecordHelper.unUserSwaitch()
                screenRecordHelper.deleteFile()
                stopImgShowTimer()
                stopTimer()
                screenRecordStatus = RecordStatus.Idle.ordinal
            }
//            screenRecordHelper.startAgan()
            if (!settings.getMic() && !settings.getSystemAudio()) { //没有麦克风、没有声音
                source = ScreenRecordingAudioSource.NONE
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.NONE)
                Log.d(TAG, "onStartCommand:----- " + "click-mute")
            } else if (!settings.getMic() && settings.getSystemAudio()) { //没有麦克风、有声音
                source = ScreenRecordingAudioSource.INTERNAL
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.INTERNAL)
                Log.d(TAG, "onStartCommand:----- " + "click-Audio or no click")
            } else if (settings.getMic() && settings.getSystemAudio()) { //有麦克风、有声音
                source = ScreenRecordingAudioSource.MIC_AND_INTERNAL
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.MIC_AND_INTERNAL)
                Log.d(TAG, "onStartCommand:----- " + "click-Audio and mic")
            } else if (settings.getMic() && !settings.getSystemAudio()) { //有麦克风、没有声音
                source = ScreenRecordingAudioSource.MIC
                screenRecordHelper.startRecord(ScreenRecordingAudioSource.MIC)
                Log.d(TAG, "onStartCommand:----- " + "click-mic")
            }
            isNeedToShowLowSpace = true
            screenRecordStatus = RecordStatus.Recording.ordinal
            Log.d(TAG, "onStartCommand: $source")
            setSystemProperty("sys.skg.recorder.state", "recording")
            val recorderState = getSystemProperty("sys.skg.recorder.state", "default_state")
            Log.d(TAG, "onStartCommand: recording++++开始录屏--重新录制---" + recorderState)
            continueView?.visibility = View.GONE
            suspendView?.visibility = View.VISIBLE
        }*/
//        recordPage = screenRecordLayout.findViewById(R.id.record_layout)

        //退出按钮点击事件
        closeView.setOnClickListener {
            if (screenRecordStatus != RecordStatus.Recording.ordinal && screenRecordStatus != RecordStatus.Pause.ordinal) {
                lowSpaceMenu = null
                exitDialog()
            }
        }

        //设置按钮点击事件
        setTingView.setOnClickListener {
            lowSpaceMenu = null
            setTingDialog()
        }

        //收起菜单栏点击事件
        movableView.setOnClickListener {
            try {
                // 禁用主工具栏防透传
                disableAntiPenetration(screenRecordLayoutOpsTouchRect)
                screenRecordLayoutOpsTouchRect = null
                mWindowManager.removeView(screenRecordLayout)
                mWindowManager.addView(screenRecordingLayout, screenRecordingLayoutParams)
                // 启用收起状态窗口防透传
                screenRecordingLayoutOpsTouchRect = enableAntiPenetration(screenRecordingLayout, SCREEN_RECORDER)
            } catch (e: IllegalArgumentException) {
                Log.e(TAG, "onStartCommand: $e")
            }

            val whiteImg: ImageView = screenRecordingLayout.findViewById(R.id.white_imap_id)
            val redImg: ImageView = screenRecordingLayout.findViewById(R.id.red_imap_id)
            val blueImg: ImageView = screenRecordingLayout.findViewById(R.id.blue_imap_id)
            recordTimeTwo = screenRecordingLayout.findViewById(R.id.record_time)

            screenRecordingLayout.setBackgroundResource(R.drawable.retract_bg)

            if (screenRecordStatus == RecordStatus.Recording.ordinal) {
                Handler().postDelayed({
                    screenRecordingLayout.setBackgroundResource(R.drawable.alpha_50_bg)
                }, 5000)

                Handler().postDelayed({
                    screenRecordingLayout.setBackgroundResource(R.drawable.alpha_20_bg)
                }, 10000)

                imgFlicker(whiteImg, redImg, blueImg)
            }
        }

        //缩略按钮点击事件
        screenRecordingLayout.setOnClickListener {
            stopImgShowTimer()
            // 禁用收起状态窗口防透传
            disableAntiPenetration(screenRecordingLayoutOpsTouchRect)
            screenRecordingLayoutOpsTouchRect = null
            mWindowManager.removeView(screenRecordingLayout)
            mWindowManager.addView(screenRecordLayout, screenRecorderLayoutParams)
            // 启用主工具栏防透传
            screenRecordLayoutOpsTouchRect = enableAntiPenetration(screenRecordLayout, "win_title_screenrecoder_toolbar")
        }

        initLayoutParameter()
        mWindowManager.addView(screenRecordLayout, screenRecorderLayoutParams)
        // 启用主工具栏防透传
        screenRecordLayoutOpsTouchRect = enableAntiPenetration(screenRecordLayout, "win_title_screenrecoder_toolbar")
        return START_NOT_STICKY
    }

    /**
     * 硬件级麦克风检测（第一层检测）
     *
     * 检测策略：
     * 1. 使用AudioManager检测音频输入设备
     * 2. 检查RECORD_AUDIO权限
     * 3. 检测包管理器中的麦克风特性
     *
     * 注意：硬件存在不代表有真实音频，仍需运行时检测
     */
    private fun checkMicrophoneHardware(): Boolean {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as? AudioManager
            if (audioManager == null) {
                Log.e(TAG, "checkMicrophoneHardware: AudioManager not available")
                return false
            }

            // 检查1：PackageManager特性检测
            val hasFeature = packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_MICROPHONE)
            Log.d(TAG, "checkMicrophoneHardware: FEATURE_MICROPHONE = $hasFeature")

            // 检查2：权限检测
            val hasPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED
            } else {
                true
            }
            Log.d(TAG, "checkMicrophoneHardware: RECORD_AUDIO permission = $hasPermission")

            // 检查3：AudioManager设备检测（API 23+）
            var hasInputDevice = false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val devices = audioManager.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS)
                hasInputDevice = devices.any { device ->
                    val type = device.type
                    // 检测常见的音频输入设备类型
                    val isValidInput = type == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC ||
                            type == android.media.AudioDeviceInfo.TYPE_WIRED_HEADSET ||
                            type == android.media.AudioDeviceInfo.TYPE_USB_DEVICE ||
                            type == android.media.AudioDeviceInfo.TYPE_USB_HEADSET ||
                            type == android.media.AudioDeviceInfo.TYPE_BLUETOOTH_SCO

                    if (isValidInput) {
                        Log.d(TAG, "checkMicrophoneHardware: Found input device - type=$type, productName=${device.productName}")
                    }
                    isValidInput
                }
            } else {
                // API < 23，假定有输入设备
                hasInputDevice = true
            }
            Log.d(TAG, "checkMicrophoneHardware: hasInputDevice = $hasInputDevice")

            // 综合判断
            val result = hasFeature && hasPermission && hasInputDevice

            Log.i(TAG, "checkMicrophoneHardware: Final result = $result " +
                    "(feature=$hasFeature, permission=$hasPermission, device=$hasInputDevice)")

            return result

        } catch (e: Exception) {
            Log.e(TAG, "checkMicrophoneHardware: Exception - ${e.message}")
            e.printStackTrace()
            // 出错时保守处理，返回true让运行时检测来判断
            return true
        }
    }



    /**
     * 此地方无作用
     * 适配后台应用
     */
    private fun createNotificationChannel() {
        val builder = Notification.Builder(this.applicationContext)
        val nfIntent = Intent(this, MainActivity::class.java)
        builder.setContentIntent(PendingIntent.getActivity(this, 0, nfIntent, PendingIntent.FLAG_IMMUTABLE))
            .setLargeIcon(BitmapFactory.decodeResource(this.resources, R.mipmap.ic_launcher))
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentText("is running......")
            .setWhen(System.currentTimeMillis())

        builder.setChannelId("notification_id")
        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            "notification_id",
            "notification_name",
            NotificationManager.IMPORTANCE_LOW
        )
        notificationManager.createNotificationChannel(channel)
        val notification = builder.build()
        notification.defaults = Notification.DEFAULT_SOUND
        startForeground(110, notification)
    }

    private fun initLayoutParameter() {
        screenRecorderLayoutParams = WindowManager.LayoutParams()
        screenRecorderLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        screenRecorderLayoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT

        screenRecorderLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        screenRecorderLayoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        screenRecorderLayoutParams.format = PixelFormat.RGBA_8888
        screenRecorderLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
        screenRecorderLayoutParams.x = (metrics.widthPixels * 1 / 2)
        screenRecorderLayoutParams.y = (metrics.heightPixels * 1 / 3)
        screenRecorderLayoutParams.title = "win_title_screenrecoder_toolbar"

        screenRecordingLayoutParams = WindowManager.LayoutParams()
        screenRecordingLayoutParams.height = WindowManager.LayoutParams.WRAP_CONTENT
        screenRecordingLayoutParams.width = WindowManager.LayoutParams.WRAP_CONTENT

        screenRecordingLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY

        screenRecordingLayoutParams.flags =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
        screenRecordingLayoutParams.format = PixelFormat.RGBA_8888
        screenRecordingLayoutParams.gravity = Gravity.TOP or Gravity.LEFT
        screenRecordingLayoutParams.x = 260
        screenRecordingLayoutParams.y = metrics.heightPixels - 540
        screenRecordingLayoutParams.title = SCREEN_RECORDER


        menuLayoutParams = WindowManager.LayoutParams()
        menuLayoutParams.height = WindowManager.LayoutParams.MATCH_PARENT
        menuLayoutParams.width = WindowManager.LayoutParams.MATCH_PARENT
        menuLayoutParams.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        menuLayoutParams.format = PixelFormat.RGBA_8888
        menuLayoutParams.title = "win_title_screenrecoder_menu"
    }

    private fun  initScreenRecordHelper() {
        screenRecordHelper = ScreenRecordHelper(
            this, object :
                ScreenRecordHelper.OnVideoRecordListener {
                override fun onBeforeRecord() {
                    Log.d(TAG, "onBeforeRecord")
                }

                override fun onStartRecord() {
//                    Log.d(TAG, "onStartRecord" + "重新开始录制了重新重新开始录制了重新重新开始录制了重新")
                    isNeedToUpdateDurationTime = true
                    initTimer()
                }

                override fun onPauseRecord() {
                    Log.d(TAG, "onPauseRecord")
                    isNeedToUpdateDurationTime = false
                }

                override fun onCancelRecord() {
                    Log.d(TAG, "onCancelRecord")
                    // 录制取消或失败，重置UI状态
                    isNeedToUpdateDurationTime = false
                    reSetDurationTime()
                    // 重置UI到初始状态
                    var handler = Handler(Looper.getMainLooper())
                    handler.post {
                        // 通过findViewById重新获取UI元素引用
                        val recView: Button = screenRecordLayout.findViewById(R.id.ic_start)
                        val setTingView: Button = screenRecordLayout.findViewById(R.id.ic_setting)
                        val recorderStopView: Button = screenRecordLayout.findViewById(R.id.ic_recorder_stop)
                        val suspendView: Button = screenRecordLayout.findViewById(R.id.ic_suspend)
                        val continueView: Button = screenRecordLayout.findViewById(R.id.ic_continue)

                        recordTime?.visibility = View.GONE
                        recView.visibility = View.VISIBLE
                        setTingView.visibility = View.VISIBLE
                        recorderStopView.visibility = View.GONE
                        suspendView.visibility = View.GONE
                        continueView.visibility = View.GONE
                        screenRecordStatus = RecordStatus.Idle.ordinal
                    }
                }

                override fun onEndRecord() {
                    Log.d(TAG, "onEndRecord")
                    isNeedToUpdateDurationTime = false
                    reSetDurationTime()
                }

                override fun onInternalAudioNotAvailable(audioType: Int) {
                    Log.w(TAG, "★★★ ScreenRecordService: Audio not available (audioType=$audioType), showing Toast ★★★")
                    // 在主线程显示Toast提示用户
                    Handler(Looper.getMainLooper()).post {
                        val message = when (audioType) {
                            0 -> "麦克风无声音数据，已切换为无声音录制"  // MIC模式
                            1 -> "系统内置声音无数据，已切换为无声音录制"  // INTERNAL模式
                            2 -> "音频源无有效数据，已自动降级录制"  // MIC_AND_INTERNAL模式（智能降级）
                            else -> "音频录制异常，已切换为无声音录制"
                        }
                        Toast.makeText(
                            this@ScreenRecordService,
                            message,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
            },
            mediaProjection
        )
    }

    private fun stopTimer() {
        timer?.cancel()
    }

    private fun stopImgShowTimer() {
        imgShowTimer?.cancel()
    }

    private fun reSetDurationTime() {
        minute = 0
        second = 0
        hour = 0
        updateTextDuration()
    }

    var showIndex = 1
    private fun imgFlicker(whiteImg: ImageView, redImg: ImageView, blueImg: ImageView) {
        val task: TimerTask = object : TimerTask() {
            override fun run() {
                var hander = Handler(Looper.getMainLooper())
                hander.post {
                    whiteImg.visibility = View.GONE
                    redImg.visibility = View.GONE
                    blueImg.visibility = View.GONE
                    val index = showIndex % 3
                    when (index) {
                        1 -> whiteImg.visibility = View.VISIBLE
                        2 -> redImg.visibility = View.VISIBLE
                        0 -> blueImg.visibility = View.VISIBLE
                    }
                    showIndex++
                    if (showIndex > 3) {
                        showIndex %= 3
                    }
                }
            }
        }
        imgShowTimer = Timer()
        imgShowTimer?.schedule(task, 3000, 200)
    }

    /**
     * 初始化录制时间TimerTask
     */
    private fun initTimer() {
        val timerTask: TimerTask = object : TimerTask() {
            override fun run() {
                if (isNeedToUpdateDurationTime) {
                    second++
                    if (second >= 60) {
                        second = 0
                        minute++
                        if (minute >= 60) {
                            minute = 0
                            hour++
                        }
                    }
                    Log.d(TAG, "run: second+minute+hour------$second+$minute+$hour")
                    handler.sendEmptyMessage(UPDATE_RECORD_DURATION_UI)

                    // 计算当前录制总秒数
                    val totalSeconds = hour * 3600 + minute * 60 + second

                    // 检查是否达到1小时时长限制
                    if (totalSeconds >= Settings.MAX_RECORD_DURATION_SECONDS) {
                        Log.w(TAG, "达到最大录制时长限制（1小时），自动停止录制")
                        handler.sendEmptyMessage(STOP_RECORD_TIME_LIMIT)
                        return
                    }

                    // 检查存储空间
                    val freeSpace = settings.getRemainSpace()

                    // 检查是否低于500MB阈值，直接停止录制
                    if (freeSpace < Settings.STOP_RECORD_SPACE_THRESHOLD) {
                        Log.w(TAG, "存储空间不足500MB（当前：${freeSpace}MB），自动停止录制")
                        handler.sendEmptyMessage(STOP_RECORD_SPACE_LIMIT)
                        return
                    }

                    // 低于1GB时显示警告（但不停止）
                    if (freeSpace < Settings.LOW_SPACE_STANDARD) {
                        if (isNeedToShowLowSpace && freeSpace >= Settings.STOP_RECORD_SPACE_THRESHOLD) {
                            handler.sendEmptyMessage(SHOW_LOW_SPACE_DIALOG)
                            isNeedToShowLowSpace = false
                        }
                    }
                }
                settings.setRunningState(true)
            }
        }
        timer = Timer()
        timer?.schedule(timerTask, 1000, 1000)
    }


    private val handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                UPDATE_RECORD_DURATION_UI -> {
                    updateTextDuration()
                }

                SHOW_LOW_SPACE_DIALOG -> {
                    stopTimer()
                    stopImgShowTimer()
                    // 禁用收起状态窗口防透传
                    disableAntiPenetration(screenRecordingLayoutOpsTouchRect)
                    screenRecordingLayoutOpsTouchRect = null
                    mWindowManager.removeView(screenRecordingLayout)
                    screenRecordStatus = RecordStatus.Idle.ordinal
                    screenRecordHelper.stopRecorder()
                    showLowSpaceMenu()
                }

                SHOW_STOP_RECORD_DIALOG -> {
                    if (screenRecordStatus == RecordStatus.Recording.ordinal || screenRecordStatus == RecordStatus.Pause.ordinal) {
                    }
                }

                STOP_RECORD_TIME_LIMIT -> {
                    // 达到1小时时长限制，自动停止录制并弹出保存界面
                    Log.d(TAG, "handleMessage: STOP_RECORD_TIME_LIMIT - 达到时长限制")
                    stopRecordAndShowSaveDialog(Settings.STOP_REASON_TIME_LIMIT)
                }

                STOP_RECORD_SPACE_LIMIT -> {
                    // 存储空间不足500MB，自动停止录制并弹出保存界面
                    Log.d(TAG, "handleMessage: STOP_RECORD_SPACE_LIMIT - 存储空间不足")
                    stopRecordAndShowSaveDialog(Settings.STOP_REASON_LOW_SPACE)
                }
            }
            super.handleMessage(msg)
        }
    }

    /**
     * 停止录制并显示保存对话框
     * @param stopReason 停止原因：Settings.STOP_REASON_TIME_LIMIT 或 Settings.STOP_REASON_LOW_SPACE
     */
    private fun stopRecordAndShowSaveDialog(stopReason: Int) {
        // 1. 停止计时器
        stopTimer()
        stopImgShowTimer()
        isNeedToUpdateDurationTime = false

        // 2. 重置UI状态
        val recView: Button = screenRecordLayout.findViewById(R.id.ic_start)
        val setTingView: Button = screenRecordLayout.findViewById(R.id.ic_setting)
        val closeView: Button = screenRecordLayout.findViewById(R.id.ic_recorder_exit)
        val recorderStopView: Button = screenRecordLayout.findViewById(R.id.ic_recorder_stop)
        val suspendView: Button = screenRecordLayout.findViewById(R.id.ic_suspend)
        val continueView: Button = screenRecordLayout.findViewById(R.id.ic_continue)

        recordTime?.visibility = View.GONE
        recView.visibility = View.VISIBLE
        setTingView.visibility = View.VISIBLE
        closeView.visibility = View.VISIBLE
        recorderStopView.visibility = View.GONE
        suspendView.visibility = View.GONE
        continueView.visibility = View.GONE

        // 3. 更新录制状态
        screenRecordStatus = RecordStatus.Idle.ordinal

        // 4. 设置停止原因标记，让 ScreenRecordHelper 传递给 SaveActivity
        screenRecordHelper.setStopReason(stopReason)

        // 5. 停止录制（这会触发 SaveActivity）
        screenRecordHelper.stopRecorder()

        // 6. 重置时间显示
        reSetDurationTime()

        // 7. 设置系统属性
        setSystemProperty("sys.skg.recorder.state", "prepare")
        Log.d(TAG, "stopRecordAndShowSaveDialog: 录制已停止，原因=$stopReason")
    }


    /**
     * 更新时间变化
     */
    private fun updateTextDuration() {
        val stringMaxCount: String = this.resources.getString(R.string.record_minute_time)
        val textMaxCount = String.format(stringMaxCount, hour, minute, second)
        recordTime?.text = textMaxCount
        recordTimeTwo?.text = textMaxCount
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: 服务销毁开始，isuserSwitch=$isuserSwitch")

        // 1. 注销用户切换广播接收器
        unregisterUserSwitchReceiver()

        // 2. 停止定时器
        stopTimer()
        stopImgShowTimer()

        // 3. 清理所有防透传设置
        disableAntiPenetration(screenRecordLayoutOpsTouchRect)
        disableAntiPenetration(screenRecordingLayoutOpsTouchRect)
        disableAntiPenetration(lowSpaceMenuOpsTouchRect)
        screenRecordLayoutOpsTouchRect = null
        screenRecordingLayoutOpsTouchRect = null
        lowSpaceMenuOpsTouchRect = null

        // 4. 停止前台服务
        stopForeground(true)

        // 5. 清理录制相关资源
        if (::screenRecordHelper.isInitialized) {
            screenRecordHelper.clearAll()
            screenRecordHelper.stopRecorder()
            screenRecordHelper.startAgan()
        }

        // 6. 重置运行状态
        settings.setRunningState(false)

        // 7. 设置系统属性
        setSystemProperty("sys.skg.recorder.state", "idle")
        val recorderState = getSystemProperty("sys.skg.recorder.state", "default_state")
        Log.d(TAG, "onDestroy: 录屏应用关闭，状态=$recorderState")

        super.onDestroy()
    }

    /**
     * 设置弹窗
     */
    private fun setTingDialog() {
        if (lowSpaceMenu == null) {
            lowSpaceMenu = mInflater.inflate(R.layout.setting_layout, null) as ConstraintLayout?
        }
        val mic_popup = lowSpaceMenu?.findViewById(R.id.mic_popup) as ImageView
        val imv_exit = lowSpaceMenu?.findViewById(R.id.imv_exit) as ImageView
        val mic_text_id = lowSpaceMenu?.findViewById(R.id.mic_text_id) as TextView
        val linear_mic = lowSpaceMenu?.findViewById(R.id.linear_mic) as LinearLayout

        val resolution_popup = lowSpaceMenu?.findViewById(R.id.resolution_popup) as ImageView
        val resolution_text = lowSpaceMenu?.findViewById(R.id.resolution_text) as TextView
        val linear_resolution = lowSpaceMenu?.findViewById(R.id.linear_resolution) as LinearLayout
        resolution_text.setText(settings.getResolutionData())

        val params = WindowManager.LayoutParams()
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.height = 968
        params.width = 829
        params.gravity = Gravity.CENTER
        params.format = PixelFormat.RGBA_8888
        params.flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        mWindowManager.addView(lowSpaceMenu, params)
        // 启用设置菜单防透传
        lowSpaceMenu?.let {
            lowSpaceMenuOpsTouchRect = enableAntiPenetration(it, "SettingMenu")
        }

        imv_exit.setOnClickListener {
            // 禁用菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
        }

        if (!settings.getMic() && !settings.getSystemAudio()) { //没有麦克风、没有声音
            mic_text_id.setText(R.string.mute)
        } else if (!settings.getMic() && settings.getSystemAudio()) { //没有麦克风、有声音
            mic_text_id.setText(R.string.system_auto)
        } else if (settings.getMic() && settings.getSystemAudio()) { //有麦克风、有声音
            mic_text_id.setText(R.string.micAndSystem)
        } else if (settings.getMic() && !settings.getSystemAudio()) { //有麦克风、没有声音
            mic_text_id.setText(R.string.built_mic)
        }

        mic_popup.setOnClickListener {
            showMicPopupMenu(mic_popup, mic_text_id, linear_mic)
        }

        resolution_popup.setOnClickListener {
            showResolutionPopupMenu(resolution_popup, resolution_text, linear_resolution)
        }
    }

    /**
     * 退出弹窗
     */
    private fun exitDialog() {
        if (lowSpaceMenu == null) {
            lowSpaceMenu = mInflater.inflate(R.layout.exit_layout, null) as ConstraintLayout?
        }
        val cancel = lowSpaceMenu?.findViewById(R.id.cancel) as TextView
        val exit = lowSpaceMenu?.findViewById(R.id.exit) as TextView

        cancel.setTextColor(ColorUtils.getColor(R.color.button_normal_color))
        cancel.setBackgroundColor(ColorUtils.getColor(R.color.colorWhite))

        exit.setTextColor(ColorUtils.getColor(R.color.button_normal_color))
        exit.setBackgroundColor(ColorUtils.getColor(R.color.colorWhite))

        val params = WindowManager.LayoutParams()
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.height = 528
        params.width = 1188
        params.gravity = Gravity.CENTER
        params.format = PixelFormat.RGBA_8888
        params.flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        mWindowManager.addView(lowSpaceMenu, params)
        // 启用退出菜单防透传
        lowSpaceMenu?.let {
            lowSpaceMenuOpsTouchRect = enableAntiPenetration(it, "ExitMenu")
        }

        cancel.setOnClickListener {
            cancel.setTextColor(ColorUtils.getColor(R.color.exit_color))
            cancel.setBackgroundColor(ColorUtils.getColor(R.color.button_disable_color))
            // 禁用菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
        }

        exit.setOnClickListener {
            exit.setTextColor(ColorUtils.getColor(R.color.exit_color))
            exit.setBackgroundColor(ColorUtils.getColor(R.color.button_disable_color))
            handler.post {
                if (screenRecordLayout.windowToken != null) {
                    // 禁用主工具栏防透传
                    disableAntiPenetration(screenRecordLayoutOpsTouchRect)
                    screenRecordLayoutOpsTouchRect = null
                    mWindowManager.removeView(screenRecordLayout)
                }
            }
            stopTimer()
            stopImgShowTimer()
            settings.setRunningState(false)
            stopSelf()
            val finishIntent = Intent("com.example.FINISH_ACTIVITY")
            sendBroadcast(finishIntent)
            // 禁用菜单弹窗防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
        }

    }

    /**
     * 麦克风下拉列表
     */
    @SuppressLint("MissingInflatedId")
    private fun showMicPopupMenu(view: View, tv: TextView, lin: LinearLayout) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.layout_mic_item, null)

        // 创建PopupWindow对象，其中参数1是要显示的View对象，参数2是PopupWindow的宽度，参数3是PopupWindow的高度，参数4表示PopupWindow是否具有焦点
        val popupWindow = PopupWindow(
            popupView,
            lin.width,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            true
        )
        popupWindow.showAsDropDown(tv, -40, 0);
        //点外部区域关闭弹窗
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        // 显示PopupWindow，其中参数1是锚点View，参数2和参数3分别表示PopupWindow的X轴和Y轴的偏移量
        popupWindow.showAtLocation(view, Gravity.LEFT or Gravity.CENTER_VERTICAL, 0, 0)

        val micPhone = popupView.findViewById(R.id.imv_mic_phone) as TextView
        val audioSystem = popupView.findViewById(R.id.imv_system_audio) as TextView
        val mic_and_system = popupView.findViewById(R.id.mic_and_system) as TextView
        val mute = popupView.findViewById(R.id.mute) as TextView
        val autoCheck = popupView.findViewById(R.id.system_auto_check) as ImageView
        val micCheck = popupView.findViewById(R.id.mic_phone_check) as ImageView
        val minAutoCheck = popupView.findViewById(R.id.mic_and_system_check) as ImageView
        val muteCheck = popupView.findViewById(R.id.mute_check) as ImageView

        // 如果MIC硬件不可用，禁用MIC相关选项（置灰不可选）
        if (!isMicHardwareAvailable) {
            val disabledColor = resources.getColor(R.color.button_disable_color, null)
            micPhone.isEnabled = false
            micPhone.setTextColor(disabledColor)
            mic_and_system.isEnabled = false
            mic_and_system.setTextColor(disabledColor)
            Log.d(TAG, "showMicPopupMenu: MIC hardware not available, disabled mic options")

            // 如果当前选中了MIC相关选项，自动切换到第一个可用选项（System Audio）
            if (settings.getMic()) {
                settings.setMic(false)
                settings.setSystemAudio(true)
                tv.setText(R.string.system_auto)
                Log.d(TAG, "showMicPopupMenu: Auto switched from MIC option to System Audio")
            }
        }

        if (!settings.getMic() && !settings.getSystemAudio()) { //没有麦克风、没有声音
            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.VISIBLE
        } else if (!settings.getMic() && settings.getSystemAudio()) { //没有麦克风、有声音
            autoCheck?.visibility = View.VISIBLE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.GONE
        } else if (settings.getMic() && settings.getSystemAudio()) { //有麦克风、有声音
            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.VISIBLE
            muteCheck?.visibility = View.GONE
        } else if (settings.getMic() && !settings.getSystemAudio()) { //有麦克风、没有声音
            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.VISIBLE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.GONE
        }

        micPhone.setOnClickListener {

            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.VISIBLE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.GONE

            tv.setText(micPhone.text)
            settings.setMic(true)
            settings.setSystemAudio(false)
            popupWindow.dismiss()
        }
        audioSystem.setOnClickListener {

            autoCheck?.visibility = View.VISIBLE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.GONE

            tv.setText(audioSystem.text)
            settings.setMic(false)
            settings.setSystemAudio(true)
            popupWindow.dismiss()
        }
        mic_and_system.setOnClickListener {

            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.VISIBLE
            muteCheck?.visibility = View.GONE

            tv.setText(mic_and_system.text)
            settings.setMic(true)
            settings.setSystemAudio(true)
            popupWindow.dismiss()
        }
        mute.setOnClickListener {

            autoCheck?.visibility = View.GONE
            micCheck?.visibility = View.GONE
            minAutoCheck?.visibility = View.GONE
            muteCheck?.visibility = View.VISIBLE

            tv.setText(mute.text)
            settings.setMic(false)
            settings.setSystemAudio(false)
            popupWindow.dismiss()
        }
    }

    /**
     * 分辨率下拉列表
     */
    @SuppressLint("MissingInflatedId")
    private fun showResolutionPopupMenu(view: View, tv: TextView, lin: LinearLayout) {
        val popupView = LayoutInflater.from(this).inflate(R.layout.layout_resolution_item, null)
        // 创建PopupWindow对象，其中参数1是要显示的View对象，参数2是PopupWindow的宽度，参数3是PopupWindow的高度，参数4表示PopupWindow是否具有焦点
        val popupWindow = PopupWindow(
            popupView,
            lin.width,
            ViewGroup.LayoutParams.WRAP_CONTENT, true
        )
        popupWindow.showAsDropDown(tv, -40, 0);
        //点外部区域关闭弹窗
        popupWindow.isOutsideTouchable = true
        popupWindow.isFocusable = true
        // 设置PopupWindow的进入和退出动画
//        popupWindow.setAnimationStyle(R.style.PopupLeftAnim);
        // 显示PopupWindow，其中参数1是锚点View，参数2和参数3分别表示PopupWindow的X轴和Y轴的偏移量
        popupWindow.showAtLocation(view, Gravity.LEFT or Gravity.CENTER_VERTICAL, 0, 0)

        val quality_3840 = popupView.findViewById(R.id.quality_3840) as TextView

        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            val platcode = getMethod.invoke(null, "persist.hht.channel.identify", "") as String
            if (platcode.toLowerCase().contains("odin")){
                quality_3840.visibility = View.GONE
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        val quality_1920 = popupView.findViewById(R.id.quality_1920) as TextView
        val quality_1280 = popupView.findViewById(R.id.quality_1280) as TextView
//        val quality_960 = popupView.findViewById(R.id.quality_960) as TextView

        val quality1920Check = popupView.findViewById(R.id.one_check) as ImageView
        val quality1280Check = popupView.findViewById(R.id.two_check) as ImageView
        val quality3840Check = popupView.findViewById(R.id.three_check) as ImageView

        if (settings.getResolutionData() == RESOLUTION_1920_1080) {
            quality1920Check?.visibility = View.VISIBLE
            quality1280Check?.visibility = View.GONE
            quality3840Check?.visibility = View.GONE
        } else if (settings.getResolutionData() == RESOLUTION_1280_720) {
            quality1920Check?.visibility = View.GONE
            quality3840Check?.visibility = View.GONE
            quality1280Check?.visibility = View.VISIBLE
        } else if (settings.getResolutionData() == RESOLUTION_3840_2160) {
            quality1920Check?.visibility = View.GONE
            quality3840Check?.visibility = View.VISIBLE
            quality1280Check?.visibility = View.GONE
        }
        quality_3840.setOnClickListener {

            quality1920Check?.visibility = View.GONE
            quality1280Check?.visibility = View.GONE
            quality3840Check?.visibility = View.VISIBLE

            tv.setText(quality_3840.text)
            settings.saveResolutionData(RESOLUTION_3840_2160)
            popupWindow.dismiss()
        }
        quality_1920.setOnClickListener {

            quality1920Check?.visibility = View.VISIBLE
            quality1280Check?.visibility = View.GONE
            quality3840Check?.visibility = View.GONE

            tv.setText(quality_1920.text)
            settings.saveResolutionData(RESOLUTION_1920_1080)
            popupWindow.dismiss()
        }
        quality_1280.setOnClickListener {

            quality1920Check?.visibility = View.GONE
            quality3840Check?.visibility = View.GONE
            quality1280Check?.visibility = View.VISIBLE

            tv.setText(quality_1280.text)
            settings.saveResolutionData(RESOLUTION_1280_720)
            popupWindow.dismiss()
        }
//        quality_960.setOnClickListener {
//            tv.setText(quality_960.text)
//            settings.saveResolutionData(RESOLUTION_960_720)
//            popupWindow.dismiss()
//        }
    }

    /**
     * 自定义对话框、低内存对话框
     */
    @SuppressLint("InflateParams")
    private fun showLowSpaceMenu() {
        lowSpaceMenu = null
        if (lowSpaceMenu == null) {
            lowSpaceMenu = mInflater.inflate(R.layout.dialog_normal, null) as ConstraintLayout
        }
        val cancel = lowSpaceMenu?.findViewById(R.id.cancel) as TextView
        val confirm = lowSpaceMenu?.findViewById(R.id.confirm) as TextView
        val params = WindowManager.LayoutParams()
        params.height = 532
        params.width = 960
        params.gravity = Gravity.CENTER
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.format = PixelFormat.RGBA_8888
        cancel.setOnClickListener {
            screenRecordHelper.deleteFile()
            // 禁用低内存菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
            mWindowManager.addView(screenRecordLayout, screenRecorderLayoutParams)
            // 启用主工具栏防透传
            screenRecordLayoutOpsTouchRect = enableAntiPenetration(screenRecordLayout, "win_title_screenrecoder_toolbar")
        }
        confirm.setOnClickListener {
            // 禁用低内存菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
        }
        mWindowManager.addView(lowSpaceMenu, params)
        // 启用低内存菜单防透传
        lowSpaceMenu?.let {
            lowSpaceMenuOpsTouchRect = enableAntiPenetration(it, "LowSpaceMenu")
        }
    }

    /**
     * 自定义弹窗,检查剩余内存是否超过20MB
     */
    private fun showFreeSpaceMenu() {
        if (lowSpaceMenu == null) {
            lowSpaceMenu =
                mInflater.inflate(R.layout.dialog_enough_normal, null) as ConstraintLayout
        }
        val isOK = lowSpaceMenu?.findViewById(R.id.is_ok) as TextView
        val params = WindowManager.LayoutParams()
        params.height = 532
        params.width = 960
        params.gravity = Gravity.CENTER
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.format = PixelFormat.RGBA_8888
        isOK.setOnClickListener {
            // 禁用菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
        }
        mWindowManager.addView(lowSpaceMenu, params)
        // 启用剩余内存不足菜单防透传
        lowSpaceMenu?.let {
            lowSpaceMenuOpsTouchRect = enableAntiPenetration(it, "FreeSpaceMenu")
        }
    }

    private fun getData() {

    }

    /**
     * 保存弹窗
     */
    private fun saveDialog(endCode: Int,setTingView: Button, closeView: Button, recView: Button, movableView: Button) {
        if (lowSpaceMenu == null) {
            lowSpaceMenu = mInflater.inflate(R.layout.save_layout, null) as ConstraintLayout?
        }
        val params = WindowManager.LayoutParams()
        params.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        params.height = 424
        params.width = 802
        params.gravity = Gravity.CENTER
        params.format = PixelFormat.RGBA_8888
        params.flags = WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
        mWindowManager.addView(lowSpaceMenu, params)
        // 启用保存菜单防透传
        lowSpaceMenu?.let {
            lowSpaceMenuOpsTouchRect = enableAntiPenetration(it, "SaveMenu")
        }

        val saveDown = lowSpaceMenu?.findViewById(R.id.imv_save) as ImageView
        saveDown?.visibility = View.GONE
        val data = mutableListOf<RecycleGridBean>()
        val bean = RecycleGridBean()
        bean.drawable = getDrawable(R.drawable.local_file)
        bean.storageName = getString(R.string.locality)
        data.add(bean)

        Log.d(TAG, "getData: -----" + UsbFlashUtil.getUsbPath().size)
        for (i in 0 until UsbFlashUtil.getUsbPath().size) {
            val item = RecycleGridBean()
            item.drawable = getDrawable(R.drawable.usb_file)
            item.storageName = UsbFlashUtil.getUsbName()[i]
            data.add(item)
        }
        mAdapter?.notifyDataSetChanged();

        var recyclerView = lowSpaceMenu?.findViewById(R.id.usb_recycle) as RecyclerView
        val manager = GridLayoutManager(this, data.size)
        var isSaveViewClicked = false
        recyclerView.setLayoutManager(manager)
        mAdapter = RecycleAdapter(this, data)

        mAdapter?.setItemCLickListener(ItemCLickListener {
            Log.d(TAG, "position: " + it)
            if (it > 0) {
//                screenRecordHelper.copyVideoToUsb(it)
            } else if (it == 0) {
                screenRecordHelper.deleteFile()
            }
            saveDown?.visibility = View.VISIBLE

            if (endCode == 1 || endCode == 2){
                Handler().postDelayed({
                    if (!isSaveViewClicked){
                        // 禁用保存菜单防透传
                        disableAntiPenetration(lowSpaceMenuOpsTouchRect)
                        lowSpaceMenuOpsTouchRect = null
                        lowSpaceMenu?.let { mWindowManager.removeView(it) }
//                    mWindowManager.removeView(lowSpaceMenu)
                        showToast(R.string.save_success)
                    }
                }, 3000)
            }
        })

        recyclerView.setAdapter(mAdapter)

        saveDown.setOnClickListener {
            setTingView.setClickable(false);
            closeView.setClickable(false);
            recView.setClickable(false);
            movableView.setClickable(false);
            mWindowManager.addView(screenSaveLayout, screenRecordingLayoutParams)
            // 禁用保存菜单防透传
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            lowSpaceMenuOpsTouchRect = null
            mWindowManager.removeView(lowSpaceMenu)
            isSaveViewClicked = true
            if (endCode == 1 || endCode == 2){
                Handler().postDelayed({
                    mWindowManager.removeView(screenSaveLayout)
                    showToast(R.string.save_success)
                    setTingView.setClickable(true);
                    closeView.setClickable(true);
                    recView.setClickable(true);
                    movableView.setClickable(true);
                }, 3000)
            }
        }


    }

    fun showToast(resId: Int) {
        if (resId == null) {
            Log.e(TAG, "call method showToast, text is null.")
            return
        }
        val handler = Handler(Looper.getMainLooper())
        handler.post {
            Toast.makeText(this, resId, Toast.LENGTH_LONG)
                .show()
        }
    }

    private fun getFormatTime(time: Long): String? {
        val format = SimpleDateFormat("yyyyMMddHHMMSS", Locale.getDefault())
        val d1 = Date(time)
        return format.format(d1)
    }

    fun startShare(path: String) {
        val packageName = "tw.com.hitevision.sharer.MULTIPLE_FILES"
        val intent = Intent()
        intent.component =
            ComponentName("tw.com.hitevision.sharer", "tw.com.hitevision.sharer.MainActivity")
        intent.action = "android.intent.action.MAIN"
        intent.putExtra(packageName, arrayListOf(path))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    /**
     * 用户切换广播接收器
     * 监听系统用户切换事件，确保在用户切换时完全退出录屏服务
     */
    inner class UserSwitchReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            Log.d(TAG, "UserSwitchReceiver onReceive: action=$action")

            when (action) {
                ACTION_USER_SWITCHED -> {
                    // 用户已切换完成
                    // 注意：Intent.EXTRA_USER_HANDLE 是隐藏API，直接使用字符串
                    val newUserId = intent.getIntExtra("android.intent.extra.user_handle", -1)
                    Log.w(TAG, "用户切换完成，新用户ID: $newUserId，立即退出录屏服务")
                    isuserSwitch = true
                    forceStopAndCleanup("USER_SWITCHED")
                }
                ACTION_USER_BACKGROUND -> {
                    // 当前用户被切换到后台
                    Log.w(TAG, "当前用户被切换到后台，立即退出录屏服务")
                    isuserSwitch = true
                    forceStopAndCleanup("USER_BACKGROUND")
                }
                ACTION_USER_FOREGROUND -> {
                    // 有用户被切换到前台（可能是其他用户）
                    Log.d(TAG, "有用户被切换到前台")
                }
                USER_ID_ACTION -> {
                    // 兼容旧的自定义广播
                    val userId = intent.getIntExtra("android.intent.action.USER_SWITCHED", -1)
                    Log.w(TAG, "收到自定义用户切换广播，用户ID: $userId")
                    isuserSwitch = true
                    forceStopAndCleanup("USER_ID_ACTION")
                }
            }
        }
    }

    /**
     * 强制停止服务并清理所有资源
     * @param reason 停止原因，用于日志
     */
    private fun forceStopAndCleanup(reason: String) {
        Log.w(TAG, "forceStopAndCleanup: 开始强制清理，原因=$reason")

        try {
            // 1. 停止录制计时器
            stopTimer()
            stopImgShowTimer()

            // 2. 停止正在进行的录制
            if (::screenRecordHelper.isInitialized) {
                if (screenRecordStatus == RecordStatus.Recording.ordinal ||
                    screenRecordStatus == RecordStatus.Pause.ordinal) {
                    Log.d(TAG, "forceStopAndCleanup: 正在录制，设置用户切换标记并停止录制")
                    // 设置用户切换标记，停止时跳过保存弹窗，直接保留默认路径的文件
                    screenRecordHelper.setUserSwitching(true)
                    screenRecordHelper.stopRecorder()
                    // 注意：不再删除文件，让文件保留在默认路径
                    // screenRecordHelper.deleteFile()
                    Log.d(TAG, "forceStopAndCleanup: 录制已停止，文件保留在默认路径")
                }
                screenRecordHelper.clearAll()
            }

            // 3. 清理悬浮窗
            cleanupFloatingWindows()

            // 4. 重置状态
            screenRecordStatus = RecordStatus.Idle.ordinal
            isNeedToUpdateDurationTime = false
            isNeedToShowLowSpace = false

            // 5. 设置系统属性标记
            setSystemProperty("sys.skg.recorder.state", "idle")

            // 6. 发送广播通知 Activity 退出
            val finishIntent = Intent("com.example.FINISH_ACTIVITY")
            sendBroadcast(finishIntent)

            // 7. 停止前台服务和自身
            stopForeground(true)
            stopSelf()

            Log.w(TAG, "forceStopAndCleanup: 强制清理完成")
        } catch (e: Exception) {
            Log.e(TAG, "forceStopAndCleanup: 清理过程出错", e)
            // 即使出错也要尝试停止服务
            try {
                stopSelf()
            } catch (e2: Exception) {
                Log.e(TAG, "forceStopAndCleanup: stopSelf失败", e2)
            }
        }
    }

    /**
     * 清理所有悬浮窗
     */
    private fun cleanupFloatingWindows() {
        try {
            // 禁用所有防透传设置
            disableAntiPenetration(screenRecordLayoutOpsTouchRect)
            disableAntiPenetration(screenRecordingLayoutOpsTouchRect)
            disableAntiPenetration(lowSpaceMenuOpsTouchRect)
            screenRecordLayoutOpsTouchRect = null
            screenRecordingLayoutOpsTouchRect = null
            lowSpaceMenuOpsTouchRect = null

            // 移除悬浮窗
            if (::screenRecordLayout.isInitialized && screenRecordLayout.windowToken != null) {
                mWindowManager.removeView(screenRecordLayout)
            }
            if (::screenRecordingLayout.isInitialized && screenRecordingLayout.windowToken != null) {
                mWindowManager.removeView(screenRecordingLayout)
            }
            if (lowSpaceMenu != null && lowSpaceMenu?.windowToken != null) {
                mWindowManager.removeView(lowSpaceMenu)
                lowSpaceMenu = null
            }
        } catch (e: Exception) {
            Log.e(TAG, "cleanupFloatingWindows: 清理悬浮窗出错", e)
        }
    }

    fun getSystemProperty(key: String, defaultValue: String): String {

        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val getMethod = systemPropertiesClass.getMethod("get", String::class.java, String::class.java)
            return getMethod.invoke(null, key, defaultValue) as String
        } catch (e: Exception) {
            e.printStackTrace()
            return defaultValue
        }
    }

    fun setSystemProperty(key: String, value: String) {
        try {
            val systemPropertiesClass = Class.forName("android.os.SystemProperties")
            val setMethod = systemPropertiesClass.getMethod("set", String::class.java, String::class.java)
            setMethod.invoke(null, key, value)
        } catch (e: Exception) {
            e.printStackTrace()
            // 处理异常
        }
    }

    /**
     * 为悬浮窗启用防透传
     * @param view 悬浮窗的View
     * @param title 悬浮窗标识
     * @return 创建的OpsTouchRect对象
     */
    private fun enableAntiPenetration(view: View, title: String): OpsTouchRect? {
        // 立即创建一个 OpsTouchRect 用于返回（用于后续 disable 操作）
        val opsTouchRect = HHTTouchHelper.createOpsTouchRect(view, title)
        Log.d(TAG, "enableAntiPenetration start: $title")

        // 使用 ViewTreeObserver 监听布局完成，确保获取准确的位置和尺寸
        view.viewTreeObserver.addOnGlobalLayoutListener(object : ViewTreeObserver.OnGlobalLayoutListener {
            override fun onGlobalLayout() {
                // 移除监听器，避免重复调用
                view.viewTreeObserver.removeOnGlobalLayoutListener(this)

                // 布局完成后，使用准确的位置和尺寸设置防透传
                val correctRect = HHTTouchHelper.createOpsTouchRect(view, title)
                HHTTouchHelper.enableOpsTouchRect(correctRect)
                Log.d(TAG, "enableAntiPenetration onGlobalLayout: $title, rect=${correctRect?.rect}")
            }
        })

        return opsTouchRect
    }

    /**
     * 禁用悬浮窗防透传
     * @param opsTouchRect 之前创建的OpsTouchRect对象
     */
    private fun disableAntiPenetration(opsTouchRect: OpsTouchRect?) {
        if (opsTouchRect != null) {
            HHTTouchHelper.disableOpsTouchRect(opsTouchRect)
            Log.d(TAG, "disableAntiPenetration: ${opsTouchRect.rectTitle}")
        }
    }


}

