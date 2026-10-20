#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机系统资源告警分诊训练集(CPU/内存/存储/温度/耗电/可疑占用;含 App 内告警模板同款句式)。
输出: data/sysalert-{train,test}.jsonl + sysalert-classes.json
用法: cd /data/ai/finetune && venv/bin/python convert_sysalert.py
"""
import json, os, random, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, vary, split_and_write

CLASSES = ["正常波动", "内存压力", "存储紧张", "发热降频", "耗电异常", "可疑占用"]
NORMAL = "正常波动"
rows, seen = [], set()
rng = random.Random(41)
g1, g2, g3, g4, g5, g6 = Gen(300), Gen(300), Gen(300), Gen(300), Gen(300), Gen(300)

def add(text, label, severity, review, source):
    t = " ".join(str(text).split()).strip()
    if len(t) < 20: return False
    h = hashlib.md5(t.encode()).hexdigest()
    if h in seen: return False
    seen.add(h)
    rows.append({"text": t[:600], "label": label, "severity": severity, "review": review, "source": source})
    return True

def synth(t):
    stack = [""]
    for ch in t:
        if ch == "{": stack.append("")
        elif ch == "}": stack[-1] += rng.choice(stack.pop().split("|"))
        else: stack[-1] += ch
    return vary(rng, stack[0])

LOAD3 = ["7.80 / 6.20 / 5.10", "8.90 / 7.20 / 6.00", "6.50 / 5.80 / 5.20"]
MEMAVAIL = ["450M", "0.6G", "0.3G", "0.5G"]
MEMTOTAL = ["7.4G", "8.0G", "11.6G", "12.0G"]
DISKAVAIL = ["2.1G", "4.2G", "3.3G", "1.8G", "0.9G"]
SURF = ["微信", "抖音", "相机", "游戏", "图库", "地图"]

# 正常波动(sev 1, review 0):短时峰值/可解释的临时占用
def gen_norm():
    t = rng.choice([
        f"CPU 短时冲到 {rng.choice(['92%','95%','88%'])} 后回落,1 分钟均值 20%,属正常峰值",
        f"打开{rng.choice(SURF)}瞬间内存上升,退出后已释放,无异常",
        f"应用商店安装更新时存储写入峰值,完成后恢复,无需处理",
        f"充电时温度升到 {rng.choice(['38.5','39.2','40.1'])}°C,亮屏轻度使用,属正常范围",
        f"CPU 使用率 45%(负载 2.10 / 1.80 / 1.60),内存占用 68%,各项指标平稳",
        f"跑分测试导致 CPU/GPU 拉满 {rng.choice(['30秒','1分钟'])} 后回落,温度 {rng.choice(['44.0','45.2'])}°C,正常",
        f"短视频连续播放 {rng.choice(['1小时','2小时'])},耗电 {rng.choice(['12%','15%'])}%,符合预期",
        f"系统更新正在后台解压,CPU 使用率 {rng.choice(['70%','75%'])} 属临时行为,完成后回落",
    ])
    return g1.add(synth(t), label="正常波动", severity=1, review=0, source="synth")

# 内存压力(sev 3-4, review 0-1):内存占用高/后台频繁被杀
def gen_mem():
    heavy = rng.random() < 0.4
    sev = 4 if heavy else 3
    review = 1 if heavy else 0
    t = rng.choice([
        f"告警:检测到本机资源异常。内存占用 {rng.choice(['93%','95%','94%'])}%(可用 {rng.choice(MEMAVAIL)}/{rng.choice(MEMTOTAL)});建议排查后台高占用应用并降载处置。",
        f"内存占用 {rng.choice(['91%','92%'])}%,可用仅 {rng.choice(MEMAVAIL)},后台应用频繁被清理,微信/相机反复重载",
        f"告警:检测到本机资源异常。CPU 使用率 {rng.choice(['91%','94%'])}%(负载 {rng.choice(LOAD3)});内存占用 {rng.choice(['94%','96%'])}%(可用 {rng.choice(MEMAVAIL)}/{rng.choice(MEMTOTAL)});建议排查后台高占用应用并降载处置。",
        f"{rng.choice(SURF)}占用内存超过 2GB,系统可用内存不足 {rng.choice(['500M','300M'])},滑动明显卡顿",
        f"连续使用 {rng.choice(['3小时','4小时'])}未清理,内存占用攀升到 {rng.choice(['92%','95%'])},应用切换重新加载",
        f"ZRAM 交换使用接近上限,物理内存占用 {rng.choice(['93%','96%'])},建议重启手机或清理后台",
    ])
    return g2.add(synth(t), label="内存压力", severity=sev, review=review, source="synth")

# 存储紧张(sev 3-4, review 0)
def gen_disk():
    sev = 4 if rng.random() < 0.35 else 3
    t = rng.choice([
        f"告警:检测到本机资源异常。存储剩余 {rng.choice(['3%','2%','4%'])}%(可用 {rng.choice(DISKAVAIL)});建议排查后台高占用应用并降载处置。",
        f"存储剩余不足 {rng.choice(['5%','3%'])},可用 {rng.choice(DISKAVAIL)},相机无法拍照,请尽快清理",
        f"{rng.choice(SURF)}占用存储 {rng.choice(['32G','48G','64G'])},系统提示存储空间不足,建议清理聊天记录与缓存",
        f"应用安装失败:存储空间不足,剩余 {rng.choice(DISKAVAIL)}/128G,需要释放至少 5G",
        f"存储占用 {rng.choice(['96%','97%'])}%,系统更新无法下载,建议清理大文件与重复照片",
        f"缓存文件堆积 {rng.choice(['12G','18G'])},存储剩余 {rng.choice(['3%','4%'])},建议一键清理",
    ])
    return g3.add(synth(t), label="存储紧张", severity=sev, review=0, source="synth")

# 发热降频(sev 3, review 0)
def gen_heat():
    t = rng.choice([
        f"告警:检测到本机资源异常。SoC 温度 {rng.choice(['57.5','58.2','59.4'])}°C;建议排查后台高占用应用并降载处置。",
        f"SoC 温度 {rng.choice(['60.1','61.3'])}°C,大核已降频,游戏帧率从 60 掉到 {rng.choice(['42','45','38'])}",
        f"告警:检测到本机资源异常。电池温度 {rng.choice(['44.0','45.6'])}°C;建议排查后台高占用应用并降载处置。",
        f"边充电边玩游戏,机身温度 {rng.choice(['43.5','46.2'])}°C,系统触发温控,建议暂停充电",
        f"导航 {rng.choice(['1小时','2小时'])}后机身发烫,温度 {rng.choice(['47.0','48.3'])}°C,亮度被自动降低",
        f"GPU 高温 {rng.choice(['62.0','64.5'])}°C 持续 {rng.choice(['5分钟','10分钟'])},存在降频风险,建议关闭后台游戏",
    ])
    return g4.add(synth(t), label="发热降频", severity=3, review=0, source="synth")

# 耗电异常(sev 3-4, review 0-1)
def gen_drain():
    heavy = rng.random() < 0.35
    sev = 4 if heavy else 3
    review = 1 if heavy else 0
    t = rng.choice([
        f"夜间待机 8 小时掉电 {rng.choice(['22%','28%','31%'])},后台唤醒 {rng.choice(['180','260'])} 次,疑似异常耗电",
        f"1 小时耗电 {rng.choice(['18%','21%'])}(主要屏幕 20 分钟),放电电流 {rng.choice(['-980mA','-1150mA'])}偏高",
        f"{rng.choice(SURF)}后台耗电排名第一,占 {rng.choice(['35%','42%'])},建议限制后台活动",
        f"告警:检测到本机资源异常。电池温度 {rng.choice(['44.5','46.0'])}°C;建议排查后台高占用应用并降载处置。",
        f"亮屏 10 分钟掉电 {rng.choice(['6%','8%'])},CPU 持续占用 {rng.choice(['60%','70%'])},怀疑后台任务异常",
        f"充电速度变慢,边用边充 1 小时仅充入 {rng.choice(['12%','18%'])},电流波动大,建议检查充电器",
    ])
    return g5.add(synth(t), label="耗电异常", severity=sev, review=review, source="synth")

# 可疑占用(sev 5, review 1)
def gen_susp():
    t = rng.choice([
        f"发现未安装来源的进程持续占用 CPU {rng.choice(['88%','92%'])},并有对外网络连接,疑似异常程序",
        f"后台常驻进程拒绝退出,占用内存 1.5GB,包名可疑,建议卸载并全盘扫描",
        f"电量异常下降同时流量后台大量上传,疑似被植入挖矿/代理程序,需要立即处理",
        f"告警:检测到本机资源异常。CPU 使用率 {rng.choice(['97%','99%'])}%(负载 {rng.choice(LOAD3)});GPU 使用率 {rng.choice(['96%','98%'])}%;建议排查后台高占用应用并降载处置。",
        f"锁屏后 CPU 仍维持 {rng.choice(['50%','60%'])} 占用,进程列表出现乱码名称,疑似恶意软件",
        f"热点被未知设备连接并产生大流量,同时本机 CPU/温度异常升高,需要人工核查",
    ])
    return g6.add(synth(t), label="可疑占用", severity=5, review=1, source="synth")

QUOTA = [(gen_norm, 3600), (gen_mem, 3400), (gen_disk, 2600), (gen_heat, 2600), (gen_drain, 2600), (gen_susp, 2400)]
for fn, q in QUOTA:
    made, attempts = 0, 0
    while made < q and attempts < q * 80:
        attempts += 1
        if fn(): made += 1
    print(f"{fn.__name__}: {made}", flush=True)
rows += g1.rows + g2.rows + g3.rows + g4.rows + g5.rows + g6.rows

dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("sysalert", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=110, seed=41)
