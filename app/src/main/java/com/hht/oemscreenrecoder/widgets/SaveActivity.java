package com.hht.oemscreenrecoder.widgets;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.storage.StorageManager;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.hht.oemscreenrecoder.MainActivity;
import com.hht.oemscreenrecoder.R;
import com.hht.oemscreenrecoder.adapter.RecycleAdapter;
import com.hht.oemscreenrecoder.adapter.RecycleGridBean;

import org.greenrobot.eventbus.EventBus;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.channels.FileChannel;
import java.util.ArrayList;
import java.util.List;

public class SaveActivity extends AppCompatActivity {
    private BaseDialog saveDialog;
    private static String TAG = "SaveActivity";

    private String filePath = "";
    // 录制停止原因
    private int stopReason = 0;

    private RecyclerView recyclerView ;
    private RecycleAdapter adapter ;

    private List<String> pathList = null;
    private List<String> nameList = null;

    private static Toast toast = null;

    private BroadcastReceiver receiverU = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            pathList = new ArrayList<>();
            nameList = new ArrayList<>();
            if (Intent.ACTION_MEDIA_MOUNTED.equals(action)) {
                // USB inserted
                String path = intent.getData().getPath();
                String usbName = new File(path).getName(); // Get USB name
                Log.d(TAG, "onReceive: +++++" + path);
                Log.d(TAG, "onReceive: +++++" + usbName);
                path = getCorrectPath(path); // Get the correct, full path
                pathList.add(path);
                nameList.add(usbName);

                UsbFlashUtil.setUsbPath(pathList);
                UsbFlashUtil.setUsbName(nameList);

                for (int i = 0; i < UsbFlashUtil.getUsbPath().size(); i++) {
                    Log.d(TAG, "------>USB path count: " + UsbFlashUtil.getUsbPath().size());
                    Log.d(TAG, "------>USB path: " + UsbFlashUtil.getUsbPath().get(i));
                }

//                if (diskListenerList.size() > 0) {
//                    for (int i = 0; i < diskListenerList.size(); i++) {
//                        if (diskListenerList.get(i) != null) {
//                            diskListenerList.get(i).onConnect();
//                        }
//                    }
//                }
            } else if (Intent.ACTION_MEDIA_UNMOUNTED.equals(action) || Intent.ACTION_MEDIA_EJECT.equals(action)) {
                List<String> list = new ArrayList<>();
                list.add("");
                UsbFlashUtil.setUsbPath(list);
                adapter.notifyDataSetChanged();
                // USB ejected
//                if (diskListenerList.size() > 0) {
//                    for (int i = 0; i < diskListenerList.size(); i++) {
//                        if (diskListenerList.get(i) != null) {
//                            diskListenerList.get(i).onDisconnect();
//                        }
//                    }
//                }
            }
        }
    };

    private BroadcastReceiver finishReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent.getAction().equals("com.example.FINISH_ACTIVITY")) {
                finish();
            }
        }
    };

