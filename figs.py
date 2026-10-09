#!/usr/bin/env python3
# 公众号配图 x3:性能对比 / 架构图 / 延迟台阶图
from PIL import Image, ImageDraw, ImageFont

FONT = "/system/fonts/NotoSansCJK-Regular.ttc"
def F(size, idx=2):
    return ImageFont.truetype(FONT, size, index=idx)

W = 1080
INK = (26, 26, 46); MUT = (110, 115, 130)
BLUE = (67, 97, 238); RED = (230, 57, 70); GREEN = (42, 157, 143); ORANGE = (233, 140, 30)
LINE = (225, 228, 235); BG = (255, 255, 255); SOFT = (246, 247, 250)

# ---------- 图1:性能对比 ----------
img = Image.new("RGB", (W, 640), BG)
d = ImageDraw.Draw(img)
d.text((60, 44), "天玑 9500 · Laya 决策模型 · 单问延迟", font=F(40), fill=INK)
d.text((60, 96), "四条路径,从 4.2s 到 17ms", font=F(26), fill=MUT)

rows = [
    ("WASM fp32(起点)",        4200,  "4.2 s",   (176, 182, 197)),
    ("ONNX int8 · CPU",         1670,  "1.67 s",  (150, 160, 185)),
    ("LiteRT GPU · 热节流",     140,   "140 ms",  ORANGE),
    ("LiteRT GPU · 常温",       18.5,  "17-19 ms", GREEN),
]
y = 170; bar_x = 400; bar_max = 500
for name, ms, label, color in rows:
    d.text((60, y - 4), name, font=F(28), fill=INK)
    w = max(8, int(bar_max * (ms / 4200) ** 0.45))  # 轻微压缩长尾,保视觉可读
    d.rounded_rectangle([bar_x, y, bar_x + w, y + 46], 10, fill=color)
    tw = d.textlength(label, font=F(28))
    d.text((min(bar_x + w + 14, W - 60 - tw), y + 2), label, font=F(28), fill=INK)
    y += 92
d.text((60, y + 6), "同一张工单、同一组问题;GPU 路径为 fp32,窗口 256", font=F(24), fill=MUT)
img.save("/sdcard/Pictures/fig1_perf.png")

# ---------- 图2:架构(分层决策:小模型全量筛查 + LLM 升级通道 + 微调闭环) ----------
img = Image.new("RGB", (W, 1120), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "端侧分层决策架构:决策模型全量筛查,LLM 只接重要紧急", font=F(38), fill=INK)

def box(xy, title, lines, border, fill=SOFT):
    d.rounded_rectangle(xy, 14, fill=fill, outline=border, width=3)
    x0, y0, x1, y1 = xy
    cx = (x0 + x1) / 2
    d.text((cx - d.textlength(title, font=F(30)) / 2, y0 + 18), title, font=F(30), fill=INK)
    yy = y0 + 66
    for ln in lines:
        d.text((cx - d.textlength(ln, font=F(22)) / 2, yy), ln, font=F(22), fill=MUT)
        yy += 30

def arrow(p0, p1, label=None):
    d.line([p0, p1], fill=INK, width=3)
    import math
    ang = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
    for s in (2.7, -2.7):
        d.line([p1, (p1[0] - 14 * math.cos(ang - s), p1[1] - 14 * math.sin(ang - s))], fill=INK, width=3)
    if label:
        d.text(((p0[0] + p1[0]) / 2 - d.textlength(label, font=F(20)) / 2 + 10, (p0[1] + p1[1]) / 2 - 34), label, font=F(20), fill=BLUE)

# 云端层:微调闭环 + LLM 升级通道
box([70, 100, 510, 265], "云端微调(5060 GPU)", ["prepare → RLCD 训练 → evaluate", "split_negfix(NEG fp16 修复+拆图)", "新业务 2-3 天上线"], BLUE, (240, 244, 255))
box([570, 100, 1010, 265], "云端 LLM(决策后处理)", ["决策后接手 重要+紧急(39.1%)", "进一步推理 · 生成回复", "token 支出 ↓~60%"], ORANGE, (255, 246, 238))
# 端侧应用层
box([70, 330, 1010, 545], "Node JS / Android APK(端侧)",
    ["tokenize + 拼序列(~2ms)", "Laya 决策:department / urgency / intent",
     "引擎链 NPU → GPU → CPU 逐级兜底(MTK SoC 探测)", "温度校准 · 解码 · 置信度"], BLUE, (240, 244, 255))
