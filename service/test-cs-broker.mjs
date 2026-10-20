// 客服采集真机验证 broker:aedes 127.0.0.1:18830 + 记录 laya/cs/resp 回复到 stdout/文件
import { Aedes } from "aedes";
import net from "net";
import fs from "fs";

const PORT = 18830;
const aedes = await Aedes.createBroker();
const server = net.createServer(aedes.handle);
const replies = [];
server.listen(PORT, "127.0.0.1", () => console.log("broker up :" + PORT));
aedes.subscribe("laya/cs/resp", (pkt, cb) => {
  const s = pkt.payload.toString();
  replies.push(s);
  fs.writeFileSync("/data/data/com.termux/files/home/laya-test/cs-resp.jsonl", replies.join("\n"));
  console.log("RESP:", s.slice(0, 300));
  cb();
});
process.on("SIGINT", () => { server.close(); aedes.close(); process.exit(0); });
