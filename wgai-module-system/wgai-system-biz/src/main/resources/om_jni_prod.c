#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "acl/acl.h"

#define LOGI(f, ...) fprintf(stdout, "[Om] " f "\n", ##__VA_ARGS__)
#define LOGE(f, ...) fprintf(stderr, "[Om][ERR] " f "\n", ##__VA_ARGS__)

#define MAX_DEV 16
static int          g_libInit = 0;
static aclrtContext g_ctx[MAX_DEV];
static int          g_ctxOk[MAX_DEV];

typedef struct {
    uint32_t id; int dev; aclmdlDesc *desc; aclmdlDataset *in, *out;
    size_t nIn, nOut; void **inDev, **outDev; size_t *inSz, *outSz;
} Om;

static void thr(JNIEnv *e, const char *m) {
    jclass c = (*e)->FindClass(e, "java/lang/RuntimeException");
    if (c) (*e)->ThrowNew(e, c, m);
}

JNIEXPORT jint JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeInit(JNIEnv *e, jclass c, jint dev) {
    if (dev < 0 || dev >= MAX_DEV) { LOGE("device %d out of range", dev); return -1; }
    if (g_ctxOk[dev]) return 0;
    if (!g_libInit) {
        aclError r0 = aclInit(NULL);
        if (r0 != ACL_SUCCESS && r0 != ACL_ERROR_REPEAT_INITIALIZE) { LOGE("aclInit %d", r0); return -1; }
        g_libInit = 1;
    }
    aclError r = aclrtSetDevice(dev);
    if (r != ACL_SUCCESS) { LOGE("setDevice(%d) %d", dev, r); return -1; }
    r = aclrtCreateContext(&g_ctx[dev], dev);
    if (r != ACL_SUCCESS) { LOGE("createContext(%d) %d", dev, r); return -1; }
    g_ctxOk[dev] = 1;
    LOGI("device %d context ready", dev);
    return 0;
}

JNIEXPORT jlong JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeLoad(JNIEnv *e, jclass c, jstring jp, jint dev) {
    if (dev < 0 || dev >= MAX_DEV || !g_ctxOk[dev]) { thr(e, "device not inited"); return 0; }
    aclrtSetCurrentContext(g_ctx[dev]);
    const char *p = (*e)->GetStringUTFChars(e, jp, NULL);
    Om *m = (Om *)calloc(1, sizeof(Om));
    m->dev = dev;
    aclError r = aclmdlLoadFromFile(p, &m->id);
    if (r != ACL_SUCCESS) {
        LOGE("load %s @dev%d -> %d", p, dev, r);
        (*e)->ReleaseStringUTFChars(e, jp, p); free(m); thr(e, "aclmdlLoadFromFile"); return 0;
    }
    LOGI("loaded %s @dev%d id=%u", p, dev, m->id);
    (*e)->ReleaseStringUTFChars(e, jp, p);

    m->desc = aclmdlCreateDesc();
    if (aclmdlGetDesc(m->desc, m->id) != ACL_SUCCESS) { thr(e, "getDesc"); return 0; }
    m->nIn = aclmdlGetNumInputs(m->desc); m->nOut = aclmdlGetNumOutputs(m->desc);
    m->inDev = calloc(m->nIn,sizeof(void*));  m->inSz = calloc(m->nIn,sizeof(size_t));
    m->outDev= calloc(m->nOut,sizeof(void*)); m->outSz= calloc(m->nOut,sizeof(size_t));

    m->in = aclmdlCreateDataset();
    for (size_t i = 0; i < m->nIn; i++) {
        size_t s = aclmdlGetInputSizeByIndex(m->desc, i); m->inSz[i] = s;
        if (aclrtMalloc(&m->inDev[i], s, ACL_MEM_MALLOC_HUGE_FIRST) != ACL_SUCCESS) { thr(e,"malloc in"); return 0; }
        aclmdlAddDatasetBuffer(m->in, aclCreateDataBuffer(m->inDev[i], s));
        LOGI("  in[%zu] %zu bytes = %zu floats", i, s, s/sizeof(float));
    }
    m->out = aclmdlCreateDataset();
    for (size_t i = 0; i < m->nOut; i++) {
        size_t s = aclmdlGetOutputSizeByIndex(m->desc, i); m->outSz[i] = s;
        if (aclrtMalloc(&m->outDev[i], s, ACL_MEM_MALLOC_HUGE_FIRST) != ACL_SUCCESS) { thr(e,"malloc out"); return 0; }
        aclmdlAddDatasetBuffer(m->out, aclCreateDataBuffer(m->outDev[i], s));
        LOGI("  out[%zu] %zu bytes = %zu floats", i, s, s/sizeof(float));
    }
    return (jlong)(intptr_t)m;
}

