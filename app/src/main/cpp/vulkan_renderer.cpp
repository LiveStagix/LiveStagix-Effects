#define VK_USE_PLATFORM_ANDROID_KHR

#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/log.h>
#include <android/native_window.h>
#include <android/native_window_jni.h>
#include <jni.h>
#include <vulkan/vulkan.h>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

namespace {

constexpr const char* kLogTag = "LiveStagixVulkan";
JavaVM* gJavaVm = nullptr;
jclass gActivityClass = nullptr;
jmethodID gNativeLogMethod = nullptr;

void emitLog(const char* level, const std::string& message) {
    int priority = ANDROID_LOG_INFO;
    if (std::strcmp(level, "WARN") == 0) priority = ANDROID_LOG_WARN;
    if (std::strcmp(level, "ERROR") == 0) priority = ANDROID_LOG_ERROR;
    if (std::strcmp(level, "DEBUG") == 0) priority = ANDROID_LOG_DEBUG;
    __android_log_print(priority, kLogTag, "%s", message.c_str());

    if (gJavaVm == nullptr || gActivityClass == nullptr || gNativeLogMethod == nullptr) {
        return;
    }

    JNIEnv* env = nullptr;
    bool attachedHere = false;
    const jint getEnvResult = gJavaVm->GetEnv(
        reinterpret_cast<void**>(&env),
        JNI_VERSION_1_6
    );

    if (getEnvResult == JNI_EDETACHED) {
        if (gJavaVm->AttachCurrentThread(&env, nullptr) != JNI_OK) {
            return;
        }
        attachedHere = true;
    } else if (getEnvResult != JNI_OK) {
        return;
    }

    jstring javaLevel = env->NewStringUTF(level);
    jstring javaMessage = env->NewStringUTF(message.c_str());
    if (javaLevel != nullptr && javaMessage != nullptr) {
        env->CallStaticVoidMethod(
            gActivityClass,
            gNativeLogMethod,
            javaLevel,
            javaMessage
        );
    }

    if (env->ExceptionCheck()) {
        env->ExceptionClear();
    }
    if (javaLevel != nullptr) env->DeleteLocalRef(javaLevel);
    if (javaMessage != nullptr) env->DeleteLocalRef(javaMessage);
    if (attachedHere) gJavaVm->DetachCurrentThread();
}

std::string vkVersionString(uint32_t version) {
    std::ostringstream stream;
    stream << VK_VERSION_MAJOR(version) << "."
           << VK_VERSION_MINOR(version) << "."
           << VK_VERSION_PATCH(version);
    return stream.str();
}

std::string vkResultString(VkResult result) {
    switch (result) {
        case VK_SUCCESS: return "VK_SUCCESS";
        case VK_NOT_READY: return "VK_NOT_READY";
        case VK_TIMEOUT: return "VK_TIMEOUT";
        case VK_EVENT_SET: return "VK_EVENT_SET";
        case VK_EVENT_RESET: return "VK_EVENT_RESET";
        case VK_INCOMPLETE: return "VK_INCOMPLETE";
        case VK_ERROR_OUT_OF_HOST_MEMORY: return "VK_ERROR_OUT_OF_HOST_MEMORY";
        case VK_ERROR_OUT_OF_DEVICE_MEMORY: return "VK_ERROR_OUT_OF_DEVICE_MEMORY";
        case VK_ERROR_INITIALIZATION_FAILED: return "VK_ERROR_INITIALIZATION_FAILED";
        case VK_ERROR_DEVICE_LOST: return "VK_ERROR_DEVICE_LOST";
        case VK_ERROR_MEMORY_MAP_FAILED: return "VK_ERROR_MEMORY_MAP_FAILED";
        case VK_ERROR_LAYER_NOT_PRESENT: return "VK_ERROR_LAYER_NOT_PRESENT";
        case VK_ERROR_EXTENSION_NOT_PRESENT: return "VK_ERROR_EXTENSION_NOT_PRESENT";
        case VK_ERROR_FEATURE_NOT_PRESENT: return "VK_ERROR_FEATURE_NOT_PRESENT";
        case VK_ERROR_INCOMPATIBLE_DRIVER: return "VK_ERROR_INCOMPATIBLE_DRIVER";
        case VK_ERROR_TOO_MANY_OBJECTS: return "VK_ERROR_TOO_MANY_OBJECTS";
        case VK_ERROR_FORMAT_NOT_SUPPORTED: return "VK_ERROR_FORMAT_NOT_SUPPORTED";
        case VK_ERROR_SURFACE_LOST_KHR: return "VK_ERROR_SURFACE_LOST_KHR";
        case VK_ERROR_NATIVE_WINDOW_IN_USE_KHR: return "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
        case VK_SUBOPTIMAL_KHR: return "VK_SUBOPTIMAL_KHR";
        case VK_ERROR_OUT_OF_DATE_KHR: return "VK_ERROR_OUT_OF_DATE_KHR";
        default: return "VkResult(" + std::to_string(static_cast<int>(result)) + ")";
    }
}

void requireVk(VkResult result, const char* operation) {
    if (result != VK_SUCCESS) {
        throw std::runtime_error(
            std::string(operation) + " failed: " + vkResultString(result)
        );
    }
}

bool hasInstanceExtension(const char* name) {
    uint32_t count = 0;
    if (vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr) != VK_SUCCESS) {
        return false;
    }

    std::vector<VkExtensionProperties> extensions(count);
    if (count > 0 && vkEnumerateInstanceExtensionProperties(
            nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }

    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [name](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, name) == 0;
        }
    );
}

bool hasDeviceExtension(VkPhysicalDevice device, const char* name) {
    uint32_t count = 0;
    if (vkEnumerateDeviceExtensionProperties(device, nullptr, &count, nullptr) != VK_SUCCESS) {
        return false;
    }

    std::vector<VkExtensionProperties> extensions(count);
    if (count > 0 && vkEnumerateDeviceExtensionProperties(
            device, nullptr, &count, extensions.data()) != VK_SUCCESS) {
        return false;
    }

    return std::any_of(
        extensions.begin(),
        extensions.end(),
        [name](const VkExtensionProperties& extension) {
            return std::strcmp(extension.extensionName, name) == 0;
        }
    );
}

struct QueueFamilies {
    uint32_t graphics = UINT32_MAX;
    uint32_t present = UINT32_MAX;

    bool complete() const {
        return graphics != UINT32_MAX && present != UINT32_MAX;
    }
};

class VulkanRenderer {
public:
    VulkanRenderer(ANativeWindow* window, AAssetManager* assets)
        : window_(window), assets_(assets) {
    }

    ~VulkanRenderer() {
        stop();
        if (window_ != nullptr) {
            ANativeWindow_release(window_);
            window_ = nullptr;
        }
    }

    void start() {
        stopRequested_.store(false);
        renderThread_ = std::thread(&VulkanRenderer::threadMain, this);
    }

    void stop() {
        stopRequested_.store(true);
        if (renderThread_.joinable()) {
            renderThread_.join();
        }
    }

