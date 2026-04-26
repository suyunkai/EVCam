cmake_minimum_required(VERSION 3.22.1)

project("evcam_gles_compositor")

add_library(
        evcam_gles_compositor
        SHARED
        evcam_gles_compositor.cpp)

find_library(log-lib log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)
find_library(egl-lib EGL)
find_library(gles-lib GLESv2)

target_compile_features(evcam_gles_compositor PRIVATE cxx_std_17)

target_include_directories(evcam_gles_compositor PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(
        evcam_gles_compositor
        ${log-lib}
        ${android-lib}
        ${jnigraphics-lib}
        ${egl-lib}
        ${gles-lib})
