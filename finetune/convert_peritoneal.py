#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
腹膜癌专用训练集组装(两阶段:以 laya-cancer-ft 为底座继续训)。
输入: /data/ai/cancer/raw/{peritoneal_zh,peritoneal_en,pool_peritoneal_zh}.jsonl + 通用 zh 背景(cmb/medbook/toyhom)
输出: data/peritoneal-{train,test}.jsonl + peritoneal-classes.json(写入 /data/ai/finetune/data/)
用法: cd /data/ai/finetune && /home/my/finetune/venv/bin/python convert_peritoneal.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, vary, split_and_write

RAW = "/data/ai/cancer/raw"
CLASSES = ["正常科普", "诊疗记录", "错误信息", "伪科学养生", "诈骗引流"]
NORMAL = "正常科普"
rows, seen = [], set()

def add(text, label, severity, review, source):
    t = " ".join(str(text).split()).strip()
    if len(t) < 25:
        return False
    h = hashlib.md5(t.encode()).hexdigest()
    if h in seen:
        return False
    seen.add(h)
    rows.append({"text": t[:1200], "label": label, "severity": severity, "review": review, "source": source})
    return True

def load(name, cap):
    p = os.path.join(RAW, name)
    if not os.path.exists(p):
        print(f"[miss] {name}"); return []
    out = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()][:cap]
    print(f"[{name}] {len(out)}")
    return out

# ---------- 1. 腹膜癌真实语料(zh) ----------
ZK = ("腹膜", "腹水", "腹腔")
for r in load("peritoneal_zh.jsonl", 3000):
    t = r.get("text", "")
    src = r.get("src", "")
    if src in ("cmb_exam", "medical_book_zh"):
        add(t, "正常科普", 1, 0, src)
    else:
        add(t, "诊疗记录", 2, 0, src)

# ---------- 2. 腹膜癌真实语料(已翻译 en→zh) ----------
for r in load("pool_peritoneal_zh.jsonl", 2000):
    add(r["text"], "诊疗记录", 2, 0, r.get("src", "en"))

# ---------- 3. 通用癌症 zh 背景(防灾难遗忘,约占 1/3) ----------
for r in load("cmb_exam.jsonl", 2500):
    add(r["text"], "正常科普", 1, 0, "cmb-bg")
for r in load("medical_book_zh.jsonl", 1500):
    add(r["text"], "正常科普", 1, 0, "medbook-bg")
for r in load("toyhom_oncology.jsonl", 2500):
    add(r["text"], "诊疗记录", 2, 0, "toyhom-bg")

# ---------- 4. 腹膜癌特化合成 ----------
rng = random.Random(11)
g1, g2, g3 = Gen(300), Gen(300), Gen(300)

def synth(t, slots):
    stack = [""]
    for ch in t:
        if ch == "{": stack.append("")
        elif ch == "}":
            body = stack.pop()
            if body.isdigit():
                stack[-1] += "{" + body + "}"
            else:
                stack[-1] += rng.choice(body.split("|"))
        else: stack[-1] += ch
    out = stack[0]
    need = max((int(m) for m in re.findall(r"\{(\d+)\}", out)), default=-1) + 1
    if need > 0:
        while len(slots) < need:  # 模板槽序不连续(如只有 {1})时补齐,防 IndexError
            slots = slots + [rng.choice(PSEUDO_SLOTS + SC_SLOTS)]
        out = out.format(*[rng.choice(s) for s in slots[:need]])
    return vary(rng, out)