    void requestResize(int width, int height) {
        if (width <= 0 || height <= 0) {
            emitLog(
                "WARN",
                "Ignoring invalid Android surface size request: " +
                    std::to_string(width) + "x" + std::to_string(height)
            );
            return;
        }

        {
            std::lock_guard<std::mutex> lock(resizeMutex_);
            pendingResizeWidth_ = width;
            pendingResizeHeight_ = height;
            resizeRequestedAt_ = std::chrono::steady_clock::now();
            resizePending_ = true;
        }

        emitLog(
            "DEBUG",
            "Android surface resize requested: " +
                std::to_string(width) + "x" + std::to_string(height) +
                " | waiting 100 ms for resize to settle"
        );
    }

    bool takeStableResizeRequest(int& width, int& height) {
        std::lock_guard<std::mutex> lock(resizeMutex_);
        if (!resizePending_) {
            return false;
        }

        const auto elapsed = std::chrono::steady_clock::now() - resizeRequestedAt_;
        if (elapsed < std::chrono::milliseconds(100)) {
            return false;
        }

        width = pendingResizeWidth_;
        height = pendingResizeHeight_;
        resizePending_ = false;
        return true;
    }

    bool hasPendingResizeRequest() {
        std::lock_guard<std::mutex> lock(resizeMutex_);
        return resizePending_;
    }

    void getLatestResizeRequest(int& width, int& height) {
        std::lock_guard<std::mutex> lock(resizeMutex_);
        width = pendingResizeWidth_;
        height = pendingResizeHeight_;
    }

    void setPaused(bool paused) {
        paused_.store(paused);
    }

private:
    ANativeWindow* window_ = nullptr;
    AAssetManager* assets_ = nullptr;
    std::thread renderThread_;
    std::atomic<bool> stopRequested_{false};
    std::atomic<bool> paused_{false};

    std::mutex resizeMutex_;
    int pendingResizeWidth_ = 0;
    int pendingResizeHeight_ = 0;
    std::chrono::steady_clock::time_point resizeRequestedAt_{};
    bool resizePending_ = false;

    VkInstance instance_ = VK_NULL_HANDLE;
    VkSurfaceKHR surface_ = VK_NULL_HANDLE;
    VkPhysicalDevice physicalDevice_ = VK_NULL_HANDLE;
    VkPhysicalDeviceProperties deviceProperties_{};
    VkDevice device_ = VK_NULL_HANDLE;
    VkQueue graphicsQueue_ = VK_NULL_HANDLE;
    VkQueue presentQueue_ = VK_NULL_HANDLE;
    QueueFamilies queueFamilies_{};

    VkSwapchainKHR swapchain_ = VK_NULL_HANDLE;
    VkFormat swapchainFormat_ = VK_FORMAT_UNDEFINED;
    VkExtent2D swapchainExtent_{};
    std::vector<VkImage> swapchainImages_;
    std::vector<VkImageView> imageViews_;
    VkRenderPass renderPass_ = VK_NULL_HANDLE;
    VkPipelineLayout pipelineLayout_ = VK_NULL_HANDLE;
    VkPipeline graphicsPipeline_ = VK_NULL_HANDLE;
    std::vector<VkFramebuffer> framebuffers_;

    VkCommandPool commandPool_ = VK_NULL_HANDLE;
    std::vector<VkCommandBuffer> commandBuffers_;
    VkSemaphore imageAvailable_ = VK_NULL_HANDLE;
    std::vector<VkSemaphore> renderFinished_;
    VkFence inFlightFence_ = VK_NULL_HANDLE;

    void threadMain() {
        emitLog("INFO", "Native Vulkan render thread started");

        try {
            initialize();
            emitLog(
                "INFO",
                "READY: " + std::string(deviceProperties_.deviceName) +
                    " | Vulkan " + vkVersionString(deviceProperties_.apiVersion) +
                    " | " + std::to_string(swapchainExtent_.width) + "x" +
                    std::to_string(swapchainExtent_.height)
            );
            renderLoop();
        } catch (const std::exception& error) {
            emitLog("ERROR", error.what());
        } catch (...) {
            emitLog("ERROR", "Unknown native renderer failure");
        }

        shutdown();
        emitLog("INFO", "Renderer stopped; Vulkan resources released");
    }

    void initialize() {
        emitLog("INFO", "Creating Vulkan instance");
        createInstance();

        emitLog("INFO", "Creating Android Vulkan surface");
        createSurface();

        emitLog("INFO", "Enumerating physical devices and queue families");
        selectPhysicalDevice();

        emitLog("INFO", "Creating logical device and queues");
        createLogicalDevice();

        createCommandPool();
        createFrameSync();
        createSwapchainResources();

        emitLog("INFO", "Initial Vulkan setup completed");
    }

    void createInstance() {
        if (!hasInstanceExtension(VK_KHR_SURFACE_EXTENSION_NAME)) {
            throw std::runtime_error("Required instance extension VK_KHR_surface is unavailable");
        }
        if (!hasInstanceExtension(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME)) {
            throw std::runtime_error("Required instance extension VK_KHR_android_surface is unavailable");
        }

        uint32_t layerCount = 0;
        std::vector<VkLayerProperties> layers;
        if (vkEnumerateInstanceLayerProperties(&layerCount, nullptr) == VK_SUCCESS) {
            layers.resize(layerCount);
            if (layerCount > 0) {
                vkEnumerateInstanceLayerProperties(&layerCount, layers.data());
            }
        }

        const bool validationAvailable = std::any_of(
            layers.begin(),
            layers.end(),
            [](const VkLayerProperties& layer) {
                return std::strcmp(layer.layerName, "VK_LAYER_KHRONOS_validation") == 0;
            }
        );

        emitLog(
            "INFO",
            validationAvailable
                ? "Vulkan validation layer is available but not enabled in this lightweight test"
                : "Vulkan validation layer is not exposed by this device/runtime"
        );

        const char* extensions[] = {
            VK_KHR_SURFACE_EXTENSION_NAME,
            VK_KHR_ANDROID_SURFACE_EXTENSION_NAME
        };

        VkApplicationInfo applicationInfo{};
        applicationInfo.sType = VK_STRUCTURE_TYPE_APPLICATION_INFO;
        applicationInfo.pApplicationName = "LiveStagix Vulkan Lab";
        applicationInfo.applicationVersion = 1;
        applicationInfo.pEngineName = "LiveStagix Vulkan Test";
        applicationInfo.engineVersion = 1;
        applicationInfo.apiVersion = VK_API_VERSION_1_0;

        VkInstanceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO;
        createInfo.pApplicationInfo = &applicationInfo;
        createInfo.enabledExtensionCount = 2;
        createInfo.ppEnabledExtensionNames = extensions;

        requireVk(vkCreateInstance(&createInfo, nullptr, &instance_), "vkCreateInstance");
        emitLog("INFO", "Vulkan instance created (application API request: 1.0)");
    }

