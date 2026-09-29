// Laya resident inference runner (Android/Termux, native onnxruntime C API)
// Loads the ONNX model once, serves inference over a Unix SOCK_SEQPACKET socket.
//
// Protocol (native little-endian):
// Request header (20B): u32 magic=0x4C415941 u8 cmd(0=infer,1=ping,2=exit) pad3 u32 n u32 L u32 K
//   infer payload: i64 input_ids[n*L], i64 attention[n*L], i64 marker_pos[n*K],
//                  u8 marker_mask[n*K], i64 qtype[n]
// Response header (16B): u32 magic u8 status(0=ok,1=err) u8 act_dim u16 pad u32 n u32 K
//   ok payload: f32 logits[n*K], f32 act[n*act_dim]
//   err payload: u32 msglen + msg
//
// usage: runner <model.onnx> <socket-path> [threads]
// Framing: every message (both directions) is prefixed with u32 total length,
// then that many bytes (STREAM socket).
#include "onnxruntime_c_api.h"
#include <errno.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/socket.h>
#include <sys/un.h>
#include <unistd.h>
#include <pthread.h>

#define MAGIC 0x4C415941u
#define HDR_REQ 20
#define HDR_RSP 16
#define MAX_OUTS 4

static const OrtApi* g_ort = NULL;
static pthread_mutex_t g_ort_mutex = PTHREAD_MUTEX_INITIALIZER;
static OrtSession* g_session = NULL;
static char g_out_names[MAX_OUTS][32];
static const char* g_out_ptr[MAX_OUTS];
static size_t g_n_outs = 0;

static void die(const char* what, OrtStatus* s) {
  fprintf(stderr, "runner: %s: %s\n", what, s ? g_ort->GetErrorMessage(s) : "unknown");
  if (s) g_ort->ReleaseStatus(s);
  exit(1);
}

static int read_exact(int fd, void* buf, size_t len) {
  size_t got = 0;
  while (got < len) {
    ssize_t r = read(fd, (char*)buf + got, len - got);
    if (r <= 0) return -1;
    got += (size_t)r;
  }
  return 0;
}

static int write_all(int fd, const void* buf, size_t len) {
  size_t sent = 0;
  while (sent < len) {
    ssize_t w = write(fd, (const char*)buf + sent, len - sent);
    if (w <= 0) return -1;
    sent += (size_t)w;
  }
  return 0;
}

static int send_error(int fd, const char* msg) {
  size_t mlen = strlen(msg);
  size_t total = HDR_RSP + mlen;
  unsigned char* out = malloc(total);
  unsigned int len = (unsigned int)total;
  memcpy(out, &len, 4);
  unsigned char* h = out + 4;
  h[0] = MAGIC & 0xff; h[1] = (MAGIC >> 8) & 0xff; h[2] = (MAGIC >> 16) & 0xff; h[3] = (MAGIC >> 24) & 0xff;
  h[4] = 1; // status err
  memcpy(h + 12, &mlen, 4);
  memcpy(out + 4 + HDR_RSP, msg, mlen);
  int rc = write_all(fd, out, total);
  free(out);
  return rc;
}

