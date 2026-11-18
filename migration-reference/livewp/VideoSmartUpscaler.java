package com.livewp.app;

import android.opengl.GLES20;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * Video-only spatial upscaler.
 *
 * Smart Upscale now has two renderer-native paths. Render 3.0 uses GLSL ES
 * 3.00 with GPU-resident quad buffers; compatibility uses the existing GLSL ES
 * 1.00/GLES2 path. The Adreno path uses a compact SGSR-inspired, edge-aware
 * reconstruction with a fixed five-tap neighborhood for wallpaper playback.
 *
 * Copyright (c) Qualcomm Innovation Center, Inc. All rights reserved.
 * SPDX-License-Identifier: BSD-3-Clause
 *
 * Qualcomm Snapdragon Game Super Resolution reference:
 * https://github.com/SnapdragonGameStudios/snapdragon-gsr
 *
 * The generic path is the fallback for non-Adreno and unsupported Adreno
 * devices.
 */
final class VideoSmartUpscaler {
    public static final int TYPE_SGSR = 0;
    public static final int TYPE_RCAS = 1;
    public static final int TYPE_LINEAR = 2;

    private int adrenoProgram;
    private int rcasProgram;
    private int genericProgram;
    private int activeProgram;
    private int modernAdrenoProgram;
    private int modernRcasProgram;
    private int modernGenericProgram;
    private int aPosition;
    private int aTexCoord;
    private int uTexture;
    private int uTexel;
    private int uTransform;
    private int uQuality;
    private int uViewportInfo;
    private boolean ready;
    private int quality = 100;
    private boolean adrenoBackend;
    private boolean modernRenderer;
    private int modernPositionVbo;
    private int modernTexCoordVbo;
    private String backendName = "Unavailable";
    private String fallbackReason = "";

    private final FloatBufferHolder quad = new FloatBufferHolder();

    void initialize() {
        initialize("auto", false);
    }

