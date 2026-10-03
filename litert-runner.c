// Laya resident runner, LiteRT edition (GPU fp32 main + CPU act head)
// Serves the SAME wire protocol as runner.c (onnx) so laya-native.mjs works unchanged:
//   Request  (20B hdr): u32 magic=0x4C415941 u8 cmd(0=infer,1=ping,2=exit) pad3 u32 n u32 L u32 K
//     infer payload: i64 input_ids[n*L], i64 attention[n*L], i64 marker_pos[n*K],
//                    u8 marker_mask[n*K], i64 qtype[n]
//   Response (16B hdr): u32 magic u8 status u8 act_dim u16 pad u32 n u32 K
//     ok payload: f32 logits[n*K] (marker logits), f32 act[n*2]
//     err payload: u32 msglen + msg
// Framing: u32 total length prefix on both directions (SOCK_STREAM).
//
// n must be 1 (LiteRT graphs are batch-1); JS side does batch1 loop like the multi model.
// usage: litert-runner <main.tflite> <act.tflite> <token_embeddings_fp16.bin> <socket-path> [gpucache-dir]
#include "litert/c/litert_common.h"
#include "litert/c/litert_environment.h"
#include "litert/c/litert_environment_options.h"
#include "litert/c/litert_model.h"
#include "litert/c/litert_compiled_model.h"
#include "litert/c/litert_tensor_buffer.h"
#include "litert/c/litert_opaque_options.h"
#include "litert/c/litert_options.h"
#include "litert/c/options/litert_gpu_options.h"
#include <errno.h>
#include <stddef.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <arpa/inet.h>
#include <netinet/in.h>
#include <math.h>
#include <fcntl.h>
#include <pthread.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/mman.h>
#include <sys/socket.h>
#include <sys/stat.h>
#include <sys/un.h>
#include <unistd.h>

#define MAGIC 0x4C415941u
#define HDR_REQ 20
#define HDR_RSP 16
#define WINDOW 256
#define HIDDEN 768
#define VOCAB 256000

#define CHECK(expr) do { \
  LiteRtStatus s__ = (expr); \
  if (s__ != kLiteRtStatusOk) { fprintf(stderr, "litert-runner: LITERT ERROR %d @ %s\n", (int)s__, #expr); exit(1); } \
} while (0)

static LiteRtEnvironment g_env;
static LiteRtCompiledModel g_cm_main, g_cm_act;
static LiteRtTensorBuffer g_in_embeds, g_in_mask, g_in_qtype, g_out_logits, g_out_pooled;
static LiteRtTensorBuffer g_a_in_pooled, g_a_in_feats, g_a_out;
static int g_act_out_dim = 2;
static const __fp16* g_embed_table;
static size_t g_embed_bytes;
static pthread_mutex_t g_mutex = PTHREAD_MUTEX_INITIALIZER;

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
  h[4] = 1;
  memcpy(h + 12, &mlen, 4);
  memcpy(out + 4 + HDR_RSP, msg, mlen);
  int rc = write_all(fd, out, total);
  free(out);
  return rc;
}

static LiteRtTensorBuffer make_buffer(LiteRtEnvironment env, LiteRtCompiledModel cm,
                                      LiteRtSignature sig, int idx, int is_output) {
  LiteRtTensor t;
  LiteRtStatus s = is_output ? LiteRtGetSignatureOutputTensorByIndex(sig, idx, &t)
                             : LiteRtGetSignatureInputTensorByIndex(sig, idx, &t);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "litert-runner: sig tensor %d err %d\n", idx, (int)s); exit(1); }
  LiteRtRankedTensorType tt;
  CHECK(LiteRtGetRankedTensorType(t, &tt));
  LiteRtTensorBufferRequirements req;
  s = is_output ? LiteRtGetCompiledModelOutputBufferRequirements(cm, 0, idx, &req)
                : LiteRtGetCompiledModelInputBufferRequirements(cm, 0, idx, &req);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "litert-runner: bufreq %d err %d\n", idx, (int)s); exit(1); }
  LiteRtTensorBuffer buf;
  CHECK(LiteRtCreateManagedTensorBufferFromRequirements(env, &tt, req, &buf));
  return buf;
}

