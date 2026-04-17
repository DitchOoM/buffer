cmake_minimum_required(VERSION 3.10)
project(buffer_jni C)

add_library(buffer_jni SHARED buffer_jni.c)

# Minimal size: hide all symbols except JNI exports, optimize for size
target_compile_options(buffer_jni PRIVATE -Oz -fvisibility=hidden)
target_link_options(buffer_jni PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)

# No C++ runtime needed — pure C JNI
set_target_properties(buffer_jni PROPERTIES LINKER_LANGUAGE C)
