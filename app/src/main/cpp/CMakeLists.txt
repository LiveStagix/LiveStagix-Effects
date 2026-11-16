cmake_minimum_required(VERSION 3.22.1)

project(livestagix_vulkan_lab LANGUAGES CXX)

add_library(
    vulkan_test
    SHARED
    vulkan_renderer.cpp
)

target_compile_features(vulkan_test PRIVATE cxx_std_17)
target_compile_options(vulkan_test PRIVATE -Wall -Wextra)

find_library(android-lib android)
find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(
    vulkan_test
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)
