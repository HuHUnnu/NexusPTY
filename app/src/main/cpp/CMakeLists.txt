cmake_minimum_required(VERSION 3.22.1)
project(nexuspty LANGUAGES C)

add_library(nexuspty SHARED pty_jni.c)

find_library(log-lib log)
target_link_libraries(nexuspty ${log-lib})
