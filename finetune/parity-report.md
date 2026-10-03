# 四示例微调模型 · 端侧部署与 Parity 报告

日期:2026-10-02 · 训练机:RTX 5060 Ti 16G(my@192.168.0.168,~/finetune) · 端机:天玑 9500 手机(Termux + onnxruntime CPU)

## 一、流水线总览

```
数据(本地构造/公开集) → 上传 5060 → RLCD 训练 4ep(bf16, 322M 全参)
  → 测试集评测 → ONNX fp32 导出(seq 160, opset 17, dynamo=False) → int8 动态 per-channel
  → 打包回传手机 → /sdcard/models/laya-{task}-int8/ → 手机端 parity 对拍
```

| 任务 | 训练量(train/test) | 耗时 | 数据性质 |
|---|---|---|---|
| ticket | 26,673 / 1,914(Tobi-Bueck 真实工单) | 46min | **真实数据** |
| ugc | 14,160 / 840 | 29min | 合成冷启动 |
| agent | 2,700 / 300 | 9min | 合成冷启动 |
| risk | 6,795 / 405 | 16min | 合成冷启动 |

产物(5060 `~/finetune/`):`laya-{task}-results.tar.gz`(fp32+int8 全量,留档)与 `laya-{task}-int8only.tar.gz`(已回传)。手机:`/sdcard/models/laya-{ticket,ugc,agent,risk}-int8/`,各 375MB(int8 324MB + tokenizer + laya_config + manifest)。

## 二、Parity 对照(5060 checkpoint bf16 vs 手机 int8 ORT CPU,各 150 条)

| 任务 | 指标 | ckpt | 手机 int8 | 判定 |
|---|---|---|---|---|
| ticket | choice acc | 0.6735 | 0.6133 | −6.0pp:int8 量化漂移(导出校验 argmax 一致 19/24)+ 小类翻转 + 小样本噪声,**可接受** |
| ticket | score MAE / within-1 | 1.051 / 0.478 | 1.096 / 0.540 | 持平(±0.05) |
| ugc | choice acc | 1.0000 | 1.0000 | ✅ 完全一致 |
| ugc | noul F1 | 0.7213 | 0.8421 | ✅ 反而更好 |
| agent | choice acc | 1.0000 | 1.0000 | ✅ 完全一致 |
| agent | noul F1 | 0.8889 | 0.8966 | ✅ |
| risk | choice acc | 1.0000 | 1.0000 | ✅ 完全一致 |
| risk | noul F1 | 0.7759 | 0.7356 | −0.04,可接受 |
| 全部 | score MAE | 0.93–1.05 | 1.05–1.32 | +0.05~0.35:期望分被量化抹平 |

**注意两处口径**:score 真值是 1-indexed,预测是 0-indexed 期望,两侧同公式,MAE 里含 ~1 的固定偏移(不影响对拍可比性);ticket noul 无真值(escalate 未标),两侧都无该项。

### ⚠️ 关键发现:noul ECE 校准漂移

| 任务 | noul ECE (ckpt) | noul ECE (手机 int8) |
|---|---|---|
| ugc | 0.039 | **0.302** |
| agent | 0.029 | **0.274** |
| risk | 0.045 | **0.230** |

argmax 决策在 int8 后基本保真,但 **logits 幅度被量化扰动,softmax 概率形状变形,训练期温度校准失效**(ECE 0.03-0.05 → 0.23-0.30)。

**影响**:所有依赖概率的部署逻辑(ugc 高置信自动处置 p>0.9、risk 复核阈值、ticket 期望分排序)不能直接搬 ckpt 阈值。

**处置建议**(按优先级):
1. 手机端用 test 片重拟合 int8 温度(改 `laya_config.json` 的 `temperature_by_options`/`temperature`,无需重训)
2. 或部署前按手机端 PR 曲线重选阈值(管线现成:evaluate 同款脚本已在手机跑通)
3. 长期:导出后加 int8-aware 校准步(5060 上用 int8 输出拟合温度再回填 config)

### 延迟

| 环境 | p50/问 |
|---|---|
| 5060 GPU(bf16) | 9.6-9.7ms |
| 手机 ORT CPU int8(seq160) | **254-347ms** |
| 参照:现网 multi int8 seq512 | ~650ms |

