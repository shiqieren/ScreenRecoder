package com.hht.oemscreenrecoder.widgets;

import android.app.Dialog;
import android.content.Context;
import android.graphics.PixelFormat;
import android.os.Bundle;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.Window;
import android.view.WindowManager;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;

public abstract class BaseDialog extends Dialog {
    @LayoutRes
    protected int mLayoutResId;
    private int mAnimStyle = 0;//进入退出动画
    private boolean mOutCancel = false;//点击外部取消
    private Context mContext;
    private int mWidth;
    private int mHeight;

    public BaseDialog(@NonNull Context context) {
        super(context);
    }

    public BaseDialog(@NonNull Context context, int themeResId) {
        super(context, themeResId);
    }

    protected BaseDialog(@NonNull Context context, boolean cancelable, @Nullable OnCancelListener cancelListener) {
        super(context, cancelable, cancelListener);
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mLayoutResId = setUpLayoutId();
        setContentView(mLayoutResId);
    }

    @Override
    public void onStart() {
        super.onStart();
        initParams();
    }

    @Override
    public void onStop() {
        super.onStop();

    }

    private void initParams() {
        Window window = getWindow();
        if(window != null){
            window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            window.setDimAmount(0.7f);
        }
        if (window != null) {
            WindowManager.LayoutParams params = window.getAttributes();
            params.format = PixelFormat.TRANSPARENT;
            params.gravity= Gravity.CENTER;
            //设置动画
            if (mAnimStyle != 0) {
                window.setWindowAnimations(mAnimStyle);
            }
            window.setAttributes(params);
        }
        setCancelable(mOutCancel);
    }

    /**
     * 获取屏幕宽度
     */
    public static int getScreenWidth(Context context) {
        WindowManager wm = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics outMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(outMetrics);
        return outMetrics.widthPixels;
    }

    /**
     * 获取屏幕高度
     */
    public static int getScreenHeight(Context context) {
        WindowManager wm = (WindowManager) context
                .getSystemService(Context.WINDOW_SERVICE);
        DisplayMetrics outMetrics = new DisplayMetrics();
        wm.getDefaultDisplay().getMetrics(outMetrics);
        return outMetrics.heightPixels;
    }

    /**
     * 设置宽高
     *
     * @param width
     * @param height
     * @return
     */
    public BaseDialog setSize(int width, int height) {
        mWidth = width;
        mHeight = height;
        return this;
    }

    /**
     * 设置进入退出动画
     *
     * @param animStyle
     * @return
     */
    public BaseDialog setAnimStyle(@StyleRes int animStyle) {
        mAnimStyle = animStyle;
        return this;
    }

    /**
     * 设置是否点击外部取消
     * @param outCancel
     * @return
     */
    public BaseDialog setOutCancel(boolean outCancel) {
        mOutCancel = outCancel;
        return this;
    }

    /**
     * 设置dialog布局
     *
     * @return
     */
    public abstract int setUpLayoutId();

    /**
     * 操作dialog布局
     * @param holder
     * @param dialog
     */
    public abstract void convertView(ViewHolder holder, BaseDialog dialog);
}