JNIEXPORT jobjectArray JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeInfer(JNIEnv *e, jclass c, jlong h, jfloatArray ji) {
    Om *m = (Om *)(intptr_t)h;
    if (!m) { thr(e, "null handle"); return NULL; }
    aclrtSetCurrentContext(g_ctx[m->dev]);
    jsize n = (*e)->GetArrayLength(e, ji);
    size_t b = (size_t)n * sizeof(float);
    if (b != m->inSz[0]) {
        char t[160]; snprintf(t,sizeof(t),"input %zu bytes, model wants %zu", b, m->inSz[0]);
        thr(e, t); return NULL;
    }
    jfloat *host = (*e)->GetFloatArrayElements(e, ji, NULL);
    aclError r = aclrtMemcpy(m->inDev[0], m->inSz[0], host, b, ACL_MEMCPY_HOST_TO_DEVICE);
    (*e)->ReleaseFloatArrayElements(e, ji, host, JNI_ABORT);
    if (r != ACL_SUCCESS) { LOGE("H2D %d", r); thr(e, "memcpy H2D"); return NULL; }

    r = aclmdlExecute(m->id, m->in, m->out);
    if (r != ACL_SUCCESS) { LOGE("execute %d", r); thr(e, "aclmdlExecute"); return NULL; }

    jclass fa = (*e)->FindClass(e, "[F");
    jobjectArray res = (*e)->NewObjectArray(e, (jsize)m->nOut, fa, NULL);
    for (size_t i = 0; i < m->nOut; i++) {
        size_t cnt = m->outSz[i] / sizeof(float);
        jfloatArray arr = (*e)->NewFloatArray(e, (jsize)cnt);
        jfloat *d = (*e)->GetFloatArrayElements(e, arr, NULL);
        r = aclrtMemcpy(d, m->outSz[i], m->outDev[i], m->outSz[i], ACL_MEMCPY_DEVICE_TO_HOST);
        (*e)->ReleaseFloatArrayElements(e, arr, d, 0);
        if (r != ACL_SUCCESS) { LOGE("D2H %d", r); thr(e, "memcpy D2H"); return NULL; }
        (*e)->SetObjectArrayElement(e, res, (jsize)i, arr);
        (*e)->DeleteLocalRef(e, arr);
    }
    return res;
}

JNIEXPORT jlongArray JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeOutShape(JNIEnv *e, jclass c, jlong h, jint idx) {
    Om *m = (Om *)(intptr_t)h;
    if (!m) { thr(e, "null handle"); return NULL; }
    aclmdlIODims d;
    if (aclmdlGetOutputDims(m->desc, (size_t)idx, &d) != ACL_SUCCESS) { thr(e, "getOutputDims"); return NULL; }
    jlongArray a = (*e)->NewLongArray(e, (jsize)d.dimCount);
    jlong *buf = (*e)->GetLongArrayElements(e, a, NULL);
    for (size_t i = 0; i < d.dimCount; i++) buf[i] = (jlong)d.dims[i];
    (*e)->ReleaseLongArrayElements(e, a, buf, 0);
    return a;
}

JNIEXPORT jint JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeNumOut(JNIEnv *e, jclass c, jlong h) {
    Om *m = (Om *)(intptr_t)h; return m ? (jint)m->nOut : -1;
}

JNIEXPORT void JNICALL Java_org_jeecg_modules_ai_ascend_AscendOmSession_nativeUnload(JNIEnv *e, jclass c, jlong h) {
    Om *m = (Om *)(intptr_t)h;
    if (!m) return;
    uint32_t id = m->id; int dev = m->dev;
    aclrtSetCurrentContext(g_ctx[dev]);
    if (m->in) {
        size_t n = aclmdlGetDatasetNumBuffers(m->in);
        for (size_t i = 0; i < n; i++) aclDestroyDataBuffer(aclmdlGetDatasetBuffer(m->in, i));
        aclmdlDestroyDataset(m->in);
    }
    if (m->out) {
        size_t n = aclmdlGetDatasetNumBuffers(m->out);
        for (size_t i = 0; i < n; i++) aclDestroyDataBuffer(aclmdlGetDatasetBuffer(m->out, i));
        aclmdlDestroyDataset(m->out);
    }
    for (size_t i = 0; i < m->nIn;  i++) if (m->inDev[i])  aclrtFree(m->inDev[i]);
    for (size_t i = 0; i < m->nOut; i++) if (m->outDev[i]) aclrtFree(m->outDev[i]);
    free(m->inDev); free(m->outDev); free(m->inSz); free(m->outSz);
    if (m->desc) aclmdlDestroyDesc(m->desc);
    aclmdlUnload(id);
    free(m);
    LOGI("unloaded id=%u @dev%d", id, dev);
}