static LiteRtOpaqueOptions gpu_opts(const char* cache_dir, const char* key) {
  LrtGpuOptions* gpu;
  CHECK(LrtCreateGpuOptions(&gpu));
  CHECK(LrtSetGpuAcceleratorCompilationOptionsPrecision(gpu, kLiteRtDelegatePrecisionFp32));
  CHECK(LrtSetGpuAcceleratorCompilationOptionsSerializationDir(gpu, cache_dir));
  CHECK(LrtSetGpuAcceleratorCompilationOptionsModelCacheKey(gpu, key));
  const char* id; void* payload; void (*dtor)(void*);
  CHECK(LrtGetOpaqueGpuOptionsData(gpu, &id, &payload, &dtor));
  LiteRtOpaqueOptions opaque;
  CHECK(LiteRtCreateOpaqueOptions(id, payload, dtor, &opaque));
  return opaque;
}

static void write_buf(LiteRtTensorBuffer buf, const void* data, size_t bytes) {
  void* p;
  LiteRtStatus s = LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeWrite);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "litert-runner: lock write err %d\n", (int)s); exit(1); }
  memcpy(p, data, bytes);
  LiteRtUnlockTensorBuffer(buf);
}
static void read_buf(LiteRtTensorBuffer buf, void* dst, size_t bytes) {
  void* p;
  LiteRtStatus s = LiteRtLockTensorBuffer(buf, &p, kLiteRtTensorBufferLockModeRead);
  if (s != kLiteRtStatusOk) { fprintf(stderr, "litert-runner: lock read err %d\n", (int)s); exit(1); }
  memcpy(dst, p, bytes);
  LiteRtUnlockTensorBuffer(buf);
}

static double now_ms_runner(void) {
  struct timespec ts; clock_gettime(CLOCK_MONOTONIC, &ts);
  return ts.tv_sec * 1000.0 + ts.tv_nsec / 1e6;
}

