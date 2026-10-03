// laya-jni.c — APK 内置 LiteRT 推理(C API,与 litert-bench/runner 同款已验证路径)
// 替代 litert Java API 的 buffer 创建(app 域下 createInputBuffer 全挂的问题)。
// open: env → 模型 → GPU 编译(带 program cache)→ managed host buffers
// run : fp16 查表 gather → GPU 主图 → marker gather → act 特征 → CPU act 头
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/options/litert_gpu_options.h"
#include <dlfcn.h>
#include <jni.h>
#include <android/log.h>
#include <time.h>
#include <math.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <malloc.h>
#include <unistd.h>

#define WINDOW 256
#define HIDDEN 768
#define VOCAB 256000
#define TAG "LayaJni"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
static void free_cb(void *p) { free(p); }
static double now_ms(void) {
  struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

#define CHECK(expr) do { \
  LiteRtStatus s__ = (expr); \
  if (s__ != kLiteRtStatusOk) { LOGE("LITERT ERROR %d @ %s", (int)s__, #expr); goto fail; } \
} while (0)

typedef struct {
  LiteRtEnvironment env;
  LiteRtModel main_m, act_m;
  LiteRtCompiledModel cm_main, cm_act;
  LiteRtTensorBuffer in_embeds, in_mask, in_qtype, out_logits, out_pooled;
  LiteRtTensorBuffer a_in_pooled, a_in_feats, a_out;
  float *embed_table;   // mmap fp16 表
  size_t embed_bytes;
  int act_dim;
  // 常驻 scratch(单线程使用)
  float embeds[WINDOW * HIDDEN];
  float mask[WINDOW];
  float qonehot[3];
  float logits[WINDOW];
  float pooled[HIDDEN];
} Ctx;

static LiteRtTensorBuffer make_buffer(LiteRtEnvironment env, LiteRtCompiledModel cm,
                                      LiteRtSignature sig, int idx, int is_output, int *lastdim) {
  LiteRtTensor t;
  LiteRtStatus s = is_output ? LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t)
                             : LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  if (s != kLiteRtStatusOk) { LOGE("sig tensor %d err %d", idx, (int)s); return NULL; }
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) { LOGE("ranked type %d", idx); return NULL; }
  if (lastdim && tt.layout.rank >= 1) *lastdim = (int)tt.layout.dimensions[tt.layout.rank - 1];
  LiteRtTensorBufferRequirements req;
  s = is_output ? LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, idx, &req)
                : LiteRtGetCompiledModelInputBufferRequirements(cm, 0, idx, &req);
  if (s != kLiteRtStatusOk) { LOGE("bufreq %d err %d", idx, (int)s); return NULL; }
  LiteRtTensorBuffer buf = NULL;
  s = LiteRtCreateManagedTensorBufferFromRequirements(env, &tt, req, &buf);
  if (s == kLiteRtStatusOk) return buf;
  // app 域下 managed 分配(ion/dmabuf 路径)不可用 → 降级纯 host 内存。
  // 所有图 IO 均为 fp32,按 4 字节元素计算容量;64 字节对齐满足 HOST_MEMORY 约束。
  size_t bytes = 4;
  for (int d = 0; d < tt.layout.rank; d++) bytes *= (size_t)tt.layout.dimensions[d];
  void *mem = memalign(64, ((bytes + 63) / 64) * 64);
  if (!mem) { LOGE("host alloc %d (%zu bytes) failed", idx, bytes); return NULL; }
  if (LiteRtCreateTensorBufferFromHostMemory(&tt, mem, bytes, free_cb, &buf) != kLiteRtStatusOk) {
    LOGE("host buffer %d failed", idx);
    free(mem);
    return NULL;
  }
  LOGI("buffer %d -> host memory fallback (%zu bytes, managed err %d)", idx, bytes, (int)s);
  return buf;
}

static LiteRtOpaqueOptions gpu_opts(const char *cache_dir, const char *key) {
  LrtGpuOptions *gpu;
  LiteRtOpaqueOptions opaque = {0};
  if (LrtCreateGpuOptions(&gpu) != kLiteRtStatusOk) { LOGE("gpu opts create failed"); return opaque; }
  if (LrtSetGpuAcceleratorCompilationOptionsPrecision(gpu, kLiteRtDelegatePrecisionFp32) != kLiteRtStatusOk ||
      LrtSetGpuAcceleratorCompilationOptionsSerializationDir(gpu, cache_dir) != kLiteRtStatusOk ||
      LrtSetGpuAcceleratorCompilationOptionsModelCacheKey(gpu, key) != kLiteRtStatusOk) {
    LOGE("gpu opts set failed"); return opaque;
  }
  const char *id; void *payload; void (*dtor)(void *);
  if (LrtGetOpaqueGpuOptionsData(gpu, &id, &payload, &dtor) != kLiteRtStatusOk ||
      LiteRtCreateOpaqueOptions(id, payload, dtor, &opaque) != kLiteRtStatusOk) {
    LOGE("opaque options failed"); memset(&opaque, 0, sizeof opaque); return opaque;
  }
  return opaque;
}

