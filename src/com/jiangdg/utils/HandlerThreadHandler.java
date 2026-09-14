package com.jiangdg.utils;

import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;

/** Compatibility class omitted from the upstream 3.2.9 AAR. */
public final class HandlerThreadHandler extends Handler {
    public static HandlerThreadHandler createHandler() {
        return createHandler("HandlerThreadHandler");
    }

    public static HandlerThreadHandler createHandler(String name) {
        HandlerThread thread = new HandlerThread(name);
        thread.start();
        return new HandlerThreadHandler(thread.getLooper());
    }

    public static HandlerThreadHandler createHandler(Callback callback) {
        return createHandler("HandlerThreadHandler", callback);
    }

    public static HandlerThreadHandler createHandler(String name, Callback callback) {
        HandlerThread thread = new HandlerThread(name);
        thread.start();
        return new HandlerThreadHandler(thread.getLooper(), callback);
    }

    private HandlerThreadHandler(Looper looper) {
        super(looper);
    }

    private HandlerThreadHandler(Looper looper, Callback callback) {
        super(looper, callback);
    }
}
