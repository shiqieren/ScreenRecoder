package com.hht.oemscreenrecoder

import android.Manifest.permission.READ_EXTERNAL_STORAGE
import android.Manifest.permission.WRITE_EXTERNAL_STORAGE
import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.storage.StorageManager
import android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
import android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
import android.text.TextUtils
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.annotation.NonNull
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.blankj.utilcode.constant.PermissionConstants
import com.blankj.utilcode.util.PermissionUtils
import com.hht.oemscreenrecoder.screenrecorder.ScreenRecordService
import com.hht.oemscreenrecoder.widgets.UsbFlashUtil
import com.hjq.permissions.OnPermissionCallback
import com.hjq.permissions.Permission
import com.hjq.permissions.XXPermissions
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.lang.reflect.Method


class MainActivity : AppCompatActivity() {
    private lateinit var mInflater: LayoutInflater
    private var lowSpaceMenu: ConstraintLayout? = null
    private lateinit var mWindowManager: WindowManager
    private val usbPath: String? = null
    private var isServiceRunning = false

    private val pathList: MutableList<String> = java.util.ArrayList()
    private val nameList: MutableList<String> = java.util.ArrayList()
    val list: MutableList<String> = java.util.ArrayList()

    // 用户切换广播接收器
    private var userSwitchReceiver: BroadcastReceiver? = null
    // Activity 退出广播接收器
    private var finishReceiver: BroadcastReceiver? = null

    private val mediaProjectionManager by lazy { getSystemService(Context.MEDIA_PROJECTION_SERVICE) as? MediaProjectionManager }
    private val settings: Settings by lazy {
        Settings.getInstance(
            this
        )
    }

