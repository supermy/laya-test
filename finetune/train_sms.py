#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Laya 垃圾短信 RLCD 微调 — 单卡版
(改自官方 notebooks/laya_finetune_typed_decisions_2xT4_kaggle.ipynb, 去 DDP)

依赖(云端 GPU 机器):
  export HF_ENDPOINT=https://hf-mirror.com   # 国内镜像
  pip install "laya==0.3.4" "transformers>=4.48.0" safetensors huggingface_hub accelerate torch numpy

用法:
  python train_sms.py --output laya-sms-ft
  python train_sms.py --data-dir ./data --prefix sms --output laya-sms-ft
  # 4090/3090 建议加 --bf16;OOM 则 --micro-batch 4

输入: {prefix}-train.jsonl + {prefix}-classes.json  (prepare_data.py 产出)
输出: --output 目录(HF checkpoint 布局, 可被 laya.Agent 直接加载):
  model.safetensors / encoder/ / tokenizer/ / rl_agent_config.json

任务定义(推理侧必须一字不差, 已写进 rl_agent_config.json 的 question_defs):
  state = {"sms": <短信正文>}
  问题 1: choice "这条短信的类别" criteria = classes.json 的 classes(顺序固定)
  问题 2: noul   "这是垃圾或骚扰短信吗"  (true = label != normal_label)