static void write_buf(LiteRtTensorBuffer buf, const void *data, size_t bytes) {
  void *p;
  if (LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeWrite) != kLiteRtStatusOk) return;
  memcpy(p, data, bytes);
  LiteRtUnlockTensorBuffer(buf);
}
static void read_buf(LiteRtTensorBuffer buf, void *dst, size_t bytes) {
  void *p;
  if (LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeRead) != kLiteRtStatusOk) return;
  memcpy(dst, p, bytes);
  LiteRtUnlockTensorBuffer(buf);
}

JNIEXPORT jlong JNICALL
Java_com_laya_LayaJni_nativeOpen(JNIEnv *env, jclass clazz, jstring jmain, jstring jact,
                                 jstring jembed, jstring jcache, jboolean useGpu) {
  const char *main_path = (*env)->GetStringUTFChars(env, jmain, NULL);
  const char *act_path = (*env)->GetStringUTFChars(env, jact, NULL);
  const char *embed_path = (*env)->GetStringUTFChars(env, jembed, NULL);
  const char *cache_dir = (*env)->GetStringUTFChars(env, jcache, NULL);

  Ctx *c = calloc(1, sizeof(Ctx));

  // app 链接器命名空间下裸名 dlopen("libOpenCL.so") 被限制(Termux 等旧 targetSdk
  // 进程不受限)。先用全路径 + GLOBAL 预载 vendor ICD,之后 litert 内部同名裸
  // dlopen 会命中进程内已加载库 → OpenCL 路径可用(managed buffer 走 CL mem)。
  {
    const char *cl_paths[] = { "/vendor/lib64/libOpenCL.so", "/odm/lib64/libOpenCL.so",
                               "/system/lib64/libOpenCL.so", NULL };
    int cl_ok = 0;
    for (int i = 0; cl_paths[i]; i++) {
      void *h = dlopen(cl_paths[i], RTLD_NOW | RTLD_GLOBAL);
      if (h) { LOGI("preloaded %s", cl_paths[i]); cl_ok = 1; break; }
    }
    if (!cl_ok) LOGE("libOpenCL preload failed: %s", dlerror());
  }

  int efd = open(embed_path, O_RDONLY);
  if (efd < 0) { LOGE("open embeddings failed"); goto fail; }
  c->embed_bytes = (size_t)VOCAB * HIDDEN * sizeof(short);
  c->embed_table = mmap(NULL, c->embed_bytes, PROT_READ, MAP_PRIVATE, efd, 0);
  close(efd);
  if (c->embed_table == MAP_FAILED) { LOGE("mmap embeddings failed"); goto fail; }
  madvise(c->embed_table, c->embed_bytes, MADV_RANDOM);

  CHECK(LiteRtCreateEnvironment(0, NULL, &c->env));
  CHECK(LiteRtCreateModelFromFile(c->env, main_path, &c->main_m));
  CHECK(LiteRtCreateModelFromFile(c->env, act_path, &c->act_m));

  {
    LiteRtOptions opts;
    CHECK(LiteRtCreateOptions(&opts));
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts, useGpu ? kLiteRtHwAcceleratorGpu
                                                            : kLiteRtHwAcceleratorCpu));
    if (useGpu) LiteRtAddOpaqueOptions(opts, gpu_opts(cache_dir, "laya_ml_s256_wfp16_fp32"));
    LiteRtStatus s = LiteRtCreateCompiledModel(c->env, c->main_m, opts, &c->cm_main);
    if (s != kLiteRtStatusOk) { LOGE("main compiled model failed: %d", (int)s); goto fail; }
    bool fully = false;
    LiteRtCompiledModelIsFullyAccelerated(c->cm_main, &fully);
    LOGI("main fully accelerated=%d", (int)fully);
  }
  {
    LiteRtOptions opts2;
    CHECK(LiteRtCreateOptions(&opts2));
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts2, kLiteRtHwAcceleratorCpu));
    CHECK(LiteRtCreateCompiledModel(c->env, c->act_m, opts2, &c->cm_act));
  }

  {
    LiteRtSignature msig, asig;
    CHECK(LiteRtGetModelSignature(c->main_m, 0, &msig));
    CHECK(LiteRtGetModelSignature(c->act_m, 0, &asig));
    c->in_embeds = make_buffer(c->env, c->cm_main, msig, 0, 0, NULL);
    c->in_mask   = make_buffer(c->env, c->cm_main, msig, 1, 0, NULL);
    c->in_qtype  = make_buffer(c->env, c->cm_main, msig, 2, 0, NULL);
    c->out_logits = make_buffer(c->env, c->cm_main, msig, 0, 1, NULL);
    c->out_pooled = make_buffer(c->env, c->cm_main, msig, 1, 1, NULL);
    c->a_in_pooled = make_buffer(c->env, c->cm_act, asig, 0, 0, NULL);
    c->a_in_feats  = make_buffer(c->env, c->cm_act, asig, 1, 0, NULL);
    c->act_dim = 2;
    c->a_out = make_buffer(c->env, c->cm_act, asig, 0, 1, &c->act_dim);
    if (c->act_dim < 1 || c->act_dim > 8) c->act_dim = 2;
    if (!c->in_embeds || !c->in_mask || !c->in_qtype || !c->out_logits || !c->out_pooled ||
        !c->a_in_pooled || !c->a_in_feats || !c->a_out) {
      LOGE("buffer creation failed");
      goto fail;
    }
  }
  LOGI("model ready (gpu=%d, act_dim=%d)", (int)useGpu, c->act_dim);
  (*env)->ReleaseStringUTFChars(env, jmain, main_path);
  (*env)->ReleaseStringUTFChars(env, jact, act_path);
  (*env)->ReleaseStringUTFChars(env, jembed, embed_path);
  (*env)->ReleaseStringUTFChars(env, jcache, cache_dir);
  return (jlong)(intptr_t)c;