    companion object {
        const val READY_TO_FINISH_ACTIVITY = 1

        // activity result
        const val REQUEST_PERMISSION_OVERLAY = 1
        const val REQUEST_PERMISSION_VIDEO = 2
        const val REQUEST_PERMISSION_FILE = 4
        private const val TAG = "mzfRecord"
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        //to check the service already running
//        if (settings.getRunningState()) {
//            settings.setRunningState(false)
//            finish()
//        }

        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 隐藏导航栏（可选）
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(R.layout.activity_main)
        setSystemProperty("sys.skg.recorder.state", "prepare")
        val recorderState = getSystemProperty("sys.skg.recorder.state", "default_state")
        Log.d(TAG, "onStartCommand: recording++++录屏应用打开----" + recorderState)

        mInflater = LayoutInflater.from(this)
        mWindowManager = this.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        //判断剩余内存是否大于20MB
//        if (!StorageUtils.checkFreeSpace()){
//            showLowSpaceMenu()
//        }

//申请权限
        //申请权限PermissionConstants.STORAGE, PermissionConstants.MICROPHONE
        XXPermissions.with(this@MainActivity) // 申请单个权限
//            .permission(Permission.MANAGE_EXTERNAL_STORAGE)
            .permission(Permission.RECORD_AUDIO)
            .permission(Permission.READ_EXTERNAL_STORAGE)
            .permission(Permission.WRITE_EXTERNAL_STORAGE)
//            .permission(Permission.READ_MEDIA_IMAGES)
            .permission(Permission.CAMERA)
//            .permission(Permission.READ_MEDIA_VIDEO)
//            .permission(Permission.READ_MEDIA_AUDIO) // 申请多个权限
            .permission(Permission.Group.STORAGE) // 设置权限请求拦截器（局部设置）
            //.interceptor(new PermissionInterceptor())
            // 设置不触发错误检测机制（局部设置）
            //.unchecked()
            .request(object : OnPermissionCallback {
                override fun onGranted(@NonNull permissions: List<String>, allGranted: Boolean) {
                    if (!allGranted) {
//                            toast("获取部分权限成功，但部分权限未正常授予");
                        Log.i("liyiwei", "获取文件权限权限获取部分权限成功，但部分权限未正常授予")
                        return
                    }
                    //                        toast("获取录音和日历权限成功");
                    Log.i("liyiwei", "获取文件权限权限成功")
                    mediaProjectionManager?.apply {
                        val intent = this.createScreenCaptureIntent()
                        if (packageManager.resolveActivity(
                                intent,
                                PackageManager.MATCH_DEFAULT_ONLY
                            ) != null
                        ) {
                            startActivityForResult(intent, REQUEST_PERMISSION_VIDEO)
                        } else {
                            showToast(R.string.device_not_support_screen_record)
                        }
                    }
                }

                override fun onDenied(@NonNull permissions: List<String>, doNotAskAgain: Boolean) {
                    if (doNotAskAgain) {
//                            toast("被永久拒绝授权，请手动授予录音和日历权限");
                        // 如果是被永久拒绝就跳转到应用权限系统设置页面
                        Log.i("liyiwei", "被永久拒绝授权，请手动授予获取文件权限权限")
                        XXPermissions.startPermissionActivity(this@MainActivity, permissions)
                    } else {
//                            toast("获取录音和日历权限失败");
                        Log.i("liyiwei", "获取文件权限权限失败")
                    }
                }
            })
        requestRecordPermission()
//        requestPermission()
        registerBroadcast()

        // 注册用户切换广播和Activity退出广播
        registerUserSwitchReceiver()
        registerFinishReceiver()

        if (SDK_INT >= Build.VERSION_CODES.M) {
            if (!android.provider.Settings.canDrawOverlays(applicationContext)) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                intent.data = Uri.parse("package:$packageName")
                startActivityForResult(intent, REQUEST_PERMISSION_OVERLAY)
            }
        }
    }

    /**
     * 申请文件权限
     */
    private fun requestPermission() {
        if (!checkPermission()) {
            if (SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.addCategory("android.intent.category.DEFAULT")
                    intent.data =
                        Uri.parse(String.format("package:%s", applicationContext.packageName))
                    startActivityForResult(intent, REQUEST_PERMISSION_FILE)
                } catch (e: Exception) {
                    val intent = Intent()
                    intent.action = ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    startActivityForResult(intent, REQUEST_PERMISSION_FILE)
                }
            } else {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(WRITE_EXTERNAL_STORAGE),
                    REQUEST_PERMISSION_FILE
                )
            }
        }
    }

    /**
     * 检查文件权限
     */
    private fun checkPermission(): Boolean {
        return if (SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            val result = ContextCompat.checkSelfPermission(this, READ_EXTERNAL_STORAGE)
            val result1 = ContextCompat.checkSelfPermission(this, WRITE_EXTERNAL_STORAGE)
            result == PackageManager.PERMISSION_GRANTED && result1 == PackageManager.PERMISSION_GRANTED
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        val isMyServiceRunning = isServiceRunning(this, ScreenRecordService::class.java)
        when (requestCode) {
            REQUEST_PERMISSION_OVERLAY -> {

            }
            REQUEST_PERMISSION_VIDEO -> {
                //剩余内存如果不大于20MB,则不启动录屏服务
//                if (StorageUtils.checkFreeSpace()){
                    if (resultCode == RESULT_OK) {
                        if (!isMyServiceRunning) {
                            val service = Intent(this, ScreenRecordService::class.java)
                            service.putExtra("code", resultCode)
                            service.putExtra("data", data)
//                        service.putExtra("usbPath", UsbFlashUtil.usbPath)
                            startForegroundService(service)
                            settings.setRunningState(true)
                        }
                    } else {
//                        showToast(R.string.permission_denied)
                    }
                    handler.dispatchMessage(Message().apply { what = READY_TO_FINISH_ACTIVITY })
//                }
            }

            REQUEST_PERMISSION_FILE -> {
                Log.d(TAG, "onActivityResult: REQUEST_PERMISSION_FILE")
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    private val handler = object : Handler(Looper.myLooper()!!) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                READY_TO_FINISH_ACTIVITY -> {
                    finish()
                }
            }
            super.handleMessage(msg)
        }
    }

    private fun requestRecordPermission() {
        PermissionUtils.permission(PermissionConstants.STORAGE, PermissionConstants.MICROPHONE)
            .callback(object : PermissionUtils.SimpleCallback {
                override fun onGranted() {
                    mediaProjectionManager?.apply {
                        val intent = this.createScreenCaptureIntent()
                        if (packageManager.resolveActivity(
                                intent,
                                PackageManager.MATCH_DEFAULT_ONLY
                            ) != null
                        ) {
                            startActivityForResult(intent, REQUEST_PERMISSION_VIDEO)
                        } else {
                            showToast(R.string.device_not_support_screen_record)
                        }
                    }
                }

                override fun onDenied() {
//                    showToast(R.string.permission_denied)
                }
            })
            .request()
    }

    private fun showToast(resId: Int) {
        Toast.makeText(this, getString(resId), Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy: ------finish")
        // 注销用户切换广播
        unregisterUserSwitchReceiver()
        // 注销Activity退出广播
        unregisterFinishReceiver()
        super.onDestroy()
    }

    /**
     * 注册用户切换广播接收器
     */
    private fun registerUserSwitchReceiver() {
        try {
            userSwitchReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    val action = intent.action
                    Log.d(TAG, "UserSwitchReceiver onReceive: action=$action")

                    when (action) {
                        "android.intent.action.USER_SWITCHED",
                        "android.intent.action.USER_BACKGROUND" -> {
                            Log.w(TAG, "检测到用户切换，立即关闭 Activity")
                            // 停止服务
                            stopService(Intent(this@MainActivity, ScreenRecordService::class.java))
                            settings.setRunningState(false)
                            // 关闭 Activity
                            finish()
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction("android.intent.action.USER_SWITCHED")
                addAction("android.intent.action.USER_BACKGROUND")
                addAction("android.intent.action.USER_FOREGROUND")
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

    /**
     * 注册 Activity 退出广播接收器
     * 用于接收 Service 发出的退出通知
     */
    private fun registerFinishReceiver() {
        try {
            finishReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    Log.d(TAG, "FinishReceiver: 收到退出通知，关闭 Activity")
                    finish()
                }
            }
            val filter = IntentFilter("com.example.FINISH_ACTIVITY")
            registerReceiver(finishReceiver, filter)
            Log.d(TAG, "registerFinishReceiver: Activity退出广播注册成功")
        } catch (e: Exception) {
            Log.e(TAG, "registerFinishReceiver: 注册广播失败", e)
        }
    }

    /**
     * 注销 Activity 退出广播接收器
     */
    private fun unregisterFinishReceiver() {
        try {
            finishReceiver?.let {
                unregisterReceiver(it)
                Log.d(TAG, "unregisterFinishReceiver: Activity退出广播注销成功")
            }
        } catch (e: Exception) {
            Log.e(TAG, "unregisterFinishReceiver: 注销广播失败", e)
        }
        finishReceiver = null
    }
    /**
     * 广播接收者
     */
    private val receiverU: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val action = intent.action
            if (Intent.ACTION_MEDIA_MOUNTED == action) {
                //U盘插入
                var path = intent.data!!.path
                val usbName = File(path).name  // 获取U盘名称
                Log.d(TAG, "onReceive: +++++$path")
                Log.d(TAG, "onReceive: +++++$usbName")
                path = getCorrectPath(path) //获取正确的，完整的路径
                pathList.add(path!!)
                nameList.add(usbName)

                UsbFlashUtil.setUsbPath(pathList)
                UsbFlashUtil.setUsbName(nameList)

                for (i in UsbFlashUtil.getUsbPath().indices) {
                    Log.d(TAG, "------>U盘路径数量：" + UsbFlashUtil.getUsbPath().size)
                    Log.d(TAG, "------>U盘路径：" + UsbFlashUtil.getUsbPath()[i])
                }

                if (diskListenerList.size > 0) {
                    for (i in diskListenerList.indices) {
                        if (null != diskListenerList[i]) diskListenerList[i]!!.onConnect()
                    }
                }
            } else if (Intent.ACTION_MEDIA_UNMOUNTED == action || Intent.ACTION_MEDIA_EJECT == action) {
                list.add("")
                UsbFlashUtil.setUsbPath(list)

                //U盘拔出
//                for (i in UsbFlashUtil.getUsbPath().indices) {
//                    Log.d(TAG, "------>拔出之后U盘路径数量：" + UsbFlashUtil.getUsbPath().size)
//                    Log.d(TAG, "------>拔出之后U盘路径：" + UsbFlashUtil.getUsbPath()[i])
//                }
                if (diskListenerList.size > 0) {
                    for (i in diskListenerList.indices) {
                        if (null != diskListenerList[i]) diskListenerList[i]!!.onDisconnect()
                    }
                }
            }
        }
    }

    /**
     * U盘连接状态回调
     */
    interface IUDiskListener {
        fun onConnect()
        fun onDisconnect()
    }

    private val diskListenerList: MutableList<IUDiskListener?> = java.util.ArrayList()
    fun setUDiskListener(uDiskListener: IUDiskListener?) {
        diskListenerList.add(uDiskListener)
    }

    fun removeUDiskListener(uDiskListener: IUDiskListener?) {
        diskListenerList.remove(uDiskListener)
    }

    private fun getCorrectPath(path: String?): String? {
        var path = path
        if (!TextUtils.isEmpty(path)) {
            val lastSeparator = path!!.lastIndexOf(File.separator)
            val endStr = path.substring(lastSeparator + 1, path.length)
            if (!TextUtils.isEmpty(endStr) && (endStr.contains("USB_DISK") || endStr.contains("usb_disk"))) { //不区分大小写
                val file = File(path)
                if (file.exists() && file.listFiles().size == 1 && file.listFiles()[0].isDirectory) {
                    path = file.listFiles()[0].absolutePath
                }
            }
        }
        return path
    }

    /**
     * 注册监听U盘拔插广播
     */
    fun registerBroadcast() {
        val filter = IntentFilter()
        filter.addAction(Intent.ACTION_MEDIA_SHARED) //如果SDCard未安装,并通过USB大容量存储共享返回
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED) //表明sd对象是存在并具有读/写权限
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED) //SDCard已卸掉,如果SDCard是存在但没有被安装
        filter.addAction(Intent.ACTION_MEDIA_CHECKING) //表明对象正在磁盘检查
        filter.addAction(Intent.ACTION_MEDIA_EJECT) //物理的拔出 SDCARD
        filter.addAction(Intent.ACTION_MEDIA_REMOVED) //完全拔出
        filter.addDataScheme("file") // 必须要有此行，否则无法收到广播
        applicationContext.registerReceiver(receiverU, filter)
        //在本app未启动前已经插着U盘的情况下，获取U盘路径
        val list = getPathListByStorageManager() //根据StorageManager获取U盘路径
        //        List<String> list = getPathByMount();//根据mount命令获取U盘路径

        UsbFlashUtil.setUsbPath(list)
        /**
         * 在检测到多个U盘的情况下,遍历,在每个U根目录下创建Screen Record文件夹
         */
        if (UsbFlashUtil.getUsbPath() != null && UsbFlashUtil.getUsbPath().size > 0){
            for (i in UsbFlashUtil.getUsbPath().indices) {
                val usbFile = File(UsbFlashUtil.getUsbPath()[i], "Screen Record")
                if (!usbFile.exists()) {
                    usbFile.mkdirs()
                }
            }
        }

    }

    /**
     * 根据StorageManager获取Usb插入的U盘路径
     * 可以获取内部存储、sd卡以及所有usb路径
     * 获取到的路径可能是不完整的，需要判断追加
     */
    private fun getPathListByStorageManager(): List<String> {
        val pathList: MutableList<String> = ArrayList()
        val nameList: MutableList<String> = ArrayList()
        try {
            val storageManager =
                this.application.getSystemService(STORAGE_SERVICE) as StorageManager
            val method_volumeList: Method = StorageManager::class.java.getMethod("getVolumeList")
            method_volumeList.setAccessible(true)
            val volumeList = method_volumeList.invoke(storageManager) as Array<Any>
            if (volumeList != null) {
                for (i in volumeList.indices) {
                    try {
                        val path = volumeList[i].javaClass.getMethod("getPath")
                            .invoke(volumeList[i]) as String
                        val isRemovable = volumeList[i].javaClass.getMethod("isRemovable").invoke(
                            volumeList[i]
                        ) as Boolean
                        val state = volumeList[i].javaClass.getMethod("getState")
                            .invoke(volumeList[i]) as String
                        //                        Util.logE("isRemovable:"+isRemovable+" / state:"+state+" / path:"+path);

                        if (isRemovable && "mounted".equals(
                                state,
                                ignoreCase = true
                            )
                        ) {
                            // 使用StorageManager获取U盘的名称
                            val volume = storageManager.getStorageVolume(File(path))
                            val UsbName = if (volume != null) volume.getDescription(this) else "Unknown"
                            nameList.add(UsbName)
                            pathList.add(getCorrectPath(path).toString()) //将正确的路径添加到集合中
                        }
                    } catch (e: java.lang.Exception) {
                        e.printStackTrace()
                    }
                }
            }
            UsbFlashUtil.setUsbName(nameList)
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return pathList
    }

    /**
     * 注销广播
     */
    fun unReisterReceiver() {
        if (receiverU != null) {
            pathList.clear()
            nameList.clear()
            this.unregisterReceiver(receiverU)
        }
    }

    /**
     * 使用mount命令获取usb插入的U盘路径
     * 可以获取内部存储、外部存储、tf卡、otg、系统分区等路径，获取到的U盘路径是完整的正确的
     * 限制条件是机子必须得解开root
     */
    fun getPathByMount(): List<String>? {
        val usbMemoryList: MutableList<String> = ArrayList()
        try {
            val runtime = Runtime.getRuntime()
            // 运行mount命令，获取命令的输出，得到系统中挂载的所有目录
            val process = runtime.exec("mount")
            val `is`: InputStream = process.inputStream
            val isr = InputStreamReader(`is`)
            var line: String
            val br = BufferedReader(isr)
            while (br.readLine().also { line = it } != null) {
                // 将常见的linux分区过滤掉
                if (line.contains("proc") || line.contains("tmpfs") || line.contains("media") || line.contains(
                        "asec"
                    ) || line.contains("secure") || line.contains("system") || line.contains("cache")
                    || line.contains("sys") || line.contains("data") || line.contains("shell") || line.contains(
                        "root"
                    ) || line.contains("acct") || line.contains("misc") || line.contains("obb")
                ) {
                    continue
                }
                //                Util.logE("==========>line:"+line);
                if (!TextUtils.isEmpty(line) && line.contains("usb_storage")) { //根据情况过来需要的字段
                    val items = line.split(" ".toRegex()).toTypedArray()
                    if (null != items && items.size > 1) {
                        val path = items[1]
                        //                        Util.logE("------->path:"+path);
                        // 添加一些判断，确保是sd卡，如果是otg等挂载方式，可以具体分析并添加判断条件
                        if (path != null && !usbMemoryList.contains(path) && path.contains("sd")) usbMemoryList.add(
                            items[1]
                        )
                    }
                }
            }
        } catch (e: java.lang.Exception) {
            e.printStackTrace()
        }
        return usbMemoryList
    }

    /**
     * 判断服务是否正在运行
     */
    fun isServiceRunning(context: Context, serviceClass: Class<*>): Boolean {
        val manager = context.getSystemService(ACTIVITY_SERVICE) as ActivityManager
        if (manager != null) {
            for (service in manager.getRunningServices(Int.MAX_VALUE)) {
                if (serviceClass.name == service.service.className) {
                    return true
                }
            }
        }
        return false
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

}
