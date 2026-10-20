#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机流量预警/套餐推荐专用训练集(标签由构造保证:先选类别/用量档,再填一致数字)。
输出: data/traffic-{train,test}.jsonl + traffic-classes.json
用法: cd /data/ai/finetune && venv/bin/python convert_traffic.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, vary, split_and_write

CLASSES = ["正常使用", "用量预警", "超量风险", "套餐咨询", "加购推荐"]
NORMAL = "正常使用"
rows, seen = [], set()
rng = random.Random(31)
g1, g2, g3, g4, g5 = Gen(300), Gen(300), Gen(300), Gen(300), Gen(300)

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

USED = ["1.2GB", "3.6GB", "5.8GB", "7.4GB", "9.1GB", "12.5GB", "18GB", "23GB", "31GB", "42GB"]
PLAN = ["5GB", "10GB", "20GB", "30GB", "60GB", "100GB", "不限量"]
DAYS = ["10天", "一周", "5天", "3天", "1天", "今天"]
APP = ["短视频", "直播", "在线视频", "导航", "网课", "游戏", "云盘备份"]
PKG = ["9元30G", "19元60G", "29元100G", "39元120G", "59元200G"]

# 正常使用(sev 1, review 0)
def gen_norm():
    u = rng.randint(1, 5); p = rng.choice([20, 30, 60, 100])
    t = rng.choice([
        f"本月已用{u}GB,套餐共{p}GB,还剩一半以上,够用到月底",
        "流量使用正常,本月余量充足",
        f"今日用了{round(u/5,1)}GB,主要是{rng.choice(['微信','导航','音乐'])},无异常",
        f"套餐{p}GB,已用{u}GB,月结{rng.choice(['15天','20天','18天'])}后重置",
        "WiFi环境下在线看了两集剧,流量几乎没动",
        f"流量统计:{u}GB/{p}GB,占比{int(u/p*100)}%,一切正常",
        "查了下流量,这个月用得不多,不用管",
    ])
    return g1.add(synth(t), label="正常使用", severity=1, review=0, source="synth")

# 用量预警(sev 3-4, review 0)
def gen_warn():
    used = rng.choice(["8.5GB", "9.2GB", "17.3GB", "26GB", "48GB"])
    plan = {"8.5GB": "10GB", "9.2GB": "10GB", "17.3GB": "20GB", "26GB": "30GB", "48GB": "60GB"}[used]
    t = rng.choice([
        f"提醒:本月流量已用{used}/{plan},剩{rng.choice(DAYS)}月结,请注意控制{rng.choice(APP)}使用",
        f"流量预警:已使用{used},套餐{plan}只剩{rng.choice(['10%','15%','1.2GB'])},建议连WiFi",
        f"你的流量余量不足{rng.choice(['15%','10%','1GB'])},用完按{rng.choice(['3元/GB','5元/GB','0.29元/MB'])}计费",
        f"本月{rng.choice(APP)}耗流较快,已用{used}/{plan},月结前可能不够用",
        f"流量使用{int(float(used[:-2])/float(plan[:-2])*100)}%,达到预警线,建议关注",
    ])
    return g2.add(synth(t), label="用量预警", severity=3, review=0, source="synth")

# 超量风险(sev 5, review 1)
def gen_over():
    t = rng.choice([
        f"流量已用{rng.choice(['21GB/20GB','33GB/30GB','63GB/60GB','12GB/10GB'])},已超出套餐,正在按{rng.choice(['3元/GB','5元/GB','15元/G'])}计费",
        f"账单异常:流量超套{rng.choice(['11GB','23GB','41GB'])},产生额外费用{rng.choice(['33元','115元','205元'])},用户在投诉",
        f"欠费停机预警:流量超额+月租共{rng.choice(['88元','156元','230元'])},今日24点前不缴将停机",
        f"用户已连续{rng.choice(['2','3'])}个月流量超套,本月又超{rng.choice(['8GB','15GB'])},建议推荐大流量包并核查计费",
        "热点共享导致流量爆量,超套计费仍在累计,需要人工介入核实",
        f"出境漫游未办包,已产生{rng.choice(['180MB','1.2GB'])}漫游流量,费用{rng.choice(['90元','600元'])},投诉风险高",
    ])
    return g3.add(synth(t), label="超量风险", severity=5, review=1, source="synth")

# 套餐咨询(sev 2, review 0)
def gen_ask():
    t = rng.choice([
        f"问一下,我每月大概用{rng.choice(['6GB','12GB','25GB'])},现在这个{rng.choice(PLAN)}套餐划算吗?",
        "想换个流量多点的套餐,有没有推荐?主要是刷视频多",
        "现在的套餐流量不够用,怎么升级?保号套餐能加流量包吗?",
        "办理{0}这个套餐,流量{1},通话多少分钟?".replace("{0}", rng.choice(["星卡", "王卡", "花卡"])).replace("{1}", rng.choice(PKG)),
        "学生党求推荐低价大流量套餐,预算{0}以内".replace("{0}", rng.choice(["19元", "29元", "39元"])),
        "套餐外流量怎么计费?超了1GB收多少钱?",
        "副卡共享主卡流量吗?家里老人卡想一起办",
        "5G套餐和4G套餐流量通用吗?网速差多少?",
    ])
    return g4.add(synth(t), label="套餐咨询", severity=2, review=0, source="synth")

# 加购推荐(sev 2-3, review 0)
def gen_rec():
    t = rng.choice([
        f"检测到您近三个月月均流量{rng.choice(['12GB','18GB','25GB'])},推荐升级{rng.choice(PKG)},月费更省",
        f"您本月流量将超套,推荐加购{rng.choice(PKG)}加油包,当月有效自动续订",
        f"按您的使用习惯({rng.choice(APP)}为主),推荐{rng.choice(['大流量卡','夜间流量包','定向流量包'])},预计每月省{rng.choice(['10元','20元','30元'])}",
        f"专属回馈:老用户{rng.choice(PKG)}流量包{rng.choice(['7折','5折','首月免费'])},回复{rng.choice(['1','Y','TD'])}办理",
        "您常出差,推荐全国通用流量包,无漫游限制",
        "加购推荐:10元2GB夜间包,23点后使用不计入套餐",
    ])
    return g5.add(synth(t), label="加购推荐", severity=2, review=0, source="synth")

QUOTA = [(gen_norm, 3800), (gen_warn, 3400), (gen_over, 3000), (gen_ask, 3400), (gen_rec, 3400)]
for fn, q in QUOTA:
    made, attempts = 0, 0
    while made < q and attempts < q * 80:
        attempts += 1
        if fn(): made += 1
    print(f"{fn.__name__}: {made}", flush=True)
rows += g1.rows + g2.rows + g3.rows + g4.rows + g5.rows  # 合成行并入主集

dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("traffic", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=120, seed=31)
