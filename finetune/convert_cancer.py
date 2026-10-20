#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
cancer 训练集组装(5060 PC):真实源(zh + 已翻译池)+ 构造式合成风险类 → data/cancer-{train,test}.jsonl
标签体系(task_cancer.json):q0 choice 正常科普/诊疗记录/错误信息/伪科学养生/诈骗引流;q1 score severity;q2 noul review
边界:仅供文本风险筛查/分流研究,不含诊断建议;合成模板不针对真实人物/品牌。
用法: cd /data/ai/finetune-cancer && /home/my/finetune/venv/bin/python convert_cancer.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))  # 复用 gen_synthetic
from gen_synthetic import Gen, vary, split_and_write

RAW = "/data/ai/cancer/raw"
CLASSES = ["正常科普", "诊疗记录", "错误信息", "伪科学养生", "诈骗引流"]
NORMAL = "正常科普"
CAP = {"正常科普": 10500, "诊疗记录": 11000, "错误信息": 10750, "伪科学养生": 11000, "诈骗引流": 11000}
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

def load(name, cap, prefer_kw=None):
    p = os.path.join(RAW, name)
    if not os.path.exists(p):
        print(f"[miss] {name}"); return []
    prefer, rest = [], []
    for l in open(p, encoding="utf-8"):
        r = json.loads(l)
        t = r.get("text", "")
        if not t: continue
        (prefer if prefer_kw and any(k in t for k in prefer_kw) else rest).append(r)
    out = (prefer + rest)[:cap]
    print(f"[{name}] prefer={len(prefer)} taken={len(out)}")
    return out

# ---------- 1. 正常科普 ----------
kw = ("肿瘤", "癌", "化疗", "放疗", "结节", "筛查")
for r in load("cmb_exam.jsonl", 20000, kw)[:9000]:
    add(r["text"], "正常科普", 1, 0, "cmb")
for r in load("medical_book_zh.jsonl", 4500):
    add(r["text"], "正常科普", 1, 0, "medbook")
for r in load("pool_medhallu_zh.jsonl", 2000):
    if r.get("kind") == "correct":
        add(r["text"], "正常科普", 1, 0, "medhallu")

# ---------- 2. 诊疗记录/咨询 + 错误信息(真实) ----------
for r in load("toyhom_oncology.jsonl", 9000):
    add(r["text"], "诊疗记录", 2, 0, "toyhom")
for r in load("cblue.jsonl", 3000):
    if len(r.get("text", "")) > 20:
        add(r["text"], "诊疗记录", 2, 0, "cblue")
for r in load("pool_tcga_zh.jsonl", 5000):
    add(r["text"], "诊疗记录", 2, 0, "tcga")
for r in load("pool_medhallu_zh.jsonl", 2000):
    if r.get("kind") != "correct":
        add(r["text"], "错误信息", 4, 1, "medhallu")

# ---------- 3. 合成:{a|b} 交替 + {0}{1} 槽位 + vary 前后缀 ----------
rng = random.Random(7)
g1, g2, g3 = Gen(300), Gen(300), Gen(300)

def synth(t, slots):
    stack = [""]
    for ch in t:
        if ch == "{": stack.append("")
        elif ch == "}":
            body = stack.pop()
            if body.isdigit():
                stack[-1] += "{" + body + "}"  # 数字槽透传给 format
            else:
                stack[-1] += rng.choice(body.split("|"))
        else: stack[-1] += ch
    out = stack[0]
    if "{" in out and slots:
        out = out.format(*[rng.choice(s) for s in slots])
    return vary(rng, out)

