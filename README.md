# LiveStagix Vulkan Lab

A small Android Vulkan test app kept separate from LiveWP.

The project now tests the same Vulkan triangle renderer in two Android environments:
- A Vulkan test surface during the initial renderer milestone.
- A real Android WallpaperService surface for live-wallpaper lifecycle testing.

The wallpaper is deliberately a minimal mock. It validates Android wallpaper integration; it is not yet the production LiveStagix renderer and does not include visual effects.

## Run the wallpaper test

1. Build and install the vulkan-lab-debug-apk artifact on an Android device.
2. Open LiveStagix Vulkan Mock.
3. Tap Preview LiveStagix wallpaper.
4. Android opens its native live-wallpaper preview. Set the wallpaper to test it on the home screen.
5. Return to the app and use Copy logs to share the Event Recorder output.

The wallpaper engine records:
- Wallpaper engine creation and destruction
- Wallpaper surface creation, resize and destruction
- Visibility changes
- Vulkan renderer start, pause, resume and stop
- GPU initialization and swapchain details
- Five-second FPS samples and average CPU-side frame duration

When the wallpaper is not visible, the renderer is paused. When its surface is destroyed, Vulkan resources are released.

## Event Recorder

The Event Recorder displays recent events and saves them to the app-private file named vulkan-session.log. Use Copy logs to copy the current in-memory session log.

The reported frame duration is CPU-side timing around the frame submission/presentation path. It is not a direct GPU execution-time measurement.

## Build

The GitHub Actions workflow builds a debug APK when changes are pushed to main, or when manually started from the Actions tab.

Download the vulkan-lab-debug-apk artifact from a successful run and install the APK on an Android device.

The build uses:
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Kotlin 2.3.21
- Android SDK 36
- Android NDK 28.2.13676358
- CMake and the Android NDK Vulkan headers/libraries

The initial APK targets arm64-v8a, matching the first test device.

## Current limits

- Vulkan API 1.0 is requested by the app for broad compatibility. The detected GPU's supported API version is logged separately.
- The renderer uses the guaranteed FIFO presentation mode.
- The first pass does not include a Vulkan validation layer or GPU timestamp queries.
- Only one native Vulkan renderer instance is managed by the test app.
- This mock tests Android's wallpaper service and surface lifecycle; it does not prove production readiness for all Android devices.
- No OpenGL ES code in LiveWP is changed or replaced.
- No visual effects or downloadable effect packages are included yet.
