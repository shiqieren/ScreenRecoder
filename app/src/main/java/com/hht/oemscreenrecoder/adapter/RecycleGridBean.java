package com.hht.oemscreenrecoder.adapter;

import android.graphics.drawable.Drawable;

public class RecycleGridBean {
    private Drawable drawable;

    private String storageName;

    public Drawable getDrawable() {
        return drawable;
    }

    public void setDrawable(Drawable drawable) {
        this.drawable = drawable;
    }

    public String getStorageName() {
        return storageName;
    }

    public void setStorageName(String storageName) {
        this.storageName = storageName;
    }
}
