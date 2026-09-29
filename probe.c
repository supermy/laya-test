#include "onnxruntime_c_api.h"
#include <stdio.h>
static const OrtApi* g_ort;
#define CHECK(x) do { OrtStatus* s=(x); if(s){ printf("ERR: %s\n", g_ort->GetErrorMessage(s)); return 1;} } while(0)
int main(int argc, char** argv) {
  g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  OrtEnv* env; OrtSession* ses; OrtSessionOptions* sso; OrtAllocator* alloc;
  CHECK(g_ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "p", &env));
  CHECK(g_ort->CreateSessionOptions(&sso));
  CHECK(g_ort->SetIntraOpNumThreads(sso, 4));
  CHECK(g_ort->CreateSession(env, argv[1], sso, &ses));
  CHECK(g_ort->GetAllocatorWithDefaultOptions(&alloc));
  size_t n; CHECK(g_ort->SessionGetInputCount(ses, &n));
  printf("== inputs (%zu) ==\n", n);
  for (size_t i = 0; i < n; i++) {
    char* nm; CHECK(g_ort->SessionGetInputName(ses, i, alloc, &nm));
    OrtTensorTypeAndShapeInfo* info = NULL;
    OrtStatus* st = g_ort->SessionGetInputTypeInfo(ses, i, &info);
    if (!st) {
      size_t nd = 0; g_ort->GetDimensionsCount(info, &nd);
      int64_t dims[8] = {0}; g_ort->GetDimensions(info, dims, nd);
      ONNXTensorElementDataType et; g_ort->GetTensorElementType(info, &et);
      printf("  %s : type=%d dims=[", nm, et);
      for (size_t d = 0; d < nd; d++) printf("%lld ", (long long)dims[d]);
      printf("]\n");
      g_ort->ReleaseTensorTypeAndShapeInfo(info);
    } else { printf("  %s : (non-tensor)\n", nm); g_ort->ReleaseStatus(st); }
    g_ort->AllocatorFree(alloc, nm);
  }
  CHECK(g_ort->SessionGetOutputCount(ses, &n));
  printf("== outputs (%zu) ==\n", n);
  for (size_t i = 0; i < n; i++) {
    char* nm; CHECK(g_ort->SessionGetOutputName(ses, i, alloc, &nm));
    OrtTensorTypeAndShapeInfo* info = NULL;
    OrtStatus* st = g_ort->SessionGetOutputTypeInfo(ses, i, &info);
    if (!st) {
      size_t nd = 0; g_ort->GetDimensionsCount(info, &nd);
      int64_t dims[8] = {0}; g_ort->GetDimensions(info, dims, nd);
      ONNXTensorElementDataType et; g_ort->GetTensorElementType(info, &et);
      printf("  %s : type=%d dims=[", nm, et);
      for (size_t d = 0; d < nd; d++) printf("%lld ", (long long)dims[d]);
      printf("]\n");
      g_ort->ReleaseTensorTypeAndShapeInfo(info);
    } else { printf("  %s : (non-tensor)\n", nm); g_ort->ReleaseStatus(st); }
    g_ort->AllocatorFree(alloc, nm);
  }
  return 0;
}