fail:
  (*env)->ReleaseStringUTFChars(env, jmain, main_path);
  (*env)->ReleaseStringUTFChars(env, jact, act_path);
  (*env)->ReleaseStringUTFChars(env, jembed, embed_path);
  (*env)->ReleaseStringUTFChars(env, jcache, cache_dir);
  free(c);
  return 0;
}

// 返回 float[K + act_dim]:前 K 个 marker logits,后 act_dim 个 act logits;失败返回 NULL
JNIEXPORT jfloatArray JNICALL
Java_com_laya_LayaJni_nativeRun(JNIEnv *env, jclass clazz, jlong h, jintArray jids,
                                jintArray jmarkers, jint qtype, jint window) {
  Ctx *c = (Ctx *)(intptr_t)h;
  if (!c) return NULL;
  if (window <= 0 || window > WINDOW) { LOGE("bad window %d", window); return NULL; }
  jsize L = (*env)->GetArrayLength(env, jids);
  jsize K = (*env)->GetArrayLength(env, jmarkers);
  if (L <= 0 || L > window || K <= 0 || K > 64) { LOGE("bad dims L=%d K=%d", L, K); return NULL; }
  jint *ids = (*env)->GetIntArrayElements(env, jids, NULL);
  jint *markers = (*env)->GetIntArrayElements(env, jmarkers, NULL);

  // gather embedding rows(fp16→fp32),mask,qtype onehot;window 尾部补零
  memset(c->embeds, 0, sizeof(float) * (size_t)window * HIDDEN);
  memset(c->mask, 0, sizeof(float) * (size_t)window);
  memset(c->qonehot, 0, sizeof(c->qonehot));
  if (qtype < 0 || qtype > 2) { LOGE("bad qtype %d", qtype); goto err; }
  c->qonehot[qtype] = 1.0f;
  for (int i = 0; i < L; i++) {
    int tok = ids[i];
    if (tok < 0 || tok >= VOCAB) { LOGE("token id out of range: %d", tok); goto err; }
    const short *row = (const short *)c->embed_table + (size_t)tok * HIDDEN; // __fp16 = short 位型
    float *dst = c->embeds + (size_t)i * HIDDEN;
    for (int hh = 0; hh < HIDDEN; hh++) {
      __fp16 f16;
      memcpy(&f16, row + hh, sizeof(short));
      dst[hh] = (float)f16;
    }
    c->mask[i] = 1.0f;
  }
  {
    double w0 = now_ms();
    write_buf(c->in_embeds, c->embeds, sizeof(float) * (size_t)window * HIDDEN);
    double w1 = now_ms();
    write_buf(c->in_mask, c->mask, sizeof(float) * (size_t)window);
    write_buf(c->in_qtype, c->qonehot, sizeof(c->qonehot));
    LOGI("perf write-embeds=%.1fms", w1 - w0);
  }

  {
    LiteRtTensorBuffer mins[3] = { c->in_embeds, c->in_mask, c->in_qtype };
    LiteRtTensorBuffer mouts[2] = { c->out_logits, c->out_pooled };
    double t0 = now_ms();
    LiteRtStatus s = LiteRtRunCompiledModel(c->cm_main, 0, 3, mins, 2, mouts);
    double t1 = now_ms();
    if (s != kLiteRtStatusOk) { LOGE("main run failed: %d", (int)s); goto err; }
    LOGI("perf main-invoke=%.1fms", t1 - t0);
  }
  {
    double r0 = now_ms();
    read_buf(c->out_logits, c->logits, sizeof(float) * (size_t)window);
    read_buf(c->out_pooled, c->pooled, sizeof(float) * HIDDEN);
    double r1 = now_ms();
    LOGI("perf read-outputs=%.1fms", r1 - r0);
  }

  // marker gather
  float raw[64];
  int k = 0;
  for (int j = 0; j < K; j++)
    if (markers[j] >= 0 && markers[j] < L) raw[k++] = c->logits[markers[j]];
  if (k == 0) { LOGE("no valid markers"); goto err; }

  // act 头特征(laya_host.act_features,与 runner.c 同款)
  {
    float top1 = -1e30f, top2 = -1e30f, ent = 0.0f;
    float mx = raw[0];
    for (int j = 1; j < k; j++) if (raw[j] > mx) mx = raw[j];
    float sum = 0.0f, probs[64];
    for (int j = 0; j < k; j++) { probs[j] = expf(raw[j] - mx); sum += probs[j]; }
    for (int j = 0; j < k; j++) probs[j] /= sum;
    for (int j = 0; j < k; j++) {
      ent -= probs[j] * logf(probs[j] > 1e-9f ? probs[j] : 1e-9f);
      if (probs[j] > top1) { top2 = top1; top1 = probs[j]; }
      else if (probs[j] > top2) top2 = probs[j];
    }
    float kk = (float)(k > 2 ? k : 2);
    float feats[4] = { top1, top1 - (top2 < 0 ? 0.0f : top2), ent / logf(kk), kk / 255.0f };
    write_buf(c->a_in_pooled, c->pooled, sizeof(float) * HIDDEN);
    write_buf(c->a_in_feats, feats, sizeof(feats));
    LiteRtTensorBuffer ains[2] = { c->a_in_pooled, c->a_in_feats };
    LiteRtTensorBuffer aouts[1] = { c->a_out };
    double ta0 = now_ms();
    LiteRtStatus s = LiteRtRunCompiledModel(c->cm_act, 0, 2, ains, 1, aouts);
    double ta1 = now_ms();
    if (s != kLiteRtStatusOk) { LOGE("act run failed: %d", (int)s); goto err; }
    LOGI("perf act-invoke=%.1fms", ta1 - ta0);
  }

  (*env)->ReleaseIntArrayElements(env, jids, ids, JNI_ABORT);
  (*env)->ReleaseIntArrayElements(env, jmarkers, markers, JNI_ABORT);
  {
    jfloatArray out = (*env)->NewFloatArray(env, (jsize)(k + c->act_dim));
    float tmp[64 + 8];
    memcpy(tmp, raw, sizeof(float) * (size_t)k);
    read_buf(c->a_out, tmp + k, sizeof(float) * (size_t)c->act_dim);
    (*env)->SetFloatArrayRegion(env, out, 0, (jsize)(k + c->act_dim), tmp);
    return out;
  }

err:
  (*env)->ReleaseIntArrayElements(env, jids, ids, JNI_ABORT);
  (*env)->ReleaseIntArrayElements(env, jmarkers, markers, JNI_ABORT);
  return NULL;
}