CLAIM = [
    "化疗{副作用}太大，{越化疗死得越快|化疗就是医院创收的手段|不化疗反而活得更久}",
    "癌症{会|绝对会}传染，家里有{肿瘤|癌症}病人一定要{分餐|隔离碗筷|分开住}",
    "活检{穿刺}会让癌细胞{扩散|转移|跑得到处都是}，千万别做{穿刺|活检}",
    "肿瘤{摸不到|不疼不痒}就说明是{良性|早期}，不用{管它|复查}",
    "手术一切，肿瘤{反而}爆发性生长，{能不动刀就别动|千万别开刀}",
    "癌细胞怕饿，{辟谷|断食|节食}七天使肿瘤{饿死|萎缩}",
    "{酸性体质}才得癌，喝{苏打水|碱性水}中和体质就{痊愈|没事了}",
    "癌胚抗原{一升高}就是得了{癌症|肿瘤}，不用再做别的检查",
    "体检{没查出问题}就证明{绝对}不会得{癌|肿瘤}",
    "晚期癌症治疗{都是徒劳|纯属烧钱}，{回家等着|放弃治疗}就行",
    "中药{纯天然零副作用}，可以{完全替代|代替}化疗放疗",
    "肿瘤标志物{正常}就排除了所有{癌症|肿瘤}，年年查它就够了",
    "癌症病人不能吃{鸡肉|鸡蛋|海鲜|发物}，吃了肿瘤{长得更快|复发}",
    "CT检查{辐射致癌}，一年做一次就是{自己找病|慢性自杀}",
    "{止痛药|吗啡}一用就{上瘾}，{癌痛|术后疼}忍着才对",
    "肿瘤切除后{不可能}复发，{复查|体检}都是浪费钱",
    "{微波炉|手机|WiFi}辐射{致癌}，{防辐射服|仙人球}能防癌",
    "小孩得癌是{风水|因果}问题，{求神拜佛|做法事}就能好",
    "早期肺癌{一定}有症状，没{咳嗽|咯血}就没事",
    "靶向药{人人都能}吃，比化疗{好}还{无副作用|不伤身}",
    "癌性{发热|疼痛}是好转迹象，{烧得越高}肿瘤消得越快",
    "{输液|打点滴}能把{癌细胞|毒素}冲出去，每周输两次防癌",
]
PSEUDO = [
    "每天喝{0}，三个月肿瘤{自己缩小|消失}了，{1}",
    "{0}能拔出{癌毒|湿毒|淤堵}，坚持{1}肿瘤全消",
    "祖传{秘方|膏方}：{0}配{1}，专治{结节|肿块|肿瘤}，{2}人痊愈",
    "得了肿瘤先别{手术|化疗}，先{0}七天，把癌细胞{饿死|排出来}",
    "量子{检测|理疗}仪照一照，癌细胞{当场灭活|全部清零}，{0}",
    "{0}是抗癌神品，坚持吃{肿瘤|结节}自己化掉，{1}",
    "癌症就是身体太{酸}，调理成{碱性}体质{百病全消}，方法：{0}",
    "{0}敷{患处|腹部}，老辈人传下的{治癌|散结}偏方，{1}",
    "防癌只需{0}，得癌的都是{生活习惯差|心态不好|命里带的}",
    "{0}能烧死癌细胞，一周{三次|五次}结节{明显变小|摸不到了}",
    "夜里{十一点}后不睡就是{慢性自杀}，熬出来的肿瘤{治不好|没救}",
    "{0}以毒攻毒，{晚期|复发}癌症好了{大半|七成}",
    "这款{0}床垫能{磁化|净化}血液，癌细胞{无处藏身|活不成}",
    "治癌先治{心}，{0}调理，肿瘤{自愈|消失}",
    "{0}逆转癌细胞，这是{科学界|药厂}不敢公开的秘密",
    "甲状腺{结节|肿块}忌口{海鲜|碘盐}，再喝{0}两个月{散结|痊愈}",
    "癌症是{业障|因果}病，{0}十万遍肿瘤自然消",
    "化疗前先喝{0}，能{护住}免疫{不被杀光|不受损}",
    "{0}抗癌，吃了五年体检{零}结节，{1}",
    "身体{自愈力}才是神医，肿瘤是{排毒}反应，{忌医|静养}就好",
    "{0}泡水喝，{血液|淋巴}里的癌细胞全部{代谢|排出}",
    "肿瘤是{经络|气血}堵了，{0}通经络，堵点一开瘤就散",
]
SCAM = [
    "{老中医|肿瘤专家}{坐诊|把关}，加{0}免费{舌诊|问诊|看报告}，{1}",
    "印度仿制{0}代购，{原厂包装|扫码验证}，加{1}详谈",
    "{女儿|父亲|儿子}{白血病|淋巴瘤|肺癌晚期}急需{骨髓移植|手术费}，转发一次捐{一块}钱，{0}",
    "医保{报销|补贴}新政：肿瘤患者登记{0}领取{补助|退费|补贴}，{1}",
    "祖传治瘤世家，{治愈率98%|无效退款}，{0}送{体质检测|舌诊}",
    "内部渠道{0}，{三折|一折}代购，{1}",
    "防癌{体检套餐}今日{前50名免费}，登记{0}即可领取",
    "抗癌{神药}厂价直销，{0}报暗号\"{健康}\"{优先发货|立减}",
    "{肿瘤医院}内部关系可{插队|加急}手术，{0}，加微详聊",
    "{干细胞|免疫细胞}回输防癌，套餐{0}，今天{定金减半|送体检}",
    "{大师开光|加持}防癌符，配{0}服用，{痊愈}案例上千",
    "免费{领鸡蛋|领大米}听课学防癌，{0}，地址{1}",
    "{区块链|元宇宙}抗癌新药项目，认购{原始股}，上市{翻百倍|十倍回报}",
    "退休{军医|教授}专治{晚期|复发}肿瘤，{0}预约",
    "转发这条{防癌秘方}到{三个群}，{0}领取{1}",
    "你的{体检报告}有异常，我是{三甲医院}主任，{0}帮你{免排队|加急}看片",
    "{0}抗癌俱乐部招会员，年费{1}，送{专家一对一}",
    "稀有{血型|配型}求助，{酬谢|重金}，联系{0}",
]