static int handle_infer(int fd, unsigned char* buf, size_t payload, unsigned int n, unsigned int L, unsigned int K) {
  if (n != 1) return send_error(fd, "litert-runner only supports n=1 (batch1 on host)");
  if (L == 0 || L > WINDOW || K == 0 || K > 64) return send_error(fd, "bad dimensions");
  size_t ids_len = (size_t)n * L, pos_len = (size_t)n * K;
  if (payload != ids_len * 16 + pos_len * 9 + n * 8) return send_error(fd, "payload size mismatch");

  const int64_t* ids64 = (const int64_t*)buf;                       // [L]
  const int64_t* att64 = (const int64_t*)(buf + ids_len * 8);       // [L]
  const int64_t* markers = (const int64_t*)(buf + ids_len * 16);    // [K]
  const unsigned char* mmask = buf + ids_len * 16 + pos_len * 8;    // [K]
  const int64_t* qtype = (const int64_t*)(buf + ids_len * 16 + pos_len * 9); // [1]

  // gather embedding rows (fp16 table -> fp32), attention mask, qtype onehot
  static float embeds[WINDOW * HIDDEN];
  static float mask[WINDOW];
  static float qonehot[3] = { 0, 0, 0 };
  if (*qtype < 0 || *qtype > 2) return send_error(fd, "bad qtype");
  memset(qonehot, 0, sizeof qonehot);
  qonehot[*qtype] = 1.0f;
  for (unsigned int i = 0; i < L; i++) {
    int64_t tok = ids64[i];
    if (tok < 0 || tok >= VOCAB) return send_error(fd, "token id out of range");
    const __fp16* row = g_embed_table + (size_t)tok * HIDDEN;
    float* dst = embeds + (size_t)i * HIDDEN;
    for (int h = 0; h < HIDDEN; h++) dst[h] = (float)row[h];
    mask[i] = att64[i] ? 1.0f : 0.0f;
  }
  double tp0 = now_ms_runner();
  write_buf(g_in_embeds, embeds, sizeof(float) * L * HIDDEN);
  double tp1 = now_ms_runner();
  write_buf(g_in_mask, mask, sizeof(float) * L);
  write_buf(g_in_qtype, qonehot, sizeof(qonehot));

  LiteRtTensorBuffer mins[3] = { g_in_embeds, g_in_mask, g_in_qtype };
  LiteRtTensorBuffer mouts[2] = { g_out_logits, g_out_pooled };
  double tr0 = now_ms_runner();
  LiteRtStatus s = LiteRtRunCompiledModel(g_cm_main, 0, 3, mins, 2, mouts);
  double tr1 = now_ms_runner();
  if (s != kLiteRtStatusOk) { char m[64]; snprintf(m, sizeof m, "main run failed: %d", (int)s); return send_error(fd, m); }

  static float logits[WINDOW];
  static float pooled[HIDDEN];
  read_buf(g_out_logits, logits, sizeof(float) * L);
  read_buf(g_out_pooled, pooled, sizeof(float) * HIDDEN);

  // gather marker logits (only masked markers)
  unsigned int k = 0;
  static float raw[64];
  for (unsigned int j = 0; j < K; j++)
    if (mmask[j] && markers[j] >= 0 && markers[j] < (int64_t)L) raw[k++] = logits[markers[j]];
  if (k == 0) return send_error(fd, "no valid markers");

  // act head features (laya_host.act_features)
  float top1 = -1e30f, top2 = -1e30f, ent = 0.0f;
  float mx = raw[0];
  for (unsigned int j = 1; j < k; j++) if (raw[j] > mx) mx = raw[j];
  float sum = 0.0f;
  static float probs[64];
  for (unsigned int j = 0; j < k; j++) { probs[j] = expf(raw[j] - mx); sum += probs[j]; }
  for (unsigned int j = 0; j < k; j++) probs[j] /= sum;
  for (unsigned int j = 0; j < k; j++) {
    ent -= probs[j] * logf(probs[j] > 1e-9f ? probs[j] : 1e-9f);
    if (probs[j] > top1) { top2 = top1; top1 = probs[j]; }
    else if (probs[j] > top2) top2 = probs[j];
  }
  float kk = (float)(k > 2 ? k : 2);
  float feats[4] = { top1, top1 - (top2 < 0 ? 0.0f : top2), ent / logf(kk), kk / 255.0f };

  write_buf(g_a_in_pooled, pooled, sizeof(float) * HIDDEN);
  write_buf(g_a_in_feats, feats, sizeof(feats));
  LiteRtTensorBuffer ains[2] = { g_a_in_pooled, g_a_in_feats };
  LiteRtTensorBuffer aouts[1] = { g_a_out };
  s = LiteRtRunCompiledModel(g_cm_act, 0, 2, ains, 1, aouts);
  if (s != kLiteRtStatusOk) { char m[64]; snprintf(m, sizeof m, "act run failed: %d", (int)s); return send_error(fd, m); }
  static float act[8];
  read_buf(g_a_out, act, sizeof(float) * g_act_out_dim);
  double tr2 = now_ms_runner();
  fprintf(stderr, "runner-perf: write=%.1f main=%.1f act=%.1f total=%.1f ms\n", tp1-tp0, tr1-tr0, tr2-tr1, tr2-tp0);

  // response: logits[n*K] (unmasked markers left 0), act[n*act_dim]
  size_t rsp_len = HDR_RSP + (size_t)K * 4 + (size_t)g_act_out_dim * 4;
  unsigned char* rsp = calloc(1, rsp_len + 4);
  unsigned int total = (unsigned int)rsp_len;
  memcpy(rsp, &total, 4);
  unsigned char* body = rsp + 4;
  body[0] = MAGIC & 0xff; body[1] = (MAGIC >> 8) & 0xff; body[2] = (MAGIC >> 16) & 0xff; body[3] = (MAGIC >> 24) & 0xff;
  body[4] = 0;
  body[5] = (unsigned char)g_act_out_dim;
  unsigned int nn = n, kk2 = K;
  memcpy(body + 8, &nn, 4);
  memcpy(body + 12, &kk2, 4);
  unsigned int j2 = 0;
  for (unsigned int j = 0; j < K; j++) {
    float v = (mmask[j] && markers[j] >= 0 && markers[j] < (int64_t)L) ? raw[j2++] : 0.0f;
    memcpy(body + HDR_RSP + (size_t)j * 4, &v, 4);
  }
  memcpy(body + HDR_RSP + (size_t)K * 4, act, (size_t)g_act_out_dim * 4);
  int rc = write_all(fd, rsp, rsp_len + 4);
  free(rsp);
  return rc;
}

