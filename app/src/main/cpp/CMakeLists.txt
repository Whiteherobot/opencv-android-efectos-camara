cmake_minimum_required(VERSION 3.22.1)
project("practica21")

# OpenCV Android SDK: la variable de entorno OPENCV_ANDROID_SDK manda; si no
# está, se busca junto a la carpeta del proyecto (../opencv-android-sdk).
if(DEFINED ENV{OPENCV_ANDROID_SDK})
    set(OpenCV_DIR "$ENV{OPENCV_ANDROID_SDK}/sdk/native/jni")
else()
    set(OpenCV_DIR "${CMAKE_SOURCE_DIR}/../../../../../opencv-android-sdk/sdk/native/jni")
endif()
find_package(OpenCV REQUIRED)

add_library(${CMAKE_PROJECT_NAME} SHARED native-lib.cpp)

find_library(log-lib     log)
find_library(android-lib android)
find_library(jnigraphics-lib jnigraphics)

target_link_libraries(
    ${CMAKE_PROJECT_NAME}
    ${OpenCV_LIBS}
    ${log-lib}
    ${android-lib}
    ${jnigraphics-lib}
)
