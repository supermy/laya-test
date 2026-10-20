#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
手机语音(通话分钟)预警/套餐推荐专用训练集(标签由构造保证:先选类别/用量档,再填一致数字)。
多运营商:移动/联通/电信分别正确归属(服务号、卡品名、App 名一律不串台)。
输出: data/voice-{train,test}.jsonl + voice-classes.json
用法: cd /data/ai/finetune && venv/bin/python convert_voice.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, vary, split_and_write

CLASSES = ["正常使用", "用量预警", "超量风险", "套餐咨询", "加购推荐"]
NORMAL = "正常使用"
rows, seen = [], set()
rng = random.Random(37)
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

# 运营商正确归属表:卡品、服务号、官方 App、查询指令(勿串台)
CARR = {
    "中国移动": {"attr": "移动", "svc": "10086", "app": "中国移动App", "sms": "CXTC",
                 "cards": ["花卡", "宝藏卡", "动感地带", "8元保号套餐"]},
    "中国联通": {"attr": "联通", "svc": "10010", "app": "中国联通App", "sms": "CXLL",
                 "cards": ["腾讯王卡", "冰激凌套餐", "沃派", "8元保号套餐"]},
    "中国电信": {"attr": "电信", "svc": "10001", "app": "电信营业厅App", "sms": "108",
                 "cards": ["星卡", "天翼大流量", "5G畅享", "8元保号套餐"]},
}
CALLER = ["客户", "家人", "客户经理", "面试官", "售后客服", "异地同事", "孩子学校老师"]
DAY_LEFT = ["10天", "一周", "5天", "3天", "1天", "今天"]
V_PKG = ["10元100分钟", "19元200分钟", "29元500分钟", "39元1000分钟", "59元1200分钟"]
RATE = ["0.19元/分钟", "0.15元/分钟", "0.25元/分钟", "0.1元/分钟"]

def pick_carrier():
    name = rng.choice(list(CARR))
    return name, CARR[name]

# 正常使用(sev 1, review 0)
def gen_norm():
    u = rng.randint(20, 120); p = rng.choice([300, 500, 1000, 1200])
    cn, c = pick_carrier()
    t = rng.choice([
        f"本月通话已用{u}分钟,套餐共{p}分钟,还剩大半,用不完",
        f"{cn}语音使用正常,本月分钟数充足",
        f"今天和{rng.choice(CALLER)}通了{round(u/6)}分钟电话,套餐内还有富余",
        f"{cn}套餐{p}分钟,已用{u}分钟,月结{rng.choice(['15天','20天','18天'])}后重置",
        f"用{c['app']}查了下,通话{u}分钟/{p}分钟,一切正常",
        f"平时都用微信语音,{c['attr']}通话分钟几乎没动,不用担心",
        "查了下通话时长,这个月用得不多,不用管",
    ])
    return g1.add(synth(t), label="正常使用", severity=1, review=0, source="synth")

# 用量预警(sev 3, review 0)
def gen_warn():
    used = rng.choice(["240分钟", "260分钟", "460分钟", "920分钟"])
    plan = {"240分钟": "300分钟", "260分钟": "300分钟", "460分钟": "500分钟", "920分钟": "1000分钟"}[used]
    cn, c = pick_carrier()
    t = rng.choice([
        f"{cn}提醒:本月通话已用{used}/{plan},剩{rng.choice(DAY_LEFT)}月结,请注意控制通话时长",
        f"语音预警:已使用{used},套餐{plan}只剩{rng.choice(['15%','10%','50分钟'])},建议改用微信语音",
        f"你的通话余量不足{rng.choice(['15%','10%','60分钟'])},用完按{rng.choice(RATE)}计费,可发{c['sms']}到{c['svc']}查询",
        f"本月和{rng.choice(CALLER)}通话较频繁,已用{used}/{plan},月结前可能不够用",
        f"{c['app']}提示:语音使用{int(float(re.sub(r'[^0-9]','',used))/float(re.sub(r'[^0-9]','',plan))*100)}%,达到预警线,建议关注",
    ])
    return g2.add(synth(t), label="用量预警", severity=3, review=0, source="synth")

