# laya-test — Laya 决策模型 Android/Termux 本地推理

在手机(Termux, aarch64)上运行 [Laya](https://huggingface.co/convaiinnovations/laya) System-1 决策模型:给定工单/文本(state)和类型化问题(choice/score/noul),单次前向返回带校准概率的答案,不生成文本、无幻觉。

## 架构

```
应用(Node/HTTP 客户端)
  │  tokenize + 拼序列(@huggingface/tokenizers + sequence.js,~2ms)
  ▼
TCP 127.0.0.1(GPU)/ Unix socket(长度前缀分帧协议,两端同款)
  ▼
┌─ LiteRT 路径(默认,推荐)────────────────────────────┐
│ litert-runner(C 守护进程,须在 adb shell 域运行)     │
│   └─ LiteRT 2.2.0 GPU delegate(fp32,Mali 天玑9500)  │
│      主图 17-19ms/问 + act 头(CPU)                    │
└───────────────────────────────────────────────────────┘
┌─ onnxruntime 路径 ────────────────────────────────────┐
│ runner(C 守护进程)                                    │
│   └─ 原生 onnxruntime 1.23(MLAS)→ int8 量化模型      │
└───────────────────────────────────────────────────────┘
```

- 两条路径**协议完全同款**,客户端(`laya-native.mjs`)零改动复用;JS 侧负责 tokenize/查表/温度校准/解码
- 模型在 `/sdcard/models/`,全程无 Python 依赖
- 单张工单(3 问)端到端:LiteRT GPU ~0.44s / multi int8 ORT ~1.2s

## 模型

| 模型 | 大小 | 语言 | 强项 | 切换 |
|---|---|---|---|---|
| **litert**(GPU,最快) | 250MB tflite + 393MB embedding 表 | 100+ 语言(mmBERT) | 官方 LiteRT 转换,GPU fp32,概率带官方温度校准 | `loadLitert()` |
| **multi**(ORT 默认) | 326MB int8 | 100+ 语言 | 中文/德文置信度高,sales/other recall 好 | 默认 |
| en | 606MB int8 + 606MB data | 英文训练 | billing/technical recall 好,urgency 有官方温度校准 | `LAYA_MODEL=en laya serve` |

LiteRT 模型集(litert-community/Laya-Multilingual-LiteRT,sha256 已验)放 `/sdcard/models/laya-litert/`;另有两款英文版(Laya-English-LiteRT / laya-LiteRT)可扩展。

## 快速开始

```bash
# 确保唤醒锁(防息屏降频)
termux-wake-lock

# 常驻 HTTP 服务(默认 multi 模型,端口 8787)
laya serve                # GET /health,POST /system-one
LAYA_MODEL=en laya serve  # 切英文 checkpoint

# 单次推理
echo '{"state":{"subject":"...","body":"..."},"questions":{...}}' | laya infer

# 工单分流
curl -s localhost:8787/system-one -d @ticket.json
```

问题类型(与官方 system_one API 同形):

```json
{
  "department": { "type": "choice", "instructions": "Which team?",
    "criteria": { "billing": "...", "technical": "..." } },
  "urgency": { "type": "score", "instructions": "How urgent?",
    "criteria": ["not urgent", "urgent", "critical"] },
  "intent": { "type": "choice", "instructions": "What does the user ask for?",
    "criteria": { "refund": "...", "fix": "...", "info": "..." } }
}
```

## 测试

```bash
node triage-test.mjs            # 11 用例分流回归(自动拉起 serve)
node validate-historical.mjs    # 真实历史工单回归(需 tickets.csv + sample.jsonl)
node e2e.mjs                    # native vs wasm 输出一致性
node e2e-litert.mjs             # LiteRT GPU 全链路(adb 自连,自动拉起 litert-runner)
node parity.mjs                 # LiteRT GPU vs multi ONNX 语义对齐
```

## 文件结构

| 文件 | 说明 |
|---|---|
| `litert-runner.c` | LiteRT 版 C 守护进程(GPU 主图 + CPU act 头,TCP/unix/abstract socket) |
| `laya-litert.mjs` | LiteRT 客户端:adb shell 域拉起 daemon + `loadLitert()` / `stopLitert()` |
| `e2e-litert.mjs` / `parity.mjs` | GPU 链路 E2E / 与 ONNX 路径语义对齐 |
| `litert-bench.c` | LiteRT GPU 基准(waittype/kbatch/priority/cbsteps/benchmode 旋钮 + 输出校验) |
| `runner.c` / `runner` | C 常驻推理守护进程(ORT C API) |
| `laya-native.mjs` | Node 客户端:tokenize → socket → 后处理,`LayaNative.loadWithDaemon()` |
| `cli.mjs` → `~/bin/laya` | CLI:`infer` / `serve` / `status` / `stop` |
| `bench.c` / `bench86` | 原生推理基准(SEQ/BATCH/OPTS 可编译期配置,nnapi/xnnpack EP) |
| `triage-test.mjs` | 工单分流 E2E 回归 |
| `validate-historical.mjs` | 真实历史工单全量回归 |
| `laya-litert/` | LiteRT 模型兼容目录(软链到 /sdcard/models/laya-litert + laya_config.json) |
| `laya-onnx-int8/`, `laya-multilingual-int8/` | 模型兼容目录(软链到 /sdcard/models) |
| `pylib/` | libpython3.12→3.14 符号链接(ORT 动态库依赖) |

## 性能(天玑 9500)

| 配置 | 单次推理(3 问) |
|---|---|
| wasm fp32(起点) | 4.2s |
| 原生 int8(bench,seq=86) | 0.63s |
| 真实工单端到端(multi ORT,seq≤512) | p50 1.2s |
| **LiteRT GPU fp32(窗口 256)** | **0.44s(纯推理 ~0.06s,强冷下 ~0.02s)** |

注意:GPU 长时间满载且无主动散热时会热节流到 ~140ms/问(仍 12x 于 ORT);静止降温后自动恢复。

详细演进见 `changelog.md`,踩坑经验见 `最佳实践.md`。

## 已知边界

- 零样本跨域(非训练分布的工单)准确率 ~38-42%,生产级需微调(官方路径 Kaggle 2×T4,0.362→0.766)
- multi 的 int8 导出未做 urgency 温度校准(分布偏平),urgency 断言仅对 en 有效
- noul 是非判别在中文上不可靠,退款意图请用判别式 choice(intent)
