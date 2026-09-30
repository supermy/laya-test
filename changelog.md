# Changelog

## 2026-09-30 — v1.1.0:LiteRT GPU 后端(90x 提速)

### LiteRT GPU 路径打通(本次核心)

- 官方现成 tflite:`litert-community/Laya-Multilingual-LiteRT`(2026-09-29 发布,Apache-2.0);推荐集 679MB 存 `/sdcard/models/laya-litert/`(sha256 全验)
- 图吃 embedding 行(非 token id):host 负责 tokenize(`laya_host.py` 同款序列格式)/fp16 查表/attention_mask/qtype_onehot;主图出 token_logits+pooled_cls,act 头独立小图;decode 用官方 `laya_ml_calibration.json` 温度
- 新增 `litert-runner.c`:LiteRT 版常驻 runner,**与 runner.c 协议完全同款**(客户端零改动);fp16 查表 mmap→GPU 主图→marker gather→act 头(CPU);支持 `tcp:PORT` / `@abstract` / 文件 unix socket 三种监听
- 新增 `laya-litert.mjs`:`loadLitert()` 经 adb shell 域拉起 daemon(raw connect 探针轮询)+ `stopLitert()`;modelDir 兼容目录 `laya-litert/`(symlink + 含官方温度的 laya_config.json)
- **实测天玑 9500 Mali GPU fp32(窗口 256):纯推理 17-19ms/问**,3 问端到端 438ms;vs multi ONNX int8 1.67s/问 = **11x**(被动散热热节流时 ~140ms/问,仍 12x)
- 输出与 CPU XNNPACK 逐位一致(token_logits/act_logits 4 位小数);与 multi ONNX argmax 语义全同(parity.mjs)
- 工具链:libLiteRt.so 提取自 AAR `com.google.ai.edge.litert:litert:2.2.0`;Lrt* GPU options 帮助函数未导出 → SDK 的 3 个 .cc 随程序编译;编译宏 `-DLITERT_DISABLE_OPENGL_SUPPORT` 等走 stub 分支(无需 GL 头)

### 排除的死路(实测定论)

- **NNAPI EP**:termux ORT 1.23 带 NNAPI(legacy 符号手动 extern 可用),但 int8/fp32 都 **0/1841 节点**被接管(ModernBERT 图整体不支持),性能与纯 CPU 持平
- **XNNPACK EP**:分区边界 bug(注意力块 4D→2D reshape 链被改坏,输出少 84 倍数据),ORT_DISABLE_ALL 也不影响;救它需 ONNX 图手术且 MatMulNBits 本就不被 XNNPACK 支持,收益≈0
- **ORT WebGPU/Vulkan**:termux 构建未编译(dawn/vulkan 0 命中)
- **GPU 热节流台阶**:满载后 22ms→140ms 确定性跳变(6x);wait_type/kernel_batch/priority/命令缓冲步数全部无效;根因是 SoC 积累热状态触发 MTK 温控——强冷立即消除、静置自然恢复;静谧调频模式无影响

### 运维与工具

- adb 自连复通:android-tools 降级 35.0.2-7(本地版与 protobuf 符号不兼容)+ 重新配对;**shell 域可加载 app 域加载不了的 APEX/vendor 库**(NNAPI/GPU 都靠这个)
- `litert-bench.c`:LiteRT GPU 基准(6 旋钮 + 输出一致性校验),已入库
- 修复 `loadWithDaemon` 贵重试:重试循环先做 raw connect 探测,再 full load(旧逻辑每次重试解析 34MB tokenizer,最多假死十几分钟)

## 2026-09-29

### 多语言 checkpoint 接入(最新)

- 新增 multi 模型:`Torim98/laya-multilingual-int8`(mmBERT-base 322M,int8 单文件 326MB,100+ 语言)
- 兼容层(补丁进 `node_modules/@receptron/laya/dist/laya.js` 与 `laya-native.mjs`):
  - 输出重映射:`probs`(已 softmax)/ `act` ← 原格式 `logits` + `act_probs`
  - `config.fixed_seq_len`(导出固定 seq=512)
  - `config.batch1`(导出固定 batch=1,逐问推理后合并)
  - 特殊 token 回退链 `[CLS]→<s>→<bos>` 等(mmBERT 用 `<s>/<eos>/<mask>/<pad>`),`config.special_tokens` 可覆盖
- `cli.mjs` 支持 `LAYA_MODEL=multi|en` 切换,默认 multi;独立 socket `laya-multi.sock`
- `triage-test.mjs` 模型感知:urgency 断言仅 en 有效(multi 的 int8 导出 urgency 分布平)、路由阈值 multi 1.65 / en 1.5、EN 询价用例 expectMulti
- 实测:中文/德文置信度 0.99 级(en 0.84);同样本历史回归 38.1% 持平,分类特长互补;multi p50 1.2s

### 历史工单全量回归

- 新增 `validate-historical.mjs` + Python 分层抽样(`sample.jsonl` / `sample-multi.jsonl`)
- 数据集:Tobi-Bueck/customer-support-tickets(28,587 张 en/de 合成 IT 工单,经 hf-mirror)
- 结果(120 张):en 38.1% / multi 42%(不同样本)、同样本 38.1% 持平;与官方标注的跨域基线 0.362 吻合
- 修复:serve 每请求重建 LayaNative 导致 2GB 堆 OOM → 客户端缓存 + 断线重连

### 常驻 runner 桥接

- 新增 `runner.c`:C 守护进程,ORT C API,Unix socket(STREAM + 4 字节长度前缀帧),线程每连接 + Run 全局互斥
- 新增 `laya-native.mjs`:Node 客户端,复用 sequence.js 做 tokenize,后处理与 @receptron/laya 同形
- 端到端 627ms(421M fp32)/ 4396→1200ms(int8 真实工单)

### CLI 与 HTTP 服务

- `~/bin/laya`:`infer`(stdin JSON)/ `serve`(HTTP)/ `status` / `stop`
- stop 经 /proc 扫描 SIGKILL(Termux pkill/pgrep 匹配不到这些进程)

### int8 量化 + 原生 ORT

- 接入 `inferenceprince/laya-onnx-int8`(MatMulNBits block64,606MB):加载 11.5s→4.3s,精度几乎无损
- 原生 onnxruntime(Termux `python-onnxruntime` 包里的 `libonnxruntime.so`)经 C API 调用:
  - wasm fp32 4.2s → 原生 fp32 1.6s → 原生 int8 0.63s(seq=86 基准)
- 补丁 `laya.js`:`act_logits`→`act_probs` 转换(int8 导出输出名不同)
- `XNNPACK EP` 已编译进 Termux 版但对 ModernBERT 图不支持(Reshape 布局错误)

### 初次跑通

- `@receptron/laya` + onnxruntime-web WASM 垫片(`onnxruntime-node` 不支持 Android)
- hf-mirror 下载模型(直连 huggingface.co 挂死)
- 基线:wasm fp32 单次 4.2s,自建用例 11/11