arrow((290, 265), (290, 328), "模型下发 · 自动发现")
arrow((790, 328), (790, 267), "决策后升级")
arrow((540, 545), (540, 578))
d.text((560, 552), "TCP/unix socket(同款协议,双后端)", font=F(20), fill=BLUE)
# 两条后端
box([70, 583, 510, 800], "litert-runner(GPU/NPU)", ["LiteRT 2.2.0(AAR)C API", "GPU fp16 ~150ms/问", "NPU split:encoder MDLA 54-59ms/问", "scorer 头 C 实现 <1ms · 5/5 模型"], GREEN, (238, 248, 244))
box([570, 583, 1010, 800], "runner(ORT int8)", ["onnxruntime 1.23 C API", "int8 MatMulNBits", "batch1 逐问推理", "~650ms/问"], (150, 160, 185), (246, 247, 250))
# 硬件层
box([70, 845, 510, 965], "天玑 9500 · Mali GPU / MDLA NPU", ["APK 内置 dispatch,app 域独立进程"], GREEN, (238, 248, 244))
box([570, 845, 1010, 965], "天玑 9500 · 6 线程 CPU", ["LD_LIBRARY_PATH=capi+pylib"], (150, 160, 185), (246, 247, 250))
arrow((290, 800), (290, 843)); arrow((790, 800), (790, 843))
d.text((60, 1000), "算力账:Laya 决策 ~0.8 TFLOP/单 ≈ 全 LLM 处理(8B,~22 TFLOP)的 1/30;", font=F(26), fill=INK)
d.text((60, 1040), "39.1% 升级率下总算力 ↓57%;加置信度门槛压到 20% 升级,可 ↓77%", font=F(26), fill=INK)
img.save("/sdcard/Pictures/fig2_arch.png")

# ---------- 图4:业务流程(决策分流 + LLM 升级 + 微调闭环) ----------
img = Image.new("RGB", (W, 1180), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "业务流程:决策模型分流 · LLM 只处理重要紧急", font=F(38), fill=INK)
d.text((60, 92), "升级率取真实分布(tickets.csv 28587 单):high 39.1% / medium 40.3% / low 20.6%", font=F(24), fill=MUT)

box([340, 135, 740, 228], "业务输入", ["工单 / 短信 / UGC / 风控事件"], BLUE, (240, 244, 255))
arrow((540, 228), (540, 248))
box([240, 252, 840, 442], "Laya 决策模型(三问逐次前向,NPU ~0.08s/问)",
    ["department → 该谁管", "urgency → 重要紧急吗", "intent → 用户要什么",
     "微调后 choice acc 67.4%(零样本仅 38-42%)"], BLUE, (240, 244, 255))
arrow((400, 442), (400, 478)); arrow((680, 442), (680, 478))
box([70, 482, 470, 650], "本端自动处理(60.9%)",
    ["决策后:低/中优先级模板回复 · 智能路由", "sms/ugc/risk 判别即拦截", "~0.2s · <1J 能耗/单"], GREEN, (238, 248, 244))
box([610, 482, 1010, 650], "LLM 进一步处理(39.1%)",
    ["决策后:重要+紧急升级", "云端 API / 端侧大模型兜底", "复杂推理 · 生成回复"], ORANGE, (255, 246, 238))
arrow((290, 650), (290, 690)); arrow((790, 650), (790, 690))
box([240, 694, 840, 797], "处理结果 + 决策日志(service /report/daily)",
    ["数据回流:处理质量标注 → 训练语料"], INK, (246, 247, 250))
arrow((540, 797), (540, 835))
box([70, 839, 1010, 1000], "新业务上线流水线(finetune/ 四脚本 + litert-conv,云端 GPU)",
    ["prepare_data 切分 → train RLCD(--task) → evaluate(acc/F1/ECE)",
     "split_negfix(NEG=-1e4 修复+scorer 拆图)→ GPU wfp16 / NPU dispatch+scorer",
     "双格式落 /sdcard/models 自动发现;引擎链按 SoC 自动选 NPU→GPU→CPU"], BLUE, (240, 244, 255))
