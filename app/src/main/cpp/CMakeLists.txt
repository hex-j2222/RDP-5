cmake_minimum_required(VERSION 3.22.1)
project(hexrdp_native)

# ════════════════════════════════════════════════════════════════════════
# aFreeRDP / FreeRDP native bridge
#
# This builds a thin JNI bridge (hexrdp_jni.c) against the official FreeRDP
# client library, checked out as a git submodule at
# `app/src/main/cpp/FreeRDP` (see SETUP.md in this directory for the exact
# `git submodule add` command).
#
# This file is NOT built by Anthropic's sandboxed build environment — it has
# no internet access and cannot run the NDK toolchain or fetch the FreeRDP
# source. It is provided ready-to-build on your own machine, where Android
# Studio + the NDK + CMake are installed normally. See SETUP.md for the
# step-by-step local build instructions.
# ════════════════════════════════════════════════════════════════════════

set(CMAKE_C_STANDARD 11)
set(FREERDP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP)

# Only build the native bridge if the FreeRDP submodule has actually been
# checked out. This lets the rest of the Gradle project (VNC, SSH, pure-
# Kotlin RDP fallback) configure and build fine even before you've pulled
# the submodule, instead of hard-failing the whole project.
if(EXISTS "${FREERDP_DIR}/CMakeLists.txt")
    message(STATUS "HexRDP: FreeRDP submodule found, building native aFreeRDP bridge")

    # FreeRDP build options tuned for a minimal mobile client:
    # client/server libs we don't need are disabled to keep the .so small
    # and the build fast.
    set(WITH_CLIENT ON CACHE BOOL "" FORCE)
    set(WITH_SERVER OFF CACHE BOOL "" FORCE)
    set(WITH_SHADOW OFF CACHE BOOL "" FORCE)
    set(WITH_PLATFORM_SERVER OFF CACHE BOOL "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(WITH_SAMPLE OFF CACHE BOOL "" FORCE)
    set(CHANNEL_URBDRC OFF CACHE BOOL "" FORCE)
    set(WITH_FFMPEG OFF CACHE BOOL "" FORCE)
    set(WITH_CUPS OFF CACHE BOOL "" FORCE)
    set(WITH_PULSE OFF CACHE BOOL "" FORCE)
    set(WITH_X11 OFF CACHE BOOL "" FORCE)
    set(WITH_WAYLAND OFF CACHE BOOL "" FORCE)

    add_subdirectory(${FREERDP_DIR} freerdp_build)

    add_library(hexrdp_jni SHARED hexrdp_jni.c)

    target_include_directories(hexrdp_jni PRIVATE
        ${FREERDP_DIR}/include
        ${CMAKE_CURRENT_BINARY_DIR}/freerdp_build/include
        ${FREERDP_DIR}/winpr/include
        ${CMAKE_CURRENT_BINARY_DIR}/freerdp_build/winpr/include
    )

    target_link_libraries(hexrdp_jni
        freerdp
        freerdp-client
        winpr
        android
        log
    )
else()
    message(STATUS "HexRDP: FreeRDP submodule NOT found at ${FREERDP_DIR} — "
                    "skipping native aFreeRDP bridge. The app will still build "
                    "and run using the pure-Kotlin RDP client as a fallback, "
                    "plus VNC and SSH. Run the commands in SETUP.md to add "
                    "the submodule and rebuild for native aFreeRDP support.")
endif()
