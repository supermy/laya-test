package com.selfhost.layatest;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.View;

/**
 * 系统页示意图(零依赖 Canvas 自绘)。文字全部来自 strings 资源(随语言切换),颜色经 Ui 主题(随深浅色切换)。
 * mode 0 = 架构图(分层决策:云端微调 + LLM 升级通道);
 * mode 1/2 = 数据流泳道(横带 = 参与方,一次决策请求自上而下;两 mode 同布局);
 * mode 3 = 业务流程图(决策分流 → 本端处理/交付 LLM → 日志 → 微调闭环)。
 */
public class DiagramView extends View {
  private final int mode;
  private static final int PRIMARY = 0xFF3E7BFA;
  private static final int GREEN_LINE = 0xFF2A9D8F;
  private static final int ORANGE_LINE = 0xFFD97706;

  // ---- 数据流泳道(fig5 同构):横带 = 参与方,箭头 = 一次决策请求的路径 ----
  private static final float[][] SW_BANDS = {{4, 58}, {62, 222}, {226, 316}, {320, 384}, {388, 454}};
  private static final int[] SW_BAND_BG_L = {0xFFF3F6FB, 0xFFF0F7F2, 0xFFF7F4FC, 0xFFFCF6EC, 0xFFFBF0F4};
  private static final int[] SW_BAND_BG_D = {0xFF232A35, 0xFF22332C, 0xFF2B2738, 0xFF38301F, 0xFF38252D};

  public DiagramView(Context c, int mode) {
    super(c);
    this.mode = mode;
  }

  /** 主题取色:浅色/深色 */
  private int th(int light, int dark) { return Ui.dark(getContext()) ? dark : light; }
  private String s(int id) { return getContext().getString(id); }
  private String[] sl(int id) { return s(id).split("\\n"); }

  @Override
  protected void onMeasure(int wSpec, int hSpec) {
    int w = MeasureSpec.getSize(wSpec);
    float scale = w / 360f;
    int logicalH = mode == 0 ? 508 : mode == 1 || mode == 2 ? 472 : mode == 3 ? 415 : 575;
    setMeasuredDimension(w, (int) (logicalH * scale) + 1);
  }

  @Override
  protected void onDraw(Canvas c) {
    float scale = getWidth() / 360f;
    c.save();
    c.scale(scale, scale);
    c.drawColor(th(Color.WHITE, 0xFF1E232B)); // 图纸底随主题
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

  private void box(Canvas c, float x, float y, float w, float h, int titleId, int blockId, int fill) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(fill);
    c.drawRoundRect(new RectF(x, y, x + w, y + h), 8, 8, p);
    p.setStyle(Paint.Style.STROKE);
    p.setStrokeWidth(1.5f);
    p.setColor(th(0xFF8894A6, 0xFF6B7684));
    c.drawRoundRect(new RectF(x, y, x + w, y + h), 8, 8, p);
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setColor(th(0xFF1A2B4C, 0xFFCFD8E6));
    t.setTextAlign(Paint.Align.CENTER);
    t.setFakeBoldText(true);
    t.setTextSize(12);
    String block = s(blockId);
    int nl = block.indexOf('\n');
    String title = s(titleId);
    drawFitted(c, title, x + w / 2, y + 17, w - 8, t);
    t.setFakeBoldText(false);
    t.setTextSize(10);
    t.setColor(th(0xFF444A55, 0xFFB8C0CC));
    if (nl >= 0) {
      String[] lines = block.split("\\n");
      for (int i = 0; i < lines.length; i++)
        drawFitted(c, lines[i], x + w / 2, y + 33 + i * 13, w - 8, t);
    }
  }

  private void arrow(Canvas c, float x1, float y1, float x2, float y2, String label) {
    arrow(c, x1, y1, x2, y2, label, 0.5f);
  }

  /** t = 标签沿线位置(0 起点 1 终点) */
  private void arrow(Canvas c, float x1, float y1, float x2, float y2, String label, float t) {
    arrow(c, x1, y1, x2, y2, label, t, PRIMARY);
  }

  private void arrow(Canvas c, float x1, float y1, float x2, float y2, String label, float t, int color) {
    arrowLine(c, x1, y1, x2, y2, color);
    if (label != null) arrowLabel(c, x1 + (x2 - x1) * t, y1 + (y2 - y1) * t - 4, label, color);
  }

  private void arrowLine(Canvas c, float x1, float y1, float x2, float y2) {
    arrowLine(c, x1, y1, x2, y2, PRIMARY);
  }

