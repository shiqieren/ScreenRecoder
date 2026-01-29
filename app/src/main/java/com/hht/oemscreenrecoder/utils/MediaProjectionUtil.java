package com.hht.oemscreenrecoder.utils;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.IBinder;
import android.util.Log;

import java.lang.reflect.Method;

/**
 * 因为系统的要在Activity里面的startActivityForResult(captureIntent, RESULT_OK)回调里，
 * 才能拿的到MediaProjection实例，如果要在Service中直接是拿不到的，所以使用下面这个拿到
 * MediaProjection实例
 */

public class MediaProjectionUtil {
    private final static String TAG = "Hogan MProjectionUtil";
    private final static String PKG = "com.hht.optoma.screenrecoder";
    public static final String EXTRA_MEDIA_PROJECTION =
            "android.media.projection.extra.EXTRA_MEDIA_PROJECTION";
    private static MediaProjectionUtil INSTALL;

    private MediaProjectionUtil() {

    }

    public static MediaProjectionUtil getInstance() {
        if (INSTALL == null) {
            synchronized (MediaProjectionUtil.class) {
                if (INSTALL == null) {
                    INSTALL = new MediaProjectionUtil();
                }
            }
        }
        return INSTALL;
    }

    public MediaProjection getMediaProjection(Context context) {
        MediaProjection mediaProjection = null;
        MediaProjectionManager projectionManager = (MediaProjectionManager) context.getSystemService
                (Context.MEDIA_PROJECTION_SERVICE);
        if (projectionManager == null) {
            Log.e(TAG, "start record fail, reason : projectionManager is null");
        } else {
            Intent intent = new Intent();
            try {
                ApplicationInfo aInfo = context.getPackageManager().getApplicationInfo(PKG, 0);
                int uid = aInfo.uid;
                Log.w(TAG, "AppInfo: " + aInfo.toString());
                Class<?> smClazz = Class.forName("android.os.ServiceManager");
                Method getService = smClazz.getDeclaredMethod("getService", String.class);
                IBinder binder = (IBinder) getService.invoke(null, Context.MEDIA_PROJECTION_SERVICE);
                Class<?> mpmsClazz = Class.forName("android.media.projection.IMediaProjectionManager$Stub");
                @SuppressLint("SoonBlockedPrivateApi") Method asInterface = mpmsClazz.getDeclaredMethod("asInterface", IBinder.class);
                Object service = asInterface.invoke(null, binder);
                Class<?> IMediaProjectionManager = service.getClass();

                Method createProjection = IMediaProjectionManager.getDeclaredMethod("createProjection", int.class,
                        String.class, int.class, boolean.class);
                Object projection = createProjection.invoke(service, uid, PKG, 0, false);

                Class<?> mpmClazz = Class.forName("android.media.projection.IMediaProjection$Stub$Proxy");
                Method asBinder = mpmClazz.getDeclaredMethod("asBinder");
                IBinder projectionBinder = (IBinder) asBinder.invoke(projection);

                Class<?> intentClazz = Class.forName("android.content.Intent");
                Method putExtra = intentClazz.getDeclaredMethod("putExtra", String.class, IBinder.class);
                putExtra.invoke(intent, EXTRA_MEDIA_PROJECTION, projectionBinder);
            } catch (Exception e) {
                Log.e(TAG, "Error in getMediaProjection", e);
            }
            mediaProjection = projectionManager.getMediaProjection(Activity.RESULT_OK, intent);
            if (mediaProjection != null) {
                Log.w(TAG, "create mediaProjection successfully");
            }
        }

        return mediaProjection;
    }
}
