cmake_minimum_required(VERSION 3.22.1)
project(hexrdp_native)

# ════════════════════════════════════════════════════════════════════════
# aFreeRDP / FreeRDP native bridge
# ════════════════════════════════════════════════════════════════════════

set(CMAKE_C_STANDARD 11)
set(FREERDP_DIR ${CMAKE_CURRENT_SOURCE_DIR}/FreeRDP)

# ── تحديد مسار OpenSSL لكل ABI ──────────────────────────────────────────
# ANDROID_OPENSSL_ROOT يُمرَّر من build.gradle.kts كـ -D argument.
# نحسب المسار الكامل لكل ABI ثم نُعيّن OPENSSL_* كـ CACHE vars
# حتى يجدها winpr و libfreerdp عند استدعاء find_package(OpenSSL).
#
# ملاحظة مهمة: NDK toolchain يُعيَّن بعد CMakeLists.txt ويُعيد ضبط
# CMAKE_FIND_ROOT_PATH_MODE_PACKAGE. لذلك الحل الوحيد الموثوق هو تمرير
# OPENSSL_CRYPTO_LIBRARY و OPENSSL_SSL_LIBRARY و OPENSSL_INCLUDE_DIR
# مباشرةً كـ -D arguments من Gradle (انظر build.gradle.kts) — هذا يُدخلها
# في cmake cache مباشرة قبل أي processing ويتجاوز مشكلة الـ toolchain.
if(DEFINED ANDROID_OPENSSL_ROOT AND NOT ANDROID_OPENSSL_ROOT STREQUAL "")
    set(_OPENSSL_ABI_DIR "${ANDROID_OPENSSL_ROOT}/openssl-${ANDROID_ABI}")
    message(STATUS "HexRDP: Using OpenSSL from ${_OPENSSL_ABI_DIR}")

    # نتحقق من وجود الملف فعلاً قبل المتابعة
    if(NOT EXISTS "${_OPENSSL_ABI_DIR}/lib/libcrypto.a")
        message(FATAL_ERROR "HexRDP: libcrypto.a not found at ${_OPENSSL_ABI_DIR}/lib/libcrypto.a")
    endif()

    # تعيين OPENSSL_ROOT_DIR فقط — find_package(OpenSSL) سيشتق منه الباقي
    # تعيينها كـ CACHE INTERNAL تجعلها مرئية لكل subdirectory
    set(OPENSSL_ROOT_DIR       "${_OPENSSL_ABI_DIR}"                    CACHE INTERNAL "")
    set(OPENSSL_INCLUDE_DIR    "${_OPENSSL_ABI_DIR}/include"            CACHE INTERNAL "")
    set(OPENSSL_CRYPTO_LIBRARY "${_OPENSSL_ABI_DIR}/lib/libcrypto.a"    CACHE INTERNAL "")
    set(OPENSSL_SSL_LIBRARY    "${_OPENSSL_ABI_DIR}/lib/libssl.a"       CACHE INTERNAL "")

    # إضافة المسار إلى CMAKE_PREFIX_PATH (يعمل مع FIND_ROOT_PATH_MODE_PACKAGE=BOTH)
    list(APPEND CMAKE_PREFIX_PATH "${_OPENSSL_ABI_DIR}")
    # فرض وضع البحث لمنع NDK toolchain من تقييد find_package داخل sysroot فقط
    set(CMAKE_FIND_ROOT_PATH_MODE_PACKAGE BOTH CACHE STRING "" FORCE)
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

    # تعطيل FFmpeg بشكل كامل — libfreerdp/CMakeLists.txt يستدعي
    # find_package(SWScale REQUIRED) عندما يكون WITH_FFMPEG مفعّلاً.
    # SWScale لا يتوفر في NDK sysroot ويسبب فشل الـ build.
    set(WITH_FFMPEG OFF CACHE BOOL "" FORCE)
    # منع أي استدعاء find_package لـ FFmpeg أو SWScale
    set(CMAKE_DISABLE_FIND_PACKAGE_FFmpeg   TRUE CACHE BOOL "" FORCE)
    set(CMAKE_DISABLE_FIND_PACKAGE_SWScale  TRUE CACHE BOOL "" FORCE)
    set(CMAKE_DISABLE_FIND_PACKAGE_SWResample TRUE CACHE BOOL "" FORCE)
    set(CMAKE_DISABLE_FIND_PACKAGE_AVCodec  TRUE CACHE BOOL "" FORCE)
    set(CMAKE_DISABLE_FIND_PACKAGE_AVUtil   TRUE CACHE BOOL "" FORCE)
    set(CMAKE_DISABLE_FIND_PACKAGE_AVFormat TRUE CACHE BOOL "" FORCE)

    set(WITH_CUPS OFF CACHE BOOL "" FORCE)
    set(WITH_PULSE OFF CACHE BOOL "" FORCE)
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