  private void arrowLine(Canvas c, float x1, float y1, float x2, float y2, int color) {
    Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
    p.setColor(color);
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
    arrowLabel(c, lx, ly, label, PRIMARY);
  }

  private void arrowLabel(Canvas c, float lx, float ly, String label, int color) {
    Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
    t.setTextSize(9);
    t.setColor(color);
    t.setTextAlign(Paint.Align.CENTER);
    float tw = t.measureText(label);
    Paint bg = new Paint(Paint.ANTI_ALIAS_FLAG);
    bg.setColor(th(Color.WHITE, 0xFF2A2F38));
    c.drawRoundRect(new RectF(lx - tw / 2 - 3, ly - 10, lx + tw / 2 + 3, ly + 3), 3, 3, bg);
    c.drawText(label, lx, ly, t);
  }

  /** 架构图:云端(微调 + LLM 升级)→ 渠道 → 接入 → 决策核心(分流)→ 推理 → 模型/日志 */
  private void drawArch(Canvas c) {
    box(c, 10, 10, 162, 74, R.string.diag_arch_tune_t, R.string.diag_arch_tune, th(0xFFEAF1FF, 0xFF1C2534));
    box(c, 188, 10, 162, 74, R.string.diag_arch_llm_t, R.string.diag_arch_llm, th(0xFFFFF3E0, 0xFF3A2E1C));
    arrow(c, 91, 84, 91, 104, s(R.string.diag_a_push));
    arrow(c, 269, 104, 269, 84, s(R.string.diag_a_escalate));
    box(c, 10, 106, 340, 46, R.string.diag_arch_channel_t, R.string.diag_arch_channel, th(0xFFEAF1FF, 0xFF1C2534));
    arrow(c, 180, 152, 180, 170, s(R.string.diag_a_intent));
    box(c, 10, 172, 340, 54, R.string.diag_arch_gw_t, R.string.diag_arch_gw, th(0xFFEAF1FF, 0xFF1C2534));
    arrow(c, 180, 226, 180, 244, null);
    box(c, 10, 246, 340, 68, R.string.diag_arch_core_t, R.string.diag_arch_core, th(0xFFE9F7EE, 0xFF1D3A2E));
    arrow(c, 180, 314, 180, 332, null);
    box(c, 10, 334, 340, 68, R.string.diag_arch_infer_t, R.string.diag_arch_infer, th(0xFFFFF3E0, 0xFF3A2E1C));
    box(c, 10, 420, 162, 54, R.string.diag_arch_model_t, R.string.diag_arch_model, th(0xFFF5F0FF, 0xFF2A2440));
    box(c, 188, 420, 162, 54, R.string.diag_arch_out_t, R.string.diag_arch_out, th(0xFFF5F0FF, 0xFF2A2440));
    arrow(c, 120, 402, 91, 420, s(R.string.diag_a_load));
    arrow(c, 240, 402, 269, 420, s(R.string.diag_a_log));
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(10);
    foot.setColor(th(0xFF8894A6, 0xFF8A93A0));
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText(s(R.string.diag_arch_foot), 180, 492, foot);
  }

  /** 业务流程图(与 fig4_flow 同构):输入 → 决策分流 → 处理 → 日志回流 → 微调闭环 */
  private void drawFlow(Canvas c) {
    box(c, 105, 10, 150, 44, R.string.diag_flow_input_t, R.string.diag_flow_input, th(0xFFEAF1FF, 0xFF1C2534));
    arrow(c, 180, 54, 180, 72, null);
    box(c, 70, 74, 220, 68, R.string.diag_flow_laya_t, R.string.diag_flow_laya, th(0xFFEAF1FF, 0xFF1C2534));
    arrow(c, 140, 142, 90, 162, null);
    arrow(c, 220, 142, 270, 162, null);
    box(c, 10, 164, 160, 68, R.string.diag_flow_local_t, R.string.diag_flow_local, th(0xFFE9F7EE, 0xFF1D3A2E));
    box(c, 190, 164, 160, 68, R.string.diag_flow_llm_t, R.string.diag_flow_llm, th(0xFFFFF3E0, 0xFF3A2E1C));
    arrow(c, 90, 232, 140, 252, null);
    arrow(c, 270, 232, 220, 252, null);
    box(c, 70, 254, 220, 48, R.string.diag_flow_result_t, R.string.diag_flow_result, th(0xFFF7F8FA, 0xFF232833));
    arrow(c, 180, 302, 180, 320, null);
    box(c, 10, 322, 340, 60, R.string.diag_flow_pipe_t, R.string.diag_flow_pipe, th(0xFFEAF1FF, 0xFF1C2534));
    // 微调 → 决策模型 回路(右侧上行)
    Paint loop = new Paint(Paint.ANTI_ALIAS_FLAG);
    loop.setColor(GREEN_LINE);
    loop.setStyle(Paint.Style.STROKE);
    loop.setStrokeWidth(1.6f);
    c.drawLine(350, 352, 355, 352, loop);
    c.drawLine(355, 352, 355, 108, loop);
    c.drawLine(355, 108, 294, 108, loop);
    c.drawLine(294, 108, 300, 104, loop);
    c.drawLine(294, 108, 300, 112, loop);
    Paint lt = new Paint(Paint.ANTI_ALIAS_FLAG);
    lt.setTextSize(9);
    lt.setColor(GREEN_LINE);
    lt.setTextAlign(Paint.Align.CENTER);
    c.drawText(s(R.string.diag_a_push), 322, 62, lt);
    c.drawText(s(R.string.diag_flow_newbiz), 322, 74, lt);
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(10);
    foot.setColor(th(0xFF8894A6, 0xFF8A93A0));
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText(s(R.string.diag_flow_foot), 180, 400, foot);
  }

