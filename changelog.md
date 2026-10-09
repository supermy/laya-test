# Changelog

## 2026-10-10 — v1.4.5:DecisionCore.kt 文案资源化(i18n 收尾)

- Kotlin 决策核心 54 条中文按"文案/数据键"分类处理:**34 处资源化**——业务名(BUILTIN→biz_* 资源,scanTasks/fmt 加 Context)、引擎描述 6 条(engineDesc 改可空 + currentEngine(ctx))、backendInfo 硬件 5 条、ensureRunner 校验 2 条、导入校验 7 条、内置业务问题文本 12 条(instructions 本地化)、格式词(评分/需人工/自动/是/否/报表头/分组行)
- **数据键保留**(日志存储值,不随 locale 翻译):等级键(高/中/低/未知)、choice 选项 criteria/legend(模型输出类别)、noul basis、LLM prompt(任务指令)
- 签名变更:`scanTasks(ctx?)`/`currentEngine(ctx)`/`fmt(ctx,...)`/`questionDefs(ctx,...)`;Gateway 邮件回复与 MainActivity 两处调用点同步
- 真机验证:EN 下业务名(Customer ticket triage/Agent workflow routing/Financial risk pre-screen/Multilingual ticket triage)、backendInfo、真实决策结果(Backend: NPU (MTK MDLA, separate process),238ms 无回归)✓
- 至此 APK 三层(MainActivity/Gateway/DecisionCore)UI 文案全部 zh/en 双语

## 2026-10-10 — v1.4.4:报表/网关/系统页左栏 tab 整词旋转 90°

- 三页左栏 rail(日报/月报/年报/详单/下钻详单、邮件/队列/LLM、系统/架构图/流程图/数据流)由"逐字竖排"改为**整词旋转 90°**,与决策页业务 tab 同构
- 抽公共 `railChip()`:slot 容器 + `setRotation(90)` + `measureText` 词长自适应;中英文统一渲染(英文 Daily/Month/Year/List/Drill、Mail/MQ/LLM、Sys/Arch/Flow/Data 不再逐字堆叠)
- 删除 vert/gVert/subVert 逐字拼接数组
- 真机验证:三页中文/英文 rail 旋转展示 + 词长自适应 ✓

## 2026-10-10 — v1.4.3:标题栏语言切换按钮 + Kotlin 侧网关文案 i18n

- 标题栏右侧新增语言按钮:**🌐A(跟随系统)→ 中 → EN 循环**,点击即切 recreate 生效,与系统页选择器同一记忆键
- `Gateway.kt` 全部用户可见文案资源化(18 处):`status()`/`stop()`/`saveLlm()` 状态、IMAP/SMTP/MQTT/上传四组测试结果、日报与决策回复邮件主题;`testEmail/testSmtp/testMqtt/testUpload` 签名加 `Context` 参数
- `GatewayService.kt` 前台通知(渠道名/标题/正文)资源化
- build.sh:资源与 R.java 生成提前到 kotlinc 之前(`com.laya` Kotlin 代码引用 app R 需要)
- 真机验证:标题按钮三态循环、EN 下 `Gateway: Email gateway running (60s poll);MQTT connected…;Model upload service…` 全英文、循环切回跟随系统 ✓
- 保留:`DecisionCore.kt` 推理/业务侧文案(54 条,后续)、业务名与等级数据键

## 2026-10-10 — v1.4.2:深层文案 i18n 全量抽取(报表/网关/系统页)

- v1.4.0 遗留的"报表/网关/系统三页深层文案"全部抽取至 strings.xml(zh/en 各 ~110 条):报表表头/翻页/下钻矩阵、网关邮件/MQTT/LLM 三面板表单与测试按钮、系统页上传/重扫/业务卡片/模型详情/删除确认对话框
- 左栏竖排 tab(日报/月报/年报/详单/下钻详单、邮件/队列/LLM、系统/架构图/流程图/数据流)改为资源驱动,英文按词逐字竖排(Daily→D/a/i/l/y)
- 修复:资源 XML 中误写 `\\n` 导致系统页状态行显示字面 `\n`
- 保留中文的合理项:等级数据键(高/中/低,日志存储值,UI 按索引映射)、LLM 厂商默认名、LLM ping prompt、业务名(注册表数据)、`Gateway.status()`/`backendInfo()` 返回值(Kotlin 侧状态文本,后续抽取)
- 替换方式:104 条精确匹配批量替换(逐条断言命中次数);真机验证系统/报表/网关三页英文态 + 中英来回切换回归