任务专用 + seq160 使端侧吞吐翻倍;三问全跑 ~0.8-1s/条,CPU 上完全可用。若要再压,走 LiteRT GPU(现网 laya-litert 链路 ~18ms/问)。

## 三、指标意义说明(防误读)

- **ticket 是唯一真实数据**:67.4%(ckpt)是有效泛化数字;小类(销售咨询/退换货)recall ~0.3 是类别不平衡(918/1437 vs 17047),v2 用 oversample/类权重
- **ugc/agent/risk choice 100% 只证明管线正确**:测试集与训练集同模板生成器,不代表真实分布;上线前必须换真实语料(ugc 黑产评论、agent 自有日志、risk 自有申请文本)混训或替换
- **score MAE ~1 有结构性成分**:ticket urgency 真值仅 3 档(2/3/4),差一档即 MAE 1

## 四、部署清单

- 手机模型:`/sdcard/models/laya-{ticket,ugc,agent,risk}-int8/`(已就位,校验过文件大小一致)
- CLI 接入:`cli.mjs` 的 `MODELS` map 加 4 个条目(socket 同款模式)
- runner 启动需 `LD_LIBRARY_PATH` 含 onnxruntime capi 目录(cli.mjs daemonEnv() 同款),`parity-task.mjs` 是可复用范式
- 完整指标:`metrics-{task}.json`(5060 侧)、`phone-metrics-{task}.json`(手机侧,含 vs 对比块)

## 六、LiteRT GPU 路径(2026-10-02 追加,已完成)

### 转换链(自研,5060 `~/litert-conv/`)

onnx2tf 对 ModernBERT RoPE 的 Expand 算子无解 → 改走 **litert-torch**(ai-edge-torch 更名版)直转。HF 图直转后 GPU delegate 编译失败(rank≥5 RESHAPE/SLICE/TRANSPOSE,仅 78/1717 节点上 GPU)→ **手写干净版前向 `clean_main.py`**(权重零拷贝引用原模块,全 rank≤4 算子,RoPE cos/sin 与滑窗 band 预计算为 buffer——常量上的 ABS 会导致 GPU 编译 504)。

等价验证(CleanMain vs HF,真实行区域):4e-7 ~ 5e-6,PASS×4。tflite CPU 解释器复验同过。

**语义注记**:clean 图不复刻 HF 对 padding query 行的整行遮蔽(slide 层)——该差异仅存在于 padding 位置,marker 恒在真实序列内,host 永不 gather,无影响。

### 三路对照(ckpt bf16 / 手机 CPU int8 / 手机 GPU fp32,各 100 行)

| 任务 | 指标 | ckpt | CPU int8 | GPU fp32 |
|---|---|---|---|---|
| ticket | choice acc | 0.6735 | 0.6133 | **0.6900** ✅ 无量化漂移 |
| ticket | score MAE | 1.051 | 1.096 | 1.085 |
| ugc | choice acc | 1.0 | 1.0 | 1.0 |
| ugc | noul ECE | 0.039 | **0.302** | **0.059** ✅ 校准保真 |
| agent | noul ECE | 0.029 | **0.274** | **0.041** ✅ |
| risk | noul ECE | 0.045 | **0.230** | **0.057** ✅ |
| risk | score MAE | 0.929 | 1.216 | 0.981 |
| 全部 | p50/行(3 问) | 9.6-9.7ms | 254-347ms | 444-461ms(热节流) |

**结论**:
1. **GPU fp32 路径精度 = checkpoint**(无 int8 漂移,ECE 校准保真)——int8 路径的两大缺陷(精度漂移+校准失效)全部解决
2. **延迟**:实测 ~150ms/问为**热节流档**(整机连续数小时负载后的 MTK 温控,与官方模型行为一致);官方基准冷态 17-25ms/问——静置/强冷后按 ~20ms/问预期(3 问 ~60ms/行,CPU int8 的 4-6 倍)。生产判断:设备热时 GPU≈CPU int8,冷时 GPU 大胜
3. 模型体积:fp32 主图 501MB(官方 wfp16 为 250MB)→ fp16 权重压缩可再省一半带宽,预期冷态延迟进一步下降(后续优化)

### GPU 部署要素