CLAIM = [  # 错误信息(腹膜/腹水谣言)
    "腹水{抽了就没了|一抽就灵}，不用查{原因|原发病}",
    "{腹腔热灌注|热化疗}会把{肠子|内脏}烧坏，千万别做",
    "腹膜{转移|种植}就是{没救|等死}，治疗{白花钱|纯属折腾}",
    "腹水是身体在{排毒|排湿}，{排干净|流干净}就好了",
    "{腹膜癌|腹水}会{传染|遗传给下一代}，{餐具|生活用品}都要分开",
    "抽腹水越抽越{多|上瘾}，{坚决不能抽|医院就为了收费}",
    "{腹腔|腹部}里有{水|积液}，{热敷|暖水袋}焐一焐就吸收了",
    "腹膜癌{吃止痛药}就行，{手术|化疗}都是过度治疗",
    "{CA125|肿瘤标志物}正常就{绝对}没有腹膜转移",
    "腹水{化验|病理}不需要，{老中医|摸脉}一看便知",
    "腹腔{积液}是{穿刺}引起的，不穿刺就不会有",
    "{晚期}腹膜癌{化疗}只会{加速死亡|人财两空}",
    "腹水患者{不能喝水}，喝一口涨一斤",
    "{腹腔镜|探查手术}会{气腹|把癌细胞吹散}，千万别做",
    "腹膜{增厚|结节}是{炎症}，抗炎输液就能{消下去|痊愈}",
]
PSEUDO = [  # 伪科学养生(腹水/腹膜偏方)
    "{0}敷肚脐，{腹水|积水}当天就{消|往下走}，{1}",
    "{0}煮水喝，三天排掉{腹腔积液}，{1}",
    "{艾灸|拔罐}神阙{穴|关元穴}，{癌性腹水|积水}自己{吸收|消失}",
    "祖传{消水方|利水散}：{0}配{1}，专治{腹水|腹膜转移}，{2}人痊愈",
    "腹水是{脾虚|湿气重}，{0}健脾胃，积水{自己化掉|自动排走}",
    "{薏米|赤小豆|冬瓜皮}汤利尿{排癌毒}，喝一个月{腹水|肿瘤}全消",
    "量子{理疗|水疗}把{腹腔}积水{振碎|排出}，{1}",
    "{高温汗蒸|盐敷}把{腹水|寒湿}烤干，结节{跟着消}",
    "{白蛋白|人血白蛋白}食补疗法：{0}炖汤，腹水{再也不}来",
    "腹膜癌{忌盐忌水}，{0}代水喝，{积水|肿瘤}自愈",
    "{磁疗|远红外}腹带{24小时}佩戴，{腹水|肠粘连}全通",
    "{放血|刺络}疗法排{腹腔}毒水，{老中医}亲授，{1}",
    "{酵素|青汁}灌肠{排毒}，{腹水|宿便}一起清空，{1}",
    "肿瘤{怕热}，{0}浴把腹腔{烤到}42度，癌细胞{烫死}",
    "{蟾蜍|壁虎}焙干研末冲服，{腹水|腹膜肿瘤}三天见效",
]
S1 = [["土豆片", "芒硝", "大黄粉", "葱姜泥", "艾灰"], ["亲测有效", "病友群实拍", "三天就见效", "老辈人的方子"]]
S2 = [["薏米", "赤小豆", "车前草", "玉米须", "黄芪", "半边莲", "半枝莲", "白花蛇舌草"], ["邻居喝了都说好", "病友群都在传", "亲测有效", "祖上相传"]]
S3 = [["三七", "甘遂", "大戟", "芫花", "牵牛子", "蝼蛄", "蟋蟀"], ["大枣", "生姜", "甘草", "陈皮"], ["上千", "几千", "八百多"]]
S4 = [["斑蝥", "蟾酥", "壁虎", "蜈蚣", "水蛭"], ["亲历", "真人真事", "上千案例"]]
S5 = [["灵芝孢子粉", "白藜芦醇", "褐藻糖胶", "虫草", "桑黄"], ["腹水再没来过", "积水全消", "肿瘤标志物正常"]]
S6 = [["汗蒸", "盐敷", "艾火", "蜡疗"], ["结节跟着消", "积水全干", "肚子变小"]]
S7 = [["磁疗", "远红外", "石墨烯", "托玛琳"], ["腹水全通", "积水不再生", "活下去有劲"]]
S8 = [["黄芪", "党参", "山药", "茯苓"], ["腹水全消", "积水自愈", "复发 impossible", "再也不涨水"]]
S9 = [["人参", "鹿茸", "海参", "泥鳅", "鲫鱼"], ["腹水", "积水"]]
S10 = [["蜂胶", "酵素", "益生菌", "青汁"], ["腹水自愈", "积水全排", "肿瘤清零"]]
PSEUDO_SLOTS = [S1, S2, S3, S4, S5, S6, S7, S8, S9, S10]

