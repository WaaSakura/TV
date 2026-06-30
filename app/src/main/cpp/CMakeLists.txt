cmake_minimum_required(VERSION 3.4.1)
project("yaxinnode")

# JNI bridge that boots the embedded Node server (nodejs-mobile).
add_library(native-lib SHARED native-lib.cpp)

# Prebuilt libnode.so from a nodejs-mobile release, per ABI, placed under:
#   app/src/main/jniLibs/<ABI>/libnode.so
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

# Node headers from the same nodejs-mobile release, placed under:
#   app/src/main/cpp/nodejs-mobile/include/node/
include_directories(${CMAKE_SOURCE_DIR}/nodejs-mobile/include/node)

find_library(log-lib log)
target_link_libraries(native-lib libnode ${log-lib})