- 模型:`/sdcard/models/laya-litert-{task}/`(主图 tflite + act tflite + token_embeddings_fp16.bin + laya_config.json + tokenizer/ 双文件)
- runner:`/data/local/tmp/litert/litert-runner`(adb shell 域;GPU 需系统 EGL 库),参数化 `<main> <act> <embed> <socket> [gpucache]`
- 客户端:`parity-litert.mjs`(adb 拉起 + LayaNative 同款协议);cli.mjs 集成时复用 loadLitert 参数化
- 每任务独立 gpucache 目录;僵死 runner 的端口用换端口绕过(pkill 对跨域进程 EPERM)


## 五、遗留项(更新)

1. ~~int8 温度重校准~~ → GPU fp32 路径校准保真,优先用 GPU 路径;CPU int8 路径如需保留仍要重校准
2. ticket escalate(转人工)真值缺失 → 5060 `annotate_news.py` 补标后 noul 生效
3. ugc/agent/risk 真实语料替换/混训(合成数据仅管线验证)
4. ticket 小类重采样 v2
5. GPU 主图 fp16 权重压缩(501MB→~250MB,带宽减半,冷态延迟有望再降)
6. cli.mjs/serve 集成 litert 任务模型(LAYA_MODEL 扩展)
7. 5060 保留物:litert-conv/(转换器三件套脚本)、finetune/(数据+脚本+venv)、tfconv+ aet venv

## 七、fp16 权重压缩(2026-10-02 追加,已完成)

- 工具:`ai_edge_quantizer` FLOAT_CASTING(16bit weight-only,显式 Dequantize,激活全 fp32)——与官方 wfp16 同款表示。脚本 `~/litert-conv/fp16_quant.py`(注意:Quantizer 无 create_recipe/export_model,用 add_weight_only_config + quantize(serialize_to_path=))
- 体积:501MB → **250.8MB**(四任务)
- **决策级验证**(真实行,CPU 双图对拍):四任务 **600/600 argmax 一致**,最大概率差 0.0007-0.0085——纯舍入级,零决策漂移
- GPU 上机(100 行):四任务全部 exit=0,choice acc / noul ECE 与 fp32 GPU 完全一致(ugc/agent/risk acc 1.0、ECE 0.041-0.059)
- **延迟(热节流态)**:p50/行 449-476ms ≈ fp32 的 449-461ms,热态无收益(瓶颈在温控降频不在带宽);模型体积减半的价值在存储与冷态带宽,冷态延迟收益待设备静置后复测
- 手机上两版并存:`laya_{task}_s256_embeds.tflite`(fp32)+ `_wfp16.tflite`,默认部署建议 wfp16

## 七、fp16 权重压缩与冷态复测(2026-10-02)

### fp16 转换
ai_edge_quantizer `FLOAT_CASTING`(16bit weight-only,显式反量化,激活全 fp32)作用于 FULLY_CONNECTED —— 与官方 wfp16 同款表示。CPU 验证:wfp16 vs fp32 输出一致;GPU 上机四任务 parity 全过(ticket acc 0.69/score 1.085,与 fp32 一致)。

### 四象限:精度表示 × 热状态(每问延迟,天玑 9500 Mali)

| | 冷态(静置后) | 热态(连续负载后) |
|---|---|---|
| **fp32**(501MB) | **avg 13.7-16.3ms,best 11.4ms** | ~154ms/问(温控降档 ~6x) |
| **wfp16**(250MB) | **avg 14.6-14.8ms,best 10.6ms** | ~159ms/问 |
| 官方 wfp16 基准 | 17-19ms(宣称)/ 23-25ms(bench) | ~140ms |

数据:fp32 两轮 avg 16.3/13.7ms;wfp16 两轮 avg 14.6/14.8ms;交替轮换抵消热漂移。输出一致性:token_logits 两种表示 3-4 位小数一致。

### 结论
1. **官方 17ms 已达到并超过**(冷态 13.7-16.3ms)——此前 154ms/问的差距 100% 来自热节流(MTK 温控,整机热状态决定),与模型转换无关
2. **fp16 权重的收益不在推理延迟**:冷态推理两种表示打平(GPU 算力/调度主导,非带宽瓶颈);收益在 **模型加载 182ms→19ms**(10 倍)和体积减半(501→250MB)
3. 编译耗时 2.4-4.9s(gpucache 后),首次 load fp32 需要 182ms vs wfp16 19ms——对冷启动体验,wfp16 更优
4. 采样方差:单次 invoke 10.6-26.9ms 波动(亮屏/调度),avg 稳定
