package com.livewp.app;

import android.app.ActivityManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Rect;
import android.graphics.SurfaceTexture;
import android.hardware.display.DisplayManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLSurface;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.Matrix;
import android.os.Build;
import android.os.PowerManager;
import android.view.Display;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

final class GpuWallpaperRenderer {
    private final Context context;
    private final int deviceDisplayShortSide;
    private RenderThread thread;
    private final Object stateLock = new Object();
    private volatile String rendererBackendPreference = "auto";
    private volatile String smartUpscaleBackendPreference = "auto";
    private volatile boolean smartUpscaleEnabledPreference = true;
    private volatile int smartUpscaleQualityPreference = 100;
    // Keep FI user preference outside the render thread so early requests and media reloads
    // survive.
    private volatile boolean frameGenerationEnabledPreference = false;
    private volatile int frameGenerationStrengthPreference = 50;
    private volatile String standaloneEffectPreference = "";
    private volatile int standaloneEffectSpeedPreference = 50;
    private volatile int standaloneEffectIntensityPreference = 50;
    private volatile boolean standaloneEffectGyroEnabledPreference = false;
    private volatile boolean standaloneEffectInwardPreference = false;
    private volatile boolean standaloneEffectAnimationEnabledPreference = true;

    interface PerformanceListener {
        void onRendererProtectionChanged(
                boolean heavyLoad, boolean hardPaused, int effectiveFps, int systemRefreshRateFps);
    }

    interface WallpaperFpsListener {
        void onWallpaperFpsChanged(int fps);
    }

    interface FailureListener {
        void onRendererFailure(String reason);
    }

    interface StandaloneEffectRecoveryListener {
        void onStandaloneEffectRecovery(
                String effectId,
                String reason,
                boolean mediaFallbackAvailable,
                boolean fallbackMediaReloaded,
                String retainedEffectId);
    }

    interface FramePresentedListener {
        void onFramePresented();
    }

    interface StandaloneEffectQualityListener {
        void onStandaloneEffectQualityChanged(String effectId, String quality);
    }

    private interface ThermalStatusCallback {
        void onThermalStatusChanged(int status);
    }

    @android.annotation.TargetApi(Build.VERSION_CODES.Q)
    private static final class ThermalStatusMonitor {
        private final PowerManager powerManager;
        private final PowerManager.OnThermalStatusChangedListener listener;

        ThermalStatusMonitor(Context context, ThermalStatusCallback callback) {
            powerManager = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
            if (powerManager == null) {
                throw new IllegalStateException("PowerManager unavailable");
            }
            listener = callback::onThermalStatusChanged;
            powerManager.addThermalStatusListener(listener);
        }

        int getCurrentThermalStatus() {
            return powerManager.getCurrentThermalStatus();
        }

        void unregister() {
            powerManager.removeThermalStatusListener(listener);
        }
    }

    interface VideoPreparedListener {
        void onVideoPrepared(int width, int height);
    }

    interface VideoFrameListener {
        void onVideoFrame(long timestampNs);
    }

    private static final class PhotoTextureResult {
        final Bitmap bitmap;
        final int sourceWidth, sourceHeight, visibleCropWidth, visibleCropHeight;
        final int workingCropWidth, workingCropHeight, sampleSize, surfaceWidth, surfaceHeight;
        final float overscanScaleX, overscanScaleY;

        PhotoTextureResult(
                Bitmap bitmap,
                int sourceWidth,
                int sourceHeight,
                int visibleCropWidth,
                int visibleCropHeight,
                int workingCropWidth,
                int workingCropHeight,
                float overscanScaleX,
                float overscanScaleY,
                int sampleSize,
                int surfaceWidth,
                int surfaceHeight) {
            this.bitmap = bitmap;
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.visibleCropWidth = visibleCropWidth;
            this.visibleCropHeight = visibleCropHeight;
            this.workingCropWidth = workingCropWidth;
            this.workingCropHeight = workingCropHeight;
            this.overscanScaleX = overscanScaleX;
            this.overscanScaleY = overscanScaleY;
            this.sampleSize = sampleSize;
            this.surfaceWidth = surfaceWidth;
            this.surfaceHeight = surfaceHeight;
        }

        void recycle() {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
        }
    }

    interface FrameGenerationEventListener {
        void onFrameGenerationEvent(boolean generatedFrame);
    }

    interface SmartUpscaleEventListener {
        void onSmartUpscaleEvent(boolean enabled, boolean working, String backend);
    }

    private volatile PerformanceListener performanceListener;
    private volatile FailureListener failureListener;
    private volatile StandaloneEffectRecoveryListener standaloneEffectRecoveryListener;
    private volatile StandaloneEffectQualityListener standaloneEffectQualityListener;
    private volatile WallpaperFpsListener wallpaperFpsListener;
    private volatile FramePresentedListener framePresentedListener;
    private volatile VideoPreparedListener videoPreparedListener;
    private volatile VideoFrameListener videoFrameListener;
    private volatile FrameGenerationEventListener frameGenerationEventListener;
    private volatile SmartUpscaleEventListener smartUpscaleEventListener;

    // Sensor input is state-based: the latest filtered angles are written here
    // and consumed by the render loop at its normal frame cadence. A settled photo
    // may be woken by meaningful accumulated motion, but its frames remain FPS-paced.
    private volatile float latestGyroRoll;
    private volatile float latestGyroPitch;
    private volatile float latestGyroDepth;
    private volatile float latestGyroRotation;

    GpuWallpaperRenderer(Context context) {
        this.context = context.getApplicationContext();

        int profileShortSide = 0;
        try {
            DeviceCapabilityProfile.Profile profile = DeviceCapabilityProfile.ensure(this.context);
            if (profile.displayWidth > 0 && profile.displayHeight > 0) {
                profileShortSide = Math.min(profile.displayWidth, profile.displayHeight);
            }
        } catch (Throwable error) {
            android.util.Log.w(
                    "GpuWallpaperRenderer",
                    "Device profile unavailable for standalone effect sizing",
                    error);
        }
        this.deviceDisplayShortSide = Math.max(0, profileShortSide);
    }

    private static String normalizeStandaloneEffect(String effectId) {
        return StandaloneEffectRegistry.normalize(effectId);
    }

    private static String normalizeRendererBackend(String value) {
        if ("modern".equalsIgnoreCase(value)) return "modern";
        if ("compatibility".equalsIgnoreCase(value)) return "compatibility";
        return "auto";
    }

    void setPerformanceListener(PerformanceListener listener) {
        performanceListener = listener;
    }

    void setWallpaperFpsListener(WallpaperFpsListener listener) {
        wallpaperFpsListener = listener;
    }

    void setFailureListener(FailureListener listener) {
        failureListener = listener;
    }

    void setStandaloneEffectRecoveryListener(StandaloneEffectRecoveryListener listener) {
        standaloneEffectRecoveryListener = listener;
    }

    void setStandaloneEffectQualityListener(StandaloneEffectQualityListener listener) {
        standaloneEffectQualityListener = listener;
    }

    private void notifyStandaloneEffectQualityChanged(String effectId, String quality) {
        StandaloneEffectQualityListener listener = standaloneEffectQualityListener;
        if (listener == null) return;
        try {
            listener.onStandaloneEffectQualityChanged(effectId, quality);
        } catch (Throwable error) {
            WallpaperBugRecorder.record(context, "GPU_EFFECT_QUALITY_LISTENER_FAILED", error);
        }
    }

    void setFramePresentedListener(FramePresentedListener listener) {
        framePresentedListener = listener;
    }

    void setVideoPreparedListener(VideoPreparedListener listener) {
        videoPreparedListener = listener;
    }

    void setVideoFrameListener(VideoFrameListener listener) {
        videoFrameListener = listener;
    }

    void setFrameGenerationEventListener(FrameGenerationEventListener listener) {
        frameGenerationEventListener = listener;
    }

    void setSmartUpscaleEventListener(SmartUpscaleEventListener listener) {
        smartUpscaleEventListener = listener;
    }

    boolean isSmartUpscaleEnabled() {
        RenderThread t = getThread();
        return t != null && t.isSmartUpscaleEnabled();
    }

    boolean isSmartUpscaleActive() {
        RenderThread t = getThread();
        return t != null && t.isSmartUpscaleActive();
    }

    String getSmartUpscaleBackend() {
        RenderThread t = getThread();
        return t == null ? "Unavailable" : t.getSmartUpscaleBackend();
    }