    void initialize(String preference, boolean useModernRenderer) {
        release();
        modernRenderer = useModernRenderer;
        fallbackReason = "";

        String renderer = safeLower(GLES20.glGetString(GLES20.GL_RENDERER));
        boolean isAdreno = renderer.contains("adreno");
        boolean isMaliOrModern =
                renderer.contains("mali")
                        || renderer.contains("immortalis")
                        || renderer.contains("xclipse")
                        || renderer.contains("geforce")
                        || renderer.contains("radeon")
                        || renderer.contains("apple")
                        || renderer.contains("powervr");

        boolean preferSa = "sa".equalsIgnoreCase(preference);
        boolean preferRcas = "rcas".equalsIgnoreCase(preference);
        boolean preferGe = "ge".equalsIgnoreCase(preference) || "ls".equalsIgnoreCase(preference);

        // Keep the proven GLES2 implementations available as fallback.
        try {
            genericProgram = createProgram(TYPE_LINEAR, false);
        } catch (Throwable ignored) {
            genericProgram = 0;
        }
        try {
            rcasProgram = createProgram(TYPE_RCAS, false);
        } catch (Throwable failure) {
            rcasProgram = 0;
            if (preferRcas) {
                fallbackReason = "RCAS compile failed: " + failure.getMessage();
            }
        }
        if (isAdreno || preferSa) {
            try {
                adrenoProgram = createProgram(TYPE_SGSR, false);
            } catch (Throwable failure) {
                adrenoProgram = 0;
                if (preferSa) {
                    fallbackReason = "SGSR compile failed: " + failure.getMessage();
                }
            }
        }

        // Render 3.0 gets native GLSL ES 3.00 shaders and GPU-resident quad VBOs.
        if (modernRenderer) {
            try {
                modernGenericProgram = createProgram(TYPE_LINEAR, true);
            } catch (Throwable ignored) {
                modernGenericProgram = 0;
            }
            try {
                modernRcasProgram = createProgram(TYPE_RCAS, true);
            } catch (Throwable failure) {
                modernRcasProgram = 0;
                if (preferRcas && (fallbackReason == null || fallbackReason.isEmpty())) {
                    fallbackReason = "RCAS ES3 compile failed: " + failure.getMessage();
                }
            }
            if ((isAdreno || preferSa) && supportsGlslEs31()) {
                try {
                    modernAdrenoProgram = createProgram(TYPE_SGSR, true);
                } catch (Throwable failure) {
                    modernAdrenoProgram = 0;
                    fallbackReason =
                            "SGSR initialization failed: "
                                    + failure.getClass().getName()
                                    + ":"
                                    + String.valueOf(failure.getMessage());
                }
            } else if (modernRenderer && (isAdreno || preferSa)) {
                fallbackReason = "SGSR unavailable: OpenGL ES 3.1+ required for textureGather";
            }
        }

        // Select best engine based on device profile and user preference
        int selectedType;
        if (preferSa) {
            selectedType = TYPE_SGSR;
        } else if (preferRcas) {
            selectedType = TYPE_RCAS;
        } else if (preferGe) {
            selectedType = TYPE_LINEAR;
        } else {
            // Auto device adaptation:
            if (isAdreno) {
                selectedType = TYPE_SGSR;
            } else if (isMaliOrModern) {
                selectedType = TYPE_RCAS;
            } else {
                selectedType = TYPE_LINEAR;
            }
        }

        if (selectedType == TYPE_SGSR) {
            if (modernRenderer && modernAdrenoProgram != 0) {
                activeProgram = modernAdrenoProgram;
                adrenoBackend = true;
                backendName = "SGSR1-Adreno-ES3";
                fallbackReason = "";
            } else if (adrenoProgram != 0) {
                activeProgram = adrenoProgram;
                adrenoBackend = true;
                backendName = "SGSR1-Adreno";
                modernRenderer = false;
                fallbackReason = "";
            } else if (modernRenderer && modernRcasProgram != 0) {
                activeProgram = modernRcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD-ES3";
                if (preferSa) fallbackReason = "SGSR unavailable; fell back to RCAS (ES3)";
            } else if (rcasProgram != 0) {
                activeProgram = rcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD";
                modernRenderer = false;
                if (preferSa) fallbackReason = "SGSR unavailable; fell back to RCAS (GLES2)";
            } else if (modernRenderer && modernGenericProgram != 0) {
                activeProgram = modernGenericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-ES3";
                if (preferSa)
                    fallbackReason = "SGSR unavailable; fell back to Linear Sharpener (ES3)";
            } else if (genericProgram != 0) {
                activeProgram = genericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-GLES2";
                modernRenderer = false;
                if (preferSa)
                    fallbackReason = "SGSR unavailable; fell back to Linear Sharpener (GLES2)";
            }
        } else if (selectedType == TYPE_RCAS) {
            if (modernRenderer && modernRcasProgram != 0) {
                activeProgram = modernRcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD-ES3";
                fallbackReason = "";
            } else if (rcasProgram != 0) {
                activeProgram = rcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD";
                modernRenderer = false;
                fallbackReason = "";
            } else if (modernRenderer && modernGenericProgram != 0) {
                activeProgram = modernGenericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-ES3";
                if (preferRcas)
                    fallbackReason = "RCAS unavailable; fell back to Linear Sharpener (ES3)";
            } else if (genericProgram != 0) {
                activeProgram = genericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-GLES2";
                modernRenderer = false;
                if (preferRcas)
                    fallbackReason = "RCAS unavailable; fell back to Linear Sharpener (GLES2)";
            } else if (adrenoProgram != 0) {
                activeProgram = adrenoProgram;
                adrenoBackend = true;
                backendName = "SGSR1-Adreno";
                modernRenderer = false;
                if (preferRcas) fallbackReason = "RCAS unavailable; fell back to SGSR";
            }
        } else {
            // TYPE_LINEAR
            if (modernRenderer && modernGenericProgram != 0) {
                activeProgram = modernGenericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-ES3";
                fallbackReason = "";
            } else if (genericProgram != 0) {
                activeProgram = genericProgram;
                adrenoBackend = false;
                backendName = "LS-Linear-GLES2";
                modernRenderer = false;
                fallbackReason = "";
            } else if (modernRenderer && modernRcasProgram != 0) {
                activeProgram = modernRcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD-ES3";
            } else if (rcasProgram != 0) {
                activeProgram = rcasProgram;
                adrenoBackend = false;
                backendName = "RCAS-AMD";
                modernRenderer = false;
            }
        }

        if (activeProgram == 0) {
            ready = false;
            backendName = "Unavailable";
            return;
        }

        if (modernRenderer) {
            try {
                initializeModernGeometry();
            } catch (Throwable modernGeometryFailure) {
                fallbackReason =
                        "Modern SU geometry failed: "
                                + modernGeometryFailure.getClass().getName()
                                + ":"
                                + String.valueOf(modernGeometryFailure.getMessage());
                // A native ES3 setup problem must not disable video upscaling.
                // Drop to the already-created GLES2 implementation instead.
                if (modernRcasProgram != 0 && activeProgram == modernRcasProgram) {
                    activeProgram =
                            rcasProgram != 0
                                    ? rcasProgram
                                    : (genericProgram != 0 ? genericProgram : adrenoProgram);
                    adrenoBackend = false;
                    backendName =
                            rcasProgram != 0
                                    ? "RCAS-AMD"
                                    : (genericProgram != 0 ? "LS-Linear-GLES2" : "SGSR1-Adreno");
                } else if (modernAdrenoProgram != 0 && activeProgram == modernAdrenoProgram) {
                    activeProgram =
                            adrenoProgram != 0
                                    ? adrenoProgram
                                    : (rcasProgram != 0 ? rcasProgram : genericProgram);
                    adrenoBackend = adrenoProgram != 0;
                    backendName =
                            adrenoProgram != 0
                                    ? "SGSR1-Adreno"
                                    : (rcasProgram != 0 ? "RCAS-AMD" : "Generic-GLES2");
                } else {
                    activeProgram =
                            genericProgram != 0
                                    ? genericProgram
                                    : (rcasProgram != 0 ? rcasProgram : adrenoProgram);
                    adrenoBackend = false;
                    backendName =
                            genericProgram != 0
                                    ? "Generic-GLES2"
                                    : (rcasProgram != 0 ? "RCAS-AMD" : "SGSR1-Adreno");
                }
                modernRenderer = false;
            }
        }

        cacheLocations();
        boolean sgsr1Ready = modernRenderer && adrenoBackend && uViewportInfo >= 0;
        boolean fallbackReady = !sgsr1Ready && uTexel >= 0;
        ready =
                aPosition >= 0
                        && aTexCoord >= 0
                        && uTexture >= 0
                        && uTransform >= 0
                        && (sgsr1Ready || fallbackReady);

        if (!ready) {
            if (fallbackReason.isEmpty()) {
                fallbackReason =
                        "Smart Upscale program setup incomplete: pos="
                                + aPosition
                                + ",tc="
                                + aTexCoord
                                + ",tex="
                                + uTexture
                                + ",tr="
                                + uTransform
                                + ",sgsrReady="
                                + sgsr1Ready
                                + ",fbReady="
                                + fallbackReady;
            }
            release();
        }
    }