// serialized: ORT session is thread-safe for Run, but we keep ordering simple
typedef struct { int fd; unsigned char* buf; size_t payload; unsigned int n, L, K; } InferArgs;
static int handle_infer_inner(int fd, unsigned char* buf, size_t payload, unsigned int n, unsigned int L, unsigned int K) {
  if (n == 0 || L == 0 || K == 0 || n > 256 || L > 8192 || K > 64) {
    return send_error(fd, "bad dimensions");
  }
  size_t ids_len = (size_t)n * L, pos_len = (size_t)n * K;
  if (payload != ids_len * 16 + pos_len * 9 + n * 8) {
    return send_error(fd, "payload size mismatch");
  }

  int64_t* input_ids = (int64_t*)buf;
  int64_t* attention = (int64_t*)(buf + ids_len * 8);
  int64_t* marker_pos = (int64_t*)(buf + ids_len * 16);
  unsigned char* marker_mask = buf + ids_len * 16 + pos_len * 8;
  int64_t* qtype = (int64_t*)(buf + ids_len * 16 + pos_len * 9);

  OrtMemoryInfo* minfo = NULL;
  OrtStatus* st = g_ort->CreateCpuMemoryInfo(OrtArenaAllocator, OrtMemTypeDefault, &minfo);
  if (st) die("meminfo", st);

  const int64_t dims2[2] = { (int64_t)n, (int64_t)L };
  const int64_t dimsk[2] = { (int64_t)n, (int64_t)K };
  const int64_t dims1[1] = { (int64_t)n };
  OrtValue* inputs[5] = { 0 };
  st = g_ort->CreateTensorWithDataAsOrtValue(minfo, input_ids, ids_len * 8, dims2, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[0]);
  if (!st) st = g_ort->CreateTensorWithDataAsOrtValue(minfo, attention, ids_len * 8, dims2, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[1]);
  if (!st) st = g_ort->CreateTensorWithDataAsOrtValue(minfo, marker_pos, pos_len * 8, dimsk, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[2]);
  if (!st) st = g_ort->CreateTensorWithDataAsOrtValue(minfo, marker_mask, pos_len, dimsk, 2, ONNX_TENSOR_ELEMENT_DATA_TYPE_BOOL, &inputs[3]);
  if (!st) st = g_ort->CreateTensorWithDataAsOrtValue(minfo, qtype, n * 8, dims1, 1, ONNX_TENSOR_ELEMENT_DATA_TYPE_INT64, &inputs[4]);
  if (st) {
    for (int i = 0; i < 5; i++) if (inputs[i]) g_ort->ReleaseValue(inputs[i]);
    g_ort->ReleaseMemoryInfo(minfo);

    char msg[128];
    snprintf(msg, sizeof msg, "tensor create failed: %s", g_ort->GetErrorMessage(st));
    g_ort->ReleaseStatus(st);
    return send_error(fd, msg);
  }

  const char* in_names[] = { "input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype" };
  OrtValue* outputs[MAX_OUTS] = { 0 };
  st = g_ort->Run(g_session, NULL, in_names, (const OrtValue* const*)inputs, 5, g_out_ptr, g_n_outs, outputs);
  for (int i = 0; i < 5; i++) g_ort->ReleaseValue(inputs[i]);
  g_ort->ReleaseMemoryInfo(minfo);

  if (st) {
    for (size_t i = 0; i < g_n_outs; i++) if (outputs[i]) g_ort->ReleaseValue(outputs[i]);
    char msg[256];
    snprintf(msg, sizeof msg, "run failed: %s", g_ort->GetErrorMessage(st));
    g_ort->ReleaseStatus(st);
    return send_error(fd, msg);
  }

  // gather output data + dims
  float* out_data[MAX_OUTS] = { 0 };
  size_t out_elems[MAX_OUTS] = { 0 };
  unsigned int act_dim = 0;
  for (size_t i = 0; i < g_n_outs; i++) {
    OrtTensorTypeAndShapeInfo* info = NULL;
    st = g_ort->GetTensorTypeAndShape(outputs[i], &info);
    if (st) die("getshape", st);
    size_t ndim = 0;
    g_ort->GetDimensionsCount(info, &ndim);
    int64_t dims[8] = { 0 };
    g_ort->GetDimensions(info, dims, ndim);
    size_t elems = 1;
    for (size_t d = 0; d < ndim; d++) elems *= (size_t)dims[d];
    if (i == 1) act_dim = ndim >= 2 ? (unsigned int)dims[1] : 1;
    g_ort->ReleaseTensorTypeAndShapeInfo(info);
    float* data = NULL;
    st = g_ort->GetTensorMutableData(outputs[i], (void**)&data);
    if (st) die("getdata", st);
    out_data[i] = data;
    out_elems[i] = elems;
  }

  size_t rsp_len = HDR_RSP + (out_elems[0] + out_elems[1] > 0 ? out_elems[0] * 4 + out_elems[1] * 4 : 0);
  unsigned char* rsp = calloc(1, rsp_len + 4);
  unsigned int total = (unsigned int)rsp_len;
  memcpy(rsp, &total, 4);
  unsigned char* body = rsp + 4;
  body[0] = MAGIC & 0xff; body[1] = (MAGIC >> 8) & 0xff; body[2] = (MAGIC >> 16) & 0xff; body[3] = (MAGIC >> 24) & 0xff;
  body[4] = 0; // ok
  body[5] = (unsigned char)act_dim;
  unsigned int nn = (unsigned int)n, kk = (unsigned int)K;
  memcpy(body + 8, &nn, 4);
  memcpy(body + 12, &kk, 4);
  memcpy(body + HDR_RSP, out_data[0], out_elems[0] * 4);
  if (g_n_outs > 1) memcpy(body + HDR_RSP + out_elems[0] * 4, out_data[1], out_elems[1] * 4);

  int rc = write_all(fd, rsp, rsp_len + 4);
  for (size_t i = 0; i < g_n_outs; i++) if (outputs[i]) g_ort->ReleaseValue(outputs[i]);
  free(rsp);
  return rc;
}

static const char* g_sock_path = NULL;
static volatile int g_shutdown = 0;

