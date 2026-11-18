# LiveStagix Renderer Foundation

This document is the durable implementation map for the shared renderer. It records what exists,
what is only a contract or CPU-side starting point, and what must be implemented before a backend
may advertise support to LiveWP.

## Ownership boundary

- **LiveWP host:** WallpaperService/Engine lifecycle, Android sensor registration and recovery,
  preferences, battery saver and visibility policy, media preparation and durable storage,
  diagnostics, and cleanup/rollback ownership.
- **Shared renderer core:** backend-neutral scene and media contracts, portable CPU algorithms,
  common feature selection, runtime event contracts, and workload-quality policy.
- **Each graphics backend:** graphics context/device, GPU textures and buffers, render targets,
  shaders/pipelines, video output/import, GPU synchronization, presentation, and resource cleanup.

The renderer library must not read LiveWP preferences or retain LiveWP's Context. The host supplies
media access and a small environment snapshot through RendererCreateRequest.

## Backend baseline

| Backend | Current baseline | Not yet claimed |
| --- | --- | --- |
| Vulkan | Native device, surface, swapchain, pipeline and diagnostic frame | Photo/video scene, motion, effects, FI, SU |
| OpenGL ES 3.0 | EGL ES3 context, photo texture upload/rendering, 2D/3D/rotation motion | Video, effects, FI, SU |
| OpenGL ES 2.0 | Independent EGL ES2 context, photo texture upload/rendering, 2D/3D/rotation motion | Video, effects, FI, SU |

All three backend capability objects intentionally leave contentFeatures empty until a complete
end-to-end feature path is implemented and tested. Low-level GPU extensions are not proof that a
wallpaper feature works.

## Shared foundations added

- **Photo:** RendererPhotoDecoder performs CPU decode, sampling, orientation and cover-crop.
  GLES2 and GLES3 now upload the decoded bitmap on their own EGL threads, replace/delete
  photo textures safely, and redraw the image through backend-specific shaders.
- **Video:** RendererMediaAccess and RendererVideoDecoder define host access and decoder
  boundaries. GLES2/3 need a SurfaceTexture/external-OES import path; Vulkan needs an
  AImageReader/HardwareBuffer and Vulkan external-memory path. These are not interchangeable.
- **Motion:** RendererMotionTransformSmoother supplies the LiveWP-derived per-axis smoothing,
  auto-stabilisation, edge-safety and rate limiting. GLES2 and GLES3 apply the shared result
  through independent 2D or perspective 3D transform matrices.
- **Effects:** RendererEffectCatalog preserves pulse_halo and reactor_core. Their existing
  LiveWP GLES20 shaders must be ported independently to GLES2, GLES3 and Vulkan pipelines.
- **Frame generation:** RendererFrameGenerationAnalyzer estimates CPU luma block motion.
  Frame capture, GPU interpolation, cadence control and display presentation remain per-backend.
- **Smart Upscale:** RendererUpscalePolicy selects the requested algorithm and strength.
  SA/RCAS/GE/linear shader passes are not implemented by this policy.
- **Performance:** RendererPerformanceController consumes measured frame/GPU times and host-fed
  thermal state. It does not own battery saver, visibility, sensors or user preferences.
- **Runtime contract:** RendererEvent covers frame presentation, video readiness/frames, FI/SU
  state, effect recovery/quality, performance, media release, fallback and failures.

## Migration order

1. Keep LiveWP's current GpuWallpaperRenderer as the app-side fallback. Do not run it alongside
   a new Effects session for the same wallpaper surface.
2. Complete the remaining photo upload and motion path in Vulkan; GLES2 and GLES3 now expose
   content sessions for photos and motion.
3. Add the GLES SurfaceTexture/external-OES video path and the Vulkan HardwareBuffer path.
4. Port Pulse Halo and Reactor Core shader passes to each backend.
5. Add backend-specific FI interpolation and SU shader passes, with explicit feature checks.
6. Wire GPU timing, quality decisions and typed events into the host compatibility adapter.
7. Move LiveWP's facade calls to the shared manager only after the required content features are
   available for that scene. Preserve transaction release acknowledgements before deleting old media.

## Verification rules

- Unit tests cover portable CPU algorithms; backend instrumentation/smoke tests cover EGL/Vulkan
  lifecycle, surface resize, pause/resume and cleanup.
- Feature parity is not inferred from successful context creation or a diagnostic frame.
- A backend only advertises a feature after its decode/import, render, synchronization and cleanup
  path passes targeted tests on supported device classes.
- LiveWP integration uses development as its source reference. Stable is not modified by this
  renderer-foundation work.
