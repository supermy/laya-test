package com.laya

/** UI/网关共用的决策引擎接口(独立进程 runner 与 JNI 双实现) */
interface DecisionEngine : AutoCloseable {
  fun answer(state: Any?, question: Map<String, Any?>, questionId: String = ""): Map<String, Any?>
}