static const char* g_sock_path = NULL;

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
    if (cmd == 2) { free(msg); close(fd); if (g_sock_path[0] != 64) unlink(g_sock_path); fprintf(stderr, "litert-runner: shutdown\n"); _exit(0); }
    if (cmd == 1) {
      unsigned char rsp[4 + HDR_RSP] = { 0 };
      unsigned int t2 = HDR_RSP;
      memcpy(rsp, &t2, 4);
      rsp[4] = MAGIC & 0xff; rsp[5] = (MAGIC >> 8) & 0xff; rsp[6] = (MAGIC >> 16) & 0xff; rsp[7] = (MAGIC >> 24) & 0xff;
      write_all(fd, rsp, sizeof rsp);
      free(msg);
      continue;
    }
    pthread_mutex_lock(&g_mutex);
    int rc = handle_infer(fd, msg + HDR_REQ, total - HDR_REQ, n, L, K);
    pthread_mutex_unlock(&g_mutex);
    free(msg);
    if (rc != 0) break;
  }
  close(fd);
  return NULL;
}

int main(int argc, char** argv) {
  if (argc < 5) { fprintf(stderr, "usage: %s <main.tflite> <act.tflite> <embeddings.bin> <socket-path> [gpucache-dir]\n", argv[0]); return 2; }
  const char* main_path = argv[1];
  const char* act_path = argv[2];
  const char* embed_path = argv[3];
  const char* sock_path = argv[4];
  const char* cache_dir = argc > 5 ? argv[5] : "/data/local/tmp/gpucache";
  g_sock_path = sock_path;
  signal(SIGPIPE, SIG_IGN);

  int efd = open(embed_path, O_RDONLY);
  if (efd < 0) { perror("open embeddings"); return 1; }
  g_embed_bytes = (size_t)VOCAB * HIDDEN * sizeof(__fp16);
  g_embed_table = mmap(NULL, g_embed_bytes, PROT_READ, MAP_PRIVATE, efd, 0);
  if (g_embed_table == MAP_FAILED) { perror("mmap embeddings"); return 1; }
  madvise((void*)g_embed_table, g_embed_bytes, MADV_RANDOM);

  int use_npu = getenv("LAYA_BACKEND") && strcmp(getenv("LAYA_BACKEND"), "npu") == 0;
  if (use_npu) {
    // NPU 模式:dispatch 库目录固定 /data/local/tmp/litert(libLiteRtDispatch_MediaTek.so 所在)
    static const char* disp_dir = "/data/local/tmp/litert";
    LiteRtEnvOption eopts[1] = {
        {kLiteRtEnvOptionTagDispatchLibraryDir,
         {kLiteRtAnyTypeString, {.str_value = disp_dir}}}};
    CHECK(LiteRtCreateEnvironment(1, eopts, &g_env));
  } else {
    CHECK(LiteRtCreateEnvironment(0, NULL, &g_env));
  }
  LiteRtModel main_m, act_m;
  CHECK(LiteRtCreateModelFromFile(g_env, main_path, &main_m));
  CHECK(LiteRtCreateModelFromFile(g_env, act_path, &act_m));

  LiteRtOptions opts;
  CHECK(LiteRtCreateOptions(&opts));
  if (use_npu) {
    // AOT 模型含 CPU 残余节点,必须 NPU|CPU,只 NPU 会 504
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorNpu | kLiteRtHwAcceleratorCpu));
    CHECK(LiteRtCreateCompiledModel(g_env, main_m, opts, &g_cm_main));
  } else {
    CHECK(LiteRtSetOptionsHardwareAccelerators(opts, kLiteRtHwAcceleratorGpu));
    LiteRtAddOpaqueOptions(opts, gpu_opts(cache_dir, "laya_ml_s256_wfp16_fp32"));
    CHECK(LiteRtCreateCompiledModel(g_env, main_m, opts, &g_cm_main));
  }
  bool fully = false;
  LiteRtCompiledModelIsFullyAccelerated(g_cm_main, &fully);
  fprintf(stderr, "litert-runner: main fully accelerated=%d\n", (int)fully);

  LiteRtOptions opts2;
  CHECK(LiteRtCreateOptions(&opts2));
  CHECK(LiteRtSetOptionsHardwareAccelerators(opts2, kLiteRtHwAcceleratorCpu));
  CHECK(LiteRtCreateCompiledModel(g_env, act_m, opts2, &g_cm_act));

  LiteRtSignature msig, asig;
  CHECK(LiteRtGetModelSignature(main_m, 0, &msig));
  CHECK(LiteRtGetModelSignature(act_m, 0, &asig));
  g_in_embeds = make_buffer(g_env, g_cm_main, msig, 0, 0);
  g_in_mask = make_buffer(g_env, g_cm_main, msig, 1, 0);
  g_in_qtype = make_buffer(g_env, g_cm_main, msig, 2, 0);
  g_out_logits = make_buffer(g_env, g_cm_main, msig, 0, 1);
  g_out_pooled = make_buffer(g_env, g_cm_main, msig, 1, 1);
  g_a_in_pooled = make_buffer(g_env, g_cm_act, asig, 0, 0);
  g_a_in_feats = make_buffer(g_env, g_cm_act, asig, 1, 0);
  g_a_out = make_buffer(g_env, g_cm_act, asig, 0, 1);
  // act output dim from tensor type
  LiteRtTensor t;
  CHECK(LiteRtGetSignatureOutputTensorByIndex(asig, 0, &t));
  LiteRtRankedTensorType tt;
  CHECK(LiteRtGetRankedTensorType(t, &tt));
  g_act_out_dim = tt.layout.rank >= 1 ? tt.layout.dimensions[tt.layout.rank - 1] : 1;
  if (g_act_out_dim < 1 || g_act_out_dim > 8) g_act_out_dim = 2;
  fprintf(stderr, "litert-runner: act_out_dim=%d, model ready\n", g_act_out_dim);

  int srv;
  socklen_t addr_len;
  struct sockaddr* addr_p;
  static struct sockaddr_in tcp_addr;
  struct sockaddr_un addr = { 0 };
  if (strncmp(sock_path, "tcp:", 4) == 0) {
    int port = atoi(sock_path + 4);
    if (port <= 0 || port > 65535) { fprintf(stderr, "bad tcp port\n"); return 1; }
    srv = socket(AF_INET, SOCK_STREAM, 0);
    tcp_addr.sin_family = AF_INET;
    tcp_addr.sin_port = htons((uint16_t)port);
    tcp_addr.sin_addr.s_addr = htonl(INADDR_LOOPBACK);
    addr_p = (struct sockaddr*)&tcp_addr;
    addr_len = sizeof tcp_addr;
    if (bind(srv, addr_p, addr_len) != 0) { perror("bind tcp"); return 1; }
    if (listen(srv, 2) != 0) { perror("listen"); return 1; }
    fprintf(stderr, "litert-runner: listening on 127.0.0.1:%d\n", port);
    goto accept_loop;
  }
  srv = socket(AF_UNIX, SOCK_STREAM, 0);
  addr.sun_family = AF_UNIX;
  if (sock_path[0] == '@') {
    // abstract namespace socket: no filesystem perms, works across shell/app domains
    size_t plen = strlen(sock_path + 1);
    if (plen >= sizeof addr.sun_path) { fprintf(stderr, "socket name too long\n"); return 1; }
    memcpy(addr.sun_path + 1, sock_path + 1, plen);
    addr_len = offsetof(struct sockaddr_un, sun_path) + 1 + plen;
  } else {
    unlink(sock_path);
    snprintf(addr.sun_path, sizeof addr.sun_path, "%s", sock_path);
    addr_len = sizeof addr;
  }
  if (bind(srv, (struct sockaddr*)&addr, addr_len) != 0) { perror("bind"); return 1; }
  if (sock_path[0] != '@') chmod(sock_path, 0777);
  if (listen(srv, 2) != 0) { perror("listen"); return 1; }
accept_loop:
  for (;;) {
    int fd = accept(srv, NULL, NULL);
    if (fd < 0) continue;
    pthread_t tid;
    if (pthread_create(&tid, NULL, conn_thread, (void*)(intptr_t)fd) == 0)
      pthread_detach(tid);
    else
      close(fd);
  }
}
