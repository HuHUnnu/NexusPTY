cmake_minimum_required(VERSION 3.22.1)
project(nexuspty LANGUAGES C)

add_library(educore SHARED pty_jni.c)

find_library(log-lib log)
target_link_libraries(educore ${log-lib})
