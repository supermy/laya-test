#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""通用:LayaLiteRT 手机包组装 + ModelScope supermy/myjev 上传(改造自 upload_news2_myjev.py)。
用法: ~/aet/bin/python assemble_myjev.py --biz cancer --label "癌症文本风险筛查"
前置: /data/ai/{laya-<biz>-ft, laya-<biz>-int8, litert-<biz>-conv, litert-<biz>-negfix, laya-aot/out-<biz>}
"""
import argparse, hashlib, json, os, shutil, sys

from modelscope.hub.api import HubApi

ap = argparse.ArgumentParser()
ap.add_argument("--biz", required=True)
ap.add_argument("--label", required=True)
args = ap.parse_args()
BIZ = args.biz

DATA = '/data/ai'
CONV = f'{DATA}/litert-{BIZ}-conv'
NEG = f'{DATA}/litert-{BIZ}-negfix'
AOT = f'{DATA}/laya-aot/out-{BIZ}'
FT = f'{DATA}/laya-{BIZ}-ft'
INT8 = f'{DATA}/laya-{BIZ}-int8'
PKG = f'{DATA}/phone-{BIZ}'
TOKEN = os.environ.get('MS_TOKEN') or 'ms-bdaee2fc-0044-4c00-aa5e-3ad0ecf64c03'

def need(path):
    if not os.path.exists(path):
        print('MISSING:', path); sys.exit(1)

def find_aot():
    need(AOT)
    for f in os.listdir(AOT):
        if f.endswith('_apply_plugin.tflite'):
            return os.path.join(AOT, f)
    print('MISSING: aot tflite in', AOT); sys.exit(1)

def sha256(p):
    h = hashlib.sha256()
    with open(p, 'rb') as f:
        for chunk in iter(lambda: f.read(1 << 20), b''): h.update(chunk)
    return h.hexdigest()

def make_readme(pkg, biz, label):
    """生成模型包使用说明 README.md(随包上传与分发)"""
    ft = f'{DATA}/laya-{biz}-ft'
    task = json.load(open(f'{DATA}/finetune/task_{biz}.json'))
    cal = json.load(open(f'{pkg}/laya_ml_calibration.json'))
    lines = []
    A = lines.append
    A(f'# laya_{biz} 手机端模型包 — {label}')
    A('')
    A('## 用途')
    A(f'{label}(LayaLiteRT 端侧文本分诊,离线运行,不上传数据)。')
    A(f'来源模型:laya-{biz}-ft(业务 {biz});序列长度 {cal.get("provenance", {}).get("max_len", 256)}。')
    A('')
    A('## 题组口径(questions.json)')
    for q in task.get('questions', []):
        A(f'- **{q.get("qid")}** [{q.get("type")}] {q.get("instructions","")}')
        for i, c in enumerate(q.get('criteria', [])):
            A(f'  - {i+1}. {c}')
    A('')
    A('## 文件清单')
    A('| 文件 | 说明 |')
    A('|---|---|')
    A('| laya_ml_s256_embeds_npu.tflite | 主图(MTK NPU AOT 编译,apply_plugin) |')
    A('| laya_ml_s256_embeds_wfp16.tflite | 主图(GPU/CPU fp16 通用) |')
    A('| laya_ml_act_head_fp32.tflite | act 头图:pooled_cls[1,768]+feats[1,4]→act_logits[1,2] |')
    A('| laya_ml_scorer.bin | 选项打分参数(与主图配合) |')
    A('| token_embeddings_fp16.bin | 词嵌入 fp16(256000×768,row-major) |')
    A('| token_embeddings.json | 嵌入元数据(含 sha256 校验) |')
    A('| tokenizer.json | 分词器(HuggingFace tokenizer 格式) |')
    A('| laya_ml_calibration.json | 输出温度校准(按题组选项) |')
    A('| questions.json | 题组定义(state_key + questions) |')
    A('| label.txt | 一行业务描述 |')
    A('| README.md | 本说明 |')
    A('')
    A('## 部署(手机管家)')
    A('1. 下载:`bash ~/dl-laya-model.sh ' + biz + '` → /sdcard/models/laya-litert-' + biz + '/phone/')
    A('2. 管家 设置→AI→模型目录(默认 /sdcard/models/laya-litert-' + biz + '/phone)→「检测」→「加载引擎」。')
    A('3. 引擎就绪后对应业务页「AI 分诊」可用;NPU 不可用时自动回退 wfp16 主图。')
    A('')
    A('## 手机管家四模型清单(本仓库任务目录)')
    A('| 目录 | 业务 | 标签 | 手机下载命令 |')
    A('|---|---|---|---|')
    A('| `sysalert/phone/` | 系统资源告警分诊 | 6 类:正常波动/内存压力/存储紧张/发热降频/耗电异常/可疑占用 | `bash ~/dl-laya-model.sh sysalert` |')
    A('| `voice/phone/` | 语音用量分诊与推荐 | 5 类:正常使用/用量预警/超量风险/套餐咨询/加购推荐 | `bash ~/dl-laya-model.sh voice` |')
    A('| `sms/phone/` | 垃圾/骚扰/诈骗短信分诊 | 4 类:正常/垃圾广告/骚扰推销/诈骗话术 | `bash ~/dl-laya-model.sh sms` |')
    A('| `traffic/phone/` | 流量用量分诊与推荐 | 5 类:正常使用/用量预警/超量风险/套餐咨询/加购推荐 | `bash ~/dl-laya-model.sh traffic` |')
    A('')
    A('历史任务目录(cancer/news/news2/peritoneal/risk/sec/sec2/ticket2/sms2 等)为旧版包,保持可用;新业务以本表为准。')
    A('')
    A('## 下载链接目录')
    A('- **直链模板(浏览器/HTTP)**:`https://modelscope.cn/models/supermy/myjev/resolve/master/<路径>`')
    A('  例:`.../resolve/master/' + biz + '/phone/laya_ml_s256_embeds_npu.tflite`')
    A('- **API 模板(程序下载,dl-laya-model.sh 同款)**:`https://modelscope.cn/api/v1/models/supermy/myjev/repo?Revision=master&FilePath=<路径>`')
    A('  例:`.../FilePath=' + biz + '/phone/questions.json`')
    A('- **目录树**:`<biz>/{phone,base,npu,gpu,cpu}/` — phone=标准包;base=小文件;npu=NPU 主图+scorer;gpu=wfp16 主图;cpu=int8 ONNX(ORT 服务)')
    A('- **一键脚本**:手机 Termux 里 `bash ~/dl-laya-model.sh <biz>`(自动补齐到 /sdcard/models/laya-litert-<biz>/phone,断点续传)')
    A('- **下载校验**:忽略失败重跑同一命令即可;`token_embeddings.json` 内含嵌入文件 sha256')
    A('')
    A('## 变体目录(同仓库)')
    A('- `' + biz + '/phone/` 手机标准包(本目录)')
    A('- `' + biz + '/base/` 仅小文件(不含主图)')
    A('- `' + biz + '/npu/` 仅 NPU 主图 + scorer')
    A('- `' + biz + '/gpu/` 仅 wfp16 主图')
    if os.path.isdir(f'{DATA}/phone-{biz}-cpu'):
        A('- `' + biz + '/cpu/` int8 ONNX(ORT 服务端)')
    A('')
    A('## 校准与溯源')
    A('```json')
    A(json.dumps(cal.get('provenance', {}), ensure_ascii=False, indent=2))
    A('```')
    A('- 温度:' + json.dumps(cal.get('temperature', []), ensure_ascii=False))
    evalp = f'{ft}/eval.txt'
    if os.path.isfile(evalp):
        A('')
        A('## 评测摘要')
        A('```')
        with open(evalp) as f:
            A(f.read().strip()[-800:])
        A('```')
    open(f'{pkg}/README.md', 'w').write('\n'.join(lines) + '\n')

def assemble():
    os.makedirs(PKG, exist_ok=True)
    shutil.copy(find_aot(), f'{PKG}/laya_ml_s256_embeds_npu.tflite')
    shutil.copy(f'{NEG}/laya_{BIZ}_scorer.bin', f'{PKG}/laya_ml_scorer.bin')
    shutil.copy(f'{CONV}/laya_{BIZ}_s256_embeds_wfp16.tflite', f'{PKG}/laya_ml_s256_embeds_wfp16.tflite')
    shutil.copy(f'{CONV}/laya_{BIZ}_act_head_fp32.tflite', f'{PKG}/laya_ml_act_head_fp32.tflite')
    shutil.copy(f'{CONV}/token_embeddings_fp16.bin', f'{PKG}/token_embeddings_fp16.bin')
    meta = {'shape': [256000, 768], 'dtype': 'float16', 'byte_order': 'little', 'layout': 'row-major',
            'filename': 'token_embeddings_fp16.bin', 'size_bytes': os.path.getsize(f'{PKG}/token_embeddings_fp16.bin'),
            'sha256': sha256(f'{PKG}/token_embeddings_fp16.bin'), 'source_repository': 'finetuned-laya', 'source_task': BIZ}
    json.dump(meta, open(f'{PKG}/token_embeddings.json', 'w'), indent=1)
    cfg = json.load(open(f'{FT}/rl_agent_config.json'))
    cal = {'temperature': cfg.get('temperature', [1, 1, 1]), 'temperature_by_options': cfg.get('temperature_by_options', {}),
           'provenance': {'model': f'laya-{BIZ}-ft', 'task': BIZ, 'max_len': 256, 'head_max_len': 256}}
    json.dump(cal, open(f'{PKG}/laya_ml_calibration.json', 'w'), ensure_ascii=False, indent=2)
    task = json.load(open(f'/data/ai/finetune/task_{BIZ}.json'))  # 新格式 {state_key, questions}
    json.dump(task, open(f'{PKG}/questions.json', 'w'), ensure_ascii=False, indent=1)
    shutil.copy(f'{FT}/tokenizer/tokenizer.json', f'{PKG}/tokenizer.json')
    open(f'{PKG}/label.txt', 'w').write(args.label)
    if os.path.isdir(INT8):
        os.makedirs(f'{DATA}/phone-{BIZ}-cpu', exist_ok=True)
        for f in os.listdir(INT8):
            src = os.path.join(INT8, f)
            if os.path.isfile(src):  # 跳过 tokenizer/ 子目录
                shutil.copy(src, os.path.join(f'{DATA}/phone-{BIZ}-cpu', f))
    print('assembled:', sorted(os.listdir(PKG)))

def upload():
    api = HubApi(); api.login(TOKEN)
    api.upload_folder(repo_id='supermy/myjev', folder_path=PKG, path_in_repo=f'{BIZ}/phone',
                      commit_message=f'{BIZ} phone package')
    base_files = {'laya_ml_act_head_fp32.tflite', 'token_embeddings_fp16.bin', 'token_embeddings.json',
                  'laya_ml_calibration.json', 'tokenizer.json', 'questions.json', 'label.txt'}
    npu_files = {'laya_ml_s256_embeds_npu.tflite', 'laya_ml_scorer.bin'}
    gpu_files = {'laya_ml_s256_embeds_wfp16.tflite'}
    vdir = f'{DATA}/myjev-variants/{BIZ}'
    for sub, want in (('base', base_files), ('npu', npu_files), ('gpu', gpu_files)):
        os.makedirs(f'{vdir}/{sub}', exist_ok=True)
        for f in os.listdir(PKG):
            if f in want:
                shutil.copy(os.path.join(PKG, f), os.path.join(f'{vdir}/{sub}', f))
        api.upload_folder(repo_id='supermy/myjev', folder_path=f'{vdir}/{sub}', path_in_repo=f'{BIZ}/{sub}',
                          commit_message=f'{BIZ} {sub} variant')
    if os.path.isdir(f'{DATA}/phone-{BIZ}-cpu'):
        api.upload_folder(repo_id='supermy/myjev', folder_path=f'{DATA}/phone-{BIZ}-cpu', path_in_repo=f'{BIZ}/cpu',
                          commit_message=f'{BIZ} int8 ONNX (ORT service)')
    print(f'{BIZ}-MYJEV-DONE', flush=True)

if __name__ == '__main__':
    for p in (CONV, NEG, FT):
        need(p)
    make_readme(PKG, BIZ, args.label)
    assemble()
    upload()
