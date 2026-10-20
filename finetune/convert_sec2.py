#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
sec 训练集 v2(数据增强版)——防御性告警分诊用途,不含可操作攻击载荷。
相对 v1 的增强:
  1) 真实源细化严重度:NVD CVSS 连续分 → 1-5 五档(不再粗分);新增 CISA KEV(已知被利用 → 严重度5/必须研判)
  2) 合成严重度分级:每类模板带严重度提示词(高危/紧急/低危/例行),直击 v1 score MAE 短板
  3) 正常业务加入"安全像但无辜"的困难负例(例行扫描无风险、误报调优),压侦察/正常边界
  4) 类别配额封顶,避免真实攻击语料压垮其余类
输出: data/sec2-{train,test}.jsonl + sec2-classes.json
用法: cd /data/ai/finetune && venv/bin/python convert_sec2.py
"""
import json, os, random, re, sys, hashlib

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from gen_synthetic import Gen, split_and_write

RAW = "/data/ai/cancer/raw"
CLASSES = ["正常业务", "侦察探测", "攻击威胁", "钓鱼欺诈", "防御处置"]
NORMAL = "正常业务"
TARGET_PER_CLASS = 6000

rows, seen = [], set()


def add(text, label, severity, review, source):
    t = " ".join(str(text).split()).strip()
    if len(t) < 25:
        return False
    h = hashlib.md5(t.encode()).hexdigest()
    if h in seen:
        return False
    seen.add(h)
    rows.append({"text": t[:1200], "label": label, "severity": int(severity), "review": int(review), "source": source})
    return True


def load(name, cap):
    p = os.path.join(RAW, name)
    if not os.path.exists(p):
        print(f"[miss] {name}"); return []
    out = [json.loads(l) for l in open(p, encoding="utf-8") if l.strip()][:cap]
    print(f"[{name}] {len(out)}")
    return out


def cvss_sev(c):
    """CVSS 0-10 → 严重度 1-5 五档(细于 v1 的三档)"""
    if c <= 0:
        return 3
    if c < 2.0:
        return 1
    if c < 4.0:
        return 2
    if c < 6.0:
        return 3
    if c < 8.0:
        return 4
    return 5


# ---------- 1. 真实语料 ----------
for r in load("sec_cve.jsonl", 5000):
    c = float(r.get("cvss") or 0)
    add(r["text"], "攻击威胁", cvss_sev(c), 1 if c >= 9 else 0, "nvd")
for r in load("sec_attack.jsonl", 1200):
    add(r["text"], "攻击威胁", 4, 0, "attack")
for r in load("sec_phish.jsonl", 4500):
    add(r["text"], "钓鱼欺诈", 4, 1, "phish")
for r in load("sec2_kev.jsonl", 2000):
    add(r["text"], "攻击威胁", 5, 1, "kev")

# ---------- 2. 合成(带严重度提示词) ----------
rng = random.Random(2026)
g = Gen(600)

SLOT_POOL = [
    ["巡检", "发布", "备份", "扩容", "加固", "演练", "值班"],
    ["CPU", "内存", "磁盘IO", "网络", "接口", "数据库"],
    ["32", "64", "128", "256", "500"],
    ["登录页", "支付页", "API网关", "OA系统", "VPN", "邮件"],
    ["nmap", "masscan", "zmap", "自研工具"],
    ["admin", "backup", "config", "actuator", ".git", ".env", "phpinfo"],
    ["10.0.0.15", "172.16.3.44", "192.168.8.77", "203.0.113.9"],
    ["waf", "soc", "态势感知", "EDR", "IDS", "蜜罐"],
    ["redis", "mysql", "kafka", "nginx", "PostgreSQL"],
    ["0", "1", "3", "7", "15", "30"],
    ["13800138000", "客服QQ", "http://t.cn/xx", "短链"],
    ["微信", "QQ", "Telegram"],
    ["会员", "奖金", "礼盒", "优惠券", "补贴"],
    ["银联", "建设银行", "社保局", "某电商", "某银行"],
    ["华为云", "阿里云", "IDC机房", "总部", "分公司"],
    ["凌晨", "业务低峰", "周末", "发版窗口"],
    ["已完成", "已隔离", "已加固", "已封禁", "已整改"],
    ["SQL注入", "命令注入", "反序列化", "SSRF", "越权", "文件上传"],
    ["C2", "挖矿", "勒索", "远控", "横向移动", "凭证转储"],
]

SEC_PRE = ["安全告警：", "监测发现：", "日志：", "SOC通报：", "情况说明：", "工单：", "日报：", "备注："]
SEC_SUF = ["。请研判。", "，待确认。", "。", "，请核实。", "，已记录。", "。"]


def synth(t):
    """{0}{1}... 占位符随机填充(同 v1,支持 a|b 交替)"""
    stack = [""]
    for ch in t:
        if ch == "{":
            stack.append("")
        elif ch == "}":
            body = stack.pop()
            if body.isdigit():
                stack[-1] += "{" + body + "}"
            else:
                stack[-1] += rng.choice(body.split("|"))
        else:
            stack[-1] += ch
    out = stack[0]
    need = max((int(m) for m in re.findall(r"\{(\d+)\}", out)), default=-1) + 1
    if need > 0:
        out = out.format(*[rng.choice(SLOT_POOL) for _ in range(need)])
    return out


def svary(t):
    p = rng.random()
    if p < 0.3:
        return rng.choice(SEC_PRE) + t
    if p < 0.6:
        return t + rng.choice(SEC_SUF)
    if p < 0.7:
        return t
    return rng.choice(SEC_PRE) + t + rng.choice(SEC_SUF)


# (模板, 严重度) —— 严重度提示词已嵌在文本里
NORMAL_T = [
    ("巡检完成:{0}台服务器{1}指标正常,无告警", 1),
    ("{0}版本已发布,更新内容:{1},计划{2}窗口升级", 1),
    ("例行防火墙变更已审批:放行{0}到{1}的{2}端口,无异常", 1),
    ("{0}数据备份成功,耗时{1}分钟,校验通过", 1),
    ("新员工{0}入职,已开通{1}权限,流程正常", 1),
    ("{0}接口平均响应{1}ms,较上周{2},在正常范围", 1),
    ("客服工单:{0}咨询{1}发票,已答复", 1),
    ("测试环境{0}部署完成,等待{1}验证", 1),
    ("本月{0}报表已生成,发你邮箱", 1),
    ("{0}季度容量规划评审通过,扩容{1}", 1),
    ("例行漏洞扫描完成:{0}台资产无中高危,仅{1}个信息级,无需处置", 1),
    ("SIEM 告警调优:{0}规则排除正常业务后误报下降{1}%,效果良好", 1),
    ("{0}弱口令排查已纳入季度例行,本轮未发现高风险账号", 1),
    ("防火墙今日放行正常业务流量{0}万条,无拒绝告警", 1),
    ("{0}项目周会纪要:{1}进度正常,风险{2}可控", 1),
]

RECON_T = [
    ("告警:{0}在{1}分钟内对{2}个端口发起连接,疑似端口扫描(低速探测)", 2),
    ("WAF日志:{0}使用{1}特征遍历{2}接口,疑似爬虫或探测", 2),
    ("蜜罐捕获:{0}对{1}端口长时间慢速连接,疑似慢速扫描", 2),
    ("DNS日志:{0}高频查询{1}子域,疑似子域枚举", 2),
    ("发现{0}对/login和/admin目录{1}目录爆破,已触发频控", 3),
    ("IDS:{0}来源对{1}网段执行{2}扫描,流量特征匹配masscan", 3),
    ("{0}在{1}内尝试{2}个用户名组合,疑似账号枚举", 3),
    ("告警:{0}尝试访问{1}", 3),
    ("态势感知:{0}对{1}进行攻击面测绘,涉及{2}个存活主机", 3),
    ("SOC:{0}对VPN入口{1}撞库尝试,已锁定{2}个账号", 3),
]

ATTACK_T = [
    ("【高危】{0}利用未授权漏洞对{1}发起远程代码执行(RCE),已确认可利用,建议紧急修复", 5),
    ("IDS:检测到{0}图标流量,疑似C2回连,主机{1}可能已被控,需应急响应", 5),
    ("告警:主机{0}出现Webshell写入({1}),疑似已失陷", 5),
    ("公安通报:{0}行业遭遇勒索软件攻击,文件被加密并索要赎金", 5),
    ("告警:数据库{0}出现异常大批量导出,疑似数据泄露,涉及{1}", 5),
    ("WAF拦截到{0}对{1}的{2}载荷,命中EXP特征库", 4),
    ("EDR:{0}检测到权限提升与凭证转储行为,影响{1},疑似横向移动", 4),
    ("邮件网关:来自{0}的附件含宏病毒,目标{1}部门,已隔离待研判", 4),
    ("{0}登录地异常(境外IP),且短时间内提权操作,疑似账号失窃", 4),
    ("VirusTotal:{0}样本与{1}家族匹配,具备下载执行与持久化能力", 4),
    ("告警:{0}频繁触发{1}漏洞利用尝试,匹配EXP特征库,成功与否待核", 3),
    ("蜜罐诱捕到{0}对{1}的上传攻击尝试,未成功,已记录", 3),
]

PHISH_T = [
    ("【{0}】您的账号存在异常,点击{1}立即验证,否则将被冻结", 4),
    ("您的{0}账单已逾期,为避免影响征信,尽快处理:{1}", 4),
    ("【紧急】我是{0}领导,马上加我{1},有急事安排", 4),
    ("【银行通知】您的账户{0}存在风险交易,核实请点{1}", 4),
    ("尊敬的用户,您有一份{0}待领取,填写{1}即可发放", 3),
    ("我是{0},这是我的新号码,方便时加一下{1}", 3),
    ("{0}快递因{1}无法送达,联系{2}处理退款", 3),
    ("{0}IT部门通知:全员邮箱将升级,请到{1}改密码", 4),
    ("恭喜您在{0}活动中获得{1},凭验证码领取{2}", 3),
    ("免费领取{0}会员,仅需{1},名额有限速抢", 3),
]

DEFENSE_T = [
    ("已下发{0}条WAF规则,阻断{1}特征,覆盖{2}业务", 2, 0),
    ("封禁{0}等{1}个恶意IP,持续{2}天", 1, 0),
    ("{0}补丁已测试通过,今晚{1}窗口对{2}台主机批量安装", 2, 0),
    ("{0}弱口令整改完成,{1}个账号强制改密", 2, 0),
    ("漏洞扫描完成:{0}台资产,{1}个中高危,已建单跟踪", 2, 0),
    ("日志审计:{0}平台接入{1}类设备,告警调优{2}条", 1, 0),
    ("蜜罐{0}部署完成,告警已接入{1}", 1, 0),
    ("培训:{0}部门钓鱼演练点击率{1}%,已组织复训", 1, 0),
    ("双因子认证已在{0}系统启用,覆盖{1}用户", 1, 0),
    ("应急响应:{0}已隔离,取证镜像已保留,等保流程{1},已上报", 3, 1),
]


def gen_synth(class_targets):
    made = {}
    pool = [("正常业务", NORMAL_T), ("侦察探测", RECON_T), ("攻击威胁", ATTACK_T),
            ("钓鱼欺诈", PHISH_T), ("防御处置", DEFENSE_T)]
    for label, templates in pool:
        n_target = class_targets[label]
        made[label] = 0
        attempts = 0
        while made[label] < n_target and attempts < n_target * 40:
            attempts += 1
            entry = rng.choice(templates)
            t = svary(synth(entry[0]))
            sev = entry[1]
            review = entry[2] if len(entry) > 2 else (1 if (label in ("侦察探测", "钓鱼欺诈") or sev >= 4) else 0)
            if g.add(t, label=label, severity=sev, review=review, source="synth"):
                made[label] += 1
        print(f"synth {label}: {made[label]}", flush=True)


# 真实语料已占多少,合成补足到目标配额
real_cnt = {}
for r in rows:
    real_cnt[r["label"]] = real_cnt.get(r["label"], 0) + 1
print("real dist:", real_cnt, flush=True)
class_targets = {c: max(0, TARGET_PER_CLASS - real_cnt.get(c, 0)) for c in CLASSES}
gen_synth(class_targets)
rows += g.rows

# ---------- 3. 封顶 + 切分 ----------
by = {}
for r in rows:
    by.setdefault(r["label"], []).append(r)
final = []
for c in CLASSES:
    lst = by.get(c, [])
    rng.shuffle(lst)
    final += lst[:TARGET_PER_CLASS]

dist = {}
for r in final:
    dist[r["label"]] = dist.get(r["label"], 0) + 1
sev_dist = {}
for r in final:
    sev_dist[r["severity"]] = sev_dist.get(r["severity"], 0) + 1
print("final:", len(final), dist, flush=True)
print("severity dist:", dict(sorted(sev_dist.items())), flush=True)
os.makedirs("data", exist_ok=True)
split_and_write("sec2", final, "label", CLASSES, NORMAL, test_ratio=0.06, min_test_per_class=150, seed=2026)