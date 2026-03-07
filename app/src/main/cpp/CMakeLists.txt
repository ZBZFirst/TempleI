cmake_minimum_required(VERSION 3.22.1)

project(templei_native_stream)

add_library(templei_mux SHARED
    templei_mux_stub.cpp
)

add_library(templei_srt SHARED
    templei_srt_stub.cpp
)

find_library(log-lib log)
find_library(dl-lib dl)

target_link_libraries(templei_mux ${log-lib})
target_link_libraries(templei_srt ${log-lib} ${dl-lib})
