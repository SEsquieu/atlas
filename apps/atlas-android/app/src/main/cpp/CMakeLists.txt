cmake_minimum_required(VERSION 3.22)
project(atlas_workspace_js LANGUAGES C CXX)
include(FetchContent)
# Content-addressed upstream source; no qjs-libc, CLI, shell, os or std modules.
set(QJS_ENABLE_INSTALL OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_LIBC OFF CACHE BOOL "" FORCE)
set(QJS_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_Declare(quickjs
    GIT_REPOSITORY https://github.com/quickjs-ng/quickjs.git
    GIT_TAG 6d46d07d04041b40f4f49eaa7fdebe44c314c699
    GIT_PROGRESS FALSE)
FetchContent_MakeAvailable(quickjs)
if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()
add_library(atlas_workspace_js SHARED workspace_engine.cpp)
target_compile_features(atlas_workspace_js PRIVATE cxx_std_17)
target_include_directories(atlas_workspace_js PRIVATE ${quickjs_SOURCE_DIR} ${JNI_INCLUDE_DIRS})
target_link_libraries(atlas_workspace_js PRIVATE qjs)
