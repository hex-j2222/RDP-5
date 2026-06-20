/*
 * hexrdp_jni.c — JNI bridge between Kotlin (com.gotohex.rdp.rdp.native.AFreeRdpBridge)
 * and the FreeRDP client library (libfreerdp / libfreerdp-client).
 *
 * Compatible: FreeRDP 3.x (tested 3.24.x)
 *
 * Changes vs original:
 *  - Added (void) casts for freerdp_settings_set_* [[nodiscard]] return values (FreeRDP 3.23+)
 *  - Added freerdp_context_new() return value check
 *  - Added PIXEL_FORMAT_BGRA32 pixel-format guard (FreeRDP 3.x uses pixel_format.h)
 *  - Proper NULL check before update callback assignment
 */

#include <jni.h>
#include <stdlib.h>
#include <string.h>
#include <android/log.h>

#include <freerdp/freerdp.h>
#include <freerdp/client/cmdline.h>
#include <freerdp/gdi/gdi.h>
#include <freerdp/channels/channels.h>
#include <freerdp/codec/color.h>
#include <winpr/synch.h>

#ifndef PIXEL_FORMAT_BGRA32
#define PIXEL_FORMAT_BGRA32 PIXEL_FORMAT_BGRA32_VER
#endif

#define TAG "hexrdp_jni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)

typedef struct
{
    rdpContext context;
    JavaVM* jvm;
    jobject bridgeObjGlobalRef;
    jmethodID onFrameMethod;
    jmethodID onStateMethod;
    jmethodID onErrorMethod;
} hexrdpContext;

#define HEXRDP_CTX(inst) ((hexrdpContext*)(inst)->context)

/* ── Callbacks invoked by FreeRDP's core on graphics updates ──────────── */

static BOOL hexrdp_on_frame(rdpContext* context, const RECTANGLE_16* rect)
{
    hexrdpContext* hctx = (hexrdpContext*)context;
    rdpGdi* gdi = context->gdi;
    if (!gdi || !gdi->primary_buffer)
        return TRUE;

    JNIEnv* env;
    if ((*hctx->jvm)->GetEnv(hctx->jvm, (void**)&env, JNI_VERSION_1_6) != JNI_OK)
        return TRUE;

    int x = rect ? rect->left : 0;
    int y = rect ? rect->top : 0;
    int w = rect ? (rect->right - rect->left) : (int)gdi->width;
    int h = rect ? (rect->bottom - rect->top) : (int)gdi->height;
    if (w <= 0 || h <= 0)
        return TRUE;

    jintArray pixels = (*env)->NewIntArray(env, w * h);
    if (!pixels)
        return TRUE;

    jint* buf = (*env)->GetIntArrayElements(env, pixels, NULL);
    const UINT32 stride = gdi->stride;
    const BYTE* src = gdi->primary_buffer;
    for (int row = 0; row < h; row++)
    {
        const UINT32* srcRow = (const UINT32*)(src + (size_t)(y + row) * stride) + x;
        memcpy(buf + (size_t)row * w, srcRow, (size_t)w * sizeof(jint));
    }
    (*env)->ReleaseIntArrayElements(env, pixels, buf, 0);

    (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onFrameMethod,
                            x, y, w, h, pixels,
                            (jboolean)(rect == NULL));
    (*env)->DeleteLocalRef(env, pixels);
    return TRUE;
}

/* ── FreeRDP lifecycle callbacks ────────────────────────────────────────── */

static BOOL hexrdp_pre_connect(freerdp* instance)
{
    rdpSettings* settings = instance->context->settings;
    (void)freerdp_settings_set_bool(settings, FreeRDP_SoftwareGdi, TRUE);
    return TRUE;
}

static BOOL hexrdp_post_connect(freerdp* instance)
{
    if (!gdi_init(instance, PIXEL_FORMAT_BGRA32))
        return FALSE;

    rdpUpdate* update = instance->context->update;
    if (update)
        update->EndPaint = NULL;
    return TRUE;
}

static void hexrdp_post_disconnect(freerdp* instance)
{
    gdi_free(instance);
}

/* ── JNI exported functions ─────────────────────────────────────────────── */

JNIEXPORT jlong JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeInit(JNIEnv* env, jobject thiz)
{
    freerdp* instance = freerdp_new();
    if (!instance)
        return 0;

    instance->ContextSize = sizeof(hexrdpContext);
    instance->ContextNew  = NULL;
    instance->ContextFree = NULL;

    /* freerdp_context_new returns BOOL in FreeRDP 3.x */
    if (!freerdp_context_new(instance))
    {
        freerdp_free(instance);
        return 0;
    }

    hexrdpContext* hctx = HEXRDP_CTX(instance);
    (*env)->GetJavaVM(env, &hctx->jvm);
    hctx->bridgeObjGlobalRef = (*env)->NewGlobalRef(env, thiz);

    jclass cls = (*env)->GetObjectClass(env, thiz);
    hctx->onFrameMethod = (*env)->GetMethodID(env, cls, "onNativeFrame", "(IIII[IZ)V");
    hctx->onStateMethod = (*env)->GetMethodID(env, cls, "onNativeState", "(I)V");
    hctx->onErrorMethod = (*env)->GetMethodID(env, cls, "onNativeError", "(Ljava/lang/String;)V");

    instance->PreConnect    = hexrdp_pre_connect;
    instance->PostConnect   = hexrdp_post_connect;
    instance->PostDisconnect = hexrdp_post_disconnect;

    return (jlong)(intptr_t)instance;
}

