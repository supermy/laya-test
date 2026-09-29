import { LayaNative } from "./laya-native.mjs";
const laya = await LayaNative.loadWithDaemon({
  modelDir: new URL("./laya-onnx-int8", import.meta.url).pathname,
  modelPath: "/sdcard/models/laya-onnx-int8/model.onnx",
  socketPath: new URL("./laya.sock", import.meta.url).pathname,
  threads: 6,
});
const V6b = { type: "choice", instructions: "What does the user mainly ask for in this ticket? Base the answer on what the user wants to happen next.",
  criteria: {
    refund: "be given money back for a payment that was already made",
    fix: "have a technical problem repaired so they can keep using the product",
    info: "receive information, pricing or a general answer",
  } };
const cases = [
  ["误报1 急需修复→fix", { subject: "登录持续报 500", body: "今天早上开始生产环境登录就一直报 500 错误,重装了也没用,整个团队现在完全无法办公,业务已经中断,急需修复。" }, "fix"],
  ["误报2 尽快处理→fix", { subject: "系统崩了", body: "服务器崩溃了,数据也丢了,需要修复,请尽快处理!" }, "fix"],
  ["误报3 EN求修→fix", { subject: "Fix my billing page crash", body: "The billing page crashes when I open it, please fix it as soon as possible." }, "fix"],
  ["误报4 billing咨询→info", { subject: "Billing page question", body: "How do I update my card on the billing page? I am not changing anything, just want to know where the setting is." }, "info"],
  ["真阳 中文退款→refund", { subject: "退款未到账", body: "我两周前取消了订阅,到现在还没收到退款,再不处理我就要投诉了。" }, "refund"],
  ["真阳 中文退钱→refund", { subject: "把钱退给我", body: "你们多扣了一个月的费用,必须把钱退给我,不然就投诉到消费者协会。" }, "refund"],
  ["真阳 EN退款→refund", { subject: "Refund not received", body: "I cancelled my subscription two weeks ago and still have not received my refund. Please process it back to my card." }, "refund"],
  ["真阳 ES退款→refund", { subject: "Reembolso no recibido", body: "Cancelé mi suscripción hace dos semanas y todavía no he recibido el reembolso. Por favor, devolvedme el dinero." }, "refund"],
  ["真阴 EN咨询→info", { subject: "Question about features", body: "Hi, could you tell me whether the free plan includes export to PDF? No rush, thanks!" }, "info"],
];
let ok = 0;
for (const [name, state, want] of cases) {
  const r = await laya.systemOne(state, { intent: V6b });
  const a = r.answers.intent;
  const pass = a.choice === want;
  ok += pass;
  console.log(`${pass ? "PASS" : "FAIL"} ${name}: ${a.choice}(${a.probabilities[a.choice].toFixed(2)})`);
}
console.log(`\n${ok}/${cases.length}`);
await laya.shutdown();
