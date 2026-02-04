cmake_minimum_required(VERSION 3.18.1)
project("melspectrogram")

# Include Eigen library
include_directories(${CMAKE_CURRENT_SOURCE_DIR})

# Create shared library
add_library(
        melspectrogram
        SHARED
        jni_bridge.cpp
)

# Link with Android log library
find_library(log-lib log)
target_link_libraries(melspectrogram ${log-lib})