// You'll need to implement or translate the following methods and classes:
// - getCorrectPath(String path)
// - UsbFlashUtil (including setUsbPath(), getUsbPath(), and setUsbName() methods)
// - diskListenerList (and its related DiskListener interface with onConnect() and onDisconnect() methods)

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        // 隐藏导航栏（可选）
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        setContentView(R.layout.save_activity_layout);
        filePath = getIntent().getStringExtra("filePath");
        // 获取录制停止原因
        stopReason = getIntent().getIntExtra("stopReason", 0);
        Log.d(TAG, "onCreate: stopReason=" + stopReason);
        registerBroadcast();

        IntentFilter filter = new IntentFilter("com.example.FINISH_ACTIVITY");
        registerReceiver(finishReceiver, filter);

        saveDialog();
    }

    private void saveDialog(){
        saveDialog = new BaseDialog(this) {
            @Override
            public int setUpLayoutId() {
                return R.layout.save_layout;
            }
            @Override
            public void convertView(ViewHolder holder, BaseDialog dialog) {
            }
        };
        // 根据是否有提示信息调整弹窗高度
        int dialogHeight = (stopReason != 0) ? 480 : 424;
        saveDialog.setSize(802, dialogHeight);
        saveDialog.setOutCancel(false);
        saveDialog.show();

        // 设置停止原因提示
        TextView stopReasonHint = saveDialog.findViewById(R.id.stop_reason_hint);
        if (stopReasonHint != null) {
            if (stopReason == 1) {
                // 时长限制
                stopReasonHint.setText(R.string.stop_reason_time_limit);
                stopReasonHint.setVisibility(View.VISIBLE);
                Log.d(TAG, "saveDialog: 显示时长限制提示");
            } else if (stopReason == 2) {
                // 空间不足
                stopReasonHint.setText(R.string.stop_reason_low_space);
                stopReasonHint.setVisibility(View.VISIBLE);
                Log.d(TAG, "saveDialog: 显示空间不足提示");
            } else {
                stopReasonHint.setVisibility(View.GONE);
            }
        }

        recyclerView = saveDialog.findViewById(R.id.usb_recycle);

        List<RecycleGridBean> data = new ArrayList<>();
        RecycleGridBean bean = new RecycleGridBean();
        bean.setDrawable(getResources().getDrawable(R.drawable.local_file));
        bean.setStorageName(getString(R.string.locality));
        data.add(bean);

        for (int i = 0; i < UsbFlashUtil.getUsbPath().size(); i++) {
            RecycleGridBean item = new RecycleGridBean();
            item.setDrawable(getResources().getDrawable(R.drawable.usb_file));
            item.setStorageName(UsbFlashUtil.getUsbName().get(i));
            data.add(item);
        }

        GridLayoutManager manager = new GridLayoutManager(this, data.size());
        recyclerView.setLayoutManager(manager);
        adapter = new RecycleAdapter(this, data);
        recyclerView.setAdapter(adapter);

        adapter.setItemCLickListener(new RecycleAdapter.ItemCLickListener() {
            @Override
            public void onItemClickListener(int position) {
                if (position > 0){
//                    复制到U盘走完复制方法之后也可以加一个延时 然后关闭弹窗
                    File sourceFile = new File(filePath);
                    File usbFile = new File(UsbFlashUtil.getUsbPath().get(position-1), "Screen Record");
                    if (!usbFile.exists()) {
                        usbFile.mkdirs();
                    }
                    try {
                        moveFile(sourceFile,usbFile);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        showToast(getApplicationContext(),R.string.save_success);
                        finish();
                    },2000);
                } else if (position == 0) {
                    //存储到本地给个假进度条，finish掉弹窗
                    //                    screenRecordHelper.deleteFile()
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        showToast(getApplicationContext(),R.string.save_success);
                        finish();
                    },2000);
                }
            }
        });
    }

    public void saveDialogDismiss(){
        Log.d("ysgysg", "saveDialogDismiss: ----" + "dismiss");
        if (saveDialog != null){
            saveDialog.dismiss();
        }
        finish();
    }

    public void registerBroadcast() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(Intent.ACTION_MEDIA_SHARED); // If SDCard is not mounted and shared via USB mass storage //如果SDCard未安装,并通过USB大容量存储共享返回
        filter.addAction(Intent.ACTION_MEDIA_MOUNTED); // Indicates that the sd object exists and has read/write permissions//表明sd对象是存在并具有读/写权限
        filter.addAction(Intent.ACTION_MEDIA_UNMOUNTED); // SDCard is unmounted, if SDCard exists but is not mounted//SDCard已卸掉,如果SDCard是存在但没有被安装
        filter.addAction(Intent.ACTION_MEDIA_CHECKING); // Indicates that the object is being disk checked //表明对象正在磁盘检查
        filter.addAction(Intent.ACTION_MEDIA_EJECT); // Physically eject SDCARD//物理的拔出 SDCARD
        filter.addAction(Intent.ACTION_MEDIA_REMOVED); // Completely removed//完全拔出
        filter.addDataScheme("file"); // This line is necessary, otherwise, the broadcast cannot be received// 必须要有此行，否则无法收到广播
        this.registerReceiver(receiverU, filter);

        // Get the USB path if the USB is already plugged in before the app starts//保存弹窗未启动前已经插着U盘的情况下，获取U盘路径
        List<String> list = getPathListByStorageManager(); // Get USB path based on StorageManager //根据StorageManager获取U盘路径
        // List<String> list = getPathByMount(); // Get USB path based on mount command

        UsbFlashUtil.setUsbPath(list);

        /**
         * 在检测到多个U盘的情况下,遍历,在每个U根目录下创建Screen Record文件夹
         */
