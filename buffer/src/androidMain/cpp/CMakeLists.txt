cmake_minimum_required(VERSION 3.22.1)
project(ditchoom_buffer_jni LANGUAGES CXX)

add_library(ditchoom_buffer_jni SHARED buffer_jni.cpp)

set_target_properties(ditchoom_buffer_jni PROPERTIES
    CXX_STANDARD 17
    CXX_STANDARD_REQUIRED ON
)

# Optimize for size + strip unreferenced sections + drop unwind tables we don't
# need for a single-function leaf wrapper. AGP strips the produced .so again at
# APK packaging time, but starting from a smaller artifact keeps the AAR slim.
target_compile_options(ditchoom_buffer_jni PRIVATE
    -Os
    -fdata-sections
    -ffunction-sections
    -fno-unwind-tables
    -fno-asynchronous-unwind-tables
    -fvisibility=hidden
)
target_link_options(ditchoom_buffer_jni PRIVATE
    -Wl,--gc-sections
    -Wl,--strip-all
    -Wl,--exclude-libs,ALL
)
