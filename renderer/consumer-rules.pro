# JNI resolves these methods by their exact class and method names.
# Keep native entry points and callbacks intact in every consuming application.
-keep class com.livestagix.renderer.NativeVulkanRenderer { *; }