## 2026-10-10 — v1.4.1:应用内语言切换(系统页)

- 系统页新增「语言 / Language」选择器:**跟随系统 / 中文 / English**,点击即切,`recreate()` 重建后立即生效,无需重启
- 实现:`attachBaseContext` 包裹目标 locale(`createConfigurationContext`)+ SharedPreferences(`ui/locale`)记忆——绕开 targetSdk 28 在 HyperOS 上系统级 per-app locale 不注入的限制
- 语言与当前 tab 记忆(`ui/tab`),切换语言后停留在原页面
- 真机验证:中→英(标题/底部 tab/决策页文案变英文)、英→中恢复、tab 保持 ✓

## 2026-10-10 — v1.4.0:决策 tab 整词旋转 90° + ☰ 显隐 + UI 国际化(zh/en)

### 决策页 tab 改版(与报表页统一)

- 业务 tab 由"逐字竖排"改为**英文单词整词旋转 90°**(`setRotation(90)` + FrameLayout slot 容器,slot 尺寸按 `Paint.measureText` 量化词长,chip 长度自适应)
- tab 显隐与报表页一致:**标题栏 ☰ 切换左栏**;显隐状态记入字段 `railHidden`,**跨页面重建保持**——修复网关轮询触发 `handleIntent`/`refreshTasks` 重建决策页时显隐状态被重置的问题
- tab 动态增减:左栏按 `taskIds` 循环构建,系统页重扫注册新业务后进入决策页自动出现新 tab(已有机制,本次验证)
- 移除旧弹窗式业务菜单(showTaskMenu/PopupWindow),顶部改为"当前业务:X · 左侧竖排 tab 切换业务(标题栏 ☰ 显隐)"提示
- 真机验证:5 业务 tab(ticket/ugc/agent/risk/multi)旋转展示 ✓、☰ 隐藏/恢复 ✓、聊天区随显隐自适应 ✓

### APK UI 国际化第一层(zh 默认 + en)

- 新增 `apk/res/values/strings.xml`(中文默认)+ `res/values-en/strings.xml`;build.sh 接入 aapt2 compile/link + R.java 生成管线
- 首批接入:应用标题、四个底部 tab、决策页(当前业务提示/输入提示/决策按钮/就绪消息/历史 header/暂无历史)、决策结果格式(分类 header/评分/判定/耗时/是否)、报表提示/暂无数据
- 报表/网关/系统三页的深层文案(表头/表单/报表文本)尚未抽取,**后续版本完成**
- 已知限制:APK `targetSdk=28`,HyperOS 上 `cmd locale set-app-locales` 对旧应用不注入运行时 locale(en 资源已确认打入 APK,系统语言为英文时正常回退生效;后续升 targetSdk + `android:localeConfig` 后支持应用内切换)

### 补记:v1.3.0 期间 multi 模型状态更新(10-09 当日已完成)

- multi(torch ckpt 缺失的结论已过时):从 HF `convaiinnovations/laya-multilingual` 经 hf-mirror 补齐 ckpt → `split_negfix` 重导出 → AOT,**bench 54.3ms(5 模型全场最快)**,5/5 模型 NPU 全覆盖
- multi 已接入 APK(DecisionCore BUILTIN + scanTasks 特例 + 路径映射),真机英文输入决策通过(故障维护 1.87/5,NPU 后端 76ms/问)

## 2026-10-09 — v1.3.0:NPU 决策全链路打通(NEG 根因修复)+ APK NPU 后端

### ⚠️ 10-03 NPU 结论勘误(v1.2.0 的 NPU 段落作废)

