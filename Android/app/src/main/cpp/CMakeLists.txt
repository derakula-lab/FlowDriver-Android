cmake_minimum_required(VERSION 3.22.1)
project("xvpn")

add_library(fdhelper SHARED fdhelper.c)
find_library(log-lib log)
target_link_libraries(fdhelper ${log-lib})