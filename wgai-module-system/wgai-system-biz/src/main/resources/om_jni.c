#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include "acl/acl.h"

#define LOGI(f, ...) fprintf(stdout, "[Om] " f "\n", ##__VA_ARGS__)
#define LOGE(f, ...) fprintf(stderr, "[Om][ERR] " f "\n", ##__VA_ARGS__)

static int g_init = 0; static int g_dev = 0; static aclrtContext g_ctx = NULL;

typedef struct {
    uint32_t id; aclmdlDesc *desc; aclmdlDataset *in, *out;
    size_t nIn, nOut; void **inDev, **outDev; size_t *inSz, *outSz;
} Om;

static void thr(JNIEnv *e, const char *m) {
    jclass c = (*e)->FindClass(e, "java/lang/RuntimeException");
    if (c) (*e)->ThrowNew(e, c, m);
}

JNIEXPORT jint JNICALL Java_TestOm_nativeInit(JNIEnv *e, jclass c, jint dev) {
    if (g_init) return 0;
    aclError r = aclInit(NULL);
    if (r != ACL_SUCCESS && r != ACL_ERROR_REPEAT_INITIALIZE) { LOGE("aclInit %d", r); thr(e,"aclInit"); return -1; }
    r = aclrtSetDevice(dev);
    if (r != ACL_SUCCESS) { LOGE("setDevice %d", r); thr(e,"setDevice"); return -1; }
    r = aclrtCreateContext(&g_ctx, dev);
    if (r != ACL_SUCCESS) { LOGE("createCtx %d", r); thr(e,"createCtx"); return -1; }
    g_dev = dev; g_init = 1; LOGI("ACL init ok, device=%d", dev); return 0;
}

JNIEXPORT jlong JNICALL Java_TestOm_nativeLoad(JNIEnv *e, jclass c, jstring jp) {
    if (!g_init) { thr(e, "init first"); return 0; }
    aclrtSetCurrentContext(g_ctx);
    const char *p = (*e)->GetStringUTFChars(e, jp, NULL);
    Om *m = (Om *)calloc(1, sizeof(Om));
    aclError r = aclmdlLoadFromFile(p, &m->id);
    if (r != ACL_SUCCESS) { LOGE("load %s -> %d", p, r); (*e)->ReleaseStringUTFChars(e,jp,p); free(m); thr(e,"aclmdlLoadFromFile"); return 0; }
    LOGI("loaded %s id=%u", p, m->id);
    (*e)->ReleaseStringUTFChars(e, jp, p);

    m->desc = aclmdlCreateDesc();
    if (aclmdlGetDesc(m->desc, m->id) != ACL_SUCCESS) { thr(e,"getDesc"); return 0; }
    m->nIn = aclmdlGetNumInputs(m->desc); m->nOut = aclmdlGetNumOutputs(m->desc);
    LOGI("inputs=%zu outputs=%zu", m->nIn, m->nOut);

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

JNIEXPORT jobjectArray JNICALL Java_TestOm_nativeInfer(JNIEnv *e, jclass c, jlong h, jfloatArray ji) {
    Om *m = (Om *)(intptr_t)h;
    if (!m) { thr(e,"null handle"); return NULL; }
    aclrtSetCurrentContext(g_ctx);
    jsize n = (*e)->GetArrayLength(e, ji);
    size_t b = (size_t)n * sizeof(float);
    if (b != m->inSz[0]) { char t[160]; snprintf(t,sizeof(t),"input %zu bytes, model wants %zu", b, m->inSz[0]); thr(e,t); return NULL; }
    jfloat *host = (*e)->GetFloatArrayElements(e, ji, NULL);
    aclError r = aclrtMemcpy(m->inDev[0], m->inSz[0], host, b, ACL_MEMCPY_HOST_TO_DEVICE);
    (*e)->ReleaseFloatArrayElements(e, ji, host, JNI_ABORT);
    if (r != ACL_SUCCESS) { LOGE("H2D %d", r); thr(e,"memcpy H2D"); return NULL; }

    r = aclmdlExecute(m->id, m->in, m->out);
    if (r != ACL_SUCCESS) { LOGE("execute %d", r); thr(e,"aclmdlExecute"); return NULL; }

    jclass fa = (*e)->FindClass(e, "[F");
    jobjectArray res = (*e)->NewObjectArray(e, (jsize)m->nOut, fa, NULL);
    for (size_t i = 0; i < m->nOut; i++) {
        size_t cnt = m->outSz[i] / sizeof(float);
        jfloatArray arr = (*e)->NewFloatArray(e, (jsize)cnt);
        jfloat *d = (*e)->GetFloatArrayElements(e, arr, NULL);
        r = aclrtMemcpy(d, m->outSz[i], m->outDev[i], m->outSz[i], ACL_MEMCPY_DEVICE_TO_HOST);
        (*e)->ReleaseFloatArrayElements(e, arr, d, 0);
        if (r != ACL_SUCCESS) { LOGE("D2H %d", r); thr(e,"memcpy D2H"); return NULL; }
        (*e)->SetObjectArrayElement(e, res, (jsize)i, arr);
        (*e)->DeleteLocalRef(e, arr);
    }
    return res;
}

JNIEXPORT jlongArray JNICALL Java_TestOm_nativeOutShape(JNIEnv *e, jclass c, jlong h, jint idx) {
    Om *m = (Om *)(intptr_t)h;
    aclmdlIODims d;
    if (aclmdlGetOutputDims(m->desc, (size_t)idx, &d) != ACL_SUCCESS) { thr(e,"getOutputDims"); return NULL; }
    jlongArray a = (*e)->NewLongArray(e, (jsize)d.dimCount);
    jlong *buf = (*e)->GetLongArrayElements(e, a, NULL);
    for (size_t i = 0; i < d.dimCount; i++) buf[i] = (jlong)d.dims[i];
    (*e)->ReleaseLongArrayElements(e, a, buf, 0);
    return a;
}

JNIEXPORT jint JNICALL Java_TestOm_nativeNumOut(JNIEnv *e, jclass c, jlong h) {
    Om *m = (Om *)(intptr_t)h; return m ? (jint)m->nOut : -1;
}
