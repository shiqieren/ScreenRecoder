package com.hht.oemscreenrecoder

import android.content.Context
import android.content.SharedPreferences
import android.os.Environment
import android.os.StatFs
import android.util.Log
import com.blankj.utilcode.util.PathUtils
import java.io.File

class Settings(context: Context?) {

    companion object {
        private lateinit var settings: SharedPreferences
        private const val DATA = "screen_record_settings"
        private const val WARNING_DONT_SHOW= "warning_dont_show"
        private const val RESOLUTION_DATA= "resolution_data"
        private const val SAVE_PATH= "save_path"
        private const val VIDEO_SET= "video_set"
        private const val SYSTEM_VOLUME= "system_volume"
        private const val MIC= "mic"
        private const val AUDIO_SET= "audio_set"
        private const val SERVICE_RUNNING = "service_running"
        private const val SP_KEY_FILE_SERIAL_NUMBER = "sp_key_file_serial_number"

        private val DEFAULT_SAVE_PATH = PathUtils.getExternalStoragePath()+"/Screen Record"
        private var instance : Settings? = null

        fun  getInstance(context: Context): Settings {
            if (instance == null)  // NOT thread safe!
                instance = Settings(context)

            return instance!!
        }

        //The unit is MB
        const val LOW_SPACE_STANDARD :Long = 1024
        const val CANT_RECORD_STANDARD :Long = 200
        // 空间不足停止录制阈值（500MB）
        const val STOP_RECORD_SPACE_THRESHOLD: Long = 500
        // 最大录制时长限制（1小时 = 3600秒）
        const val MAX_RECORD_DURATION_SECONDS: Int = 3600

        // 录制停止原因
        const val STOP_REASON_NORMAL = 0           // 用户手动停止
        const val STOP_REASON_TIME_LIMIT = 1       // 达到1小时时长限制
        const val STOP_REASON_LOW_SPACE = 2        // 存储空间不足500MB

        const val RESOLUTION_1280_720 = "1280x720p"
        const val RESOLUTION_1920_1080 = "1920x1080p"
        const val RESOLUTION_3840_2160 = "3840x2160p"
        const val RESOLUTION_960_720 = "960x720p"
        private const val TAG="Settings"
    }

    var fileName: String = ""

    init {
        Log.d(TAG, "Settings: init")
        settings = context!!.getSharedPreferences(DATA, 0)
    }

    fun getSystemAudio(): Boolean {
        return settings.getBoolean(SYSTEM_VOLUME, true)
    }

    fun setSystemAudio(boolean: Boolean){
        settings.edit()
            .putBoolean(SYSTEM_VOLUME, boolean)
            .apply()
    }

    fun getMic(): Boolean {
        return settings.getBoolean(MIC, false)
    }

    fun setMic(boolean: Boolean){
        settings.edit()
            .putBoolean(MIC, boolean)
            .apply()
    }

    fun saveWarningData(boolean: Boolean) {
        settings.edit()
            .putBoolean(WARNING_DONT_SHOW, boolean)
            .apply()
    }

    fun getWarningData():Boolean {
        return settings.getBoolean(WARNING_DONT_SHOW, false)
    }

    fun savePathData(string: String) {
        settings.edit()
            .putString(SAVE_PATH, string)
            .apply()
    }

    fun getPathData():String {
        return settings.getString(SAVE_PATH, DEFAULT_SAVE_PATH)!!
    }

    fun setRunningState(b:Boolean) {
        settings.edit()
            .putBoolean(SERVICE_RUNNING, b)
            .apply()
    }

    fun getRunningState():Boolean {
        return settings.getBoolean(SERVICE_RUNNING, false)
    }

    fun saveResolutionData(string: String) {
        settings.edit()
            .putString(RESOLUTION_DATA, string)
            .apply()
    }

    fun getResolutionData():String {
        return settings.getString(RESOLUTION_DATA, RESOLUTION_1920_1080)!!
    }

    fun saveVideoData(boolean: Boolean) {
        settings.edit()
            .putBoolean(VIDEO_SET,boolean)
            .apply()
    }

    fun getVideoData():Boolean {
        return settings.getBoolean(VIDEO_SET,false)
    }

    fun saveAudioData(boolean: Boolean) {
        settings.edit()
            .putBoolean(AUDIO_SET,boolean)
            .apply()
    }

    fun getAudioData():Boolean {
        return settings.getBoolean(AUDIO_SET,false)
    }

    //return unit is MB
    fun getRemainSpace(): Long {
//        val external: File = Environment.getExternalStorageDirectory()
//        return external.freeSpace / 1000000
        return getAvailableMemory()
    }

    private fun getAvailableMemory(): Long {
        var path: File = Environment.getDataDirectory();
        var stat = StatFs(path.path)
        //获取一个扇区的大小
        var blockSize: Long = stat.blockSizeLong
        //获取扇区可用数量
        var availableBlocks: Long = stat.availableBlocksLong
        var availableSize = blockSize * availableBlocks

        return availableSize / 1000000
    }
}