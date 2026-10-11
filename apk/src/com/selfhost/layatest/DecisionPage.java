package com.selfhost.layatest;

import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONObject;

/** ① 决策页(从 MainActivity 拆出):聊天式决策 / 业务 rail / 历史 / LLM 风险分析展示 */
class DecisionPage {
  private final MainActivity m;
  private LinearLayout msgList;
  private TextView[] stNums;
  private LinearLayout statsRowRef;
  private EditText searchBox;

  private boolean newsMode = false;          // rail 第一项「快讯」选中:内容区显示快讯流
  private Button[] bizChips;                 // 业务 chips(重漆用)
  private Button newsChip;                   // 快讯 chip
  private LinearLayout leftColRef;           // ☰ 显隐目标(两种模式共用)
  private final android.os.Handler stHandler = new android.os.Handler(android.os.Looper.getMainLooper());
  private boolean stQueued = false;
  private TextView curBizLabel;
  private EditText input;
  private boolean busy = false;

  DecisionPage(MainActivity m) { this.m = m; }

  void bot(String t) { bubble(t, false); refreshStatsSoon(); }

  private void bubble(String text, boolean user) {
    if (msgList == null) return;
    TextView tv = new TextView(m);
    tv.setText(text); tv.setTextSize(14);
    tv.setTextColor(user ? Ui.c(m, 0xFF1A2B4C, 0xFFDFF2E4) : Ui.c(m, 0xFF22262E, 0xFFE2E6EC));
    tv.setMaxWidth(Ui.dp(m,280));
    tv.setBackground(Ui.pill(user ? Ui.c(m, m.USER_BG, 0xFF2E5D3A) : Ui.c(m, m.BOT_BG, 0xFF2A3040), Ui.dp(m,8)));
    tv.setPadding(Ui.dp(m,13), Ui.dp(m,9), Ui.dp(m,13), Ui.dp(m,9));
    LinearLayout row = new LinearLayout(m);
    row.setGravity(user ? Gravity.END : Gravity.START);
    row.setPadding(0, Ui.dp(m,4), 0, Ui.dp(m,4));
    row.addView(tv);
    msgList.addView(row);
    m.scroller.post(() -> m.scroller.fullScroll(View.FOCUS_DOWN));
  }

  // ================= ① 决策 =================
  private boolean railHidden = false; // 左栏显隐跨重建保持(网关轮询会触发页面重建)

