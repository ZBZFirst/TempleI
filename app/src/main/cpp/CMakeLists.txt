cmake_minimum_required(VERSION 3.22.1)

project(templei_native_stream)

add_library(templei_mux SHARED
    templei_mux_stub.cpp
)

add_library(templei_srt SHARED
    templei_srt_stub.cpp
)

add_library(templei_ffmpeg SHARED
    templei_ffmpeg_stub.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

set(FFMPEG_HEADERS_DIR ${CMAKE_CURRENT_SOURCE_DIR}/third_party/ffmpeg/include)
if(EXISTS ${FFMPEG_HEADERS_DIR})
    target_include_directories(templei_ffmpeg PRIVATE ${FFMPEG_HEADERS_DIR})
endif()

set(FFMPEG_PREBUILT_DIR ${CMAKE_SOURCE_DIR}/src/main/jniLibs/${ANDROID_ABI})
add_library(ffmpeg_avformat SHARED IMPORTED)
set_target_properties(ffmpeg_avformat PROPERTIES IMPORTED_LOCATION ${FFMPEG_PREBUILT_DIR}/libavformat.so)
add_library(ffmpeg_avcodec SHARED IMPORTED)
set_target_properties(ffmpeg_avcodec PROPERTIES IMPORTED_LOCATION ${FFMPEG_PREBUILT_DIR}/libavcodec.so)
add_library(ffmpeg_avutil SHARED IMPORTED)
set_target_properties(ffmpeg_avutil PROPERTIES IMPORTED_LOCATION ${FFMPEG_PREBUILT_DIR}/libavutil.so)
add_library(ffmpeg_swresample SHARED IMPORTED)
set_target_properties(ffmpeg_swresample PROPERTIES IMPORTED_LOCATION ${FFMPEG_PREBUILT_DIR}/libswresample.so)

target_link_libraries(templei_mux ${log-lib})
target_link_libraries(templei_srt ${log-lib} ${dl-lib})
target_link_libraries(
    templei_ffmpeg
    ${log-lib}
    ${dl-lib}
    ffmpeg_avformat
    ffmpeg_avcodec
    ffmpeg_avutil
    ffmpeg_swresample
)
