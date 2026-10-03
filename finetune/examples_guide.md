# 四个微调示例 — 任务定义 / 数据 / 训练 / 部署用法

框架已任务化:每个示例 = 一个 `task_*.json` + 一份数据;训练/评测命令只差 `--task` 与数据前缀。
真值缺失的字段自动跳过该问(不丢整行),教师标注可以渐进补充。

通用命令模板:

```bash
# 训练
python3 train_sms.py --task <name> --data-dir data --prefix <name> --output laya-<name>-ft --bf16
# 评测
python3 evaluate.py --task <name> --model laya-<name>-ft --data data/<name>-test.jsonl --classes data/<name>-classes.json --out metrics-<name>.json
# 导出端侧(签名 [1,160] 或 [1,512] 由 --seq-len 定)
python3 export_onnx.py --task-checkout ...   # 见 export_onnx.py
```

---

## 例 1 · 客服工单分流(单次调用多决策,数据已全备)

- **task**: `task_ticket.json` — choice 分诊 6 类 + score 紧急度 + noul 转人工
- **数据**: `data/ticket-{train,test}.jsonl`(Tobi-Bueck/customer-support-tickets,26,673 训练 / 1,914 测试)
  - choice 真值 = queue 映射(技术支持/账单计费/销售咨询/退换货/故障维护/其他)
  - score 真值 = priority 映射(low→2, medium→3, high→4)
  - noul(escalate) 真值未标 → 训练自动跳过该问;要启用就在 5060 用 annotate_news.py 补标
- **训练**: `python3 train_sms.py --task ticket --data-dir data --prefix ticket --output laya-ticket-ft --bf16`
- **部署**: 一次 predict 返回三个答案——部门路由直接用 argmax;紧急度用期望分排序队列;转人工用 noul 阈值

## 例 2 · UGC 内容审核(阈值 + 升级模式)

- **task**: `task_ugc.json` — choice 审核判定 5 类 + score 违规严重度 + noul 人工复核
- **数据**: `data/ugc-{train,test}.jsonl`(合成冷启动 14,160 训练/840 测试,`gen_synthetic.py --which ugc` 生成)
  - 5 类全覆盖(正常含 12% 硬负例:提到广告/诈骗/色情词但本身正常),severity 与类别联动,review=1 标边界样本(软广/硬负例)
  - 合成数据只保证话术模式覆盖,分布与真实黑产有差距 → 上线前用真实评论池 + 教师(`run_annotate.sh` 改 prompt 与 state_key,--two-pass 消位置偏置)替换或混训
- **部署(阈值+升级是用法模式,不靠模型)**:
  - 高置信自动处置:choice=违规 且 p(choice)>τ_high(如 0.9) → 自动删除/折叠
  - 中间带 → 人工复核队列(noul"需要人工复核">0.5 或 max(p)<τ_low)
  - τ 从 evaluate 输出的 ECE/PR 曲线上选:先保 precision≥0.95 再压阈值
- **注意**: 审核是强对抗场景,黑产话术持续演化,需要周期性重训(管线现成,换数据即可)

## 例 3 · Agent 工作流路由

- **task**: `task_agent.json` — choice 路由 5 流(直接回答/检索问答/工具调用/多步规划/转人工) + score 复杂度 + noul 任务拆解
- **数据**: `data/agent-{train,test}.jsonl`(合成 2,700 训练/300 测试,`gen_synthetic.py --which agent`,5 流均衡)
  - 标签由构造保证(模板按流+复杂度分层),multi_step=1 全部来自多步规划、转人工 15%
  - 真实升级路径:从自有 Agent 日志抽 query 池,教师标注(标注 prompt 里写死你的工作流清单和判定标准),替换或混训
- **部署**: 每轮用户请求先过 Laya(~20-600ms 端侧)再进工作流,替代"用大模型判断路由"的那次 LLM 调用;score 复杂度可作为预算控制(低复杂度走小模型)

## 例 4 · 金融风控 — 只能当前置信号,不能当最终决策者

- **task**: `task_risk.json` — score 风险信号强度 + choice 风险类型 + noul 人工复核
- **数据**: `data/risk-{train,test}.jsonl`(合成冷启动 6,795 训练/405 测试,`gen_synthetic.py --which risk`)
  - risk_level 与类型联动(无1/信用2-3/欺诈4-5/合规3-4),欺诈全部 review=1,正式文体的前缀变化
  - 合成数据只用于打通管线与冷启动;**必须**用自有申请文本+真实风控结果迭代,教师只做真值缺口的渐进补标
- **为什么不能当最终决策者(结构性原因,不是精度问题)**:
  1. **概率未对齐代价**: 模型输出是"分布",决策需要代价矩阵(漏欺诈损失 >> 误拦成本),这个映射只能由业务规则层做,且要随余额/额度/客户分层变化
  2. **校准会漂移**: 出厂 ECE 0.2+,温度校准只在训练分布内有效;欺诈话术持续演化 → 分布漂移后阈值静默失效
  3. **可被对抗规避**: 非自回归单次前向 = 输入文本决定输出,黑产改几个词即可探测并绕过;规则+模型集成才能抬高攻击成本
  4. **无解释性**: 监管要求拒贷/限额给出理由,"模型说 p=0.87"不是可申诉的解释
  5. **单点错误代价不可逆**: 拦截错误有申诉兜底,放行欺诈没有
- **正确用法(前置信号)**: Laya 作为毫秒级第一道筛——score 高风险 → 进规则引擎/大模型复核;低风险且无规则命中 → 快速通道;中间带 → 人工。它是"分层决策的最外层漏斗",不是裁判。

---

## 数据准备速查

| 示例 | 数据状态 | 补齐方式 |
|---|---|---|
| ticket | **全备**(choice+score 真值) | escalate 可选教师补标 |
| sms | **全备**(80 万条,17 万训练) | — |
| stock | 新闻池 12,744 条待标注 | `run_annotate.sh`(5060) |
| ugc / agent / risk | **合成冷启动已就绪**(`gen_synthetic.py`,choice+score+noul 全真值) | 上线前换真实语料+教师标注,混训或替换 |

教师标注统一模式: `annotate_news.py --pool <池> --out <标> --two-pass`(prompt 按任务改 SYSTEM 常量),输出行含任务真值字段,`convert_stock.py` 同款转换或直接喂 train_sms(字段名对上 task 的 from 即可)。