# 回路:微调 → 决策模型
d.line([(1010, 920), (1052, 920)], fill=GREEN, width=3)
d.line([(1052, 920), (1052, 322)], fill=GREEN, width=3)
d.line([(1052, 322), (846, 322)], fill=GREEN, width=3)
import math as _m
for s in (2.7, -2.7):
    d.line([(846, 322), (846 - 14 * _m.cos(s), 322 - 14 * _m.sin(s))], fill=GREEN, width=3)
d.text((852, 250), "模型下发", font=F(22), fill=GREEN)
d.text((852, 280), "新业务上线", font=F(22), fill=GREEN)
d.text((60, 1045), "算力账:全 LLM ≈ 22 TFLOP/单(8B);分层 = 0.8 + 39.1%×22 ≈ 9.5 TFLOP,↓57%", font=F(26), fill=INK)
d.text((60, 1085), "能耗/费用:自动处理单 <1J vs LLM 单 ~700J;云端 token 费随升级率同比例 ↓~60%", font=F(26), fill=INK)
d.text((60, 1125), "再加低置信度也升级的门槛(p→20%),总算力可 ↓77%", font=F(26), fill=MUT)
img.save("/sdcard/Pictures/fig4_flow.png")
print("done: fig4_flow -> /sdcard/Pictures/")

# ---------- 图3:热节流台阶(真实基准数据) ----------
img = Image.new("RGB", (W, 700), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "被动散热下的 GPU 延迟台阶(30 次连续调用)", font=F(38), fill=INK)
d.text((60, 92), "同一模型、同一输入、同一进程——第 4 次调用起延迟 ×5.4", font=F(24), fill=MUT)

runs = [25.7,26.8,26.8,106.4,150.3,140.0,140.4,139.6,145.0,142.8,144.8,144.6,
        140.7,146.1,149.5,142.7,148.4,145.7,142.6,144.9,145.3,142.3,145.9,140.0,
        142.0,149.0,145.9,146.4,150.6,142.9]
px0, py0, px1, py1 = 90, 150, 1010, 560
ymax = 170.0
def XY(i, v):
    return (px0 + (px1 - px0) * i / (len(runs) - 1), py1 - (py1 - py0) * v / ymax)
# 网格 + 刻度
for gv in (0, 50, 100, 150):
    gy = XY(0, gv)[1]
    d.line([px0, gy, px1, gy], fill=LINE, width=1)
    d.text((px0 - 46, gy - 12), f"{gv}", font=F(22), fill=MUT)
d.text((px1 - 60, py1 + 14), "调用次数", font=F(22), fill=MUT)
d.text((px0 - 55, py0 - 36), "ms", font=F(22), fill=MUT)
# 阈值虚线
for th, col, lab in ((25, GREEN, "正常 22-26ms"), (140, RED, "热节流稳态 ~140ms")):
    gy = XY(0, th)[1]
    for x in range(px0, px1, 18):
        d.line([x, gy, x + 10, gy], fill=col, width=2)
# 折线 + 点
pts = [XY(i, v) for i, v in enumerate(runs)]
d.line(pts, fill=BLUE, width=3)
for i, v in enumerate(runs):
    col = GREEN if v < 40 else RED
    r = 5 if v < 40 else 6
    d.ellipse([pts[i][0]-r, pts[i][1]-r, pts[i][0]+r, pts[i][1]+r], fill=col)
# 分界标注
d.line([XY(3, 0)[0], py0, XY(3, 0)[0], py1], fill=ORANGE, width=2)
d.text((XY(3, 0)[0] + 10, py0 + 6), "第 4 次调用起", font=F(24), fill=ORANGE)
d.text((px0 + 8, py1 - 30), "开强冷后:全程 15.5-22.5ms,台阶消失", font=F(24), fill=GREEN)
d.text((60, py1 + 52), "根因:SoC 积热触发温控降档 —— wait_type / 狂暴模式 / 调度参数全部无效", font=F(24), fill=MUT)
img.save("/sdcard/Pictures/fig3_step.png")
print("done: fig1_perf / fig2_arch / fig3_step -> /sdcard/Pictures/")

# ---------- 图5:泳道图(一次决策请求的端到端路径) ----------
img = Image.new("RGB", (W, 1300), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "泳道图:一次决策请求的端到端路径", font=F(38), fill=INK)
d.text((60, 92), "三问逐次前向;每问 = 主图(MDLA 54-59ms)+ scorer(<1ms)+ 校准解码", font=F(24), fill=MUT)