WATERS = ["苏打水", "碱性水", "小苏打水", "柠檬水", "蒲公英水", "鱼腥草水", "苦丁茶", "玉米须水", "红薯藤水", "无根水"]
TESTI = ["亲测有效", "邻居喝了都说好", "病友群都在传", "老中医推荐的", "官方都不敢公开", "我们全村都在喝"]
MOXA = ["艾灸", "拔罐", "刮痧", "放血", "拍打", "火疗", "艾草熏"]
DURA = ["半年", "三个月", "一百天", "一年", "四十九天"]
HERB1 = ["三七粉", "灵芝孢子粉", "铁皮石斛", "白藜芦醇", "酵素", "NMN", "蜂胶", "海参肽", "破壁灵芝", "重楼"]
HERB2 = ["蜈蚣", "全蝎", "壁虎", "守宫", "蟾蜍皮", "斑蝥", "水蛭"]
NUMS = ["上千", "上万", "八千多", "三千多"]
FAST = ["辟谷", "断食", "轻断食", "果蔬汁断食", "盐水排毒"]
SUPP = ["灵芝孢子粉", "白藜芦醇", "酵素", "深海藻胶囊", "褐藻糖胶", "虫草胶囊", " NMN", "辅酶Q10"]
EVID = ["之前结节3毫米现在没了", "肿瘤标志物全转阴", "医生都惊讶", "复查全部正常"]
HEAT = ["汗蒸", "岩盘浴", "高温瑜伽", "桑拿", "艾火燎"]
CURE = ["明显变小", "摸不到了", "钙化了", "软了"]
RAW1 = ["生吃泥鳅", "喝尿疗法", "生吞活蝎", "活吃蜈蚣", "蜈蚣入药酒"]
STAG = ["晚期", "复发", "骨转移", "四期"]
FRAC = ["大半", "七成", "痊愈出院", "九成"]
MAT = ["负离子", "磁疗", "远红外", "托玛琳", "锗石"]
QIGONG = ["气功", "站桩", "打坐", "意念", "点穴"]
TEAS = ["蒲公英水", "猫爪草汤", "夏枯草膏", "芋头水", "橘核水"]
GOOD = ["放生", "吃素", "念经", "布施", "朝拜"]
DETOX = ["解毒汤", "清毒茶", "护肝茶", "五行汤", "青汁"]
PRES = ["护住", "保住", "锁住"]
KEEP = ["不被杀光", "不受损", "不掉白细胞"]
LONGS = ["十年没感冒过", "结节全没了", "癌症指标全正常", "全家没一个得癌"]
PSEUDO_SLOTS = [WATERS, TESTI, MOXA, DURA, HERB1, HERB2, NUMS, FAST, SUPP, EVID, HEAT, CURE, RAW1, STAG, FRAC, MAT, QIGONG, TEAS, GOOD, DETOX, PRES, KEEP, LONGS]

WX = ["v886699", "vx5201320", "wx_laya668", "Q77886655", "扣扣9527"]
LIMIT = ["一号难求", "仅限前20名", "过时不候", "今天最后一天"]
DRUG = ["PD-1", "奥希替尼", "格列卫", "易瑞沙", "K药", "仑伐替尼"]
IDCARD = ["身份证+银行卡", "身份证复印件", "医保卡照片", "社保卡号"]
FEE = ["收3000服务费", "先付5000定金", "红包打点"]
AMT = ["39800", "59800", "128000", "19800"]
TALIS = ["消瘤丹", "化瘤丸", "散结丹", "抗癌丸"]
CROWD = ["老哥老姐们", "叔叔阿姨们", "家人们", "病友们"]
CTA = ["加微信", "私信我", "点链接", "扫码进群"]
GRAT = ["免费", "半价", "一元", "零元"]
SCAM_SLOTS = [WX, LIMIT, DRUG, IDCARD, FEE, AMT, TALIS, CROWD, CTA, GRAT]

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
    return g3.add(synth(tpl, pick_slots(tpl, SCAM_SLOTS)), label="诈骗引流", severity=5, review=1, source="synth")

n_s = {}
for fn, key, quota in ((gen_claim, "错误信息", CAP["错误信息"]), (gen_pseudo, "伪科学养生", CAP["伪科学养生"]), (gen_scam, "诈骗引流", CAP["诈骗引流"])):
    made, attempts = 0, 0
    while made < quota and attempts < quota * 80:
        attempts += 1
        if fn(): made += 1
    n_s[key] = made
print("synth:", n_s, flush=True)
rows += g1.rows + g2.rows + g3.rows  # 合成行并入主集

# ---------- 4. 切分写盘(gen_synthetic.split_and_write → finetune-cancer/data/) ----------
dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("cancer", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=150, seed=42)
