# LiveStagix Vulkan Lab

A small Android Vulkan test app kept separate from LiveWP.

The first milestone is intentionally narrow: create an Android Vulkan surface, find a compatible physical device and graphics/present queues, create a swapchain, and draw one triangle. It is a Vulkan foundation test, not the LiveWP renderer and not a visual-effects project.

## What the test records

The Event Recorder displays the latest session events and saves them to the app's private file named `vulkan-session.log`.

It records:
- Android device model, API level, hardware name and supported ABIs
- Vulkan features advertised by Android
- Vulkan instance and surface creation results
- Detected GPU name, device type, Vulkan API version and driver version
- Graphics/presentation queue selection
- Swapchain dimensions, image count, format and presentation mode
- Surface resize and app pause/resume events
- Vulkan operation failures and swapchain recovery
- A five-second FPS sample and average CPU-side frame duration

Use **Copy logs** in the app to copy the current in-memory session log. The log is also retained in app-private storage across launches.

The reported frame duration is CPU-side timing around the frame submission/presentation path. It is not a direct GPU execution-time measurement.

## Build

The GitHub Actions workflow builds a debug APK when changes are pushed to `main`, or when manually started from the Actions tab.

Download the `vulkan-lab-debug-apk` artifact from a successful run and install the APK on an Android device.

The build uses:
- Android Gradle Plugin 9.4.0
- Gradle 9.6.0
- Kotlin 2.3.21
- Android SDK 36
- Android NDK 28.2.13676358
- CMake and the Android NDK Vulkan headers/libraries

The initial APK targets `arm64-v8a`, matching the first test device. The app can still open on a device without a compatible Vulkan GPU; the recorder will show where initialization stopped.

## Current limits

- Vulkan API 1.0 is requested by the app for broad compatibility. The detected GPU's supported API version is logged separately.
- The renderer uses the guaranteed FIFO presentation mode.
- The first pass does not include a Vulkan validation layer or GPU timestamp queries.
- No OpenGL ES code in LiveWP is changed or replaced.
- No visual effects or downloadable effect packages are included yet.
