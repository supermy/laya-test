import { LayaNative } from "./laya-native.mjs";
import path from "node:path";
import { fileURLToPath } from "node:url";
const HERE = path.dirname(fileURLToPath(import.meta.url));
const laya = await LayaNative.loadWithDaemon({
  modelDir: path.join(HERE, "laya-multilingual-int8"),
  modelPath: "/sdcard/models/laya-multilingual-int8/laya-multilingual.int8.onnx",
  socketPath: path.join(HERE, "laya-multi.sock"),
  threads: 6,
  env: { ...process.env, LD_LIBRARY_PATH: "/data/data/com.termux/files/usr/lib/python3.12/site-packages/onnxruntime/capi:" + HERE + "/pylib:" + (process.env.PREFIX ?? "") + "/lib" },
});
laya.config.batch1 = true; // multi int8 ONNX 是 batch1-only
// 覆盖问题定义(微调模型落地后会从 config.question_defs 读,无需传)
const questions = {
  q0: { type: "choice", instructions: "这条短信的类别", criteria: ["正常", "推销广告", "诈骗", "其他骚扰"] },
  q1: { type: "noul", instructions: "这是垃圾或骚扰短信吗" },
};
for (const text of [
  "【中国移动】尊敬的客户,您10月话费发票已开具,可前往营业厅领取。",
  "兼职刷单,日结300-800元,加微信138xxxx立即上岗!",
  "【公安提醒】您涉嫌一起洗钱案件,请立即点击链接配合调查,否则冻结银行账户!",
]) {
  const r = await laya.smsInfer(text, questions);
  const v = r.verdict;
  console.log(`[${v.label}] spam=${v.spamProb} isSpam=${v.isSpam} (${r._inferMs}ms) | ${text.slice(0, 22)}…`);
  if (!v.probabilities || typeof v.spamProb !== "number") { console.error("verdict 形状不对", v); process.exit(1); }
}
// 非 sms 模型无 question_defs,应给出明确报错
try { laya.questionsFromConfig(); console.error("should have thrown"); process.exit(1); }
catch (e) { console.log("expected error OK:", e.message.slice(0, 40) + "…"); }
console.log("E2E PASS");
process.exit(0);