    void createSurface() {
        VkAndroidSurfaceCreateInfoKHR createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR;
        createInfo.window = window_;

        requireVk(
            vkCreateAndroidSurfaceKHR(instance_, &createInfo, nullptr, &surface_),
            "vkCreateAndroidSurfaceKHR"
        );
        emitLog("INFO", "Android surface created successfully");
    }

    void selectPhysicalDevice() {
        uint32_t deviceCount = 0;
        requireVk(
            vkEnumeratePhysicalDevices(instance_, &deviceCount, nullptr),
            "vkEnumeratePhysicalDevices(count)"
        );

        if (deviceCount == 0) {
            throw std::runtime_error(
                "Vulkan instance exists, but the runtime reported zero physical devices"
            );
        }

        std::vector<VkPhysicalDevice> devices(deviceCount);
        requireVk(
            vkEnumeratePhysicalDevices(instance_, &deviceCount, devices.data()),
            "vkEnumeratePhysicalDevices(list)"
        );

        for (VkPhysicalDevice candidate : devices) {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(candidate, &properties);

            emitLog(
                "INFO",
                "GPU candidate: " + std::string(properties.deviceName) +
                    " | API " + vkVersionString(properties.apiVersion) +
                    " | vendor=0x" + [&properties]() {
                        std::ostringstream stream;
                        stream << std::hex << properties.vendorID;
                        return stream.str();
                    }()
            );

            if (!hasDeviceExtension(candidate, VK_KHR_SWAPCHAIN_EXTENSION_NAME)) {
                emitLog("WARN", std::string(properties.deviceName) + " has no VK_KHR_swapchain support");
                continue;
            }

            uint32_t familyCount = 0;
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, nullptr);
            std::vector<VkQueueFamilyProperties> families(familyCount);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, &familyCount, families.data());

            QueueFamilies candidateFamilies;
            for (uint32_t index = 0; index < familyCount; ++index) {
                if ((families[index].queueFlags & VK_QUEUE_GRAPHICS_BIT) != 0 &&
                    candidateFamilies.graphics == UINT32_MAX) {
                    candidateFamilies.graphics = index;
                }

                VkBool32 canPresent = VK_FALSE;
                requireVk(
                    vkGetPhysicalDeviceSurfaceSupportKHR(
                        candidate, index, surface_, &canPresent
                    ),
                    "vkGetPhysicalDeviceSurfaceSupportKHR"
                );
                if (canPresent == VK_TRUE && candidateFamilies.present == UINT32_MAX) {
                    candidateFamilies.present = index;
                }
            }

            if (!candidateFamilies.complete()) {
                emitLog(
                    "WARN",
                    std::string(properties.deviceName) +
                        " does not expose both graphics and Android presentation queues"
                );
                continue;
            }

            uint32_t formatCount = 0;
            uint32_t modeCount = 0;
            vkGetPhysicalDeviceSurfaceFormatsKHR(
                candidate, surface_, &formatCount, nullptr
            );
            vkGetPhysicalDeviceSurfacePresentModesKHR(
                candidate, surface_, &modeCount, nullptr
            );

            if (formatCount == 0 || modeCount == 0) {
                emitLog("WARN", std::string(properties.deviceName) + " has no usable surface formats/present modes");
                continue;
            }