JNIEXPORT void JNICALL
Java_com_laya_LayaJni_nativeClose(JNIEnv *env, jclass clazz, jlong h) {
  Ctx *c = (Ctx *)(intptr_t)h;
  if (!c) return;
  // 完整销毁链:buffer → compiled model → model → environment。
  // 漏掉任何一层都会泄漏(压测实测:每引擎 ~500MB Graphics 不归还)
  LiteRtDestroyTensorBuffer(c->in_embeds);
  LiteRtDestroyTensorBuffer(c->in_mask);
  LiteRtDestroyTensorBuffer(c->in_qtype);
  LiteRtDestroyTensorBuffer(c->out_logits);
  LiteRtDestroyTensorBuffer(c->out_pooled);
  LiteRtDestroyTensorBuffer(c->a_in_pooled);
  LiteRtDestroyTensorBuffer(c->a_in_feats);
  LiteRtDestroyTensorBuffer(c->a_out);
  LiteRtDestroyCompiledModel(c->cm_main);
  LiteRtDestroyCompiledModel(c->cm_act);
  LiteRtDestroyModel(c->main_m);
  LiteRtDestroyModel(c->act_m);
  LiteRtDestroyEnvironment(c->env);
  if (c->embed_table && c->embed_table != MAP_FAILED) munmap(c->embed_table, c->embed_bytes);
  free(c);
}
