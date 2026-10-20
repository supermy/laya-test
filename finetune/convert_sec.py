#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
网络安全(攻防)训练集组装——防御性告警分诊用途,不含可操作攻击载荷。
真实源:NVD CVE(公有领域)/ MITRE ATT&CK(知识)/ HF phishing;其余模板合成。
输出: data/sec-{train,test}.jsonl + sec-classes.json
用法: cd /data/ai/finetune && venv/bin/python convert_sec.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, vary, split_and_write

RAW = "/data/ai/cancer/raw"
CLASSES = ["正常业务", "侦察探测", "攻击威胁", "钓鱼欺诈", "防御处置"]
NORMAL = "正常业务"
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

# ---------- 1. 真实语料 ----------
for r in load("sec_cve.jsonl", 6000):
    cvss = float(r.get("cvss") or 0)
    sev = 2 if cvss < 4 else 3 if cvss < 7 else 4 if cvss < 9 else 5
    add(r["text"], "攻击威胁", sev, 1 if cvss >= 9 else 0, "nvd")
for r in load("sec_attack.jsonl", 1200):
    add(r["text"], "攻击威胁", 4, 0, "attack")
for r in load("sec_phish.jsonl", 4500):
    add(r["text"], "钓鱼欺诈", 4, 1, "phish")

# ---------- 2. 合成 ----------
rng = random.Random(23)
g1, g2, g3, g4 = Gen(300), Gen(300), Gen(300), Gen(300)

def synth(t, slots):
    stack = [""]
    for ch in t:
        if ch == "{": stack.append("")
        elif ch == "}":
            body = stack.pop()
            if body.isdigit(): stack[-1] += "{" + body + "}"
            else: stack[-1] += rng.choice(body.split("|"))
        else: stack[-1] += ch
    out = stack[0]
    need = max((int(m) for m in re.findall(r"\{(\d+)\}", out)), default=-1) + 1
    if need > 0:
        while len(slots) < need:
            slots = slots + [rng.choice(SLOT_POOL + SC_SLOTS)]
        out = out.format(*[rng.choice(s) for s in slots[:need]])
    return vary(rng, out)

