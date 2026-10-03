#!/usr/bin/env python3
# 同一批历史工单(sample.jsonl)跑 Intern-Decision-4B,只比 department
import json, time, urllib.request
PORT = 8090
QUEUE_OK = {"billing", "technical", "sales", "other"}
SYSTEM_PROMPT = 'You are a careful decision assistant. Use the state and decision schema in the user message to make the requested decisions. For every field, choose exactly one answer symbol (e.g. A, B, C, ...) from its listed options and return one valid JSON object mapping each field name to its chosen symbol. Use the field names and symbols exactly as given. Do not include explanations, Markdown, or extra text.'
QUESTIONS = json.load(open("ci-questions.json"))
def build_user(state):
    schema = []
    for f, q in QUESTIONS.items():
        schema.append(f"{f}: {q['instructions']}")
        crit = q["criteria"]
        opts = [(str(i), v) for i, v in enumerate(crit)] if isinstance(crit, list) else list(crit.items())
        for i, (k, d) in enumerate(opts):
            schema.append(f"    {'ABCDEFGHIJKLMNOPQRSTUVWXYZ'[i]} = {k}: {d}")
    return ("Return one answer for every field using the supplied answer symbols.\n\n"
            f"## State\n{json.dumps(state, ensure_ascii=False, indent=2)}\n## Decision schema\n" + "\n".join(schema))
def ask(state):
    body = json.dumps({"messages": [{"role": "system", "content": SYSTEM_PROMPT}, {"role": "user", "content": build_user(state)}],
                       "temperature": 0, "max_tokens": 200, "chat_template_kwargs": {"enable_thinking": False}}).encode()
    req = urllib.request.Request(f"http://127.0.0.1:{PORT}/v1/chat/completions", body, {"Content-Type": "application/json"})
    t0 = time.time()
    data = json.loads(urllib.request.urlopen(req, timeout=300).read())
    c = data["choices"][0]["message"]["content"]
    if isinstance(c, list): c = "".join(p.get("text", "") for p in c if isinstance(p, dict))
    return c.strip(), (time.time() - t0) * 1000
rows = [json.loads(l) for l in open("sample.jsonl")]
ok = 0; done = 0; lats = []
for r in rows:
    try:
        out, dt = ask({"subject": r["subject"][:300], "body": r["body"][:1200]})
        lats.append(dt)
        s, e = out.find("{"), out.rfind("}")
        obj = json.loads(out[s:e+1]) if e > s else {}
        keys = list(QUESTIONS["department"]["criteria"].keys())
        letter = str(obj.get("department", "")).strip()
        i = "ABCDEFGHIJKLMNOPQRSTUVWXYZ".find(letter)
        dept = keys[i] if 0 <= i < len(keys) else letter
        passed = dept == r["gt"]
        ok += passed
        done += 1
        if done % 10 == 0:
            print(f"  进度 {done}/{len(rows)}  当前 acc={ok}/{done}", flush=True)
        with open("intern-results.jsonl", "a") as f:
            f.write(json.dumps({"queue": r["queue"], "gt": r["gt"], "pred": dept, "lang": r["language"], "ms": dt}, ensure_ascii=False) + "\n")
    except Exception as e:
        print(f"ERR: {e}", flush=True)
print(f"\n== Intern-Decision-4B 历史工单: {ok}/{done} = {ok/done*100:.1f}% ==")
