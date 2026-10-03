// LiteRT smoke test for Laya multilingual graphs (main wfp16 + act head) on Android/Termux
// usage: litert-bench <main.tflite> <act.tflite> [gpu|cpu] [runs]
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/options/litert_gpu_options.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <stdbool.h>
#include <time.h>
#include <unistd.h>
#include <pthread.h>

#define WINDOW 256
#define HIDDEN 768
#define VOCAB 256000

static LiteRtCompiledModel g_cm;

static double now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

#define CHECK(expr) do { \
  LiteRtStatus s__ = (expr); \
  if (s__ != kLiteRtStatusOk) { fprintf(stderr, "LITERT ERROR %d @ %s\n", (int)s__, #expr); return 1; } \
} while (0)

static LiteRtEnvironment g_ka_env_unused;
static LiteRtCompiledModel g_cm_ka;          // GPU act-head model for keep-alive
static LiteRtTensorBuffer g_ka_in0, g_ka_in1, g_ka_out0;
static volatile int g_ka_on = 0;
static void* ka_thread(void* arg) {
  int ivl = (int)(intptr_t)arg;
  float pooled[768] = { 0 }, feats[4] = { 0.9f, 0.3f, 0.4f, 0.008f };
  void* p;
  if (LiteRtLockTensorBuffer(g_ka_in0, &p, kLiteRtTensorBufferLockModeWrite) == kLiteRtStatusOk) {
    memcpy(p, pooled, sizeof pooled); LiteRtUnlockTensorBuffer(g_ka_in0);
  }
  if (LiteRtLockTensorBuffer(g_ka_in1, &p, kLiteRtTensorBufferLockModeWrite) == kLiteRtStatusOk) {
    memcpy(p, feats, sizeof feats); LiteRtUnlockTensorBuffer(g_ka_in1);
  }
  LiteRtTensorBuffer ins[2] = { g_ka_in0, g_ka_in1 };
  LiteRtTensorBuffer outs[1] = { g_ka_out0 };
  while (g_ka_on) {
    LiteRtRunCompiledModel(g_cm_ka, 0, 2, ins, 1, outs);
    usleep(ivl * 1000);
  }
  return NULL;
}

#define CHECKM(expr) do { LiteRtStatus s_ = (expr); if (s_ != kLiteRtStatusOk) fprintf(stderr, "buf err %d\n", (int)s_); } while (0)

static void fill(LiteRtTensorBuffer buf, const void* data, size_t bytes) {
  void* p;
  CHECKM(LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeWrite));
  memcpy(p, data, bytes);
  LiteRtUnlockTensorBuffer(buf);
}

static LiteRtTensorBuffer make_buf2(LiteRtEnvironment env, LiteRtCompiledModel cm,
                                    LiteRtSignature sig, int idx, int is_output) {
  LiteRtTensor t;
  LiteRtStatus s;
  if (is_output) s = LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t);
  else s = LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "sig tensor err %d\n", (int)s); return NULL; }
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) { fprintf(stderr, "tensor type err\n"); return NULL; }
  LiteRtTensorBufferRequirements req;
  if (is_output) s = LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, idx, &req);
  else s = LiteRtGetCompiledModelInputBufferRequirements(cm, 0, idx, &req);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "buf req err %d\n", (int)s); return NULL; }
  LiteRtTensorBuffer buf;
  s = LiteRtCreateManagedTensorBufferFromRequirements(env, &tt, req, &buf);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "buf create err %d\n", (int)s); return NULL; }
  return buf;
}