显存: 322M 全参微调, fp16/bf16 + 梯度检查点, 16GB(T4) 够用。
注意: laya 包内部 API 以官方 notebook 为准(版本锁 0.3.4);若 import 报错说明接口漂移, 以 laya.common 实际为准。
"""
import argparse
import json
import os
import random
import time

import numpy as np
import torch
from safetensors.torch import load_file, save_file
from transformers import AutoTokenizer

from laya.agent import _fix_tokenizer_config
from laya.common import build_model, build_sequence, proper_reward, QTYPES, render_options

CHOICE_INS = "这条短信的类别"
NOUL_INS = "这是垃圾或骚扰短信吗"

# 内置任务(等价 task_sms.json);--task 换任意任务(如 task_stock.json)
# 字段: state_key=state 的 key; questions[].from=数据行里取真值的字段; derive=not_normal 表示"标签≠normal 类"
TASK_SMS = {
    "state_key": "sms",
    "questions": [
        {"qid": "q0", "type": "choice", "instructions": CHOICE_INS, "from": "label"},
        {"qid": "q1", "type": "noul", "instructions": NOUL_INS, "from": "label", "derive": "not_normal"},
    ],
}


def build_items(path, task, classes, normal, tok, cfg, eps):
    """数据行 -> RLCD 训练 items(按 task.questions 每行展开多问, label smoothing eps)"""
    n_cls = len(classes)
    if n_cls < 2:
        raise SystemExit("至少需要 2 个类别")
    state_key = task["state_key"]
    # 产物 config 里的 question_defs(不含训练侧 from/derive 字段)
    qdefs = [
        {
            "qid": qd["qid"],
            "type": qd["type"],
            "instructions": qd["instructions"],
            "criteria": list(classes) if qd["type"] == "choice" else qd.get("criteria", []),
        }
        for qd in task["questions"]
    ]

    def q_internal(qd):
        if qd["type"] == "choice":
            # render_options 的 choice 分支调 crit.items(),必须是 dict;None = 无描述只渲染标签名
            return {"t": "choice", "ins": qd["instructions"], "crit": {c: None for c in classes}}
        if qd["type"] == "score":
            return {"t": "score", "ins": qd["instructions"], "crit": qd["criteria"]}
        return {"t": "noul", "ins": qd["instructions"], "crit": {}}

    items = []
    with open(path, encoding="utf-8") as f:
        for line in f:
            line = line.strip()
            if not line:
                continue
            d = json.loads(line)
            state = {state_key: d["text"]}
            row = []
            for qd in task["questions"]:
                gold = d.get(qd.get("from", "label"))
                if gold is None:
                    continue  # 该问真值缺失(如教师还没标):跳过此问,行内其余问照常训练
                if qd["type"] == "choice":
                    if gold not in classes:
                        continue  # 越界标签:只丢这一问
                    t = [eps / (n_cls - 1)] * n_cls
                    t[classes.index(gold)] = 1.0 - eps
                elif qd["type"] == "score":
                    lvl = int(gold)
                    n = len(qd["criteria"])
                    if not (1 <= lvl <= n):
                        continue
                    t = [eps / (n - 1)] * n
                    t[lvl - 1] = 1.0 - eps
                else:  # noul: true=1
                    v = (1 if gold != normal else 0) if qd.get("derive") == "not_normal" else int(gold)
                    t = [eps, 1.0 - eps] if v == 1 else [1.0 - eps, eps]
                row.append((q_internal(qd), t))
            for q, tq in row:
                seq, markers = build_sequence(tok, state, q, cfg["max_len"], cfg["head_max_len"])
                if len(markers) != len(render_options(q)):
                    continue
                items.append(
                    {
                        "ids": seq,
                        "markers": markers,
                        "qtype": QTYPES[q["t"]],
                        "target": tq,
                        "label": int(np.argmax(tq)),
                    }
                )
    return items, qdefs


def collate_train_batch(items, pad_id):
    n, L = len(items), max(len(it["ids"]) for it in items)
    kmax = max(len(it["markers"]) for it in items)
    ids = torch.full((n, L), pad_id, dtype=torch.long)
    att = torch.zeros((n, L), dtype=torch.long)
    mpos = torch.zeros((n, kmax), dtype=torch.long)
    mmask = torch.zeros((n, kmax), dtype=torch.bool)
    target = torch.zeros((n, kmax), dtype=torch.float32)
    for i, it in enumerate(items):
        ids[i, : len(it["ids"])] = torch.tensor(it["ids"])
        att[i, : len(it["ids"])] = 1
        k = len(it["markers"])
        mpos[i, :k] = torch.tensor(it["markers"])
        mmask[i, :k] = True
        target[i, : len(it["target"])] = torch.tensor(it["target"], dtype=torch.float32)
    return {
        "input_ids": ids,
        "attention_mask": att,
        "marker_pos": mpos,
        "marker_mask": mmask,
        "target": target,
        "qtype": torch.tensor([it["qtype"] for it in items]),
        "label": torch.tensor([it["label"] for it in items]),
    }


def fit_one_temp(sel):
    """LBFGS 拟合单类温度(同官方 notebook)"""
    if len(sel) < 10:
        return 1.0
    kmax = max(len(z) for z, _ in sel)
    Z = torch.full((len(sel), kmax), -1e4)
    T = torch.zeros((len(sel), kmax))
    for i, (z, t) in enumerate(sel):
        Z[i, : len(z)] = torch.tensor(z)
        T[i, : len(t)] = torch.tensor(t, dtype=torch.float32)
    log_t = torch.zeros(1, requires_grad=True)
    opt = torch.optim.LBFGS([log_t], lr=0.1, max_iter=100)

    def closure():
        opt.zero_grad()
        loss = -(T * torch.log_softmax(Z / log_t.exp(), -1)).sum(-1).mean()
        loss.backward()
        return loss

    opt.step(closure)
    return float(torch.clamp(log_t.exp(), 0.1, 10.0).item())


def save_ckpt(model, tok, ckpt_dir):
    os.makedirs(ckpt_dir, exist_ok=True)
    sd = {k: v.half().contiguous().cpu() for k, v in model.state_dict().items()}
    save_file(sd, os.path.join(ckpt_dir, "model.safetensors"))
    model.encoder.config.save_pretrained(os.path.join(ckpt_dir, "encoder"))
    tok.save_pretrained(os.path.join(ckpt_dir, "tokenizer"))


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--model", default="convaiinnovations/laya-multilingual", help="底座(中文必须 multilingual)")
    ap.add_argument("--task", default="sms", help="任务定义: sms(内置) | stock(task_stock.json) | <task_*.json 的名字>")
    ap.add_argument("--data-dir", default=".")
    ap.add_argument("--prefix", default="sms")
    ap.add_argument("--output", required=True)
    ap.add_argument("--epochs", type=int, default=4)
    ap.add_argument("--micro-batch", type=int, default=8)
    ap.add_argument("--grad-accum", type=int, default=4)
    ap.add_argument("--group-size", type=int, default=4, help="GRPO baseline 采样数")
    ap.add_argument("--lr-encoder", type=float, default=2.5e-5)
    ap.add_argument("--lr-head", type=float, default=1.0e-4)
    ap.add_argument("--sigma-start", type=float, default=0.4, help="探索噪声起点")
    ap.add_argument("--sigma-end", type=float, default=0.1)
    ap.add_argument("--label-smooth", type=float, default=0.05)
    ap.add_argument("--calib-max", type=int, default=400, help="温度校准留出片上限")
    ap.add_argument("--weight-decay", type=float, default=0.01)
    ap.add_argument("--bf16", action="store_true", help="Ampere+ 用 bf16(免 GradScaler)")
    ap.add_argument("--no-grad-ckpt", action="store_true", help="显存充裕时关梯度检查点提速")
    ap.add_argument("--seed", type=int, default=42)
    args = ap.parse_args()

    assert torch.cuda.is_available(), "需要 CUDA GPU"

    model_dir = args.model if os.path.isdir(args.model) else None
    if model_dir is None:
        from huggingface_hub import snapshot_download

        model_dir = snapshot_download(args.model)
    _fix_tokenizer_config(model_dir)

    with open(os.path.join(model_dir, "rl_agent_config.json")) as f:
        cfg = json.load(f)
    cfg["gradient_checkpointing"] = not args.no_grad_ckpt
    cfg["max_tokens_per_batch"] = 4096

    tok = AutoTokenizer.from_pretrained(os.path.join(model_dir, "tokenizer"))

    with open(os.path.join(args.data_dir, f"{args.prefix}-classes.json"), encoding="utf-8") as f:
        meta = json.load(f)
    classes, normal = meta["classes"], meta["normal_label"]
    print(f"类别({len(classes)}): {classes} | normal={normal}", flush=True)

    task_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), f"task_{args.task}.json")
    if os.path.exists(task_path):
        with open(task_path, encoding="utf-8") as f:
            task = json.load(f)
    elif args.task == "sms":
        task = TASK_SMS
    else:
        raise SystemExit(f"任务定义不存在: {task_path}")

    items, qdefs = build_items(
        os.path.join(args.data_dir, f"{args.prefix}-train.jsonl"),
        task, classes, normal, tok, cfg, args.label_smooth,
    )
    n_q = len(task["questions"])
    print(f"训练 items: {len(items)} (每行 {n_q} 问)", flush=True)

    # 温度校准片必须留出训练(官方教训: 混进训练集会把温度拟合变成复读)
    order = list(range(len(items)))
    random.Random(20260922).shuffle(order)
    n_calib = min(args.calib_max, len(items) // 10)
    calib_items = [items[i] for i in sorted(order[:n_calib])]
    train_items = [items[i] for i in sorted(order[n_calib:])]
    print(f"train={len(train_items)} calib={len(calib_items)}", flush=True)

    device = torch.device("cuda")
    model = build_model(cfg, encoder_dir=os.path.join(model_dir, "encoder"))
    weights = load_file(os.path.join(model_dir, "model.safetensors"))
    model.load_state_dict({k: v.float() for k, v in weights.items()}, strict=True)
    if cfg["gradient_checkpointing"]:
        model.encoder.gradient_checkpointing_enable(gradient_checkpointing_kwargs={"use_reentrant": False})
    model.head_checkpointing = True
    model.to(device)
    model.train()

    MICRO, ACCUM, GROUP = args.micro_batch, args.grad_accum, args.group_size
    enc_params = [p for n, p in model.named_parameters() if "encoder." in n]
    head_params = [p for n, p in model.named_parameters() if "encoder." not in n]
    optimizer = torch.optim.AdamW(
        [
            {"params": enc_params, "lr": args.lr_encoder},
            {"params": head_params, "lr": args.lr_head},
        ],
        weight_decay=args.weight_decay,
    )
    total_updates = (len(train_items) // (MICRO * ACCUM)) * args.epochs
    scheduler = torch.optim.lr_scheduler.CosineAnnealingLR(optimizer, T_max=max(1, total_updates), eta_min=1e-6)
    amp_dtype = torch.bfloat16 if args.bf16 else torch.float16
    scaler = torch.amp.GradScaler("cuda", enabled=not args.bf16)

    t0 = time.time()
    for epoch in range(args.epochs):
        random.seed(args.seed + epoch)
        random.shuffle(train_items)
        epoch_loss, n_batches = 0.0, 0
        optimizer.zero_grad(set_to_none=True)
        accum_step = 0
        sigma = args.sigma_start + (args.sigma_end - args.sigma_start) * epoch / max(1, args.epochs - 1)

        for b_idx in range(0, len(train_items), MICRO):
            chunk = train_items[b_idx : b_idx + MICRO]
            if not chunk:
                continue
            batch = collate_train_batch(chunk, tok.pad_token_id)
            with torch.autocast("cuda", dtype=amp_dtype):
                logits, act = model(
                    batch["input_ids"].to(device),
                    batch["attention_mask"].to(device),
                    batch["marker_pos"].to(device),
                    batch["marker_mask"].to(device),
                    batch["qtype"].to(device),
                )
            logits = logits.float()
            mask = batch["marker_mask"].to(device)
            k = mask.sum(-1, keepdim=True).float()
            target = batch["target"].to(device)

            # 1) GROUP 份零均值高噪 logits 采样(同官方 RLCD)
            eps = torch.randn((GROUP,) + logits.shape, device=device) * sigma * mask
            eps = (eps - eps.sum(-1, keepdim=True) / k) * mask
            z = logits.detach().unsqueeze(0) + eps
            q = torch.softmax(z.masked_fill(~mask, -1e4), -1)

            # 2) strictly proper scoring rule 奖励 + group-mean baseline
            with torch.no_grad():
                r = proper_reward(q, target.unsqueeze(0), batch["qtype"].to(device), mask, w_sph=0.75, w_rps=1.0)
                adv = r - r.mean(0, keepdim=True)
                adv = adv / (adv.std() + 1e-6)

            # 3) REINFORCE 策略梯度 + 1.0 权重软交叉熵引导
            logp = -(((z - logits.unsqueeze(0)) ** 2) * mask).sum(-1) / (2 * sigma**2)
            loss_rl = -(adv * logp).mean()
            loss_ce = -(target * torch.log_softmax(logits.masked_fill(~mask, -1e4), -1)).sum(-1).mean()
            loss = (loss_rl + 1.0 * loss_ce) / ACCUM + 0.0 * act.sum()

            scaler.scale(loss).backward()
            accum_step += 1
            if accum_step % ACCUM == 0 or (b_idx + MICRO) >= len(train_items):
                scaler.unscale_(optimizer)
                torch.nn.utils.clip_grad_norm_(model.parameters(), 1.0)
                scaler.step(optimizer)
                scaler.update()
                scheduler.step()
                optimizer.zero_grad(set_to_none=True)

            epoch_loss += loss.item() * ACCUM
            n_batches += 1
            if n_batches % 50 == 0:
                print(
                    f"  epoch {epoch+1}/{args.epochs} step {n_batches} "
                    f"loss {loss.item()*ACCUM:.4f} reward {r.mean().item():.3f} "
                    f"lr {scheduler.get_last_lr()[0]:.2e}",
                    flush=True,
                )
        print(f"=== epoch {epoch+1}/{args.epochs} done in {time.time()-t0:.0f}s avg_loss {epoch_loss/max(1,n_batches):.4f} ===", flush=True)
        save_ckpt(model, tok, os.path.join(args.output, "checkpoint_latest"))

    # ---- 温度校准(留出片, 从未进训练) ----
    print("拟合校准温度...", flush=True)
    del optimizer, scaler, scheduler
    torch.cuda.empty_cache()
    model.eval()
    calib_preds = []
    with torch.no_grad():
        for c_idx in range(0, len(calib_items), 16):
            c_chunk = calib_items[c_idx : c_idx + 16]
            cb = collate_train_batch(c_chunk, tok.pad_token_id)
            with torch.autocast("cuda", dtype=amp_dtype):
                l_sub, _ = model(
                    cb["input_ids"].to(device),
                    cb["attention_mask"].to(device),
                    cb["marker_pos"].to(device),
                    cb["marker_mask"].to(device),
                    cb["qtype"].to(device),
                )
            l_np = l_sub.float().cpu().numpy()
            for ri, it in enumerate(c_chunk):
                kk = len(it["markers"])
                calib_preds.append((it["qtype"], l_np[ri, :kk], it["target"]))

    temps = [1.0, 1.0, 1.0]  # choice / score / noul (score 本任务没有, 保持 1.0)
    for qt in range(3):
        sel = [(z, t) for q_type, z, t in calib_preds if q_type == qt]
        if sel:
            temps[qt] = fit_one_temp(sel)
    print(f"温度(choice,score,noul): {[round(t, 3) for t in temps]}", flush=True)

    os.makedirs(args.output, exist_ok=True)
    sd = {k: v.half().contiguous().cpu() for k, v in model.state_dict().items()}
    save_file(sd, os.path.join(args.output, "model.safetensors"))
    model.encoder.config.save_pretrained(os.path.join(args.output, "encoder"))
    tok.save_pretrained(os.path.join(args.output, "tokenizer"))
    cfg["fine_tuned"] = True
    cfg["model_name"] = f"laya-{args.task}"
    cfg["temperature"] = temps
    cfg.pop("temperature_by_options", None)  # 官方坑: 遗留分桶会盖掉新温度
    cfg["classes"] = classes
    cfg["normal_label"] = normal
    cfg["state_format"] = {task["state_key"]: "文本"}
    cfg["question_defs"] = qdefs
    with open(os.path.join(args.output, "rl_agent_config.json"), "w", encoding="utf-8") as f:
        json.dump(cfg, f, ensure_ascii=False, indent=2)
    print(f"完成: {args.output}", flush=True)


if __name__ == "__main__":
    main()