# 超量风险(sev 5, review 1)
def gen_over():
    cn, c = pick_carrier()
    t = rng.choice([
        f"通话已用{rng.choice(['320分钟/300分钟','560分钟/500分钟','1080分钟/1000分钟','180分钟/150分钟'])},已超出套餐,正在按{rng.choice(RATE)}计费",
        f"{cn}账单异常:通话超套{rng.choice(['80分钟','160分钟','240分钟'])},产生额外费用{rng.choice(['15元','30元','60元'])},用户在投诉",
        f"欠费停机预警:{c['attr']}用户通话超额+月租共{rng.choice(['88元','126元','180元'])},今日24点前不缴将停机",
        f"用户已连续{rng.choice(['2','3'])}个月通话超套,本月又超{rng.choice(['60分钟','120分钟'])},建议推荐大语音包并核查{c['attr']}计费",
        f"长时间会议通话导致{c['attr']}分钟数爆表,超套计费仍在累计,需要人工介入核实",
        f"老人机用户不会看余额,{c['attr']}通话超套产生费用{rng.choice(['45元','90元'])},投诉风险高,请{c['svc']}客服人工跟进",
    ])
    return g3.add(synth(t), label="超量风险", severity=5, review=1, source="synth")

# 套餐咨询(sev 2, review 0)
def gen_ask():
    cn, c = pick_carrier()
    card = rng.choice(c["cards"])
    t = rng.choice([
        f"问一下,我每月大概打{rng.choice(['60分钟','120分钟','300分钟'])}电话,现在这个{rng.choice(['59元','79元','99元'])}套餐划算吗?",
        f"想换个通话分钟多点的套餐,{c['attr']}有没有推荐?主要是联系客户多",
        f"现在的套餐通话时长不够用,怎么升级?{c['attr']}保号套餐能加语音包吗?",
        f"想办{cn}的{card},{V_PKG[2]}档,通话时长够用吗?",
        f"学生党求推荐{c['attr']}低价套餐,打电话少上网多,预算{rng.choice(['19元','29元','39元'])}以内",
        f"{c['attr']}套餐外通话怎么计费?超了1分钟收多少钱?发{c['sms']}到{c['svc']}能查吗?",
        f"{c['attr']}亲情号互打免费吗?家里老人卡想一起办",
        f"公司报销话费,想办{c['attr']}通话时长多的商务套餐,有推荐吗?",
    ])
    return g4.add(synth(t), label="套餐咨询", severity=2, review=0, source="synth")

# 加购推荐(sev 2, review 0)
def gen_rec():
    cn, c = pick_carrier()
    t = rng.choice([
        f"检测到您近三个月月均通话{rng.choice(['200分钟','300分钟','450分钟'])},推荐升级{V_PKG[3]},月费更省",
        f"您本月通话将超套,推荐加购{V_PKG[0]}语音包,当月有效自动续订",
        f"按您的通话习惯(联系{rng.choice(CALLER)}为主),推荐{rng.choice(['亲情包','商务语音包','长途优惠包'])},预计每月省{rng.choice(['10元','20元','30元'])}",
        f"【{cn}】专属回馈:老用户语音包{rng.choice(['7折','5折','首月免费'])},回复{rng.choice(['1','Y','TD'])}办理",
        f"您常打长途,推荐{c['attr']}全国语音包,无长途漫游费",
        f"加购推荐:{cn}10元100分钟语音包,当月有效,超出按原价计费",
    ])
    return g5.add(synth(t), label="加购推荐", severity=2, review=0, source="synth")

QUOTA = [(gen_norm, 3800), (gen_warn, 3400), (gen_over, 3000), (gen_ask, 3400), (gen_rec, 3400)]
for fn, q in QUOTA:
    made, attempts = 0, 0
    while made < q and attempts < q * 80:
        attempts += 1
        if fn(): made += 1
    print(f"{fn.__name__}: {made}", flush=True)
rows += g1.rows + g2.rows + g3.rows + g4.rows + g5.rows

dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("voice", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=120, seed=37)