int main(int argc, char** argv) {
  if (argc < 3) { fprintf(stderr, "usage: %s <main.tflite> <act.tflite> [gpu|cpu] [runs] [waittype 0-3] [kbatch] [prio 0-3]\n", argv[0]); return 2; }
  int use_gpu = argc > 3 && strcmp(argv[3], "gpu") == 0;
  int use_npu = argc > 3 && strcmp(argv[3], "npu") == 0;
  int runs = argc > 4 ? atoi(argv[4]) : 10;
  int waittype = argc > 5 ? atoi(argv[5]) : -1;  // 0=default 1=passive 2=active 3=donotwait
  int kbatch = argc > 6 ? atoi(argv[6]) : -1;    // kernel batch size for one flush
  int prio = argc > 7 ? atoi(argv[7]) : -1;      // 0=default 1=low 2=normal 3=high
  int cbsteps = argc > 8 ? atoi(argv[8]) : -1;   // num_steps_of_command_buffer_preparations
  int benchmode = argc > 9 ? atoi(argv[9]) : -1; // 1 = benchmark mode
  int sleepms = argc > 10 ? atoi(argv[10]) : 0;  // sleep between invokes
  int kams = argc > 11 ? atoi(argv[11]) : 0;     // keep-alive: GPU act-head invoke every N ms (0=off)

  LiteRtEnvironment env;
  if (use_npu) {
    // NPU: dispatch library dir 必须指向 libLiteRtDispatch_MediaTek.so 所在目录
    static const char* disp_dir = "/data/local/tmp/litert";
    LiteRtEnvOption eopts[1] = {
        {kLiteRtEnvOptionTagDispatchLibraryDir,
         {kLiteRtAnyTypeString, {.str_value = disp_dir}}}};
    CHECK(LiteRtCreateEnvironment(1, eopts, &env));
  } else {
    CHECK(LiteRtCreateEnvironment(0, NULL, &env));
  }

  LiteRtOptions opts;
  CHECK(LiteRtCreateOptions(&opts));
  CHECK(LiteRtSetOptionsHardwareAccelerators(opts, use_npu ? (kLiteRtHwAcceleratorNpu | kLiteRtHwAcceleratorCpu)
                                          : use_gpu ? kLiteRtHwAcceleratorGpu
                                                    : kLiteRtHwAcceleratorCpu));
  if (use_gpu) {
    LrtGpuOptions* gpu;
    CHECK(LrtCreateGpuOptions(&gpu));
    CHECK(LrtSetGpuAcceleratorCompilationOptionsPrecision(gpu, kLiteRtDelegatePrecisionFp32));
    static const char* ser_dir = "/data/local/tmp/gpucache";
    CHECK(LrtSetGpuAcceleratorCompilationOptionsSerializationDir(gpu, ser_dir));
    CHECK(LrtSetGpuAcceleratorCompilationOptionsModelCacheKey(gpu, "laya_ml_s256_wfp16_fp32"));
    if (waittype >= 0) CHECK(LrtSetGpuAcceleratorRuntimeOptionsWaitType(gpu, (LiteRtGpuWaitType)waittype));
    if (kbatch >= 0) CHECK(LrtSetGpuAcceleratorRuntimeOptionsKernelBatchSize(gpu, kbatch));
    if (prio >= 0) CHECK(LrtSetGpuOptionsGpuPriority(gpu, (LiteRtGpuPriority)prio));
    if (cbsteps >= 0) CHECK(LrtSetGpuAcceleratorRuntimeOptionsNumStepsOfCommandBufferPreparations(gpu, cbsteps));
    if (benchmode >= 0) CHECK(LrtSetGpuOptionsBenchmarkMode(gpu, benchmode != 0));
    const char* id; void* payload; void (*dtor)(void*);
    CHECK(LrtGetOpaqueGpuOptionsData(gpu, &id, &payload, &dtor));
    LiteRtOpaqueOptions opaque;
    CHECK(LiteRtCreateOpaqueOptions(id, payload, dtor, &opaque));
    CHECK(LiteRtAddOpaqueOptions(opts, opaque));
  }

  double t0 = now_ms();
  LiteRtModel main_m, act_m;
  CHECK(LiteRtCreateModelFromFile(env, argv[1], &main_m));
  CHECK(LiteRtCreateModelFromFile(env, argv[2], &act_m));
  double t_model = now_ms() - t0;

  t0 = now_ms();
  LiteRtCompiledModel cm_main, cm_act;
  CHECK(LiteRtCreateCompiledModel(env, main_m, opts, &cm_main));
  if (use_npu) {
    // act head 走 CPU（主图才是延迟大头）
    LiteRtOptions opts2;
    CHECK(LiteRtCreateOptions(&opts2));
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts2, kLiteRtHwAcceleratorCpu));
    CHECK(LiteRtCreateCompiledModel(env, act_m, opts2, &cm_act));
  } else if (use_gpu) {
    // act head: same accelerator class, fresh options
    LiteRtOptions opts2;
    CHECK(LiteRtCreateOptions(&opts2));
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts2, kLiteRtHwAcceleratorGpu));
    LrtGpuOptions* gpu2;
    CHECK(LrtCreateGpuOptions(&gpu2));
    CHECK(LrtSetGpuAcceleratorCompilationOptionsPrecision(gpu2, kLiteRtDelegatePrecisionFp32));
    CHECK(LrtSetGpuAcceleratorCompilationOptionsSerializationDir(gpu2, "/data/local/tmp/gpucache"));
    CHECK(LrtSetGpuAcceleratorCompilationOptionsModelCacheKey(gpu2, "laya_act_head_fp32"));
    const char* id2; void* payload2; void (*dtor2)(void*);
    CHECK(LrtGetOpaqueGpuOptionsData(gpu2, &id2, &payload2, &dtor2));
    LiteRtOpaqueOptions opaque2;
    CHECK(LiteRtCreateOpaqueOptions(id2, payload2, dtor2, &opaque2));
    CHECK(LiteRtAddOpaqueOptions(opts2, opaque2));
    CHECK(LiteRtCreateCompiledModel(env, act_m, opts2, &cm_act));
  } else {
    LiteRtOptions opts2;
    CHECK(LiteRtCreateOptions(&opts2));
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts2, kLiteRtHwAcceleratorCpu));
    CHECK(LiteRtCreateCompiledModel(env, act_m, opts2, &cm_act));
  }
  bool fully = false;
  LiteRtCompiledModelIsFullyAccelerated(cm_main, &fully);
  printf("fully accelerated: %s\n", fully ? "yes" : "no");
  double t_comp = now_ms() - t0;
  printf("model load: %.0f ms | compile: %.0f ms\n", t_model, t_comp);

  LiteRtSignature msig, asig;
  CHECK(LiteRtGetModelSignature(main_m, 0, &msig));
  CHECK(LiteRtGetModelSignature(act_m, 0, &asig));
  LiteRtTensorBuffer in0 = make_buf2(env, cm_main, msig, 0, 0); // inputs_embeds
  LiteRtTensorBuffer in1 = make_buf2(env, cm_main, msig, 1, 0); // attention_mask
  LiteRtTensorBuffer in2 = make_buf2(env, cm_main, msig, 2, 0); // qtype_onehot
  LiteRtTensorBuffer out0 = make_buf2(env, cm_main, msig, 0, 1); // token_logits
  LiteRtTensorBuffer out1 = make_buf2(env, cm_main, msig, 1, 1); // pooled_cls

  // act head buffers
  LiteRtTensorBuffer ain0 = make_buf2(env, cm_act, asig, 0, 0); // pooled_cls
  LiteRtTensorBuffer ain1 = make_buf2(env, cm_act, asig, 1, 0); // feats
  LiteRtTensorBuffer aout0 = make_buf2(env, cm_act, asig, 0, 1); // act_logits

  pthread_t ka_tid;
  if (use_gpu && kams > 0) {
    g_cm_ka = cm_act; g_ka_in0 = ain0; g_ka_in1 = ain1; g_ka_out0 = aout0;
    g_ka_on = 1;
    if (pthread_create(&ka_tid, NULL, ka_thread, (void*)(intptr_t)kams) == 0)
      pthread_detach(ka_tid);
    printf("keep-alive: GPU act-head invoke every %d ms\n", kams);
  }

  // dummy inputs: token i -> embedding row (i*7919)%VOCAB via direct synthetic values
  static float embeds[WINDOW * HIDDEN];
  static float mask[WINDOW];
  static float qtype[3] = {1.0f, 0.0f, 0.0f};
  for (int i = 0; i < WINDOW; i++) {
    mask[i] = (i < 86) ? 1.0f : 0.0f;
    for (int h = 0; h < HIDDEN; h++)
      embeds[i * HIDDEN + h] = ((float)((i * 7919 + h * 131) % 2001) - 1000.0f) / 1000.0f * 0.1f;
  }
  fill(in0, embeds, sizeof(embeds));
  fill(in1, mask, sizeof(mask));
  fill(in2, qtype, sizeof(qtype));

  LiteRtTensorBuffer mains_in[3] = {in0, in1, in2};
  LiteRtTensorBuffer mains_out[2] = {out0, out1};

  t0 = now_ms();
  CHECK(LiteRtRunCompiledModel(cm_main, 0, 3, mains_in, 2, mains_out));
  printf("first run (main): %.0f ms\n", now_ms() - t0);

  double total = 0, best = 1e9;
  static float prev_logits[WINDOW];
  for (int r = 0; r < runs; r++) {
    if (sleepms > 0) usleep(sleepms * 1000);
    double a = now_ms(); double dt;
    CHECK(LiteRtRunCompiledModel(cm_main, 0, 3, mains_in, 2, mains_out));
    // 计时必须包含输出回读同步:invoke 是异步提交,真实执行发生在 lock 时
    void* pv; float sum = 0;
    if (LiteRtLockTensorBuffer(out0, &pv, kLiteRtTensorBufferLockModeRead) == kLiteRtStatusOk) {
      const float* lg = (const float*)pv;
      dt = now_ms() - a;
      total += dt; if (dt < best) best = dt;
      for (int i = 0; i < WINDOW; i++) sum += lg[i];
      if (r == 0) memcpy(prev_logits, lg, sizeof(float) * WINDOW);
      else {
        int diffs = 0;
        for (int i = 0; i < WINDOW; i++) if (lg[i] != prev_logits[i]) diffs++;
        if (diffs) printf("  run %2d: OUTPUT DIFFERS in %d slots\n", r, diffs);
      }
      LiteRtUnlockTensorBuffer(out0);
    }
    (void)sum;
    printf("  run %2d: %.1f ms\n", r, dt);
  }
  printf("main graph: avg %.1f ms | best %.1f ms (%d runs)\n", total / runs, best, runs);

  // act head with dummy pooled/feats
  float pooled[HIDDEN];
  for (int i = 0; i < HIDDEN; i++) pooled[i] = 0.01f * i / HIDDEN;
  float feats[4] = {0.9f, 0.3f, 0.4f, 2.0f / 255.0f};
  fill(ain0, pooled, sizeof(pooled));
  fill(ain1, feats, sizeof(feats));
  LiteRtTensorBuffer acts_in[2] = {ain0, ain1};
  LiteRtTensorBuffer acts_out[1] = {aout0};
  t0 = now_ms();
  CHECK(LiteRtRunCompiledModel(cm_act, 0, 2, acts_in, 1, acts_out));
  printf("act graph: %.1f ms\n", now_ms() - t0);

  // read a few token_logits values at marker positions (32, 33, 34)
  void* p;
  CHECKM(LiteRtLockTensorBuffer(out0, &p, kLiteRtTensorBufferLockModeRead));
  const float* logits = (const float*)p;
  printf("token_logits[0..3] = %.4f %.4f %.4f %.4f | [32] = %.4f\n",
         logits[0], logits[1], logits[2], logits[3], logits[32]);
  { // hexdump 前 8 个 float 的位模式 + 全 256 里的统计
    unsigned total = 0, infs = 0, zeros = 0;
    for (int i = 0; i < 256; i++) {
      unsigned u; memcpy(&u, &logits[i], 4);
      total++;
      if ((u & 0x7f800000u) == 0x7f800000u) infs++;
      else if (u == 0) zeros++;
    }
    printf("hex[0..7] = %08x %08x %08x %08x %08x %08x %08x %08x | total=%u inf=%u zero=%u\n",
           ({ unsigned u; memcpy(&u, &logits[0], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[1], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[2], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[3], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[4], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[5], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[6], 4); u; }),
           ({ unsigned u; memcpy(&u, &logits[7], 4); u; }),
           total, infs, zeros);
  }
  LiteRtUnlockTensorBuffer(out0);
  void* q;
  CHECKM(LiteRtLockTensorBuffer(aout0, &q, kLiteRtTensorBufferLockModeRead));
  const float* act = (const float*)q;
  printf("act_logits = %.4f %.4f\n", act[0], act[1]);
  LiteRtUnlockTensorBuffer(aout0);
  return 0;
}
