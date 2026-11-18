package com.livewp.app;

import android.content.SharedPreferences;

public final class WallpaperCrop {
    private WallpaperCrop() {}

    public static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }

    public static String key(String mediaUri, String axis) {
        String value = mediaUri == null ? "" : mediaUri;
        return "crop_" + axis + "_" + Integer.toHexString(value.hashCode());
    }

    public static float readX(SharedPreferences p, String mediaUri) {
        return p.getFloat(key(mediaUri, "x"), 0f);
    }

    public static float readY(SharedPreferences p, String mediaUri) {
        return p.getFloat(key(mediaUri, "y"), 0f);
    }

    public static void write(SharedPreferences.Editor e, String mediaUri, float x, float y) {
        e.putFloat(key(mediaUri, "x"), clamp(x));
        e.putFloat(key(mediaUri, "y"), clamp(y));
    }

    public static float[] coverOverflow(float mediaW, float mediaH, float outW, float outH) {
        if (mediaW <= 0 || mediaH <= 0 || outW <= 0 || outH <= 0) return new float[] {0f, 0f};
        float scale = Math.max(outW / mediaW, outH / mediaH);
        return new float[] {
            Math.max(0f, mediaW * scale - outW), Math.max(0f, mediaH * scale - outH)
        };
    }
}
