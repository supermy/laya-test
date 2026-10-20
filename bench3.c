// batch/split 自适应 bench:形状与输出数全部动态探测
// 用法: bench3b <main.tflite> <act.tflite|-> <npu|gpu> [runs]
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/litert_tensor_buffer.h"
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>

#define CHECK(x) do { LiteRtStatus s_=(x); if(s_!=kLiteRtStatusOk){ printf("ERR %d @%d\n",(int)s_,__LINE__); return 1;} } while(0)
#define WINDOW 256
#define HIDDEN 768

static double now_ms(void) { struct timespec ts; clock_gettime(CLOCK_MONOTONIC,&ts); return ts.tv_sec*1e3+ts.tv_nsec/1e6; }

static size_t total_elems(LiteRtSignature sig, int idx, int is_out) {
  LiteRtTensor t;
  if (is_out) LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t);
  else LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) return 0;
  size_t n = 1;
  for (int d = 0; d < tt.layout.rank; d++) n *= (size_t)tt.layout.dimensions[d];
  return n;
}

static LiteRtTensorBuffer mkbuf_req(LiteRtEnvironment env, LiteRtCompiledModel cm,
                                    LiteRtSignature sig, int idx, int is_out) {
  LiteRtTensor t;
  if (is_out) LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t);
  else LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) return NULL;
  LiteRtTensorBufferRequirements req;
  if (is_out) { if (LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, idx, &req) != kLiteRtStatusOk) return NULL; }
  else { if (LiteRtGetCompiledModelInputBufferRequirements(cm, 0, idx, &req) != kLiteRtStatusOk) return NULL; }
  LiteRtTensorBuffer buf;
  if (LiteRtCreateManagedTensorBufferFromRequirements(env, &tt, req, &buf) != kLiteRtStatusOk) return NULL;
  return buf;
}

static LiteRtTensorBuffer mkbuf_pad(LiteRtEnvironment env, LiteRtSignature sig, int idx, int is_out, int batch_factor) {
  LiteRtTensor t;
  if (is_out) LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t);
  else LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  LiteRtRankedTensorType tt;
  if (LiteRtGetRankedTensorType(t, &tt) != kLiteRtStatusOk) return NULL;
  size_t n = 1;
  for (int d = 0; d < tt.layout.rank; d++) n *= (size_t)tt.layout.dimensions[d];
  LiteRtTensorBuffer buf;
  if (LiteRtCreateManagedTensorBuffer(env, kLiteRtTensorBufferTypeHostMemory, &tt,
                                      n * 4 * batch_factor, &buf) != kLiteRtStatusOk) return NULL;
  return buf;
}

static void fill(LiteRtTensorBuffer buf, const void* data, size_t bytes) {
  void* p;
  if (LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeWrite) != kLiteRtStatusOk) return;
  memcpy(p, data, bytes);
  LiteRtUnlockTensorBuffer(buf);
}

