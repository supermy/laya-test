#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
微调 checkpoint -> ONNX fp32 + int8 动态量化, 目录布局对齐 /sdcard/models/laya-multilingual-int8

签名(与现网 int8 模型逐字对齐, 端侧 runner/JS 零改动):
  inputs : input_ids[1,512]i64  attention_mask[1,512]i64
           marker_pos[1,num_options]i64  marker_mask[1,num_options]bool  qtype[1]i64
  outputs: probs[1,num_options]fp32(softmax 后)  act[1,2]fp32
  opset 17, int8 动态 per-channel(同现网 manifest)

用法:
  python export_onnx.py --ckpt laya-sms-ft --data sms-test.jsonl --out laya-sms-int8
  # 拷回手机: rsync -av laya-sms-int8/ /sdcard/models/laya-sms-int8/
"""
import argparse
import json
import os
import shutil
import time

import numpy as np
import torch
from safetensors.torch import load_file
from transformers import AutoTokenizer

from laya.common import build_model, build_sequence, QTYPES


class ExportWrapper(torch.nn.Module):
    """forward(input_ids, attention_mask, marker_pos, marker_mask, qtype) -> (probs, act)"""

    def __init__(self, model):
        super().__init__()
        self.model = model

    def forward(self, input_ids, attention_mask, marker_pos, marker_mask, qtype):
        logits, act = self.model(input_ids, attention_mask, marker_pos, marker_mask, qtype)
        logits = logits.float().masked_fill(~marker_mask, -1e4)
        return torch.softmax(logits, dim=-1), act.float()


def make_inputs(text, qdef, tok, seq_len, head_max_len, state_key="sms"):
    """单条样本 -> 固定形状 numpy 输入(batch=1, seq=fixed)"""
    crit = qdef.get("criteria", {})
    if isinstance(crit, list):  # render_options 只认 dict(与 _to_internal 同规则)
        crit = {c: None for c in crit}
    q = {"t": qdef["type"], "ins": qdef["instructions"], "crit": crit}
    seq, markers = build_sequence(tok, {state_key: text}, q, seq_len, head_max_len)
    if max(markers) >= seq_len:
        raise ValueError(f"marker 位置 {max(markers)} 超出固定长度 {seq_len}, 增大 --seq-len")
    ids = list(seq[:seq_len]) + [tok.pad_token_id] * (seq_len - len(seq))
    k = len(markers)
    return {
        "input_ids": np.array([ids], dtype=np.int64),
        "attention_mask": np.array([[1] * seq_len], dtype=np.int64),
        "marker_pos": np.array([markers], dtype=np.int64),
        "marker_mask": np.ones((1, k), dtype=bool),
        "qtype": np.array([QTYPES[qdef["type"]]], dtype=np.int64),
    }


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--ckpt", required=True, help="train_sms.py 输出目录")
    ap.add_argument("--data", default=None, help="test jsonl(用于导出校验样本)")
    ap.add_argument("--out", required=True, help="输出目录(如 laya-sms-int8)")
    ap.add_argument("--seq-len", type=int, default=512, help="固定序列长(对齐现网 fixed_seq_len=512)")
    ap.add_argument("--validate-n", type=int, default=24)
    ap.add_argument("--questions", default=None, help="question_defs JSON(底座 checkpoint 没有 question_defs 时必填,如 questions.json)")
    ap.add_argument("--model-name", default=None, help="产物命名(默认取 ckpt 的 model_name 或 laya-sms)")
    ap.add_argument("--skip-validate", action="store_true", help="导出机上不做 onnxruntime 校验(回手机再验)")
    args = ap.parse_args()

    if not os.path.isdir(args.ckpt):  # HF repo id → 自动下载(支持 HF_ENDPOINT 镜像)
        from huggingface_hub import snapshot_download

        args.ckpt = snapshot_download(args.ckpt)
        print(f"已下载 checkpoint: {args.ckpt}")
    with open(os.path.join(args.ckpt, "rl_agent_config.json"), encoding="utf-8") as f:
        cfg = json.load(f)
    tok = AutoTokenizer.from_pretrained(os.path.join(args.ckpt, "tokenizer"))
    if args.questions:
        with open(args.questions, encoding="utf-8") as f:
            qdefs = json.load(f)["question_defs"]
        cfg["question_defs"] = qdefs  # 写进产物 config,JS 侧从这读
    else:
        qdefs = cfg["question_defs"]
    q_choice = qdefs[0]
    # state key 按 checkpoint 的 state_format 取(任务化后不再是固定 "sms")
    state_key = next(iter(cfg.get("state_format", {"sms": "文本"})))
    model_name = args.model_name or cfg.get("model_name", "laya-sms")

    device = "cuda" if torch.cuda.is_available() else "cpu"
    model = build_model(cfg, encoder_dir=os.path.join(args.ckpt, "encoder"))
    weights = load_file(os.path.join(args.ckpt, "model.safetensors"))
    model.load_state_dict({k: v.float() for k, v in weights.items()}, strict=True)
    model.to(device).eval()

    # ---- 导出 fp32(用真实样本 trace) ----
    os.makedirs(args.out, exist_ok=True)
    sample = None
    if args.data and os.path.exists(args.data):
        with open(args.data, encoding="utf-8") as f:
            sample = next(json.loads(l) for l in f if l.strip())
    text = sample["text"] if sample else "您好,尾号8899的卡本月账单已出,请及时查看。"
    d = make_inputs(text, q_choice, tok, args.seq_len, cfg["head_max_len"], state_key)
    dummy = tuple(torch.from_numpy(v).to(device) for v in d.values())

    fp32_path = os.path.join(args.out, f"{model_name}.fp32.onnx")
    print("导出 fp32 ONNX...", flush=True)
    torch.onnx.export(
        ExportWrapper(model),
        dummy,
        fp32_path,
        input_names=["input_ids", "attention_mask", "marker_pos", "marker_mask", "qtype"],
        output_names=["probs", "act"],
        opset_version=17,
        dynamic_axes={
            "marker_pos": {1: "num_options"},
            "marker_mask": {1: "num_options"},
            "probs": {1: "num_options"},
        },
        do_constant_folding=True,
        dynamo=False,  # torch>=2.12 默认 dynamo 导出器会把动态轴固化成静态 shape,量化 shape inference 直接炸
    )
    print(f"  {fp32_path} ({os.path.getsize(fp32_path)/1e6:.1f} MB)")

    # ---- int8 动态量化(per-channel, 同现网配方) ----
    from onnxruntime.quantization import QuantType, quantize_dynamic

    int8_path = os.path.join(args.out, f"{model_name}.int8.onnx")
    print("int8 动态量化...", flush=True)
    try:
        quantize_dynamic(fp32_path, int8_path, weight_type=QuantType.QInt8, per_channel=True)
    except TypeError:  # 旧版 onnxruntime 无 per_channel 参数
        quantize_dynamic(fp32_path, int8_path, weight_type=QuantType.QInt8)
    print(f"  {int8_path} ({os.path.getsize(int8_path)/1e6:.1f} MB)")

    # ---- fp32 vs int8 校验(argmax 一致性 + 精度损失) ----
    validation = {}
    if not args.skip_validate:
        import onnxruntime as ort

        so = ort.SessionOptions()
        s_fp32 = ort.InferenceSession(fp32_path, so, providers=["CPUExecutionProvider"])
        s_int8 = ort.InferenceSession(int8_path, so, providers=["CPUExecutionProvider"])

        rows = []
        if args.data and os.path.exists(args.data):
            with open(args.data, encoding="utf-8") as f:
                rows = [json.loads(l) for l in f if l.strip()][: args.validate_n]
        rows = rows or [{"text": text, "label": ""}]

        diffs, match, lat = [], 0, []
        for r in rows:
            try:
                d = make_inputs(r["text"], q_choice, tok, args.seq_len, cfg["head_max_len"], state_key)
            except ValueError:
                continue
            t0 = time.perf_counter()
            p_fp = s_fp32.run(None, d)[0]
            lat.append((time.perf_counter() - t0) * 1000)
            p_i8 = s_int8.run(None, d)[0]
            diffs.append(float(np.abs(p_fp - p_i8).max()))
            match += float(np.argmax(p_fp) == np.argmax(p_i8))

        n_val = len(diffs)
        validation = {
            "int8_vs_fp32": {
                "max_abs_diff": max(diffs) if diffs else None,
                "mean_abs_diff": float(np.mean(diffs)) if diffs else None,
                "argmax_match": match / n_val if n_val else None,
                "fp32_latency_ms": round(float(np.mean(lat)), 2) if lat else None,
                "n": n_val,
            }
        }
        print(f"校验({n_val} 样本): argmax 一致 {match}/{n_val}, max_abs_diff={max(diffs) if diffs else 'n/a'}")

    # ---- 配套文件(布局对齐现网) ----
    tok_dir = os.path.join(args.out, "tokenizer")
    if os.path.exists(tok_dir):
        shutil.rmtree(tok_dir)
    shutil.copytree(os.path.join(args.ckpt, "tokenizer"), tok_dir)
    if os.path.exists(os.path.join(tok_dir, "tokenizer.json")):
        shutil.copy(os.path.join(tok_dir, "tokenizer.json"), os.path.join(args.out, "tokenizer.json"))

    laya_config = {
        "max_len": args.seq_len,
        "head_max_len": cfg["head_max_len"],
        "fixed_seq_len": args.seq_len,
        "batch1": True,  # 导出的 ONNX 是固定 batch=1,JS 侧 systemOne 必须逐问推理
        "spam_threshold": 0.5,
        "temperature": cfg.get("temperature", [1.0, 1.0, 1.0]),
        "temperature_by_options": cfg.get("temperature_by_options", {}),
        "special_tokens": {"cls": "<s>", "sep": "<eos>", "mask": "<mask>", "pad": "<pad>"},
        "state_format": cfg.get("state_format", {"sms": "短信正文", "news": "新闻标题/正文"}),
        "question_defs": qdefs,
    }
    if "classes" in cfg:  # 微调 checkpoint 才有类别信息
        laya_config["classes"] = cfg["classes"]
        laya_config["normal_label"] = cfg["normal_label"]
    with open(os.path.join(args.out, "laya_config.json"), "w", encoding="utf-8") as f:
        json.dump(laya_config, f, ensure_ascii=False, indent=2)

    manifest = {
        "model": model_name,
        "source_ckpt": os.path.abspath(args.ckpt),
        "base": cfg.get("model_name", os.path.basename(args.ckpt.rstrip("/"))),
        "max_len": args.seq_len,
        "fixed_seq_len": args.seq_len,
        "head_max_len": cfg["head_max_len"],
        "temperature": cfg.get("temperature", [1.0, 1.0, 1.0]),
        "quantization": "int8 dynamic per-channel (ORT)",
        "opset": 17,
        "size_mb": round(os.path.getsize(int8_path) / 1e6, 1),
        "validation": validation,
    }
    with open(os.path.join(args.out, "laya-manifest.json"), "w", encoding="utf-8") as f:
        json.dump(manifest, f, ensure_ascii=False, indent=2)

    print(f"完成: {args.out}/  (int8 + fp32 回退 + tokenizer + laya_config + manifest)")


if __name__ == "__main__":
    main()