- **"pooled 输出与 GPU 参考一致,决策可用"是错的**。依据是 bench 的 act_logits(0.0360/-0.0311)与 GPU 一致——事后证实该值对**任何输入恒定不变**,是无效信号。MDLA 实际把整个 encoder 算成垃圾(token_logits/pooled 全 inf/nan);adb shell 域复验排除域差异,10-03 起即如此
- "57ms/问"是真实的 MDLA 执行速度,但输出一直是垃圾;multi 模型今日复测在 shell 域也因 dispatch 未加载而 CPU 兜底(272ms)

### 根因:掩码常量 NEG 的 fp16 下溢

- `clean_main.py` 的滑动窗口/key 掩码常量 `NEG = -3.4028235e38`(torch fp32 min)。MDLA 内部 fp16 执行时该常量溢出为 **-inf**;`keybias = (1 - mask) × NEG` 在有效位置(mask=1)算出 **0 × (-inf) = NaN**(IEEE-754),NaN 随注意力扩散毒化全图
- CPU/GPU 走 fp32,NEG 有限,所以只有 NPU 坏;与执行域无关
- **修复:`NEG = -10000.0`**(fp16 可表示;softmax 中 exp(score-1e4) 下溢为 0,掩码语义等价)
- 定位路径:tiny FC 模型 AOT 对照(MDLA 管线无恙,输出与 CPU 逐位一致)→ 6 级分层二分(0 层=LayerNorm 正确,+1 层 attention 即坏)→ 锁定掩码常量

### 拆图架构:scorer 移出 NPU 图

- 主图(encoder+head)输出改为 hidden_states [1,256,768] + pooled;scorer(LN→FC768→GELU(erf)→FC)权重导出 2.4MB fp32 bin,runner 用 C 实现(<1ms),marker logits 由 CPU 补算
- `litert-runner.c`:split 模式检测(输出 rank=3 + LAYA_SCORER env)、LITERT_DISP_DIR env(替代硬编码 dispatch 目录)、LITERT_RUNNER_NO_GPU 编译开关、SO_REUSEADDR;`litert-bench.c` 同步 LITERT_DISP_DIR
- **教训:bench 的 act_logits 恒定值不能作为输出正确性信号**(10-03、10-08 两次被它误导)

### 四业务模型 NPU 化(ticket/ugc/agent/risk)

- PC `split_negfix.py` 重导出(NEG 修复 + 拆图),AOT(MT6993)后推手机,bench 全有限
- ticket 全量 E2E:**决策正确**(intent=billing 0.898 / urgency 1.94),**72ms/问 = GPU(150ms)的 2.1x**;ugc/agent/risk runner 冒烟通过
- multi 模型:torch ckpt 缺失(仅存 wfp16/ONNX;HF 源 `convaiinnovations/laya-multilingual` 已定位),暂留 GPU,补齐后走同一管线

### APK NPU 后端(独立进程,真机验证)

- 方案与 GPU runner 相同的 lib*.so exec 模式:`libnpu_rt.so`(litert-runner NPU 版)+ `libLiteRtDispatch_MediaTek.so`;**AAR libLiteRt 2.2.0 与手编 dispatch 库兼容性实测通过**,dispatch 仅依赖 libLiteRt+libc++_shared,无 absl 污染
- `LayaRunnerEngine(npu=true)`:spawn libnpu_rt.so + split 主图,env `LAYA_BACKEND=npu`/`LAYA_SCORER`/`LITERT_DISP_DIR=nativeLibraryDir`;`DecisionCore.mtkNpuAvailable()`(MediaTek SoC + 库存在),引擎链 **NPU→GPU→CPU** 逐级兜底,系统页显示 NPU 可用性
- **真机验证:客服工单分流 3 问 246ms(≈82ms/问),后端 "NPU(MTK MDLA,独立进程)"**
- 集成中修复 3 个存量 bug:①spawn 漏设 LAYA_BACKEND=npu → DISPATCH_OP unresolved;②`connectWithRetry` 的 `sockFile.length()>0` 恒假(unix socket 文件 st_size 恒为 0)——exec-runner 路径此前在设备上从未真正连通,历史 GPU 决策实为 JNI 进程内兜底;③`infer()` 响应解析 `readFull(total-4)` 多减 4 字节 → BufferUnderflow

### 工具链沉淀

