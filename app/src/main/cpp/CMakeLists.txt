cmake_minimum_required(VERSION 3.22.1)
project(hexrdp_native)

set(CMAKE_C_STANDARD 11)

# ════════════════════════════════════════════════════════════════════════
# HexRDP JNI Bridge
# ════════════════════════════════════════════════════════════════════════
#
# الاستراتيجية: نستخدم FreeRDP و OpenSSL كـ prebuilt libraries جاهزة.
# FreeRDP يُبنى مسبقاً في CI عبر cmake مباشرة (انظر main.yml خطوة
# "Build FreeRDP prebuilt"). هذا يتجنب تماماً مشاكل add_subdirectory
# مع NDK toolchain (SWScale REQUIRED، OpenSSL scope، إلخ).

set(FREERDP_DIR    ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP)
set(FREERDP_PREBUILT_ROOT ${CMAKE_CURRENT_SOURCE_DIR}/freerdp-prebuilt)
set(FREERDP_ABI_DIR ${FREERDP_PREBUILT_ROOT}/${ANDROID_ABI})

# OpenSSL prebuilt
if(DEFINED ANDROID_OPENSSL_ROOT AND NOT ANDROID_OPENSSL_ROOT STREQUAL "")
    set(OPENSSL_ABI_DIR "${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}")
else()
    message(FATAL_ERROR "ANDROID_OPENSSL_ROOT is required")
endif()

# ── الحالة 1: FreeRDP prebuilt موجود → بناء JNI bridge كامل ──────────
if(EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.so" OR
   EXISTS "${FREERDP_ABI_DIR}/lib/libfreerdp3.a")

    message(STATUS "HexRDP: FreeRDP prebuilt found at ${FREERDP_ABI_DIR}")

    # OpenSSL
    add_library(OpenSSL::Crypto STATIC IMPORTED)
    set_target_properties(OpenSSL::Crypto PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libcrypto.a")

    add_library(OpenSSL::SSL STATIC IMPORTED)
    set_target_properties(OpenSSL::SSL PROPERTIES
        IMPORTED_LOCATION "${OPENSSL_ABI_DIR}/lib/libssl.a")

    # WinPR — السكربت الرسمي يبني .so
    add_library(winpr SHARED IMPORTED)
    set_target_properties(winpr PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libwinpr3.so")

    # FreeRDP
    add_library(freerdp SHARED IMPORTED)
    set_target_properties(freerdp PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp3.so")

    add_library(freerdp-client SHARED IMPORTED)
    set_target_properties(freerdp-client PROPERTIES
        IMPORTED_LOCATION "${FREERDP_ABI_DIR}/lib/libfreerdp-client3.so")

    # JNI bridge
    add_library(hexrdp_jni SHARED hexrdp_jni.c)

    target_include_directories(hexrdp_jni PRIVATE
        ${FREERDP_ABI_DIR}/include
        ${OPENSSL_ABI_DIR}/include
    )

    target_link_libraries(hexrdp_jni
        freerdp-client
        freerdp
        winpr
        OpenSSL::SSL
        OpenSSL::Crypto
        android
        log
        z
    )

# ── الحالة 2: لا prebuilt → skip silently, Kotlin fallback يعمل ─────────
else()
    message(STATUS "HexRDP: FreeRDP prebuilt NOT found at ${FREERDP_ABI_DIR}")
    message(STATUS "HexRDP: Skipping native bridge — Kotlin RDP client will be used")
    message(STATUS "HexRDP: Run CI to build FreeRDP prebuilt automatically")
endif()
