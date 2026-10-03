# laya-test — Laya 决策模型 Android/Termux 本地推理

在手机(Termux, aarch64)上运行 [Laya](https://huggingface.co/convaiinnovations/laya) System-1 决策模型:给定工单/文本(state)和类型化问题(choice/score/noul),单次前向返回带校准概率的答案,不生成文本、无幻觉。

## 架构

```
应用(Node/HTTP 客户端 / Android APK)
  │  tokenize + 拼序列(@huggingface/tokenizers + sequence.js,~2ms)
  ▼
TCP 127.0.0.1(GPU)/ Unix socket(长度前缀分帧协议,两端同款)
  ▼
┌─ LiteRT 路径(默认,推荐)────────────────────────────┐
│ litert-runner(C 守护进程,须在 adb shell 域运行)     │
│   └─ LiteRT 2.2.0 GPU delegate(fp32,Mali 天玑9500)  │
│      ~150ms/问(异步提交+回读同步)+ act 头(CPU)       │
├─ NPU AOT 路径(最快)─────────────────────────────────┤
│ PC 上 MTK 编译插件 aot_compile → dispatch tflite      │
│   └─ litert-bench npu 模式:NPU|CPU,~57ms/问          │
└───────────────────────────────────────────────────────┘
┌─ onnxruntime 路径 ────────────────────────────────────┐
│ runner(C 守护进程)                                    │
│   └─ 原生 onnxruntime 1.23(MLAS)→ int8 量化模型      │
└───────────────────────────────────────────────────────┘
```

- 两条路径**协议完全同款**,客户端(`laya-native.mjs`)零改动复用;JS 侧负责 tokenize/查表/温度校准/解码
- 模型在 `/sdcard/models/`,全程无 Python 依赖
- 单张工单(3 问)端到端:LiteRT GPU ~0.45s / multi int8 ORT ~1.2s

## 模型

| 模型 | 大小 | 语言 | 强项 | 切换 |
|---|---|---|---|---|
| **litert**(GPU,推荐) | 251MB wfp16 tflite + 393MB embedding 表 | 100+ 语言(mmBERT) | 官方 LiteRT 转换,GPU,概率带官方温度校准 | `loadLitert()` |
| **multi**(ORT 默认) | 326MB int8 | 100+ 语言 | 中文/德文置信度高,sales/other recall 好 | 默认 |
| en | 606MB int8 + 606MB data | 英文训练 | billing/technical recall 好,urgency 有官方温度校准 | `LAYA_MODEL=en laya serve` |
| **sms**(微调) | int8 | 中文 | 垃圾短信/骚扰判别(80 万条真实短信微调) | `LAYA_MODEL=sms laya serve` |
| ticket / ugc / agent / risk / stock(微调) | int8 | 中文 | 四+一业务微调模型,`/sdcard/models/laya-*-int8/` | service 自动发现 |

LiteRT 模型集(litert-community/Laya-Multilingual-LiteRT,sha256 已验)放 `/sdcard/models/laya-litert/`;微调业务 LiteRT 版放 `/sdcard/models/laya-litert-{task}/`(5060 上自研转换链产出,精度=ckpt)。

## 微调(云端 GPU)

`finetune/` 四脚本:`prepare_data.py`(数据切分)→ `train_sms.py`(RLCD,`--task` 任务化)→ `evaluate.py`(choice acc/F1 + noul P/R/F1/ECE)→ `export_onnx.py`(fp32+int8,签名与现网对齐)。训练在 5060 PC(RTX 5060 Ti),详见 `finetune/parity-report.md` 与 `examples_guide.md`。注意:int8 导出会使 noul 温度校准失效(ECE 0.03→0.23-0.30),概率阈值部署前须手机端重校准;LiteRT fp32/wfp16 路径无此问题。

## 快速开始

```bash
# 确保唤醒锁(防息屏降频)
termux-wake-lock

# 常驻 HTTP 服务(默认 multi 模型,端口 8787)
laya serve                # GET /health,POST /system-one
LAYA_MODEL=en laya serve  # 切英文 checkpoint
LAYA_MODEL=sms laya serve # 垃圾短信判别,另有 POST /sms {"text":"..."}

# 多业务服务(端口 8789,自动发现 /sdcard/models/laya-*-int8)
node service/server.mjs   # GET /tasks,POST /task/:id,GET /report/daily

# 单次推理
echo '{"state":{"subject":"...","body":"..."},"questions":{...}}' | laya infer

# 工单分流
curl -s localhost:8787/system-one -d @ticket.json

# 垃圾短信判别
curl -s localhost:8787/sms -d '{"text":"短信正文"}'
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
node parity-task.mjs            # 微调任务手机端对拍(metrics 口径与 evaluate.py 对齐)
node parity-litert.mjs          # 微调任务 LiteRT GPU 版对拍
node sms-e2e-test.mjs           # SMS 判别管路测试(multi 模型+覆盖问题定义)
```

## 文件结构

| 文件/目录 | 说明 |
|---|---|
| `litert-runner.c` | LiteRT 版 C 守护进程(GPU 主图 + CPU act 头,TCP/unix/abstract socket) |
| `laya-litert.mjs` | LiteRT 客户端:adb shell 域拉起 daemon + `loadLitert()` / `stopLitert()` |
| `e2e-litert.mjs` / `parity.mjs` | GPU 链路 E2E / 与 ONNX 路径语义对齐 |
| `litert-bench.c` | LiteRT 基准(GPU/NPU 旋钮 + 输出一致性校验;计时含回读同步) |
| `runner.c` / `runner` | C 常驻推理守护进程(ORT C API) |
| `laya-native.mjs` | Node 客户端:tokenize → socket → 后处理,`LayaNative.loadWithDaemon()` / `smsInfer()` |
| `cli.mjs` → `~/bin/laya` | CLI:`infer` / `serve` / `status` / `stop` |
| `service/` | 多业务决策服务:自适配注册表 + 决策日志报表 + 邮件/MQTT 网关(端口 8789) |
| `finetune/` | 微调管线:数据准备/RLCD 训练/评估/导出 + 任务定义 + parity 报告 |
| `apk/` | Android APK:LiteRT GPU 内置推理(JNI C API)+ 动态业务 + 网关 + 微信风 UI |
| `bench.c` / `bench86` | 原生推理基准(SEQ/BATCH/OPTS 可编译期配置,nnapi/xnnpack EP) |
| `triage-test.mjs` | 工单分流 E2E 回归 |
| `validate-historical.mjs` | 真实历史工单全量回归 |
| `laya-litert/` | LiteRT 模型兼容目录(软链到 /sdcard/models/laya-litert + laya_config.json) |
| `laya-onnx-int8/`, `laya-multilingual-int8/`, `laya-sms-int8/` | 模型兼容目录(软链到 /sdcard/models) |
| `pylib/` | libpython3.12→3.14 符号链接(ORT 动态库依赖) |

## 性能(天玑 9500,窗口 256,含回读同步)

| 配置 | 单次推理(3 问) |
|---|---|
| wasm fp32(起点) | 4.2s |
| 原生 int8(bench,seq=86) | 0.63s |
| 真实工单端到端(multi ORT,seq≤512) | p50 1.2s |
| **LiteRT GPU fp32/wfp16** | **~0.45s(~150ms/问,冷热基本无关)** |
| **NPU AOT(MTK dispatch,PC 预编译)** | **~0.17s(~57ms/问,目前最快)** |

注意:此前"GPU 17-19ms/问"是计时口径错误(异步提交只计了提交,真实执行在回读);修正后各加速器真实排序为 GPU ~150ms < CPU int8 254-347ms < CPU fp32 ~309ms。NPU 设备侧 JIT 不可用(Neuron UNMAPPABLE),唯一路径是 PC 上 AOT 编译 dispatch tflite。

详细演进见 `changelog.md`,踩坑经验见 `最佳实践.md`。

## 已知边界

- 零样本跨域(非训练分布的工单)准确率 ~38-42%,生产级需微调(官方路径 Kaggle 2×T4,0.362→0.766);微调管线已就位(`finetune/`),ticket 真实数据微调后 67.4%
- int8 导出使 noul 温度校准失效(ECE 0.03→0.23-0.30),概率阈值部署前须手机端重校准;LiteRT fp32/wfp16 路径无此问题
- multi 的 int8 导出未做 urgency 温度校准(分布偏平),urgency 断言仅对 en 有效
- noul 是非判别在中文上不可靠,退款意图请用判别式 choice(intent)
- ugc/agent/risk 微调数据为构造式合成(冷启动),分布≠真实黑产/风控,上线前换真实语料