- PC:`split_convert.py`(拆图+scorer bin)、`split_negfix.py`(NEG 修复版)、`bisect_export.py`(分层二分)、`patch_neg.py`(常量字节补丁)
- 手机:`dbg-npu-raw.mjs`(raw 探针)、`run_npu_e2e.sh`/`run_dbg_*.sh`(按端口杀孤儿 runner,**pkill -f 会自匹配勿用**)

## 2026-10-03 — v1.2.0:微调四业务 + APK 端侧闭环 + NPU AOT

### ⚠️ 17ms 勘误(计时方法论修正)

- 此前所有 13-25ms 的 LiteRT GPU bench 数据都是**计时口径错误**:GPU invoke 是异步提交,旧计时只含提交(~10ms),真实 GPU 执行发生在其后的输出 lock 回读(~137ms),不在计时内。`litert-bench.c` 已修(回读纳入计时)
- **真实端到端 ≈ 140-155ms/问,与热/冷基本无关**;此前"热节流降档 6x""强冷后 13-17ms"结论全部作废
- 真实排序(窗口 256):**GPU fp32 ~150ms/问 < CPU int8 254-347ms/问 < CPU fp32 XNNPACK ~309ms/问**

### NPU AOT 路线全通(目前最快路径)

- 设备侧 JIT 不可用:Neuron delegate 对 laya 主图(wfp16/fp32)全部 `NEURON_UNMAPPABLE`,重建 legacy 测试台 APK 复核确认
- **AOT 成功**:PC(5060)上 `ai-edge-litert==2.2.0` + `ai-edge-litert-sdk-mediatek==2.2.0` wheel(内含 libLiteRtCompilerPlugin_MediaTek.so + host libneuron_adapter),对 Target=MT6993 aot_compile,7 秒把 501MB fp32 主图编成 252MB dispatch tflite
- 手机侧:`litert-bench.c` 加 npu 模式(EnvOption DispatchLibraryDir=/data/local/tmp/litert;加速器集合必须 **NPU|CPU**,只 NPU 会 504——AOT 模型 3 节点中 2 个 CPU 残余)+ 手编 dispatcher(9.2.1 适配)
- **实测 NPU 端到端 avg 57.1ms / best 52.5ms(20 轮含回读),vs GPU 150ms = 2.6x**;apuware_server 日志实证 APU 执行;pooled 输出与 GPU 参考一致,决策可用(token_logits 辅助输出 inf,fp16 溢出嫌疑,不影响决策头)
- TurboBoost+LowLatency 重编无效(60.8 vs 61.4,噪声内)——瓶颈在 CPU 残余节点 + buffer 拷贝 + MDLA 执行

### finetune/:微调管线 + 四业务落地

- 云端微调管线(`finetune/`):`prepare_data.py` / `train_sms.py`(RLCD,任务化 --task)/ `evaluate.py` / `export_onnx.py`(fp32+int8,签名与现网逐字对齐,`dynamo=False`);任务定义 `task_{ticket,ugc,agent,risk,sms,stock}.json`,示例指南 `examples_guide.md`
- 训练在 5060 PC(RTX 5060 Ti 16G,ssh my@192.168.0.168):ticket 46min / ugc 29min / agent 9min / risk 16min 全 exit 0;ticket 真实数据 choice 67.4%(macro-F1 0.600),其余同模板测试集 100%(只证管线)
- **手机 parity**(`parity-task.mjs` / `parity-litert.mjs`,报告 `finetune/parity-report.md`):int8 路径 choice argmax 基本保真(ticket −6pp 量化漂移),**noul ECE 0.03-0.05 → 0.23-0.30**——int8 扰动 logits 幅度,训练期温度失效,概率阈值部署前须手机端重校准
- **LiteRT GPU 转换链全通**:自研 clean 前向(ModernBERT rank≤4,band/cos/sin 预计算 buffer)经 litert_torch 转 tflite,四业务 GPU 精度=ckpt(ECE 0.041-0.059 保真),规避 onnx2tf 对 RoPE 的 Expand 无解;fp16 权重压缩(ai_edge_quantizer FLOAT_CASTING)501→251MB,argmax 全一致
- 股票任务走教师蒸馏:FinCorpus 12,751 条真实金融新闻 → 5060 llama.cpp + Qwen3.5-35B-A3B 两遍正反序标注 → `convert_stock.py`;公开中文金融情感数据集不存在(HF 搜遍)
- ugc/agent/risk 合成冷启动:`gen_synthetic.py` 构造式合成 25,200 条(标签由构造保证;分布≠真实,上线前换真实语料)

