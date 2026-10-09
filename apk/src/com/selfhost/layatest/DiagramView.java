package com.selfhost.layatest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 系统页示意图(零依赖 Canvas 自绘)。
 * mode 0 = 架构图(分层决策:云端微调 + LLM 升级通道);
 * mode 1 = 泳道图(横向,5 泳道);mode 2 = 泳道图(竖向转置,5 行);
 * mode 3 = 业务流程图(决策分流 → 本端处理/交付 LLM → 日志 → 微调闭环)。
 * 泳道图数据驱动:lanes × flows,双布局共用一套数据。
 * 竖向模式箭头标签偏向起点(t=0.32)且泳道标题最后绘制,避免标签遮挡标题文字。
 */
public class DiagramView extends View {
  private final int mode;
  private static final int PRIMARY = 0xFF3E7BFA;
  private static final int GREEN_BG = 0xFFE9F7EE;
  private static final int BLUE_BG = 0xFFEAF1FF;
  private static final int ORANGE_BG = 0xFFFFF3E0;
  private static final int PURPLE_BG = 0xFFF5F0FF;

  // ---- 泳道图数据(5 泳道,LLM 决策后处理独立泳道) ----
  private static final String[] LANES = {"发起方", "网关(app)", "DecisionCore", "LiteRT GPU", "LLM 后处理"};
  private static final int[] LANE_BG = {0xFFEAF1FF, 0xFFE9F7EE, 0xFFFFF3E0, 0xFFF5F0FF, 0xFFFAE8F0};
  /** 每条流 = 依序的步骤(lane, 标题, 副行);行5 为日志→报表。竖向=横向布局放大行距 */
  private static final Object[][][] FLOWS = {
    { {0, "输入文本", null}, {2, "三问编排", "choice/score/noul"}, {3, "GPU 3问", "~0.45s 端到端"} },
    { {0, "指令邮件", "laya+业务+文本"}, {1, "IMAP 取件", "取件→转发决策→SMTP回复"}, {2, "三问编排", null}, {3, "GPU 3问", null} },
    { {0, "laya/req", "{task,text}"}, {1, "MQTT 网关", "publish laya/resp"}, {2, "三问编排", null}, {3, "GPU 3问", null} },
    { {2, "决策分流", "高/中/低·39.1% 升级"}, {4, "重要+紧急", "决策后进一步处理"} },
    { {2, "决策日志", "JSONL"}, {1, "日报/月报/年报+详单", "邮件推送/页面下钻"} },
  };

  public DiagramView(Context c, int mode) {
    super(c);
    this.mode = mode;
  }

  @Override
  protected void onMeasure(int wSpec, int hSpec) {
    int w = MeasureSpec.getSize(wSpec);
    float scale = w / 360f;
    int logicalH = mode == 0 ? 508 : mode == 1 ? 455 : mode == 3 ? 415 : 575;
    setMeasuredDimension(w, (int) (logicalH * scale) + 1);
  }

  @Override
  protected void onDraw(Canvas c) {
    float scale = getWidth() / 360f;
    c.save();
    c.scale(scale, scale);
    if (mode == 0) drawArch(c);
    else if (mode == 3) drawFlow(c);
    else drawSwim(c, mode == 2);
    c.restore();
  }

  /** 文本自适应:超过 maxWidth 时按比例缩小字号,避免压线 */
  private void drawFitted(Canvas c, String text, float centerX, float y, float maxWidth, Paint t) {
    float base = t.getTextSize();
    float w = t.measureText(text);
    if (w > maxWidth) t.setTextSize(Math.max(7f, base * maxWidth / w));
    c.drawText(text, centerX, y, t);
    t.setTextSize(base);
  }