            physicalDevice_ = candidate;
            deviceProperties_ = properties;
            queueFamilies_ = candidateFamilies;
            break;
        }

        if (physicalDevice_ == VK_NULL_HANDLE) {
            throw std::runtime_error(
                "No physical device supports graphics, Android presentation and VK_KHR_swapchain"
            );
        }

        emitLog(
            "INFO",
            "Selected GPU: " + std::string(deviceProperties_.deviceName) +
                " | type=" + std::to_string(static_cast<int>(deviceProperties_.deviceType)) +
                " | driverVersion=" + std::to_string(deviceProperties_.driverVersion) +
                " | graphicsQueue=" + std::to_string(queueFamilies_.graphics) +
                " | presentQueue=" + std::to_string(queueFamilies_.present)
        );
        emitLog(
            "INFO",
            "Selected GPU Vulkan API: " + vkVersionString(deviceProperties_.apiVersion)
        );
    }

    void createLogicalDevice() {
        const float queuePriority = 1.0f;
        std::set<uint32_t> uniqueFamilies = {
            queueFamilies_.graphics,
            queueFamilies_.present
        };

        std::vector<VkDeviceQueueCreateInfo> queueCreateInfos;
        for (uint32_t family : uniqueFamilies) {
            VkDeviceQueueCreateInfo queueInfo{};
            queueInfo.sType = VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO;
            queueInfo.queueFamilyIndex = family;
            queueInfo.queueCount = 1;
            queueInfo.pQueuePriorities = &queuePriority;
            queueCreateInfos.push_back(queueInfo);
        }

        const char* deviceExtensions[] = {
            VK_KHR_SWAPCHAIN_EXTENSION_NAME
        };

        VkPhysicalDeviceFeatures enabledFeatures{};
        VkDeviceCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO;
        createInfo.queueCreateInfoCount = static_cast<uint32_t>(queueCreateInfos.size());
        createInfo.pQueueCreateInfos = queueCreateInfos.data();
        createInfo.pEnabledFeatures = &enabledFeatures;
        createInfo.enabledExtensionCount = 1;
        createInfo.ppEnabledExtensionNames = deviceExtensions;

        requireVk(vkCreateDevice(physicalDevice_, &createInfo, nullptr, &device_), "vkCreateDevice");

        vkGetDeviceQueue(device_, queueFamilies_.graphics, 0, &graphicsQueue_);
        vkGetDeviceQueue(device_, queueFamilies_.present, 0, &presentQueue_);

        emitLog("INFO", "Logical device created; graphics and presentation queues acquired");
    }

    void createCommandPool() {
        VkCommandPoolCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO;
        createInfo.flags = VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT;
        createInfo.queueFamilyIndex = queueFamilies_.graphics;

        requireVk(
            vkCreateCommandPool(device_, &createInfo, nullptr, &commandPool_),
            "vkCreateCommandPool"
        );
        emitLog("INFO", "Graphics command pool created");
    }

    void createFrameSync() {
        VkSemaphoreCreateInfo semaphoreInfo{};
        semaphoreInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;
        requireVk(
            vkCreateSemaphore(device_, &semaphoreInfo, nullptr, &imageAvailable_),
            "vkCreateSemaphore(imageAvailable)"
        );

        VkFenceCreateInfo fenceInfo{};
        fenceInfo.sType = VK_STRUCTURE_TYPE_FENCE_CREATE_INFO;
        fenceInfo.flags = VK_FENCE_CREATE_SIGNALED_BIT;
        requireVk(
            vkCreateFence(device_, &fenceInfo, nullptr, &inFlightFence_),
            "vkCreateFence"
        );
    }

    VkSurfaceFormatKHR chooseSurfaceFormat(
        const std::vector<VkSurfaceFormatKHR>& formats
    ) const {
        if (formats.size() == 1 && formats[0].format == VK_FORMAT_UNDEFINED) {
            VkSurfaceFormatKHR selected{};
            selected.format = VK_FORMAT_B8G8R8A8_UNORM;
            selected.colorSpace = formats[0].colorSpace;
            return selected;
        }

        const VkFormat preferredFormats[] = {
            VK_FORMAT_B8G8R8A8_UNORM,
            VK_FORMAT_R8G8B8A8_UNORM,
            VK_FORMAT_B8G8R8A8_SRGB,
            VK_FORMAT_R8G8B8A8_SRGB
        };

        for (VkFormat preferred : preferredFormats) {
            const auto found = std::find_if(
                formats.begin(),
                formats.end(),
                [preferred](const VkSurfaceFormatKHR& format) {
                    return format.format == preferred &&
                        format.colorSpace == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR;
                }
            );
            if (found != formats.end()) return *found;
        }

        return formats.front();
    }

    VkCompositeAlphaFlagBitsKHR chooseCompositeAlpha(
        VkCompositeAlphaFlagsKHR supported
    ) const {
        const VkCompositeAlphaFlagBitsKHR choices[] = {
            VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR,
            VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR,
            VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR
        };

        for (VkCompositeAlphaFlagBitsKHR choice : choices) {
            if ((supported & choice) != 0) return choice;
        }
        throw std::runtime_error("Surface exposes no supported composite alpha mode");
    }

    void createSwapchainResources() {
        VkSurfaceCapabilitiesKHR capabilities{};
        requireVk(
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(
                physicalDevice_, surface_, &capabilities
            ),
            "vkGetPhysicalDeviceSurfaceCapabilitiesKHR"
        );

        if ((capabilities.supportedUsageFlags & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) == 0) {
            throw std::runtime_error("Android surface does not support color-attachment swapchain images");
        }

        uint32_t formatCount = 0;
        requireVk(
            vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice_, surface_, &formatCount, nullptr
            ),
            "vkGetPhysicalDeviceSurfaceFormatsKHR(count)"
        );
        if (formatCount == 0) throw std::runtime_error("Surface returned no formats");

        std::vector<VkSurfaceFormatKHR> formats(formatCount);
        requireVk(
            vkGetPhysicalDeviceSurfaceFormatsKHR(
                physicalDevice_, surface_, &formatCount, formats.data()
            ),
            "vkGetPhysicalDeviceSurfaceFormatsKHR(list)"
        );

        uint32_t modeCount = 0;
        requireVk(
            vkGetPhysicalDeviceSurfacePresentModesKHR(
                physicalDevice_, surface_, &modeCount, nullptr
            ),
            "vkGetPhysicalDeviceSurfacePresentModesKHR(count)"
        );
        std::vector<VkPresentModeKHR> modes(modeCount);
        if (modeCount > 0) {
            requireVk(
                vkGetPhysicalDeviceSurfacePresentModesKHR(
                    physicalDevice_, surface_, &modeCount, modes.data()
                ),
                "vkGetPhysicalDeviceSurfacePresentModesKHR(list)"
            );
        }

        const VkSurfaceFormatKHR surfaceFormat = chooseSurfaceFormat(formats);
        swapchainFormat_ = surfaceFormat.format;

        if (capabilities.currentExtent.width != UINT32_MAX) {
            swapchainExtent_ = capabilities.currentExtent;
        } else {
            const uint32_t requestedWidth = static_cast<uint32_t>(
                std::max(1, ANativeWindow_getWidth(window_))
            );
            const uint32_t requestedHeight = static_cast<uint32_t>(
                std::max(1, ANativeWindow_getHeight(window_))
            );
            swapchainExtent_.width = std::clamp(
                requestedWidth,
                capabilities.minImageExtent.width,
                capabilities.maxImageExtent.width
            );
            swapchainExtent_.height = std::clamp(
                requestedHeight,
                capabilities.minImageExtent.height,
                capabilities.maxImageExtent.height
            );
        }

        uint32_t imageCount = capabilities.minImageCount + 1;
        if (capabilities.maxImageCount > 0 && imageCount > capabilities.maxImageCount) {
            imageCount = capabilities.maxImageCount;
        }

        uint32_t familyIndices[] = {
            queueFamilies_.graphics,
            queueFamilies_.present
        };

        VkSwapchainCreateInfoKHR createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
        createInfo.surface = surface_;
        createInfo.minImageCount = imageCount;
        createInfo.imageFormat = surfaceFormat.format;
        createInfo.imageColorSpace = surfaceFormat.colorSpace;
        createInfo.imageExtent = swapchainExtent_;
        createInfo.imageArrayLayers = 1;
        createInfo.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
        if (queueFamilies_.graphics != queueFamilies_.present) {
            createInfo.imageSharingMode = VK_SHARING_MODE_CONCURRENT;
            createInfo.queueFamilyIndexCount = 2;
            createInfo.pQueueFamilyIndices = familyIndices;
        } else {
            createInfo.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        }
        createInfo.preTransform = capabilities.currentTransform;
        createInfo.compositeAlpha = chooseCompositeAlpha(capabilities.supportedCompositeAlpha);
        createInfo.presentMode = VK_PRESENT_MODE_FIFO_KHR;
        createInfo.clipped = VK_TRUE;
        createInfo.oldSwapchain = VK_NULL_HANDLE;

        requireVk(
            vkCreateSwapchainKHR(device_, &createInfo, nullptr, &swapchain_),
            "vkCreateSwapchainKHR"
        );

        uint32_t actualImageCount = 0;
        requireVk(
            vkGetSwapchainImagesKHR(device_, swapchain_, &actualImageCount, nullptr),
            "vkGetSwapchainImagesKHR(count)"
        );
        swapchainImages_.resize(actualImageCount);
        requireVk(
            vkGetSwapchainImagesKHR(
                device_, swapchain_, &actualImageCount, swapchainImages_.data()
            ),
            "vkGetSwapchainImagesKHR(list)"
        );

        emitLog(
            "INFO",
            "Swapchain created: " + std::to_string(swapchainExtent_.width) + "x" +
                std::to_string(swapchainExtent_.height) +
                " | images=" + std::to_string(actualImageCount) +
                " | format=" + std::to_string(static_cast<int>(swapchainFormat_)) +
                " | present=FIFO"
        );

        createImageViews();
        createRenderPass();
        createGraphicsPipeline();
        createFramebuffers();
        createCommandBuffers();
        createRenderFinishedSemaphores();
    }

    void createImageViews() {
        imageViews_.resize(swapchainImages_.size());

        for (size_t index = 0; index < swapchainImages_.size(); ++index) {
            VkImageViewCreateInfo createInfo{};
            createInfo.sType = VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO;
            createInfo.image = swapchainImages_[index];
            createInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            createInfo.format = swapchainFormat_;
            createInfo.components.r = VK_COMPONENT_SWIZZLE_IDENTITY;
            createInfo.components.g = VK_COMPONENT_SWIZZLE_IDENTITY;
            createInfo.components.b = VK_COMPONENT_SWIZZLE_IDENTITY;
            createInfo.components.a = VK_COMPONENT_SWIZZLE_IDENTITY;
            createInfo.subresourceRange.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            createInfo.subresourceRange.baseMipLevel = 0;
            createInfo.subresourceRange.levelCount = 1;
            createInfo.subresourceRange.baseArrayLayer = 0;
            createInfo.subresourceRange.layerCount = 1;

            requireVk(
                vkCreateImageView(
                    device_, &createInfo, nullptr, &imageViews_[index]
                ),
                "vkCreateImageView"
            );
        }
        emitLog("INFO", "Swapchain image views created");
    }

    void createRenderPass() {
        VkAttachmentDescription colorAttachment{};
        colorAttachment.format = swapchainFormat_;
        colorAttachment.samples = VK_SAMPLE_COUNT_1_BIT;
        colorAttachment.loadOp = VK_ATTACHMENT_LOAD_OP_CLEAR;
        colorAttachment.storeOp = VK_ATTACHMENT_STORE_OP_STORE;
        colorAttachment.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
        colorAttachment.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
        colorAttachment.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        colorAttachment.finalLayout = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;

        VkAttachmentReference colorReference{};
        colorReference.attachment = 0;
        colorReference.layout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;

        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        subpass.colorAttachmentCount = 1;
        subpass.pColorAttachments = &colorReference;

        VkSubpassDependency dependency{};
        dependency.srcSubpass = VK_SUBPASS_EXTERNAL;
        dependency.dstSubpass = 0;
        dependency.srcStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstStageMask = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        dependency.dstAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT;

        VkRenderPassCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO;
        createInfo.attachmentCount = 1;
        createInfo.pAttachments = &colorAttachment;
        createInfo.subpassCount = 1;
        createInfo.pSubpasses = &subpass;
        createInfo.dependencyCount = 1;
        createInfo.pDependencies = &dependency;

        requireVk(
            vkCreateRenderPass(device_, &createInfo, nullptr, &renderPass_),
            "vkCreateRenderPass"
        );
        emitLog("INFO", "Color render pass created");
    }

    std::vector<uint32_t> loadShader(const char* assetPath) {
        AAsset* asset = AAssetManager_open(assets_, assetPath, AASSET_MODE_BUFFER);
        if (asset == nullptr) {
            throw std::runtime_error(
                std::string("Could not open compiled shader asset: ") + assetPath
            );
        }

        const off_t length = AAsset_getLength(asset);
        if (length <= 0 || (length % 4) != 0) {
            AAsset_close(asset);
            throw std::runtime_error(
                std::string("Shader asset has an invalid SPIR-V size: ") + assetPath
            );
        }

        std::vector<uint32_t> code(static_cast<size_t>(length) / sizeof(uint32_t));
        const int bytesRead = AAsset_read(asset, code.data(), static_cast<size_t>(length));
        AAsset_close(asset);

        if (bytesRead != length) {
            throw std::runtime_error(
                std::string("Could not read complete shader asset: ") + assetPath
            );
        }
        return code;
    }

    VkShaderModule createShaderModule(const std::vector<uint32_t>& code) {
        VkShaderModuleCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO;
        createInfo.codeSize = code.size() * sizeof(uint32_t);
        createInfo.pCode = code.data();

        VkShaderModule shaderModule = VK_NULL_HANDLE;
        requireVk(
            vkCreateShaderModule(device_, &createInfo, nullptr, &shaderModule),
            "vkCreateShaderModule"
        );
        return shaderModule;
    }

    void createGraphicsPipeline() {
        const std::vector<uint32_t> vertexCode = loadShader("shaders/triangle.vert.spv");
        const std::vector<uint32_t> fragmentCode = loadShader("shaders/triangle.frag.spv");

        VkShaderModule vertexModule = createShaderModule(vertexCode);
        VkShaderModule fragmentModule = VK_NULL_HANDLE;

        try {
            fragmentModule = createShaderModule(fragmentCode);

            VkPipelineShaderStageCreateInfo vertexStage{};
            vertexStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            vertexStage.stage = VK_SHADER_STAGE_VERTEX_BIT;
            vertexStage.module = vertexModule;
            vertexStage.pName = "main";

            VkPipelineShaderStageCreateInfo fragmentStage{};
            fragmentStage.sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            fragmentStage.stage = VK_SHADER_STAGE_FRAGMENT_BIT;
            fragmentStage.module = fragmentModule;
            fragmentStage.pName = "main";

            VkPipelineShaderStageCreateInfo stages[] = {
                vertexStage,
                fragmentStage
            };

            VkPipelineVertexInputStateCreateInfo vertexInput{};
            vertexInput.sType = VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO;

            VkPipelineInputAssemblyStateCreateInfo inputAssembly{};
            inputAssembly.sType = VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO;
            inputAssembly.topology = VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            inputAssembly.primitiveRestartEnable = VK_FALSE;

            VkPipelineViewportStateCreateInfo viewportState{};
            viewportState.sType = VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO;
            viewportState.viewportCount = 1;
            viewportState.scissorCount = 1;

            VkPipelineRasterizationStateCreateInfo rasterization{};
            rasterization.sType = VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO;
            rasterization.depthClampEnable = VK_FALSE;
            rasterization.rasterizerDiscardEnable = VK_FALSE;
            rasterization.polygonMode = VK_POLYGON_MODE_FILL;
            rasterization.cullMode = VK_CULL_MODE_NONE;
            rasterization.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
            rasterization.depthBiasEnable = VK_FALSE;
            rasterization.lineWidth = 1.0f;

            VkPipelineMultisampleStateCreateInfo multisampling{};
            multisampling.sType = VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO;
            multisampling.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

            VkPipelineColorBlendAttachmentState colorBlendAttachment{};
            colorBlendAttachment.colorWriteMask =
                VK_COLOR_COMPONENT_R_BIT |
                VK_COLOR_COMPONENT_G_BIT |
                VK_COLOR_COMPONENT_B_BIT |
                VK_COLOR_COMPONENT_A_BIT;
            colorBlendAttachment.blendEnable = VK_FALSE;

            VkPipelineColorBlendStateCreateInfo colorBlending{};
            colorBlending.sType = VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO;
            colorBlending.logicOpEnable = VK_FALSE;
            colorBlending.attachmentCount = 1;
            colorBlending.pAttachments = &colorBlendAttachment;

            const VkDynamicState dynamicStates[] = {
                VK_DYNAMIC_STATE_VIEWPORT,
                VK_DYNAMIC_STATE_SCISSOR
            };

            VkPipelineDynamicStateCreateInfo dynamicState{};
            dynamicState.sType = VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO;
            dynamicState.dynamicStateCount = 2;
            dynamicState.pDynamicStates = dynamicStates;

            VkPipelineLayoutCreateInfo layoutInfo{};
            layoutInfo.sType = VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO;
            requireVk(
                vkCreatePipelineLayout(
                    device_, &layoutInfo, nullptr, &pipelineLayout_
                ),
                "vkCreatePipelineLayout"
            );

            VkGraphicsPipelineCreateInfo pipelineInfo{};
            pipelineInfo.sType = VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO;
            pipelineInfo.stageCount = 2;
            pipelineInfo.pStages = stages;
            pipelineInfo.pVertexInputState = &vertexInput;
            pipelineInfo.pInputAssemblyState = &inputAssembly;
            pipelineInfo.pViewportState = &viewportState;
            pipelineInfo.pRasterizationState = &rasterization;
            pipelineInfo.pMultisampleState = &multisampling;
            pipelineInfo.pColorBlendState = &colorBlending;
            pipelineInfo.pDynamicState = &dynamicState;
            pipelineInfo.layout = pipelineLayout_;
            pipelineInfo.renderPass = renderPass_;
            pipelineInfo.subpass = 0;

            requireVk(
                vkCreateGraphicsPipelines(
                    device_,
                    VK_NULL_HANDLE,
                    1,
                    &pipelineInfo,
                    nullptr,
                    &graphicsPipeline_
                ),
                "vkCreateGraphicsPipelines"
            );
        } catch (...) {
            if (fragmentModule != VK_NULL_HANDLE) {
                vkDestroyShaderModule(device_, fragmentModule, nullptr);
            }
            vkDestroyShaderModule(device_, vertexModule, nullptr);
            throw;
        }

        vkDestroyShaderModule(device_, fragmentModule, nullptr);
        vkDestroyShaderModule(device_, vertexModule, nullptr);
        emitLog("INFO", "Graphics pipeline created from precompiled SPIR-V shaders");
    }

    void createFramebuffers() {
        framebuffers_.resize(imageViews_.size());

        for (size_t index = 0; index < imageViews_.size(); ++index) {
            VkImageView attachments[] = { imageViews_[index] };

            VkFramebufferCreateInfo createInfo{};
            createInfo.sType = VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO;
            createInfo.renderPass = renderPass_;
            createInfo.attachmentCount = 1;
            createInfo.pAttachments = attachments;
            createInfo.width = swapchainExtent_.width;
            createInfo.height = swapchainExtent_.height;
            createInfo.layers = 1;

            requireVk(
                vkCreateFramebuffer(
                    device_, &createInfo, nullptr, &framebuffers_[index]
                ),
                "vkCreateFramebuffer"
            );
        }
        emitLog("INFO", "Swapchain framebuffers created");
    }

    void createCommandBuffers() {
        commandBuffers_.resize(framebuffers_.size());

        VkCommandBufferAllocateInfo allocateInfo{};
        allocateInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO;
        allocateInfo.commandPool = commandPool_;
        allocateInfo.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
        allocateInfo.commandBufferCount = static_cast<uint32_t>(commandBuffers_.size());

        requireVk(
            vkAllocateCommandBuffers(device_, &allocateInfo, commandBuffers_.data()),
            "vkAllocateCommandBuffers"
        );
    }

    void createRenderFinishedSemaphores() {
        renderFinished_.resize(swapchainImages_.size());

        VkSemaphoreCreateInfo createInfo{};
        createInfo.sType = VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO;

        for (VkSemaphore& semaphore : renderFinished_) {
            requireVk(
                vkCreateSemaphore(device_, &createInfo, nullptr, &semaphore),
                "vkCreateSemaphore(renderFinished)"
            );
        }
    }

    void recordCommandBuffer(uint32_t imageIndex) {
        VkCommandBuffer commandBuffer = commandBuffers_[imageIndex];

        VkCommandBufferBeginInfo beginInfo{};
        beginInfo.sType = VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO;
        beginInfo.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;

        requireVk(
            vkBeginCommandBuffer(commandBuffer, &beginInfo),
            "vkBeginCommandBuffer"
        );

        VkClearValue clearColor{};
        clearColor.color.float32[0] = 0.025f;
        clearColor.color.float32[1] = 0.035f;
        clearColor.color.float32[2] = 0.075f;
        clearColor.color.float32[3] = 1.0f;

        VkRenderPassBeginInfo renderBegin{};
        renderBegin.sType = VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO;
        renderBegin.renderPass = renderPass_;
        renderBegin.framebuffer = framebuffers_[imageIndex];
        renderBegin.renderArea.offset = {0, 0};
        renderBegin.renderArea.extent = swapchainExtent_;
        renderBegin.clearValueCount = 1;
        renderBegin.pClearValues = &clearColor;

        vkCmdBeginRenderPass(commandBuffer, &renderBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdBindPipeline(
            commandBuffer,
            VK_PIPELINE_BIND_POINT_GRAPHICS,
            graphicsPipeline_
        );

        VkViewport viewport{};
        viewport.x = 0.0f;
        viewport.y = 0.0f;
        viewport.width = static_cast<float>(swapchainExtent_.width);
        viewport.height = static_cast<float>(swapchainExtent_.height);
        viewport.minDepth = 0.0f;
        viewport.maxDepth = 1.0f;
        vkCmdSetViewport(commandBuffer, 0, 1, &viewport);

        VkRect2D scissor{};
        scissor.offset = {0, 0};
        scissor.extent = swapchainExtent_;
        vkCmdSetScissor(commandBuffer, 0, 1, &scissor);

        vkCmdDraw(commandBuffer, 3, 1, 0, 0);
        vkCmdEndRenderPass(commandBuffer);

        requireVk(
            vkEndCommandBuffer(commandBuffer),
            "vkEndCommandBuffer"
        );
    }

    bool recreateSwapchain(int requestedWidth, int requestedHeight, bool force) {
        const int nativeWidth = ANativeWindow_getWidth(window_);
        const int nativeHeight = ANativeWindow_getHeight(window_);

        if (requestedWidth <= 0) requestedWidth = nativeWidth;
        if (requestedHeight <= 0) requestedHeight = nativeHeight;

        std::ostringstream requestDetails;
        requestDetails << "Swapchain resize check: requested="
                       << requestedWidth << "x" << requestedHeight
                       << " | nativeWindow=" << nativeWidth << "x" << nativeHeight
                       << " | currentVulkan=" << swapchainExtent_.width << "x"
                       << swapchainExtent_.height
                       << " | forced=" << (force ? "yes" : "no");
        emitLog("INFO", requestDetails.str());

        if (requestedWidth <= 0 || requestedHeight <= 0 ||
            nativeWidth <= 0 || nativeHeight <= 0) {
            emitLog("WARN", "Swapchain recreation delayed because the native window has zero size");
            return false;
        }

        if (!force &&
            swapchain_ != VK_NULL_HANDLE &&
            static_cast<uint32_t>(requestedWidth) == swapchainExtent_.width &&
            static_cast<uint32_t>(requestedHeight) == swapchainExtent_.height) {
            emitLog(
                "DEBUG",
                "Swapchain recreation skipped; requested size matches the current Vulkan extent"
            );
            return true;
        }

        requireVk(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle(swapchain recreation)");
        cleanupSwapchain();
        createSwapchainResources();

        std::ostringstream resultDetails;
        resultDetails << "Swapchain recreation completed: requested="
                      << requestedWidth << "x" << requestedHeight
                      << " | nativeWindow=" << ANativeWindow_getWidth(window_) << "x"
                      << ANativeWindow_getHeight(window_)
                      << " | Vulkan selected=" << swapchainExtent_.width << "x"
                      << swapchainExtent_.height;
        emitLog("INFO", resultDetails.str());
        return true;
    }

    void renderLoop() {
        using Clock = std::chrono::steady_clock;
        auto sampleStart = Clock::now();
        uint64_t sampleFrames = 0;
        double sampleFrameMs = 0.0;
        bool wasPaused = false;
        bool swapchainOutOfDate = false;
        bool acquireSuboptimalLogged = false;
        bool presentSuboptimalLogged = false;
        auto lastForcedRecreation = Clock::time_point::min();

        while (!stopRequested_.load()) {
            if (paused_.load()) {
                if (!wasPaused) {
                    requireVk(vkDeviceWaitIdle(device_), "vkDeviceWaitIdle(pause)");
                    emitLog("INFO", "Renderer paused; GPU work drained");
                    wasPaused = true;
                }
                std::this_thread::sleep_for(std::chrono::milliseconds(100));
                continue;
            }

            if (wasPaused) {
                sampleStart = Clock::now();
                sampleFrames = 0;
                sampleFrameMs = 0.0;

                emitLog("INFO", "Renderer resumed");
                wasPaused = false;
                requestResize(
                    ANativeWindow_getWidth(window_),
                    ANativeWindow_getHeight(window_)
                );
            }

            if (swapchainOutOfDate) {
                int requestedWidth = 0;
                int requestedHeight = 0;

                if (!takeStableResizeRequest(requestedWidth, requestedHeight)) {
                    if (hasPendingResizeRequest()) {
                        std::this_thread::sleep_for(std::chrono::milliseconds(5));
                        continue;
                    }
                    getLatestResizeRequest(requestedWidth, requestedHeight);
                }

                const auto now = Clock::now();
                if (lastForcedRecreation != Clock::time_point::min() &&
                    now - lastForcedRecreation < std::chrono::milliseconds(100)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(5));
                    continue;
                }

                emitLog(
                    "WARN",
                    "Forcing swapchain recreation after VK_ERROR_OUT_OF_DATE_KHR"
                );
                if (!recreateSwapchain(requestedWidth, requestedHeight, true)) {
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                    continue;
                }

                lastForcedRecreation = Clock::now();
                swapchainOutOfDate = false;
                acquireSuboptimalLogged = false;
                presentSuboptimalLogged = false;
                continue;
            }

            int requestedWidth = 0;
            int requestedHeight = 0;
            if (takeStableResizeRequest(requestedWidth, requestedHeight)) {
                const VkExtent2D previousExtent = swapchainExtent_;
                if (!recreateSwapchain(requestedWidth, requestedHeight, false)) {
                    requestResize(requestedWidth, requestedHeight);
                    std::this_thread::sleep_for(std::chrono::milliseconds(50));
                    continue;
                }

                if (previousExtent.width != swapchainExtent_.width ||
                    previousExtent.height != swapchainExtent_.height) {
                    acquireSuboptimalLogged = false;
                    presentSuboptimalLogged = false;
                }
            }

            const auto frameStart = Clock::now();

            requireVk(
                vkWaitForFences(device_, 1, &inFlightFence_, VK_TRUE, UINT64_MAX),
                "vkWaitForFences"
            );

            uint32_t imageIndex = 0;
            const VkResult acquireResult = vkAcquireNextImageKHR(
                device_,
                swapchain_,
                UINT64_MAX,
                imageAvailable_,
                VK_NULL_HANDLE,
                &imageIndex
            );

            if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
                emitLog(
                    "WARN",
                    "vkAcquireNextImageKHR returned VK_ERROR_OUT_OF_DATE_KHR"
                );
                swapchainOutOfDate = true;
                continue;
            }

            if (acquireResult == VK_SUBOPTIMAL_KHR) {
                if (!acquireSuboptimalLogged) {
                    emitLog(
                        "WARN",
                        "vkAcquireNextImageKHR returned VK_SUBOPTIMAL_KHR; "
                        "the image is usable, continuing until a resize or mandatory recovery"
                    );
                    acquireSuboptimalLogged = true;
                }
            } else if (acquireResult != VK_SUCCESS) {
                requireVk(acquireResult, "vkAcquireNextImageKHR");
            }

            requireVk(
                vkResetFences(device_, 1, &inFlightFence_),
                "vkResetFences"
            );
            requireVk(
                vkResetCommandBuffer(commandBuffers_[imageIndex], 0),
                "vkResetCommandBuffer"
            );
            recordCommandBuffer(imageIndex);

            const VkPipelineStageFlags waitStage =
                VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;

            VkSubmitInfo submitInfo{};
            submitInfo.sType = VK_STRUCTURE_TYPE_SUBMIT_INFO;
            submitInfo.waitSemaphoreCount = 1;
            submitInfo.pWaitSemaphores = &imageAvailable_;
            submitInfo.pWaitDstStageMask = &waitStage;
            submitInfo.commandBufferCount = 1;
            submitInfo.pCommandBuffers = &commandBuffers_[imageIndex];
            submitInfo.signalSemaphoreCount = 1;
            submitInfo.pSignalSemaphores = &renderFinished_[imageIndex];

            requireVk(
                vkQueueSubmit(graphicsQueue_, 1, &submitInfo, inFlightFence_),
                "vkQueueSubmit"
            );

            VkPresentInfoKHR presentInfo{};
            presentInfo.sType = VK_STRUCTURE_TYPE_PRESENT_INFO_KHR;
            presentInfo.waitSemaphoreCount = 1;
            presentInfo.pWaitSemaphores = &renderFinished_[imageIndex];
            presentInfo.swapchainCount = 1;
            presentInfo.pSwapchains = &swapchain_;
            presentInfo.pImageIndices = &imageIndex;

            const VkResult presentResult = vkQueuePresentKHR(presentQueue_, &presentInfo);
            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR) {
                emitLog(
                    "WARN",
                    "vkQueuePresentKHR returned VK_ERROR_OUT_OF_DATE_KHR"
                );
                swapchainOutOfDate = true;
                continue;
            }

            if (presentResult == VK_SUBOPTIMAL_KHR) {
                if (!presentSuboptimalLogged) {
                    emitLog(
                        "WARN",
                        "vkQueuePresentKHR returned VK_SUBOPTIMAL_KHR; "
                        "presentation can continue, waiting for a resize or mandatory recovery"
                    );
                    presentSuboptimalLogged = true;
                }
            } else if (presentResult != VK_SUCCESS) {
                requireVk(presentResult, "vkQueuePresentKHR");
            }

            const auto frameEnd = Clock::now();
            const double frameMs =
                std::chrono::duration<double, std::milli>(frameEnd - frameStart).count();
            sampleFrameMs += frameMs;
            ++sampleFrames;

            const double elapsedSeconds =
                std::chrono::duration<double>(frameEnd - sampleStart).count();

            if (elapsedSeconds >= 5.0) {
                const double fps = static_cast<double>(sampleFrames) / elapsedSeconds;
                const double averageMs =
                    sampleFrames > 0 ? sampleFrameMs / static_cast<double>(sampleFrames) : 0.0;

                std::ostringstream metrics;
                metrics.setf(std::ios::fixed);
                metrics.precision(1);
                metrics << "PERF: fps=" << fps
                        << " | avgCpuFrameMs=" << averageMs
                        << " | frames=" << sampleFrames;
                emitLog("INFO", metrics.str());

                sampleStart = frameEnd;
                sampleFrames = 0;
                sampleFrameMs = 0.0;
            }
        }
    }

    void cleanupSwapchain() {
        if (device_ == VK_NULL_HANDLE) return;

        if (!commandBuffers_.empty() && commandPool_ != VK_NULL_HANDLE) {
            vkFreeCommandBuffers(
                device_,
                commandPool_,
                static_cast<uint32_t>(commandBuffers_.size()),
                commandBuffers_.data()
            );
        }
        commandBuffers_.clear();

        for (VkFramebuffer framebuffer : framebuffers_) {
            if (framebuffer != VK_NULL_HANDLE) {
                vkDestroyFramebuffer(device_, framebuffer, nullptr);
            }
        }
        framebuffers_.clear();

        if (graphicsPipeline_ != VK_NULL_HANDLE) {
            vkDestroyPipeline(device_, graphicsPipeline_, nullptr);
            graphicsPipeline_ = VK_NULL_HANDLE;
        }
        if (pipelineLayout_ != VK_NULL_HANDLE) {
            vkDestroyPipelineLayout(device_, pipelineLayout_, nullptr);
            pipelineLayout_ = VK_NULL_HANDLE;
        }
        if (renderPass_ != VK_NULL_HANDLE) {
            vkDestroyRenderPass(device_, renderPass_, nullptr);
            renderPass_ = VK_NULL_HANDLE;
        }

        for (VkImageView imageView : imageViews_) {
            if (imageView != VK_NULL_HANDLE) {
                vkDestroyImageView(device_, imageView, nullptr);
            }
        }
        imageViews_.clear();
        swapchainImages_.clear();

        for (VkSemaphore semaphore : renderFinished_) {
            if (semaphore != VK_NULL_HANDLE) {
                vkDestroySemaphore(device_, semaphore, nullptr);
            }
        }
        renderFinished_.clear();

        if (swapchain_ != VK_NULL_HANDLE) {
            vkDestroySwapchainKHR(device_, swapchain_, nullptr);
            swapchain_ = VK_NULL_HANDLE;
        }
    }

    void shutdown() {
        if (device_ != VK_NULL_HANDLE) {
            vkDeviceWaitIdle(device_);
            cleanupSwapchain();

            if (imageAvailable_ != VK_NULL_HANDLE) {
                vkDestroySemaphore(device_, imageAvailable_, nullptr);
                imageAvailable_ = VK_NULL_HANDLE;
            }
            if (inFlightFence_ != VK_NULL_HANDLE) {
                vkDestroyFence(device_, inFlightFence_, nullptr);
                inFlightFence_ = VK_NULL_HANDLE;
            }
            if (commandPool_ != VK_NULL_HANDLE) {
                vkDestroyCommandPool(device_, commandPool_, nullptr);
                commandPool_ = VK_NULL_HANDLE;
            }

            vkDestroyDevice(device_, nullptr);
            device_ = VK_NULL_HANDLE;
        }

        if (surface_ != VK_NULL_HANDLE && instance_ != VK_NULL_HANDLE) {
            vkDestroySurfaceKHR(instance_, surface_, nullptr);
            surface_ = VK_NULL_HANDLE;
        }
        if (instance_ != VK_NULL_HANDLE) {
            vkDestroyInstance(instance_, nullptr);
            instance_ = VK_NULL_HANDLE;
        }
    }
};

