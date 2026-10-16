/** 模板覆盖存取单测(JVM):ReplyTemplates.source 注入 → 覆盖生效 / 无覆盖回退内置 / 占位符替换 */
import com.laya.ReplyTemplates

private val store = java.util.concurrent.ConcurrentHashMap<String, String>()

private fun k(task: String, locale: String, kind: String, key: String) = "$task|$locale|$kind|$key"

private fun chk(name: String, ok: Boolean) {
  println((if (ok) "PASS " else "FAIL ") + name)
  if (!ok) throw AssertionError(name)
}

fun main() {
  // 注入内存实现(模拟 Db.templateGet)
  ReplyTemplates.source = ReplyTemplates.TemplateSource { t, loc, kind, key -> store[k(t, loc, kind, key)] }

  // 1. 无覆盖:内置默认
  chk("无覆盖 autoReply 走内置", ReplyTemplates.autoReply("zh", "账单计费", 2, false)
    .contains("您好,感谢来信。") && ReplyTemplates.autoReply("zh", "账单计费", 2, false).contains("您反馈的账单问题已记录"))

  // 2. 覆盖生效(auto_header + auto_open 部门键)
  store[k("ticket", "zh", "auto_header", "")] = "【覆盖开头】您好。"
  store[k("ticket", "zh", "auto_open", "退换货")] = "【覆盖开场】退换货专员对接。"
  val r1 = ReplyTemplates.autoReply("zh", "退换货", 2, false)
  chk("覆盖 auto_header 生效", r1.startsWith("【覆盖开头】您好。"))
  chk("覆盖 auto_open(退换货) 生效", r1.contains("【覆盖开场】退换货专员对接。"))
  chk("覆盖 auto_open(其他部门) 不受影响", ReplyTemplates.autoReply("zh", "账单计费", 2, false).contains("您反馈的账单问题已记录"))

  // 3. en locale 隔离
  chk("en locale 不受 zh 覆盖影响", ReplyTemplates.autoReply("en", "退换货", 2, false)
    .contains("Your return/exchange request has been logged."))

  // 4. 删覆盖 = 恢复默认
  store.remove(k("ticket", "zh", "auto_header", ""))
  chk("删覆盖恢复默认 auto_header", ReplyTemplates.autoReply("zh", "退换货", 2, false).startsWith("您好,感谢来信。"))
  store.remove(k("ticket", "zh", "auto_open", "退换货"))
  chk("删覆盖恢复默认 auto_open", ReplyTemplates.autoReply("zh", "退换货", 2, false).contains("您的退换货申请已记录"))

  // 5. 占位符替换(agent_route {route} / ugc_sev {severity} / generic_conclusion)
  store[k("agent", "zh", "agent_route", "")] = "将交给 {route} 流程处理。"
  chk("agent_route {route} 替换", ReplyTemplates.agentReply("zh", "查知识库", 2, false).contains("将交给 查知识库 流程处理。"))
  store[k("ugc", "zh", "ugc_sev", "high")] = "严重度 {severity} 级,已处置。"
  chk("ugc_sev {severity} 替换", ReplyTemplates.ugcReply("zh", "辱骂攻击", 4, false).contains("严重度 4 级,已处置。"))
  store[k("news", "zh", "generic_conclusion", "")] = "结论:{conclusion}。"
  chk("generic_conclusion {conclusion} 替换", ReplyTemplates.genericReply("zh", "news", "利好", false).contains("结论:利好。"))

  // 6. 未注入 source(置 null)回退内置 —— 单测脚本场景
  ReplyTemplates.source = null
  chk("source=null 回退内置", ReplyTemplates.autoReply("zh", "退换货", 2, false).startsWith("您好,感谢来信。"))

  println("RESULT: TEMPLATE_STORE ALL PASS")
}