int main(int argc, char** argv) {
  setvbuf(stdout, NULL, _IONBF, 0);
  if (argc < 4) { printf("usage: %s <main.tflite> <act.tflite|-> <npu|gpu> [runs]\n", argv[0]); return 2; }
  const char* main_path = argv[1];
  int use_npu = strcmp(argv[3], "npu") == 0;
  int runs = argc > 4 ? atoi(argv[4]) : 20;

  LiteRtEnvironment env;
  if (use_npu) {
    const char* disp_dir = getenv("LITERT_DISP_DIR");
    if (!disp_dir) disp_dir = "/data/local/tmp/litert";
    LiteRtEnvOption eopts[1] = {
        {kLiteRtEnvOptionTagDispatchLibraryDir,
         {kLiteRtAnyTypeString, {.str_value = disp_dir}}}};
    CHECK(LiteRtCreateEnvironment(1, eopts, &env));
  } else {
    CHECK(LiteRtCreateEnvironment(0, NULL, &env));
  }

  LiteRtModel main_m;
  CHECK(LiteRtCreateModelFromFile(env, main_path, &main_m));
  LiteRtOptions opts;
  CHECK(LiteRtCreateOptions(&opts));
  CHECK(LiteRtSetOptionsHardwareAccelerators(opts, use_npu ? (kLiteRtHwAcceleratorNpu | kLiteRtHwAcceleratorCpu)
                                                          : kLiteRtHwAcceleratorCpu));
  LiteRtCompiledModel cm_main;
  CHECK(LiteRtCreateCompiledModel(env, main_m, opts, &cm_main));
  fprintf(stderr, "CP: cm_main created\n");
  int fully = 0;
  LiteRtCompiledModelIsFullyAccelerated(cm_main, &fully);
  printf("fully accelerated: %s\n", fully ? "yes" : "no");

  LiteRtSignature msig;
  CHECK(LiteRtGetModelSignature(main_m, 0, &msig));

  LiteRtTensorBuffer in0 = mkbuf_req(env, cm_main, msig, 0, 0);
  LiteRtTensorBuffer in1 = mkbuf_req(env, cm_main, msig, 1, 0);
  LiteRtTensorBuffer in2 = mkbuf_req(env, cm_main, msig, 2, 0);
  if (!in0 || !in1 || !in2) { printf("input buffer alloc fail\n"); return 1; }
  size_t e_elems = total_elems(msig, 0, 0), m_elems = total_elems(msig, 1, 0), q_elems = total_elems(msig, 2, 0);
  int B = (m_elems && e_elems / m_elems) ? (int)(e_elems / m_elems) : 1;
  size_t per_row = m_elems / B;
  printf("in0=%zu in1=%zu in2=%zu floats | batch=%d\n", e_elems, m_elems, q_elems, B);

  int nout = 0;
  LiteRtTensor t;
  while (LiteRtGetSignatureOutputTensorByIndex(msig, nout, &t) == kLiteRtStatusOk) nout++;
  printf("signature outputs: %d\n", nout);

  static float embeds[WINDOW * HIDDEN * 4];
  static float mask[WINDOW * 8];
  static float qtype[32];
  if (e_elems > sizeof(embeds)/4 || m_elems > sizeof(mask)/4 || q_elems > 32) {
    printf("input too large for static arrays\n"); return 1;
  }
  for (size_t i = 0; i < e_elems; i++)
    embeds[i] = ((float)((i * 7919 + (i % per_row) * 131) % 2001) - 1000.0f) / 1000.0f * 0.1f;
  for (size_t i = 0; i < m_elems; i++) mask[i] = ((i % per_row) < per_row * 3 / 4) ? 1.0f : 0.0f;
  for (int r = 0; r < B; r++) qtype[r * 3 + r] = 1.0f;

  LiteRtTensorBuffer mains_in[3] = {in0, in1, in2};
  LiteRtTensorBuffer mouts[4];
  for (int i = 0; i < nout; i++) mouts[i] = mkbuf_req(env, cm_main, msig, i, 1);

  double t0 = now_ms();
  LiteRtStatus rs = LiteRtRunCompiledModel(cm_main, 0, 3, mains_in, nout, mouts);
  if (rs != kLiteRtStatusOk) {
    printf("first invoke failed %d - retry with padded host output buffers x%d\n", (int)rs, B);
    for (int i = 0; i < nout; i++) mouts[i] = mkbuf_pad(env, msig, i, 1, B);
    if (!mouts[0]) { printf("padded alloc fail\n"); return 1; }
    t0 = now_ms();
    CHECK(LiteRtRunCompiledModel(cm_main, 0, 3, mains_in, nout, mouts));
  }
  printf("first run: %.0f ms\n", now_ms()-t0);
  double total = 0, best = 1e9;
  for (int r = 0; r < runs; r++) {
    double a = now_ms();
    CHECK(LiteRtRunCompiledModel(cm_main, 0, 3, mains_in, nout, mouts));
    for (int i = 0; i < nout; i++) {
      void* pv;
      if (LiteRtLockTensorBuffer(mouts[i], &pv, kLiteRtTensorBufferLockModeRead) == kLiteRtStatusOk)
        LiteRtUnlockTensorBuffer(mouts[i]);
    }
    double dt = now_ms()-a;
    total += dt; if (dt < best) best = dt;
    printf("  run %2d: %.1f ms\n", r, dt);
  }
  printf("main graph: avg %.1f ms | best %.1f ms (%d runs, batch=%d)\n", total/runs, best, runs, B);
  return 0;
}
