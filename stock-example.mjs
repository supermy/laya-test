// 股票资讯判别示例 — 用 multi checkpoint(mmBERT,中文)零样本跑三问
// ⚠️ 零样本跨域有限(实测 ~40%),这是管线/问法示例,生产级准确率需用自有标注数据微调
import { LayaNative } from "./laya-native.mjs";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
const laya = await LayaNative.loadWithDaemon({
  modelDir: path.join(HERE, "laya-multilingual-int8"),
  modelPath: "/sdcard/models/laya-multilingual-int8/laya-multilingual.int8.onnx",
  socketPath: path.join(HERE, "laya-multi.sock"),
  threads: 6,
  env: {
    ...process.env,
    LD_LIBRARY_PATH: "/data/data/com.termux/files/usr/lib/python3.12/site-packages/onnxruntime/capi:" + HERE + "/pylib:" + (process.env.PREFIX ?? "") + "/lib",
  },
});
laya.config.batch1 = true; // multi int8 ONNX 是 batch1-only

// 与 APK(MainActivity stock 按钮)完全一致的问题定义, state 同为 {"news": text}
const questions = {
  q0: { type: "choice", instructions: "这条新闻的倾向", criteria: ["利好", "利空", "中性"] },
  q1: { type: "score", instructions: "该新闻对股价影响的重要性1-5", criteria: ["无关紧要", "轻微", "一般", "较大", "重大"] },
  q2: { type: "noul", instructions: "是否包含重大风险警示(如立案调查、减持、业绩爆雷)" },
};

const news = [
  "某公司公告:获得国家科技进步一等奖,并中标15亿元智慧城市大单。",
  "某公司公告:因涉嫌信息披露违法违规,证监会决定对公司立案调查。",
  "央行宣布下调存款准备金率0.5个百分点,释放长期流动性约1万亿元。",
  "某公司上半年净利润同比增长12%,基本符合市场预期。",
];

console.log("=== 股票资讯判别(laya multi 零样本) ===\n");
for (const n of news) {
  const t0 = performance.now();
  const r = await laya.systemOne({ news: n }, questions);
  const ms = Math.round(performance.now() - t0);
  const a = r.answers;
  const probs = Object.entries(a.q0.probabilities).map(([k, v]) => `${k}:${v}`).join(" ");
  const imp = Math.round(a.q1.score);
  const lvl = a.q1.probabilities;
  console.log(`新闻: ${n.slice(0, 30)}…`);
  console.log(`  倾向(choice) : ${a.q0.choice}  [${probs}]`);
  console.log(`  重要性(score): ${imp}/5  ${a.q1.legend[imp] ?? ""}  p5=${lvl["4"]}`);
  console.log(`  风险(noul)   : ${a.q2.noul >= 0.5 ? "⚠️ 有重大风险警示" : "无"} (p=${a.q2.noul})`);
  console.log(`  延迟: ${ms}ms (batch1×3问)\n`);
}
process.exit(0);
