#!/usr/bin/env python3
# Intern-Decision-4B (llama.cpp) vs Laya 工单分流对比
# 用法: python3 intern-compare.py [用例数,默认全部]
import json, sys, time, urllib.request

PORT = int(sys.argv[2]) if len(sys.argv) > 2 else 8090
N = int(sys.argv[1]) if len(sys.argv) > 1 else 99
BASE = f"http://127.0.0.1:{PORT}"

SYSTEM_PROMPT = 'You are a careful decision assistant. Use the state and decision schema in the user message to make the requested decisions. For every field, choose exactly one answer symbol (e.g. A, B, C, ...) from its listed options and return one valid JSON object mapping each field name to its chosen symbol. Use the field names and symbols exactly as given. Do not include explanations, Markdown, or extra text.'

QUESTIONS = {
    "department": {
        "type": "choice",
        "instructions": "Which team should handle this ticket?",
        "criteria": {
            "billing": "a payment the user already made is wrong: duplicate charge, invoice error, refund",
            "technical": "the product does not work: bugs, crashes, errors, outage",
            "sales": "the user wants to become a customer: pricing quote, plans, trial, contract before buying",
            "other": "general questions or how-to, not covered above",
        },
    },
    "urgency": {
        "type": "score",
        "instructions": "How urgent is this ticket?",
        "criteria": ["not urgent", "somewhat urgent", "urgent", "critical"],
    },
    "intent": {
        "type": "choice",
        "instructions": "What does the user mainly ask for in this ticket? Base the answer on what the user wants to happen next.",
        "criteria": {
            "refund": "be given money back for a payment that was already made",
            "fix": "have a technical problem repaired so they can keep using the product",
            "info": "receive information, pricing or a general answer",
        },
    },
}

CASES = [
    ("EN 重复扣费", {"subject": "Duplicate charge on invoice #4411", "body": "Hi, we were billed twice for March. Please refund the duplicate today or we will cancel our plan."}, {"department": "billing", "intent": "refund"}),
    ("EN 设置页崩溃", {"subject": "App crashes on settings page", "body": "Every time I open settings the app closes immediately. Version 3.2, Android 15."}, {"department": "technical"}),
    ("EN 询价", {"subject": "Pricing for 200 seats", "body": "We are evaluating your product for our company. Could you share the enterprise pricing for 200 seats and what the contract terms look like?"}, {"department": "sales"}),
    ("EN 退款", {"subject": "Refund not received", "body": "I cancelled my subscription two weeks ago and still have not received my refund."}, {"department": "billing", "intent": "refund"}),
    ("EN how-to", {"subject": "How to export PDF", "body": "Does the free plan include export to PDF? Where is the setting?"}, {"department": "other"}),
    ("EN 全站宕机", {"subject": "Production outage", "body": "Our production integration is completely down since 09:00 UTC. All API calls return 503. Critical blocker."}, {"department": "technical"}),
    ("中文 退款", {"subject": "退款未到账", "body": "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。"}, {"department": "billing", "intent": "refund"}),
    ("中文 500", {"subject": "登录持续报 500", "body": "今天早上开始生产环境登录就一直报 500 错误,整个团队完全无法办公,急需修复。"}, {"department": "technical"}),
    ("中文 询价", {"subject": "企业版价格咨询", "body": "我们公司大概 150 人,想了解一下企业版的价格和合同方案。"}, {"department": "sales"}),
    ("中文 how-to", {"subject": "怎么导出 PDF", "body": "免费版能导出 PDF 吗?设置在哪里?"}, {"department": "other"}),
    ("ES 退款", {"subject": "Reembolso no recibido", "body": "Cancelé mi suscripción hace dos semanas y todavía no he recibido el reembolso. Por favor, devolvedme el dinero."}, {"department": "billing", "intent": "refund"}),
][:N]

SYMBOLS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"


def build_user(state):
    schema = []
    for field, q in QUESTIONS.items():
        schema.append(f"{field}: {q['instructions']}")
        crit = q["criteria"]
        if isinstance(crit, list):  # score: 选项键是索引
            opts = [(str(i), v) for i, v in enumerate(crit)]
        else:
            opts = list(crit.items())
        for i, (key, desc) in enumerate(opts):
            schema.append(f"    {SYMBOLS[i]} = {key}: {desc}")
    return ("Return one answer for every field using the supplied answer symbols.\n\n"
            f"## State\n{json.dumps(state, ensure_ascii=False, indent=2)}\n## Decision schema\n" + "\n".join(schema))


def letter_to_key(field, letter):
    crit = QUESTIONS[field]["criteria"]
    keys = [str(i) for i in range(len(crit))] if isinstance(crit, list) else list(crit.keys())
    i = SYMBOLS.index(letter) if letter in SYMBOLS else -1
    return keys[i] if 0 <= i < len(keys) else None


def ask(state):
    body = json.dumps({
        "messages": [
            {"role": "system", "content": SYSTEM_PROMPT},
            {"role": "user", "content": build_user(state)},
        ],
        "temperature": 0,
        "max_tokens": 200,
        "chat_template_kwargs": {"enable_thinking": False},
    }).encode()
    req = urllib.request.Request(f"{BASE}/v1/chat/completions", body,
                                 {"Content-Type": "application/json"})
    t0 = time.time()
    with urllib.request.urlopen(req, timeout=300) as r:
        data = json.loads(r.read())
    dt = (time.time() - t0) * 1000
    content = data["choices"][0]["message"]["content"]
    if isinstance(content, list):
        content = "".join(p.get("text", "") for p in content if isinstance(p, dict))
    return content.strip(), dt


def parse(out):
    # 提取第一个 {...} JSON 块
    s, e = out.find("{"), out.rfind("}")
    if s < 0 or e <= s:
        return None
    try:
        return json.loads(out[s:e + 1])
    except Exception:
        return None


def main():
    ok = 0
    lats = []
    detail = []
    for name, state, want in CASES:
        try:
            out, dt = ask(state)
            lats.append(dt)
            obj = parse(out)
            if not obj:
                detail.append(f"FAIL {name}: 解析失败 raw={out[:80]!r}")
                continue
            got = {}
            for field in QUESTIONS:
                letter = str(obj.get(field, "")).strip()
                got[field] = letter_to_key(field, letter) or letter
            fails = []
            for f, w in want.items():
                if got.get(f) != w:
                    fails.append(f"{f}={got.get(f)}(期望 {w})")
            passed = not fails
            ok += passed
            line = f"{'PASS' if passed else 'FAIL'} {name}: dept={got['department']} urg={got['urgency']} intent={got['intent']} [{dt:.0f}ms]"
            detail.append(line + ("" if passed else "\n     ✗ " + "; ".join(fails)))
        except Exception as e:
            detail.append(f"FAIL {name}: {e}")
    print("\n".join(detail))
    print(f"\n== Intern-Decision-4B(q8_0): {ok}/{len(CASES)} | avg {sum(lats)/len(lats):.0f}ms | p50 {sorted(lats)[len(lats)//2]:.0f}ms ==")


main()