std::mutex gRendererMutex;
std::unique_ptr<VulkanRenderer> gRenderer;

} // namespace

extern "C" JNIEXPORT jint JNICALL JNI_OnLoad(JavaVM* vm, void*) {
    gJavaVm = vm;

    JNIEnv* env = nullptr;
    if (vm->GetEnv(reinterpret_cast<void**>(&env), JNI_VERSION_1_6) != JNI_OK) {
        return JNI_ERR;
    }

    jclass localClass = env->FindClass("com/livestagix/vulkantest/MainActivity");
    if (localClass == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }

    gActivityClass = static_cast<jclass>(env->NewGlobalRef(localClass));
    env->DeleteLocalRef(localClass);
    if (gActivityClass == nullptr) return JNI_ERR;

    gNativeLogMethod = env->GetStaticMethodID(
        gActivityClass,
        "onNativeLog",
        "(Ljava/lang/String;Ljava/lang/String;)V"
    );
    if (gNativeLogMethod == nullptr) {
        if (env->ExceptionCheck()) env->ExceptionClear();
        return JNI_ERR;
    }

    return JNI_VERSION_1_6;
}

extern "C" JNIEXPORT void JNICALL
Java_com_livestagix_vulkantest_NativeVulkanRenderer_nativeStart(
    JNIEnv* env,
    jobject,
    jobject surface,
    jobject assetManager
) {
    ANativeWindow* window = ANativeWindow_fromSurface(env, surface);
    if (window == nullptr) {
        emitLog("ERROR", "ANativeWindow_fromSurface returned null");
        return;
    }

    AAssetManager* assets = AAssetManager_fromJava(env, assetManager);
    if (assets == nullptr) {
        ANativeWindow_release(window);
        emitLog("ERROR", "AAssetManager_fromJava returned null");
        return;
    }

    std::lock_guard<std::mutex> lock(gRendererMutex);
    if (gRenderer != nullptr) {
        gRenderer->stop();
        gRenderer.reset();
    }

    gRenderer = std::make_unique<VulkanRenderer>(window, assets);
    gRenderer->start();
}

extern "C" JNIEXPORT void JNICALL
Java_com_livestagix_vulkantest_NativeVulkanRenderer_nativeResize(
    JNIEnv*,
    jobject,
    jint width,
    jint height
) {
    std::lock_guard<std::mutex> lock(gRendererMutex);
    if (gRenderer != nullptr) gRenderer->requestResize(width, height);
}

extern "C" JNIEXPORT void JNICALL
Java_com_livestagix_vulkantest_NativeVulkanRenderer_nativeSetPaused(
    JNIEnv*,
    jobject,
    jboolean paused
) {
    std::lock_guard<std::mutex> lock(gRendererMutex);
    if (gRenderer != nullptr) gRenderer->setPaused(paused == JNI_TRUE);
}

extern "C" JNIEXPORT void JNICALL
Java_com_livestagix_vulkantest_NativeVulkanRenderer_nativeStop(
    JNIEnv*,
    jobject
) {
    std::lock_guard<std::mutex> lock(gRendererMutex);
    if (gRenderer != nullptr) {
        gRenderer->stop();
        gRenderer.reset();
    }
}
