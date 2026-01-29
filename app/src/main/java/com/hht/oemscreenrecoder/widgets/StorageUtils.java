package com.hht.oemscreenrecoder.widgets;

import static com.blankj.utilcode.util.ColorUtils.getColor;

import android.app.AlertDialog;
import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.StatFs;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import com.hht.oemscreenrecoder.R;

import java.util.List;
import java.util.Timer;
import java.util.TimerTask;

public class StorageUtils {
    private static final String TAG = "StorageUtils";
    private List<String> list = null;

    //检查SD卡容量是否超过20MB
    public static boolean checkFreeSpace() {
        int minimum = 20;
        long size = minimum * 1024 * 1024;
        Log.d(TAG, "checkFreeSpace: ----" + getSDFreeSpace());
        if (getSDFreeSpace() > size) {
            return true;
        } else {
            return false;
        }
    }

    public static long getSDFreeSpace() {
        StatFs stat = new StatFs(Environment.getExternalStorageDirectory().getAbsolutePath());
        long blockSize = stat.getBlockSizeLong();
        long availableBlocks = stat.getAvailableBlocksLong();
        return availableBlocks * blockSize;
    }

    public void setTingDialog(Context context) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context);
        LayoutInflater inflater = LayoutInflater.from(context);
        View v = inflater.inflate(R.layout.setting_layout, null);
        final Dialog dialog = builder.create();
        dialog.show();
        dialog.getWindow().setContentView(v);//自定义布局应该在这里添加，要在dialog.show()的后面

//        EventBus.getDefault().post();
    }

    private void showPopupMenu(Context context) {
        LayoutInflater inflater = LayoutInflater.from(context);
        View v = inflater.inflate(R.layout.setting_layout, null);
        ImageView mic_popup = v.findViewById(R.id.mic_popup);
        TextView textView = null;
        textView.setText("");
        mic_popup.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {

            }
        });
    }

//    boolean isShow= false;
    int showIndex = 1;
    private void imgFlicker(ImageView whiteImg,ImageView redImg,ImageView blueImg,TextView rec){
        Handler handler = new Handler(Looper.getMainLooper());
        try {
            Thread.sleep(40);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        redImg.setBackground(null);
        redImg.setBackgroundResource(R.drawable.rel_layout_bg);
        whiteImg.setAlpha(0.5f);
        whiteImg.setEnabled(false);
        rec.setShadowLayer(10f,10f,5f,Color.WHITE);
        rec.setTextColor(getColor(R.color.exit_color));
        rec.setBackgroundColor(getColor(R.color.button_disable_color));
        Timer timer = new Timer() ;
        TimerTask task = new TimerTask() {
            @Override
            public void run() {
                whiteImg.setVisibility(View.GONE);
                redImg.setVisibility(View.GONE);
                blueImg.setVisibility(View.GONE);
                int index  = showIndex%3;
                switch (index) {
                    case 1:
                        whiteImg.setVisibility(View.VISIBLE);
                        break;
                    case 2:
                        redImg.setVisibility(View.VISIBLE);
                        break;
                    case 0:
                        blueImg.setVisibility(View.VISIBLE);
                        break;
                }

                showIndex++;
                if (showIndex > 3){
                    showIndex %= 3;
                }
            }
        };
        timer.schedule(task , 5000,500);
    }

}
