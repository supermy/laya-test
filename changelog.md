# Changelog

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
