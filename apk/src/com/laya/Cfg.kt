package com.laya

/** 网关配置(cfg JSON)键常量:Kotlin/Java 两侧统一引用,防止散落字符串漂移。
 *  存储层:prefs(PREF)内一个 "cfg" JSON 文档,结构:
 *  { enabled, email:{enabled,host,user,pass,ssl,port?}, report:{to},
 *    mqtt:{enabled,url}, topics:{sub,pub}, llm:{active,slots:{<id>:{name,base,model?,key?}}},
 *    api:{enabled,port?,key?} } */
object Cfg {
  // 存储层(prefs 内)
  const val DOC = "cfg"                 // 整个配置 JSON 的 prefs 键
  const val LAST_REPORT_DAY = "lastReportDay"

  // 顶层
  const val ENABLED = "enabled"
  const val EMAIL = "email"
  const val REPORT = "report"
  const val MQTT = "mqtt"
  const val TOPICS = "topics"
  const val LLM = "llm"
  const val API = "api"

  // email / mqtt / api 子键
  const val HOST = "host"
  const val USER = "user"
  const val PASS = "pass"
  const val SSL = "ssl"
  const val URL = "url"
  const val SUB = "sub"
  const val PUB = "pub"
  const val PORT = "port"
  const val KEY = "key"
  const val TO = "to"

  // llm 子键
  const val ACTIVE = "active"
  const val NAME = "name"
  const val SLOTS = "slots"
}
