package com.selfhost.layatest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 系统页示意图(零依赖 Canvas 自绘)。
 * mode 0 = 架构图;mode 1 = 泳道图(横向,4 列);mode 2 = 泳道图(竖向转置,4 行)。
 * 泳道图数据驱动:lanes × flows,双布局共用一套数据。
 */
public class DiagramView extends View {
  private final int mode;
  private static final int PRIMARY = 0xFF3E7BFA;

  // ---- 泳道图数据 ----
  private static final String[] LANES = {"发起方", "网关(app)", "DecisionCore", "LiteRT GPU"};
  private static final int[] LANE_BG = {0xFFEAF1FF, 0xFFE9F7EE, 0xFFFFF3E0, 0xFFF5F0FF};
  /** 每条流 = 依序的步骤(lane, 标题, 副行);行4 为日志→报表 */
  private static final Object[][][] FLOWS = {
    { {0, "输入文本", null}, {2, "三问编排", "choice/score/noul"}, {3, "GPU 3问", "17ms冷/470ms热"} },
    { {0, "指令邮件", "laya+业务+文本"}, {1, "IMAP 取件", "解析→决策→SMTP回复"}, {2, "三问编排", null}, {3, "GPU", null} },
    { {0, "laya/req", "{\"task\",\"text\"}"}, {1, "MQTT 网关", "publish laya/resp"}, {2, "三问编排", null}, {3, "GPU", null} },
    { {2, "决策日志", "JSONL"}, {1, "日报/月报/年报", "推送/页面展示"} },
  };

  public DiagramView(Context c, int mode) {
    super(c);
    this.mode = mode;
  }

  @Override
  protected void onMeasure(int wSpec, int hSpec) {
    int w = MeasureSpec.getSize(wSpec);
    float scale = w / 360f;
    int logicalH = mode == 0 ? 470 : mode == 1 ? 430 : 560;
    setMeasuredDimension(w, (int) (logicalH * scale) + 1);
  }

  @Override
  protected void onDraw(Canvas c) {
    float scale = getWidth() / 360f;
    c.save();
    c.scale(scale, scale);
    if (mode == 0) drawArch(c);
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
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(PRIMARY);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.6f);
    c.drawLine(x1, y1, x2, y2, p);
    float ang = (float) Math.atan2(y2 - y1, x2 - x1);
    float hl = 6;
    c.drawLine(x2, y2, x2 - hl * (float) Math.cos(ang - 0.45), y2 - hl * (float) Math.sin(ang - 0.45), p);
    c.drawLine(x2, y2, x2 - hl * (float) Math.cos(ang + 0.45), y2 - hl * (float) Math.sin(ang + 0.45), p);
    if (label != null) {
      Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
      t.setTextSize(9);
      t.setColor(0xFF3E7BFA);
      t.setTextAlign(Paint.Align.CENTER);
      float tw = t.measureText(label);
      float lx = (x1 + x2) / 2, ly = (y1 + y2) / 2 - 4;
      Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
      bg.setColor(Color.WHITE);
      c.drawRoundRect(new RectF(lx - tw / 2 - 3, ly - 10, lx + tw / 2 + 3, ly + 3), 3, 3, bg);
      c.drawText(label, lx, ly, t);
    }
  }

  /** 架构图:渠道 → 接入 → 决策核心 → 推理 → 模型/日志 */
  private void drawArch(Canvas c) {
    c.drawColor(Color.WHITE);
    box(c, 10, 10, 340, 62, "渠道层", new String[]{"决策UI · MQTT客户端 · 邮件指令"}, 0xFFEAF1FF);
    arrow(c, 180, 72, 180, 96, "Intent / MQTT / SMTP-IMAP");
    box(c, 10, 96, 340, 62, "接入层 GatewayService(前台服务)", new String[]{"IMAP轮询→解析→SMTP回复 · 日报", "订阅 laya/req/+ → 发布 laya/resp"}, 0xFFEAF1FF);
    arrow(c, 180, 158, 180, 182, null);
    box(c, 10, 182, 340, 62, "决策核心 DecisionCore", new String[]{"单引擎 · 问题定义 · 决策日志 JSONL"}, 0xFFE9F7EE);
    arrow(c, 180, 244, 180, 268, null);
    box(c, 10, 268, 340, 62, "推理层 LayaNativeEngine", new String[]{"LiteRT C API(JNI) · GPU delegate", "OpenCL · host buffer · 单线程"}, 0xFFFFF3E0);
    box(c, 10, 354, 162, 62, "模型三件套", new String[]{"wfp16 主图", "act头+词表+校准"}, 0xFFF5F0FF);
    box(c, 188, 354, 162, 62, "输出/存储", new String[]{"UI · SMTP · MQTT resp", "JSONL → 报表"}, 0xFFF5F0FF);
    arrow(c, 120, 330, 91, 354, "加载");
    arrow(c, 240, 330, 269, 354, "写日志");
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(10);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("通道:全部端侧闭环,5060 仅训练时使用", 180, 448, foot);
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

  /** 泳道图:横向 = 泳道为列;竖向(转置)= 泳道为行,数据流自上而下 */
  private void drawSwim(Canvas c, boolean vertical) {
    c.drawColor(Color.WHITE);
    int[][][] pos = new int[FLOWS.length][][];
    if (!vertical) {
      float lw = 90;
      for (int l = 0; l < 4; l++) laneHeader(c, 2 + l * lw, 8, lw, LANES[l], LANE_BG[l]);
      for (int f = 0; f < FLOWS.length; f++) {
        pos[f] = new int[FLOWS[f].length][];
        for (int st = 0; st < FLOWS[f].length; st++) {
          int lane = (Integer) FLOWS[f][st][0];
          pos[f][st] = new int[]{(int) (4 + lane * lw + 2), 52 + f * 82};
        }
      }
    } else {
      float bh = 104;
      for (int l = 0; l < 4; l++) laneHeader(c, 2, 8 + l * bh, 356, LANES[l], LANE_BG[l]);
      for (int f = 0; f < FLOWS.length; f++) {
        pos[f] = new int[FLOWS[f].length][];
        for (int st = 0; st < FLOWS[f].length; st++) {
          int lane = (Integer) FLOWS[f][st][0];
          pos[f][st] = new int[]{10 + f * 118, (int) (8 + lane * bh + 40)};
        }
      }
    }
    // 画盒子与箭头
    for (int f = 0; f < FLOWS.length; f++) {
      int n = FLOWS[f].length;
      for (int st = 0; st < n; st++) {
        int lane = (Integer) FLOWS[f][st][0];
        String title = (String) FLOWS[f][st][1];
        String sub = (String) FLOWS[f][st][2];
        int x = pos[f][st][0], y = pos[f][st][1];
        int bw = vertical ? 110 : 82, bh = 54;
        if (f == FLOWS.length - 1) bh = 42;
        box(c, x, y, bw, bh, title, sub != null ? new String[]{sub} : null, LANE_BG[lane]);
        if (st < n - 1) {
          int x2 = pos[f][st + 1][0], y2 = pos[f][st + 1][1];
          boolean logFlow = f == FLOWS.length - 1;
          String lbl = logFlow ? "读取聚合" : (st == 0 ? "decide()" : null);
          if (!vertical) arrow(c, x + bw, y + bh / 2f, x2, y2 + bh / 2f, lbl);
          else arrow(c, x + bw / 2f, y + bh, x2 + bw / 2f, y2, lbl);
        }
      }
    }
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(9);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("三条通道并发到达时由 DecisionCore 串行化(单引擎,任务切换即卸载)", 180, vertical ? 552 : 420, foot);
  }
}
