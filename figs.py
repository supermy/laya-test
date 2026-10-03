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

# ---------- 图2:架构 ----------
img = Image.new("RGB", (W, 850), BG)
d = ImageDraw.Draw(img)
d.text((60, 40), "端侧推理管线:一次 tokenize,两条加速路径", font=F(38), fill=INK)

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

# JS 层
box([70, 110, 1010, 300], "Node JS(应用侧)",
    ["tokenize + 拼序列(~2ms)", "fp16 embedding 查表(host)", "温度校准 · 解码 · 置信度"], BLUE, (240, 244, 255))
arrow((540, 300), (540, 380), "TCP 127.0.0.1(同款协议,双后端)")
# 两条后端
box([70, 385, 510, 610], "litert-runner(GPU)", ["LiteRT 2.2.0 delegate", "GPU fp32 主图 17-19ms", "act 头 · CPU 0.8ms", "程序缓存热启 3s"], GREEN, (238, 248, 244))
box([570, 385, 1010, 610], "runner(ORT int8)", ["onnxruntime 1.23 C API", "int8 MatMulNBits", "batch1 逐问推理", "~650ms/问"], (150, 160, 185), (246, 247, 250))
# 硬件层
box([70, 655, 510, 765], "天玑 9500 · Mali GPU", ["adb shell 域运行(system 库依赖)"], GREEN, (238, 248, 244))
box([570, 655, 1010, 765], "天玑 9500 · 6 线程 CPU", ["LD_LIBRARY_PATH=capi+pylib"], (150, 160, 185), (246, 247, 250))
arrow((290, 610), (290, 653)); arrow((790, 610), (790, 653))
img.save("/sdcard/Pictures/fig2_arch.png")

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
