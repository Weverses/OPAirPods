cmake_minimum_required(VERSION 3.22.1)
project(colorpods LANGUAGES C CXX)
set(CMAKE_CXX_STANDARD 20)
add_library(colorpods SHARED vendor_hook.cpp head_tracker.cpp xz/xz_crc32.c xz/xz_crc64.c
    xz/xz_sha256.c xz/xz_dec_stream.c xz/xz_dec_lzma2.c xz/xz_dec_bcj.c)
target_include_directories(colorpods PRIVATE xz)
target_compile_definitions(colorpods PRIVATE XZ_DEC_ARM64 XZ_DEC_ANY_CHECK XZ_USE_CRC64 XZ_USE_SHA256)
target_compile_options(colorpods PRIVATE -Wall -Wextra -fvisibility=hidden)
target_link_libraries(colorpods log)