  private void box(Canvas c, float x, float y, float w, float h, String title, String[] lines, int fill) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(fill);
    c.drawRoundRect(new RectF(x, y, x + w, y + h), 8, 8, p);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.5f);
    p.setColor(0xFF8894A6);
    c.drawRoundRect(new RectF(x, y, x + w, y + h), 8, 8, p);
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setColor(0xFF1A2B4C);
    t.setTextAlign(Paint.Align.CENTER);
    t.setFakeBoldText(true);
    t.setTextSize(12);
    drawFitted(c, title, x + w / 2, y + 17, w - 8, t);
    t.setFakeBoldText(false);
    t.setTextSize(10);
    t.setColor(0xFF444A55);
    if (lines != null) for (int i = 0; i < lines.length; i++)
      drawFitted(c, lines[i], x + w / 2, y + 33 + i * 13, w - 8, t);
  }

  private void arrow(Canvas c, float x1, float y1, float x2, float y2, String label) {
    arrow(c, x1, y1, x2, y2, label, 0.5f);
  }

  /** t = 标签沿线位置(0 起点 1 终点) */
  private void arrow(Canvas c, float x1, float y1, float x2, float y2, String label, float t) {
    arrowLine(c, x1, y1, x2, y2);
    if (label != null) arrowLabel(c, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t - 4, label);
  }

  private void arrowLine(Canvas c, float x1, float y1, float x2, float y2) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(PRIMARY);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.6f);
    c.drawLine(x1, y1, x2, y2, p);
    float ang = (float) Math.atan2(y2 - y1, x2 - x1);
    float hl = 6;
    c.drawLine(x2, y2, x2 - hl * (float) Math.cos(ang - 0.45), y2 - hl * (float) Math.sin(ang - 0.45), p);
    c.drawLine(x2, y2, x2 - hl * (float) Math.cos(ang + 0.45), y2 - hl * (float) Math.sin(ang + 0.45), p);
  }

  /** 白底标签单独绘制:泳道图中在标题带之后再画,保证不被切 */
  private void arrowLabel(Canvas c, float lx, float ly, String label) {
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setTextSize(9);
    t.setColor(0xFF3E7BFA);
    t.setTextAlign(Paint.Align.CENTER);
    float tw = t.measureText(label);
    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    bg.setColor(Color.WHITE);
    c.drawRoundRect(new RectF(lx - tw / 2 - 3, ly - 10, lx + tw / 2 + 3, ly + 3), 3, 3, bg);
    c.drawText(label, lx, ly, t);
  }

  /** 架构图:云端(微调 + LLM 升级)→ 渠道 → 接入 → 决策核心(分流)→ 推理 → 模型/日志 */
  private void drawArch(Canvas c) {
    c.drawColor(Color.WHITE);
    // 云端层:微调闭环 + LLM 升级通道
    box(c, 10, 10, 162, 74, "云端微调(5060 GPU)", new String[]{"RLCD→evaluate→export", "int8 / LiteRT 下发"}, BLUE_BG);
    box(c, 188, 10, 162, 74, "云端 LLM(决策后处理)", new String[]{"接手重要+紧急 39.1%", "进一步推理 · 生成回复", "token 支出 ↓~60%"}, ORANGE_BG);
    arrow(c, 91, 84, 91, 104, "模型下发");
    arrow(c, 269, 104, 269, 84, "决策后升级");
    box(c, 10, 106, 340, 46, "渠道层", new String[]{"决策UI · MQTT客户端 · 邮件指令"}, BLUE_BG);
    arrow(c, 180, 152, 180, 170, "Intent / MQTT / SMTP-IMAP");
    box(c, 10, 172, 340, 54, "接入层 GatewayService(前台服务)", new String[]{"IMAP轮询→解析→SMTP回复 · 日报", "订阅 laya/req/+ → 发布 laya/resp"}, BLUE_BG);
    arrow(c, 180, 226, 180, 244, null);
    box(c, 10, 246, 340, 68, "决策核心 DecisionCore(分流)", new String[]{"单引擎 · 三问编排 · 决策日志 JSONL", "常规问题 → 决策后本端自动处理 60.9%", "重要+紧急 → 决策后交 LLM 进一步处理"}, GREEN_BG);
    arrow(c, 180, 314, 180, 332, null);
    box(c, 10, 334, 340, 68, "推理层 引擎自动降级(装机探测)", new String[]{
        "NPU:高通SoC+QNN 库才启用(HTP 进程内)",
        "GPU:runner 独立进程 → JNI C API(OpenCL)",
        "CPU 兜底 · 天玑9500 APU 走 vendor 通道(litertlm 类)"}, ORANGE_BG);
    box(c, 10, 420, 162, 54, "模型三件套", new String[]{"wfp16 主图", "act头+词表+校准"}, PURPLE_BG);
    box(c, 188, 420, 162, 54, "输出/存储", new String[]{"UI · SMTP · MQTT resp", "JSONL → 报表"}, PURPLE_BG);
    arrow(c, 120, 402, 91, 420, "加载");
    arrow(c, 240, 402, 269, 420, "写日志");
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(10);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("决策模型全量筛查;LLM 只算 39.1% → 总算力 ↓57%", 180, 492, foot);
  }

  /** 业务流程图(与 fig4_flow 同构):输入 → 决策分流 → 处理 → 日志回流 → 微调闭环 */
  private void drawFlow(Canvas c) {
    c.drawColor(Color.WHITE);
    box(c, 105, 10, 150, 44, "业务输入", new String[]{"工单/短信/UGC/风控"}, BLUE_BG);
    arrow(c, 180, 54, 180, 72, null);
    box(c, 70, 74, 220, 68, "Laya 决策(单次前向 ~0.17s)", new String[]{"department / urgency / intent", "微调后 choice acc 67.4%"}, BLUE_BG);
    arrow(c, 140, 142, 90, 162, null);
    arrow(c, 220, 142, 270, 162, null);
    box(c, 10, 164, 160, 68, "本端自动处理 60.9%", new String[]{"决策后:常规 → 模板回复", "智能路由 · 判别即拦截", "~0.2s · <1J/单"}, GREEN_BG);
    box(c, 190, 164, 160, 68, "LLM 进一步处理 39.1%", new String[]{"决策后:重要+紧急升级", "云端 API / 端侧大模型兜底", "复杂推理 · 生成回复", "~22 TFLOP · ~700J/单"}, ORANGE_BG);
    arrow(c, 90, 232, 140, 252, null);
    arrow(c, 270, 232, 220, 252, null);
    box(c, 70, 254, 220, 48, "处理结果 + 决策日志", new String[]{"数据回流:质量标注→训练语料"}, 0xFFF7F8FA);
    arrow(c, 180, 302, 180, 320, null);
    box(c, 10, 322, 340, 60, "新业务微调闭环(finetune/ 四脚本)", new String[]{"prepare→RLCD→evaluate→export", "新业务冷启动:合成数据先行"}, BLUE_BG);
    // 微调 → 决策模型 回路(右侧上行)
    Paint loop = new Paint(Paint.ANTI_ALIAS_FLAG);
    loop.setColor(0xFF2A9D8F);
    loop.setStyle(Paint.Style.STROKE);
    loop.setStrokeWidth(1.6f);
    c.drawLine(350, 352, 355, 352, loop);
    c.drawLine(355, 352, 355, 108, loop);
    c.drawLine(355, 108, 294, 108, loop);
    c.drawLine(294, 108, 300, 104, loop);
    c.drawLine(294, 108, 300, 112, loop);
    Paint lt = new Paint(Paint.ANTI_ALIAS_FLAG);
    lt.setTextSize(9);
    lt.setColor(0xFF2A9D8F);
    lt.setTextAlign(Paint.Align.CENTER);
    c.drawText("模型下发", 322, 62, lt);
    c.drawText("新业务上线", 322, 74, lt);
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(10);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("算力账:全 LLM 22 TFLOP/单 → 分层 9.5,↓57%(升级率 39.1%)", 180, 400, foot);
  }

  private void laneHeader(Canvas c, float x, float y, float w, String title, int fill) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(fill);
    c.drawRect(x, y, x + w, y + 32, p);
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setColor(0xFF1A2B4C);
    t.setTextAlign(Paint.Align.CENTER);
    t.setFakeBoldText(true);
    t.setTextSize(11);
    drawFitted(c, title, x + w / 2, y + 20, w - 6, t);
  }

  /** 泳道图:横向 = 泳道为行、步骤自左向右(同泳道多步骤不重叠);
   *  竖向(转置)= 泳道为行、数据流自上而下。
   *  标题带先于标签绘制:带压线、标签压带,文字互不遮挡。 */
  private void drawSwim(Canvas c, boolean vertical) {
    c.drawColor(Color.WHITE);
    int nL = LANES.length, nF = FLOWS.length;
    int[][][] pos = new int[nF][][];
    int bw, bh;
    if (!vertical) {
      // 紧凑变体:同为「流为列」,行距更小(横向与竖向均无同格重叠)
      bw = 62; bh = 50;
      for (int f = 0; f < nF; f++) {
        pos[f] = new int[FLOWS[f].length][];
        for (int st = 0; st < FLOWS[f].length; st++) {
          int lane = (Integer) FLOWS[f][st][0];
          pos[f][st] = new int[]{6 + f * 70, 8 + lane * 76 + 40};
        }
      }
    } else {
      bw = 62; bh = 54;
      float bhRow = 104;
      for (int f = 0; f < nF; f++) {
        pos[f] = new int[FLOWS[f].length][];
        for (int st = 0; st < FLOWS[f].length; st++) {
          int lane = (Integer) FLOWS[f][st][0];
          pos[f][st] = new int[]{6 + f * 70, (int) (8 + lane * bhRow + 40)};
        }
      }
    }
    // 画盒子与箭头;标签先收集,泳道标题带画完后再统一绘制(最后一层,不被切)
    java.util.ArrayList<float[]> lblPos = new java.util.ArrayList<>();
    java.util.ArrayList<String> lblText = new java.util.ArrayList<>();
    for (int f = 0; f < nF; f++) {
      int n = FLOWS[f].length;
      for (int st = 0; st < n; st++) {
        int lane = (Integer) FLOWS[f][st][0];
        String title = (String) FLOWS[f][st][1];
        String sub = (String) FLOWS[f][st][2];
        int x = pos[f][st][0], y = pos[f][st][1];
        box(c, x, y, bw, bh, title, sub != null ? new String[]{sub} : null, LANE_BG[lane]);
        if (st < n - 1) {
          int x2 = pos[f][st + 1][0], y2 = pos[f][st + 1][1];
          boolean logFlow = f == nF - 1;
          String lbl = logFlow ? "读取聚合" : (st == 0 ? "decide()" : null);
          if (lbl == null) {
            if (!vertical) arrowLine(c, x + bw, y + bh / 2f, x2, y2 + bh / 2f);
            else arrowLine(c, x + bw / 2f, y + bh, x2 + bw / 2f, y2);
          } else if (!vertical) {
            arrowLine(c, x + bw, y + bh / 2f, x2, y2 + bh / 2f);
            // 标签放盒子上方空隙,列间无横向空间
            lblPos.add(new float[]{(x + bw + x2) / 2f, Math.min(y, y2) - 8});
            lblText.add(lbl);
          } else {
            arrowLine(c, x + bw / 2f, y + bh, x2 + bw / 2f, y2);
            lblPos.add(new float[]{x + bw / 2f, (y + bh + y2) / 2f - 4});
            lblText.add(lbl);
          }
        }
      }
    }
    // 泳道标题带先于标签绘制:带压线、标签压带,文字互不遮挡
    if (!vertical) {
      for (int l = 0; l < nL; l++) laneHeader(c, 2, 8 + l * 76, 356, LANES[l], LANE_BG[l]);
    } else {
      float bhRow = 104;
      for (int l = 0; l < nL; l++) laneHeader(c, 2, 8 + l * bhRow, 356, LANES[l], LANE_BG[l]);
    }
    for (int i = 0; i < lblText.size(); i++) {
      float[] lp = lblPos.get(i);
      arrowLabel(c, lp[0], lp[1], lblText.get(i));
    }
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(9);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("三条通道并发到达时由 DecisionCore 串行化(单引擎,任务切换即卸载)", 180, vertical ? 556 : 418, foot);
  }
}
