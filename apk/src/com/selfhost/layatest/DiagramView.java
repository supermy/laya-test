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
 * mode 1/2 = 数据流泳道(横带 = 参与方,一次决策请求自上而下;两 mode 同布局);
 * mode 3 = 业务流程图(决策分流 → 本端处理/交付 LLM → 日志 → 微调闭环)。
 */
public class DiagramView extends View {
  private final int mode;
  private static final int PRIMARY = 0xFF3E7BFA;
  private static final int GREEN_LINE = 0xFF2A9D8F;
  private static final int ORANGE_LINE = 0xFFD97706;
  private static final int GREEN_BG = 0xFFE9F7EE;
  private static final int BLUE_BG = 0xFFEAF1FF;
  private static final int ORANGE_BG = 0xFFFFF3E0;
  private static final int PURPLE_BG = 0xFFF5F0FF;

  // ---- 数据流泳道(fig5 同构):横带 = 参与方,箭头 = 一次决策请求的路径 ----
  private static final String[] SW_LANES = {
    "① 用户 / 业务系统", "② APK 端侧 · DecisionCore", "③ runner 独立进程",
    "④ 加速器(NPU · GPU · CPU)", "⑤ 云端 LLM(决策后处理)"
  };
  private static final float[][] SW_BANDS = {{4, 58}, {62, 222}, {226, 316}, {320, 384}, {388, 454}};
  private static final int[] SW_BAND_BG = {0xFFF3F6FB, 0xFFF0F7F2, 0xFFF7F4FC, 0xFFFCF6EC, 0xFFFBF0F4};

  public DiagramView(Context c, int mode) {
    super(c);
    this.mode = mode;
  }

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
    box(c, 10, 334, 340, 68, "推理层 引擎链自动降级(SoC 探测)", new String[]{
        "NPU:MTK 天玑9500 SoC 探测 → dispatch runner(MDLA)",
        "NPU split:主图 54-59ms/问 + scorer 头 C 实现 <1ms",
        "GPU 兜底:runner 独立进程 → JNI C API(OpenCL)~150ms",
        "CPU 兜底:ORT int8;引擎链 NPU→GPU→CPU 逐级"}, ORANGE_BG);
    box(c, 10, 420, 162, 54, "模型三件套", new String[]{"wfp16 主图 + NPU dispatch", "scorer bin+act头+词表+校准"}, PURPLE_BG);
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
    box(c, 70, 74, 220, 68, "Laya 决策(三问逐次前向)", new String[]{"NPU ~0.08s/问 · GPU ~0.15s/问", "微调后 choice acc 67.4%"}, BLUE_BG);
    arrow(c, 140, 142, 90, 162, null);
    arrow(c, 220, 142, 270, 162, null);
    box(c, 10, 164, 160, 68, "本端自动处理 60.9%", new String[]{"决策后:常规 → 模板回复", "智能路由 · 判别即拦截", "~0.2s · <1J/单"}, GREEN_BG);
    box(c, 190, 164, 160, 68, "LLM 进一步处理 39.1%", new String[]{"决策后:重要+紧急升级", "云端 API / 端侧大模型兜底", "复杂推理 · 生成回复", "~22 TFLOP · ~700J/单"}, ORANGE_BG);
    arrow(c, 90, 232, 140, 252, null);
    arrow(c, 270, 232, 220, 252, null);
    box(c, 70, 254, 220, 48, "处理结果 + 决策日志", new String[]{"数据回流:质量标注→训练语料"}, 0xFFF7F8FA);
    arrow(c, 180, 302, 180, 320, null);
    box(c, 10, 322, 340, 60, "新业务上线流水线(finetune/ + litert-conv)", new String[]{"prepare→RLCD→evaluate→split_negfix", "GPU wfp16 / NPU dispatch+scorer 双格式"}, BLUE_BG);
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

  /** 数据流泳道(fig5 同构):横带 = 参与方,自上而下画一次决策请求的端到端路径。
   *  蓝箭头 = 主流程;绿 = 张量数据(hidden/logits);橙 = 决策后升级 LLM(39.1%)。 */
  private void drawSwim(Canvas c, boolean vertical) {
    c.drawColor(Color.WHITE);
    // 参与方横带 + 左上角标题
    for (int i = 0; i < SW_BANDS.length; i++) {
      Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
      p.setColor(SW_BAND_BG[i]);
      c.drawRoundRect(new RectF(4, SW_BANDS[i][0], 356, SW_BANDS[i][1]), 6, 6, p);
      Paint t = new Paint(Paint.ANTI_ALIAS_FLAG);
      t.setColor(0xFF1A2B4C);
      t.setFakeBoldText(true);
      t.setTextSize(9);
      c.drawText(SW_LANES[i], 8, SW_BANDS[i][0] + 11, t);
    }
    // 步骤盒
    box(c, 130, 8, 120, 44, "业务输入", new String[]{"工单/短信/UGC/风控"}, BLUE_BG);
    box(c, 80, 76, 100, 44, "tokenize", new String[]{"分词/编码"}, 0xFFFFFFFF);
    box(c, 200, 76, 145, 44, "引擎链选择", new String[]{"NPU→GPU→CPU 自动选"}, 0xFFFFFFFF);
    box(c, 74, 166, 136, 54, "温度校准·解码·分流", new String[]{"60.9% 本端自动处理", "39.1% 决策后升级 LLM"}, GREEN_BG);
    box(c, 112, 244, 122, 44, "主图前向", new String[]{"dispatch 54-59ms/问"}, 0xFFFFFFFF);
    box(c, 244, 244, 108, 44, "scorer 头", new String[]{"C 实现 <1ms"}, 0xFFFFFFFF);
    box(c, 118, 336, 174, 44, "加速器", new String[]{"MDLA / OpenCL / ORT int8"}, 0xFFFFFFFF);
    box(c, 86, 406, 206, 44, "云端 LLM", new String[]{"重要+紧急 · 生成回复 · token ↓~60%"}, ORANGE_BG);
    // 主流程(蓝)
    arrow(c, 180, 54, 135, 74, null);
    arrow(c, 180, 98, 198, 98, null);
    // 引擎链 → 主图前向:折线绕开温度校准盒
    arrowLine(c, 272, 122, 272, 142, PRIMARY);
    arrowLine(c, 272, 142, 218, 142, PRIMARY);
    arrowLine(c, 218, 142, 218, 242, PRIMARY);
    arrowLabel(c, 245, 136, "下发推理");
    arrow(c, 140, 290, 148, 334, null);
    // 张量数据(绿):加速器 → scorer(hidden)、scorer → 温度校准(logits)
    arrow(c, 260, 334, 292, 290, "hidden states", 0.5f, GREEN_LINE);
    arrow(c, 258, 242, 196, 218, "logits", 0.55f, GREEN_LINE);
    // 决策后升级(橙):温度校准 → 云端 LLM
    arrow(c, 86, 222, 86, 404, "39.1% 升级", 0.45f, ORANGE_LINE);
    Paint foot = new Paint(Paint.ANTI_ALIAS_FLAG);
    foot.setTextSize(9);
    foot.setColor(0xFF8894A6);
    foot.setTextAlign(Paint.Align.CENTER);
    c.drawText("NPU ~0.23s/3问 · 常规 60.9% 本端处理 · 重要+紧急 39.1% 升级 LLM", 180, 464, foot);
  }
}