    void setWallpaperFpsTelemetryEnabled(boolean enabled) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setWallpaperFpsTelemetryEnabled(enabled));
    }

    void setTargetFps(int fps) {
        RenderThread t = getThread();
        if (t != null) {
            final int safe = Math.max(30, Math.min(240, fps));
            post(t, () -> t.setTargetFps(safe));
        }
    }

    void setFrameGeneration(boolean enabled, int strength) {
        int safeStrength = Math.max(0, Math.min(100, strength));
        frameGenerationEnabledPreference = enabled;
        frameGenerationStrengthPreference = safeStrength;
        RenderThread t = getThread();
        if (t != null) {
            // Retain the requested preference even when the current media is a photo,
            // but never enable FI on the photo render path.
            post(t, () -> t.setFrameGeneration(enabled && t.video, safeStrength));
        }
    }

    void setRendererBackend(String backend) {
        String normalized = normalizeRendererBackend(backend);
        rendererBackendPreference = normalized;
        RenderThread t = getThread();
        if (t != null) {
            post(t, () -> t.setRendererBackend(normalized));
        }
    }

    String getActiveRendererBackend() {
        RenderThread t = getThread();
        if (t == null || !t.ready || !t.eglReady || t.initFailure != null) {
            return "unavailable";
        }
        return "modern".equalsIgnoreCase(t.selectedRendererBackend)
                ? "modern"
                : ("compatibility".equalsIgnoreCase(t.selectedRendererBackend)
                        ? "compatibility"
                        : "unavailable");
    }

    void setSmartUpscaleBackend(String backend) {
        String normalized =
                ("sa".equalsIgnoreCase(backend)
                                || "rcas".equalsIgnoreCase(backend)
                                || "ge".equalsIgnoreCase(backend)
                                || "ls".equalsIgnoreCase(backend))
                        ? backend.toLowerCase(java.util.Locale.US)
                        : "auto";
        smartUpscaleBackendPreference = normalized;
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setSmartUpscaleBackend(normalized));
    }

    void setSmartUpscaleQuality(int quality) {
        int normalized = Math.max(0, Math.min(100, quality));
        smartUpscaleQualityPreference = normalized;
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setSmartUpscaleQuality(normalized));
    }

    void setSmartUpscaleEnabled(boolean enabled) {
        smartUpscaleEnabledPreference = enabled;
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setSmartUpscaleEnabled(enabled));
    }

    void setAmbientBackdropBlur(boolean enabled) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setAmbientBackdropBlur(enabled));
    }

    void setAutoPauseEnabled(boolean enabled) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setAutoPauseEnabled(enabled));
    }

    void setAutoLoadPaused(boolean paused) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setAutoLoadPaused(paused));
    }

    void surfaceCreated(SurfaceHolder holder) {
        ensureThread();
        final RenderThread t;
        synchronized (stateLock) {
            t = thread;
        }
        if (t != null) {
            Surface surface = holder.getSurface();
            post(t, () -> t.createWindowSurface(surface));
        }
    }

    void surfaceChanged(int width, int height) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.resize(width, height));
    }

    void surfaceDestroyed() {
        RenderThread t = getThread();
        if (t != null) post(t, t::destroyWindowSurface);
    }

    void setVisible(boolean visible) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setVisible(visible));
    }

    void setPlaybackPaused(boolean paused) {
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.setPlaybackPaused(paused));
    }

    void setGyro(
            float roll,
            float pitch,
            float depth,
            float rotationAngleDeg,
            boolean enabled,
            boolean gyro2d,
            boolean gyro3d,
            boolean gyroRotation,
            boolean gyro2dInverted,
            boolean gyro3dInverted,
            boolean gyroRotationInverted,
            int sensitivity2dX,
            int sensitivity2dY,
            int sensitivity3dX,
            int sensitivity3dY,
            int sensitivityDepth,
            int autoStabilize2dX,
            int autoStabilize2dY,
            int autoStabilize3dX,
            int autoStabilize3dY,
            int autoStabilizeZoom,
            int autoStabilizeRotation,
            float zoomResponseFactor,
            boolean edgeSafetyEnabled,
            float motionTranslationRate,
            float motionRotationRateDeg,
            float motionDepthRate,
            float motionRotationRateZDeg,
            int rotationSensitivity,
            int orientation) {
        latestGyroRoll = roll;
        latestGyroPitch = pitch;
        latestGyroDepth = depth;
        latestGyroRotation = rotationAngleDeg;
        RenderThread t = getThread();
        if (t != null)
            post(
                    t,
                    () ->
                            t.setGyro(
                                    roll,
                                    pitch,
                                    depth,
                                    rotationAngleDeg,
                                    enabled,
                                    gyro2d,
                                    gyro3d,
                                    gyroRotation,
                                    gyro2dInverted,
                                    gyro3dInverted,
                                    gyroRotationInverted,
                                    sensitivity2dX,
                                    sensitivity2dY,
                                    sensitivity3dX,
                                    sensitivity3dY,
                                    sensitivityDepth,
                                    autoStabilize2dX,
                                    autoStabilize2dY,
                                    autoStabilize3dX,
                                    autoStabilize3dY,
                                    autoStabilizeZoom,
                                    autoStabilizeRotation,
                                    zoomResponseFactor,
                                    edgeSafetyEnabled,
                                    motionTranslationRate,
                                    motionRotationRateDeg,
                                    motionDepthRate,
                                    motionRotationRateZDeg,
                                    rotationSensitivity,
                                    orientation));
    }

    void setGyroAngles(float roll, float pitch, float depth, float rotationAngleDeg) {
        latestGyroRoll = roll;
        latestGyroPitch = pitch;
        latestGyroDepth = depth;
        latestGyroRotation = rotationAngleDeg;
        RenderThread t = getThread();
        if (t != null) {
            t.requestStandaloneEffectFrameIfNeeded();
            t.requestMotionFrameIfNeeded(roll, pitch, depth, rotationAngleDeg);
        }
    }

    void setStandaloneEffect(String effectId) {
        setStandaloneEffect(effectId, 50, 50, false, false, true);
    }

    void setStandaloneEffect(
            String effectId,
            int speed,
            int intensity,
            boolean gyroEnabled,
            boolean inward,
            boolean animationEnabled) {
        String normalized = normalizeStandaloneEffect(effectId);
        if (effectId != null && !effectId.trim().isEmpty() && normalized.isEmpty()) {
            android.util.Log.w("GpuWallpaperRenderer", "Ignoring unsupported standalone effect ID");
            return;
        }
        int safeSpeed = Math.max(0, Math.min(100, speed));
        int safeIntensity = Math.max(0, Math.min(100, intensity));
        standaloneEffectPreference = normalized;
        standaloneEffectSpeedPreference = safeSpeed;
        standaloneEffectIntensityPreference = safeIntensity;
        standaloneEffectGyroEnabledPreference = gyroEnabled;
        standaloneEffectInwardPreference = inward;
        standaloneEffectAnimationEnabledPreference = animationEnabled;

        RenderThread t = getThread();
        if (t != null) {
            post(
                    t,
                    () ->
                            t.setStandaloneEffect(
                                    normalized,
                                    safeSpeed,
                                    safeIntensity,
                                    gyroEnabled,
                                    inward,
                                    animationEnabled));
        }
    }

    boolean isFrameGenerationActive() {
        RenderThread t = getThread();
        return t != null && t.isFrameGenerationActive();
    }

    void loadMedia(String media) {
        loadMedia(media, null);
    }

    void loadMedia(String media, Runnable onPreviousMediaReleased) {
        standaloneEffectPreference = "";
        ensureThread();
        RenderThread t = getThread();
        if (t != null) post(t, () -> t.loadMedia(media, onPreviousMediaReleased));
    }

    void destroy() {
        RenderThread t;
        synchronized (stateLock) {
            t = thread;
            thread = null;
        }
        if (t != null) t.shutdown();
    }

    private void ensureThread() {
        synchronized (stateLock) {
            if (thread == null) {
                thread = new RenderThread();
                thread.start();
            }
        }
        RenderThread t = getThread();
        if (t != null) t.waitUntilReady();
    }

    private RenderThread getThread() {
        synchronized (stateLock) {
            return thread;
        }
    }

    private void post(RenderThread t, Runnable command) {
        if (t == null || !t.running) return;
        t.commands.offer(command);
        synchronized (t.wakeLock) {
            t.wakeLock.notifyAll();
        }
    }

    private final class RenderThread extends Thread {
        final Object wakeLock = new Object();
        final BlockingQueue<Runnable> commands = new LinkedBlockingQueue<>();
        volatile boolean running = true;
        volatile boolean ready;
        volatile boolean eglReady;
        volatile Throwable initFailure;

        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext eglContext = EGL14.EGL_NO_CONTEXT;
        EGLSurface eglSurface = EGL14.EGL_NO_SURFACE;
        EGLConfig eglConfig;

        int width;
        int height;
        volatile boolean visible;
        volatile boolean playbackPaused;
        volatile boolean standaloneEffectMode;
        String standaloneEffectId = "";
        StandaloneWallpaperEffect activeStandaloneEffect;
        volatile StandaloneWallpaperEffect pendingStandaloneEffect;
        int standaloneEffectSpeed = 50;
        int standaloneEffectIntensity = 50;
        volatile boolean standaloneEffectGyroEnabled;
        boolean standaloneEffectInward;
        boolean standaloneEffectAnimationEnabled = true;
        int pendingEffectSpeed = 50;
        int pendingEffectIntensity = 50;
        volatile boolean pendingEffectGyroEnabled;
        boolean pendingEffectInward;
        boolean pendingEffectAnimationEnabled = true;
        volatile boolean standaloneEffectFrameUpdatePending;
        boolean standaloneEffectFramePresented;
        boolean standaloneEffectReleasePending;
        double standaloneEffectPhaseRadians;
        long standaloneEffectLastPhaseUpdateNs;
        long standaloneEffectNextFrameNs;
        int standaloneEffectSwapFailures;
        String standaloneEffectFallbackMedia = "";
        volatile boolean pausedImageFramePending;
        volatile boolean photoFramePending;
        volatile boolean motionUpdatePending;
        volatile boolean surfaceReady;

        int program2d;
        int programOes;
        int programBlur2d;
        int programBlurOes;
        int programFrameBlend;
        int frameBlendPrevTexture;
        int frameBlendCurrentTexture;
        int frameBlendFbo;
        int frameBlendWidth;
        int frameBlendHeight;
        int activeProgram;

        // Cached attribute/uniform locations. Shader lookups happen once when
        // each program is created, never from the per-frame draw paths.
        int program2dPosition = -1;
        int program2dTexCoord = -1;
        int program2dTexture = -1;
        int program2dTexMatrix = -1;
        int program2dTransform = -1;

        int programOesPosition = -1;
        int programOesTexCoord = -1;
        int programOesTexture = -1;
        int programOesTexMatrix = -1;
        int programOesTransform = -1;

        int programBlur2dPosition = -1;
        int programBlur2dTexCoord = -1;
        int programBlur2dTexture = -1;
        int programBlur2dTexMatrix = -1;
        int programBlur2dTransform = -1;
        int programBlur2dStep = -1;

        int programBlurOesPosition = -1;
        int programBlurOesTexCoord = -1;
        int programBlurOesTexture = -1;
        int programBlurOesTexMatrix = -1;
        int programBlurOesTransform = -1;
        int programBlurOesStep = -1;

        int frameBlendPosition = -1;
        int frameBlendTexCoord = -1;
        int frameBlendPrev = -1;
        int frameBlendCurrent = -1;
        int frameBlendStrength = -1;
        int frameBlendMotionField = -1;
        int frameBlendMotionSampleScale = -1;
        int frameBlendMotionFieldTransform = -1;

        int texture2d;
        int textureOes;

        // Persistent geometry buffers. These are allocated once per EGL context
        // and reused by every draw call, avoiding per-frame direct-buffer churn.
        int positionVbo;
        int canonicalTexCoordVbo;
        int dynamicTexCoordVbo;
        final FloatBuffer dynamicTexCoordUpload =
                ByteBuffer.allocateDirect(6 * 2 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();

        VideoSmartUpscaler smartUpscaler;
        // Smart Upscale is enabled at runtime for video and may be bypassed
        // automatically under sustained renderer strain.
        static final boolean SMART_UPSCALE_RUNTIME_ENABLED = true;
        boolean smartUpscaleEnabled;
        boolean smartUpscaleUserEnabled = false;
        boolean smartUpscalePublishedEnabled;
        String smartUpscalePublishedBackend = "";
        boolean smartUpscaleWorking;
        boolean smartUpscaleStrainBypassed = false;
        boolean smartUpscaleStrainDegraded = false;
        int smartUpscaleInputFbo;
        int smartUpscaleInputTexture;
        int smartUpscaleInputWidth;
        int smartUpscaleInputHeight;
        String smartUpscaleBackend = "Unavailable";
        String smartUpscaleBackendPreference = "auto";
        String smartUpscaleActivePreference = "auto";
        int smartUpscaleQuality = 100;
        String selectedRendererBackend = "compatibility";

        // Detected GPU capability tier for the current EGL context.
        int glEsMajor = 2;
        int glEsMinor = 0;
        String glEsCapabilityTier = "es2";
        boolean supportsEs32 = false;
        boolean supportsExternalVideoEs3Shader = false;
        String eglGlRenderer = "";
        String eglGlVendor = "";
        String eglGlVersion = "";
        StandaloneEffectQualityController standaloneEffectQualityController;
        volatile StandaloneEffectThermalLevel standaloneEffectThermalLevel =
                StandaloneEffectThermalLevel.NONE;
        ThermalStatusMonitor thermalStatusMonitor;

        SurfaceTexture videoTexture;
        Surface mediaSurface;
        MediaPlayer player;
        volatile boolean video;
        volatile boolean isVideoPrepared;
        volatile boolean frameAvailable;
        boolean videoPlaybackFrameConfirmed;
        boolean videoTransformDiagnosticsLogged;
        int videoFreshFrameCount;
        long videoFirstFrameArrivalNs;
        long videoPlaybackExpectedSinceNs;
        long lastVideoFrameArrivalNs;
        int videoStallRecoveryAttempts;
        boolean videoStallFailureReported;
        private volatile boolean frameGenerationActive = false;
        private volatile long lastFrameGenerationNs = 0L;
        boolean frameGenerationEnabled;
        float frameGenerationMix = 0.50f;
        long lastVideoTimestamp = -1L;
        boolean interpolationPending = false;
        boolean interpolationShownForCurrent = false;
        long frameGenerationStartNs = 0L;
        long frameGenerationIntervalNs = 16_666_667L;
        long interpolationTargetNs = 0L;
        long lastSourceTimestampNs = -1L;
        float videoSourceFps = 0f;
        int sourceFpsSamples = 0;
        boolean interpolationPreviousReady = false;

        // Lightweight motion-compensated FI. Only a tiny luminance sample is
        // copied to CPU; full video frames remain GPU-resident.
        static final int MOTION_SAMPLE_PIXEL_BUDGET = 96 * 54;
        static final int MOTION_SAMPLE_MAX_DIMENSION = 112;
        static final int MOTION_SAMPLE_MIN_DIMENSION = 32;
        static final int MOTION_GRID_MAX_CELLS = 32;
        static final int MOTION_SEARCH_RADIUS = 4;
        static final int MOTION_PATCH_RADIUS = 2;
        static final int MOTION_STATIONARY_SAD_THRESHOLD = 24;
        static final float MOTION_FIELD_MAX_PIXELS = 6f;
        int motionSampleWidth = 96;
        int motionSampleHeight = 54;
        int motionGridColumns = 8;
        int motionGridRows = 4;
        int motionGeometrySourceWidth = -1;
        int motionGeometrySourceHeight = -1;
        int motionSampleAllocatedWidth = -1;
        int motionSampleAllocatedHeight = -1;
        int motionFieldTextureWidth = -1;
        int motionFieldTextureHeight = -1;
        int motionSampleFbo;
        int motionSampleTexture;
        int motionFieldTexture;
        final ByteBuffer motionSampleReadback =
                ByteBuffer.allocateDirect(MOTION_SAMPLE_PIXEL_BUDGET * 4)
                        .order(ByteOrder.nativeOrder());
        final ByteBuffer motionFieldUpload =
                ByteBuffer.allocateDirect(MOTION_GRID_MAX_CELLS * 4).order(ByteOrder.nativeOrder());
        final byte[] motionFieldPixels = new byte[MOTION_GRID_MAX_CELLS * 4];
        final float[] regionalMotionDxPixels = new float[MOTION_GRID_MAX_CELLS];
        final float[] regionalMotionDyPixels = new float[MOTION_GRID_MAX_CELLS];
        final float[] regionalMotionConfidence = new float[MOTION_GRID_MAX_CELLS];
        byte[] previousMotionLuma;
        byte[] currentMotionLuma;
        float motionConfidence = 0f;
        int fiTargetFps = 0;

        volatile Uri currentUri;
        int imageWidth = 1;
        int imageHeight = 1;
        private boolean imageTextureIsPreCropped;
        private float imageTextureOverscanScaleX = 1f;
        private float imageTextureOverscanScaleY = 1f;
        int videoWidth = 1;
        int videoHeight = 1;

        // SurfaceTexture can change its decoder transform between frames.
        // Keep one stable transform for all FI source frames.
        final float[] videoTexMatrix = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        };
        final float[] stableVideoTexMatrix = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        };
        boolean stableVideoTexMatrixReady = false;

        float gyroRoll;
        float gyroPitch;
        float gyroDepth;
        float gyroRotation;
        int gyroRotationSensitivity = 25;
        int autoRotation = 65;
        volatile boolean gyroEnabled;
        boolean gyro2dEnabled;
        boolean gyro3dEnabled;
        boolean gyroRotationEnabled;
        boolean gyro2dInverted;
        boolean gyro3dInverted;
        boolean gyroRotationInverted;
        boolean ambientBackdropBlur = true;

        // Runtime FPS protection. The user's fixed FPS setting remains the
        // normal ceiling; Auto Pause may temporarily throttle below it only
        // after sustained renderer timing degradation.
        int requestedTargetFps = 60;
        int systemRefreshRateFps = 60;
        int effectiveTargetFps = 60;
        int autoThrottleTargetFps = 60;
        boolean autoPauseEnabled = true;
        boolean autoLoadPaused = false;

        long lastFrameEndNs = 0L;
        long nextFrameDeadlineNs = 0L;
        long healthProbeDueNs = 0L;
        long slowSinceNs = 0L;
        long stableSinceNs = 0L;
        long lastThrottleChangeNs = 0L;
        int timingSampleCount = 0;
        int recoveryGoodProbes = 0;
        boolean hardPaused = false;
        boolean motionFrozen = false;
        boolean pauseStabilizationActive = false;

        // Wallpaper FPS telemetry is opt-in. No frame timing is sampled while
        // the expanded island is hidden.
        boolean wallpaperFpsTelemetryEnabled = false;
        long wallpaperFpsWindowStartNs = 0L;
        long wallpaperFpsWindowFrames = 0L;
        int consecutiveSwapFailures = 0;

        float[] lastRenderedTransform = new float[16];
        int photoTextureTier = PHOTO_TEXTURE_INITIAL_TIER;
        boolean photoTextureRebuildPending;
        int photoTexturePendingTier = -1;
        int photoTextureRequestGeneration;
        boolean photoTextureAdaptationBlocked;
        boolean photoTextureQualityIncreaseBlocked;
        int videoIntermediateResolutionTier = 0;
        volatile float lastRenderedGyroRoll;
        volatile float lastRenderedGyroPitch;
        volatile float lastRenderedGyroDepth;
        volatile float lastRenderedGyroRotation;

        static final float PHOTO_TEXTURE_OVERSCAN_FACTOR = 1.40f;
        // The initial tier preserves the current target; adaptive tiers may go lower
        // under sustained strain or higher when the renderer has sustained headroom.
        static final float[] PHOTO_TEXTURE_QUALITY_FACTORS = {
            0.65f, 0.85f, 1.15f, 1.35f, 1.60f, 1.80f, 2.00f
        };
        static final int PHOTO_TEXTURE_INITIAL_TIER = 2;
        // Video resolution adapts only through renderer-owned FI buffers, never by
        // reconfiguring the MediaPlayer decoder or its SurfaceTexture output.
        static final float[] VIDEO_FI_RESOLUTION_FACTORS = {1.00f, 0.75f, 0.50f, 0.35f};
        static final long QUALITY_RECOVERY_CONFIRM_NS = 8_000_000_000L;
        // Start throttling before a frame deadline is missed, preserving some
        // GPU submission headroom for the launcher and other foreground apps.
        static final float RENDER_STRAIN_BUDGET_FRACTION = 0.70f;
        static final float QUALITY_RECOVERY_RENDER_BUDGET_FRACTION = 0.55f;
        static final float QUALITY_RECOVERY_INTERVAL_BUDGET_FRACTION = 1.10f;
        static final float PHOTO_MOTION_WAKE_THRESHOLD_DEG = 0.18f;
        private static final long MOTION_WAKE_DIAGNOSTICS_INTERVAL_MS = 5000L;
        private long motionWakeDiagnosticsWindowStartMs = 0L;
        private int motionWakeRequestCount = 0;
        private int motionWakeQueuedCount = 0;
        private int motionWakeCoalescedCount = 0;
        private int motionWakeBelowThresholdCount = 0;
        private int motionWakeBlockedVideoCount = 0;
        private int motionWakeBlockedVisibilityCount = 0;
        private int motionWakeBlockedSurfaceCount = 0;
        private int motionWakeBlockedPausedCount = 0;
        private int motionWakeBlockedGyroCount = 0;
        private int motionWakeBlockedMediaCount = 0;
        private float motionWakeLastDeltaDeg = 0f;
        private String motionWakeLastDecision = "none";

        static final long RENDER_STRAIN_CONFIRM_NS = 1_000_000_000L;
        static final long HARD_PAUSE_CONFIRM_NS = 2_500_000_000L;
        static final long RECOVERY_CONFIRM_NS = 4_000_000_000L;
        static final long THROTTLE_HYSTERESIS_NS = 3_000_000_000L;
        static final long HEALTH_PROBE_INTERVAL_NS = 2_000_000_000L;
        static final int HEALTH_PROBES_TO_RESUME = 3;
        int sens2dX = 50;
        int sens2dY = 25;
        int sens3dX = 25;
        int sens3dY = 25;
        int sensDepth = 25;
        int auto2dX = 25;
        int auto2dY = 25;
        int auto3dX = 25;
        int auto3dY = 25;
        int autoZoom = 25;
        float zoomResponseFactor = 0.65f;
        boolean edgeSafetyEnabled = true;
        float motionTranslationRate = 0.020f;
        float motionRotationRateDeg = 1.0f;
        float motionRotationRateZDeg = 1.0f;
        float motionDepthRate = 0.5f;
        int mediaOrientation;

        final float[] identity = {
            1f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f,
            0f, 0f, 1f, 0f,
            0f, 0f, 0f, 1f
        };

        // SurfaceTexture already supplies the decoder transform through
        // videoTexMatrix. Video and post-processed video frames therefore use
        // canonical texture coordinates; manual cropping is only for images.
        final float[] canonicalVideoTexCoords = {
            0f, 0f, 1f, 0f, 0f, 1f,
            1f, 0f, 1f, 1f, 0f, 1f
        };

        RenderThread() {
            super("LiveWP-GPU");
        }

        void waitUntilReady() {
            long end = System.currentTimeMillis() + 2000;
            while (!ready && System.currentTimeMillis() < end) {
                synchronized (wakeLock) {
                    try {
                        wakeLock.wait(20);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        @Override
        public void run() {
            WallpaperBugRecorder.record(context, "GPU_THREAD_START", "thread=" + getName());
            try {
                WallpaperBugRecorder.record(context, "GPU_EGL_INIT_START", "");
                initEgl();
                eglReady = true;
                WallpaperBugRecorder.record(context, "GPU_EGL_INIT_COMPLETE", "");
            } catch (Throwable t) {
                initFailure = t;
                eglReady = false;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EGL_INIT_FAILED",
                        t.getClass().getName()
                                + ":"
                                + String.valueOf(t.getMessage())
                                + ",eglError=0x"
                                + Integer.toHexString(EGL14.eglGetError()));
            }

            ready = true;
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }

            while (running) {
                boolean didWork = false;
                Runnable command;
                while ((command = commands.poll()) != null) {
                    didWork = true;
                    try {
                        command.run();
                    } catch (Throwable t) {
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_COMMAND_FAILED",
                                t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
                    }
                }

                boolean standaloneEffectRequested = isStandaloneEffectRendering();
                StandaloneWallpaperEffect requestedEffect =
                        pendingStandaloneEffect != null
                                ? pendingStandaloneEffect
                                : activeStandaloneEffect;
                boolean standaloneEffectReady =
                        standaloneEffectRequested
                                && requestedEffect != null
                                && requestedEffect.isReady();
                boolean animationEnabledForFrame =
                        pendingStandaloneEffect != null
                                ? pendingEffectAnimationEnabled
                                : standaloneEffectAnimationEnabled;
                long frameCheckNs = System.nanoTime();
                boolean mediaReady =
                        standaloneEffectRequested
                                ? standaloneEffectReady
                                : (video ? isVideoPrepared : (currentUri != null));
                boolean standaloneEffectUpdateDue =
                        standaloneEffectFrameUpdatePending
                                && (standaloneEffectNextFrameNs <= 0L
                                        || frameCheckNs >= standaloneEffectNextFrameNs);
                boolean standaloneEffectFrameDue =
                        standaloneEffectReady
                                && visible
                                && !playbackPaused
                                && !autoLoadPaused
                                && (animationEnabledForFrame || standaloneEffectUpdateDue)
                                && (standaloneEffectNextFrameNs <= 0L
                                        || frameCheckNs >= standaloneEffectNextFrameNs);
                boolean healthProbe =
                        StandaloneEffectLifecyclePolicy.canRunHealthProbe(
                                        autoLoadPaused,
                                        visible,
                                        surfaceReady,
                                        mediaReady,
                                        standaloneEffectRequested,
                                        animationEnabledForFrame,
                                        hardPaused)
                                && frameCheckNs >= healthProbeDueNs;
                boolean photoMotionNeedsFrame =
                        !standaloneEffectRequested
                                && !video
                                && !playbackPaused
                                && !autoLoadPaused
                                && (motionUpdatePending || !isMotionSettledForRendering());
                boolean shouldRenderFrame =
                        standaloneEffectRequested
                                ? StandaloneEffectLifecyclePolicy.shouldRenderStandaloneEffectFrame(
                                        standaloneEffectReady,
                                        standaloneEffectFrameDue,
                                        standaloneEffectUpdateDue,
                                        visible,
                                        playbackPaused,
                                        standaloneEffectFramePresented,
                                        healthProbe,
                                        animationEnabledForFrame,
                                        hardPaused)
                                : (video
                                        ? (!playbackPaused
                                                || healthProbe
                                                || pauseStabilizationActive)
                                        : (photoFramePending
                                                || pausedImageFramePending
                                                || healthProbe
                                                || pauseStabilizationActive
                                                || photoMotionNeedsFrame));

                if (running
                        && surfaceReady
                        && (visible
                                || (!standaloneEffectRequested
                                        && !video
                                        && pauseStabilizationActive))
                        && mediaReady
                        && shouldRenderFrame) {
                    // Consume the latest sensor state only when a normal render is
                    // actually due. This preserves gyro responsiveness without
                    // allowing sensor frequency to drive the render rate.
                    gyroRoll = latestGyroRoll;
                    gyroPitch = latestGyroPitch;
                    gyroDepth = latestGyroDepth;
                    gyroRotation = latestGyroRotation;

                    long frameStartNs = System.nanoTime();
                    renderFrame();
                    long frameEndNs = System.nanoTime();
                    long renderDurationNs = Math.max(0L, frameEndNs - frameStartNs);
                    long frameIntervalNs =
                            lastFrameEndNs <= 0L ? 0L : Math.max(0L, frameEndNs - lastFrameEndNs);
                    lastFrameEndNs = frameEndNs;

                    if (!standaloneEffectRequested
                            && !video
                            && eglReady
                            && surfaceReady
                            && eglSurface != EGL14.EGL_NO_SURFACE) {
                        photoFramePending = false;
                        if (playbackPaused && !healthProbe) pausedImageFramePending = false;
                        synchronized (wakeLock) {
                            lastRenderedGyroRoll = gyroRoll;
                            lastRenderedGyroPitch = gyroPitch;
                            lastRenderedGyroDepth = gyroDepth;
                            lastRenderedGyroRotation = gyroRotation;
                            motionUpdatePending =
                                    hasMeaningfulGyroDelta(
                                            latestGyroRoll, latestGyroPitch,
                                            latestGyroDepth, latestGyroRotation);
                        }
                    }
                    if (healthProbe) {
                        healthProbeDueNs = frameEndNs + HEALTH_PROBE_INTERVAL_NS;
                    }

                    recordFramePerformance(frameIntervalNs, renderDurationNs, healthProbe);
                    recordStandaloneEffectQuality(
                            frameEndNs,
                            frameIntervalNs,
                            renderDurationNs,
                            standaloneEffectRequested,
                            healthProbe);
                    recordWallpaperFps(healthProbe, frameEndNs);

                    if (pauseStabilizationActive && isMotionAtRest()) {
                        motionTx = 0f;
                        motionTy = 0f;
                        motionRx = 0f;
                        motionRy = 0f;
                        motionTz = 0f;
                        motionZoom = 1f;
                        targetTx = 0f;
                        targetTy = 0f;
                        targetRx = 0f;
                        targetRy = 0f;
                        targetTz = 0f;
                        targetZoom = 1f;
                        motionFrozen = true;
                        pauseStabilizationActive = false;
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_AUTO_STABILIZE_COMPLETE",
                                "reason=power_or_renderer_pause");
                    }

                    didWork = true;

                    if (isStandaloneEffectRendering()) {
                        paceStandaloneEffectFrame(frameStartNs, frameEndNs);
                    } else if (!autoLoadPaused || pauseStabilizationActive) {
                        paceNextFrame(frameEndNs);
                    }
                }

                if (!didWork) {
                    waitForRendererWork();
                }
            }

            releaseMedia();
            clearStandaloneEffectResources();
            destroyWindowSurfaceInternal();
            destroyGl();
            destroyEgl();
        }

        void initEgl() {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EGL_DISPLAY_READY",
                    "displayValid=" + (display != EGL14.EGL_NO_DISPLAY));
            if (display == EGL14.EGL_NO_DISPLAY)
                throw new IllegalStateException("eglGetDisplay failed");

            int[] version = new int[2];
            if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
                throw new IllegalStateException("eglInitialize failed");
            }

            final int EGL_OPENGL_ES3_BIT_KHR = 0x40;
            final String requestedRenderer = normalizeRendererBackend(rendererBackendPreference);
            boolean modernContextCreated = false;

            EGLConfig[] configs = new EGLConfig[1];
            int[] count = new int[1];

            if (!"compatibility".equals(requestedRenderer)) {
                int[] es3ConfigAttrs = {
                    EGL14.EGL_RED_SIZE,
                    8,
                    EGL14.EGL_GREEN_SIZE,
                    8,
                    EGL14.EGL_BLUE_SIZE,
                    8,
                    EGL14.EGL_ALPHA_SIZE,
                    8,
                    EGL14.EGL_RENDERABLE_TYPE,
                    EGL14.EGL_OPENGL_ES2_BIT | EGL_OPENGL_ES3_BIT_KHR,
                    EGL14.EGL_SURFACE_TYPE,
                    EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
                };

                boolean configFound =
                        EGL14.eglChooseConfig(display, es3ConfigAttrs, 0, configs, 0, 1, count, 0)
                                && count[0] > 0;
                if (configFound) {
                    eglConfig = configs[0];
                    int[] contextAttrs3 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE};
                    eglContext =
                            EGL14.eglCreateContext(
                                    display, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttrs3, 0);
                    modernContextCreated = eglContext != EGL14.EGL_NO_CONTEXT;
                }
            }

            if (!modernContextCreated) {
                int[] es2ConfigAttrs = {
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                    EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                    EGL14.EGL_NONE
                };

                if (!EGL14.eglChooseConfig(display, es2ConfigAttrs, 0, configs, 0, 1, count, 0)
                        || count[0] == 0) {
                    throw new IllegalStateException("eglChooseConfig failed");
                }

                eglConfig = configs[0];
                int[] contextAttrs2 = {EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE};
                eglContext =
                        EGL14.eglCreateContext(
                                display, eglConfig, EGL14.EGL_NO_CONTEXT, contextAttrs2, 0);
            }

            selectedRendererBackend = modernContextCreated ? "modern" : "compatibility";
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EGL_CONTEXT_CREATE",
                    "preference="
                            + requestedRenderer
                            + ", selected="
                            + selectedRendererBackend
                            + ", contextValid="
                            + (eglContext != EGL14.EGL_NO_CONTEXT));
            if (eglContext == EGL14.EGL_NO_CONTEXT) {
                throw new IllegalStateException("eglCreateContext failed");
            }

            int[] pbufferAttrs = {EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE};
            EGLSurface pbuffer = EGL14.eglCreatePbufferSurface(display, eglConfig, pbufferAttrs, 0);
            if (pbuffer == EGL14.EGL_NO_SURFACE
                    || !EGL14.eglMakeCurrent(display, pbuffer, pbuffer, eglContext)) {
                throw new IllegalStateException("eglMakeCurrent bootstrap failed");
            }

            String glVersion = GLES20.glGetString(GLES20.GL_VERSION);
            String glRenderer = GLES20.glGetString(GLES20.GL_RENDERER);
            String glVendor = GLES20.glGetString(GLES20.GL_VENDOR);
            eglGlVersion = glVersion == null ? "" : glVersion;
            eglGlRenderer = glRenderer == null ? "" : glRenderer;
            eglGlVendor = glVendor == null ? "" : glVendor;
            registerThermalStatusMonitor();
            detectGlCapabilities(glVersion);
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EGL_CONTEXT_READY",
                    "preference="
                            + rendererBackendPreference
                            + ", selected="
                            + selectedRendererBackend
                            + ", glVersion="
                            + glVersion
                            + ", glRenderer="
                            + glRenderer
                            + ", glVendor="
                            + glVendor
                            + ",capabilityTier="
                            + glEsCapabilityTier
                            + ",supportsEs32="
                            + supportsEs32
                            + ",externalVideoEs3Shader="
                            + supportsExternalVideoEs3Shader);
            long shaderProgramStartNs;
            try {
                WallpaperBugRecorder.record(
                        context, "GPU_SHADER_PROGRAM_START", "name=program2d,eager=true");
                shaderProgramStartNs = System.nanoTime();
                program2d = createProgram(false);
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=program2d,eager=true,durationMs="
                                + ((System.nanoTime() - shaderProgramStartNs) / 1_000_000L));
            } catch (Throwable modernShaderFailure) {
                if (!isModernShaderPath()) throw modernShaderFailure;

                WallpaperBugRecorder.record(
                        context,
                        "GPU_MODERN_SHADER_FALLBACK",
                        modernShaderFailure.getClass().getName()
                                + ":"
                                + String.valueOf(modernShaderFailure.getMessage()));

                // Preserve the ES3 context. Fall back only the shader language so
                // an ES3 shader incompatibility cannot break wallpaper rendering.
                destroyGl();
                selectedRendererBackend = "compatibility";
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_START",
                        "name=program2d,eager=true,fallback=true");
                shaderProgramStartNs = System.nanoTime();
                program2d = createProgram(false);
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=program2d,eager=true,fallback=true,durationMs="
                                + ((System.nanoTime() - shaderProgramStartNs) / 1_000_000L));
            }
            if (SMART_UPSCALE_RUNTIME_ENABLED) {
                smartUpscaler = new VideoSmartUpscaler();
                smartUpscaleBackendPreference =
                        GpuWallpaperRenderer.this.smartUpscaleBackendPreference;
                smartUpscaleQuality = GpuWallpaperRenderer.this.smartUpscaleQualityPreference;
                smartUpscaleUserEnabled = GpuWallpaperRenderer.this.smartUpscaleEnabledPreference;
                smartUpscaler.initialize(smartUpscaleBackendPreference, isModernShaderPath());
                smartUpscaler.setQuality(smartUpscaleQuality);
                smartUpscaleBackend = smartUpscaler.getBackendName();
                String smartUpscaleFallbackReason = smartUpscaler.getFallbackReason();
                if (smartUpscaleFallbackReason != null && !smartUpscaleFallbackReason.isEmpty()) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_SMART_UPSCALE_FALLBACK",
                            "preference="
                                    + smartUpscaleBackendPreference
                                    + ",backend="
                                    + smartUpscaleBackend
                                    + ",reason="
                                    + smartUpscaleFallbackReason);
                }
            } else {
                smartUpscaler = null;
                smartUpscaleEnabled = false;
                smartUpscaleUserEnabled = false;
                smartUpscaleBackend = "Disabled";
            }
            WallpaperBugRecorder.record(
                    context,
                    "GPU_GL_PROGRAM_READY",
                    "smartUpscaleRuntimeEnabled="
                            + SMART_UPSCALE_RUNTIME_ENABLED
                            + ",smartUpscaleBackend="
                            + smartUpscaleBackend);
            cacheProgram2dLocations();
            texture2d = createTexture(GLES20.GL_TEXTURE_2D);
            textureOes = createTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES);
            initGeometryBuffers();
            WallpaperBugRecorder.record(
                    context, "GPU_TEXTURES_READY", "tex2d=" + texture2d + ",texOes=" + textureOes);

            EGL14.eglDestroySurface(display, pbuffer);
            EGL14.eglMakeCurrent(
                    display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);

            WallpaperBugRecorder.record(
                    context,
                    "EGL_INITIALIZED",
                    "renderer="
                            + selectedRendererBackend
                            + ", preference="
                            + rendererBackendPreference
                            + ", glVersion="
                            + glVersion);
        }

        private void detectGlCapabilities(String glVersion) {
            int major = 2;
            int minor = 0;
            if (glVersion != null) {
                java.util.regex.Matcher matcher =
                        java.util.regex.Pattern.compile("OpenGL\\s+ES\\s+(\\d+)\\.(\\d+)")
                                .matcher(glVersion);
                if (matcher.find()) {
                    try {
                        major = Integer.parseInt(matcher.group(1));
                        minor = Integer.parseInt(matcher.group(2));
                    } catch (NumberFormatException ignored) {
                        major = 2;
                        minor = 0;
                    }
                }
            }

            glEsMajor = major;
            glEsMinor = minor;

            if (major >= 3) {
                if (major > 3 || minor >= 2) {
                    glEsCapabilityTier = "es3.2";
                } else if (minor >= 1) {
                    glEsCapabilityTier = "es3.1";
                } else {
                    glEsCapabilityTier = "es3.0";
                }
            } else {
                glEsCapabilityTier = "es2";
            }

            supportsEs32 = major > 3 || (major == 3 && minor >= 2);

            String extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS);
            supportsExternalVideoEs3Shader =
                    extensions != null && extensions.contains("GL_OES_EGL_image_external_essl3");

            WallpaperBugRecorder.record(
                    context,
                    "GPU_CAPABILITY_DETECTED",
                    "glEsVersion="
                            + glEsMajor
                            + "."
                            + glEsMinor
                            + ",capabilityTier="
                            + glEsCapabilityTier
                            + ",supportsEs32="
                            + supportsEs32
                            + ",externalVideoEs3Shader="
                            + supportsExternalVideoEs3Shader);
        }

        boolean isModernShaderPath() {
            return "modern".equals(selectedRendererBackend);
        }

        int createProgram(boolean external) {
            if (isModernShaderPath()) {
                String vertex =
                        "#version 300 es\n"
                                + "in vec2 aPosition;in vec2 aTexCoord;"
                                + "uniform mat4 uTexMatrix;uniform mat4 uTransform;"
                                + "out vec2 vTexCoord;"
                                + "void main(){"
                                + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                                + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;"
                                + "}";
                String fragment =
                        external
                                ? "#version 300 es\n"
                                        + "#extension GL_OES_EGL_image_external_essl3 : require\n"
                                        + "precision mediump float;"
                                        + "uniform samplerExternalOES uTexture;"
                                        + "in vec2 vTexCoord;out vec4 fragColor;"
                                        + "void main(){fragColor=texture(uTexture,vTexCoord);}"
                                : "#version 300 es\n"
                                        + "precision mediump float;"
                                        + "uniform sampler2D uTexture;"
                                        + "in vec2 vTexCoord;out vec4 fragColor;"
                                        + "void main(){fragColor=texture(uTexture,vTexCoord);}";
                return createLinkedProgram(vertex, fragment, "modern program link failed: ");
            }

            String vertex =
                    "attribute vec2 aPosition;attribute vec2 aTexCoord;"
                            + "uniform mat4 uTexMatrix;uniform mat4 uTransform;"
                            + "varying vec2 vTexCoord;"
                            + "void main(){"
                            + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                            + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;"
                            + "}";
            String fragment =
                    external
                            ? "#extension GL_OES_EGL_image_external : require\n"
                                    + "precision mediump float;uniform samplerExternalOES uTexture;"
                                    + "varying vec2 vTexCoord;"
                                    + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}"
                            : "precision mediump float;uniform sampler2D uTexture;"
                                    + "varying vec2 vTexCoord;"
                                    + "void main(){gl_FragColor=texture2D(uTexture,vTexCoord);}";
            return createLinkedProgram(vertex, fragment, "program link failed: ");
        }

        int createBlurProgram(boolean external) {
            if (isModernShaderPath()) {
                String vertex =
                        "#version 300 es\n"
                                + "in vec2 aPosition;in vec2 aTexCoord;"
                                + "uniform mat4 uTexMatrix;uniform mat4 uTransform;"
                                + "out vec2 vTexCoord;"
                                + "void main(){"
                                + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                                + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;"
                                + "}";
                String fragment =
                        external
                                ? "#version 300 es\n"
                                        + "#extension GL_OES_EGL_image_external_essl3 : require\n"
                                        + "precision mediump float;"
                                        + "uniform samplerExternalOES uTexture;uniform vec2 uBlurStep;"
                                        + "in vec2 vTexCoord;out vec4 fragColor;"
                                        + "void main(){"
                                        + "vec2 s=uBlurStep;"
                                        + "vec4 c=texture(uTexture,vTexCoord)*0.25;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,0.0))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(s.x,0.0))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(0.0,s.y))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(0.0,s.y))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,-s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(-s.x,s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(s.x,s.y))*0.0625;"
                                        + "fragColor=vec4(c.rgb*0.82,1.0);"
                                        + "}"
                                : "#version 300 es\n"
                                        + "precision mediump float;"
                                        + "uniform sampler2D uTexture;uniform vec2 uBlurStep;"
                                        + "in vec2 vTexCoord;out vec4 fragColor;"
                                        + "void main(){"
                                        + "vec2 s=uBlurStep;"
                                        + "vec4 c=texture(uTexture,vTexCoord)*0.25;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,0.0))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(s.x,0.0))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(0.0,s.y))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(0.0,s.y))*0.125;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(s.x,-s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord+vec2(-s.x,s.y))*0.0625;"
                                        + "c+=texture(uTexture,vTexCoord-vec2(s.x,s.y))*0.0625;"
                                        + "fragColor=vec4(c.rgb*0.82,1.0);"
                                        + "}";
                return createLinkedProgram(vertex, fragment, "modern blur program link failed: ");
            }

            String vertex =
                    "attribute vec2 aPosition;attribute vec2 aTexCoord;"
                            + "uniform mat4 uTexMatrix;uniform mat4 uTransform;"
                            + "varying vec2 vTexCoord;"
                            + "void main(){"
                            + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                            + "vTexCoord=(uTexMatrix*vec4(aTexCoord,0.0,1.0)).xy;"
                            + "}";
            String fragment =
                    external
                            ? "#extension GL_OES_EGL_image_external : require\n"
                                    + "precision mediump float;uniform samplerExternalOES uTexture;"
                                    + "uniform vec2 uBlurStep;varying vec2 vTexCoord;"
                                    + "void main(){"
                                    + "vec2 s=uBlurStep;"
                                    + "vec4 c=texture2D(uTexture,vTexCoord)*0.25;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,0.0))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(s.x,0.0))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(0.0,s.y))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(0.0,s.y))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,-s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(-s.x,s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(s.x,s.y))*0.0625;"
                                    + "gl_FragColor=vec4(c.rgb*0.82,1.0);"
                                    + "}"
                            : "precision mediump float;uniform sampler2D uTexture;uniform vec2 uBlurStep;"
                                    + "varying vec2 vTexCoord;"
                                    + "void main(){"
                                    + "vec2 s=uBlurStep;"
                                    + "vec4 c=texture2D(uTexture,vTexCoord)*0.25;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,0.0))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(s.x,0.0))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(0.0,s.y))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(0.0,s.y))*0.125;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(s.x,-s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord+vec2(-s.x,s.y))*0.0625;"
                                    + "c+=texture2D(uTexture,vTexCoord-vec2(s.x,s.y))*0.0625;"
                                    + "gl_FragColor=vec4(c.rgb*0.82,1.0);"
                                    + "}";
            return createLinkedProgram(vertex, fragment, "blur program link failed: ");
        }

        int createFrameBlendProgram() {
            String v;
            String q;
            if (isModernShaderPath()) {
                v =
                        "#version 300 es\n"
                                + "in vec2 aPosition;in vec2 aTexCoord;out vec2 vTexCoord;"
                                + "void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
                q =
                        "#version 300 es\n"
                                + "precision mediump float;"
                                + "uniform sampler2D uPrev;uniform sampler2D uCurrent;"
                                + "uniform float uStrength;uniform sampler2D uMotionField;"
                                + "uniform vec2 uMotionSampleScale;uniform vec4 uMotionFieldTransform;"
                                + "in vec2 vTexCoord;out vec4 fragColor;"
                                + "void main(){"
                                + "vec2 fieldUv=clamp(vTexCoord*uMotionFieldTransform.xy"
                                + "+uMotionFieldTransform.zw,0.0,1.0);"
                                + "vec4 motionSample=texture(uMotionField,fieldUv);"
                                + "float confidence=clamp(motionSample.b,0.0,1.0);"
                                + "float strength=clamp(uStrength,0.0,1.0);"
                                + "vec2 motion=vec2((motionSample.r*2.0-1.0)*uMotionSampleScale.x,"
                                + "(motionSample.g*2.0-1.0)*uMotionSampleScale.y);"
                                + "vec2 prevUv=clamp(vTexCoord-motion*0.5,0.0,1.0);"
                                + "vec2 currentUv=clamp(vTexCoord+motion*0.5,0.0,1.0);"
                                + "vec3 previous=texture(uPrev,prevUv).rgb;"
                                + "vec3 currentWarped=texture(uCurrent,currentUv).rgb;"
                                + "vec3 current=texture(uCurrent,vTexCoord).rgb;"
                                + "vec3 generated=0.5*(previous+currentWarped);"
                                + "float mismatch=length(previous-currentWarped);"
                                + "float consistency=1.0-smoothstep(0.06,0.28,mismatch);"
                                + "float contribution=strength*confidence*consistency;"
                                + "fragColor=vec4(mix(current,generated,contribution),1.0);"
                                + "}";
            } else {
                v =
                        "attribute vec2 aPosition;attribute vec2 aTexCoord;varying vec2 vTexCoord;void main(){gl_Position=vec4(aPosition,0.0,1.0);vTexCoord=aTexCoord;}";
                // Conservative bidirectional midpoint reconstruction. User strength
                // controls how strongly the generated frame is used; motion itself is
                // never weakened by strength because partial warping creates ghosts.
                // The local consistency test rejects occlusion/disocclusion areas and
                // falling-back pixels stay on the real current frame instead of tearing.
                q =
                        "precision mediump float;uniform sampler2D uPrev;uniform sampler2D uCurrent;uniform sampler2D uMotionField;uniform float uStrength;uniform vec2 uMotionSampleScale;uniform vec4 uMotionFieldTransform;varying vec2 vTexCoord;void main(){vec2 fieldUv=clamp(vTexCoord*uMotionFieldTransform.xy+uMotionFieldTransform.zw,0.0,1.0);vec4 motionSample=texture2D(uMotionField,fieldUv);float confidence=clamp(motionSample.b,0.0,1.0);float strength=clamp(uStrength,0.0,1.0);vec2 motion=vec2((motionSample.r*2.0-1.0)*uMotionSampleScale.x,(motionSample.g*2.0-1.0)*uMotionSampleScale.y);vec2 prevUv=clamp(vTexCoord-motion*0.5,0.0,1.0);vec2 currentUv=clamp(vTexCoord+motion*0.5,0.0,1.0);vec3 previous=texture2D(uPrev,prevUv).rgb;vec3 currentWarped=texture2D(uCurrent,currentUv).rgb;vec3 current=texture2D(uCurrent,vTexCoord).rgb;vec3 generated=0.5*(previous+currentWarped);float mismatch=length(previous-currentWarped);float consistency=1.0-smoothstep(0.06,0.28,mismatch);float contribution=strength*confidence*consistency;gl_FragColor=vec4(mix(current,generated,contribution),1.0);}";
            }
            return createLinkedProgram(v, q, "frame blend program link failed: ");
        }

        private void initGeometryBuffers() {
            releaseGeometryBuffers();

            float[] positions = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, 1f};

            int[] ids = new int[1];

            GLES20.glGenBuffers(1, ids, 0);
            positionVbo = ids[0];
            FloatBuffer positionData = directFloats(positions);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, positionVbo);
            GLES20.glBufferData(
                    GLES20.GL_ARRAY_BUFFER,
                    positions.length * 4,
                    positionData,
                    GLES20.GL_STATIC_DRAW);

            GLES20.glGenBuffers(1, ids, 0);
            canonicalTexCoordVbo = ids[0];
            FloatBuffer canonicalData = directFloats(canonicalVideoTexCoords);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, canonicalTexCoordVbo);
            GLES20.glBufferData(
                    GLES20.GL_ARRAY_BUFFER,
                    canonicalVideoTexCoords.length * 4,
                    canonicalData,
                    GLES20.GL_STATIC_DRAW);

            GLES20.glGenBuffers(1, ids, 0);
            dynamicTexCoordVbo = ids[0];
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynamicTexCoordVbo);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER, 6 * 2 * 4, null, GLES20.GL_DYNAMIC_DRAW);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }

        private void uploadDynamicTexCoords(float[] coords) {
            if (dynamicTexCoordVbo == 0 || coords == null || coords.length != 12) {
                throw new IllegalStateException("dynamic texture-coordinate VBO unavailable");
            }

            dynamicTexCoordUpload.position(0);
            dynamicTexCoordUpload.put(coords);
            dynamicTexCoordUpload.position(0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, dynamicTexCoordVbo);
            GLES20.glBufferSubData(
                    GLES20.GL_ARRAY_BUFFER, 0, coords.length * 4, dynamicTexCoordUpload);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }

        private void releaseGeometryBuffers() {
            if (positionVbo != 0 || canonicalTexCoordVbo != 0 || dynamicTexCoordVbo != 0) {
                int[] ids = {positionVbo, canonicalTexCoordVbo, dynamicTexCoordVbo};
                GLES20.glDeleteBuffers(3, ids, 0);
            }
            positionVbo = 0;
            canonicalTexCoordVbo = 0;
            dynamicTexCoordVbo = 0;
        }

        private void bindGeometryAttributes(
                int positionLocation, int texCoordLocation, int texCoordVbo) {
            if (positionVbo == 0 || texCoordVbo == 0) {
                throw new IllegalStateException("geometry VBO unavailable");
            }

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, positionVbo);
            GLES20.glEnableVertexAttribArray(positionLocation);
            GLES20.glVertexAttribPointer(positionLocation, 2, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, texCoordVbo);
            GLES20.glEnableVertexAttribArray(texCoordLocation);
            GLES20.glVertexAttribPointer(texCoordLocation, 2, GLES20.GL_FLOAT, false, 0, 0);

            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        }

        private void cacheProgram2dLocations() {
            program2dPosition = GLES20.glGetAttribLocation(program2d, "aPosition");
            program2dTexCoord = GLES20.glGetAttribLocation(program2d, "aTexCoord");
            program2dTexture = GLES20.glGetUniformLocation(program2d, "uTexture");
            program2dTexMatrix = GLES20.glGetUniformLocation(program2d, "uTexMatrix");
            program2dTransform = GLES20.glGetUniformLocation(program2d, "uTransform");
        }

        private void cacheProgramOesLocations() {
            programOesPosition = GLES20.glGetAttribLocation(programOes, "aPosition");
            programOesTexCoord = GLES20.glGetAttribLocation(programOes, "aTexCoord");
            programOesTexture = GLES20.glGetUniformLocation(programOes, "uTexture");
            programOesTexMatrix = GLES20.glGetUniformLocation(programOes, "uTexMatrix");
            programOesTransform = GLES20.glGetUniformLocation(programOes, "uTransform");
        }

        private void cacheProgramBlur2dLocations() {
            programBlur2dPosition = GLES20.glGetAttribLocation(programBlur2d, "aPosition");
            programBlur2dTexCoord = GLES20.glGetAttribLocation(programBlur2d, "aTexCoord");
            programBlur2dTexture = GLES20.glGetUniformLocation(programBlur2d, "uTexture");
            programBlur2dTexMatrix = GLES20.glGetUniformLocation(programBlur2d, "uTexMatrix");
            programBlur2dTransform = GLES20.glGetUniformLocation(programBlur2d, "uTransform");
            programBlur2dStep = GLES20.glGetUniformLocation(programBlur2d, "uBlurStep");
        }

        private void cacheProgramBlurOesLocations() {
            programBlurOesPosition = GLES20.glGetAttribLocation(programBlurOes, "aPosition");
            programBlurOesTexCoord = GLES20.glGetAttribLocation(programBlurOes, "aTexCoord");
            programBlurOesTexture = GLES20.glGetUniformLocation(programBlurOes, "uTexture");
            programBlurOesTexMatrix = GLES20.glGetUniformLocation(programBlurOes, "uTexMatrix");
            programBlurOesTransform = GLES20.glGetUniformLocation(programBlurOes, "uTransform");
            programBlurOesStep = GLES20.glGetUniformLocation(programBlurOes, "uBlurStep");
        }

        private void cacheProgramFrameBlendLocations() {
            frameBlendPosition = GLES20.glGetAttribLocation(programFrameBlend, "aPosition");
            frameBlendTexCoord = GLES20.glGetAttribLocation(programFrameBlend, "aTexCoord");
            frameBlendPrev = GLES20.glGetUniformLocation(programFrameBlend, "uPrev");
            frameBlendCurrent = GLES20.glGetUniformLocation(programFrameBlend, "uCurrent");
            frameBlendStrength = GLES20.glGetUniformLocation(programFrameBlend, "uStrength");
            frameBlendMotionField = GLES20.glGetUniformLocation(programFrameBlend, "uMotionField");
            frameBlendMotionSampleScale =
                    GLES20.glGetUniformLocation(programFrameBlend, "uMotionSampleScale");
            frameBlendMotionFieldTransform =
                    GLES20.glGetUniformLocation(programFrameBlend, "uMotionFieldTransform");
        }

        private boolean ensureProgramOes() {
            if (programOes != 0) return true;
            try {
                WallpaperBugRecorder.record(
                        context, "GPU_SHADER_PROGRAM_START", "name=programOes,lazy=true");
                long startNs = System.nanoTime();
                programOes = createProgram(true);
                cacheProgramOesLocations();
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=programOes,lazy=true,durationMs="
                                + ((System.nanoTime() - startNs) / 1_000_000L));
                return true;
            } catch (Throwable t) {
                programOes = 0;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_LAZY_FAILED",
                        "name=programOes,error="
                                + t.getClass().getName()
                                + ":"
                                + String.valueOf(t.getMessage()));
                notifyRendererFailure("programOes_lazy_failed");
                return false;
            }
        }

        private boolean ensureProgramBlur2d() {
            if (programBlur2d != 0) return true;
            try {
                WallpaperBugRecorder.record(
                        context, "GPU_SHADER_PROGRAM_START", "name=programBlur2d,lazy=true");
                long startNs = System.nanoTime();
                programBlur2d = createBlurProgram(false);
                cacheProgramBlur2dLocations();
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=programBlur2d,lazy=true,durationMs="
                                + ((System.nanoTime() - startNs) / 1_000_000L));
                return true;
            } catch (Throwable t) {
                programBlur2d = 0;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_LAZY_FAILED",
                        "name=programBlur2d,error="
                                + t.getClass().getName()
                                + ":"
                                + String.valueOf(t.getMessage()));
                return false;
            }
        }

        private boolean ensureProgramBlurOes() {
            if (programBlurOes != 0) return true;
            try {
                WallpaperBugRecorder.record(
                        context, "GPU_SHADER_PROGRAM_START", "name=programBlurOes,lazy=true");
                long startNs = System.nanoTime();
                programBlurOes = createBlurProgram(true);
                cacheProgramBlurOesLocations();
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=programBlurOes,lazy=true,durationMs="
                                + ((System.nanoTime() - startNs) / 1_000_000L));
                return true;
            } catch (Throwable t) {
                programBlurOes = 0;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_LAZY_FAILED",
                        "name=programBlurOes,error="
                                + t.getClass().getName()
                                + ":"
                                + String.valueOf(t.getMessage()));
                return false;
            }
        }

        private boolean ensureProgramFrameBlend() {
            if (programFrameBlend != 0) return true;
            try {
                WallpaperBugRecorder.record(
                        context, "GPU_SHADER_PROGRAM_START", "name=programFrameBlend,lazy=true");
                long startNs = System.nanoTime();
                programFrameBlend = createFrameBlendProgram();
                cacheProgramFrameBlendLocations();
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_READY",
                        "name=programFrameBlend,lazy=true,durationMs="
                                + ((System.nanoTime() - startNs) / 1_000_000L));
                return true;
            } catch (Throwable t) {
                programFrameBlend = 0;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SHADER_PROGRAM_LAZY_FAILED",
                        "name=programFrameBlend,error="
                                + t.getClass().getName()
                                + ":"
                                + String.valueOf(t.getMessage()));
                return false;
            }
        }

        void setRendererBackend(String backend) {
            rendererBackendPreference = normalizeRendererBackend(backend);
        }

        int createLinkedProgram(String vertex, String fragment, String errorPrefix) {
            int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
            int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
            int p = GLES20.glCreateProgram();
            GLES20.glAttachShader(p, vs);
            GLES20.glAttachShader(p, fs);
            GLES20.glLinkProgram(p);
            int[] ok = new int[1];
            GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0);
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            if (ok[0] == 0) {
                String l = GLES20.glGetProgramInfoLog(p);
                GLES20.glDeleteProgram(p);
                throw new IllegalStateException(errorPrefix + l);
            }
            return p;
        }

        int compileShader(int type, String source) {
            int shader = GLES20.glCreateShader(type);
            GLES20.glShaderSource(shader, source);
            GLES20.glCompileShader(shader);
            int[] ok = new int[1];
            GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
            if (ok[0] == 0) {
                String log = GLES20.glGetShaderInfoLog(shader);
                GLES20.glDeleteShader(shader);
                throw new IllegalStateException("shader compile failed: " + log);
            }
            return shader;
        }

        int createTexture(int target) {
            int[] ids = new int[1];
            GLES20.glGenTextures(1, ids, 0);
            GLES20.glBindTexture(target, ids[0]);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(target, 0);
            return ids[0];
        }

        private void clearRegionalMotionFieldData() {
            for (int i = 0; i < MOTION_GRID_MAX_CELLS; i++) {
                regionalMotionDxPixels[i] = 0f;
                regionalMotionDyPixels[i] = 0f;
                regionalMotionConfidence[i] = 0f;
                int offset = i * 4;
                motionFieldPixels[offset] = (byte) 128;
                motionFieldPixels[offset + 1] = (byte) 128;
                motionFieldPixels[offset + 2] = 0;
                motionFieldPixels[offset + 3] = (byte) 255;
            }
            motionConfidence = 0f;
        }

        private void uploadRegionalMotionField() {
            if (motionFieldTexture == 0) return;
            int activeBytes = motionGridColumns * motionGridRows * 4;
            motionFieldUpload.clear();
            motionFieldUpload.put(motionFieldPixels, 0, activeBytes);
            motionFieldUpload.flip();
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionFieldTexture);
            GLES20.glTexSubImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    0,
                    0,
                    motionGridColumns,
                    motionGridRows,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    motionFieldUpload);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            motionFieldUpload.clear();
        }

        private void resetRegionalMotionField() {
            clearRegionalMotionFieldData();
            uploadRegionalMotionField();
        }

        private boolean configureMotionAnalysisGeometry() {
            int sourceWidth = Math.max(1, videoWidth);
            int sourceHeight = Math.max(1, videoHeight);
            double sourceAspect = sourceWidth / (double) sourceHeight;
            double scale =
                    Math.sqrt(MOTION_SAMPLE_PIXEL_BUDGET / (sourceWidth * (double) sourceHeight));
            double targetWidth = sourceWidth * scale;
            double targetHeight = sourceHeight * scale;
            double longest = Math.max(targetWidth, targetHeight);
            if (longest > MOTION_SAMPLE_MAX_DIMENSION) {
                double cap = MOTION_SAMPLE_MAX_DIMENSION / longest;
                targetWidth *= cap;
                targetHeight *= cap;
            }

            int nextWidth = Math.max(MOTION_SAMPLE_MIN_DIMENSION, (int) Math.round(targetWidth));
            int nextHeight = Math.max(MOTION_SAMPLE_MIN_DIMENSION, (int) Math.round(targetHeight));
            // Rounding can push the pixel count slightly above the fixed readback
            // buffer. Reduce the dimension that best preserves the source aspect.
            while ((long) nextWidth * nextHeight > MOTION_SAMPLE_PIXEL_BUDGET) {
                double widthAspect = (nextWidth - 1) / (double) nextHeight;
                double heightAspect = nextWidth / (double) (nextHeight - 1);
                double widthError = Math.abs(widthAspect - sourceAspect);
                double heightError = Math.abs(heightAspect - sourceAspect);
                if (nextWidth > MOTION_SAMPLE_MIN_DIMENSION
                        && (nextHeight <= MOTION_SAMPLE_MIN_DIMENSION
                                || widthError <= heightError)) {
                    nextWidth--;
                } else if (nextHeight > MOTION_SAMPLE_MIN_DIMENSION) {
                    nextHeight--;
                } else {
                    break;
                }
            }

            double sampleAspect = nextWidth / (double) nextHeight;
            int nextColumns =
                    Math.max(
                            3,
                            Math.min(
                                    10,
                                    (int)
                                            Math.round(
                                                    Math.sqrt(
                                                            MOTION_GRID_MAX_CELLS
                                                                    * sampleAspect))));
            int nextRows = Math.max(2, MOTION_GRID_MAX_CELLS / nextColumns);

            boolean changed =
                    sourceWidth != motionGeometrySourceWidth
                            || sourceHeight != motionGeometrySourceHeight
                            || nextWidth != motionSampleWidth
                            || nextHeight != motionSampleHeight
                            || nextColumns != motionGridColumns
                            || nextRows != motionGridRows;
            if (!changed) return false;

            motionGeometrySourceWidth = sourceWidth;
            motionGeometrySourceHeight = sourceHeight;
            motionSampleWidth = nextWidth;
            motionSampleHeight = nextHeight;
            motionGridColumns = nextColumns;
            motionGridRows = nextRows;
            previousMotionLuma = null;
            currentMotionLuma = null;
            interpolationPreviousReady = false;
            interpolationPending = false;
            interpolationShownForCurrent = false;
            interpolationTargetNs = 0L;
            clearRegionalMotionFieldData();
            return true;
        }

        private void ensureMotionSampleBuffers() {
            boolean geometryChanged = configureMotionAnalysisGeometry();

            if (motionSampleFbo == 0) {
                int[] ids = new int[1];
                GLES20.glGenFramebuffers(1, ids, 0);
                motionSampleFbo = ids[0];
            }
            if (motionSampleTexture == 0) {
                motionSampleTexture = createTexture(GLES20.GL_TEXTURE_2D);
            }
            if (motionSampleAllocatedWidth != motionSampleWidth
                    || motionSampleAllocatedHeight != motionSampleHeight) {
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionSampleTexture);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        motionSampleWidth,
                        motionSampleHeight,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        null);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionSampleFbo);
                GLES20.glFramebufferTexture2D(
                        GLES20.GL_FRAMEBUFFER,
                        GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D,
                        motionSampleTexture,
                        0);
                int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("motion sample FBO incomplete");
                }
                motionSampleAllocatedWidth = motionSampleWidth;
                motionSampleAllocatedHeight = motionSampleHeight;
            }

            if (motionFieldTexture == 0) {
                motionFieldTexture = createTexture(GLES20.GL_TEXTURE_2D);
            }
            if (motionFieldTextureWidth != motionGridColumns
                    || motionFieldTextureHeight != motionGridRows) {
                clearRegionalMotionFieldData();
                int activeBytes = motionGridColumns * motionGridRows * 4;
                motionFieldUpload.clear();
                motionFieldUpload.put(motionFieldPixels, 0, activeBytes);
                motionFieldUpload.flip();
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionFieldTexture);
                GLES20.glTexImage2D(
                        GLES20.GL_TEXTURE_2D,
                        0,
                        GLES20.GL_RGBA,
                        motionGridColumns,
                        motionGridRows,
                        0,
                        GLES20.GL_RGBA,
                        GLES20.GL_UNSIGNED_BYTE,
                        motionFieldUpload);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                motionFieldUpload.clear();
                motionFieldTextureWidth = motionGridColumns;
                motionFieldTextureHeight = motionGridRows;
            }

            if (geometryChanged) resetRegionalMotionField();
        }

        private void ensureFrameGenerationBuffers() {
            // FI must never invent spatial detail by allocating above the decoded
            // source size. If decoder dimensions are not known yet, wait for them.
            if (!frameGenerationEnabled
                    || !video
                    || videoWidth <= 1
                    || videoHeight <= 1
                    || width <= 0
                    || height <= 0) return;
            int sourceTargetWidth = videoWidth;
            int sourceTargetHeight = videoHeight;
            int resolutionTier =
                    Math.max(
                            0,
                            Math.min(
                                    videoIntermediateResolutionTier,
                                    VIDEO_FI_RESOLUTION_FACTORS.length - 1));
            float resolutionFactor = Math.min(1f, VIDEO_FI_RESOLUTION_FACTORS[resolutionTier]);
            int targetWidth =
                    Math.max(
                            1,
                            Math.min(
                                    sourceTargetWidth,
                                    Math.round(sourceTargetWidth * resolutionFactor)));
            int targetHeight =
                    Math.max(
                            1,
                            Math.min(
                                    sourceTargetHeight,
                                    Math.round(sourceTargetHeight * resolutionFactor)));
            boolean fullReady =
                    frameBlendWidth == targetWidth
                            && frameBlendHeight == targetHeight
                            && frameBlendPrevTexture != 0
                            && frameBlendCurrentTexture != 0
                            && frameBlendFbo != 0;
            if (!fullReady) {
                if (frameBlendPrevTexture != 0
                        || frameBlendCurrentTexture != 0
                        || frameBlendFbo != 0) {
                    GLES20.glDeleteTextures(
                            2, new int[] {frameBlendPrevTexture, frameBlendCurrentTexture}, 0);
                    if (frameBlendFbo != 0)
                        GLES20.glDeleteFramebuffers(1, new int[] {frameBlendFbo}, 0);
                }
                frameBlendPrevTexture = frameBlendCurrentTexture = frameBlendFbo = 0;
                frameBlendWidth = frameBlendHeight = 0;
                int[] ids = new int[1];
                GLES20.glGenFramebuffers(1, ids, 0);
                frameBlendFbo = ids[0];
                frameBlendPrevTexture = createTexture(GLES20.GL_TEXTURE_2D);
                frameBlendCurrentTexture = createTexture(GLES20.GL_TEXTURE_2D);
                for (int tex : new int[] {frameBlendPrevTexture, frameBlendCurrentTexture}) {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex);
                    GLES20.glTexImage2D(
                            GLES20.GL_TEXTURE_2D,
                            0,
                            GLES20.GL_RGBA,
                            targetWidth,
                            targetHeight,
                            0,
                            GLES20.GL_RGBA,
                            GLES20.GL_UNSIGNED_BYTE,
                            null);
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBlendFbo);
                GLES20.glFramebufferTexture2D(
                        GLES20.GL_FRAMEBUFFER,
                        GLES20.GL_COLOR_ATTACHMENT0,
                        GLES20.GL_TEXTURE_2D,
                        frameBlendCurrentTexture,
                        0);
                int st = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                frameBlendWidth = targetWidth;
                frameBlendHeight = targetHeight;
                if (st != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                    throw new IllegalStateException("frame generation FBO incomplete");
                }
                WallpaperBugRecorder.record(
                        context,
                        "GPU_FI_BUFFER_SIZE",
                        "source="
                                + sourceTargetWidth
                                + "x"
                                + sourceTargetHeight
                                + ",working="
                                + targetWidth
                                + "x"
                                + targetHeight
                                + ",tier="
                                + resolutionTier
                                + ",sourceBound=true");
                interpolationPreviousReady = false;
                interpolationPending = false;
                interpolationShownForCurrent = false;
                frameGenerationActive = false;
            }
            ensureMotionSampleBuffers();
            if (!fullReady) resetRegionalMotionField();
        }

        private boolean smartUpscaleNeedsProcessing(int sourceWidth, int sourceHeight) {
            if (!video
                    || !smartUpscaleEnabled
                    || smartUpscaleStrainBypassed
                    || smartUpscaler == null
                    || !smartUpscaler.isReady()
                    || width <= 0
                    || height <= 0
                    || sourceWidth <= 0
                    || sourceHeight <= 0) {
                return false;
            }

            int effectiveSourceWidth =
                    (mediaOrientation == 90 || mediaOrientation == 270)
                            ? sourceHeight
                            : sourceWidth;
            int effectiveSourceHeight =
                    (mediaOrientation == 90 || mediaOrientation == 270)
                            ? sourceWidth
                            : sourceHeight;

            return effectiveSourceWidth < width || effectiveSourceHeight < height;
        }

        private void refreshSmartUpscaleState() {
            boolean enabled = video && smartUpscaleEnabled;
            int sourceWidth = frameBlendWidth > 0 ? frameBlendWidth : videoWidth;
            int sourceHeight = frameBlendHeight > 0 ? frameBlendHeight : videoHeight;
            boolean working =
                    enabled
                            && !smartUpscaleStrainBypassed
                            && isVideoPrepared
                            && (smartUpscaler != null && smartUpscaler.isReady())
                            && smartUpscaleNeedsProcessing(sourceWidth, sourceHeight);
            String backend =
                    smartUpscaleBackend == null || smartUpscaleBackend.trim().isEmpty()
                            ? "Unavailable"
                            : smartUpscaleBackend;

            if (enabled == smartUpscalePublishedEnabled
                    && working == smartUpscaleWorking
                    && backend.equals(smartUpscalePublishedBackend)) return;
            smartUpscalePublishedEnabled = enabled;
            smartUpscalePublishedBackend = backend;
            smartUpscaleWorking = working;

            WallpaperBugRecorder.recordRuntimeSync(
                    context,
                    "SMART_UPSCALE_STATE",
                    "enabled="
                            + enabled
                            + ",working="
                            + working
                            + ",bypassed="
                            + smartUpscaleStrainBypassed
                            + ",degraded="
                            + smartUpscaleStrainDegraded
                            + ",source="
                            + sourceWidth
                            + "x"
                            + sourceHeight
                            + ",output="
                            + width
                            + "x"
                            + height
                            + ",backend="
                            + backend);

            SmartUpscaleEventListener listener = smartUpscaleEventListener;
            if (listener != null) {
                try {
                    listener.onSmartUpscaleEvent(enabled, working, backend);
                } catch (Throwable ignored) {
                }
            }
        }

        boolean isSmartUpscaleEnabled() {
            return SMART_UPSCALE_RUNTIME_ENABLED && video && smartUpscaleEnabled;
        }

        boolean isSmartUpscaleActive() {
            int sourceWidth = frameBlendWidth > 0 ? frameBlendWidth : videoWidth;
            int sourceHeight = frameBlendHeight > 0 ? frameBlendHeight : videoHeight;
            return SMART_UPSCALE_RUNTIME_ENABLED
                    && video
                    && smartUpscaleEnabled
                    && isVideoPrepared
                    && !smartUpscaleStrainBypassed
                    && smartUpscaler != null
                    && smartUpscaler.isReady()
                    && smartUpscaleNeedsProcessing(sourceWidth, sourceHeight);
        }

        String getSmartUpscaleBackend() {
            return smartUpscaleBackend;
        }

        private void ensureSmartUpscaleInputBuffer(int sourceWidth, int sourceHeight) {
            if (sourceWidth <= 0 || sourceHeight <= 0) {
                throw new IllegalArgumentException("invalid smart upscale input size");
            }
            if (smartUpscaleInputFbo != 0
                    && smartUpscaleInputTexture != 0
                    && smartUpscaleInputWidth == sourceWidth
                    && smartUpscaleInputHeight == sourceHeight) {
                return;
            }

            releaseSmartUpscaleInputBuffer();

            int[] ids = new int[1];
            GLES20.glGenFramebuffers(1, ids, 0);
            smartUpscaleInputFbo = ids[0];
            smartUpscaleInputTexture = createTexture(GLES20.GL_TEXTURE_2D);

            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, smartUpscaleInputTexture);
            GLES20.glTexImage2D(
                    GLES20.GL_TEXTURE_2D,
                    0,
                    GLES20.GL_RGBA,
                    sourceWidth,
                    sourceHeight,
                    0,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    null);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smartUpscaleInputFbo);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    smartUpscaleInputTexture,
                    0);
            int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
                releaseSmartUpscaleInputBuffer();
                throw new IllegalStateException(
                        "smart upscale input FBO incomplete: 0x" + Integer.toHexString(status));
            }

            smartUpscaleInputWidth = sourceWidth;
            smartUpscaleInputHeight = sourceHeight;
        }

        private void releaseSmartUpscaleInputBuffer() {
            if (smartUpscaleInputTexture != 0) {
                GLES20.glDeleteTextures(1, new int[] {smartUpscaleInputTexture}, 0);
            }
            if (smartUpscaleInputFbo != 0) {
                GLES20.glDeleteFramebuffers(1, new int[] {smartUpscaleInputFbo}, 0);
            }
            smartUpscaleInputTexture = 0;
            smartUpscaleInputFbo = 0;
            smartUpscaleInputWidth = 0;
            smartUpscaleInputHeight = 0;
            smartUpscaleWorking = false;
        }

        private void applyFrameBlendMotionUniforms() {
            float margin = 2f * MOTION_SEARCH_RADIUS + MOTION_PATCH_RADIUS;
            float sampleFirstU = margin / (motionSampleWidth - 1f);
            float sampleLastU = (motionSampleWidth - 1f - margin) / (motionSampleWidth - 1f);
            float sampleFirstV = margin / (motionSampleHeight - 1f);
            float sampleLastV = (motionSampleHeight - 1f - margin) / (motionSampleHeight - 1f);
            float fieldFirstU = 0.5f / motionGridColumns;
            float fieldLastU = 1f - fieldFirstU;
            float fieldFirstV = 0.5f / motionGridRows;
            float fieldLastV = 1f - fieldFirstV;
            float scaleU =
                    (fieldLastU - fieldFirstU) / Math.max(0.0001f, sampleLastU - sampleFirstU);
            float scaleV =
                    (fieldLastV - fieldFirstV) / Math.max(0.0001f, sampleLastV - sampleFirstV);
            float offsetU = fieldFirstU - scaleU * sampleFirstU;
            float offsetV = fieldFirstV - scaleV * sampleFirstV;
            GLES20.glUniform2f(
                    frameBlendMotionSampleScale,
                    MOTION_FIELD_MAX_PIXELS / motionSampleWidth,
                    MOTION_FIELD_MAX_PIXELS / motionSampleHeight);
            GLES20.glUniform4f(frameBlendMotionFieldTransform, scaleU, scaleV, offsetU, offsetV);
        }

        private void drawGeneratedFrameToBoundFramebuffer(int drawWidth, int drawHeight) {
            GLES20.glViewport(0, 0, drawWidth, drawHeight);
            GLES20.glUseProgram(programFrameBlend);

            int p = frameBlendPosition;
            int t = frameBlendTexCoord;
            int ps = frameBlendPrev;
            int cs = frameBlendCurrent;
            int strength = frameBlendStrength;
            int motionField = frameBlendMotionField;

            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBlendPrevTexture);
            GLES20.glUniform1i(ps, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBlendCurrentTexture);
            GLES20.glUniform1i(cs, 1);
            GLES20.glUniform1f(strength, frameGenerationMix);
            applyFrameBlendMotionUniforms();
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionFieldTexture);
            GLES20.glUniform1i(motionField, 2);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);

            bindGeometryAttributes(p, t, canonicalTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(p);
            GLES20.glDisableVertexAttribArray(t);

            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        private void drawBufferedFiSourceToBoundFramebuffer(
                int sourceTexture, int drawWidth, int drawHeight) {
            if (sourceTexture == 0)
                throw new IllegalStateException("FI source texture unavailable");
            GLES20.glViewport(0, 0, drawWidth, drawHeight);
            GLES20.glUseProgram(program2d);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, sourceTexture);
            GLES20.glUniform1i(program2dTexture, 0);
            GLES20.glUniformMatrix4fv(program2dTexMatrix, 1, false, identity, 0);
            GLES20.glUniformMatrix4fv(program2dTransform, 1, false, identity, 0);
            bindGeometryAttributes(program2dPosition, program2dTexCoord, canonicalTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(program2dPosition);
            GLES20.glDisableVertexAttribArray(program2dTexCoord);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        }

        private void drawVideoToBoundFramebuffer(int drawWidth, int drawHeight) {
            GLES20.glViewport(0, 0, drawWidth, drawHeight);
            GLES20.glUseProgram(programOes);
            int p = programOesPosition,
                    t = programOesTexCoord,
                    s = programOesTexture,
                    tm = programOesTexMatrix,
                    tr = programOesTransform;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, textureOes);
            GLES20.glUniform1i(s, 0);
            GLES20.glUniformMatrix4fv(tm, 1, false, videoTexMatrix, 0);
            GLES20.glUniformMatrix4fv(tr, 1, false, identity, 0);
            bindGeometryAttributes(p, t, canonicalTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(p);
            GLES20.glDisableVertexAttribArray(t);
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);
        }

        private void captureCurrentVideoFrame() {
            if (!frameGenerationEnabled || !video || frameBlendFbo == 0) return;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBlendFbo);
            drawVideoToBoundFramebuffer(frameBlendWidth, frameBlendHeight);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);

            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, motionSampleFbo);
            drawVideoToBoundFramebuffer(motionSampleWidth, motionSampleHeight);
            motionSampleReadback.clear();
            GLES20.glReadPixels(
                    0,
                    0,
                    motionSampleWidth,
                    motionSampleHeight,
                    GLES20.GL_RGBA,
                    GLES20.GL_UNSIGNED_BYTE,
                    motionSampleReadback);
            motionSampleReadback.position(0);
            int samplePixelCount = motionSampleWidth * motionSampleHeight;
            if (currentMotionLuma == null || currentMotionLuma.length != samplePixelCount) {
                currentMotionLuma = new byte[samplePixelCount];
            }
            for (int i = 0; i < currentMotionLuma.length; i++) {
                int o = i * 4;
                int rr = motionSampleReadback.get(o) & 0xff;
                int gg = motionSampleReadback.get(o + 1) & 0xff;
                int bb = motionSampleReadback.get(o + 2) & 0xff;
                currentMotionLuma[i] = (byte) ((77 * rr + 150 * gg + 29 * bb) >> 8);
            }
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        }

        private void copyCurrentToPrevious() {
            if (!frameGenerationEnabled
                    || frameBlendFbo == 0
                    || frameBlendWidth <= 0
                    || frameBlendHeight <= 0) return;
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBlendFbo);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    frameBlendPrevTexture,
                    0);
            GLES20.glViewport(0, 0, frameBlendWidth, frameBlendHeight);
            GLES20.glUseProgram(program2d);
            int p = program2dPosition,
                    t = program2dTexCoord,
                    s = program2dTexture,
                    tm = program2dTexMatrix,
                    tr = program2dTransform;
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBlendCurrentTexture);
            GLES20.glUniform1i(s, 0);
            GLES20.glUniformMatrix4fv(tm, 1, false, identity, 0);
            GLES20.glUniformMatrix4fv(tr, 1, false, identity, 0);
            bindGeometryAttributes(p, t, canonicalTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(p);
            GLES20.glDisableVertexAttribArray(t);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, frameBlendFbo);
            GLES20.glFramebufferTexture2D(
                    GLES20.GL_FRAMEBUFFER,
                    GLES20.GL_COLOR_ATTACHMENT0,
                    GLES20.GL_TEXTURE_2D,
                    frameBlendCurrentTexture,
                    0);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
            GLES20.glViewport(0, 0, width, height);
        }

        private int patchSad(byte[] previous, byte[] current, int cx, int cy, int dx, int dy) {
            int radius = MOTION_PATCH_RADIUS, sum = 0;
            for (int y = -radius; y <= radius; y++) {
                int pRow = (cy + y) * motionSampleWidth;
                int qRow = (cy + y + dy) * motionSampleWidth;
                for (int x = -radius; x <= radius; x++) {
                    int pv = previous[pRow + cx + x] & 0xff;
                    int cv = current[qRow + cx + x + dx] & 0xff;
                    sum += Math.abs(pv - cv);
                }
            }
            return sum;
        }

        private float patchGradient(byte[] image, int cx, int cy) {
            int sum = 0;
            for (int y = -MOTION_PATCH_RADIUS; y <= MOTION_PATCH_RADIUS; y++) {
                int row = (cy + y) * motionSampleWidth;
                for (int x = -MOTION_PATCH_RADIUS; x <= MOTION_PATCH_RADIUS; x++) {
                    int v = image[row + cx + x] & 0xff;
                    sum += Math.abs(v - (image[row + cx + x + 1] & 0xff));
                    sum += Math.abs(v - (image[(cy + y + 1) * motionSampleWidth + cx + x] & 0xff));
                }
            }
            return sum;
        }

        private int motionGridSampleCoordinate(int index, int count, int sampleSize) {
            int margin = 2 * MOTION_SEARCH_RADIUS + MOTION_PATCH_RADIUS;
            int last = sampleSize - 1 - margin;
            if (count <= 1) return sampleSize / 2;
            return margin + Math.round((last - margin) * (index / (float) (count - 1)));
        }

        private void estimateRegionalMotion() {
            if (previousMotionLuma == null || currentMotionLuma == null) {
                resetRegionalMotionField();
                return;
            }
            long totalDiff = 0;
            for (int i = 0; i < currentMotionLuma.length; i++) {
                totalDiff +=
                        Math.abs((previousMotionLuma[i] & 0xff) - (currentMotionLuma[i] & 0xff));
            }
            float meanDiff = totalDiff / (float) currentMotionLuma.length;
            int acceptedRegions = 0;
            float strongestConfidence = 0f;

            // Estimate each region independently. A single global vector cannot
            // represent foreground and background objects moving in different ways.
            for (int row = 0; row < motionGridRows; row++) {
                int cy = motionGridSampleCoordinate(row, motionGridRows, motionSampleHeight);
                for (int column = 0; column < motionGridColumns; column++) {
                    int cx =
                            motionGridSampleCoordinate(
                                    column, motionGridColumns, motionSampleWidth);
                    int index = row * motionGridColumns + column;
                    int offset = index * 4;
                    float previousConfidence = regionalMotionConfidence[index];

                    int baseline = patchSad(previousMotionLuma, currentMotionLuma, cx, cy, 0, 0);
                    if (baseline < MOTION_STATIONARY_SAD_THRESHOLD) {
                        // A near-identical patch is a valid zero-motion observation,
                        // not a failed match. This covers still scenes and steady
                        // backgrounds without reviving the old global-difference shortcut.
                        float stableConfidence =
                                1f - baseline / (float) (MOTION_STATIONARY_SAD_THRESHOLD * 4);
                        regionalMotionDxPixels[index] = 0f;
                        regionalMotionDyPixels[index] = 0f;
                        regionalMotionConfidence[index] = stableConfidence;
                        motionFieldPixels[offset] = (byte) 128;
                        motionFieldPixels[offset + 1] = (byte) 128;
                        motionFieldPixels[offset + 2] =
                                (byte)
                                        Math.max(
                                                0,
                                                Math.min(255, Math.round(stableConfidence * 255f)));
                        motionFieldPixels[offset + 3] = (byte) 255;
                        strongestConfidence = Math.max(strongestConfidence, stableConfidence);
                        acceptedRegions++;
                        continue;
                    }

                    int best = baseline, second = baseline, bestDx = 0, bestDy = 0;
                    if (baseline >= MOTION_STATIONARY_SAD_THRESHOLD) {
                        for (int dy = -MOTION_SEARCH_RADIUS; dy <= MOTION_SEARCH_RADIUS; dy++) {
                            for (int dx = -MOTION_SEARCH_RADIUS; dx <= MOTION_SEARCH_RADIUS; dx++) {
                                int sad =
                                        patchSad(
                                                previousMotionLuma,
                                                currentMotionLuma,
                                                cx,
                                                cy,
                                                dx,
                                                dy);
                                if (sad < best) {
                                    second = best;
                                    best = sad;
                                    bestDx = dx;
                                    bestDy = dy;
                                } else if (sad < second) {
                                    second = sad;
                                }
                            }
                        }
                    }

                    float improvement = baseline > 0 ? (baseline - best) / (float) baseline : 0f;
                    float ambiguity = (second - best) / (float) Math.max(1, second);
                    float texture = Math.min(1f, patchGradient(previousMotionLuma, cx, cy) / 900f);
                    float confidence =
                            Math.min(1f, improvement * 5f) * Math.min(1f, ambiguity * 6f) * texture;
                    boolean reliable =
                            baseline >= 24
                                    && (bestDx != 0 || bestDy != 0)
                                    && improvement >= 0.04f
                                    && texture >= 0.12f
                                    && confidence >= 0.18f;

                    // Reject occlusions and accidental local matches using a reverse
                    // search: current -> previous should recover the opposite vector.
                    if (reliable) {
                        int reverseCx = cx + bestDx;
                        int reverseCy = cy + bestDy;
                        int reverseBaseline =
                                patchSad(
                                        currentMotionLuma,
                                        previousMotionLuma,
                                        reverseCx,
                                        reverseCy,
                                        0,
                                        0);
                        int reverseBest = reverseBaseline;
                        int reverseDx = 0, reverseDy = 0;
                        for (int dy = -MOTION_SEARCH_RADIUS; dy <= MOTION_SEARCH_RADIUS; dy++) {
                            for (int dx = -MOTION_SEARCH_RADIUS; dx <= MOTION_SEARCH_RADIUS; dx++) {
                                int sad =
                                        patchSad(
                                                currentMotionLuma,
                                                previousMotionLuma,
                                                reverseCx,
                                                reverseCy,
                                                dx,
                                                dy);
                                if (sad < reverseBest) {
                                    reverseBest = sad;
                                    reverseDx = dx;
                                    reverseDy = dy;
                                }
                            }
                        }
                        float reverseImprovement =
                                reverseBaseline > 0
                                        ? (reverseBaseline - reverseBest) / (float) reverseBaseline
                                        : 0f;
                        reliable =
                                reverseImprovement >= 0.025f
                                        && Math.abs(bestDx + reverseDx) <= 1
                                        && Math.abs(bestDy + reverseDy) <= 1;
                    }

                    if (reliable) {
                        float dx = bestDx;
                        float dy = bestDy;
                        if (previousConfidence >= 0.20f) {
                            float priorDx = regionalMotionDxPixels[index];
                            float priorDy = regionalMotionDyPixels[index];
                            float delta = (float) Math.hypot(dx - priorDx, dy - priorDy);
                            // Smooth small estimator noise but follow genuine direction
                            // changes immediately instead of dragging stale motion forward.
                            if (delta <= 2.5f) {
                                dx = priorDx * 0.30f + dx * 0.70f;
                                dy = priorDy * 0.30f + dy * 0.70f;
                                confidence = confidence * 0.75f + previousConfidence * 0.25f;
                            }
                        }
                        regionalMotionDxPixels[index] = dx;
                        regionalMotionDyPixels[index] = dy;
                        regionalMotionConfidence[index] = confidence;
                        motionFieldPixels[offset] =
                                (byte)
                                        Math.max(
                                                0,
                                                Math.min(
                                                        255,
                                                        Math.round(
                                                                (dx / MOTION_FIELD_MAX_PIXELS * 0.5f
                                                                                + 0.5f)
                                                                        * 255f)));
                        motionFieldPixels[offset + 1] =
                                (byte)
                                        Math.max(
                                                0,
                                                Math.min(
                                                        255,
                                                        Math.round(
                                                                (dy / MOTION_FIELD_MAX_PIXELS * 0.5f
                                                                                + 0.5f)
                                                                        * 255f)));
                        motionFieldPixels[offset + 2] =
                                (byte) Math.max(0, Math.min(255, Math.round(confidence * 255f)));
                        motionFieldPixels[offset + 3] = (byte) 255;
                        strongestConfidence = Math.max(strongestConfidence, confidence);
                        acceptedRegions++;
                    } else {
                        // Invalid regions must use the real current frame, not a stale
                        // motion vector left over from the previous frame pair.
                        regionalMotionDxPixels[index] = 0f;
                        regionalMotionDyPixels[index] = 0f;
                        regionalMotionConfidence[index] = 0f;
                        motionFieldPixels[offset] = (byte) 128;
                        motionFieldPixels[offset + 1] = (byte) 128;
                        motionFieldPixels[offset + 2] = 0;
                        motionFieldPixels[offset + 3] = (byte) 255;
                    }
                }
            }

            // A large luminance discontinuity with almost no trustworthy local
            // correspondences is treated as a scene cut; never blend across it.
            if (meanDiff > 52f && acceptedRegions < 4) {
                resetRegionalMotionField();
                return;
            }
            motionConfidence = strongestConfidence;
            uploadRegionalMotionField();

            // Diagnostics are queued off the render thread; never perform file I/O
            // synchronously while presenting an interpolated frame.
            if ((sourceFpsSamples % 60) == 0) {
                WallpaperBugRecorder.record(
                        context,
                        "GPU_FI_MOTION_ESTIMATE",
                        "meanDiff="
                                + (Math.round(meanDiff * 100f) / 100f)
                                + ",validRegions="
                                + acceptedRegions
                                + ",confidence="
                                + (Math.round(motionConfidence * 100f) / 100f)
                                + ",grid="
                                + motionGridColumns
                                + "x"
                                + motionGridRows
                                + ",sample="
                                + motionSampleWidth
                                + "x"
                                + motionSampleHeight);
            }
        }

        private void promoteCurrentMotionSample() {
            if (currentMotionLuma == null) return;
            if (previousMotionLuma == null
                    || previousMotionLuma.length != currentMotionLuma.length) {
                previousMotionLuma = new byte[currentMotionLuma.length];
            }
            byte[] tmp = previousMotionLuma;
            previousMotionLuma = currentMotionLuma;
            currentMotionLuma = tmp;
        }

        private void releaseFrameGenerationBuffers() {
            frameGenerationActive = false;
            lastFrameGenerationNs = 0L;
            if (frameBlendPrevTexture != 0 || frameBlendCurrentTexture != 0) {
                GLES20.glDeleteTextures(
                        2, new int[] {frameBlendPrevTexture, frameBlendCurrentTexture}, 0);
            }
            if (frameBlendFbo != 0) {
                GLES20.glDeleteFramebuffers(1, new int[] {frameBlendFbo}, 0);
            }
            frameBlendPrevTexture = frameBlendCurrentTexture = frameBlendFbo = 0;
            frameBlendWidth = frameBlendHeight = 0;
            if (motionSampleTexture != 0) {
                GLES20.glDeleteTextures(1, new int[] {motionSampleTexture}, 0);
            }
            if (motionSampleFbo != 0) {
                GLES20.glDeleteFramebuffers(1, new int[] {motionSampleFbo}, 0);
            }
            motionSampleTexture = motionSampleFbo = 0;
            motionSampleAllocatedWidth = motionSampleAllocatedHeight = -1;
            if (motionFieldTexture != 0) {
                GLES20.glDeleteTextures(1, new int[] {motionFieldTexture}, 0);
            }
            motionFieldTexture = 0;
            motionFieldTextureWidth = motionFieldTextureHeight = -1;
            motionGeometrySourceWidth = motionGeometrySourceHeight = -1;
            previousMotionLuma = null;
            currentMotionLuma = null;
            resetRegionalMotionField();
            interpolationPending = false;
            interpolationPreviousReady = false;
            interpolationTargetNs = 0L;
        }

        void createWindowSurface(Surface surface) {
            refreshSystemRefreshRate();
            WallpaperBugRecorder.record(
                    context,
                    "GPU_SURFACE_CREATE_START",
                    "eglReady=" + eglReady + ",valid=" + (surface != null && surface.isValid()));
            if (!eglReady) {
                WallpaperBugRecorder.record(
                        context, "GPU_SURFACE_CREATE_SKIPPED_EGL_NOT_READY", "");
                return;
            }
            if (surface == null || !surface.isValid()) {
                WallpaperBugRecorder.record(context, "SURFACE_CREATE_SKIPPED_INVALID", "");
                return;
            }
            destroyWindowSurfaceInternal();
            surfaceReady = false;

            int[] attrs = {EGL14.EGL_NONE};
            eglSurface = EGL14.eglCreateWindowSurface(display, eglConfig, surface, attrs, 0);
            if (eglSurface == EGL14.EGL_NO_SURFACE) {
                WallpaperBugRecorder.record(context, "GPU_EGL_WINDOW_SURFACE_FAILED", "");
                return;
            }
            if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, eglContext)) {
                WallpaperBugRecorder.record(context, "GPU_EGL_MAKE_CURRENT_FAILED", "");
                destroyWindowSurfaceInternal();
                return;
            }
            surfaceReady = true;
            WallpaperBugRecorder.record(
                    context, "GPU_SURFACE_ATTACHED", "width=" + width + ",height=" + height);

            String requestedEffect = GpuWallpaperRenderer.this.standaloneEffectPreference;
            if (!requestedEffect.isEmpty()) {
                setStandaloneEffect(
                        requestedEffect,
                        GpuWallpaperRenderer.this.standaloneEffectSpeedPreference,
                        GpuWallpaperRenderer.this.standaloneEffectIntensityPreference,
                        GpuWallpaperRenderer.this.standaloneEffectGyroEnabledPreference,
                        GpuWallpaperRenderer.this.standaloneEffectInwardPreference,
                        GpuWallpaperRenderer.this.standaloneEffectAnimationEnabledPreference);
            } else if (standaloneEffectReleasePending
                    || activeStandaloneEffect != null
                    || pendingStandaloneEffect != null) {
                clearStandaloneEffectResources();
            }

            if (!isStandaloneEffectRendering()) {
                renderTestFrame();
            }
            if (video && isVideoPrepared) {
                checkAndStartPlayback();
            }
        }

        void resize(int w, int h) {
            int nextWidth = Math.max(1, w);
            int nextHeight = Math.max(1, h);
            boolean dimensionsChanged = width != nextWidth || height != nextHeight;
            width = nextWidth;
            height = nextHeight;
            if (dimensionsChanged && isStandaloneEffectRendering()) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                if (!isRequestedEffectAnimationEnabled()) {
                    standaloneEffectFrameUpdatePending = true;
                }
            }
            refreshSystemRefreshRate();
            recalculateEffectiveTarget();
            refreshSmartUpscaleState();
            WallpaperBugRecorder.record(context, "RESIZE", "width=" + w + ",height=" + h);
            if (dimensionsChanged
                    && !video
                    && currentUri != null
                    && !isStandaloneEffectRendering()) {
                // The working texture contains only the previous surface's source crop.
                // Rebuild it when the launcher changes the wallpaper surface dimensions.
                String media = currentUri.toString();
                loadMedia(media);
            }
        }

        void checkAndStartPlayback() {
            if (video
                    && player != null
                    && isVideoPrepared
                    && surfaceReady
                    && visible
                    && !playbackPaused) {
                try {
                    if (!player.isPlaying()) {
                        player.start();
                        WallpaperBugRecorder.record(context, "VIDEO_STARTED", "");
                    }
                } catch (Exception e) {
                    String reason =
                            "video_start_failed:"
                                    + e.getClass().getName()
                                    + ":"
                                    + String.valueOf(e.getMessage());
                    WallpaperBugRecorder.record(context, "GPU_VIDEO_START_FAILED", reason);
                    recoverMediaPlayer("start_exception");
                }
            }
        }

        private void recoverMediaPlayer(String triggerReason) {
            if (!video || currentUri == null) return;
            WallpaperBugRecorder.record(
                    context, "GPU_VIDEO_RECOVER_BEGIN", "reason=" + triggerReason);
            isVideoPrepared = false;
            if (player != null) {
                try {
                    player.setOnVideoSizeChangedListener(null);
                } catch (Exception ignored) {
                }
                try {
                    player.setOnPreparedListener(null);
                } catch (Exception ignored) {
                }
                try {
                    player.setOnErrorListener(null);
                } catch (Exception ignored) {
                }
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
                try {
                    player.reset();
                } catch (Exception ignored) {
                }
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            if (videoTexture == null || mediaSurface == null || !mediaSurface.isValid()) {
                createVideoSurfaceTexture();
            }
            try {
                final MediaPlayer newPlayer = new MediaPlayer();
                player = newPlayer;
                setPlayerDataSource(newPlayer, currentUri.toString());
                newPlayer.setLooping(true);
                newPlayer.setSurface(mediaSurface);
                newPlayer.setOnVideoSizeChangedListener(
                        (mp, width, height) -> {
                            if (width > 0 && height > 0) {
                                post(
                                        RenderThread.this,
                                        () -> {
                                            if (player != mp) return;
                                            videoWidth = width;
                                            videoHeight = height;
                                            WallpaperBugRecorder.record(
                                                    context,
                                                    "VIDEO_SIZE_CHANGED",
                                                    "width=" + width + ",height=" + height);
                                        });
                            }
                        });
                newPlayer.setOnPreparedListener(
                        mp -> {
                            post(RenderThread.this, () -> onVideoPrepared(mp));
                        });
                newPlayer.setOnErrorListener(
                        (mp, what, extra) -> {
                            String reason = "video_error:what=" + what + ",extra=" + extra;
                            WallpaperBugRecorder.record(context, "GPU_VIDEO_ERROR", reason);
                            if (what == -38) {
                                post(
                                        RenderThread.this,
                                        () -> {
                                            if (player == mp) {
                                                recoverMediaPlayer("error_-38");
                                            }
                                        });
                                return true;
                            }
                            notifyRendererFailure(reason);
                            return true;
                        });
                newPlayer.prepareAsync();
                WallpaperBugRecorder.record(context, "VIDEO_PREPARE_ASYNC", "recovery=true");
            } catch (Exception e) {
                String reason =
                        "video_recovery_failed:"
                                + e.getClass().getName()
                                + ":"
                                + String.valueOf(e.getMessage());
                WallpaperBugRecorder.record(context, "GPU_VIDEO_RECOVERY_FAILED", reason);
                notifyRendererFailure(reason);
            }
        }

        void onVideoPrepared(MediaPlayer mp) {
            if (!video || player != mp) return;
            isVideoPrepared = true;
            videoPlaybackExpectedSinceNs = 0L;
            lastVideoFrameArrivalNs = 0L;
            videoStallFailureReported = false;
            int preparedWidth = mp.getVideoWidth();
            int preparedHeight = mp.getVideoHeight();
            if (preparedWidth > 0 && preparedHeight > 0) {
                videoWidth = preparedWidth;
                videoHeight = preparedHeight;
                if (videoTexture != null) {
                    try {
                        videoTexture.setDefaultBufferSize(videoWidth, videoHeight);
                    } catch (Exception ignored) {
                    }
                }
            }
            if (frameGenerationEnabled && surfaceReady) {
                ensureFrameGenerationBuffers();
            }
            refreshSmartUpscaleState();
            WallpaperBugRecorder.record(
                    context,
                    "VIDEO_PREPARED",
                    "width="
                            + videoWidth
                            + ",height="
                            + videoHeight
                            + ",manualOrientation="
                            + mediaOrientation
                            + ",smartUpscale="
                            + smartUpscaleEnabled
                            + ",smartUpscaleWorking="
                            + smartUpscaleWorking
                            + ",backend="
                            + smartUpscaleBackend);

            // Setup readiness is deliberately independent of playback. LiveStagix
            // may be open and Auto Pause may correctly keep the player stopped.
            VideoPreparedListener preparedListener = videoPreparedListener;
            if (videoWidth > 0
                    && videoHeight > 0
                    && videoTexture != null
                    && mediaSurface != null
                    && mediaSurface.isValid()
                    && preparedListener != null) {
                try {
                    preparedListener.onVideoPrepared(videoWidth, videoHeight);
                } catch (Throwable listenerError) {
                    WallpaperBugRecorder.record(
                            context,
                            "VIDEO_SETUP_READY_CALLBACK_FAILED",
                            listenerError.getClass().getName()
                                    + ":"
                                    + String.valueOf(listenerError.getMessage()));
                }
            } else {
                WallpaperBugRecorder.record(
                        context,
                        "VIDEO_SETUP_NOT_READY",
                        "width="
                                + videoWidth
                                + ",height="
                                + videoHeight
                                + ",texture="
                                + (videoTexture != null)
                                + ",surfaceValid="
                                + (mediaSurface != null && mediaSurface.isValid()));
            }
            checkAndStartPlayback();
        }

        void setVisible(boolean value) {
            visible = value;
            if (isStandaloneEffectRendering()) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                if (value && !isRequestedEffectAnimationEnabled()) {
                    standaloneEffectFrameUpdatePending = true;
                }
            }
            if (!value) {
                videoPlaybackExpectedSinceNs = 0L;
                lastVideoFrameArrivalNs = 0L;
                if (!videoPlaybackFrameConfirmed) {
                    videoFreshFrameCount = 0;
                    videoFirstFrameArrivalNs = 0L;
                }
            }
            if (value && !video && currentUri != null) photoFramePending = true;
            if (!value && wallpaperFpsTelemetryEnabled) {
                WallpaperFpsListener listener = wallpaperFpsListener;
                if (listener != null) {
                    try {
                        listener.onWallpaperFpsChanged(0);
                    } catch (Throwable ignored) {
                    }
                }
                wallpaperFpsWindowFrames = 0L;
                wallpaperFpsWindowStartNs = 0L;
            }
            if (value && autoLoadPaused) {
                healthProbeDueNs = System.nanoTime();
            }
            WallpaperBugRecorder.record(context, "VISIBLE", "value=" + value);
            if (video && player != null && isVideoPrepared) {
                try {
                    if (value && !playbackPaused) {
                        checkAndStartPlayback();
                    } else if (!value) {
                        if (player.isPlaying()) player.pause();
                    }
                } catch (Exception e) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_VIDEO_VISIBILITY_FAILED",
                            e.getClass().getName() + ":" + String.valueOf(e.getMessage()));
                }
            }
        }

        void setWallpaperFpsTelemetryEnabled(boolean enabled) {
            wallpaperFpsTelemetryEnabled = enabled;
            wallpaperFpsWindowFrames = 0L;
            wallpaperFpsWindowStartNs = enabled ? System.nanoTime() : 0L;
        }

        private void recordWallpaperFps(boolean healthProbe, long nowNs) {
            if (!wallpaperFpsTelemetryEnabled) return;

            if (wallpaperFpsWindowStartNs <= 0L) {
                wallpaperFpsWindowStartNs = nowNs;
            }
            if (!healthProbe) {
                wallpaperFpsWindowFrames++;
            }

            long elapsedNs = nowNs - wallpaperFpsWindowStartNs;
            if (elapsedNs < 1_000_000_000L) return;

            int measuredRenderFps =
                    (int)
                            Math.max(
                                    0L,
                                    Math.round(
                                            (wallpaperFpsWindowFrames * 1_000_000_000d)
                                                    / elapsedNs));

            // Photo telemetry is the native render cadence. For video, however,
            // the renderer can redraw the same SurfaceTexture frame many times
            // between decoded video frames, especially on a 90 Hz display.
            // Report the video's actual source cadence unless FI is active; when
            // FI is active, report the intended interpolated presentation cadence.
            int fps = measuredRenderFps;
            if (video) {
                int fiFps = calculateFiTargetFps();
                if (fiFps > 0) {
                    fps = calculateFiPresentationFps();
                } else if (Float.isFinite(videoSourceFps)
                        && videoSourceFps >= 1f
                        && sourceFpsSamples >= 3) {
                    fps = Math.max(1, Math.round(videoSourceFps));
                }
            }

            WallpaperFpsListener listener = wallpaperFpsListener;
            if (listener != null) {
                try {
                    listener.onWallpaperFpsChanged(fps);
                } catch (Throwable t) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_WALLPAPER_FPS_CALLBACK_FAILED",
                            t.getClass().getName() + ": " + String.valueOf(t.getMessage()));
                }
            }
            wallpaperFpsWindowFrames = 0L;
            wallpaperFpsWindowStartNs = nowNs;
        }

        void setPlaybackPaused(boolean paused) {
            playbackPaused = paused;
            if (isStandaloneEffectRendering()) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                if (!paused && !isRequestedEffectAnimationEnabled()) {
                    standaloneEffectFrameUpdatePending = true;
                }
            }
            if (paused || !videoPlaybackFrameConfirmed) {
                videoPlaybackExpectedSinceNs = 0L;
                lastVideoFrameArrivalNs = 0L;
                if (!videoPlaybackFrameConfirmed) {
                    videoFreshFrameCount = 0;
                    videoFirstFrameArrivalNs = 0L;
                }
            }
            if (paused && !video && currentUri != null) pausedImageFramePending = true;
            if (!paused) pausedImageFramePending = false;

            if (paused && !gyroEnabled && !hardPaused) {
                // Auto Pause disables gyro input, but the current transform must
                // glide back to the stable position before the rendered frame freezes.
                motionFrozen = false;
                pauseStabilizationActive = true;
                lastMotionNanos = 0L;
            } else if (!paused) {
                motionFrozen = false;
                pauseStabilizationActive = false;
            }
            if (video && player != null && isVideoPrepared) {
                try {
                    if (paused) {
                        if (player.isPlaying()) player.pause();
                    } else if (visible) {
                        checkAndStartPlayback();
                    }
                } catch (Exception e) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_VIDEO_PAUSE_STATE_FAILED",
                            e.getClass().getName() + ":" + String.valueOf(e.getMessage()));
                }
            }
        }

        void setSmartUpscaleBackend(String backend) {
            String preference =
                    ("sa".equalsIgnoreCase(backend)
                                    || "rcas".equalsIgnoreCase(backend)
                                    || "ge".equalsIgnoreCase(backend)
                                    || "ls".equalsIgnoreCase(backend))
                            ? backend.toLowerCase(java.util.Locale.US)
                            : "auto";
            boolean preferenceChanged = !preference.equals(smartUpscaleBackendPreference);
            smartUpscaleBackendPreference = preference;
            smartUpscaleStrainDegraded = false;
            applySmartUpscaleBackend(preferenceChanged);
        }

        private boolean isLightweightBackend() {
            String b = smartUpscaleBackend;
            if (b == null) return false;
            String lower = b.toLowerCase(java.util.Locale.US);
            return lower.contains("linear") || lower.contains("ls") || lower.contains("generic");
        }

        private void applySmartUpscaleBackend(boolean force) {
            if (smartUpscaler != null) {
                String targetEngine =
                        smartUpscaleStrainDegraded ? "ls" : smartUpscaleBackendPreference;
                if (!force
                        && smartUpscaler.isReady()
                        && targetEngine.equalsIgnoreCase(smartUpscaleActivePreference)) {
                    return;
                }
                smartUpscaleActivePreference = targetEngine;
                releaseSmartUpscaleInputBuffer();
                smartUpscaler.initialize(targetEngine, isModernShaderPath());
                int effectiveQuality =
                        smartUpscaleStrainDegraded
                                ? Math.min(50, smartUpscaleQuality)
                                : smartUpscaleQuality;
                smartUpscaler.setQuality(effectiveQuality);
                smartUpscaleBackend = smartUpscaler.getBackendName();
                String smartUpscaleFallbackReason = smartUpscaler.getFallbackReason();
                if (smartUpscaleFallbackReason != null && !smartUpscaleFallbackReason.isEmpty()) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_SMART_UPSCALE_FALLBACK",
                            "preference="
                                    + smartUpscaleBackendPreference
                                    + ",degraded="
                                    + smartUpscaleStrainDegraded
                                    + ",backend="
                                    + smartUpscaleBackend
                                    + ",reason="
                                    + smartUpscaleFallbackReason);
                }
            }
            smartUpscaleWorking = false;
            refreshSmartUpscaleState();
        }

        void setSmartUpscaleQuality(int quality) {
            int normalized = Math.max(0, Math.min(100, quality));
            if (normalized == smartUpscaleQuality) {
                return;
            }
            smartUpscaleQuality = normalized;
            if (smartUpscaler != null) {
                int effectiveQuality =
                        smartUpscaleStrainDegraded
                                ? Math.min(50, smartUpscaleQuality)
                                : smartUpscaleQuality;
                smartUpscaler.setQuality(effectiveQuality);
            }
            smartUpscaleWorking = false;
            refreshSmartUpscaleState();
        }

        void setSmartUpscaleEnabled(boolean enabled) {
            boolean userEnabled = SMART_UPSCALE_RUNTIME_ENABLED && enabled;
            boolean effectiveEnabled = SMART_UPSCALE_RUNTIME_ENABLED && video && enabled;
            if (smartUpscaleUserEnabled == userEnabled && smartUpscaleEnabled == effectiveEnabled) {
                return;
            }
            smartUpscaleUserEnabled = userEnabled;
            smartUpscaleEnabled = effectiveEnabled;
            smartUpscaleStrainBypassed = false;
            smartUpscaleStrainDegraded = false;
            smartUpscaleWorking = false;
            releaseSmartUpscaleInputBuffer();
            refreshSmartUpscaleState();
        }

        void setFrameGeneration(boolean enabled, int strength) {
            frameGenerationMix = Math.max(0f, Math.min(1f, strength / 100f));
            frameGenerationEnabled = enabled;
            frameGenerationActive = false;
            lastFrameGenerationNs = 0L;
            interpolationPending = false;
            interpolationPreviousReady = false;
            resetRegionalMotionField();
            frameGenerationStartNs = 0L;
            frameGenerationIntervalNs = 16_666_667L;
            interpolationTargetNs = 0L;
            fiTargetFps = 0;
            if (!frameGenerationEnabled) releaseFrameGenerationBuffers();
            refreshSmartUpscaleState();
        }

        private int calculateFiTargetFps() {
            refreshSystemRefreshRate();
            int outputCeiling = Math.max(30, Math.min(systemRefreshRateFps, effectiveTargetFps));
            float maxSourceFps = outputCeiling / 2f;
            if (!video
                    || !frameGenerationEnabled
                    || videoWidth <= 1
                    || videoHeight <= 1
                    || !Float.isFinite(videoSourceFps)
                    || videoSourceFps < 1f
                    || videoSourceFps > maxSourceFps + 0.75f
                    || sourceFpsSamples < 3) {
                return 0;
            }
            int target = Math.round(videoSourceFps * 2f);
            return Math.max(30, Math.min(target, outputCeiling));
        }

        private int calculateFiPresentationFps() {
            int fi = calculateFiTargetFps();
            if (fi <= 0) return effectiveTargetFps;
            refreshSystemRefreshRate();
            return Math.max(30, Math.min(fi, Math.min(systemRefreshRateFps, effectiveTargetFps)));
        }

        boolean isFrameGenerationActive() {
            return frameGenerationActive;
        }

        void setTargetFps(int fps) {
            int safe = Math.max(30, Math.min(240, fps));
            refreshSystemRefreshRate();

            if (requestedTargetFps == safe) {
                recalculateEffectiveTarget();
                return;
            }

            requestedTargetFps = safe;
            if (!hardPaused) {
                autoThrottleTargetFps = calculateNormalTargetFps();
                timingSampleCount = 0;
                slowSinceNs = 0L;
                stableSinceNs = 0L;
                lastThrottleChangeNs = System.nanoTime();
                nextFrameDeadlineNs = 0L;
                recalculateEffectiveTarget();
            }
        }

        void setAmbientBackdropBlur(boolean enabled) {
            ambientBackdropBlur = enabled;
        }

        void setAutoPauseEnabled(boolean enabled) {
            if (autoPauseEnabled == enabled) {
                if (enabled) {
                    refreshSystemRefreshRate();
                    recalculateEffectiveTarget();
                }
                return;
            }

            autoPauseEnabled = enabled;
            if (!enabled) {
                hardPaused = false;
                autoLoadPaused = false;
                motionFrozen = false;
                pauseStabilizationActive = false;
                recoveryGoodProbes = 0;
                slowSinceNs = 0L;
                stableSinceNs = 0L;
                autoThrottleTargetFps = calculateNormalTargetFps();
                effectiveTargetFps = autoThrottleTargetFps;
                timingSampleCount = 0;
                nextFrameDeadlineNs = 0L;
                if (isStandaloneEffectRendering()) {
                    playbackPaused = false;
                    standaloneEffectLastPhaseUpdateNs = 0L;
                    standaloneEffectNextFrameNs = 0L;
                    if (!standaloneEffectAnimationEnabled) {
                        standaloneEffectFrameUpdatePending = true;
                    }
                }
                notifyProtectionState();
            } else {
                refreshSystemRefreshRate();
                autoThrottleTargetFps = calculateNormalTargetFps();
                effectiveTargetFps = autoThrottleTargetFps;
                timingSampleCount = 0;
                slowSinceNs = 0L;
                stableSinceNs = 0L;
                nextFrameDeadlineNs = 0L;
                notifyProtectionState();
            }
        }

        void setAutoLoadPaused(boolean paused) {
            autoLoadPaused = paused;
            motionFrozen = paused;
            if (isStandaloneEffectRendering()) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                if (!paused && !hardPaused) {
                    playbackPaused = false;
                    if (!isRequestedEffectAnimationEnabled()) {
                        standaloneEffectFrameUpdatePending = true;
                    }
                }
            }
            if (paused) {
                healthProbeDueNs = System.nanoTime();
                recoveryGoodProbes = 0;
                playbackPaused = true;
            } else {
                healthProbeDueNs = 0L;
                recoveryGoodProbes = 0;
            }
            WallpaperBugRecorder.record(
                    context,
                    "GPU_AUTO_LOAD_PAUSE_STATE",
                    "paused="
                            + paused
                            + ",effectiveFps="
                            + effectiveTargetFps
                            + ",systemHz="
                            + systemRefreshRateFps);
        }

        private void refreshSystemRefreshRate() {
            try {
                DisplayManager dm =
                        (DisplayManager) context.getSystemService(Context.DISPLAY_SERVICE);
                Display display = dm == null ? null : dm.getDisplay(Display.DEFAULT_DISPLAY);
                float rate = display == null ? 60f : display.getRefreshRate();
                if (!Float.isFinite(rate) || rate <= 0f) rate = 60f;
                systemRefreshRateFps = Math.max(30, Math.round(rate));
            } catch (Throwable ignored) {
                systemRefreshRateFps = 60;
            }
        }

        private int calculateNormalTargetFps() {
            int preferred = requestedTargetFps;
            return Math.max(30, Math.min(preferred, systemRefreshRateFps));
        }

        private void recalculateEffectiveTarget() {
            int normal = calculateNormalTargetFps();
            if (autoThrottleTargetFps <= 0) autoThrottleTargetFps = normal;
            autoThrottleTargetFps = Math.min(autoThrottleTargetFps, normal);
            int next = hardPaused ? 30 : Math.max(30, Math.min(normal, autoThrottleTargetFps));
            if (effectiveTargetFps != next) {
                effectiveTargetFps = next;
                nextFrameDeadlineNs = 0L;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_FPS_TARGET_CHANGED",
                        "effectiveFps="
                                + effectiveTargetFps
                                + ",normalFps="
                                + normal
                                + ",requestedFps="
                                + requestedTargetFps
                                + ",systemHz="
                                + systemRefreshRateFps
                                + ",hardPaused="
                                + hardPaused);
                notifyProtectionState();
            }
        }

        private int stepDownFps(int current) {
            final int[] ladder = {90, 60, 45, 30};
            for (int value : ladder) {
                if (value < current) return value;
            }
            return 30;
        }

        private int stepUpFps(int current, int normal) {
            final int[] ladder = {30, 45, 60, 90};
            for (int value : ladder) {
                if (value > current) return Math.min(normal, value);
            }
            return normal;
        }

        private void enterHardPause(long nowNs) {
            if (hardPaused) return;
            hardPaused = true;
            autoThrottleTargetFps = 30;
            effectiveTargetFps = 30;
            autoLoadPaused = true;
            gyroEnabled = false;
            motionFrozen = false;
            pauseStabilizationActive = true;
            lastMotionNanos = 0L;
            playbackPaused = true;
            if (isStandaloneEffectRendering()) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
            }
            healthProbeDueNs = nowNs;
            recoveryGoodProbes = 0;
            slowSinceNs = 0L;
            stableSinceNs = 0L;
            lastThrottleChangeNs = nowNs;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_RENDER_AUTO_PAUSE",
                    "reason=renderer_strain,effectiveFps=30,systemHz=" + systemRefreshRateFps);
            notifyProtectionState();
        }

        private void exitHardPause(long nowNs) {
            if (!hardPaused) return;
            boolean restorePreferredSmartUpscaleBackend =
                    smartUpscaleStrainBypassed || smartUpscaleStrainDegraded;
            hardPaused = false;
            autoLoadPaused = false;
            smartUpscaleStrainBypassed = false;
            smartUpscaleStrainDegraded = false;
            motionFrozen = false;
            pauseStabilizationActive = false;
            recoveryGoodProbes = 0;
            slowSinceNs = 0L;
            stableSinceNs = 0L;
            autoThrottleTargetFps = calculateNormalTargetFps();
            effectiveTargetFps = autoThrottleTargetFps;
            nextFrameDeadlineNs = 0L;
            if (isStandaloneEffectRendering()) {
                playbackPaused = false;
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                if (!standaloneEffectAnimationEnabled) {
                    standaloneEffectFrameUpdatePending = true;
                }
            }
            WallpaperBugRecorder.record(
                    context,
                    "GPU_RENDER_AUTO_RESUME",
                    "reason=renderer_recovered,effectiveFps="
                            + effectiveTargetFps
                            + ",systemHz="
                            + systemRefreshRateFps);
            if (restorePreferredSmartUpscaleBackend) {
                applySmartUpscaleBackend(true);
            } else {
                refreshSmartUpscaleState();
            }
            notifyProtectionState();
        }

        private boolean canReduceVideoIntermediateResolution() {
            if (!video
                    || !frameGenerationEnabled
                    || !isVideoPrepared
                    || !Float.isFinite(videoSourceFps)
                    || videoSourceFps < 1f
                    || sourceFpsSamples < 3
                    || calculateFiTargetFps() <= 0
                    || videoIntermediateResolutionTier >= VIDEO_FI_RESOLUTION_FACTORS.length - 1) {
                return false;
            }
            float nextFactor = VIDEO_FI_RESOLUTION_FACTORS[videoIntermediateResolutionTier + 1];
            int orientedWidth =
                    (mediaOrientation == 90 || mediaOrientation == 270) ? videoHeight : videoWidth;
            int orientedHeight =
                    (mediaOrientation == 90 || mediaOrientation == 270) ? videoWidth : videoHeight;
            return Math.round(orientedWidth * nextFactor) >= width
                    && Math.round(orientedHeight * nextFactor) >= height;
        }

        private boolean canRecoverVideoIntermediateResolution() {
            return video
                    && frameGenerationEnabled
                    && videoIntermediateResolutionTier > 0
                    && isVideoPrepared
                    && Float.isFinite(videoSourceFps)
                    && videoSourceFps >= 1f
                    && sourceFpsSamples >= 3
                    && calculateFiTargetFps() > 0;
        }

        private boolean canReducePhotoTextureQuality() {
            return !isStandaloneEffectRendering()
                    && !video
                    && !photoTextureAdaptationBlocked
                    && !photoTextureRebuildPending
                    && photoTextureTier > 0;
        }

        private void registerThermalStatusMonitor() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
                standaloneEffectThermalLevel = StandaloneEffectThermalLevel.NONE;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_THERMAL_MONITOR",
                        "supported=false,reason=api_below_29");
                return;
            }

            try {
                thermalStatusMonitor =
                        new ThermalStatusMonitor(
                                context, this::updateStandaloneEffectThermalStatus);
                updateStandaloneEffectThermalStatus(thermalStatusMonitor.getCurrentThermalStatus());
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_THERMAL_MONITOR",
                        "supported=true,status=" + standaloneEffectThermalLevel.name());
            } catch (Throwable error) {
                if (thermalStatusMonitor != null) {
                    try {
                        thermalStatusMonitor.unregister();
                    } catch (Throwable ignored) {
                    }
                }
                thermalStatusMonitor = null;
                standaloneEffectThermalLevel = StandaloneEffectThermalLevel.NONE;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_THERMAL_MONITOR_FAILED",
                        error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
            }
        }

        private void updateStandaloneEffectThermalStatus(int platformStatus) {
            standaloneEffectThermalLevel =
                    StandaloneEffectQualityController.mapAndroidThermalStatus(platformStatus);
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        private void startStandaloneEffectQualityController() {
            DeviceCapabilityProfile.Profile deviceProfile = null;
            try {
                deviceProfile = DeviceCapabilityProfile.ensure(context);
            } catch (Throwable error) {
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_HARDWARE_PROFILE_FAILED",
                        error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
            }

            int cpuCoreCount = 0;
            try {
                cpuCoreCount = Runtime.getRuntime().availableProcessors();
            } catch (Throwable ignored) {
            }

            long totalMemoryBytes = 0L;
            try {
                ActivityManager activityManager =
                        (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
                if (activityManager != null) {
                    ActivityManager.MemoryInfo memoryInfo = new ActivityManager.MemoryInfo();
                    activityManager.getMemoryInfo(memoryInfo);
                    totalMemoryBytes = memoryInfo.totalMem;
                }
            } catch (Throwable error) {
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_MEMORY_PROFILE_FAILED",
                        error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
            }

            int displayWidth = deviceProfile == null ? 0 : deviceProfile.displayWidth;
            int displayHeight = deviceProfile == null ? 0 : deviceProfile.displayHeight;
            String socManufacturer = deviceProfile == null ? "" : deviceProfile.socManufacturer;
            String socModel = deviceProfile == null ? "" : deviceProfile.socModel;
            StandaloneEffectHardwareProfile hardwareProfile =
                    new StandaloneEffectHardwareProfile(
                            eglGlRenderer,
                            cpuCoreCount,
                            totalMemoryBytes,
                            displayWidth,
                            displayHeight,
                            eglGlVendor,
                            eglGlVersion,
                            socManufacturer,
                            socModel);
            StandaloneEffectQualityTier initialQuality =
                    StandaloneEffectQualityController.chooseInitialQuality(hardwareProfile);
            standaloneEffectQualityController =
                    new StandaloneEffectQualityController(hardwareProfile, initialQuality);

            WallpaperBugRecorder.record(
                    context,
                    "GPU_EFFECT_HARDWARE_PROFILE",
                    "gpuRenderer="
                            + eglGlRenderer
                            + ",gpuVendor="
                            + eglGlVendor
                            + ",glVersion="
                            + eglGlVersion
                            + ",soc="
                            + socManufacturer
                            + "/"
                            + socModel
                            + ",cpuCores="
                            + cpuCoreCount
                            + ",totalRamBytes="
                            + totalMemoryBytes
                            + ",display="
                            + displayWidth
                            + "x"
                            + displayHeight
                            + ",initialQuality="
                            + initialQuality.name());
            notifyStandaloneEffectQualityChanged(standaloneEffectId, initialQuality.name());
        }

        private void recordStandaloneEffectQuality(
                long timestampNs,
                long frameIntervalNs,
                long renderDurationNs,
                boolean standaloneEffectRequested,
                boolean healthProbe) {
            StandaloneEffectQualityController controller = standaloneEffectQualityController;
            if (!standaloneEffectRequested
                    || healthProbe
                    || !standaloneEffectMode
                    || activeStandaloneEffect == null
                    || controller == null) {
                return;
            }

            StandaloneEffectQualityTier previousQuality = controller.getQuality();
            StandaloneEffectPerformanceSample sample =
                    new StandaloneEffectPerformanceSample(
                            timestampNs,
                            30,
                            renderDurationNs,
                            frameIntervalNs,
                            standaloneEffectThermalLevel);
            StandaloneEffectQualityDecision decision = controller.update(sample);
            if (decision.getChanged()) {
                standaloneEffectFrameUpdatePending = true;
                notifyStandaloneEffectQualityChanged(
                        standaloneEffectId, decision.getQuality().name());
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_QUALITY_CHANGED",
                        "effect="
                                + standaloneEffectId
                                + ",from="
                                + previousQuality.name()
                                + ",to="
                                + decision.getQuality().name()
                                + ",reason="
                                + decision.getReason().name());
            }
        }

        private void recordFramePerformance(
                long frameIntervalNs, long renderDurationNs, boolean healthProbe) {
            if (!autoPauseEnabled || !visible || !surfaceReady) return;
            long nowNs = System.nanoTime();
            int targetFps =
                    isStandaloneEffectRendering() ? 30 : Math.max(30, calculateFiPresentationFps());
            long budgetNs = 1_000_000_000L / targetFps;

            if (healthProbe) {
                boolean slow = renderDurationNs > (budgetNs * 120L) / 100L;
                if (!slow) {
                    recoveryGoodProbes++;
                    if (hardPaused && recoveryGoodProbes >= HEALTH_PROBES_TO_RESUME)
                        exitHardPause(nowNs);
                } else {
                    recoveryGoodProbes = 0;
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_HEALTH_PROBE_STILL_SLOW",
                            "renderMs="
                                    + (renderDurationNs / 1_000_000L)
                                    + ",targetFps="
                                    + targetFps);
                }
                return;
            }
            if (hardPaused) return;
            timingSampleCount++;
            if (timingSampleCount < 20 || frameIntervalNs <= 0L) return;

            boolean slow =
                    frameIntervalNs > (budgetNs * 135L) / 100L
                            || renderDurationNs > (long) (budgetNs * RENDER_STRAIN_BUDGET_FRACTION);
            boolean resolutionHeadroom =
                    renderDurationNs <= (long) (budgetNs * QUALITY_RECOVERY_RENDER_BUDGET_FRACTION)
                            && frameIntervalNs
                                    <= (long)
                                            (budgetNs * QUALITY_RECOVERY_INTERVAL_BUDGET_FRACTION);

            if (slow) {
                stableSinceNs = 0L;
                if (slowSinceNs == 0L) slowSinceNs = nowNs;
                long requiredNs =
                        effectiveTargetFps <= 30 ? HARD_PAUSE_CONFIRM_NS : RENDER_STRAIN_CONFIRM_NS;
                if (nowNs - slowSinceNs >= requiredNs
                        && nowNs - lastThrottleChangeNs >= THROTTLE_HYSTERESIS_NS) {
                    if (effectiveTargetFps > 30) {
                        int next = stepDownFps(effectiveTargetFps);
                        if (next < effectiveTargetFps) {
                            autoThrottleTargetFps = next;
                            effectiveTargetFps = next;
                            lastThrottleChangeNs = nowNs;
                            slowSinceNs = 0L;
                            nextFrameDeadlineNs = 0L;
                            WallpaperBugRecorder.record(
                                    context,
                                    "GPU_RENDER_STRAIN_THROTTLE",
                                    "fromFps="
                                            + targetFps
                                            + ",toFps="
                                            + next
                                            + ",systemHz="
                                            + systemRefreshRateFps);
                            notifyProtectionState();
                        }
                    } else if (smartUpscaleEnabled
                            && !smartUpscaleStrainDegraded
                            && !isLightweightBackend()) {
                        smartUpscaleStrainDegraded = true;
                        lastThrottleChangeNs = nowNs;
                        slowSinceNs = 0L;
                        nextFrameDeadlineNs = 0L;
                        applySmartUpscaleBackend(true);
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_RENDER_STRAIN_SMART_UPSCALE_DEGRADE_LS",
                                "fps=" + effectiveTargetFps + ",backend=" + smartUpscaleBackend);
                        notifyProtectionState();
                    } else if (smartUpscaleEnabled && !smartUpscaleStrainBypassed) {
                        smartUpscaleStrainBypassed = true;
                        lastThrottleChangeNs = nowNs;
                        slowSinceNs = 0L;
                        nextFrameDeadlineNs = 0L;
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_RENDER_STRAIN_SMART_UPSCALE_BYPASS",
                                "fps=30,reason=strain_throttle");
                        refreshSmartUpscaleState();
                        notifyProtectionState();
                    } else if (!video && photoTextureRebuildPending) {
                        lastThrottleChangeNs = nowNs;
                        slowSinceNs = 0L;
                    } else if (!video && canReducePhotoTextureQuality()) {
                        int nextTier = photoTextureTier - 1;
                        if (requestPhotoTextureTier(nextTier, "sustained_render_strain")) {
                            lastThrottleChangeNs = nowNs;
                            slowSinceNs = 0L;
                            nextFrameDeadlineNs = 0L;
                            WallpaperBugRecorder.record(
                                    context,
                                    "GPU_PHOTO_RESOLUTION_THROTTLE",
                                    "fromTier="
                                            + photoTextureTier
                                            + ",toTier="
                                            + nextTier
                                            + ",effectiveFps="
                                            + effectiveTargetFps);
                            notifyProtectionState();
                        } else {
                            enterHardPause(nowNs);
                        }
                    } else if (video && canReduceVideoIntermediateResolution()) {
                        int previousTier = videoIntermediateResolutionTier;
                        videoIntermediateResolutionTier++;
                        lastThrottleChangeNs = nowNs;
                        slowSinceNs = 0L;
                        nextFrameDeadlineNs = 0L;
                        interpolationPreviousReady = false;
                        interpolationPending = false;
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_VIDEO_FI_RESOLUTION_THROTTLE",
                                "fromFactor="
                                        + VIDEO_FI_RESOLUTION_FACTORS[previousTier]
                                        + ",toFactor="
                                        + VIDEO_FI_RESOLUTION_FACTORS[
                                                videoIntermediateResolutionTier]
                                        + ",effectiveFps="
                                        + effectiveTargetFps);
                        notifyProtectionState();
                    } else {
                        enterHardPause(nowNs);
                    }
                }
            } else {
                slowSinceNs = 0L;
                boolean recoverPhoto =
                        !isStandaloneEffectRendering()
                                && !video
                                && !photoTextureRebuildPending
                                && !photoTextureAdaptationBlocked
                                && !photoTextureQualityIncreaseBlocked
                                && photoTextureTier < PHOTO_TEXTURE_QUALITY_FACTORS.length - 1
                                && resolutionHeadroom;
                boolean recoverVideo =
                        canRecoverVideoIntermediateResolution() && resolutionHeadroom;
                boolean needsRecovery =
                        smartUpscaleStrainBypassed
                                || smartUpscaleStrainDegraded
                                || effectiveTargetFps < calculateNormalTargetFps()
                                || recoverPhoto
                                || recoverVideo;
                if (needsRecovery) {
                    if (stableSinceNs == 0L) stableSinceNs = nowNs;
                    long waitNs =
                            ((recoverPhoto || recoverVideo)
                                            && effectiveTargetFps >= calculateNormalTargetFps()
                                            && !smartUpscaleStrainBypassed
                                            && !smartUpscaleStrainDegraded)
                                    ? QUALITY_RECOVERY_CONFIRM_NS
                                    : RECOVERY_CONFIRM_NS;
                    if (nowNs - stableSinceNs >= waitNs
                            && nowNs - lastThrottleChangeNs >= THROTTLE_HYSTERESIS_NS) {
                        if (smartUpscaleStrainBypassed) {
                            smartUpscaleStrainBypassed = false;
                            smartUpscaleStrainDegraded = true;
                            lastThrottleChangeNs = nowNs;
                            stableSinceNs = 0L;
                            applySmartUpscaleBackend(true);
                            WallpaperBugRecorder.record(
                                    context,
                                    "GPU_RENDER_STRAIN_SMART_UPSCALE_RESTORED_LS",
                                    "fps=" + effectiveTargetFps);
                            refreshSmartUpscaleState();
                            notifyProtectionState();
                        } else if (effectiveTargetFps < calculateNormalTargetFps()) {
                            int normal = calculateNormalTargetFps();
                            int next = stepUpFps(effectiveTargetFps, normal);
                            long candidateBudgetNs =
                                    next > 0 ? 1_000_000_000L / next : Long.MAX_VALUE;
                            boolean candidateHasHeadroom =
                                    next > effectiveTargetFps
                                            && renderDurationNs
                                                    <= (long) (candidateBudgetNs * 0.65f);
                            if (candidateHasHeadroom) {
                                autoThrottleTargetFps = next;
                                effectiveTargetFps = next;
                                lastThrottleChangeNs = nowNs;
                                stableSinceNs = 0L;
                                nextFrameDeadlineNs = 0L;
                                WallpaperBugRecorder.record(
                                        context,
                                        "GPU_RENDER_STRAIN_RECOVERY",
                                        "fromFps="
                                                + targetFps
                                                + ",toFps="
                                                + next
                                                + ",systemHz="
                                                + systemRefreshRateFps);
                                notifyProtectionState();
                            } else {
                                stableSinceNs = 0L;
                            }
                        } else if (recoverPhoto) {
                            int nextTier = photoTextureTier + 1;
                            if (requestPhotoTextureTier(
                                    nextTier, "sustained_performance_headroom")) {
                                lastThrottleChangeNs = nowNs;
                                stableSinceNs = 0L;
                                WallpaperBugRecorder.record(
                                        context,
                                        "GPU_PHOTO_RESOLUTION_RECOVERY_REQUESTED",
                                        "fromTier=" + photoTextureTier + ",toTier=" + nextTier);
                                notifyProtectionState();
                            }
                        } else if (recoverVideo) {
                            int previousTier = videoIntermediateResolutionTier;
                            videoIntermediateResolutionTier--;
                            lastThrottleChangeNs = nowNs;
                            stableSinceNs = 0L;
                            nextFrameDeadlineNs = 0L;
                            interpolationPreviousReady = false;
                            interpolationPending = false;
                            WallpaperBugRecorder.record(
                                    context,
                                    "GPU_VIDEO_FI_RESOLUTION_RECOVERY",
                                    "fromFactor="
                                            + VIDEO_FI_RESOLUTION_FACTORS[previousTier]
                                            + ",toFactor="
                                            + VIDEO_FI_RESOLUTION_FACTORS[
                                                    videoIntermediateResolutionTier]);
                            notifyProtectionState();
                        } else if (smartUpscaleStrainDegraded) {
                            smartUpscaleStrainDegraded = false;
                            lastThrottleChangeNs = nowNs;
                            stableSinceNs = 0L;
                            applySmartUpscaleBackend(true);
                            WallpaperBugRecorder.record(
                                    context,
                                    "GPU_RENDER_STRAIN_SMART_UPSCALE_OPTIMAL_RESTORED",
                                    "preference="
                                            + smartUpscaleBackendPreference
                                            + ",backend="
                                            + smartUpscaleBackend);
                            notifyProtectionState();
                        }
                    }
                } else {
                    stableSinceNs = 0L;
                }
            }
        }

        private void notifyProtectionState() {
            PerformanceListener listener = performanceListener;
            if (listener == null) return;
            try {
                listener.onRendererProtectionChanged(
                        hardPaused || autoThrottleTargetFps < calculateNormalTargetFps(),
                        hardPaused,
                        effectiveTargetFps,
                        systemRefreshRateFps);
            } catch (Throwable t) {
                WallpaperBugRecorder.record(
                        context,
                        "GPU_PROTECTION_CALLBACK_FAILED",
                        t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
            }
        }

        private void paceNextFrame(long frameEndNs) {
            // Present the generated frame at this source pair's measured midpoint,
            // rather than relying only on an averaged FPS interval. This keeps
            // variable-frame-rate source pairs aligned with their own timestamps.
            if (video
                    && frameGenerationEnabled
                    && interpolationPending
                    && !interpolationShownForCurrent
                    && interpolationTargetNs > frameEndNs) {
                nextFrameDeadlineNs = interpolationTargetNs;
                waitUntilDeadline(nextFrameDeadlineNs);
                return;
            }

            long intervalNs = 1_000_000_000L / Math.max(30, calculateFiPresentationFps());
            if (nextFrameDeadlineNs <= 0L) {
                nextFrameDeadlineNs = frameEndNs + intervalNs;
            } else {
                nextFrameDeadlineNs += intervalNs;
                if (nextFrameDeadlineNs <= frameEndNs) {
                    nextFrameDeadlineNs = frameEndNs + intervalNs;
                }
            }

            waitUntilDeadline(nextFrameDeadlineNs);
        }

        private void waitUntilDeadline(long deadlineNs) {
            while (running) {
                long remainingNs = deadlineNs - System.nanoTime();
                if (remainingNs <= 0L) return;
                synchronized (wakeLock) {
                    if (!running || !commands.isEmpty()) return;
                    remainingNs = deadlineNs - System.nanoTime();
                    if (remainingNs <= 0L) return;
                    long cappedNs = Math.min(remainingNs, 500_000_000L);
                    long ms = cappedNs / 1_000_000L;
                    int ns = (int) (cappedNs % 1_000_000L);
                    try {
                        wakeLock.wait(ms, ns);
                    } catch (InterruptedException ignored) {
                    }
                }
            }
        }

        private void waitForRendererWork() {
            synchronized (wakeLock) {
                if (!running || !commands.isEmpty()) return;

                boolean effectRequested = isStandaloneEffectRendering();
                StandaloneWallpaperEffect requestedEffect =
                        pendingStandaloneEffect != null
                                ? pendingStandaloneEffect
                                : activeStandaloneEffect;
                boolean effectReady =
                        effectRequested && requestedEffect != null && requestedEffect.isReady();
                boolean animationEnabled = isRequestedEffectAnimationEnabled();

                if (effectReady
                        && (animationEnabled || standaloneEffectFrameUpdatePending)
                        && visible
                        && !playbackPaused
                        && !autoLoadPaused) {
                    long nowNs = System.nanoTime();
                    long waitNs =
                            Math.max(
                                    1_000_000L,
                                    Math.min(
                                            500_000_000L,
                                            standaloneEffectNextFrameNs > nowNs
                                                    ? standaloneEffectNextFrameNs - nowNs
                                                    : 1_000_000L));
                    long ms = waitNs / 1_000_000L;
                    int ns = (int) (waitNs % 1_000_000L);
                    try {
                        wakeLock.wait(ms, ns);
                    } catch (InterruptedException ignored) {
                    }
                    return;
                }

                boolean mediaReady =
                        effectRequested
                                ? effectReady
                                : (video ? isVideoPrepared : (currentUri != null));
                boolean needsTimedHealthProbe =
                        StandaloneEffectLifecyclePolicy.canRunHealthProbe(
                                autoLoadPaused,
                                visible,
                                surfaceReady,
                                mediaReady,
                                effectRequested,
                                animationEnabled,
                                hardPaused);
                if (!needsTimedHealthProbe) {
                    // Frozen effects are event-driven; do not poll while nothing can change.
                    try {
                        wakeLock.wait();
                    } catch (InterruptedException ignored) {
                    }
                    return;
                }

                long nowNs = System.nanoTime();
                long waitNs =
                        Math.max(
                                1_000_000L,
                                Math.min(
                                        500_000_000L,
                                        healthProbeDueNs > nowNs
                                                ? healthProbeDueNs - nowNs
                                                : 1_000_000L));
                long ms = waitNs / 1_000_000L;
                int ns = (int) (waitNs % 1_000_000L);
                try {
                    wakeLock.wait(ms, ns);
                } catch (InterruptedException ignored) {
                }
            }
        }

        private void resetPerformanceProtection(String reason) {
            hardPaused = false;
            autoLoadPaused = false;
            smartUpscaleStrainBypassed = false;
            smartUpscaleStrainDegraded = false;
            recoveryGoodProbes = 0;
            slowSinceNs = 0L;
            stableSinceNs = 0L;
            timingSampleCount = 0;
            lastFrameEndNs = 0L;
            nextFrameDeadlineNs = 0L;
            healthProbeDueNs = 0L;
            autoThrottleTargetFps = calculateNormalTargetFps();
            effectiveTargetFps = autoThrottleTargetFps;
            motionFrozen = false;
            pauseStabilizationActive = false;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_RENDER_PROTECTION_RESET",
                    "reason="
                            + reason
                            + ",effectiveFps="
                            + effectiveTargetFps
                            + ",systemHz="
                            + systemRefreshRateFps);
            refreshSmartUpscaleState();
            notifyProtectionState();
        }

        private boolean lastLoggedMotionEnabled;
        private boolean lastLoggedGyro2d;
        private boolean lastLoggedGyro3d;
        private boolean lastLoggedGyroRotation;
        private int lastLoggedOrientation = -1;

        void setGyro(
                float roll,
                float pitch,
                float depth,
                float rotationAngleDeg,
                boolean enabled,
                boolean gyro2d,
                boolean gyro3d,
                boolean gyroRotation,
                boolean gyro2dInverted,
                boolean gyro3dInverted,
                boolean gyroRotationInverted,
                int s2x,
                int s2y,
                int s3x,
                int s3y,
                int sDepth,
                int a2x,
                int a2y,
                int a3x,
                int a3y,
                int aZoom,
                int aRotation,
                float zoomResponse,
                boolean edgeSafetyEnabled,
                float translationRate,
                float rotationRateDeg,
                float depthRate,
                float rotationRateZDeg,
                int rotationSensitivity,
                int orientation) {
            motionUpdatePending = true;
            gyroRoll = roll;
            gyroPitch = pitch;
            gyroDepth = depth;
            this.gyroRotation = rotationAngleDeg;
            gyroRotationSensitivity = Math.max(0, Math.min(100, rotationSensitivity));
            gyroEnabled = enabled;
            if (isStandaloneEffectRendering() && isRequestedEffectGyroEnabled()) {
                standaloneEffectFrameUpdatePending = true;
            }
            gyro2dEnabled = gyro2d;
            gyro3dEnabled = gyro3d;
            gyroRotationEnabled = gyroRotation;
            this.gyro2dInverted = gyro2dInverted;
            this.gyro3dInverted = gyro3dInverted;
            this.gyroRotationInverted = gyroRotationInverted;
            sens2dX = s2x;
            sens2dY = s2y;
            sens3dX = s3x;
            sens3dY = s3y;
            sensDepth = sDepth;
            auto2dX = a2x;
            auto2dY = a2y;
            auto3dX = a3x;
            auto3dY = a3y;
            autoZoom = aZoom;
            autoRotation = aRotation;
            zoomResponseFactor = Math.max(0f, Math.min(1.0f, zoomResponse));
            this.edgeSafetyEnabled = edgeSafetyEnabled;
            motionTranslationRate = Math.max(0.01f, Math.min(0.15f, translationRate));
            motionRotationRateDeg = Math.max(0.5f, Math.min(8.0f, rotationRateDeg));
            motionRotationRateZDeg = Math.max(0f, Math.min(15.0f, rotationRateZDeg));
            motionDepthRate = Math.max(0.25f, Math.min(4.0f, depthRate));
            mediaOrientation = orientation;
            boolean motionEffecting = enabled;
            boolean gyro2dEffecting = enabled && gyro2d;
            boolean gyro3dEffecting = enabled && gyro3d;
            boolean gyroRotationEffecting = enabled && gyroRotation;
            String effectiveMediaType = video ? "video" : "photo";

            boolean settingsChanged =
                    motionEffecting != lastLoggedMotionEnabled
                            || gyro2dEffecting != lastLoggedGyro2d
                            || gyro3dEffecting != lastLoggedGyro3d
                            || gyroRotationEffecting != lastLoggedGyroRotation
                            || orientation != lastLoggedOrientation;

            if (settingsChanged) {
                lastLoggedMotionEnabled = motionEffecting;
                lastLoggedGyro2d = gyro2dEffecting;
                lastLoggedGyro3d = gyro3dEffecting;
                lastLoggedGyroRotation = gyroRotationEffecting;
                lastLoggedOrientation = orientation;
                WallpaperBugRecorder.recordRuntimeSync(
                        context,
                        "GPU_MOTION_EFFECTIVE_STATE",
                        "mediaType="
                                + effectiveMediaType
                                + ", motionEffecting="
                                + motionEffecting
                                + ", gyro2dEffecting="
                                + gyro2dEffecting
                                + ", gyro3dEffecting="
                                + gyro3dEffecting
                                + ", gyroRotationEffecting="
                                + gyroRotationEffecting
                                + ", gyroRequested="
                                + (gyro2d || gyro3d || gyroRotation));
                WallpaperBugRecorder.recordRuntimeSync(
                        context,
                        "GPU_RENDERTHREAD_SETTINGS_APPLIED",
                        "gyroEnabled="
                                + enabled
                                + ", gyro2dEnabled="
                                + gyro2d
                                + ", gyro3dEnabled="
                                + gyro3d
                                + ", sens2d="
                                + s2x
                                + "/"
                                + s2y
                                + ", sens3d="
                                + s3x
                                + "/"
                                + s3y
                                + ", sensDepth="
                                + sDepth
                                + ", auto2d="
                                + a2x
                                + "/"
                                + a2y
                                + ", auto3d="
                                + a3x
                                + "/"
                                + a3y
                                + ", autoZoom="
                                + aZoom
                                + ", zoomResponse="
                                + zoomResponse
                                + ", translationRate="
                                + translationRate
                                + ", rotationRate="
                                + rotationRateDeg
                                + ", depthRate="
                                + depthRate
                                + ", orientation="
                                + orientation);
            }
        }

        private boolean isStandaloneEffectRendering() {
            return standaloneEffectMode || pendingStandaloneEffect != null;
        }

        private boolean isRequestedEffectAnimationEnabled() {
            return pendingStandaloneEffect != null
                    ? pendingEffectAnimationEnabled
                    : standaloneEffectAnimationEnabled;
        }

        private boolean isRequestedEffectGyroEnabled() {
            return pendingStandaloneEffect != null
                    ? pendingEffectGyroEnabled
                    : standaloneEffectGyroEnabled;
        }

        void requestStandaloneEffectFrameIfNeeded() {
            if (isStandaloneEffectRendering() && isRequestedEffectGyroEnabled()) {
                standaloneEffectFrameUpdatePending = true;
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
            }
        }

        private void restoreActiveEffectPreference() {
            if (!standaloneEffectMode || activeStandaloneEffect == null) {
                standaloneEffectPreference = "";
                return;
            }
            standaloneEffectPreference = standaloneEffectId;
            standaloneEffectSpeedPreference = standaloneEffectSpeed;
            standaloneEffectIntensityPreference = standaloneEffectIntensity;
            standaloneEffectGyroEnabledPreference = standaloneEffectGyroEnabled;
            standaloneEffectInwardPreference = standaloneEffectInward;
            standaloneEffectAnimationEnabledPreference = standaloneEffectAnimationEnabled;
        }

        void setStandaloneEffect(
                String effectId,
                int speed,
                int intensity,
                boolean gyroEnabled,
                boolean inward,
                boolean animationEnabled) {
            String normalized = normalizeStandaloneEffect(effectId);
            if (normalized.isEmpty()) {
                standaloneEffectMode = false;
                standaloneEffectId = "";
                standaloneEffectFrameUpdatePending = false;
                standaloneEffectFramePresented = false;
                standaloneEffectPhaseRadians = 0.0d;
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                standaloneEffectFallbackMedia = "";
                if (surfaceReady && eglSurface != EGL14.EGL_NO_SURFACE) {
                    clearStandaloneEffectResources();
                } else {
                    standaloneEffectReleasePending = true;
                }
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
                return;
            }

            if (!surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) {
                // Defer GL creation until createWindowSurface has made the EGL context current.
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
                return;
            }

            int safeSpeed = Math.max(0, Math.min(100, speed));
            int safeIntensity = Math.max(0, Math.min(100, intensity));

            if (standaloneEffectReleasePending) {
                clearStandaloneEffectResources();
            }

            if (pendingStandaloneEffect != null
                    && normalized.equals(pendingStandaloneEffect.getEffectId())) {
                pendingEffectSpeed = safeSpeed;
                pendingEffectIntensity = safeIntensity;
                pendingEffectGyroEnabled = gyroEnabled;
                pendingEffectInward = inward;
                pendingEffectAnimationEnabled = animationEnabled;
                standaloneEffectFrameUpdatePending = true;
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
                return;
            }

            if (pendingStandaloneEffect == null
                    && standaloneEffectMode
                    && normalized.equals(standaloneEffectId)) {
                standaloneEffectSpeed = safeSpeed;
                standaloneEffectIntensity = safeIntensity;
                standaloneEffectGyroEnabled = gyroEnabled;
                standaloneEffectInward = inward;
                standaloneEffectAnimationEnabled = animationEnabled;
                standaloneEffectFrameUpdatePending = true;
                standaloneEffectLastPhaseUpdateNs = 0L;
                standaloneEffectNextFrameNs = 0L;
                restoreActiveEffectPreference();
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
                return;
            }

            if (pendingStandaloneEffect != null) {
                try {
                    pendingStandaloneEffect.release();
                } catch (Throwable ignored) {
                }
                pendingStandaloneEffect = null;
            }

            StandaloneWallpaperEffect nextEffect = StandaloneEffectRegistry.create(normalized);
            try {
                if (nextEffect == null) {
                    throw new IllegalArgumentException(
                            "Unsupported standalone effect: " + normalized);
                }
                nextEffect.create(context);
                if (!nextEffect.isReady()) {
                    throw new IllegalStateException("Standalone effect did not become ready");
                }
            } catch (Throwable error) {
                if (nextEffect != null) {
                    try {
                        nextEffect.release();
                    } catch (Throwable ignored) {
                    }
                }
                WallpaperBugRecorder.record(
                        context,
                        "GPU_EFFECT_INIT_FAILED",
                        normalized
                                + ":"
                                + error.getClass().getName()
                                + ":"
                                + String.valueOf(error.getMessage()));
                if (standaloneEffectMode && activeStandaloneEffect != null) {
                    String retainedEffectId = standaloneEffectId;
                    restoreActiveEffectPreference();
                    standaloneEffectFramePresented = true;
                    notifyStandaloneEffectRecovery(
                            normalized, "init_failed", false, false, retainedEffectId);
                } else {
                    standaloneEffectMode = false;
                    standaloneEffectId = "";
                    standaloneEffectPreference = "";
                    if (currentUri == null) {
                        notifyStandaloneEffectRecovery(normalized, "init_failed", false, false, "");
                        notifyRendererFailure("standalone_effect_init_failed");
                    } else {
                        notifyStandaloneEffectRecovery(normalized, "init_failed", true, false, "");
                    }
                }
                synchronized (wakeLock) {
                    wakeLock.notifyAll();
                }
                return;
            }

            pendingStandaloneEffect = nextEffect;
            standaloneEffectReleasePending = false;
            pendingEffectSpeed = safeSpeed;
            pendingEffectIntensity = safeIntensity;
            pendingEffectGyroEnabled = gyroEnabled;
            pendingEffectInward = inward;
            pendingEffectAnimationEnabled = animationEnabled;
            standaloneEffectFramePresented = false;
            standaloneEffectFrameUpdatePending = false;
            standaloneEffectPhaseRadians = 0.0d;
            standaloneEffectLastPhaseUpdateNs = 0L;
            standaloneEffectNextFrameNs = 0L;
            standaloneEffectSwapFailures = 0;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EFFECT_INIT_READY",
                    "effect=" + normalized + ",replacing=" + standaloneEffectMode);
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        private void clearStandaloneEffectResources() {
            if (standaloneEffectQualityController != null) {
                notifyStandaloneEffectQualityChanged("", "");
            }
            standaloneEffectQualityController = null;
            if (pendingStandaloneEffect != null) {
                try {
                    pendingStandaloneEffect.release();
                } catch (Throwable ignored) {
                }
                pendingStandaloneEffect = null;
            }
            if (activeStandaloneEffect != null) {
                try {
                    activeStandaloneEffect.release();
                } catch (Throwable ignored) {
                }
                activeStandaloneEffect = null;
            }
            standaloneEffectReleasePending = false;
            standaloneEffectMode = false;
            standaloneEffectId = "";
            standaloneEffectFallbackMedia = "";
            standaloneEffectFrameUpdatePending = false;
            standaloneEffectFramePresented = false;
            standaloneEffectPhaseRadians = 0.0d;
            standaloneEffectLastPhaseUpdateNs = 0L;
            standaloneEffectNextFrameNs = 0L;
            standaloneEffectSwapFailures = 0;
        }

        private void abandonPendingStandaloneEffect(String reason, Throwable error) {
            String failedEffectId =
                    pendingStandaloneEffect == null
                            ? standaloneEffectId
                            : pendingStandaloneEffect.getEffectId();
            if (pendingStandaloneEffect != null) {
                try {
                    pendingStandaloneEffect.release();
                } catch (Throwable ignored) {
                }
                pendingStandaloneEffect = null;
            }
            standaloneEffectSwapFailures = 0;
            standaloneEffectFrameUpdatePending = false;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EFFECT_CANDIDATE_ABORTED",
                    reason
                            + (error == null
                                    ? ""
                                    : ",error="
                                            + error.getClass().getName()
                                            + ":"
                                            + String.valueOf(error.getMessage())));
            if (standaloneEffectMode && activeStandaloneEffect != null) {
                String retainedEffectId = standaloneEffectId;
                restoreActiveEffectPreference();
                standaloneEffectFramePresented = true;
                standaloneEffectNextFrameNs = 0L;
                notifyStandaloneEffectRecovery(
                        failedEffectId, reason, false, false, retainedEffectId);
            } else {
                standaloneEffectMode = false;
                standaloneEffectId = "";
                standaloneEffectPreference = "";
                standaloneEffectFramePresented = false;
                if (currentUri == null) {
                    notifyStandaloneEffectRecovery(failedEffectId, reason, false, false, "");
                    notifyRendererFailure("standalone_effect_" + reason);
                } else {
                    notifyStandaloneEffectRecovery(failedEffectId, reason, true, false, "");
                }
            }
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
        }

        private double advanceStandaloneEffectPhase(long nowNs, boolean candidate) {
            boolean animationEnabled =
                    candidate ? pendingEffectAnimationEnabled : standaloneEffectAnimationEnabled;
            int speed = candidate ? pendingEffectSpeed : standaloneEffectSpeed;
            String effectId =
                    candidate && pendingStandaloneEffect != null
                            ? pendingStandaloneEffect.getEffectId()
                            : standaloneEffectId;
            double phaseCycleRadians = Math.PI * 2.0d;
            if (StandaloneEffectRegistry.ID_REACTOR_CORE.equals(effectId)) {
                // Reactor Core phase multipliers have denominators 20, 5, 10 and 25.
                // One hundred base turns are the first point where every moving layer repeats.
                phaseCycleRadians *= 100.0d;
            }
            if (!animationEnabled || !visible || playbackPaused || autoLoadPaused || hardPaused) {
                standaloneEffectLastPhaseUpdateNs = 0L;
                return standaloneEffectPhaseRadians;
            }
            if (standaloneEffectLastPhaseUpdateNs > 0L) {
                long elapsedNs = Math.max(0L, nowNs - standaloneEffectLastPhaseUpdateNs);
                double elapsedSeconds = Math.min(elapsedNs / 1_000_000_000.0d, 0.1d);
                double speedFactor = 0.2d + speed * 0.016d;
                standaloneEffectPhaseRadians += elapsedSeconds * 1.1d * speedFactor;
                standaloneEffectPhaseRadians %= phaseCycleRadians;
                if (standaloneEffectPhaseRadians < 0.0d) {
                    standaloneEffectPhaseRadians += phaseCycleRadians;
                }
            }
            standaloneEffectLastPhaseUpdateNs = nowNs;
            return standaloneEffectPhaseRadians;
        }

        private void recoverActiveStandaloneEffect(String reason, Throwable error) {
            String failedEffectId = standaloneEffectId;
            String fallbackMedia = standaloneEffectFallbackMedia;
            if (activeStandaloneEffect != null) {
                try {
                    activeStandaloneEffect.release();
                } catch (Throwable ignored) {
                }
                activeStandaloneEffect = null;
            }
            standaloneEffectMode = false;
            standaloneEffectId = "";
            standaloneEffectFallbackMedia = "";
            standaloneEffectFramePresented = false;
            standaloneEffectFrameUpdatePending = false;
            standaloneEffectPreference = "";
            standaloneEffectLastPhaseUpdateNs = 0L;
            standaloneEffectNextFrameNs = 0L;
            standaloneEffectSwapFailures = 0;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_EFFECT_RECOVERY",
                    "reason="
                            + reason
                            + ",fallbackMedia="
                            + !fallbackMedia.isEmpty()
                            + (error == null
                                    ? ""
                                    : ",error="
                                            + error.getClass().getName()
                                            + ":"
                                            + String.valueOf(error.getMessage())));
            if (!fallbackMedia.isEmpty()) {
                loadMedia(fallbackMedia);
                notifyStandaloneEffectRecovery(failedEffectId, reason, true, true, "");
            } else {
                notifyStandaloneEffectRecovery(failedEffectId, reason, false, false, "");
                notifyRendererFailure("standalone_effect_" + reason);
            }
        }

        private StandaloneEffectRenderQuality currentStandaloneEffectRenderQuality() {
            StandaloneEffectQualityController controller = standaloneEffectQualityController;
            if (controller == null) return StandaloneEffectRenderQuality.HIGH;

            switch (controller.getQuality()) {
                case EFFICIENT:
                    return StandaloneEffectRenderQuality.EFFICIENT;
                case BALANCED:
                    return StandaloneEffectRenderQuality.BALANCED;
                case HIGH:
                default:
                    return StandaloneEffectRenderQuality.HIGH;
            }
        }

        private void renderStandaloneEffectFrame() {
            boolean candidate = pendingStandaloneEffect != null;
            StandaloneWallpaperEffect effect =
                    candidate ? pendingStandaloneEffect : activeStandaloneEffect;
            if (effect == null || !effect.isReady()) {
                if (candidate) {
                    abandonPendingStandaloneEffect("not_ready", null);
                } else {
                    recoverActiveStandaloneEffect("not_ready", null);
                }
                return;
            }

            long nowNs = System.nanoTime();
            synchronized (wakeLock) {
                // Consume the latest sensor state and pending input together. Updates
                // arriving during this frame remain pending for the next redraw.
                gyroRoll = latestGyroRoll;
                gyroPitch = latestGyroPitch;
                gyroDepth = latestGyroDepth;
                gyroRotation = latestGyroRotation;
                standaloneEffectFrameUpdatePending = false;
            }
            try {
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                StandaloneEffectRenderQuality renderQuality =
                        candidate
                                ? StandaloneEffectRenderQuality.HIGH
                                : currentStandaloneEffectRenderQuality();
                effect.draw(
                        new StandaloneEffectFrame(
                                width,
                                height,
                                deviceDisplayShortSide,
                                candidate ? pendingEffectSpeed : standaloneEffectSpeed,
                                candidate ? pendingEffectIntensity : standaloneEffectIntensity,
                                candidate ? pendingEffectGyroEnabled : standaloneEffectGyroEnabled,
                                (candidate ? pendingEffectInward : standaloneEffectInward)
                                        ? "inward"
                                        : "outward",
                                (candidate ? pendingEffectGyroEnabled : standaloneEffectGyroEnabled)
                                        ? gyroRoll
                                        : 0f,
                                (candidate ? pendingEffectGyroEnabled : standaloneEffectGyroEnabled)
                                        ? gyroPitch
                                        : 0f,
                                advanceStandaloneEffectPhase(nowNs, candidate),
                                renderQuality));

                if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                    int glError = EGL14.eglGetError();
                    standaloneEffectSwapFailures++;
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_EFFECT_SWAP_FAILED",
                            "effect="
                                    + effect.getEffectId()
                                    + ",eglError=0x"
                                    + Integer.toHexString(glError)
                                    + ",consecutive="
                                    + standaloneEffectSwapFailures);
                    if (standaloneEffectSwapFailures >= 3) {
                        if (candidate) {
                            abandonPendingStandaloneEffect(
                                    "swap_failed:0x" + Integer.toHexString(glError), null);
                        } else {
                            recoverActiveStandaloneEffect(
                                    "swap_failed:0x" + Integer.toHexString(glError), null);
                        }
                    }
                    return;
                }

                standaloneEffectSwapFailures = 0;
                if (candidate) {
                    String fallbackMedia =
                            standaloneEffectMode
                                    ? standaloneEffectFallbackMedia
                                    : (currentUri == null ? "" : currentUri.toString());
                    StandaloneWallpaperEffect previousEffect = activeStandaloneEffect;
                    activeStandaloneEffect = pendingStandaloneEffect;
                    pendingStandaloneEffect = null;
                    standaloneEffectMode = true;
                    standaloneEffectId = activeStandaloneEffect.getEffectId();
                    standaloneEffectFallbackMedia = fallbackMedia;
                    standaloneEffectSpeed = pendingEffectSpeed;
                    standaloneEffectIntensity = pendingEffectIntensity;
                    standaloneEffectGyroEnabled = pendingEffectGyroEnabled;
                    standaloneEffectInward = pendingEffectInward;
                    standaloneEffectAnimationEnabled = pendingEffectAnimationEnabled;
                    standaloneEffectFrameUpdatePending = false;
                    standaloneEffectLastPhaseUpdateNs = nowNs;
                    standaloneEffectNextFrameNs = 0L;
                    lastFrameEndNs = 0L;
                    timingSampleCount = 0;
                    slowSinceNs = 0L;
                    stableSinceNs = 0L;
                    startStandaloneEffectQualityController();
                    standaloneEffectFrameUpdatePending =
                            standaloneEffectQualityController != null
                                    && standaloneEffectQualityController.getQuality()
                                            != StandaloneEffectQualityTier.HIGH;

                    // The first candidate frame is now on screen. Only now release media
                    // resources and the previously active effect.
                    releaseMedia();
                    if (previousEffect != null) {
                        try {
                            previousEffect.release();
                        } catch (Throwable ignored) {
                        }
                    }
                    restoreActiveEffectPreference();
                }

                if (!standaloneEffectFramePresented) {
                    standaloneEffectFramePresented = true;
                    WallpaperBugRecorder.record(
                            context, "GPU_EFFECT_FIRST_FRAME", "effect=" + standaloneEffectId);
                    FramePresentedListener listener = framePresentedListener;
                    if (listener != null) {
                        try {
                            listener.onFramePresented();
                        } catch (Throwable ignored) {
                        }
                    }
                }
            } catch (Throwable error) {
                if (candidate) {
                    abandonPendingStandaloneEffect("render_failed", error);
                } else {
                    recoverActiveStandaloneEffect("render_failed", error);
                }
            }
        }

        private void paceStandaloneEffectFrame(long frameStartNs, long frameEndNs) {
            boolean animationEnabled = isRequestedEffectAnimationEnabled();
            boolean gyroUpdatesEnabled = isRequestedEffectGyroEnabled();
            boolean needsAnotherFrame =
                    animationEnabled
                            || standaloneEffectFrameUpdatePending
                            || !standaloneEffectFramePresented;
            if (!isStandaloneEffectRendering() || playbackPaused || autoLoadPaused || !visible) {
                standaloneEffectNextFrameNs = 0L;
                return;
            }

            long intervalNs = 1_000_000_000L / 30L;
            if (!needsAnotherFrame) {
                // A frozen gyro effect retains a deadline for the next input update,
                // but it does not wake the render thread until a new update arrives.
                standaloneEffectNextFrameNs = gyroUpdatesEnabled ? frameEndNs + intervalNs : 0L;
                return;
            }

            long nextFrameNs = standaloneEffectNextFrameNs;
            if (nextFrameNs <= 0L) {
                nextFrameNs = frameStartNs;
            }
            nextFrameNs += intervalNs;
            // Re-anchor after a late frame instead of spinning to catch up.
            if (nextFrameNs <= frameEndNs) {
                nextFrameNs = frameEndNs + intervalNs;
            }
            standaloneEffectNextFrameNs = nextFrameNs;
            waitUntilDeadline(nextFrameNs);
        }

        void loadMedia(String media) {
            loadMedia(media, null);
        }

        void loadMedia(String media, Runnable onPreviousMediaReleased) {
            setStandaloneEffect("", 50, 50, false, false, true);
            resetPerformanceProtection("media_changed");
            releaseMedia();
            if (onPreviousMediaReleased != null) {
                try {
                    onPreviousMediaReleased.run();
                } catch (Throwable ignored) {
                }
            }
            consecutiveSwapFailures = 0;
            currentUri = media == null || media.trim().isEmpty() ? null : Uri.parse(media);
            if (currentUri == null) return;
            String type = null;
            try {
                type = context.getContentResolver().getType(currentUri);
            } catch (Exception ignored) {
            }
            if (type == null) {
                String lower = media.toLowerCase(java.util.Locale.US);
                if (lower.endsWith(".mp4")
                        || lower.endsWith(".webm")
                        || lower.endsWith(".mkv")
                        || lower.endsWith(".3gp")) type = "video/*";
                else if (lower.endsWith(".jpg")
                        || lower.endsWith(".jpeg")
                        || lower.endsWith(".png")
                        || lower.endsWith(".webp")) type = "image/*";
            }
            WallpaperBugRecorder.record(context, "MEDIA_LOAD", "uri=" + media + ",type=" + type);
            if (type != null && type.startsWith("video/")) setupVideo(media);
            else setupImage(media);
        }

        InputStream openInputStream(String media) throws Exception {
            if (media == null) throw new IllegalArgumentException("media is null");
            if (media.startsWith("content://")
                    || media.startsWith("file://")
                    || media.startsWith("android.resource://")) {
                return context.getContentResolver().openInputStream(Uri.parse(media));
            }
            java.io.File f = new java.io.File(media);
            if (f.exists()) return new java.io.FileInputStream(f);
            String asset =
                    media.startsWith("assets/") ? media.substring("assets/".length()) : media;
            try {
                return context.getAssets().open(asset);
            } catch (Exception ignored) {
                return context.getAssets().open("public/wallpapers/" + asset);
            }
        }

        void setPlayerDataSource(MediaPlayer mp, String media) throws Exception {
            if (media == null) throw new IllegalArgumentException("media is null");
            if (media.startsWith("content://")) {
                android.os.ParcelFileDescriptor pfd =
                        context.getContentResolver().openFileDescriptor(Uri.parse(media), "r");
                if (pfd == null) throw new java.io.FileNotFoundException(media);
                try {
                    mp.setDataSource(pfd.getFileDescriptor());
                } finally {
                    pfd.close();
                }
                return;
            }
            if (media.startsWith("file://")) {
                String path = Uri.parse(media).getPath();
                if (path == null) throw new java.io.FileNotFoundException(media);
                mp.setDataSource(path);
                return;
            }
            java.io.File f = new java.io.File(media);
            if (f.exists()) {
                mp.setDataSource(f.getAbsolutePath());
                return;
            }
            android.content.res.AssetFileDescriptor afd =
                    context.getAssets()
                            .openFd(media.startsWith("assets/") ? media.substring(7) : media);
            try {
                mp.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            } finally {
                afd.close();
            }
        }

        private Rect sourceCropRect(float[] coords, int sourceWidth, int sourceHeight) {
            float u0 = Math.min(coords[0], coords[2]);
            float u1 = Math.max(coords[0], coords[2]);
            float v0 = Math.min(coords[5], coords[1]);
            float v1 = Math.max(coords[5], coords[1]);
            int left = Math.max(0, Math.min(sourceWidth - 1, (int) Math.floor(u0 * sourceWidth)));
            int right =
                    Math.max(left + 1, Math.min(sourceWidth, (int) Math.ceil(u1 * sourceWidth)));
            int top =
                    Math.max(
                            0,
                            Math.min(sourceHeight - 1, (int) Math.floor((1f - v1) * sourceHeight)));
            int bottom =
                    Math.max(
                            top + 1,
                            Math.min(sourceHeight, (int) Math.ceil((1f - v0) * sourceHeight)));
            return new Rect(left, top, right, bottom);
        }

        private Rect expandPhotoCropRect(Rect baseCrop, int sourceWidth, int sourceHeight) {
            int baseWidth = Math.max(1, baseCrop.width());
            int baseHeight = Math.max(1, baseCrop.height());
            float centerX = (baseCrop.left + baseCrop.right) * 0.5f;
            float centerY = (baseCrop.top + baseCrop.bottom) * 0.5f;

            // Expand symmetrically around the exact currently visible crop. This
            // preserves its centre while retaining off-screen source pixels for
            // gyro translation/rotation. If the source has no margin on an axis,
            // that axis safely remains at 1x rather than inventing image content.
            float scaleX =
                    Math.min(
                            PHOTO_TEXTURE_OVERSCAN_FACTOR,
                            Math.min(
                                    (2f * centerX) / baseWidth,
                                    (2f * (sourceWidth - centerX)) / baseWidth));
            float scaleY =
                    Math.min(
                            PHOTO_TEXTURE_OVERSCAN_FACTOR,
                            Math.min(
                                    (2f * centerY) / baseHeight,
                                    (2f * (sourceHeight - centerY)) / baseHeight));
            scaleX = Math.max(1f, scaleX);
            scaleY = Math.max(1f, scaleY);

            int expandedWidth =
                    Math.max(baseWidth, Math.min(sourceWidth, Math.round(baseWidth * scaleX)));
            int expandedHeight =
                    Math.max(baseHeight, Math.min(sourceHeight, Math.round(baseHeight * scaleY)));
            int left = Math.round(centerX - expandedWidth * 0.5f);
            int top = Math.round(centerY - expandedHeight * 0.5f);
            left = Math.max(0, Math.min(sourceWidth - expandedWidth, left));
            top = Math.max(0, Math.min(sourceHeight - expandedHeight, top));
            return new Rect(left, top, left + expandedWidth, top + expandedHeight);
        }

        private int[] photoWorkingTextureTarget(
                float resolutionFactor,
                float overscanScaleX,
                float overscanScaleY,
                int requestedSurfaceWidth,
                int requestedSurfaceHeight,
                int orientation) {
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int surfaceWidth =
                    requestedSurfaceWidth > 1
                            ? requestedSurfaceWidth
                            : Math.max(1, metrics.widthPixels);
            int surfaceHeight =
                    requestedSurfaceHeight > 1
                            ? requestedSurfaceHeight
                            : Math.max(1, metrics.heightPixels);
            boolean quarterTurn = orientation == 90 || orientation == 270;
            float targetWidth =
                    (quarterTurn ? surfaceHeight : surfaceWidth)
                            * resolutionFactor
                            * overscanScaleX;
            float targetHeight =
                    (quarterTurn ? surfaceWidth : surfaceHeight)
                            * resolutionFactor
                            * overscanScaleY;
            return new int[] {
                Math.max(1, Math.round(targetWidth)), Math.max(1, Math.round(targetHeight))
            };
        }

        private Bitmap fitPhotoWorkingTexture(Bitmap bitmap, int targetWidth, int targetHeight) {
            if (bitmap == null) return null;
            float scale =
                    Math.min(
                            1f,
                            Math.min(
                                    targetWidth / (float) Math.max(1, bitmap.getWidth()),
                                    targetHeight / (float) Math.max(1, bitmap.getHeight())));
            if (scale >= 0.9999f) return bitmap;
            int scaledWidth = Math.max(1, Math.round(bitmap.getWidth() * scale));
            int scaledHeight = Math.max(1, Math.round(bitmap.getHeight() * scale));
            Bitmap scaled = Bitmap.createScaledBitmap(bitmap, scaledWidth, scaledHeight, true);
            if (scaled != bitmap) bitmap.recycle();
            return scaled;
        }

        private boolean hasMeaningfulGyroDelta(
                float roll, float pitch, float depth, float rotation) {
            return Math.abs(roll - lastRenderedGyroRoll) >= PHOTO_MOTION_WAKE_THRESHOLD_DEG
                    || Math.abs(pitch - lastRenderedGyroPitch) >= PHOTO_MOTION_WAKE_THRESHOLD_DEG
                    || Math.abs(depth - lastRenderedGyroDepth) >= PHOTO_MOTION_WAKE_THRESHOLD_DEG
                    || Math.abs(rotation - lastRenderedGyroRotation)
                            >= PHOTO_MOTION_WAKE_THRESHOLD_DEG;
        }

        void requestMotionFrameIfNeeded(float roll, float pitch, float depth, float rotation) {
            long nowElapsedMs = System.nanoTime() / 1_000_000L;
            if (motionWakeDiagnosticsWindowStartMs <= 0L) {
                motionWakeDiagnosticsWindowStartMs = nowElapsedMs;
            }
            motionWakeRequestCount++;
            float deltaDeg = 0f;
            float rollDelta = Math.abs(roll - lastRenderedGyroRoll);
            float pitchDelta = Math.abs(pitch - lastRenderedGyroPitch);
            float depthDelta = Math.abs(depth - lastRenderedGyroDepth);
            float rotationDelta = Math.abs(rotation - lastRenderedGyroRotation);
            if (Float.isFinite(rollDelta)) deltaDeg = Math.max(deltaDeg, rollDelta);
            if (Float.isFinite(pitchDelta)) deltaDeg = Math.max(deltaDeg, pitchDelta);
            if (Float.isFinite(depthDelta)) deltaDeg = Math.max(deltaDeg, depthDelta);
            if (Float.isFinite(rotationDelta)) deltaDeg = Math.max(deltaDeg, rotationDelta);
            boolean meaningfulDelta = hasMeaningfulGyroDelta(roll, pitch, depth, rotation);
            motionWakeLastDeltaDeg = deltaDeg;

            if (video) {
                motionWakeBlockedVideoCount++;
                motionWakeLastDecision = "video";
            } else if (!visible) {
                motionWakeBlockedVisibilityCount++;
                motionWakeLastDecision = "not_visible";
            } else if (!surfaceReady) {
                motionWakeBlockedSurfaceCount++;
                motionWakeLastDecision = "surface_not_ready";
            } else if (playbackPaused) {
                motionWakeBlockedPausedCount++;
                motionWakeLastDecision = "playback_paused";
            } else if (!gyroEnabled) {
                motionWakeBlockedGyroCount++;
                motionWakeLastDecision = "gyro_disabled";
            } else if (currentUri == null) {
                motionWakeBlockedMediaCount++;
                motionWakeLastDecision = "media_not_ready";
            } else if (!meaningfulDelta) {
                motionWakeBelowThresholdCount++;
                motionWakeLastDecision = "below_threshold";
            } else {
                synchronized (wakeLock) {
                    // Wake once for a new motion burst, not for every sensor sample
                    // while a frame is already pending or being paced.
                    if (!motionUpdatePending) {
                        motionUpdatePending = true;
                        wakeLock.notifyAll();
                        motionWakeQueuedCount++;
                        motionWakeLastDecision = "queued";
                    } else {
                        motionWakeCoalescedCount++;
                        motionWakeLastDecision = "already_pending";
                    }
                }
            }

            if (nowElapsedMs - motionWakeDiagnosticsWindowStartMs
                    >= MOTION_WAKE_DIAGNOSTICS_INTERVAL_MS) {
                WallpaperBugRecorder.recordRuntimeSync(
                        context,
                        "g_mw",
                        "w="
                                + (nowElapsedMs - motionWakeDiagnosticsWindowStartMs)
                                + ", req="
                                + motionWakeRequestCount
                                + ", q="
                                + motionWakeQueuedCount
                                + ", pn="
                                + motionWakeCoalescedCount
                                + ", bt="
                                + motionWakeBelowThresholdCount
                                + ", bv="
                                + motionWakeBlockedVideoCount
                                + ", bvis="
                                + motionWakeBlockedVisibilityCount
                                + ", bsf="
                                + motionWakeBlockedSurfaceCount
                                + ", bp="
                                + motionWakeBlockedPausedCount
                                + ", bg="
                                + motionWakeBlockedGyroCount
                                + ", bm="
                                + motionWakeBlockedMediaCount
                                + ", d="
                                + motionWakeLastDeltaDeg
                                + ", th="
                                + PHOTO_MOTION_WAKE_THRESHOLD_DEG
                                + ", dec="
                                + motionWakeLastDecision);
                motionWakeDiagnosticsWindowStartMs = nowElapsedMs;
                motionWakeRequestCount = 0;
                motionWakeQueuedCount = 0;
                motionWakeCoalescedCount = 0;
                motionWakeBelowThresholdCount = 0;
                motionWakeBlockedVideoCount = 0;
                motionWakeBlockedVisibilityCount = 0;
                motionWakeBlockedSurfaceCount = 0;
                motionWakeBlockedPausedCount = 0;
                motionWakeBlockedGyroCount = 0;
                motionWakeBlockedMediaCount = 0;
            }
        }

        private PhotoTextureResult decodePhotoWorkingTexture(
                String media,
                float resolutionFactor,
                int requestedSurfaceWidth,
                int requestedSurfaceHeight,
                int orientation)
                throws Exception {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            try (InputStream boundsInput = openInputStream(media)) {
                BitmapFactory.decodeStream(boundsInput, null, bounds);
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
                throw new IllegalArgumentException("Unsupported or corrupt image");
            }
            android.util.DisplayMetrics metrics = context.getResources().getDisplayMetrics();
            int surfaceWidth =
                    requestedSurfaceWidth > 1
                            ? requestedSurfaceWidth
                            : Math.max(1, metrics.widthPixels);
            int surfaceHeight =
                    requestedSurfaceHeight > 1
                            ? requestedSurfaceHeight
                            : Math.max(1, metrics.heightPixels);
            float[] cropCoords =
                    sourceCropTexCoords(
                            bounds.outWidth,
                            bounds.outHeight,
                            media,
                            surfaceWidth,
                            surfaceHeight,
                            orientation);
            Rect visibleCrop = sourceCropRect(cropCoords, bounds.outWidth, bounds.outHeight);
            Rect crop = expandPhotoCropRect(visibleCrop, bounds.outWidth, bounds.outHeight);
            float overscanScaleX = crop.width() / (float) Math.max(1, visibleCrop.width());
            float overscanScaleY = crop.height() / (float) Math.max(1, visibleCrop.height());
            int[] target =
                    photoWorkingTextureTarget(
                            resolutionFactor,
                            overscanScaleX,
                            overscanScaleY,
                            surfaceWidth,
                            surfaceHeight,
                            orientation);
            int sampleSize = 1;
            while (sampleSize < (1 << 20)
                    && crop.width() / (sampleSize * 2f) >= target[0] * 0.80f
                    && crop.height() / (sampleSize * 2f) >= target[1] * 0.80f) {
                sampleSize *= 2;
            }
            Bitmap working = null;
            BitmapRegionDecoder decoder = null;
            try (InputStream regionInput = openInputStream(media)) {
                decoder = BitmapRegionDecoder.newInstance(regionInput, false);
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sampleSize;
                options.inPreferredConfig = Bitmap.Config.ARGB_8888;
                working = decoder.decodeRegion(crop, options);
            } catch (Exception regionError) {
                WallpaperBugRecorder.record(
                        context,
                        "PHOTO_REGION_DECODE_FALLBACK",
                        regionError.getClass().getSimpleName()
                                + ":"
                                + String.valueOf(regionError.getMessage()));
            } finally {
                if (decoder != null) {
                    try {
                        decoder.recycle();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (working == null) {
                BitmapFactory.Options fallbackOptions = new BitmapFactory.Options();
                fallbackOptions.inSampleSize = sampleSize;
                fallbackOptions.inPreferredConfig = Bitmap.Config.ARGB_8888;
                Bitmap full;
                try (InputStream fullInput = openInputStream(media)) {
                    full = BitmapFactory.decodeStream(fullInput, null, fallbackOptions);
                }
                if (full == null) throw new IllegalStateException("bitmap decode returned null");
                int fallbackWidth = full.getWidth(), fallbackHeight = full.getHeight();
                float fallbackScaleX = fallbackWidth / (float) Math.max(1, bounds.outWidth);
                float fallbackScaleY = fallbackHeight / (float) Math.max(1, bounds.outHeight);
                Rect safeCrop =
                        new Rect(
                                Math.max(
                                        0,
                                        Math.min(
                                                full.getWidth() - 1,
                                                (int) Math.floor(crop.left * fallbackScaleX))),
                                Math.max(
                                        0,
                                        Math.min(
                                                full.getHeight() - 1,
                                                (int) Math.floor(crop.top * fallbackScaleY))),
                                Math.max(
                                        1,
                                        Math.min(
                                                full.getWidth(),
                                                (int) Math.ceil(crop.right * fallbackScaleX))),
                                Math.max(
                                        1,
                                        Math.min(
                                                full.getHeight(),
                                                (int) Math.ceil(crop.bottom * fallbackScaleY))));
                if (safeCrop.right <= safeCrop.left || safeCrop.bottom <= safeCrop.top) {
                    safeCrop.set(0, 0, full.getWidth(), full.getHeight());
                }
                working =
                        Bitmap.createBitmap(
                                full,
                                safeCrop.left,
                                safeCrop.top,
                                safeCrop.width(),
                                safeCrop.height());
                if (working != full) full.recycle();
                WallpaperBugRecorder.record(
                        context,
                        "PHOTO_FULL_DECODE_FALLBACK",
                        "source="
                                + bounds.outWidth
                                + "x"
                                + bounds.outHeight
                                + ",decoded="
                                + fallbackWidth
                                + "x"
                                + fallbackHeight
                                + ",crop="
                                + safeCrop.width()
                                + "x"
                                + safeCrop.height()
                                + ",sample="
                                + sampleSize);
            }
            working = fitPhotoWorkingTexture(working, target[0], target[1]);
            return new PhotoTextureResult(
                    working,
                    bounds.outWidth,
                    bounds.outHeight,
                    visibleCrop.width(),
                    visibleCrop.height(),
                    crop.width(),
                    crop.height(),
                    overscanScaleX,
                    overscanScaleY,
                    sampleSize,
                    surfaceWidth,
                    surfaceHeight);
        }

        private void installPhotoTextureResult(
                PhotoTextureResult result, int tier, boolean replaceTexture) {
            if (result == null || result.bitmap == null)
                throw new IllegalArgumentException("empty photo texture");
            int targetTexture = texture2d;
            boolean createdReplacement = false;
            try {
                if (replaceTexture) {
                    targetTexture = createTexture(GLES20.GL_TEXTURE_2D);
                    createdReplacement = targetTexture != 0;
                }
                if (targetTexture == 0)
                    throw new IllegalStateException("photo texture unavailable");
                while (GLES20.glGetError() != GLES20.GL_NO_ERROR) {
                    /* discard prior GL errors */
                }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, targetTexture);
                android.opengl.GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, result.bitmap, 0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                int glError = GLES20.glGetError();
                if (glError != GLES20.GL_NO_ERROR) {
                    throw new IllegalStateException(
                            "photo texture upload GL error 0x" + Integer.toHexString(glError));
                }
                if (replaceTexture) {
                    int previousTexture = texture2d;
                    texture2d = targetTexture;
                    createdReplacement = false;
                    if (previousTexture != 0)
                        GLES20.glDeleteTextures(1, new int[] {previousTexture}, 0);
                }
                imageWidth = result.bitmap.getWidth();
                imageHeight = result.bitmap.getHeight();
                imageTextureOverscanScaleX = result.overscanScaleX;
                imageTextureOverscanScaleY = result.overscanScaleY;
                imageTextureIsPreCropped = true;
                photoTextureTier = tier;
                photoTextureAdaptationBlocked = false;
                photoFramePending = true;
                pausedImageFramePending = playbackPaused;
                motionUpdatePending = false;
                WallpaperBugRecorder.record(
                        context,
                        "PHOTO_WORKING_TEXTURE_READY",
                        "source="
                                + result.sourceWidth
                                + "x"
                                + result.sourceHeight
                                + ",visibleCrop="
                                + result.visibleCropWidth
                                + "x"
                                + result.visibleCropHeight
                                + ",workingCrop="
                                + result.workingCropWidth
                                + "x"
                                + result.workingCropHeight
                                + ",working="
                                + imageWidth
                                + "x"
                                + imageHeight
                                + ",qualityFactor="
                                + PHOTO_TEXTURE_QUALITY_FACTORS[tier]
                                + ",overscan="
                                + imageTextureOverscanScaleX
                                + "x"
                                + imageTextureOverscanScaleY
                                + ",surface="
                                + result.surfaceWidth
                                + "x"
                                + result.surfaceHeight
                                + ",sample="
                                + result.sampleSize);
            } catch (RuntimeException error) {
                if (createdReplacement && targetTexture != 0)
                    GLES20.glDeleteTextures(1, new int[] {targetTexture}, 0);
                try {
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
                } catch (Throwable ignored) {
                }
                throw error;
            } finally {
                result.recycle();
            }
        }

        private boolean requestPhotoTextureTier(int nextTier, String reason) {
            if (video
                    || currentUri == null
                    || photoTextureAdaptationBlocked
                    || (nextTier > photoTextureTier && photoTextureQualityIncreaseBlocked)
                    || photoTextureRebuildPending
                    || nextTier < 0
                    || nextTier >= PHOTO_TEXTURE_QUALITY_FACTORS.length
                    || nextTier == photoTextureTier) {
                return false;
            }
            final String media = currentUri.toString();
            final int generation = ++photoTextureRequestGeneration;
            final int requestedTier = nextTier;
            final float factor = PHOTO_TEXTURE_QUALITY_FACTORS[requestedTier];
            final int requestedWidth = width,
                    requestedHeight = height,
                    orientation = mediaOrientation;
            photoTextureRebuildPending = true;
            photoTexturePendingTier = requestedTier;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_PHOTO_RESOLUTION_DECODE_START",
                    "reason="
                            + reason
                            + ",fromTier="
                            + photoTextureTier
                            + ",toTier="
                            + requestedTier
                            + ",factor="
                            + factor);
            Thread worker =
                    new Thread(
                            () -> {
                                PhotoTextureResult decoded = null;
                                Throwable failure = null;
                                try {
                                    decoded =
                                            decodePhotoWorkingTexture(
                                                    media,
                                                    factor,
                                                    requestedWidth,
                                                    requestedHeight,
                                                    orientation);
                                } catch (Throwable error) {
                                    failure = error;
                                }
                                final PhotoTextureResult result = decoded;
                                final Throwable decodeFailure = failure;
                                if (!running) {
                                    if (result != null) result.recycle();
                                    return;
                                }
                                post(
                                        RenderThread.this,
                                        () -> {
                                            if (generation != photoTextureRequestGeneration
                                                    || currentUri == null
                                                    || !media.equals(currentUri.toString())
                                                    || video) {
                                                if (result != null) result.recycle();
                                                return;
                                            }
                                            if (orientation != mediaOrientation
                                                    || requestedWidth != width
                                                    || requestedHeight != height) {
                                                photoTextureRebuildPending = false;
                                                photoTexturePendingTier = -1;
                                                slowSinceNs = 0L;
                                                lastThrottleChangeNs = System.nanoTime();
                                                if (result != null) result.recycle();
                                                WallpaperBugRecorder.record(
                                                        context,
                                                        "GPU_PHOTO_RESOLUTION_STALE_DECODE",
                                                        "tier="
                                                                + requestedTier
                                                                + ",reason=surface_or_orientation_changed");
                                                return;
                                            }
                                            photoTextureRebuildPending = false;
                                            photoTexturePendingTier = -1;
                                            if (decodeFailure != null || result == null) {
                                                if (requestedTier > photoTextureTier)
                                                    photoTextureQualityIncreaseBlocked = true;
                                                else photoTextureAdaptationBlocked = true;
                                                slowSinceNs = 0L;
                                                lastThrottleChangeNs = System.nanoTime();
                                                WallpaperBugRecorder.record(
                                                        context,
                                                        "GPU_PHOTO_RESOLUTION_DECODE_FAILED",
                                                        "tier="
                                                                + requestedTier
                                                                + ",error="
                                                                + (decodeFailure == null
                                                                        ? "null_result"
                                                                        : decodeFailure
                                                                                        .getClass()
                                                                                        .getSimpleName()
                                                                                + ":"
                                                                                + String.valueOf(
                                                                                        decodeFailure
                                                                                                .getMessage())));
                                                if (result != null) result.recycle();
                                                return;
                                            }
                                            try {
                                                installPhotoTextureResult(
                                                        result, requestedTier, true);
                                                timingSampleCount = 0;
                                                slowSinceNs = 0L;
                                                stableSinceNs = 0L;
                                                lastFrameEndNs = 0L;
                                                nextFrameDeadlineNs = 0L;
                                                lastThrottleChangeNs = System.nanoTime();
                                                WallpaperBugRecorder.record(
                                                        context,
                                                        "GPU_PHOTO_RESOLUTION_CHANGED",
                                                        "reason="
                                                                + reason
                                                                + ",tier="
                                                                + requestedTier
                                                                + ",factor="
                                                                + factor
                                                                + ",texture="
                                                                + imageWidth
                                                                + "x"
                                                                + imageHeight);
                                                notifyProtectionState();
                                            } catch (RuntimeException error) {
                                                if (requestedTier > photoTextureTier)
                                                    photoTextureQualityIncreaseBlocked = true;
                                                else photoTextureAdaptationBlocked = true;
                                                WallpaperBugRecorder.record(
                                                        context,
                                                        "GPU_PHOTO_RESOLUTION_UPLOAD_FAILED",
                                                        "tier="
                                                                + requestedTier
                                                                + ",error="
                                                                + error.getClass().getSimpleName()
                                                                + ":"
                                                                + String.valueOf(
                                                                        error.getMessage()));
                                            }
                                        });
                            },
                            "LiveStagix-PhotoTextureDecode");
            worker.setDaemon(true);
            worker.start();
            return true;
        }

        void setupImage(String media) {
            frameGenerationEnabled = false;
            frameGenerationActive = false;
            releaseFrameGenerationBuffers();
            smartUpscaleEnabled = false;
            smartUpscaleWorking = false;
            releaseSmartUpscaleInputBuffer();
            video = false;
            photoTextureTier = PHOTO_TEXTURE_INITIAL_TIER;
            photoTextureAdaptationBlocked = false;
            photoTextureQualityIncreaseBlocked = false;
            imageTextureIsPreCropped = false;
            imageTextureOverscanScaleX = 1f;
            imageTextureOverscanScaleY = 1f;
            photoFramePending = false;
            lastVideoTimestamp = -1L;
            lastSourceTimestampNs = -1L;
            videoSourceFps = 0f;
            sourceFpsSamples = 0;
            videoWidth = 1;
            videoHeight = 1;
            Matrix.setIdentityM(videoTexMatrix, 0);
            try {
                PhotoTextureResult result =
                        decodePhotoWorkingTexture(
                                media,
                                PHOTO_TEXTURE_QUALITY_FACTORS[photoTextureTier],
                                width,
                                height,
                                mediaOrientation);
                installPhotoTextureResult(result, photoTextureTier, false);
                if (playbackPaused) pausedImageFramePending = true;
                refreshSmartUpscaleState();
            } catch (Exception e) {
                imageTextureIsPreCropped = false;
                imageTextureOverscanScaleX = 1f;
                imageTextureOverscanScaleY = 1f;
                String reason =
                        "image_load_failed:"
                                + e.getClass().getName()
                                + ":"
                                + String.valueOf(e.getMessage());
                WallpaperBugRecorder.record(context, "GPU_IMAGE_LOAD_FAILED", reason);
                notifyRendererFailure(reason);
                currentUri = null;
            }
        }

        void createVideoSurfaceTexture() {
            if (videoTexture != null) {
                try {
                    videoTexture.setOnFrameAvailableListener(null);
                } catch (Exception ignored) {
                }
                try {
                    videoTexture.release();
                } catch (Exception ignored) {
                }
                videoTexture = null;
            }
            if (mediaSurface != null) {
                try {
                    mediaSurface.release();
                } catch (Exception ignored) {
                }
                mediaSurface = null;
            }
            frameAvailable = false;
            videoTexture = new SurfaceTexture(textureOes);
            videoTexture.setOnFrameAvailableListener(
                    st -> {
                        frameAvailable = true;
                        synchronized (wakeLock) {
                            wakeLock.notifyAll();
                        }
                    });
            mediaSurface = new Surface(videoTexture);
        }

        void setupVideo(String media) {
            imageTextureIsPreCropped = false;
            imageTextureOverscanScaleX = 1f;
            imageTextureOverscanScaleY = 1f;
            photoFramePending = false;
            motionUpdatePending = false;
            frameGenerationActive = false;
            interpolationShownForCurrent = false;
            smartUpscaleEnabled = smartUpscaleUserEnabled;
            smartUpscaleStrainBypassed = false;
            smartUpscaleStrainDegraded = false;
            smartUpscaleWorking = false;
            videoIntermediateResolutionTier = 0;
            video = true;
            // Restore the requested FI state after setupImage() temporarily disables it.
            frameGenerationEnabled = GpuWallpaperRenderer.this.frameGenerationEnabledPreference;
            frameGenerationMix =
                    Math.max(
                            0f,
                            Math.min(
                                    1f,
                                    GpuWallpaperRenderer.this.frameGenerationStrengthPreference
                                            / 100f));
            interpolationPending = false;
            interpolationPreviousReady = false;
            lastVideoTimestamp = -1L;
            lastSourceTimestampNs = -1L;
            videoSourceFps = 0f;
            sourceFpsSamples = 0;
            isVideoPrepared = false;
            videoPlaybackFrameConfirmed = false;
            videoTransformDiagnosticsLogged = false;
            videoFreshFrameCount = 0;
            videoFirstFrameArrivalNs = 0L;
            videoPlaybackExpectedSinceNs = 0L;
            lastVideoFrameArrivalNs = 0L;
            videoStallRecoveryAttempts = 0;
            videoStallFailureReported = false;
            videoWidth = 1;
            videoHeight = 1;
            Matrix.setIdentityM(videoTexMatrix, 0);
            createVideoSurfaceTexture();
            player = new MediaPlayer();

            try {
                setPlayerDataSource(player, media);
                player.setLooping(true);
                player.setSurface(mediaSurface);
                player.setOnVideoSizeChangedListener(
                        (mp, width, height) -> {
                            if (width > 0 && height > 0) {
                                post(
                                        RenderThread.this,
                                        () -> {
                                            if (player != mp) return;
                                            videoWidth = width;
                                            videoHeight = height;
                                            WallpaperBugRecorder.record(
                                                    context,
                                                    "VIDEO_SIZE_CHANGED",
                                                    "width=" + width + ",height=" + height);
                                        });
                            }
                        });
                player.setOnPreparedListener(
                        mp -> {
                            post(RenderThread.this, () -> onVideoPrepared(mp));
                        });
                player.setOnErrorListener(
                        (mp, what, extra) -> {
                            String reason = "video_error:what=" + what + ",extra=" + extra;
                            WallpaperBugRecorder.record(context, "GPU_VIDEO_ERROR", reason);
                            if (what == -38) {
                                post(
                                        RenderThread.this,
                                        () -> {
                                            if (player == mp) {
                                                recoverMediaPlayer("error_-38");
                                            }
                                        });
                                return true;
                            }
                            notifyRendererFailure(reason);
                            return true;
                        });
                player.prepareAsync();
                WallpaperBugRecorder.record(context, "VIDEO_PREPARE_ASYNC", "");
            } catch (Exception e) {
                String reason =
                        "video_setup_failed:"
                                + e.getClass().getName()
                                + ":"
                                + String.valueOf(e.getMessage());
                WallpaperBugRecorder.record(context, "GPU_VIDEO_SETUP_FAILED", reason);
                notifyRendererFailure(reason);
                releaseMedia();
            }
        }

        private void notifyStandaloneEffectRecovery(
                String effectId,
                String reason,
                boolean mediaFallbackAvailable,
                boolean fallbackMediaReloaded,
                String retainedEffectId) {
            StandaloneEffectRecoveryListener listener = standaloneEffectRecoveryListener;
            if (listener != null) {
                try {
                    listener.onStandaloneEffectRecovery(
                            effectId == null ? "" : effectId,
                            reason == null ? "effect_recovery" : reason,
                            mediaFallbackAvailable,
                            fallbackMediaReloaded,
                            retainedEffectId == null ? "" : retainedEffectId);
                } catch (Throwable error) {
                    WallpaperBugRecorder.record(
                            context, "GPU_EFFECT_RECOVERY_CALLBACK_FAILED", error);
                }
            }
        }

        private void notifyRendererFailure(String reason) {
            FailureListener listener = failureListener;
            if (listener != null) {
                listener.onRendererFailure(reason == null ? "renderer_failure" : reason);
            }
        }

        void renderTestFrame() {
            if (!surfaceReady) return;
            GLES20.glViewport(0, 0, Math.max(1, width), Math.max(1, height));
            GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            boolean ok = EGL14.eglSwapBuffers(display, eglSurface);
            WallpaperBugRecorder.record(
                    context,
                    ok ? "GPU_TEST_FRAME_DRAWN" : "GPU_TEST_FRAME_SWAP_FAILED",
                    ok
                            ? "solid_frame=true"
                            : "eglError=0x" + Integer.toHexString(EGL14.eglGetError()));
        }

        private void drawAmbientBlurBackdrop(float[] coords) {
            int program = video ? programBlurOes : programBlur2d;
            int target = video ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D;
            int texture = video ? textureOes : texture2d;

            boolean useOesProgram = video;
            int position = useOesProgram ? programBlurOesPosition : programBlur2dPosition;
            int texCoord = useOesProgram ? programBlurOesTexCoord : programBlur2dTexCoord;
            int textureUniform = useOesProgram ? programBlurOesTexture : programBlur2dTexture;
            int texMatrixUniform = useOesProgram ? programBlurOesTexMatrix : programBlur2dTexMatrix;
            int transformUniform = useOesProgram ? programBlurOesTransform : programBlur2dTransform;
            int blurStepUniform = useOesProgram ? programBlurOesStep : programBlur2dStep;

            GLES20.glUseProgram(program);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, texture);
            GLES20.glUniform1i(textureUniform, 0);

            GLES20.glUniformMatrix4fv(
                    texMatrixUniform, 1, false, video ? videoTexMatrix : identity, 0);

            float[] backdropTransform = identity.clone();
            if (mediaOrientation != 0) {
                Matrix.rotateM(backdropTransform, 0, mediaOrientation, 0f, 0f, 1f);
            }
            GLES20.glUniformMatrix4fv(transformUniform, 1, false, backdropTransform, 0);

            int sourceWidth = video ? videoWidth : imageWidth;
            int sourceHeight = video ? videoHeight : imageHeight;
            float stepX = 7f / Math.max(1, sourceWidth);
            float stepY = 7f / Math.max(1, sourceHeight);
            GLES20.glUniform2f(blurStepUniform, stepX, stepY);

            uploadDynamicTexCoords(coords);
            bindGeometryAttributes(position, texCoord, dynamicTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(position);
            GLES20.glDisableVertexAttribArray(texCoord);
            GLES20.glBindTexture(target, 0);
        }

        private void drawNormalWallpaperFrame(
                boolean useGeneratedFrame, boolean useFiPreviousSourceFrame, float[] transform) {
            // Keep original and generated frames on the same FI working resolution.
            boolean useFiSourceFrame =
                    !useGeneratedFrame
                            && video
                            && frameGenerationEnabled
                            && interpolationPreviousReady
                            && frameBlendCurrentTexture != 0
                            && frameBlendWidth > 0
                            && frameBlendHeight > 0;
            boolean useBufferedVideoFrame = useGeneratedFrame || useFiSourceFrame;
            int selectedFiSourceTexture =
                    useFiPreviousSourceFrame ? frameBlendPrevTexture : frameBlendCurrentTexture;
            activeProgram =
                    useGeneratedFrame
                            ? programFrameBlend
                            : (useFiSourceFrame ? program2d : (video ? programOes : program2d));
            int positionLocation =
                    useGeneratedFrame
                            ? frameBlendPosition
                            : (useFiSourceFrame || !video ? program2dPosition : programOesPosition);
            int texCoordLocation =
                    useGeneratedFrame
                            ? frameBlendTexCoord
                            : (useFiSourceFrame || !video ? program2dTexCoord : programOesTexCoord);
            int textureUniform =
                    useGeneratedFrame
                            ? -1
                            : (useFiSourceFrame || !video ? program2dTexture : programOesTexture);
            int texMatrixUniform =
                    useGeneratedFrame
                            ? -1
                            : (useFiSourceFrame || !video
                                    ? program2dTexMatrix
                                    : programOesTexMatrix);
            int transformUniform =
                    useGeneratedFrame
                            ? -1
                            : (useFiSourceFrame || !video
                                    ? program2dTransform
                                    : programOesTransform);
            GLES20.glUseProgram(activeProgram);

            int target =
                    useBufferedVideoFrame
                            ? GLES20.GL_TEXTURE_2D
                            : (video ? GLES11Ext.GL_TEXTURE_EXTERNAL_OES : GLES20.GL_TEXTURE_2D);
            int texture =
                    useBufferedVideoFrame
                            ? (useGeneratedFrame
                                    ? frameBlendCurrentTexture
                                    : selectedFiSourceTexture)
                            : (video ? textureOes : texture2d);

            float[] crop = video ? canonicalVideoTexCoords : cropTexCoords();

            if (useGeneratedFrame) {
                int ps = frameBlendPrev;
                int cs = frameBlendCurrent;
                int strength = frameBlendStrength;
                int motionField = frameBlendMotionField;
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBlendPrevTexture);
                GLES20.glUniform1i(ps, 0);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, frameBlendCurrentTexture);
                GLES20.glUniform1i(cs, 1);
                GLES20.glUniform1f(strength, frameGenerationMix);
                applyFrameBlendMotionUniforms();
                GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, motionFieldTexture);
                GLES20.glUniform1i(frameBlendMotionField, 2);
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            } else {
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                GLES20.glBindTexture(target, texture);
                GLES20.glUniform1i(textureUniform, 0);
                GLES20.glUniformMatrix4fv(
                        texMatrixUniform,
                        1,
                        false,
                        useFiSourceFrame ? identity : (video ? videoTexMatrix : identity),
                        0);
                GLES20.glUniformMatrix4fv(transformUniform, 1, false, transform, 0);
            }

            uploadDynamicTexCoords(crop);
            bindGeometryAttributes(positionLocation, texCoordLocation, dynamicTexCoordVbo);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
            GLES20.glDisableVertexAttribArray(positionLocation);
            GLES20.glDisableVertexAttribArray(texCoordLocation);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE2);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
            GLES20.glBindTexture(target, 0);
        }

        private void updateVideoPlaybackWatchdog(long nowNs) {
            // Only monitor when Android should currently be showing the video.
            // Visibility and playbackPaused already include LiveStagix Auto Pause.
            if (!video
                    || !isVideoPrepared
                    || !surfaceReady
                    || !visible
                    || playbackPaused
                    || player == null
                    || videoStallFailureReported) {
                videoPlaybackExpectedSinceNs = 0L;
                if (!videoPlaybackFrameConfirmed) {
                    lastVideoFrameArrivalNs = 0L;
                }
                return;
            }

            if (videoPlaybackExpectedSinceNs <= 0L) {
                videoPlaybackExpectedSinceNs = nowNs;
            }

            long referenceNs =
                    lastVideoFrameArrivalNs > 0L
                            ? lastVideoFrameArrivalNs
                            : videoPlaybackExpectedSinceNs;
            long timeoutNs = lastVideoFrameArrivalNs > 0L ? 3_500_000_000L : 7_000_000_000L;
            if (nowNs - referenceNs < timeoutNs) return;

            String reason = "video_frame_stalled:no_surface_texture_timestamp_advance";
            if (videoStallRecoveryAttempts < 1) {
                videoStallRecoveryAttempts++;
                WallpaperBugRecorder.record(
                        context,
                        "GPU_VIDEO_FRAME_STALL_RECOVERY",
                        "attempt="
                                + videoStallRecoveryAttempts
                                + ",lastFrameAgeMs="
                                + ((nowNs - referenceNs) / 1_000_000L)
                                + ",playerPlaying="
                                + safePlayerIsPlaying());
                videoPlaybackExpectedSinceNs = 0L;
                lastVideoFrameArrivalNs = 0L;
                if (!videoPlaybackFrameConfirmed) {
                    videoFreshFrameCount = 0;
                    videoFirstFrameArrivalNs = 0L;
                }
                recoverMediaPlayer("frame_stall");
                return;
            }

            videoStallFailureReported = true;
            WallpaperBugRecorder.record(
                    context,
                    "GPU_VIDEO_FRAME_STALL_FAILURE",
                    "recoveryAttempts="
                            + videoStallRecoveryAttempts
                            + ",lastFrameAgeMs="
                            + ((nowNs - referenceNs) / 1_000_000L)
                            + ",playerPlaying="
                            + safePlayerIsPlaying());
            notifyRendererFailure(reason + ",recoveryAttempts=" + videoStallRecoveryAttempts);
        }

        private boolean safePlayerIsPlaying() {
            try {
                return player != null && player.isPlaying();
            } catch (Throwable ignored) {
                return false;
            }
        }

        void renderFrame() {
            if (!eglReady || !surfaceReady || eglSurface == EGL14.EGL_NO_SURFACE) return;

            if (isStandaloneEffectRendering()) {
                renderStandaloneEffectFrame();
                return;
            }

            if (video && programOes == 0 && !ensureProgramOes()) return;

            if (video && videoTexture != null && isVideoPrepared) {
                boolean shouldUpdate;
                synchronized (wakeLock) {
                    shouldUpdate = frameAvailable;
                    if (shouldUpdate) {
                        frameAvailable = false;
                    }
                }
                if (shouldUpdate) {
                    try {
                        videoTexture.updateTexImage();
                        videoTexture.getTransformMatrix(videoTexMatrix);
                        long ts = videoTexture.getTimestamp();
                        if (!videoTransformDiagnosticsLogged) {
                            videoTransformDiagnosticsLogged = true;
                            WallpaperBugRecorder.record(
                                    context,
                                    "VIDEO_FRAME_TRANSFORM",
                                    "output="
                                            + videoWidth
                                            + "x"
                                            + videoHeight
                                            + ",manualOrientation="
                                            + mediaOrientation
                                            + ",surfaceTextureMatrix="
                                            + java.util.Arrays.toString(videoTexMatrix)
                                            + ",timestampNs="
                                            + ts);
                        }
                        if (ts != lastVideoTimestamp) {
                            if (lastSourceTimestampNs >= 0L) {
                                long sourceDeltaNs = ts - lastSourceTimestampNs;
                                if (sourceDeltaNs >= 8_000_000L && sourceDeltaNs <= 200_000_000L) {
                                    float instantaneousFps = 1_000_000_000f / sourceDeltaNs;
                                    if (videoSourceFps <= 0f) {
                                        videoSourceFps = instantaneousFps;
                                    } else {
                                        videoSourceFps =
                                                videoSourceFps * 0.75f + instantaneousFps * 0.25f;
                                    }
                                    sourceFpsSamples++;
                                    if (sourceFpsSamples == 1 || sourceFpsSamples % 10 == 0) {
                                        WallpaperBugRecorder.record(
                                                context,
                                                "VIDEO_SOURCE_FPS_ESTIMATED",
                                                "fps="
                                                        + String.format(
                                                                java.util.Locale.US,
                                                                "%.2f",
                                                                videoSourceFps)
                                                        + ",samples="
                                                        + sourceFpsSamples);
                                    }
                                }
                            }
                            lastSourceTimestampNs = ts;

                            long frameArrivalNs = System.nanoTime();
                            lastVideoFrameArrivalNs = frameArrivalNs;
                            if (!videoPlaybackFrameConfirmed
                                    && player != null
                                    && visible
                                    && !playbackPaused
                                    && safePlayerIsPlaying()) {
                                if (videoFreshFrameCount == 0) {
                                    videoFirstFrameArrivalNs = frameArrivalNs;
                                }
                                videoFreshFrameCount++;
                                if (videoFreshFrameCount >= 3
                                        && frameArrivalNs - videoFirstFrameArrivalNs
                                                >= 250_000_000L) {
                                    videoPlaybackFrameConfirmed = true;
                                    VideoFrameListener frameListener = videoFrameListener;
                                    if (frameListener != null) {
                                        try {
                                            frameListener.onVideoFrame(ts);
                                        } catch (Throwable listenerError) {
                                            WallpaperBugRecorder.record(
                                                    context,
                                                    "VIDEO_FIRST_FRAME_CALLBACK_FAILED",
                                                    listenerError.getClass().getName()
                                                            + ":"
                                                            + String.valueOf(
                                                                    listenerError.getMessage()));
                                        }
                                    }
                                    WallpaperBugRecorder.record(
                                            context,
                                            "VIDEO_PLAYBACK_CONFIRMED",
                                            "timestampNs="
                                                    + ts
                                                    + ",frames="
                                                    + videoFreshFrameCount
                                                    + ",elapsedMs="
                                                    + ((frameArrivalNs - videoFirstFrameArrivalNs)
                                                            / 1_000_000L));
                                }
                            }

                            fiTargetFps = calculateFiTargetFps();
                            boolean interpolateNow = fiTargetFps > 0;

                            if (interpolateNow) {
                                ensureFrameGenerationBuffers();
                                long sourceDeltaNs =
                                        lastVideoTimestamp >= 0L ? ts - lastVideoTimestamp : -1L;
                                boolean validSourcePair =
                                        sourceDeltaNs >= 8_000_000L
                                                && sourceDeltaNs <= 200_000_000L;
                                if (interpolationPreviousReady && !validSourcePair) {
                                    interpolationPreviousReady = false;
                                    interpolationPending = false;
                                    resetRegionalMotionField();
                                    WallpaperBugRecorder.record(
                                            context,
                                            "GPU_FI_PAIR_RESET",
                                            "reason=source_timestamp_discontinuity,deltaNs="
                                                    + sourceDeltaNs);
                                }
                                if (interpolationPreviousReady) {
                                    copyCurrentToPrevious();
                                }
                                long pairIntervalNs = validSourcePair ? sourceDeltaNs : 16_666_667L;
                                frameGenerationIntervalNs =
                                        Math.max(
                                                8_000_000L, Math.min(200_000_000L, pairIntervalNs));
                                frameGenerationStartNs = frameArrivalNs;
                                interpolationTargetNs =
                                        frameArrivalNs + (frameGenerationIntervalNs / 2L);
                                captureCurrentVideoFrame();
                                if (interpolationPreviousReady) {
                                    estimateRegionalMotion();
                                } else {
                                    resetRegionalMotionField();
                                }
                                promoteCurrentMotionSample();
                                // Keep FI available for valid source pairs. Motion confidence
                                // controls the final generated-frame contribution in the shader.
                                interpolationPending =
                                        interpolationPreviousReady && motionConfidence >= 0.30f;
                                interpolationShownForCurrent = false;
                                interpolationPreviousReady = true;
                            } else {
                                interpolationPending = false;
                                interpolationShownForCurrent = false;
                                interpolationTargetNs = 0L;
                                interpolationPreviousReady = false;
                                if (frameBlendFbo != 0) {
                                    releaseFrameGenerationBuffers();
                                }
                            }

                            lastVideoTimestamp = ts;
                        }
                    } catch (Exception e) {
                        synchronized (wakeLock) {
                            frameAvailable = true;
                        }
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_VIDEO_UPDATE_TEX_FAILED",
                                e.getClass().getName() + ":" + String.valueOf(e.getMessage()));
                    }
                }
            }

            updateVideoPlaybackWatchdog(System.nanoTime());
            GLES20.glViewport(0, 0, width, height);
            GLES20.glClearColor(0f, 0f, 0f, 1f);
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
            long nowRenderNs = System.nanoTime();
            boolean midpointReached =
                    interpolationTargetNs > 0L
                            && nowRenderNs >= interpolationTargetNs
                            && nowRenderNs
                                    < (frameGenerationStartNs
                                            + Math.max(8_000_000L, frameGenerationIntervalNs));
            boolean frameBlendRequested =
                    video
                            && frameGenerationEnabled
                            && interpolationPending
                            && !interpolationShownForCurrent
                            && frameBlendPrevTexture != 0
                            && frameBlendCurrentTexture != 0
                            && motionFieldTexture != 0
                            && motionConfidence >= 0.30f
                            && midpointReached;
            if (frameBlendRequested) {
                ensureProgramFrameBlend();
            }
            boolean useGeneratedFrame = frameBlendRequested && programFrameBlend != 0;
            // A source pair is ordered previous -> interpolated midpoint -> current.
            // Until its midpoint is due, hold the previous source frame instead of
            // showing the new source frame and then visually stepping backwards.
            boolean useFiPreviousSourceFrame =
                    video
                            && frameGenerationEnabled
                            && interpolationPending
                            && !interpolationShownForCurrent
                            && interpolationTargetNs > 0L
                            && nowRenderNs < interpolationTargetNs
                            && !useGeneratedFrame
                            && frameBlendPrevTexture != 0
                            && frameBlendWidth > 0
                            && frameBlendHeight > 0;
            float[] transform = motionTransform();
            float[] transformForFreeze = transform.clone();
            if (!video && imageTextureIsPreCropped) {
                // The texture includes off-screen source margin. Scale its plane
                // by the same factor so the original visible crop stays identical
                // at rest while gyro movement can draw into the retained margins.
                Matrix.scaleM(
                        transform, 0, imageTextureOverscanScaleX, imageTextureOverscanScaleY, 1f);
            }
            int sourceWidth = useGeneratedFrame ? frameBlendWidth : videoWidth;
            int sourceHeight = useGeneratedFrame ? frameBlendHeight : videoHeight;

            // FI uses a deliberately capped working buffer. When FI is enabled,
            // both real and generated frames must enter Smart Upscale at that
            // same stable resolution so the SU input FBO/texture is never
            // destroyed and reallocated when presentation alternates frames.
            int smartUpscaleInputFrameWidth = sourceWidth;
            int smartUpscaleInputFrameHeight = sourceHeight;
            if (frameGenerationEnabled && frameBlendWidth > 0 && frameBlendHeight > 0) {
                smartUpscaleInputFrameWidth = frameBlendWidth;
                smartUpscaleInputFrameHeight = frameBlendHeight;
            }

            boolean smartUpscaleForFrame =
                    video
                            && smartUpscaleEnabled
                            && smartUpscaler != null
                            && smartUpscaler.isReady()
                            && isVideoPrepared
                            && smartUpscaleNeedsProcessing(
                                    smartUpscaleInputFrameWidth, smartUpscaleInputFrameHeight);

            if (smartUpscaleForFrame) {
                try {
                    ensureSmartUpscaleInputBuffer(
                            smartUpscaleInputFrameWidth, smartUpscaleInputFrameHeight);
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, smartUpscaleInputFbo);

                    if (useGeneratedFrame) {
                        drawGeneratedFrameToBoundFramebuffer(
                                smartUpscaleInputWidth, smartUpscaleInputHeight);
                    } else if (useFiPreviousSourceFrame) {
                        drawBufferedFiSourceToBoundFramebuffer(
                                frameBlendPrevTexture,
                                smartUpscaleInputWidth,
                                smartUpscaleInputHeight);
                    } else {
                        drawVideoToBoundFramebuffer(
                                smartUpscaleInputWidth, smartUpscaleInputHeight);
                    }

                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    GLES20.glViewport(0, 0, width, height);
                    smartUpscaler.draw(
                            smartUpscaleInputTexture,
                            smartUpscaleInputWidth,
                            smartUpscaleInputHeight,
                            width,
                            height,
                            transform);
                    if (!smartUpscaleWorking) {
                        smartUpscaleWorking = true;
                        WallpaperBugRecorder.recordRuntimeSync(
                                context,
                                "SMART_UPSCALE_WORKING",
                                "source="
                                        + sourceWidth
                                        + "x"
                                        + sourceHeight
                                        + ",output="
                                        + width
                                        + "x"
                                        + height
                                        + ",backend="
                                        + smartUpscaleBackend);
                        SmartUpscaleEventListener listener = smartUpscaleEventListener;
                        if (listener != null) {
                            try {
                                listener.onSmartUpscaleEvent(true, true, smartUpscaleBackend);
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                } catch (Throwable t) {
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
                    smartUpscaleWorking = false;
                    WallpaperBugRecorder.record(
                            context,
                            "SMART_UPSCALE_RENDER_FAILED",
                            t.getClass().getName() + ":" + String.valueOf(t.getMessage()));
                    if (!smartUpscaleStrainDegraded) {
                        smartUpscaleStrainDegraded = true;
                        applySmartUpscaleBackend(true);
                    } else if (!smartUpscaleStrainBypassed) {
                        // If the degraded backend also fails, stop retrying the
                        // upscaler every frame and keep the normal wallpaper path.
                        smartUpscaleStrainBypassed = true;
                        releaseSmartUpscaleInputBuffer();
                        WallpaperBugRecorder.record(
                                context,
                                "GPU_SMART_UPSCALE_RENDER_BYPASS",
                                "reason=render_failure_after_degrade,backend="
                                        + smartUpscaleBackend);
                        refreshSmartUpscaleState();
                    }
                    SmartUpscaleEventListener listener = smartUpscaleEventListener;
                    if (listener != null) {
                        try {
                            listener.onSmartUpscaleEvent(
                                    video && smartUpscaleEnabled, false, smartUpscaleBackend);
                        } catch (Throwable ignored) {
                        }
                    }

                    // Safe fallback: use the existing renderer path unchanged.
                    drawNormalWallpaperFrame(
                            useGeneratedFrame, useFiPreviousSourceFrame, transform);
                }
            } else {
                drawNormalWallpaperFrame(useGeneratedFrame, useFiPreviousSourceFrame, transform);
            }
            // Keep the motion-only transform frozen; the image-specific overscan
            // scale is applied separately above for each newly loaded photo.
            lastRenderedTransform = transformForFreeze;

            if (!EGL14.eglSwapBuffers(display, eglSurface)) {
                int error = EGL14.eglGetError();
                consecutiveSwapFailures++;
                String reason = "egl_swap_failed:0x" + Integer.toHexString(error);
                WallpaperBugRecorder.record(
                        context,
                        "GPU_SWAP_FAILED",
                        "eglError=0x"
                                + Integer.toHexString(error)
                                + ",consecutive="
                                + consecutiveSwapFailures);
                if (consecutiveSwapFailures >= 3) {
                    notifyRendererFailure(reason + ",consecutive=" + consecutiveSwapFailures);
                }
            } else {
                consecutiveSwapFailures = 0;
                if (useGeneratedFrame) {
                    frameGenerationActive = true;
                    lastFrameGenerationNs = System.nanoTime();
                    interpolationShownForCurrent = true;
                    interpolationPending = false;
                }

                FrameGenerationEventListener fgel = frameGenerationEventListener;
                if (fgel != null && video && frameGenerationEnabled) {
                    try {
                        if (useGeneratedFrame) {
                            fgel.onFrameGenerationEvent(true);
                        } else if (frameGenerationActive
                                && lastFrameGenerationNs > 0L
                                && System.nanoTime() - lastFrameGenerationNs > 500_000_000L) {
                            frameGenerationActive = false;
                            fgel.onFrameGenerationEvent(false);
                        }
                    } catch (Throwable ignored) {
                    }
                }

                if (lastFrameEndNs <= 0L) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_RENDER_FIRST_FRAME",
                            "mode=" + (video ? "video" : "image"));
                }
                FramePresentedListener fl = framePresentedListener;
                if (fl != null) {
                    try {
                        fl.onFramePresented();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }

        // Native motion model shared by photo and video wallpapers. Keep the
        // bounded 2D/3D feel, smooth the rendered transform, and calculate enough
        // zoom to cover the screen while panning and tilting.
        float motionTx;
        float motionTy;
        float motionRx;
        float motionRy;
        float motionTz;
        float motionRz;
        float motionZoom = 1f;

        float targetTx;
        float targetTy;
        float targetRx;
        float targetRy;
        float targetTz;
        float targetRz;
        float targetZoom = 1f;

        long lastMotionNanos;

        // Cap how quickly the rendered wallpaper can move, independently of
        // gyro sensitivity. A fast phone movement may change the target quickly,
        // but the wallpaper itself follows at a controlled physical rate.
        // Deliberately conservative physical-speed caps. Sensitivity controls
        // the desired range; these caps control how quickly the rendered wallpaper
        // is allowed to get there.
        // Advanced Motion Settings: defaults match the engine's tuned limits.

        float rateLimitedStep(float current, float target, float maxRate, float dt) {
            float maxStep = maxRate * dt;
            float delta = target - current;
            if (delta > maxStep) return current + maxStep;
            if (delta < -maxStep) return current - maxStep;
            return target;
        }

        float[] motionTransform() {
            // Video wallpapers intentionally bypass user motion effects. Keep
            // the normal renderer/media initialization and optimization path
            // unchanged; only the motion transform itself is bypassed.
            if (video) {
                return identity.clone();
            }
            if (motionFrozen) {
                return lastRenderedTransform.clone();
            }

            float[] m = new float[16];
            Matrix.setIdentityM(m, 0);
            boolean stabilizationOnly = !gyroEnabled;

            // Port of the Live Stage motion model.
            float sx = Math.max(0f, Math.min(4f, sens2dX / 25f));
            float sy = Math.max(0f, Math.min(4f, sens2dY / 25f));
            float s3x = Math.max(0f, Math.min(4f, sens3dX / 25f));
            float s3y = Math.max(0f, Math.min(4f, sens3dY / 25f));
            float sd = Math.max(0f, Math.min(4f, sensDepth / 25f));

            float nextTx = 0f;
            float nextTy = 0f;
            float nextRx = 0f;
            float nextRy = 0f;
            float nextTz = 0f;
            float nextRz = 0f;
            float nextZoom = 1f;

            if (!stabilizationOnly && gyroRotationEnabled) {
                float rotationScale = Math.max(0f, Math.min(4f, gyroRotationSensitivity / 25f));
                float rotationDirection = gyroRotationInverted ? -1f : 1f;
                nextRz =
                        Math.max(
                                -20f,
                                Math.min(20f, gyroRotation * rotationScale * rotationDirection));
            }

            if (!stabilizationOnly && gyro2dEnabled) {
                float inversion2d = gyro2dInverted ? -1f : 1f;
                float rollNorm = (float) Math.tanh((gyroRoll / 18f) * sx) * inversion2d;
                float pitchNorm = (float) Math.tanh((gyroPitch / 18f) * sy) * inversion2d;
                float baseSafety = 0.72f;
                float xBudget = width * 0.08f * sx * baseSafety;
                float yBudget = height * 0.08f * sy * baseSafety;
                nextTx = rollNorm * Math.min(width * 0.10f, Math.max(0f, xBudget));
                nextTy = pitchNorm * Math.min(height * 0.10f, Math.max(0f, yBudget));
            }

            if (!stabilizationOnly && gyro3dEnabled) {
                float inversion3d = gyro3dInverted ? -1f : 1f;
                nextRx = -(float) Math.tanh((gyroPitch / 20f) * s3y) * 14f * s3y * inversion3d;
                nextRy = (float) Math.tanh((gyroRoll / 20f) * s3x) * 14f * s3x * inversion3d;
                nextRx = Math.max(-14f, Math.min(14f, nextRx));
                nextRy = Math.max(-14f, Math.min(14f, nextRy));

                float tilt =
                        Math.min(
                                45f,
                                (float) Math.sqrt(gyroRoll * gyroRoll + gyroPitch * gyroPitch));
                nextTz = (float) Math.tanh((tilt / 35f) * sd) * 12f * sd * inversion3d;

                if (edgeSafetyEnabled) {
                    float depthNormalized = Math.min(1f, tilt / 45f);
                    float depthZoom = 1f + depthNormalized * 0.08f * sd;
                    float radX = (float) Math.toRadians(Math.abs(nextRx));
                    float radY = (float) Math.toRadians(Math.abs(nextRy));
                    float fx =
                            Math.max(
                                    0.65f,
                                    (float) Math.cos(radY)
                                            - ((width * 0.28f) / 900f) * (float) Math.sin(radY));
                    float fy =
                            Math.max(
                                    0.65f,
                                    (float) Math.cos(radX)
                                            - ((height * 0.28f) / 900f) * (float) Math.sin(radX));
                    float panX = 1f + (2f * Math.abs(nextTx)) / Math.max(1, width);
                    float panY = 1f + (2f * Math.abs(nextTy)) / Math.max(1, height);
                    float corner = 1f + radX * 0.18f + radY * 0.18f;
                    float safeScale =
                            Math.max(
                                    (1f / fx) * panX,
                                    Math.max((1f / fy) * panY, corner * Math.max(panX, panY)));

                    // Add a small rendering margin because the native 3D plane uses
                    // a real perspective projection rather than the CSS perspective
                    // used by Live Stage. This margin prevents transformed corners
                    // from reaching the viewport boundary.
                    // Zoom is only an edge-safety mechanism. Use the minimum scale
                    // required by the actual 3D transform; do not add extra zoom.
                    float dynamicDepth = 1f + 0.12f * Math.min(1f, tilt / 30f);
                    nextZoom = Math.max(1f, Math.max(dynamicDepth, safeScale));
                    nextZoom = Math.max(nextZoom, depthZoom);
                }
            } else if (!stabilizationOnly && gyro2dEnabled) {
                if (edgeSafetyEnabled) {
                    float panX = 1f + (2f * Math.abs(nextTx)) / Math.max(1, width);
                    float panY = 1f + (2f * Math.abs(nextTy)) / Math.max(1, height);
                    float safeScale2D = Math.max(panX, panY) * 1.015f;
                    // Zoom is only an edge-safety mechanism. Use the minimum scale
                    // required to keep the current 2D translation inside the viewport.
                    nextZoom = Math.max(1f, safeScale2D);
                }
            }

            targetTx = nextTx;
            targetTy = nextTy;
            targetRx = nextRx;
            targetRy = nextRy;
            targetTz = nextTz;
            targetRz = nextRz;
            targetZoom = nextZoom;

            long now = System.nanoTime();
            float dt =
                    lastMotionNanos == 0L
                            ? 0.001f
                            : Math.max(
                                    0.001f,
                                    Math.min(0.1f, (now - lastMotionNanos) / 1_000_000_000f));
            lastMotionNanos = now;

            float responseSec2dX = Math.max(0.35f, 2.5f - (auto2dX / 100f) * 2f);
            float responseSec2dY = Math.max(0.35f, 2.5f - (auto2dY / 100f) * 2f);
            float responseSec3dX = Math.max(0.35f, 2.5f - (auto3dX / 100f) * 2f);
            float responseSec3dY = Math.max(0.35f, 2.5f - (auto3dY / 100f) * 2f);
            float responseSecZoom = Math.max(0.35f, 2.5f - (autoZoom / 100f) * 2f);

            float k2dX = 1f - (float) Math.exp(-dt / responseSec2dX);
            float k2dY = 1f - (float) Math.exp(-dt / responseSec2dY);
            float k3dX = 1f - (float) Math.exp(-dt / responseSec3dX);
            float k3dY = 1f - (float) Math.exp(-dt / responseSec3dY);
            float kZoom = 1f - (float) Math.exp(-dt / responseSecZoom);
            final float responseSecRotation = Math.max(0.5f, 2.5f - (autoRotation / 100f) * 2f);
            final float kRotationGyro = 1f - (float) Math.exp(-dt / responseSecRotation);

            // Keep motion smoothing active even when recovery is set to 0%.
            // Zero disables only the automatic pull-back, not sensor-driven movement.
            float stabilizationK2dX = auto2dX > 0 ? k2dX : 0f;
            float stabilizationK2dY = auto2dY > 0 ? k2dY : 0f;
            float stabilizationK3dX = auto3dX > 0 ? k3dX : 0f;
            float stabilizationK3dY = auto3dY > 0 ? k3dY : 0f;
            float stabilizationKZoom = autoZoom > 0 ? kZoom : 0f;

            // Live Stage's per-frame Auto-Stabilise pull-back.
            targetTx += -targetTx * stabilizationK2dX;
            targetTy += -targetTy * stabilizationK2dY;
            targetRx += -targetRx * stabilizationK3dY;
            targetRy += -targetRy * stabilizationK3dX;
            targetTz += -targetTz * Math.max(stabilizationK3dX, stabilizationK3dY);
            // Z-axis Auto-Stabilise is applied to the accumulated sensor angle
            // in LiveWallpaperService; here we only smooth the resulting target.
            targetZoom += (1f - targetZoom) * stabilizationKZoom;

            motionTx += (targetTx - motionTx) * k2dX;
            motionTy += (targetTy - motionTy) * k2dY;
            motionRx += (targetRx - motionRx) * k3dY;
            motionRy += (targetRy - motionRy) * k3dX;
            motionTz += (targetTz - motionTz) * Math.max(k3dX, k3dY);
            motionRz += (targetRz - motionRz) * kRotationGyro;
            motionZoom += (targetZoom - motionZoom) * kZoom;

            // Advanced Motion translation rate is expressed in normalized
            // screen units/sec. motionTx/motionTy themselves are pixel-space values,
            // so apply the limiter in normalized space and convert back.
            float currentTxNorm = (2f * motionTx) / Math.max(1, width);
            float targetTxNorm = (2f * targetTx) / Math.max(1, width);
            float currentTyNorm = (2f * motionTy) / Math.max(1, height);
            float targetTyNorm = (2f * targetTy) / Math.max(1, height);
            currentTxNorm = rateLimitedStep(currentTxNorm, targetTxNorm, motionTranslationRate, dt);
            currentTyNorm = rateLimitedStep(currentTyNorm, targetTyNorm, motionTranslationRate, dt);
            motionTx = (currentTxNorm * width) / 2f;
            motionTy = (currentTyNorm * height) / 2f;

            motionRx = rateLimitedStep(motionRx, targetRx, motionRotationRateDeg, dt);
            motionRy = rateLimitedStep(motionRy, targetRy, motionRotationRateDeg, dt);
            motionTz = rateLimitedStep(motionTz, targetTz, motionDepthRate, dt);
            motionRz = rateLimitedStep(motionRz, targetRz, motionRotationRateZDeg, dt);

            if (edgeSafetyEnabled) {
                // Anti-black-edge guard enforces coverage only while Edge Safety is enabled.
                float safetyZoom = 1f;
                if (!stabilizationOnly && gyro3dEnabled) {
                    float tilt =
                            Math.min(
                                    45f,
                                    (float) Math.sqrt(gyroRoll * gyroRoll + gyroPitch * gyroPitch));
                    float absRxRad = (float) Math.toRadians(Math.abs(motionRx));
                    float absRyRad = (float) Math.toRadians(Math.abs(motionRy));
                    float fx =
                            Math.max(
                                    0.65f,
                                    (float) Math.cos(absRyRad)
                                            - ((width * 0.28f) / 900f)
                                                    * (float) Math.sin(absRyRad));
                    float fy =
                            Math.max(
                                    0.65f,
                                    (float) Math.cos(absRxRad)
                                            - ((height * 0.28f) / 900f)
                                                    * (float) Math.sin(absRxRad));
                    float panX = 1f + (2f * Math.abs(motionTx)) / Math.max(1, width);
                    float panY = 1f + (2f * Math.abs(motionTy)) / Math.max(1, height);
                    float corner = 1f + absRxRad * 0.18f + absRyRad * 0.18f;
                    safetyZoom =
                            Math.max(
                                    (1f / fx) * panX,
                                    Math.max((1f / fy) * panY, corner * Math.max(panX, panY)));
                    // Small margin for the native perspective projection.
                    safetyZoom *= 1.02f;
                    float rotationRad = (float) Math.toRadians(Math.abs(motionRz));
                    float rotationCos = Math.abs((float) Math.cos(rotationRad));
                    float rotationSin = Math.abs((float) Math.sin(rotationRad));
                    float viewportAspect = width / (float) Math.max(1, height);
                    float rotationSafety =
                            Math.max(
                                    rotationCos + rotationSin / Math.max(0.2f, viewportAspect),
                                    rotationCos + rotationSin * viewportAspect);
                    safetyZoom = Math.max(safetyZoom, rotationSafety);
                } else if (!stabilizationOnly && gyro2dEnabled) {
                    float panX = 1f + (2f * Math.abs(motionTx)) / Math.max(1, width);
                    float panY = 1f + (2f * Math.abs(motionTy)) / Math.max(1, height);
                    safetyZoom = Math.max(panX, panY) * 1.015f;
                    float rotationRad2D = (float) Math.toRadians(Math.abs(motionRz));
                    float rotationCos2D = Math.abs((float) Math.cos(rotationRad2D));
                    float rotationSin2D = Math.abs((float) Math.sin(rotationRad2D));
                    safetyZoom = Math.max(safetyZoom, rotationCos2D + rotationSin2D);
                } else if (!stabilizationOnly) {
                    float rotationRadOnly = (float) Math.toRadians(Math.abs(motionRz));
                    float rotationCosOnly = Math.abs((float) Math.cos(rotationRadOnly));
                    float rotationSinOnly = Math.abs((float) Math.sin(rotationRadOnly));
                    safetyZoom = Math.max(rotationCosOnly + rotationSinOnly, 1f);
                }

                targetZoom = Math.max(targetZoom, safetyZoom);
                motionZoom = Math.max(motionZoom, safetyZoom);
            }

            // With Edge Safety off, targetZoom falls back to 1x and motionZoom eases back
            // to native scale. With it on, the minimum safety scale is enforced above.
            final float zoomSmoothing = kZoom * zoomResponseFactor;
            motionZoom += (targetZoom - motionZoom) * zoomSmoothing;

            if (pauseStabilizationActive && isMotionAtRest()) {
                motionTx = 0f;
                motionTy = 0f;
                motionRx = 0f;
                motionRy = 0f;
                motionTz = 0f;
                motionRz = 0f;
                motionZoom = 1f;
                targetTx = 0f;
                targetTy = 0f;
                targetRx = 0f;
                targetRy = 0f;
                targetTz = 0f;
                targetRz = 0f;
                targetZoom = 1f;
            }

            if (Math.abs(motionTx) < 0.2f) motionTx = 0f;
            if (Math.abs(motionTy) < 0.2f) motionTy = 0f;
            if (Math.abs(motionRx) < 0.1f) motionRx = 0f;
            if (Math.abs(motionRy) < 0.1f) motionRy = 0f;
            if (Math.abs(motionRz) < 0.05f) motionRz = 0f;
            if (Math.abs(motionZoom - 1f) < 0.003f) motionZoom = 1f;

            // Native 3D uses a real perspective projection instead of treating
            // the full-screen clip-space quad as a flat 2D object. This keeps the
            // wallpaper behaving like a physical rectangular plane.
            if (gyro3dEnabled) {
                float aspect = width / (float) Math.max(1, height);

                float[] projection = new float[16];
                // 90 degree vertical FOV: at z=-1 a unit-height plane fills
                // the viewport vertically. X is expanded by the surface aspect.
                Matrix.perspectiveM(projection, 0, 90f, aspect, 0.05f, 10f);

                float[] model = new float[16];
                Matrix.setIdentityM(model, 0);

                // Live Stage dx/dy are CSS pixels. Convert them into the
                // corresponding native plane-space displacement.
                float nativeTx = (2f * motionTx / Math.max(1, width)) * aspect;
                float nativeTy = (2f * motionTy) / Math.max(1, height);

                // Keep the plane at a stable camera distance. Stage Z is only
                // a subtle depth component, so map its pixel-like value into
                // a small world-space offset.
                float depthWorld = Math.max(-0.35f, Math.min(0.35f, motionTz / 100f));

                Matrix.translateM(model, 0, nativeTx, -nativeTy, -1f + depthWorld);
                Matrix.rotateM(model, 0, motionRx, 1f, 0f, 0f);
                Matrix.rotateM(model, 0, motionRy, 0f, 1f, 0f);
                Matrix.rotateM(model, 0, motionRz, 0f, 0f, 1f);
                Matrix.scaleM(model, 0, aspect * motionZoom, motionZoom, 1f);
                Matrix.rotateM(model, 0, mediaOrientation, 0f, 0f, 1f);

                // gl_Position = projection * model * vertex.
                Matrix.multiplyMM(m, 0, projection, 0, model, 0);
                return m;
            }

            // 2D-only path: Live Stage dx/dy are pixels, so convert them to
            // the native clip-space displacement without introducing 3D
            // perspective or distortion.
            float nativeTx = (2f * motionTx) / Math.max(1, width);
            float nativeTy = (2f * motionTy) / Math.max(1, height);
            Matrix.translateM(m, 0, nativeTx, -nativeTy, 0f);
            Matrix.rotateM(m, 0, motionRz, 0f, 0f, 1f);
            Matrix.scaleM(m, 0, motionZoom, motionZoom, 1f);
            Matrix.rotateM(m, 0, mediaOrientation, 0f, 0f, 1f);
            return m;
        }

        boolean isMotionAtRest() {
            return Math.abs(motionTx) < 0.5f
                    && Math.abs(motionTy) < 0.5f
                    && Math.abs(motionRx) < 0.2f
                    && Math.abs(motionRy) < 0.2f
                    && Math.abs(motionTz) < 0.2f
                    && Math.abs(motionRz) < 0.1f
                    && Math.abs(motionZoom - 1f) < 0.004f;
        }

        boolean isMotionSettledForRendering() {
            return Math.abs(targetTx - motionTx) < 0.18f
                    && Math.abs(targetTy - motionTy) < 0.18f
                    && Math.abs(targetRx - motionRx) < 0.12f
                    && Math.abs(targetRy - motionRy) < 0.12f
                    && Math.abs(targetTz - motionTz) < 0.18f
                    && Math.abs(targetRz - motionRz) < 0.12f
                    && Math.abs(targetZoom - motionZoom) < 0.002f;
        }

        float[] cropTexCoords() {
            if (width <= 0 || height <= 0) {
                return new float[] {
                    0f, 1f, 1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f, 0f, 0f
                };
            }
            if (!video && imageTextureIsPreCropped) {
                return new float[] {
                    0f, 1f, 1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f, 0f, 0f
                };
            }
            int sourceWidth = video ? videoWidth : imageWidth;
            int sourceHeight = video ? videoHeight : imageHeight;
            return sourceCropTexCoords(
                    sourceWidth,
                    sourceHeight,
                    currentUri == null ? null : currentUri.toString(),
                    width,
                    height,
                    mediaOrientation);
        }

        private float[] sourceCropTexCoords(
                int sourceWidth,
                int sourceHeight,
                String media,
                int surfaceWidth,
                int surfaceHeight,
                int orientation) {
            if (sourceWidth <= 0 || sourceHeight <= 0 || surfaceWidth <= 0 || surfaceHeight <= 0) {
                return new float[] {
                    0f, 1f, 1f, 1f, 0f, 0f,
                    1f, 1f, 1f, 0f, 0f, 0f
                };
            }
            float sourceAspect = (float) sourceWidth / Math.max(1, sourceHeight);
            float targetAspect = (float) surfaceWidth / Math.max(1, surfaceHeight);
            if (orientation == 90 || orientation == 270) {
                targetAspect = 1f / Math.max(0.0001f, targetAspect);
            }
            float cropX = 0f;
            float cropY = 0f;
            try {
                if (context != null && media != null) {
                    android.content.SharedPreferences p =
                            context.getSharedPreferences(
                                    LiveWallpaperService.PREFS_NAME, Context.MODE_PRIVATE);
                    cropX = WallpaperCrop.readX(p, media);
                    cropY = WallpaperCrop.readY(p, media);
                }
            } catch (Throwable ignored) {
            }
            float u0 = 0f, u1 = 1f, v0 = 0f, v1 = 1f;
            if (sourceAspect > targetAspect) {
                float visible = targetAspect / sourceAspect;
                float slack = Math.max(0f, 1f - visible);
                u0 = Math.max(0f, Math.min(slack, (0.5f - cropX * 0.5f) * slack));
                u1 = u0 + visible;
            } else if (sourceAspect < targetAspect) {
                float visible = sourceAspect / targetAspect;
                float slack = Math.max(0f, 1f - visible);
                v0 = Math.max(0f, Math.min(slack, (0.5f - cropY * 0.5f) * slack));
                v1 = v0 + visible;
            }
            return new float[] {u0, v1, u1, v1, u0, v0, u1, v1, u1, v0, u0, v0};
        }

        FloatBuffer directFloats(float[] values) {
            ByteBuffer b =
                    ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
            FloatBuffer f = b.asFloatBuffer();
            f.put(values).position(0);
            return f;
        }

        void destroyWindowSurface() {
            surfaceReady = false;
            standaloneEffectFramePresented = false;
            standaloneEffectLastPhaseUpdateNs = 0L;
            standaloneEffectNextFrameNs = 0L;
            resetPerformanceProtection("surface_destroyed");
            destroyWindowSurfaceInternal();
            releaseMedia();
            WallpaperBugRecorder.record(context, "SURFACE_DETACHED", "");
        }

        void destroyWindowSurfaceInternal() {
            if (display != EGL14.EGL_NO_DISPLAY && eglSurface != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                EGL14.eglDestroySurface(display, eglSurface);
                eglSurface = EGL14.EGL_NO_SURFACE;
            }
        }

        void releaseMedia() {
            photoTextureRequestGeneration++;
            photoTextureRebuildPending = false;
            photoTexturePendingTier = -1;
            interpolationPending = false;
            interpolationShownForCurrent = false;
            interpolationPreviousReady = false;
            lastVideoTimestamp = -1L;
            lastSourceTimestampNs = -1L;
            videoSourceFps = 0f;
            sourceFpsSamples = 0;
            frameGenerationStartNs = 0L;
            fiTargetFps = 0;
            releaseFrameGenerationBuffers();
            imageTextureIsPreCropped = false;
            imageTextureOverscanScaleX = 1f;
            imageTextureOverscanScaleY = 1f;
            photoFramePending = false;
            motionUpdatePending = false;
            pausedImageFramePending = false;
            boolean wasVideo = video;
            isVideoPrepared = false;
            smartUpscaleEnabled = false;
            releaseSmartUpscaleInputBuffer();
            if (wasVideo && smartUpscaleWorking) {
                smartUpscaleWorking = false;
            }
            if (player != null) {
                try {
                    player.stop();
                } catch (Exception ignored) {
                }
                try {
                    player.reset();
                } catch (Exception ignored) {
                }
                try {
                    player.release();
                } catch (Exception ignored) {
                }
                player = null;
            }
            if (mediaSurface != null) {
                try {
                    mediaSurface.release();
                } catch (Exception ignored) {
                }
                mediaSurface = null;
            }
            if (videoTexture != null) {
                try {
                    videoTexture.setOnFrameAvailableListener(null);
                } catch (Exception ignored) {
                }
                try {
                    videoTexture.release();
                } catch (Exception ignored) {
                }
                videoTexture = null;
            }
            frameAvailable = false;
            video = false;
            smartUpscaleEnabled = false;
            smartUpscaleStrainBypassed = false;
            smartUpscalePublishedEnabled = false;
            smartUpscaleWorking = false;
            videoWidth = 1;
            videoHeight = 1;
            Matrix.setIdentityM(videoTexMatrix, 0);
        }

        void destroyGl() {
            clearStandaloneEffectResources();
            releaseGeometryBuffers();
            if (program2d != 0) GLES20.glDeleteProgram(program2d);
            if (programOes != 0) GLES20.glDeleteProgram(programOes);
            if (programBlur2d != 0) GLES20.glDeleteProgram(programBlur2d);
            if (programBlurOes != 0) GLES20.glDeleteProgram(programBlurOes);
            if (programFrameBlend != 0) GLES20.glDeleteProgram(programFrameBlend);
            releaseSmartUpscaleInputBuffer();
            if (smartUpscaler != null) smartUpscaler.release();
            if (texture2d != 0) GLES20.glDeleteTextures(1, new int[] {texture2d}, 0);
            if (textureOes != 0) GLES20.glDeleteTextures(1, new int[] {textureOes}, 0);
            program2d = 0;
            programOes = 0;
            programBlur2d = 0;
            programBlurOes = 0;
            programFrameBlend = 0;

            program2dPosition = program2dTexCoord = program2dTexture = -1;
            program2dTexMatrix = program2dTransform = -1;
            programOesPosition = programOesTexCoord = programOesTexture = -1;
            programOesTexMatrix = programOesTransform = -1;
            programBlur2dPosition = programBlur2dTexCoord = programBlur2dTexture = -1;
            programBlur2dTexMatrix = programBlur2dTransform = programBlur2dStep = -1;
            programBlurOesPosition = programBlurOesTexCoord = programBlurOesTexture = -1;
            programBlurOesTexMatrix = programBlurOesTransform = programBlurOesStep = -1;
            frameBlendPosition = frameBlendTexCoord = frameBlendPrev = -1;
            frameBlendCurrent = frameBlendStrength = frameBlendMotionField = -1;
            frameBlendMotionSampleScale = -1;
            frameBlendMotionFieldTransform = -1;

            smartUpscaler = null;
            smartUpscaleBackend = "Unavailable";
            smartUpscalePublishedEnabled = false;
            smartUpscaleWorking = false;
            activeProgram = 0;
            texture2d = 0;
            textureOes = 0;
        }

        void destroyEgl() {
            if (thermalStatusMonitor != null) {
                try {
                    thermalStatusMonitor.unregister();
                } catch (Throwable error) {
                    WallpaperBugRecorder.record(
                            context,
                            "GPU_EFFECT_THERMAL_MONITOR_REMOVE_FAILED",
                            error.getClass().getName() + ":" + String.valueOf(error.getMessage()));
                }
                thermalStatusMonitor = null;
            }
            standaloneEffectThermalLevel = StandaloneEffectThermalLevel.NONE;
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(
                        display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
                if (eglContext != EGL14.EGL_NO_CONTEXT) {
                    EGL14.eglDestroyContext(display, eglContext);
                }
                EGL14.eglTerminate(display);
            }
            display = EGL14.EGL_NO_DISPLAY;
            eglContext = EGL14.EGL_NO_CONTEXT;
        }

        void shutdown() {
            running = false;
            synchronized (wakeLock) {
                wakeLock.notifyAll();
            }
            try {
                join(3000);
            } catch (InterruptedException ignored) {
            }
        }
    }
}