static void* conn_thread(void* arg) {
  int fd = (int)(intptr_t)arg;
  for (;;) {
    unsigned int total = 0;
    if (read_exact(fd, &total, 4) != 0 || total < HDR_REQ || total > 64u * 1024 * 1024) break;
    unsigned char* msg = malloc(total);
    if (!msg) break;
    if (read_exact(fd, msg, total) != 0) { free(msg); break; }
    unsigned int magic;
    memcpy(&magic, msg, 4);
    if (magic != MAGIC) { free(msg); break; }
    unsigned char cmd = msg[4];
    unsigned int n, L, K;
    memcpy(&n, msg + 8, 4);
    memcpy(&L, msg + 12, 4);
    memcpy(&K, msg + 16, 4);
    if (cmd == 2) { free(msg); close(fd); unlink(g_sock_path); fprintf(stderr, "runner: shutdown requested\n"); _exit(0); }
    if (cmd == 1) {
      unsigned char rsp[4 + HDR_RSP] = { 0 };
      unsigned int t2 = HDR_RSP;
      memcpy(rsp, &t2, 4);
      rsp[4] = MAGIC & 0xff; rsp[5] = (MAGIC >> 8) & 0xff; rsp[6] = (MAGIC >> 16) & 0xff; rsp[7] = (MAGIC >> 24) & 0xff;
      write_all(fd, rsp, sizeof rsp);
      free(msg);
      continue;
    }
    pthread_mutex_lock(&g_ort_mutex);
    int rc = handle_infer_inner(fd, msg + HDR_REQ, total - HDR_REQ, n, L, K);
    pthread_mutex_unlock(&g_ort_mutex);
    free(msg);
    if (rc != 0) break;
  }
  close(fd);
  return NULL;
}

int main(int argc, char** argv) {
  if (argc < 3) { fprintf(stderr, "usage: %s <model.onnx> <socket-path> [threads]\n", argv[0]); return 2; }
  const char* model_path = argv[1];
  const char* sock_path = argv[2];
  g_sock_path = sock_path;
  int threads = argc > 3 ? atoi(argv[3]) : 6;
  signal(SIGPIPE, SIG_IGN);

  g_ort = OrtGetApiBase()->GetApi(ORT_API_VERSION);
  OrtEnv* env = NULL;
  OrtStatus* st = g_ort->CreateEnv(ORT_LOGGING_LEVEL_ERROR, "laya-runner", &env);
  if (st) die("env", st);
  OrtSessionOptions* sso = NULL;
  st = g_ort->CreateSessionOptions(&sso);
  if (st) die("sso", st);
  g_ort->SetIntraOpNumThreads(sso, threads);
  g_ort->SetInterOpNumThreads(sso, 1);
  fprintf(stderr, "runner: loading %s (threads=%d)...\n", model_path, threads);
  st = g_ort->CreateSession(env, model_path, sso, &g_session);
  if (st) die("session", st);

  OrtAllocator* alloc = NULL;
  g_ort->GetAllocatorWithDefaultOptions(&alloc);
  st = g_ort->SessionGetOutputCount(g_session, &g_n_outs);
  if (st) die("outcount", st);
  if (g_n_outs > MAX_OUTS) g_n_outs = MAX_OUTS;
  for (size_t i = 0; i < g_n_outs; i++) {
    char* nm = NULL;
    st = g_ort->SessionGetOutputName(g_session, i, alloc, &nm);
    if (st) die("outname", st);
    snprintf(g_out_names[i], sizeof g_out_names[i], "%s", nm);
    g_ort->AllocatorFree(alloc, nm);
    fprintf(stderr, "runner: output[%zu]=%s\n", i, g_out_names[i]);
  }
  for (size_t i = 0; i < g_n_outs; i++) g_out_ptr[i] = g_out_names[i];
  fprintf(stderr, "runner: model ready\n");

  unlink(sock_path);
  int srv = socket(AF_UNIX, SOCK_STREAM, 0);
  struct sockaddr_un addr = { 0 };
  addr.sun_family = AF_UNIX;
  snprintf(addr.sun_path, sizeof addr.sun_path, "%s", sock_path);
  if (bind(srv, (struct sockaddr*)&addr, sizeof addr) != 0) { perror("bind"); return 1; }
  if (listen(srv, 2) != 0) { perror("listen"); return 1; }

  for (;;) {
    int fd = accept(srv, NULL, NULL);
    if (fd < 0) continue;
    pthread_t tid;
    if (pthread_create(&tid, NULL, conn_thread, (void*)(intptr_t)fd) == 0)
      pthread_detach(tid);
    else
      close(fd);
    if (g_shutdown) break;
  }
done:
  close(srv);
  unlink(sock_path);
  return 0;
}
