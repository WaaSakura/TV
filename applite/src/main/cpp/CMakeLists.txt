cmake_minimum_required(VERSION 3.4.1)
project("yaxinnode")

add_library(native-lib SHARED native-lib.cpp)

# libnode.so (per ABI) from a nodejs-mobile release, in app/src/main/jniLibs/<abi>/
add_library(libnode SHARED IMPORTED)
set_target_properties(libnode PROPERTIES IMPORTED_LOCATION
        ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libnode.so)

# node headers from the same release, in app/src/main/cpp/nodejs-mobile/include/node/
include_directories(${CMAKE_SOURCE_DIR}/nodejs-mobile/include/node)

find_library(log-lib log)
target_link_libraries(native-lib libnode ${log-lib})
