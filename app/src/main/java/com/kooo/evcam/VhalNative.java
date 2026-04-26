package com.kooo.evcam;

import android.util.Log;

/** JNI bridge for the existing vehicle VHAL decoder library. */
public final class VhalNative {
    private static final String TAG = "VhalNative";
    private static boolean sLibraryLoaded = false;

    static {
        try {
            System.loadLibrary("vhal_decoder");
            sLibraryLoaded = true;
            Log.d(TAG, "vhal_decoder loaded");
        } catch (Throwable error) {
            sLibraryLoaded = false;
            Log.e(TAG, "failed to load vhal_decoder: " + error.getMessage());
        }
    }

    private VhalNative() {}

    public static boolean isLibraryLoaded() { return sLibraryLoaded; }

    public static final int EVT_CUSTOM_KEY = 5;

    public static native String getGrpcHost();
    public static native int getGrpcPort();
    public static native String getStreamMethod();
    public static native String getSendAllMethod();
    public static native int[] decode(byte[] data);
    public static native void configureCustomKey(int speedPropId, int buttonPropId, float speedThreshold);
}
