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
box([70, 110, 510, 250], "云端微调(5060 GPU)", ["prepare → RLCD 训练 → evaluate", "export int8 / LiteRT wfp16", "新业务 2-3 天上线"], BLUE, (240, 244, 255))
box([570, 110, 1010, 250], "云端 LLM(决策后处理)", ["决策后接手 重要+紧急(39.1%)", "进一步推理 · 生成回复", "token 支出 ↓~60%"], ORANGE, (255, 246, 238))
# 端侧应用层
box([70, 320, 1010, 500], "Node JS / Android APK(端侧)",
    ["tokenize + 拼序列(~2ms)", "Laya 决策:department / urgency / intent",
     "分流:常规本端处理 · 重要紧急决策后交 LLM", "温度校准 · 解码 · 置信度"], BLUE, (240, 244, 255))
arrow((290, 250), (290, 318), "模型下发 · 自动发现")
arrow((790, 318), (790, 252), "决策后升级")
arrow((540, 500), (540, 578), "TCP 127.0.0.1(同款协议,双后端)")
# 两条后端
box([70, 583, 510, 800], "litert-runner(GPU/NPU)", ["LiteRT 2.2.0 delegate", "GPU fp32/wfp16 ~150ms/问", "NPU AOT dispatch ~57ms/问", "程序缓存热启 3s"], GREEN, (238, 248, 244))
box([570, 583, 1010, 800], "runner(ORT int8)", ["onnxruntime 1.23 C API", "int8 MatMulNBits", "batch1 逐问推理", "~650ms/问"], (150, 160, 185), (246, 247, 250))
# 硬件层
box([70, 845, 510, 965], "天玑 9500 · Mali GPU / MDLA NPU", ["adb shell 域运行(system 库依赖)"], GREEN, (238, 248, 244))
box([570, 845, 1010, 965], "天玑 9500 · 6 线程 CPU", ["LD_LIBRARY_PATH=capi+pylib"], (150, 160, 185), (246, 247, 250))
arrow((290, 800), (290, 843)); arrow((790, 800), (790, 843))
d.text((60, 1000), "算力账:Laya 决策 ~0.8 TFLOP/单 ≈ 全 LLM 处理(8B,~22 TFLOP)的 1/30;", font=F(26), fill=INK)
d.text((60, 1040), "39.1% 升级率下总算力 ↓57%;加置信度门槛压到 20% 升级,可 ↓77%", font=F(26), fill=INK)
img.save("/sdcard/Pictures/fig2_arch.png")

# ---------- 图4:业务流程(决策分流 + LLM 升级 + 微调闭环) ----------
img = Image.new("RGB", (W, 1140), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "业务流程:决策模型分流 · LLM 只处理重要紧急", font=F(38), fill=INK)
d.text((60, 92), "升级率取真实分布(tickets.csv 28587 单):high 39.1% / medium 40.3% / low 20.6%", font=F(24), fill=MUT)

box([340, 135, 740, 210], "业务输入", ["工单 / 短信 / UGC / 风控事件"], BLUE, (240, 244, 255))
arrow((540, 210), (540, 248))
box([240, 252, 840, 392], "Laya 决策模型(单次前向,NPU ~0.17s/单)",
    ["department → 该谁管", "urgency → 重要紧急吗", "intent → 用户要什么",
     "微调后 choice acc 67.4%(零样本仅 38-42%)"], BLUE, (240, 244, 255))
arrow((400, 392), (400, 443)); arrow((680, 392), (680, 443))
box([70, 447, 470, 600], "本端自动处理(60.9%)",
    ["决策后:低/中优先级模板回复 · 智能路由", "sms/ugc/risk 判别即拦截", "~0.2s · <1J 能耗/单"], GREEN, (238, 248, 244))
box([610, 447, 1010, 600], "LLM 进一步处理(39.1%)",
    ["决策后:重要+紧急升级", "云端 API / 端侧大模型兜底", "复杂推理 · 生成回复", "~22 TFLOP · ~700J/单(8B)"], ORANGE, (255, 246, 238))
arrow((290, 600), (290, 655)); arrow((790, 600), (790, 655))
box([240, 659, 840, 762], "处理结果 + 决策日志(service /report/daily)",
    ["数据回流:处理质量标注 → 训练语料"], INK, (246, 247, 250))
arrow((540, 762), (540, 800))
box([70, 804, 1010, 948], "新业务微调闭环(finetune/ 四脚本,云端 GPU)",
    ["prepare_data 切分 → train RLCD(--task) → evaluate(acc/F1/ECE) → export int8/LiteRT",
     "新业务冷启动:构造式合成数据先行,上线后换真实语料;模型落 /sdcard/models 自动发现"], BLUE, (240, 244, 255))
# 回路:微调 → 决策模型
d.line([(1010, 876), (1052, 876)], fill=GREEN, width=3)
d.line([(1052, 876), (1052, 322)], fill=GREEN, width=3)
d.line([(1052, 322), (846, 322)], fill=GREEN, width=3)
import math as _m
for s in (2.7, -2.7):
    d.line([(846, 322), (846 - 14 * _m.cos(s), 322 - 14 * _m.sin(s))], fill=GREEN, width=3)
d.text((852, 250), "模型下发", font=F(22), fill=GREEN)
d.text((852, 280), "新业务上线", font=F(22), fill=GREEN)
d.text((60, 995), "算力账:全 LLM ≈ 22 TFLOP/单(8B,1400 tok);分层 = 0.8 + 39.1%×22 ≈ 9.5 TFLOP,总算力 ↓57%", font=F(26), fill=INK)
d.text((60, 1035), "能耗/费用:自动处理单 <1J vs LLM 单 ~700J;云端 token 费随升级率同比例 ↓~60%", font=F(26), fill=INK)
d.text((60, 1075), "再加低置信度也升级的门槛(p→20%),总算力可 ↓77%", font=F(26), fill=MUT)
img.save("/sdcard/Pictures/fig4_flow.png")
print("done: fig1_perf / fig2_arch / fig3_step / fig4_flow -> /sdcard/Pictures/")

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
