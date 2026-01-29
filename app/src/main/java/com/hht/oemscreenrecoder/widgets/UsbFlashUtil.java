package com.hht.oemscreenrecoder.widgets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.text.TextUtils;
import android.util.Log;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class UsbFlashUtil {

    private static List<String> usbPath;

    private static List<String> usbName;

    private final String TAG = "UsbFlashUtil";

    public static List<String> getUsbPath() {
        return usbPath;
    }

    public static void setUsbPath(List<String> usbPath) {
        UsbFlashUtil.usbPath = usbPath;
    }

    public static List<String> getUsbName() {
        return usbName;
    }

    public static void setUsbName(List<String> usbName) {
        UsbFlashUtil.usbName = usbName;
    }

    /**
     * 广播接收者
     */
    private BroadcastReceiver receiverU = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                //U盘插入
                String path = intent.getData().getPath();
                path = getCorrectPath(path);//获取正确的，完整的路径
                List<String> list = new ArrayList<>();
                list.add(path);
                setUsbPath(list);
                Log.d(TAG, "------>U盘路径："+UsbFlashUtil.this.usbPath);
                if (diskListenerList.size() > 0){
                    for (int i = 0; i < diskListenerList.size(); i++) {
                        if (null != diskListenerList.get(i))diskListenerList.get(i).onConnect();
                    }
                }
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action) || Intent.ACTION_MEDIA_EJECT.equals(action)) {
                //U盘拔出
                UsbFlashUtil.this.usbPath.add("");
                if (diskListenerList.size() > 0){
                    for (int i = 0; i < diskListenerList.size(); i++) {
                        if (null != diskListenerList.get(i))diskListenerList.get(i).onDisconnect();
                    }
                }
            }
        }
    };


    /**
     * U盘连接状态回调
     */
    public interface IUDiskListener{
        void onConnect();
        void onDisconnect();
    }
    private List<IUDiskListener> diskListenerList = new ArrayList<>();
    public void setUDiskListener(IUDiskListener uDiskListener){
        diskListenerList.add(uDiskListener);
    }
    public void removeUDiskListener(IUDiskListener uDiskListener){
        diskListenerList.remove(uDiskListener);
    }

    private String getCorrectPath(String path) {
        if (!TextUtils.isEmpty(path)){
            int lastSeparator = path.lastIndexOf(File.separator);
            String endStr = path.substring(lastSeparator + 1, path.length());
            if (!TextUtils.isEmpty(endStr) && (endStr.contains("USB_DISK") || endStr.contains("usb_disk"))){//不区分大小写
                File file = new File(path);
                if (file.exists() && file.listFiles().length == 1 && file.listFiles()[0].isDirectory()){
                    path = file.listFiles()[0].getAbsolutePath();
                }
            }
        }
        return path;
    }

    public static void createScreenrecorderFolder() {
        for (int i = 0; i < getUsbPath().size(); i++) {
            getUsbPath().get(i);
        }
        // 检查外部存储是否可用
        if (Environment.getExternalStorageState().equals(Environment.MEDIA_MOUNTED)) {
            // 获取外部存储目录
            File externalStorageDirectory = Environment.getExternalStorageDirectory();

            // 创建 "Screenrecorder" 文件夹
            File screenrecorderFolder = new File(externalStorageDirectory, "Screenrecorder");

            if (!screenrecorderFolder.exists()) {
                if (screenrecorderFolder.mkdirs()) {
                    Log.d("Folder Created", "Screenrecorder folder created");
                } else {
                    Log.e("Folder Creation Error", "Failed to create Screenrecorder folder");
                }
            } else {
                Log.d("Folder Exists", "Screenrecorder folder already exists");
            }
//
//            // 在 "Screenrecorder" 文件夹中创建并保存文件（示例）
//            File sampleFile = new File(screenrecorderFolder, "sample.txt");
//            try {
//                FileOutputStream fos = new FileOutputStream(sampleFile);
//                fos.write("This is a sample file content.".getBytes());
//                fos.close();
//                Log.d("File Saved", "Sample file saved in Screenrecorder folder");
//            } catch (IOException e) {
//                Log.e("File Save Error", "Error saving sample file: " + e.getMessage());
//            }
        } else {
            Log.e("External Storage", "External storage is not available or writable.");
        }
    }

    public static void copyVideoToUsb(File localFile,File UsbFile){
        if(localFile.exists()) {
            FileChannel outF;
            try {
                outF = new FileOutputStream(UsbFile).getChannel();
                new FileInputStream(localFile).getChannel().transferTo(0, localFile.length(),outF);
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

        }

        new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }).start();
    }
}