    private void cacheLocations() {
        aPosition = GLES20.glGetAttribLocation(activeProgram, "aPosition");
        aTexCoord = GLES20.glGetAttribLocation(activeProgram, "aTexCoord");
        uTexture = GLES20.glGetUniformLocation(activeProgram, "uTexture");
        uTexel = GLES20.glGetUniformLocation(activeProgram, "uTexel");
        uTransform = GLES20.glGetUniformLocation(activeProgram, "uTransform");
        uQuality = GLES20.glGetUniformLocation(activeProgram, "uQuality");
        uViewportInfo = GLES20.glGetUniformLocation(activeProgram, "ViewportInfo[0]");
    }

    private void initializeModernGeometry() {
        int[] ids = new int[2];
        GLES20.glGenBuffers(2, ids, 0);
        modernPositionVbo = ids[0];
        modernTexCoordVbo = ids[1];
        if (modernPositionVbo == 0 || modernTexCoordVbo == 0) {
            releaseModernGeometry();
            throw new IllegalStateException("native ES3 SU geometry buffer creation failed");
        }

        float[] positions = {-1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, 1f};
        float[] texCoords = {
            0f, 0f, 1f, 0f, 0f, 1f,
            1f, 0f, 1f, 1f, 0f, 1f
        };
        FloatBuffer positionData = direct(positions);
        FloatBuffer texData = direct(texCoords);

        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, modernPositionVbo);
        GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, positions.length * 4, positionData, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, modernTexCoordVbo);
        GLES20.glBufferData(
                GLES20.GL_ARRAY_BUFFER, texCoords.length * 4, texData, GLES20.GL_STATIC_DRAW);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
    }

    private void releaseModernGeometry() {
        if (modernPositionVbo != 0 || modernTexCoordVbo != 0) {
            GLES20.glDeleteBuffers(2, new int[] {modernPositionVbo, modernTexCoordVbo}, 0);
        }
        modernPositionVbo = 0;
        modernTexCoordVbo = 0;
    }

    boolean isReady() {
        return ready;
    }

    String getBackendName() {
        return backendName;
    }

    String getFallbackReason() {
        return fallbackReason;
    }

    void setQuality(int quality) {
        this.quality = Math.max(0, Math.min(100, quality));
    }

    void draw(int texture, int sourceWidth, int sourceHeight, float[] transform) {
        draw(texture, sourceWidth, sourceHeight, sourceWidth, sourceHeight, transform);
    }

    void draw(
            int texture,
            int sourceWidth,
            int sourceHeight,
            int outputWidth,
            int outputHeight,
            float[] transform) {
        if (!ready || activeProgram == 0 || texture == 0 || sourceWidth <= 0 || sourceHeight <= 0) {
            return;
        }

        GLES20.glUseProgram(activeProgram);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glUniform1i(uTexture, 0);
        if (uViewportInfo >= 0) {
            GLES20.glUniform4f(
                    uViewportInfo, 1f / sourceWidth, 1f / sourceHeight, sourceWidth, sourceHeight);
        } else if (uTexel >= 0) {
            GLES20.glUniform2f(uTexel, 1f / sourceWidth, 1f / sourceHeight);
        }
        if (uQuality >= 0) {
            float effectiveQuality = Math.max(0, Math.min(100, quality)) / 100f;
            GLES20.glUniform1f(uQuality, effectiveQuality);
        }
        GLES20.glUniformMatrix4fv(uTransform, 1, false, transform, 0);

        if (modernRenderer) {
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, modernPositionVbo);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, 0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, modernTexCoordVbo);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, 0);
        } else {
            FloatBuffer positionBuffer = quad.positions();
            FloatBuffer texBuffer = quad.texCoords();
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
            GLES20.glEnableVertexAttribArray(aPosition);
            GLES20.glVertexAttribPointer(aPosition, 2, GLES20.GL_FLOAT, false, 0, positionBuffer);
            GLES20.glEnableVertexAttribArray(aTexCoord);
            GLES20.glVertexAttribPointer(aTexCoord, 2, GLES20.GL_FLOAT, false, 0, texBuffer);
        }
        GLES20.glDrawArrays(GLES20.GL_TRIANGLES, 0, 6);
        GLES20.glDisableVertexAttribArray(aPosition);
        GLES20.glDisableVertexAttribArray(aTexCoord);
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);

        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
        GLES20.glUseProgram(0);
    }

    void release() {
        if (modernAdrenoProgram != 0) GLES20.glDeleteProgram(modernAdrenoProgram);
        if (modernRcasProgram != 0) GLES20.glDeleteProgram(modernRcasProgram);
        if (modernGenericProgram != 0) GLES20.glDeleteProgram(modernGenericProgram);
        if (adrenoProgram != 0) GLES20.glDeleteProgram(adrenoProgram);
        if (rcasProgram != 0) GLES20.glDeleteProgram(rcasProgram);
        if (genericProgram != 0) GLES20.glDeleteProgram(genericProgram);
        releaseModernGeometry();
        modernAdrenoProgram = 0;
        modernRcasProgram = 0;
        modernGenericProgram = 0;
        adrenoProgram = 0;
        rcasProgram = 0;
        genericProgram = 0;
        activeProgram = 0;
        ready = false;
        adrenoBackend = false;
        modernRenderer = false;
        backendName = "Unavailable";
    }

    private int createProgram(int upscalerType, boolean modern) {
        String vertex;
        boolean sgsrUsesEs31 = (upscalerType == TYPE_SGSR) && modern && supportsGlslEs31();
        if (modern) {
            String modernVersion = sgsrUsesEs31 ? "#version 310 es\n" : "#version 300 es\n";
            vertex =
                    modernVersion
                            + "in vec2 aPosition;"
                            + "in vec2 aTexCoord;"
                            + "uniform mat4 uTransform;"
                            + "out vec2 vTexCoord;"
                            + "void main(){"
                            + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                            + "vTexCoord=aTexCoord;"
                            + "}";
        } else {
            vertex =
                    "attribute vec2 aPosition;"
                            + "attribute vec2 aTexCoord;"
                            + "uniform mat4 uTransform;"
                            + "varying vec2 vTexCoord;"
                            + "void main(){"
                            + "gl_Position=uTransform*vec4(aPosition,0.0,1.0);"
                            + "vTexCoord=aTexCoord;"
                            + "}";
        }

        String fragment;
        if (upscalerType == TYPE_SGSR) {
            if (modern) {
                fragment = sgsr1MobileFragmentShader(sgsrUsesEs31);
            } else {
                fragment = adrenoFragmentShader();
            }
        } else if (upscalerType == TYPE_RCAS) {
            fragment = rcasFragmentShader();
            if (modern) fragment = toModernFragmentShader(fragment);
        } else {
            fragment = genericFragmentShader();
            if (modern) fragment = toModernFragmentShader(fragment);
        }
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vertex);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fragment);
        int p = GLES20.glCreateProgram();
        if (p == 0) {
            GLES20.glDeleteShader(vs);
            GLES20.glDeleteShader(fs);
            throw new IllegalStateException("upscale program create failed");
        }
        GLES20.glAttachShader(p, vs);
        GLES20.glAttachShader(p, fs);
        GLES20.glLinkProgram(p);

        int[] linked = new int[1];
        GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, linked, 0);
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);

        if (linked[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(p);
            GLES20.glDeleteProgram(p);
            throw new IllegalStateException("upscale program link failed: " + log);
        }
        return p;
    }

    private String toModernFragmentShader(String source) {
        String modern =
                source.replace("varying vec2 vTexCoord;", "in vec2 vTexCoord;out vec4 fragColor;")
                        .replace("texture2D(", "texture(")
                        .replace("gl_FragColor=", "fragColor=");
        return "#version 300 es\n" + modern;
    }

    private static FloatBuffer direct(float[] values) {
        ByteBuffer b = ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
        FloatBuffer f = b.asFloatBuffer();
        f.put(values).position(0);
        return f;
    }

    private int compileShader(int type, String source) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) throw new IllegalStateException("upscale shader create failed");
        GLES20.glShaderSource(shader, source);
        GLES20.glCompileShader(shader);

        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            throw new IllegalStateException("upscale shader compile failed: " + log);
        }
        return shader;
    }

    private String sgsr1MobileFragmentShader(boolean useEs31) {
        String version = useEs31 ? "#version 310 es\n" : "#version 300 es\n";
        return version
                + "precision mediump float;\n"
                + "precision highp int;\n"
                + "uniform highp vec4 ViewportInfo[1];\n"
                + "uniform mediump sampler2D uTexture;\n"
                + "uniform float uQuality;\n"
                + "in highp vec2 vTexCoord;\n"
                + "out vec4 fragColor;\n"
                + "float fastLanczos2(float x){\n"
                + "  float wA=x-4.0;\n"
                + "  float wB=x*wA-wA;\n"
                + "  wA*=wA;\n"
                + "  return wB*wA;\n"
                + "}\n"
                + "vec2 weightY(float dx,float dy,float c,float std){\n"
                + "  float x=((dx*dx)+(dy*dy))*0.55+clamp(abs(c)*std,0.0,1.0);\n"
                + "  float w=fastLanczos2(x);\n"
                + "  return vec2(w,w*c);\n"
                + "}\n"
                + "void main(){\n"
                + "  float q=clamp(uQuality,0.0,1.0);\n"
                + "  vec4 color=textureLod(uTexture,vTexCoord,0.0);\n"
                + "  if(q<0.20){\n"
                + "    fragColor=vec4(color.rgb,1.0);\n"
                + "    return;\n"
                + "  }\n"
                + "  if(q<0.40){\n"
                + "    vec4 lowA=textureLod(uTexture,vTexCoord+vec2(ViewportInfo[0].x,0.0),0.0);\n"
                + "    vec4 lowB=textureLod(uTexture,vTexCoord-vec2(ViewportInfo[0].x,0.0),0.0);\n"
                + "    vec3 lowRing=(lowA.rgb+lowB.rgb)*0.5;\n"
                + "    float lowAmount=mix(0.12,0.30,(q-0.20)/0.20);\n"
                + "    fragColor=vec4(clamp(color.rgb+(color.rgb-lowRing)*lowAmount,0.0,1.0),1.0);\n"
                + "    return;\n"
                + "  }\n"
                + "  float qualityLevel=clamp((q-0.40)/0.60,0.0,1.0);\n"
                + "  float edgeThreshold=mix(12.0/255.0,8.0/255.0,qualityLevel);\n"
                + "  float edgeSharpness=mix(1.0,2.0,qualityLevel);\n"
                + "  highp vec2 imgCoord=(vTexCoord*ViewportInfo[0].zw)+vec2(-0.5,0.5);\n"
                + "  highp vec2 imgCoordPixel=floor(imgCoord);\n"
                + "  highp vec2 coord=imgCoordPixel*ViewportInfo[0].xy;\n"
                + "  vec2 pl=imgCoord-imgCoordPixel;\n"
                + "  vec4 left=textureGather(uTexture,coord,1);\n"
                + "  float edgeVote=abs(left.z-left.y)+abs(color.g-left.y)+abs(color.g-left.z);\n"
                + "  if(edgeVote>edgeThreshold){\n"
                + "    coord.x+=ViewportInfo[0].x;\n"
                + "    vec4 right=textureGather(uTexture,coord+vec2(ViewportInfo[0].x,0.0),1);\n"
                + "    vec4 upDown;\n"
                + "    upDown.xy=textureGather(uTexture,coord+vec2(0.0,-ViewportInfo[0].y),1).wz;\n"
                + "    upDown.zw=textureGather(uTexture,coord+vec2(0.0,ViewportInfo[0].y),1).yx;\n"
                + "    float mean=(left.y+left.z+right.x+right.w)*0.25;\n"
                + "    left-=vec4(mean);\n"
                + "    right-=vec4(mean);\n"
                + "    upDown-=vec4(mean);\n"
                + "    float centerY=color.g-mean;\n"
                + "    float sum=((((abs(left.x)+abs(left.y))+abs(left.z))+abs(left.w))\n"
                + "      +(((abs(right.x)+abs(right.y))+abs(right.z))+abs(right.w))\n"
                + "      +(((abs(upDown.x)+abs(upDown.y))+abs(upDown.z))+abs(upDown.w)));\n"
                + "    float sumMean=1.014185e+01/max(sum,0.0001);\n"
                + "    float std=sumMean*sumMean;\n"
                + "    vec2 aWY=weightY(pl.x,pl.y+1.0,upDown.x,std);\n"
                + "    aWY+=weightY(pl.x-1.0,pl.y+1.0,upDown.y,std);\n"
                + "    aWY+=weightY(pl.x-1.0,pl.y-2.0,upDown.z,std);\n"
                + "    aWY+=weightY(pl.x,pl.y-2.0,upDown.w,std);\n"
                + "    aWY+=weightY(pl.x+1.0,pl.y-1.0,left.x,std);\n"
                + "    aWY+=weightY(pl.x,pl.y-1.0,left.y,std);\n"
                + "    aWY+=weightY(pl.x,pl.y,left.z,std);\n"
                + "    aWY+=weightY(pl.x+1.0,pl.y,left.w,std);\n"
                + "    aWY+=weightY(pl.x-1.0,pl.y-1.0,right.x,std);\n"
                + "    aWY+=weightY(pl.x-2.0,pl.y-1.0,right.y,std);\n"
                + "    aWY+=weightY(pl.x-2.0,pl.y,right.z,std);\n"
                + "    aWY+=weightY(pl.x-1.0,pl.y,right.w,std);\n"
                + "    float finalY=aWY.y/max(aWY.x,0.0001);\n"
                + "    float maxY=max(max(left.y,left.z),max(right.x,right.w));\n"
                + "    float minY=min(min(left.y,left.z),min(right.x,right.w));\n"
                + "    finalY=clamp(edgeSharpness*finalY,minY,maxY);\n"
                + "    float deltaY=clamp(finalY-centerY,-23.0/255.0,23.0/255.0);\n"
                + "    color.rgb=clamp(color.rgb+vec3(deltaY),0.0,1.0);\n"
                + "  }\n"
                + "  fragColor=vec4(color.rgb,1.0);\n"
                + "}";
    }

    private String rcasFragmentShader() {
        return "precision mediump float;\n"
                + "uniform mediump sampler2D uTexture;\n"
                + "uniform vec2 uTexel;\n"
                + "uniform float uQuality;\n"
                + "varying vec2 vTexCoord;\n"
                + "void main(){\n"
                + "  float q=clamp(uQuality,0.0,1.0);\n"
                + "  vec4 e=texture2D(uTexture,vTexCoord);\n"
                + "  if(q<0.15){\n"
                + "    gl_FragColor=e;\n"
                + "    return;\n"
                + "  }\n"
                + "  vec4 b=texture2D(uTexture,vTexCoord+vec2(0.0,-uTexel.y));\n"
                + "  vec4 d=texture2D(uTexture,vTexCoord+vec2(-uTexel.x,0.0));\n"
                + "  vec4 f=texture2D(uTexture,vTexCoord+vec2(uTexel.x,0.0));\n"
                + "  vec4 h=texture2D(uTexture,vTexCoord+vec2(0.0,uTexel.y));\n"
                + "  float bL=b.r*0.25+b.g*0.50+b.b*0.25;\n"
                + "  float dL=d.r*0.25+d.g*0.50+d.b*0.25;\n"
                + "  float eL=e.r*0.25+e.g*0.50+e.b*0.25;\n"
                + "  float fL=f.r*0.25+f.g*0.50+f.b*0.25;\n"
                + "  float hL=h.r*0.25+h.g*0.50+h.b*0.25;\n"
                + "  float minL=min(min(min(min(bL,dL),eL),fL),hL);\n"
                + "  float maxL=max(max(max(max(bL,dL),eL),fL),hL);\n"
                + "  float hitMin=minL/max(4.0*maxL,0.001);\n"
                + "  float hitMax=(1.0-maxL)/max(4.0*(1.0-minL),0.001);\n"
                + "  float lobe=min(hitMin,hitMax);\n"
                + "  float sharpness=mix(0.18,0.72,q);\n"
                + "  float w=-clamp(lobe,0.0,0.22)*sharpness;\n"
                + "  vec3 sharpColor=(b.rgb+d.rgb+f.rgb+h.rgb)*w+e.rgb;\n"
                + "  sharpColor=sharpColor/max(1.0+4.0*w,0.001);\n"
                + "  vec3 minRgb=min(min(min(min(b.rgb,d.rgb),e.rgb),f.rgb),h.rgb);\n"
                + "  vec3 maxRgb=max(max(max(max(b.rgb,d.rgb),e.rgb),f.rgb),h.rgb);\n"
                + "  vec3 clamped=clamp(sharpColor,minRgb,maxRgb);\n"
                + "  gl_FragColor=vec4(clamp(clamped,0.0,1.0),e.a);\n"
                + "}";
    }

    private String genericFragmentShader() {
        return "precision mediump float;"
                + "uniform mediump sampler2D uTexture;"
                + "uniform vec2 uTexel;"
                + "uniform float uQuality;"
                + "varying vec2 vTexCoord;"
                + "void main(){"
                + "float q=clamp(uQuality,0.0,1.0);"
                + "if(q<0.20){"
                + "gl_FragColor=texture2D(uTexture,vTexCoord);"
                + "return;"
                + "}"
                + "vec4 c=texture2D(uTexture,vTexCoord);"
                + "if(q<0.40){"
                + "vec4 l=texture2D(uTexture,vTexCoord+vec2(-uTexel.x,0.0));"
                + "vec4 r=texture2D(uTexture,vTexCoord+vec2(uTexel.x,0.0));"
                + "vec3 lowRing=(l.rgb+r.rgb)*0.5;"
                + "float lowAmount=mix(0.12,0.30,(q-0.20)/0.20);"
                + "gl_FragColor=vec4(clamp(c.rgb+(c.rgb-lowRing)*lowAmount,0.0,1.0),c.a);"
                + "return;"
                + "}"
                + "float qualityLevel=clamp((q-0.40)/0.60,0.0,1.0);"
                + "vec4 n=texture2D(uTexture,vTexCoord+vec2(0.0,-uTexel.y));"
                + "vec4 s=texture2D(uTexture,vTexCoord+vec2(0.0, uTexel.y));"
                + "vec4 e=texture2D(uTexture,vTexCoord+vec2( uTexel.x,0.0));"
                + "vec4 w=texture2D(uTexture,vTexCoord+vec2(-uTexel.x,0.0));"
                + "vec3 minC=min(min(n.rgb,s.rgb),min(e.rgb,w.rgb));"
                + "vec3 maxC=max(max(n.rgb,s.rgb),max(e.rgb,w.rgb));"
                + "vec3 ring=(n.rgb+s.rgb+e.rgb+w.rgb)*0.25;"
                + "vec3 detail=c.rgb-ring;"
                + "float edge=clamp(length(detail)*3.0,0.0,1.0);"
                + "float sharpness=mix(0.25,0.70,qualityLevel)+0.15*edge;"
                + "vec3 sharp=c.rgb+detail*sharpness;"
                + "gl_FragColor=vec4(clamp(sharp,minC,maxC),1.0);"
                + "}";
    }

    private String adrenoFragmentShader() {
        return "precision mediump float;"
                + "uniform mediump sampler2D uTexture;"
                + "uniform vec2 uTexel;"
                + "uniform mat4 uTransform;"
                + "uniform float uQuality;"
                + "varying vec2 vTexCoord;"
                + "void main(){"
                + "float q=clamp(uQuality,0.0,1.0);"
                + "if(q<0.20){"
                + "gl_FragColor=texture2D(uTexture,vTexCoord);"
                + "return;"
                + "}"
                + "vec4 c=texture2D(uTexture,vTexCoord);"
                + "vec4 n=texture2D(uTexture,vTexCoord+vec2(0.0,-uTexel.y));"
                + "vec4 s=texture2D(uTexture,vTexCoord+vec2(0.0, uTexel.y));"
                + "vec4 e=texture2D(uTexture,vTexCoord+vec2( uTexel.x,0.0));"
                + "vec4 w=texture2D(uTexture,vTexCoord+vec2(-uTexel.x,0.0));"
                + "vec3 minColor=min(c.rgb,min(min(n.rgb,s.rgb),min(e.rgb,w.rgb)));"
                + "vec3 maxColor=max(c.rgb,max(max(n.rgb,s.rgb),max(e.rgb,w.rgb)));"
                + "vec3 amp=clamp(min(minColor,2.0-maxColor)/max(maxColor,0.001),0.0,1.0);"
                + "vec3 peak=-1.0/mix(8.0,4.5,q);"
                + "vec3 weight=amp*peak;"
                + "vec3 sum=(n.rgb+s.rgb+e.rgb+w.rgb)*weight;"
                + "vec3 result=clamp((c.rgb+sum)/(1.0+4.0*weight),minColor,maxColor);"
                + "gl_FragColor=vec4(result,1.0);"
                + "}";
    }

    private boolean supportsGlslEs31() {
        String version = GLES20.glGetString(GLES20.GL_VERSION);
        if (version == null) return false;
        java.util.regex.Matcher matcher =
                java.util.regex.Pattern.compile("OpenGL\\s+ES\\s+(\\d+)\\.(\\d+)").matcher(version);
        if (!matcher.find()) return false;
        try {
            int major = Integer.parseInt(matcher.group(1));
            int minor = Integer.parseInt(matcher.group(2));
            return major > 3 || (major == 3 && minor >= 1);
        } catch (NumberFormatException ignored) {
            return false;
        }
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(java.util.Locale.US);
    }

    private static final class FloatBufferHolder {
        private final FloatBuffer positions;
        private final FloatBuffer texCoords;

        FloatBufferHolder() {
            positions = direct(new float[] {-1f, -1f, 1f, -1f, -1f, 1f, 1f, -1f, 1f, 1f, -1f, 1f});
            texCoords =
                    direct(
                            new float[] {
                                0f, 0f, 1f, 0f, 0f, 1f,
                                1f, 0f, 1f, 1f, 0f, 1f
                            });
        }

        FloatBuffer positions() {
            positions.position(0);
            return positions;
        }

        FloatBuffer texCoords() {
            texCoords.position(0);
            return texCoords;
        }

        private static FloatBuffer direct(float[] values) {
            ByteBuffer b =
                    ByteBuffer.allocateDirect(values.length * 4).order(ByteOrder.nativeOrder());
            FloatBuffer f = b.asFloatBuffer();
            f.put(values).position(0);
            return f;
        }
    }
}
