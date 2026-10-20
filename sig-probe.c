#include "litert/c/litert_common.h"
#include "litert/c/litert_model.h"
#include <stdio.h>
#include <stdlib.h>

static const char* tname(LiteRtTensorBufferType t) {
  switch (t) {
    case kLiteRtTensorBufferTypeHostMemory: return "host";
    case kLiteRtTensorBufferTypeDmaBuf: return "dmabuf";
    case kLiteRtTensorBufferTypeAHardwareBuffer: return "ahwb";
    default: return "other";
  }
}

int main(int argc, char** argv) {
  LiteRtEnvironment env;
  if (LiteRtCreateEnvironment(0, NULL, &env) != kLiteRtStatusOk) { printf("env fail\n"); return 1; }
  LiteRtModel m;
  if (LiteRtCreateModelFromFile(argv[1], &m) != kLiteRtStatusOk) { printf("open fail\n"); return 1; }
  LiteRtSignature sig;
  if (LiteRtGetModelSignature(m, 0, &sig) != kLiteRtStatusOk) { printf("sig fail\n"); return 1; }
  LiteRtRankedTensorType tt; LiteRtTensor t;
  int ni = 0;
  while (LiteRtGetSignatureInputTensorByIndex(sig, ni, &t) == kLiteRtStatusOk) {
    LiteRtGetRankedTensorType(t, &tt);
    printf("in[%d] rank=%d dims=[", ni, tt.layout.rank);
    for (int d = 0; d < tt.layout.rank; d++) printf("%lld ", (long long)tt.layout.dimensions[d]);
    printf("] elem=%d\n", (int)tt.element_type);
    ni++;
  }
  int no = 0;
  while (LiteRtGetSignatureOutputTensorByIndex(sig, no, &t) == kLiteRtStatusOk) {
    LiteRtGetRankedTensorType(t, &tt);
    long long total = 1;
    printf("out[%d] rank=%d dims=[", no, tt.layout.rank);
    for (int d = 0; d < tt.layout.rank; d++) { printf("%lld ", (long long)tt.layout.dimensions[d]); total *= tt.layout.dimensions[d]; }
    printf("] elem=%d bytes=%lld\n", (int)tt.element_type, (long long)(total * 2));
    no++;
  }
  return 0;
}