SCAM = [  # 诈骗引流(腹水/腹膜)
    "祖传{消水|利水}秘方，{加微|私信}{0}，{1}",
    "{老中医|腹水专家}上门{抽腹水|诊疗}，{0}，{1}",
    "印度仿制{0}治{腹膜转移|腹水}，{1}，加{2}详谈",
    "{腹水|腹腔积液}{代抽|引流}上门服务，{0}，{1}",
    "筹款：{母亲|父亲}腹膜癌{晚期|急需}，转发{一次}捐{一块}，{0}",
    "{腹腔热灌注}内部名额，{医院}关系{插队}，{0}",
    "免费{领鸡蛋}听课{消腹水}，{0}，地址{1}",
    "{干细胞|免疫细胞}治{腹膜癌}套餐{0}，今天{定金减半}",
    "{白蛋白|人血白蛋白}内部渠道{代购}，{比医院}便宜{一半}，加{0}",
    "你的{腹水报告}我看过，{三甲主任}退休在家，{0}帮你{调理}",
    "{消水丹|利水贴}厂价直销，{0}报暗号\"{排水}\"{立减}",
    "{大师|气功师}远程发功{排腹水}，{0}，{1}",
]
SC_SLOTS = [["v886699", "vx5201320", "Q77886655", "wx_laya668"], ["一号难求", "仅限今天", "过时不候"],
            ["奥沙利铂", "贝伐珠单抗", "奥希替尼", "K药"], ["收500上门费", "先付800定金", "红包打点"],
            ["老哥老姐们", "家人们", "病友们"], ["39800", "59800", "29800"], ["消水丹", "利水贴", "散结膏"],
            ["老中医", "专家", "教授"], ["三折", "一折", "半价"]]

def n_slots(tpl):
    return len(re.findall(r"\{(\d+)\}", tpl))

def pick_slots(tpl, pool):
    out, used = [], set()
    for _ in range(n_slots(tpl)):
        cand = [s for s in pool if id(s) not in used]
        s = rng.choice(cand) if cand else rng.choice(pool)
        used.add(id(s))
        out.append(s)
    return out

def gen_claim():
    return g1.add(synth(rng.choice(CLAIM), []), label="错误信息", severity=4, review=1, source="synth")

def gen_pseudo():
    tpl = rng.choice(PSEUDO)
    return g2.add(synth(tpl, pick_slots(tpl, PSEUDO_SLOTS)), label="伪科学养生", severity=4, review=1, source="synth")

def gen_scam():
    tpl = rng.choice(SCAM)
    return g3.add(synth(tpl, pick_slots(tpl, SC_SLOTS)), label="诈骗引流", severity=5, review=1, source="synth")

QUOTA = {"错误信息": 3800, "伪科学养生": 3800, "诈骗引流": 3800}
for fn, key in ((gen_claim, "错误信息"), (gen_pseudo, "伪科学养生"), (gen_scam, "诈骗引流")):
    made, attempts = 0, 0
    while made < QUOTA[key] and attempts < QUOTA[key] * 80:
        attempts += 1
        if fn(): made += 1
    print(f"synth {key}: {made}", flush=True)
rows += g1.rows + g2.rows + g3.rows

dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("peritoneal", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=100, seed=11)