  /** 数据流泳道(fig5 同构):横带 = 参与方,自上而下画一次决策请求的端到端路径。
   *  蓝箭头 = 主流程;绿 = 张量数据(hidden/logits);橙 = 决策后升级 LLM(39.1%)。 */
  private void drawSwim(Canvas c, boolean vertical) {
    String[] lanes = sl(R.string.diag_sw_lanes);
    int[] bandBg = {th(0xFFF3F6FB, 0xFF232A35), th(0xFFF0F7F2, 0xFF22332C), th(0xFFF7F4FC, 0xFF2B2738), th(0xFFFCF6EC, 0xFF38301F), th(0xFFFBF0F4, 0xFF38252D)};
    for (int i = 0; i < SW_BANDS.length; i++) {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(bandBg[i]);
      c.drawRoundRect(new RectF(4, SW_BANDS[i][0], 356, SW_BANDS[i][1]), 6, 6, p);
      Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
      t.setColor(th(0xFF1A2B4C, 0xFFCFD8E6));
      t.setFakeBoldText(true);
      t.setTextSize(9);
      c.drawText(lanes[i], 8, SW_BANDS[i][0] + 11, t);
    }
    box(c, 130, 8, 120, 44, R.string.diag_sw_input_t, R.string.diag_sw_input, th(0xFFEAF1FF, 0xFF1C2534));
    box(c, 80, 76, 100, 44, R.string.diag_sw_tok_t, R.string.diag_sw_tok, th(Color.WHITE, 0xFF262B33));
    box(c, 200, 76, 145, 44, R.string.diag_sw_chain_t, R.string.diag_sw_chain, th(Color.WHITE, 0xFF262B33));
    box(c, 74, 166, 136, 54, R.string.diag_sw_cal_t, R.string.diag_sw_cal, th(0xFFE9F7EE, 0xFF1D3A2E));
    box(c, 112, 244, 122, 44, R.string.diag_sw_main_t, R.string.diag_sw_main, th(Color.WHITE, 0xFF262B33));
    box(c, 244, 244, 108, 44, R.string.diag_sw_scorer_t, R.string.diag_sw_scorer, th(Color.WHITE, 0xFF262B33));
    box(c, 118, 336, 174, 44, R.string.diag_sw_acc_t, R.string.diag_sw_acc, th(Color.WHITE, 0xFF262B33));
    box(c, 86, 406, 206, 44, R.string.diag_sw_llm_t, R.string.diag_sw_llm, th(0xFFFFF3E0, 0xFF3A2E1C));
    arrow(c, 180, 54, 135, 74, null);
    arrow(c, 180, 98, 198, 98, null);
    arrowLine(c, 272, 122, 272, 142, PRIMARY);
    arrowLine(c, 272, 142, 218, 142, PRIMARY);
    arrowLine(c, 218, 142, 218, 242, PRIMARY);
    arrowLabel(c, 245, 136, s(R.string.diag_a_dispatch));
    arrow(c, 140, 290, 148, 334, null);
    arrow(c, 260, 334, 292, 290, s(R.string.diag_sw_hidden), 0.5f, GREEN_LINE);
    arrow(c, 258, 242, 196, 218, s(R.string.diag_sw_logits), 0.55f, GREEN_LINE);
    arrow(c, 86, 222, 86, 404, s(R.string.diag_sw_escalate), 0.45f, ORANGE_LINE);
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(9);
    foot.setColor(th(0xFF8894A6, 0xFF8A93A0));
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText(s(R.string.diag_sw_foot), 180, 464, foot);
  }
}