### sms 判别接入 CLI/HTTP

- `laya-native.mjs` 加 `smsInfer(text)`:`state={"sms":正文}` + 从 config.question_defs 自动挑 SMS 双问(按 instructions 内容定位、按回复 type 对号,兼容多技能导出);verdict={label,probabilities,spamByChoice,spamProb,isSpam},阈值读 config.spam_threshold
- `cli.mjs`:`LAYA_MODEL=sms`(独立 socket `laya-sms.sock`) + serve 加 `POST /sms {"text":...}` 端点;80 万条中文短信数据集微调产物落地 `/sdcard/models/laya-sms-int8/`
- `loadWithDaemon` 重试重构:先 raw-connect 探针轮询再 full load(旧逻辑每次重试解析 34MB tokenizer,最多假死十几分钟);`_systemOneInner` 增加 prep/infer/post 分段计时

### service/:多业务决策服务(Termux 侧)

- `service/server.mjs`(端口 8789):**业务自适配注册表**——扫 `/sdcard/models/laya-*-int8` 有 laya_config.json 即接入(合并 registry.json 手工覆盖),`POST /admin/reload` 免重启;端点 `/tasks`、`/task/:id`(决策+日志)、`/report/daily|monthly|yearly`(markdown 报表)+ 详单 CSV
- `service/gateways.mjs`:邮件网关(IMAP 轮询指令"laya <task> <text>"→ SMTP 回复)+ MQTT 网关(订 laya/req/+ 发 laya/resp);配置缺失静默降级

### apk/:Android 端侧 APK(LiteRT GPU 内置,四业务全验证)

- 自研 JNI C API(`apk/src/laya-jni.c` → liblayajni.so):官方 litert Java API 在 app 域全挂(tensor_buffer 分配失败)→ C API + managed buffer 降级;构建无 Gradle(kotlinc + aapt2/d8/apksigner,`apk/build.sh`)
- **app 域五坑**:JNI 用 clang、SDK 助手 .cc 用 clang++ 分编(absl 缺符号手写 stub);libc++_shared.so 打包;managed buffer 降级 FromHostMemory;**GPU run 失败根因=OpenCL 没加载**→ manifest `<uses-native-library libOpenCL.so>` + JNI 全路径预载 vendor 库(RTLD_GLOBAL);多业务撑爆 Java 堆 → 单引擎策略 + largeHeap
- **线程铁律**:open 与 run 必须同线程(GL 上下文绑定),全部 native 调用走单线程 executor
- 决策核心 `DecisionCore.kt`:引擎双路(独立进程 runner socket 首选,JNI 回退);业务注册表动态化(扫 /sdcard/models/laya-litert-*/phone,支持 zip/目录导入新业务 e2e 全通);决策日志 JSONL + 报表
- 内置网关:IMAP+SMTP(JavaMail)+ MQTT(Paho)前台服务 + 开机自启;本地邮件 e2e 用 pymap+aiosmtpd 全 Termux 自建(JavaMail partial fetch 坑:`msg.rawInputStream` 直读)
- **内存泄漏已修+压测验证**:补全 LiteRT 对象销毁链后 5 业务切换 Graphics 稳定 540-575MB 零累积;遗留:runner 子进程偶发自退(有兜底,待深挖)
- UI:微信风决策台(气泡对话 + 业务↔日志联动 + 泳道架构图 + 网关/系统页)
- 实测:四业务 choice/score/noul 全出,~163ms/问(热节流档),ticket 464ms/3 问端到端

### 其他

- `litert-bench.c` 输出加 hexdump 位模式统计(inf/zero 计数),便于排查 fp16 溢出
- 新增诊断:`timing-breakdown.mjs`、`trace_node.py`、`intern-compare.py`/`validate-intern.py`

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