lanes = [
    ("用户 /\n业务系统", (214, 234, 255), 150, 290),
    ("APK 端侧\n(主进程)", (240, 244, 255), 290, 740),
    ("runner\n独立进程", (238, 248, 244), 740, 920),
    ("加速器\n(NPU|GPU|CPU)", (255, 246, 238), 920, 1060),
    ("云端 LLM", (255, 246, 238), 1060, 1180),
]
for name, col, y0, y1 in lanes:
    d.rounded_rectangle([60, y0, 1020, y1], 12, fill=col, outline=LINE, width=2)
    yy = (y0 + y1) / 2 - 12 * name.count("\n") - 12
    for ln in name.split("\n"):
        d.text((74, yy), ln, font=F(22), fill=INK)
        yy += 30

def sbox(x0, y0, x1, y1, title, sub=None, border=BLUE):
    d.rounded_rectangle([x0, y0, x1, y1], 12, fill=BG, outline=border, width=3)
    cx = (x0 + x1) / 2
    d.text((cx - d.textlength(title, font=F(24)) / 2, y0 + (24 if sub else 38)), title, font=F(24), fill=INK)
    if sub:
        d.text((cx - d.textlength(sub, font=F(20)) / 2, y0 + 62), sub, font=F(20), fill=MUT)

def sarrow(p0, p1, label=None, col=INK):
    d.line([p0, p1], fill=col, width=3)
    import math
    ang = math.atan2(p1[1] - p0[1], p1[0] - p0[0])
    for s in (2.7, -2.7):
        d.line([p1, (p1[0] - 13 * math.cos(ang - s), p1[1] - 13 * math.sin(ang - s))], fill=col, width=3)
    if label:
        mx, my = (p0[0] + p1[0]) / 2, (p0[1] + p1[1]) / 2
        d.text((mx - d.textlength(label, font=F(19)) / 2 + 8, my - 30), label, font=F(19), fill=BLUE)

# 步骤布局(左列下行 → 中路 → 右列上行,无穿框)
sbox(320, 158, 760, 282, "业务输入", "工单 / 短信 / UGC / 风控事件")                       # L1
sbox(180, 303, 460, 427, "tokenize + 拼序列", "~2ms,嵌 token embeddings")                # L2-r1 左
sbox(580, 303, 1020, 427, "引擎链选择", "MTK SoC 探测:NPU→GPU→CPU 逐级兜底", GREEN)      # L2-r1 右
sbox(180, 573, 460, 697, "温度校准 · 解码 · 分流", "60.9% 本端 · 39.1% 升级")             # L2-r2 左
sbox(400, 768, 680, 892, "主图前向", "encoder+head")                                      # L3 中
sbox(620, 768, 1020, 892, "scorer 头 C 实现", "LN→FC→GELU→FC,<1ms")                       # L3 右
sbox(360, 928, 720, 1052, "MDLA | GPU | CPU 兜底", "fp16 54-59ms · NEG=-1e4 修复", GREEN)  # L4
sbox(320, 1098, 760, 1182, "复杂推理 · 生成回复", "决策后接手,token 支出 ↓~60%", ORANGE)   # L5

sarrow((540, 282), (320, 300))                            # 输入 → tokenize
sarrow((460, 365), (580, 365))                            # tokenize → 引擎链
sarrow((760, 427), (545, 765), "下发推理")                 # 引擎链 → 主图
sarrow((540, 892), (540, 926))                            # 主图 → 加速器
sarrow((660, 926), (800, 895), "hidden states", GREEN)    # 加速器 → scorer(上行)
sarrow((800, 766), (462, 662), "logits", GREEN)           # scorer → 校准分流(上行)
sarrow((320, 697), (320, 1094), "39.1% 决策后升级", ORANGE)  # 校准分流 → LLM(左路直下)

d.text((60, 1200), "每问端到端 76-82ms(APK 真机);3 问 = 228-246ms;GPU 兜底 ~500ms/问", font=F(26), fill=INK)
d.text((60, 1240), "升级触发:重要 + 紧急且低置信度 → 决策后交 LLM;token 支出随升级率 ↓~60%", font=F(24), fill=MUT)
img.save("/sdcard/Pictures/fig5_swimlane.png")
print("done: fig5_swimlane -> /sdcard/Pictures/")
