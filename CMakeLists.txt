cmake_minimum_required(VERSION 3.22.1)
project("retrobridge")

add_library(
    retrobridge
    SHARED
    libretro_bridge.cpp
)

find_library(log-lib log)

target_link_libraries(
    retrobridge
    ${log-lib}
    android
    dl
)
