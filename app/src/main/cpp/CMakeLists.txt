cmake_minimum_required(VERSION 3.22.1)
project(hexrdp_native)

# ════════════════════════════════════════════════════════════════════════
# aFreeRDP / FreeRDP native bridge
# ════════════════════════════════════════════════════════════════════════

set(CMAKE_C_STANDARD 11)
set(FREERDP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP)

# ── تحديد مسار OpenSSL لكل ABI ──────────────────────────────────────────
# ANDROID_OPENSSL_ROOT يُمرَّر من build.gradle.kts (قيمته مجلد يحوي
# openssl-arm64-v8a/ و openssl-x86_64/ وما إلى ذلك).
# نستخدم ANDROID_ABI (يضبطه NDK toolchain تلقائياً) لاختيار المسار الصحيح.
if(DEFINED ANDROID_OPENSSL_ROOT AND NOT ANDROID_OPENSSL_ROOT STREQUAL "")
    set(OPENSSL_ROOT_DIR "${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}")
    message(STATUS "HexRDP: Using OpenSSL from ${OPENSSL_ROOT_DIR}")
    set(OPENSSL_INCLUDE_DIR "${OPENSSL_ROOT_DIR}/include")
    set(OPENSSL_CRYPTO_LIBRARY "${OPENSSL_ROOT_DIR}/lib/libcrypto.a")
    set(OPENSSL_SSL_LIBRARY    "${OPENSSL_ROOT_DIR}/lib/libssl.a")
endif()

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
    # يمنع ConfigOptions.cmake في FreeRDP من تشغيل find_package(PulseAudio)
    # حتى كـ optional probe — على Android لا وجود لـ PulseAudio أساساً،
    # فالنتيجة كانت تحذير CMake غير مؤثر على نجاح البناء، لكن إخفاؤه أنظف.
    set(CMAKE_DISABLE_FIND_PACKAGE_PulseAudio TRUE CACHE BOOL "" FORCE)
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
