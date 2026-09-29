// Native onnxruntime benchmark for Laya on Android (Termux, aarch64)
// usage: bench <model.onnx> [threads] [ep:cpu|xnnpack|nnapi]
#include "onnxruntime_c_api.h"
#include <stdio.h>
#include <time.h>
#include <stdlib.h>

#ifndef BATCH
#define BATCH 1
#endif
#define B BATCH
#ifndef SEQ
#define SEQ 256
#endif
#define L SEQ
#ifndef OPTS
#define OPTS 4
#endif
#define K OPTS

static const OrtApi* g_ort = NULL;

static double now_ms(void) {
  struct timespec ts;
  clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

#define CHECK(expr) do { OrtStatus* s = (expr); if (s) { const char* m = g_ort->GetErrorMessage(s); fprintf(stderr, "ORT error @%s: %s\n", #expr, m); g_ort->ReleaseStatus(s); return 1; } } while (0)

int main(int argc, char** argv) {
  if (argc < 2) { fprintf(stderr, "usage: %s <model.onnx> [threads] [ep]\n", argv[0]); return 2; }
  const char* model_path = argv[1];
  int threads = argc > 2 ? atoi(argv[2]) : 4;
  const char* ep = argc > 3 ? argv[3] : "cpu";

  g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  if (!g_ort) { fprintf(stderr, "no ort api\n"); return 1; }

  OrtEnv* env = NULL;
  CHECK(g_ort->CreateEnv(ORT_LOGGING_LEVEL_WARNING, "laya-bench", &env));

  OrtSessionOptions* sso = NULL;
  CHECK(g_ort->CreateSessionOptions(&sso));
  CHECK(g_ort->SetIntraOpNumThreads(sso, threads));
  CHECK(g_ort->SetInterOpNumThreads(sso, 1));
  if (strcmp(ep, "cpu") != 0) {
    OrtStatus* s = g_ort->SessionOptionsAppendExecutionProvider(sso, ep, NULL, NULL, 0);
    if (s) { fprintf(stderr, "EP '%s' not available: %s\n", ep, g_ort->GetErrorMessage(s)); g_ort->ReleaseStatus(s); return 3; }
  }

  double t0 = now_ms();
  OrtSession* session = NULL;
  CHECK(g_ort->CreateSession(env, model_path, sso, &session));
  double load_ms = now_ms() - t0;
  printf("model: %s | ep=%s threads=%d | load: %.0f ms\n", model_path, ep, threads, load_ms);

  OrtAllocator* alloc = NULL;
  CHECK(g_ort->GetAllocatorWithDefaultOptions(&alloc));
  OrtMemoryInfo* minfo = NULL;
  CHECK(g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &minfo));

  static int64_t input_ids[B * L], attention[B * L], marker_pos[B * K], qtype[B];
  static bool marker_mask[B * K];
  srand(42);
  for (int i = 0; i < B * L; i++) { input_ids[i] = rand() % 50000; attention[i] = 1; }
  for (int i = 0; i < B; i++) {
    qtype[i] = i % 3;
    for (int j = 0; j < K; j++) { marker_pos[i * K + j] = SEQ / 2 + j * 10; marker_mask[i * K + j] = true; }
  }

  OrtValue* t_ids = NULL; OrtValue* t_att = NULL; OrtValue* t_pos = NULL; OrtValue* t_msk = NULL; OrtValue* t_qt = NULL;
  const int64_t ids_dims[2] = { B, L };
  const int64_t pos_dims[2] = { B, K };
  const int64_t qt_dims[1] = { B };
  CHECK(g_ort->CreateTensorWithDataAsOrtValue(minfo, input_ids, sizeof(input_ids), ids_dims, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_ids));
  CHECK(g_ort->CreateTensorWithDataAsOrtValue(minfo, attention, sizeof(attention), ids_dims, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_att));
  CHECK(g_ort->CreateTensorWithDataAsOrtValue(minfo, marker_pos, sizeof(marker_pos), pos_dims, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_pos));
  CHECK(g_ort->CreateTensorWithDataAsOrtValue(minfo, marker_mask, sizeof(marker_mask), pos_dims, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &t_msk));
  CHECK(g_ort->CreateTensorWithDataAsOrtValue(minfo, qtype, sizeof(qtype), qt_dims, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &t_qt));

  const char* in_names[] = { "input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype" };
  OrtValue* inputs[] = { t_ids, t_att, t_pos, t_msk, t_qt };

  // discover output names dynamically (fp32: logits/act_probs, int8: logits/act_logits)
  size_t n_outs = 0;
  CHECK(g_ort->SessionGetOutputCount(session, &n_outs));
  if (n_outs > 8) n_outs = 8;
  const char* out_names[8] = { 0 };
  for (size_t i = 0; i < n_outs; i++) {
    char* nm = NULL;
    CHECK(g_ort->SessionGetOutputName(session, i, alloc, &nm));
    out_names[i] = nm;
  }
  OrtValue* outputs[8] = { 0 };

  for (int w = 0; w < 3; w++) CHECK(g_ort->Run(session, NULL, in_names, inputs, 5, out_names, 2, outputs));

  double total = 0, best = 1e9;
  for (int r = 0; r < 10; r++) {
    double a = now_ms();
    CHECK(g_ort->Run(session, NULL, in_names, inputs, 5, out_names, 2, outputs));
    double dt = now_ms() - a;
    total += dt; if (dt < best) best = dt;
    for (int o = 0; o < 2 && outputs[o]; o++) { g_ort->ReleaseValue(outputs[o]); outputs[o] = NULL; }
  }
  printf("run (batch=%d seq=%d opts=%d): avg %.0f ms | best %.0f ms (10 runs)\n", B, L, K, total / 10, best);
  for (size_t i = 0; i < n_outs; i++) g_ort->AllocatorFree(alloc, (void*)out_names[i]);
  return 0;
}