//        if (UsbFlashUtil.getUsbPath() != null && UsbFlashUtil.getUsbPath().size() > 0) {
//            for (int i = 0; i < UsbFlashUtil.getUsbPath().size(); i++) {
//                File usbFile = new File(UsbFlashUtil.getUsbPath().get(i), "Screen Record");
//                if (!usbFile.exists()) {
//                    usbFile.mkdirs();
//                }
//            }
//        }
    }

    public List<String> getPathListByStorageManager() {
        List<String> pathList = new ArrayList<>();
        List<String> nameList = new ArrayList<>();
        try {
            StorageManager storageManager = (StorageManager) this.getSystemService(Context.STORAGE_SERVICE);
            Method method_volumeList = StorageManager.class.getMethod("getVolumeList");
            method_volumeList.setAccessible(true);
            Object[] volumeList = (Object[]) method_volumeList.invoke(storageManager);
            if (volumeList != null) {
                for (Object volume : volumeList) {
                    try {
                        Method getPath = volume.getClass().getMethod("getPath");
                        String path = (String) getPath.invoke(volume);
                        Method isRemovable = volume.getClass().getMethod("isRemovable");
                        boolean removable = (boolean) isRemovable.invoke(volume);
                        Method getState = volume.getClass().getMethod("getState");
                        String state = (String) getState.invoke(volume);

                        if (removable && "mounted".equalsIgnoreCase(state)) {
                            // 使用StorageManager获取U盘的名称  
                            android.os.storage.StorageVolume storageVolume = storageManager.getStorageVolume(new File(path));
                            String usbName = storageVolume != null ? storageVolume.getDescription(this) : "Unknown";
                            nameList.add(usbName);
                            pathList.add(getCorrectPath(path).toString()); // 假设getCorrectPath是一个静态方法  
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
            UsbFlashUtil.setUsbName(nameList); // 假设UsbFlashUtil是一个工具类，并且setUsbName是一个静态方法  
        } catch (Exception e) {
            e.printStackTrace();
        }
        return pathList;
    }


    private static String getCorrectPath(String path) {
        if (!TextUtils.isEmpty(path)) {
            int lastSeparator = path.lastIndexOf(File.separator);
            String endStr = path.substring(lastSeparator + 1);
            if (!TextUtils.isEmpty(endStr) && (endStr.contains("USB_DISK") || endStr.toLowerCase().contains("usb_disk"))) {
                File file = new File(path);
                if (file.exists() && file.listFiles() != null && file.listFiles().length == 1 && file.listFiles()[0].isDirectory()) {
                    path = file.listFiles()[0].getAbsolutePath();
                }
            }
        }
        return path;
    }

    public static boolean moveFile(File sourceFile, File destDir) throws IOException {
        // 确保源文件存在
        if (!sourceFile.exists() || !sourceFile.isFile()) {
            return false;
        }
        // 确保目标目录存在
        if (!destDir.exists()) {
            destDir.mkdirs();
        }

        // 创建目标文件路径
        File destFile = new File(destDir, sourceFile.getName());

        // 使用缓冲流复制文件
        try (FileInputStream fileInputStream = new FileInputStream(sourceFile);
             FileOutputStream fileOutputStream = new FileOutputStream(destFile);
             FileChannel source = fileInputStream.getChannel();
             FileChannel destination = fileOutputStream.getChannel()) {

            destination.transferFrom(source, 0, source.size());

        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }

        // 删除原文件
        return sourceFile.delete();
    }

    public void showToast(Context context, int resId){

        if(toast != null){
            toast.cancel();
        }
        toast = Toast.makeText(context, resId,Toast.LENGTH_SHORT);
        toast.show();
    }
    @Override
    protected void onDestroy() {
        super.onDestroy();
        this.unregisterReceiver(receiverU);
        unregisterReceiver(finishReceiver);
        if (saveDialog != null){
            saveDialog.dismiss();
        }
        //保存完成之后重新拉起服务
//        Intent intent = new Intent(this, MainActivity.class);
//        startActivity(intent);
    }
}