  void build() {
    // 顶部当前业务提示(业务切换由左侧竖排 tab 完成,标题栏 ☰ 控制左栏显隐,与报表页一致);快讯模式不显示
    if (!newsMode) {
      LinearLayout row = new LinearLayout(m);
      row.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,4));
      row.setGravity(Gravity.CENTER_VERTICAL);
      curBizLabel = new TextView(m);
      curBizLabel.setTextSize(12); curBizLabel.setTextColor(Ui.c(m, 0xFF66707E, 0xFF8A93A0));
      curBizLabel.setPadding(Ui.dp(m,2), 0, 0, 0);
      row.addView(curBizLabel);
      m.body.addView(row);
      paintChips();
    }

    // 左侧 tab 菜单(竖排,可上下滑动):第一项「快讯」+ 业务 chips
    final LinearLayout rail = new LinearLayout(m);
    rail.setOrientation(LinearLayout.VERTICAL);
    rail.setPadding(Ui.dp(m,2), Ui.dp(m,2), Ui.dp(m,2), Ui.dp(m,2));
    bizChips = new Button[m.taskIds.size()];
    newsChip = new Button(m);
    paintNewsChip();
    int visWn = Ui.dp(m,40);
    int visHn = (int) newsChip.getPaint().measureText(newsChip.getText().toString()) + Ui.dp(m,28);
    newsChip.setRotation(90);
    newsChip.setPadding(0, Ui.dp(m,10), 0, Ui.dp(m,10));
    FrameLayout slotN = new FrameLayout(m);
    slotN.addView(newsChip, new FrameLayout.LayoutParams(visHn, visWn, Gravity.CENTER));
    LinearLayout.LayoutParams slpN = new LinearLayout.LayoutParams(visWn, visHn);
    slpN.bottomMargin = Ui.dp(m,6);
    newsChip.setOnClickListener(v -> selectNews());
    rail.addView(slotN, slpN);
    for (int i = 0; i < m.taskIds.size(); i++) {
      final int k = i;
      // 英文单词整词旋转 90°(顺时针,自上而下读);名称用本地化业务名(中文模式中文,英文模式英文)
      Button c = new Button(m);
      String name = m.taskLabels.get(i);
      c.setText(name); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumWidth(0); c.setMinimumHeight(0);
      c.setPadding(0, Ui.dp(m,10), 0, Ui.dp(m,10)); // 旋转后成为左右内边距
      paintBizChip(c, !newsMode && k == m.taskIdx);
      int visW = Ui.dp(m,40);                                  // 旋转后视觉宽 = 按钮自身高
      int visH = (int) c.getPaint().measureText(name) + Ui.dp(m,28); // 旋转后视觉高 = 按钮自身宽
      c.setRotation(90);
      FrameLayout slot = new FrameLayout(m);
      slot.addView(c, new FrameLayout.LayoutParams(visH, visW, Gravity.CENTER));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(visW, visH);
      slp.bottomMargin = Ui.dp(m,6);
      c.setOnClickListener(v -> selectBiz(k));
      bizChips[i] = c; rail.addView(slot, slp);
    }
    final LinearLayout leftCol = new LinearLayout(m);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,0), Ui.dp(m,0));
    ScrollView railScroll = new ScrollView(m);
    railScroll.addView(rail); // 业务 tab 菜单可上下滑动
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(Ui.dp(m,64), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = Ui.dp(m,2);
    leftCol.setLayoutParams(lclp);
    leftCol.setVisibility(railHidden ? View.GONE : View.VISIBLE);

    LinearLayout top = new LinearLayout(m);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.addView(leftCol);
    leftColRef = leftCol;

    LinearLayout chatCol = new LinearLayout(m);
    chatCol.setOrientation(LinearLayout.VERTICAL);
    chatCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    top.addView(chatCol);

    if (newsMode) {
      // 快讯模式:内容区 = NewsPage(状态/统计/过滤/快讯流)
      m.newsPage.buildInto(chatCol);
      m.body.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
      m.menuBtn.setOnClickListener(v -> {
        boolean show = leftColRef.getVisibility() == View.GONE;
        leftColRef.setVisibility(show ? View.VISIBLE : View.GONE);
      });
      return;
    }

    // 统计卡 + 检索框:放入 tab 内容区(chatCol)内部,横跨聊天列
    LinearLayout statsRow = new LinearLayout(m);
    statsRow.setPadding(Ui.dp(m,10), Ui.dp(m,6), Ui.dp(m,10), 0);
    // 数字色取决策页主色系:深蓝/青/琥珀/红(浅底上保证对比度)
    stNums = Ui.statsCards(m, statsRow, new String[]{
        m.getString(R.string.st_total), m.getString(R.string.st_today),
        m.getString(R.string.st_avglat), m.getString(R.string.st_highrate)},
        Ui.dark(m) ? new int[]{0xFF7FA8FF, 0xFF39B8AA, 0xFFE78A00, 0xFFFF6B6B}
            : new int[]{0xFF1A2B4C, 0xFF2A9D8F, 0xFFB06F0A, 0xFFD62828},
        Ui.c(m, 0xFFF7F8FA, 0xFF232833), Ui.c(m, 0xFF66707E, 0xFF8A93A0));
    statsRowRef = statsRow;
    chatCol.addView(statsRow);
    paintStatsBiz(); // 卡片标题随当前业务个性化
    LinearLayout searchRow = new LinearLayout(m);
    searchRow.setPadding(Ui.dp(m,10), Ui.dp(m,6), Ui.dp(m,10), Ui.dp(m,4));
    searchBox = Ui.field(m, searchRow, m.getString(R.string.st_search_hint));
    searchBox.addTextChangedListener(new android.text.TextWatcher() {
      @Override public void beforeTextChanged(CharSequence cs, int a, int b, int cc) {}
      @Override public void onTextChanged(CharSequence cs, int a, int b, int cc) {
        String kw = String.valueOf(cs);
        if (!m.taskIds.isEmpty()) {
          String biz = m.taskIds.get(m.taskIdx);
          m.getSharedPreferences("ui", android.app.Activity.MODE_PRIVATE)
              .edit().putString("search_" + biz, kw).apply(); // 每个 tab 自己的检索词,持久化
        }
        filterBubbles(kw);
      }
      @Override public void afterTextChanged(android.text.Editable e) {}
    });
    chatCol.addView(searchRow);
    refreshStatsSoon();

    msgList = new LinearLayout(m);
    msgList.setOrientation(LinearLayout.VERTICAL);
    msgList.setPadding(Ui.dp(m,12), Ui.dp(m,6), Ui.dp(m,12), Ui.dp(m,6));
    m.scroller = new ScrollView(m);
    m.scroller.addView(msgList);
    chatCol.addView(m.scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    loadHistory(m.taskIds.get(m.taskIdx));


    LinearLayout bottom = new LinearLayout(m);
    bottom.setOrientation(LinearLayout.HORIZONTAL);
    bottom.setGravity(Gravity.CENTER_VERTICAL);
    bottom.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    bottom.setBackgroundColor(Ui.c(m, Color.WHITE, 0xFF1A1E24));
    input = new EditText(m);
    input.setHint(m.getString(R.string.input_hint, m.taskLabels.get(m.taskIdx))); input.setTextSize(14); input.setMaxLines(3);
    input.setTextColor(Ui.c(m, 0xFF22262E, 0xFFE2E6EC)); input.setHintTextColor(Ui.c(m, 0xFF9AA3AD, 0xFF66707E));
    input.setBackground(Ui.pill(Ui.c(m, Color.WHITE, 0xFF2A3040), Ui.dp(m,22)));
    input.setPadding(Ui.dp(m,14), Ui.dp(m,10), Ui.dp(m,14), Ui.dp(m,10));
    bottom.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button send = new Button(m);
    send.setText(m.getString(R.string.btn_decide)); send.setTextColor(Color.WHITE); send.setAllCaps(false);
    send.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,22)));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    slp.leftMargin = Ui.dp(m,8); send.setLayoutParams(slp);
    send.setOnClickListener(v -> sendDecision(input.getText().toString()));
    bottom.addView(send);
    chatCol.addView(bottom);
    m.body.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    // 标题栏 ☰ 切换本页左栏(与报表页一致);状态记入字段,重建后不丢
    m.menuBtn.setOnClickListener(v -> {
      railHidden = leftCol.getVisibility() != View.GONE;
      leftCol.setVisibility(railHidden ? View.GONE : View.VISIBLE);
    });
  }

  // ---- rail 选择:快讯 / 业务 ----
  private void paintNewsChip() {
    if (newsChip == null) return;
    newsChip.setText(m.getString(R.string.tab_news));
    newsChip.setAllCaps(false); newsChip.setTextSize(12);
    newsChip.setMinHeight(0); newsChip.setMinimumWidth(0); newsChip.setMinimumHeight(0);
    newsChip.setTextColor(newsMode ? Color.WHITE : Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
    newsChip.setBackground(Ui.pill(newsMode ? m.PRIMARY : Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,10)));
  }

  private void paintBizChip(Button c, boolean on) {
    c.setTextColor(on ? Color.WHITE : Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
    c.setBackground(Ui.pill(on ? m.PRIMARY : Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,10)));
  }

  private void rebuildDecision() {
    m.body.removeAllViews();
    build();
  }

  void selectNews() {
    if (newsMode) return;
    newsMode = true;
    m.newsPage.setActive(true);
    rebuildDecision();
  }

  void selectBiz(int k) {
    boolean wasNews = newsMode;
    newsMode = false;
    m.taskIdx = k;
    if (wasNews) {
      m.newsPage.setActive(false);
      rebuildDecision();
      return;
    }
    paintChips(); loadHistory(m.taskIds.get(k));
    for (int j = 0; j < bizChips.length; j++) paintBizChip(bizChips[j], j == k);
  }

  /** 统计卡标签随当前业务个性化(第一卡 = 业务名,本地化) */
  private void paintStatsBiz() {
    if (statsRowRef == null || m.taskIds.isEmpty()) return;
    String biz = m.taskLabels.get(m.taskIdx);
    String[] labs = {biz, m.getString(R.string.st_today),
        m.getString(R.string.st_avglat), m.getString(R.string.st_highrate)};
    if (searchBox != null) searchBox.setHint(m.getString(R.string.st_search_hint, biz));
    int i = 0;
    for (int r = 0; r < statsRowRef.getChildCount() && i < 4; r++) {
      View gr = statsRowRef.getChildAt(r);
      if (!(gr instanceof android.view.ViewGroup)) continue;
      android.view.ViewGroup g = (android.view.ViewGroup) gr;
      for (int k = 0; k < g.getChildCount() && i < 4; k++) {
        View card = g.getChildAt(k);
        if (card instanceof android.view.ViewGroup) {
          android.view.ViewGroup cg = (android.view.ViewGroup) card;
          if (cg.getChildCount() >= 2 && cg.getChildAt(1) instanceof TextView) {
            ((TextView) cg.getChildAt(1)).setText(labs[i]);
            i++;
          }
        }
      }
    }
  }

  /** 统计卡刷新(防抖):决策/载入历史后调用 */
  private void refreshStatsSoon() {
    if (stQueued) return;
    stQueued = true;
    stHandler.postDelayed(() -> {
      stQueued = false;
      final android.app.Activity act = m;
      new Thread(() -> {
        final String biz = m.taskIds.get(m.taskIdx); // 统计随当前业务个性化
      final long[] st = com.laya.DecisionCore.appStats(act, biz);
        m.runOnUiThread(() -> {
          if (stNums == null) return;
          stNums[0].setText(String.valueOf(st[0]));
          stNums[1].setText(String.valueOf(st[1]));
          stNums[2].setText((st[0] > 0 ? st[2] / st[0] : 0) + "ms");
          stNums[3].setText((st[0] > 0 ? st[5] * 100 / st[0] : 0) + "%");
        });
      }).start();
    }, 400);
  }

  /** 检索:隐藏不含关键字的气泡(空关键字全显) */
  private void filterBubbles(String kw) {
    if (msgList == null) return;
    String k = kw == null ? "" : kw.trim();
    for (int i = 0; i < msgList.getChildCount(); i++) {
      View v = msgList.getChildAt(i);
      v.setVisibility(bubbleText(v).contains(k) ? View.VISIBLE : View.GONE);
    }
  }

  private String bubbleText(View v) {
    StringBuilder sb = new StringBuilder();
    if (v instanceof android.view.ViewGroup) {
      android.view.ViewGroup vg = (android.view.ViewGroup) v;
      for (int i = 0; i < vg.getChildCount(); i++) {
        View c = vg.getChildAt(i);
        if (c instanceof TextView) sb.append(((TextView) c).getText()).append(' ');
        else if (c instanceof android.view.ViewGroup) sb.append(bubbleText(c));
      }
    }
    return sb.toString();
  }

  /** 业务↔日志联动:切换业务时,聊天区载入该业务的历史决策 */
  void loadHistory(String task) {
    if (msgList == null) return;
    msgList.removeAllViews();
    bot(m.getString(R.string.history_header, m.taskLabels.get(m.taskIds.indexOf(task))));
    java.util.List<org.json.JSONObject> hs = com.laya.DecisionCore.history(m, task, 20);
    if (hs.isEmpty()) bot(m.getString(R.string.history_empty));
    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
    for (org.json.JSONObject h : hs) {
      bubble(h.optString("state"), true);
      String when = df.format(new java.util.Date(h.optLong("ts")));
      bot(fmtAnswers(task, h.optJSONObject("decoded"), (int) h.optLong("latencyMs")) + "\n· " + when);
    }
    // 回显最近一次 LLM 风险分析(升级通道产物)
    org.json.JSONObject llm = com.laya.DecisionCore.lastLlm(m, task);
    if (llm != null) {
      String when = df.format(new java.util.Date(llm.optLong("ts")));
      org.json.JSONObject l = llm.optJSONObject("llm");
      String c = l != null ? l.optString("content") : "";
      if (!c.isEmpty()) bot(m.getString(R.string.last_llm_analysis, when, c));
      else if (llm.has("error")) bot(m.getString(R.string.last_llm_fail, when, llm.optString("error")));
    }
    m.scroller.post(() -> {
      m.scroller.scrollTo(0, m.scroller.getHeight());
      String kw = m.getSharedPreferences("ui", android.app.Activity.MODE_PRIVATE)
          .getString("search_" + task, "");                  // 每个 tab 自己的检索词
      if (!searchBox.getText().toString().equals(kw)) searchBox.setText(kw); // setText 触发过滤
      else filterBubbles(kw);
      paintStatsBiz();
      refreshStatsSoon();
    });
  }

  void paintChips() {
    if (curBizLabel != null) curBizLabel.setText(m.getString(R.string.current_biz_hint, m.taskLabels.get(m.taskIdx)));
    if (input != null) input.setHint(m.getString(R.string.input_hint, m.taskLabels.get(m.taskIdx)));
  }

  void sendDecision(String raw) {
    final String text = raw == null ? "" : raw.trim();
    if (text.isEmpty() || busy) return;
    busy = true;
    input.setText("");
    bubble(text, true);
    bubble(m.getString(R.string.inferring), false);
    final int ti = m.taskIdx;
    new Thread(() -> {
      String reply;
      try {
        com.laya.DecisionCore.Result r = com.laya.DecisionCore.decide(m, m.taskIds.get(ti), text);
        reply = fmtAnswers(m.taskIds.get(ti), r.answers, (int) r.latencyMs) + m.getString(R.string.backend_prefix, com.laya.DecisionCore.currentEngine(m));
      } catch (Throwable e) {
        android.util.Log.e("LayaApp", "decision failed", e);
        reply = m.getString(R.string.infer_failed, e.getClass().getSimpleName(), e.getMessage());
      }
      final String r2 = reply;
      m.runOnUiThread(() -> { bubble(r2, false); busy = false; });
    }).start();
  }

  String fmtAnswers(String task, org.json.JSONObject decoded, int ms) {
    StringBuilder sb = new StringBuilder(m.getString(R.string.decision_result_header, m.taskLabels.get(m.taskIds.indexOf(task)))).append("\n");
    java.util.Iterator<String> it = decoded.keys();
    while (it.hasNext()) {
      org.json.JSONObject a = decoded.optJSONObject(it.next());
      if (a == null) continue;
      String type = a.optString("type");
      if ("choice".equals(type)) sb.append("• ").append(a.optString("choice")).append("\n");
      else if ("score".equals(type)) sb.append("• ").append(m.getString(R.string.label_score)).append(": ").append(String.format("%.2f", a.optDouble("score"))).append("/5\n");
      else if ("noul".equals(type)) sb.append("• ").append(m.getString(R.string.label_verdict)).append(": ").append(a.optDouble("noul") >= 0.5 ? m.getString(R.string.yes) : m.getString(R.string.no)).append("\n");
    }
    sb.append("\n").append(m.getString(R.string.label_latency)).append(": ").append(ms).append("ms");
    return sb.toString();
  }
}