JNIEXPORT jboolean JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeConnect(
    JNIEnv* env, jobject thiz, jlong handle,
    jstring jHost, jint jPort, jstring jUsername, jstring jPassword, jstring jDomain,
    jint jWidth, jint jHeight, jboolean jUseNla,
    jboolean jGatewayEnabled, jstring jGwHost, jint jGwPort, jstring jGwUser, jstring jGwPass, jstring jGwDomain)
{
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return JNI_FALSE;
    rdpSettings* settings = instance->context->settings;

    const char* host   = (*env)->GetStringUTFChars(env, jHost,     NULL);
    const char* user   = (*env)->GetStringUTFChars(env, jUsername, NULL);
    const char* pass   = (*env)->GetStringUTFChars(env, jPassword, NULL);
    const char* domain = (*env)->GetStringUTFChars(env, jDomain,   NULL);

    /* (void) casts suppress [[nodiscard]] warnings in FreeRDP 3.23+ */
    (void)freerdp_settings_set_string(settings, FreeRDP_ServerHostname, host);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_ServerPort,     (UINT32)jPort);
    (void)freerdp_settings_set_string(settings, FreeRDP_Username,       user);
    (void)freerdp_settings_set_string(settings, FreeRDP_Password,       pass);
    (void)freerdp_settings_set_string(settings, FreeRDP_Domain,         domain);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopWidth,   (UINT32)jWidth);
    (void)freerdp_settings_set_uint32(settings, FreeRDP_DesktopHeight,  (UINT32)jHeight);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_NlaSecurity,    jUseNla ? TRUE : FALSE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_TlsSecurity,    TRUE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_RdpSecurity,    TRUE);
    (void)freerdp_settings_set_bool  (settings, FreeRDP_IgnoreCertificate, TRUE);

    if (jGatewayEnabled)
    {
        const char* gwHost   = (*env)->GetStringUTFChars(env, jGwHost,   NULL);
        const char* gwUser   = (*env)->GetStringUTFChars(env, jGwUser,   NULL);
        const char* gwPass   = (*env)->GetStringUTFChars(env, jGwPass,   NULL);
        const char* gwDomain = (*env)->GetStringUTFChars(env, jGwDomain, NULL);

        (void)freerdp_settings_set_bool  (settings, FreeRDP_GatewayEnabled,      TRUE);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayHostname,     gwHost);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayPort,         (UINT32)jGwPort);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayUsername,     gwUser);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayPassword,     gwPass);
        (void)freerdp_settings_set_string(settings, FreeRDP_GatewayDomain,       gwDomain);
        (void)freerdp_settings_set_uint32(settings, FreeRDP_GatewayUsageMethod,  1 /* TSC_PROXY_MODE_DIRECT */);

        (*env)->ReleaseStringUTFChars(env, jGwHost,   gwHost);
        (*env)->ReleaseStringUTFChars(env, jGwUser,   gwUser);
        (*env)->ReleaseStringUTFChars(env, jGwPass,   gwPass);
        (*env)->ReleaseStringUTFChars(env, jGwDomain, gwDomain);
    }

    (*env)->ReleaseStringUTFChars(env, jHost,     host);
    (*env)->ReleaseStringUTFChars(env, jUsername, user);
    (*env)->ReleaseStringUTFChars(env, jPassword, pass);
    (*env)->ReleaseStringUTFChars(env, jDomain,   domain);

    BOOL ok = freerdp_connect(instance);
    if (!ok)
    {
        hexrdpContext* hctx = HEXRDP_CTX(instance);
        UINT32 code = freerdp_get_last_error(instance->context);
        const char* name = freerdp_get_last_error_name(code);
        (*env)->CallVoidMethod(env, hctx->bridgeObjGlobalRef, hctx->onErrorMethod,
                                (*env)->NewStringUTF(env, name ? name : "Unknown FreeRDP error"));
    }
    return ok ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeSendMouse(
    JNIEnv* env, jobject thiz, jlong handle, jint x, jint y, jint flags)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    (void)freerdp_input_send_mouse_event(instance->context->input,
                                          (UINT16)flags, (UINT16)x, (UINT16)y);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeSendKey(
    JNIEnv* env, jobject thiz, jlong handle, jint scanCode, jboolean down, jboolean extended)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance || !instance->context->input) return;
    UINT16 kflags = (UINT16)((down ? 0 : KBD_FLAGS_RELEASE) | (extended ? KBD_FLAGS_EXTENDED : 0));
    (void)freerdp_input_send_keyboard_event(instance->context->input, kflags, (UINT16)scanCode);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeDisconnect(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)env; (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    freerdp_disconnect(instance);
}

JNIEXPORT void JNICALL
Java_com_gotohex_rdp_rdp_native_AFreeRdpBridge_nativeFree(JNIEnv* env, jobject thiz, jlong handle)
{
    (void)thiz;
    freerdp* instance = (freerdp*)(intptr_t)handle;
    if (!instance) return;
    hexrdpContext* hctx = HEXRDP_CTX(instance);
    if (hctx && hctx->bridgeObjGlobalRef)
        (*env)->DeleteGlobalRef(env, hctx->bridgeObjGlobalRef);
    freerdp_context_free(instance);
    freerdp_free(instance);
}