NORMAL_T = [
    "巡检完成:{0}台服务器{1}指标正常,无告警",
    "{0}版本已发布,更新内容:{1},计划{2}窗口升级",
    "今天{0}例会讨论了{1},下周出方案",
    "用户反馈{0}打不开,排查发现是{1},已恢复",
    "{0}数据备份成功,耗时{1}分钟,校验通过",
    "新员工{0}入职,需要开通{1}权限",
    "{0}接口平均响应{1}ms,较上周{2},在正常范围",
    "会议室{0}投影仪{1},报修单已提",
    "客服工单:{0}咨询{1}发票,已答复",
    "{0}季度容量规划评审通过,扩容{1}",
    "帮我在{0}上申请一个{1}账号,谢谢",
    "测试环境{0}部署完成,等待{1}验证",
    "本月{0}报表已生成,发你邮箱",
    "打印机{0}卡纸了,谁来处理一下",
    "{0}项目周会纪要:{1}进度{2}%,风险{3}",
]
RECON = [
    "告警:{0}在{1}分钟内对{2}个端口发起连接,疑似端口扫描",
    "发现{0}对/login和/admin目录{1}目录爆破,已触发频控",
    "WAF日志:{0}使用{1}特征遍历{2}接口,疑似爬虫或探测",
    "IDS:{0}来源对{1}网段执行{2}扫描,流量特征匹配{nmap}",
    "异常UA:{0}携带{1}探测{2},行为模式符合自动化侦察",
    "{0}在{1}内尝试{2}个用户名组合,疑似账号枚举",
    "蜜罐捕获:{0}对{1}端口长时间慢速连接,疑似慢速扫描",
    "DNS日志:{0}高频查询{1}子域,疑似子域枚举",
    "{0}对{1}发送大量{2}探测包,特征库匹配{3}",
    "态势感知:{0}攻击面测绘行为,涉及{1}个存活主机",
    "告警:{0}尝试访问{1}.git/.env等敏感路径",
    "SOC:{0}对VPN入口{1}撞库尝试,锁定{2}个账号",
]
DEFENSE = [
    "已下发{0}条WAF规则,阻断{1}特征,覆盖{2}业务",
    "{0}补丁已测试通过,今晚{1}窗口对{2}台主机批量安装",
    "应急响应:{0}已隔离,取证镜像已保留,等保流程{1}",
    "封禁{0}等{1}个恶意IP,持续{2}天",
    "{0}弱口令整改完成,{1}个账号强制改密",
    "蜜罐{0}部署完成,告警接入{1}平台",
    "{0}漏洞({1})缓解方案已上线:临时{2},等待官方补丁",
    "完成{0}台终端EDR安装,覆盖率{1}%",
    "培训:{0}部门钓鱼演练点击率{1}%,已组织复训",
    "{0}备份策略调整为{1},已验证可恢复",
    "加固基线检查:{0}项不符合,已派发整改",
    "日志审计:{0}平台接入{1}类设备,告警调优{2}条",
    "双因子认证已在{0}系统启用,覆盖{1}用户",
    "漏洞扫描完成:{0}台资产,{1}个中高危,已建单跟踪",
    "SOC值班交接:{0}事件处理中,详情见{1}",
]
PHISH_ZH = [
    "【{0}】您的账号存在异常,点击{1}立即验证,否则将被冻结",
    "尊敬的用户,您有一份{0}待领取,填写{1}即可发放",
    "我是{0},这是我的新号码,方便时加一下{1}",
    "{0}快递因{1}无法送达,联系{2}处理退款",
    "系统提示:您的{0}即将过期,续费请点{1}",
    "【紧急】我是{0}领导,马上加我{1},有急事安排",
    "恭喜您在{0}活动中获得{1},凭验证码{2}领取",
    "您的{0}账单已逾期,为避免影响征信,尽快处理:{1}",
    "【银行通知】您的账户{0}存在风险交易,核实请点{1}",
    "免费领取{0}会员,仅需{1},名额有限速抢",
    "{0}IT部门通知:全员邮箱将升级,请到{1}改密码",
    "您的机票{0}航班取消,退改签请联系{1}",
]
RNG_POOL = [["32", "64", "128", "256"], ["CPU", "内存", "磁盘IO", "网络"], ["v2.3.1", "v3.0.2", "2024Q3", "hotfix-7"],
            ["登录页", "支付页", "API网关", "OA系统"], ["nmap", "masscan", "zmap", "自研工具"], ["admin", "backup", "config", "actuator"],
            ["不定期", "凌晨", "业务低峰"], ["华为云", "阿里云", "IDC机房"], ["3", "7", "15"], ["85", "92", "97"],
            ["waf", "soc", "态势感知"], ["redis", "mysql", "kafka"], ["10.0.0.15", "172.16.3.44", "192.168.8.77"],
            ["13800138000", "客服QQ", "钓鱼链接"], ["微信", "QQ", "Telegram"], ["会员", "奖金", "礼盒"],
            ["银联", "建设银行", "社保局"], ["预防针", "疫苗", "证明"]]
SLOT_POOL = RNG_POOL

def gen_norm():
    return g1.add(synth(rng.choice(NORMAL_T), [rng.choice(RNG_POOL) for _ in range(4)]), label="正常业务", severity=1, review=0, source="synth")

def gen_recon():
    return g2.add(synth(rng.choice(RECON), [rng.choice(RNG_POOL) for _ in range(4)]), label="侦察探测", severity=3, review=1, source="synth")

def gen_def():
    return g3.add(synth(rng.choice(DEFENSE), [rng.choice(RNG_POOL) for _ in range(3)]), label="防御处置", severity=2, review=0, source="synth")

def gen_phish():
    return g4.add(synth(rng.choice(PHISH_ZH), [rng.choice(RNG_POOL) for _ in range(3)]), label="钓鱼欺诈", severity=4, review=1, source="synth")

QUOTA = {"正常业务": 3500, "侦察探测": 3500, "防御处置": 3500, "钓鱼欺诈": 800}
FNS = [(gen_norm, "正常业务"), (gen_recon, "侦察探测"), (gen_def, "防御处置"), (gen_phish, "钓鱼欺诈")]
for fn, key in FNS:
    made, attempts = 0, 0
    while made < QUOTA[key] and attempts < QUOTA[key] * 80:
        attempts += 1
        if fn(): made += 1
    print(f"synth {key}: {made}", flush=True)
rows += g1.rows + g2.rows + g3.rows + g4.rows

dist = {}
for r in rows: dist[r["label"]] = dist.get(r["label"], 0) + 1
print("total:", len(rows), dist, flush=True)
split_and_write("sec", rows, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=100, seed=23)
