package com.selfhost.layatest;

import android.content.Context;

import org.json.JSONObject;

/** 模型评分 + 分级推送(NewsAutoService 与测试注入共用) */
public final class ModelScoreHelper {
  private ModelScoreHelper() {}

  /** 评分一条快讯:本地模型决策(写决策日志)→ 强信号通知;LLM 配置且重要+紧急 → decide 内部触发升级,LLM 结果经 LlmListener 由服务推送 */
  public static void scoreAndNotify(Context ctx, String task, String source, String text) {
    try {
      com.laya.DecisionCore.Result r = com.laya.DecisionCore.decide(ctx, task, text, true);
      org.json.JSONObject q0 = r.answers.optJSONObject("q0");
      String choice = q0 != null ? q0.optString("choice") : "";
      org.json.JSONObject probs = q0 != null ? q0.optJSONObject("probabilities") : null;
      double conf = probs != null ? probs.optDouble(choice, 0) : 0;
      org.json.JSONObject q1 = r.answers.optJSONObject("q1");
      double score = q1 != null ? q1.optDouble("score", 1) : 1;
      String level = com.laya.DecisionCore.levelOf(r.answers).optString("level");
      // 强信号/信号 → 系统通知;LLM 分析(若触发)经 LlmListener 由服务二次推送
      if ("strong".equals(level) || "signal".equals(level)) {
        String llmHint = "";
        org.json.JSONObject llm = com.laya.DecisionCore.lastLlm(ctx, task);
        if (llm != null && System.currentTimeMillis() - llm.optLong("ts", 0) < 120_000L) {
          org.json.JSONObject l = llm.optJSONObject("llm");
          if (l != null) llmHint = l.optString("content", "");
        }
        com.laya.NewsAutoService.pushAlert(ctx, source, text, choice, level, score,
            llmHint.length() > 200 ? llmHint.substring(0, 200) + "…" : llmHint);
      }
    } catch (Throwable ignored) {}
  }
}
