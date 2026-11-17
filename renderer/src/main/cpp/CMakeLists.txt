cmake_minimum_required(VERSION 3.22.1)

project(livestagix_renderer LANGUAGES CXX)

add_library(
    livestagix_renderer
    SHARED
    vulkan_renderer.cpp
)

target_compile_features(livestagix_renderer PRIVATE cxx_std_17)
target_compile_options(livestagix_renderer PRIVATE -Wall -Wextra)

find_library(android-lib android)
find_library(log-lib log)
find_library(vulkan-lib vulkan)

target_link_libraries(
    livestagix_renderer
    PRIVATE
    ${android-lib}
    ${log-lib}
    ${vulkan-lib}
)
