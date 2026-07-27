cmake_minimum_required(VERSION 3.22.1)
project(pty_bridge)

add_library(pty_bridge SHARED pty_bridge.c)
target_link_libraries(pty_bridge log)
