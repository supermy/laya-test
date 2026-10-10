package com.selfhost.layatest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.laya.LayaEngine;
import com.laya.LayaDecoder;

/**
 * Laya 移动决策助手 — 单 APK 全功能:
 *   ① 决策:内置四业务注册表(ticket/ugc/agent/risk),LiteRT GPU 端侧推理(模型首启自拷贝)
 *   ② 报表:决策日志(JSONL)→ 日报/月报/年报 汇总 + 详单
 *   ③ 网关:邮件/MQTT 配置下发到 Termux 服务(8789)
 *   ④ 系统:服务健康 / 模型清单
 * intent: am start ... --es tab decision|report|gateway|sys --es task ticket --es text "..."
 */
public class MainActivity extends Activity {
  private static final int PRIMARY = 0xFF3E7BFA;
  private static final int USER_BG = 0xFF95EC69;   // 微信绿气泡
  private static final int BOT_BG = 0xFFFFFFFF;     // 白色气泡
  private static final int CHIP_OFF = 0xFFF0F1F5;
  private static final int WX_PAGE_BG = 0xFFF5F5F5; // 页面浅灰底
  private static final int WX_GREEN = 0xFF07C160;   // 微信选中绿
  private static final int REQ_PICK_ZIP = 41;       // SAF 选 zip 返回码

  // ---- 业务注册表(动态:内置四业务 + /sdcard 上传的扩展包,系统页重扫生效) ----
  private final java.util.ArrayList<String> taskIds = new java.util.ArrayList<>();
  private final java.util.ArrayList<String> taskLabels = new java.util.ArrayList<>();

  private void refreshTasks() {
    taskIds.clear(); taskLabels.clear();
    for (kotlin.Pair<String, String> t : com.laya.DecisionCore.scanTasks(this)) {
      taskIds.add(t.getFirst()); taskLabels.add(t.getSecond());
    }
    if (taskIdx >= taskIds.size()) taskIdx = 0;
  }

  private int tab = 0; // 0决策 1报表 2网关 3系统
  private int taskIdx = 0;
  private LinearLayout body;
  private Button menuBtn;
  private LinearLayout msgList;
  private ScrollView scroller;
  private EditText input;
  private TextView[] tabBtns = new TextView[4];
  private boolean busy = false;
  private static final boolean USE_GPU_MAIN = true; // GPU 主图;失败自动降级 CPU

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    final int savedTab = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0); // recreate/切语言后回到原 tab
    refreshTasks();
    buildUi();
    // LLM 升级完成回调:决策页气泡展示风险分析(邮件/MQTT 渠道触发的也在此显示)
    com.laya.DecisionCore.setLlmListener((task, content, err) -> runOnUiThread(() -> {
      if (err != null) bot(getString(R.string.llm_fail_msg, task, err));
      else if (!content.isEmpty()) bot(getString(R.string.llm_analysis_msg, task, content));
    }));
    // 恢复内置网关(仅配置了 enabled 时)
    if (com.laya.Gateway.cfg(this).optBoolean("enabled")) {
      startForegroundService(new Intent(this, com.laya.GatewayService.class));
      com.laya.Gateway.autoStart(this);
    }
    bot(getString(R.string.ready_msg, taskLabels));
    handleIntent(getIntent() != null ? getIntent() : null);
    if ((getIntent() == null || getIntent().getStringExtra("tab") == null) && tab != savedTab) setTab(savedTab);
  }

  // ---- UI 语言(应用内切换):attachBaseContext 包裹目标 locale,选择记忆在 prefs("ui"/"locale") ----
  private TextView langBtn;

  /** 标题栏语言按钮显示当前选择:A=跟随系统 / 中 / EN */
  private void paintLangBtn() {
    if (langBtn == null) return;
    String cur = uiLocale();
    langBtn.setText("en".equals(cur) ? "EN" : "zh".equals(cur) ? "中" : "🌐A");
  }

  private String uiLocale() { return getSharedPreferences("ui", MODE_PRIVATE).getString("locale", "sys"); }

  @Override
  protected void attachBaseContext(android.content.Context base) {
    String sel = base.getSharedPreferences("ui", MODE_PRIVATE).getString("locale", "sys");
    if (!"sys".equals(sel)) {
      java.util.Locale loc = "en".equals(sel) ? java.util.Locale.US : java.util.Locale.SIMPLIFIED_CHINESE;
      android.content.res.Configuration cfg = new android.content.res.Configuration(base.getResources().getConfiguration());
      cfg.setLocale(loc);
      base = base.createConfigurationContext(cfg);
    }
    super.attachBaseContext(base);
  }

  @Override
  protected void onNewIntent(android.content.Intent i) {
    super.onNewIntent(i);
    handleIntent(i);
  }

  private void handleIntent(android.content.Intent i) {
    if (i == null) return;
    String tabS = i.getStringExtra("tab");
    if (tabS != null) setTab("report".equals(tabS) ? 1 : "gateway".equals(tabS) ? 2 : "sys".equals(tabS) ? 3 : 0);
    String task = i.getStringExtra("task");
    if (task != null) for (int k = 0; k < taskIds.size(); k++) if (taskIds.get(k).equals(task)) { taskIdx = k; paintChips(); }
    String text = i.getStringExtra("text");
    if (text != null && !text.isEmpty()) sendDecision(text);
  }

  private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
  private GradientDrawable pill(int c, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(c); g.setCornerRadius(r); return g; }

  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(WX_PAGE_BG);

    // ---- 标题栏(仿微信:浅灰底、居中标题、左侧☰菜单按钮、底部分隔线) ----
    menuBtn = new Button(this);
    menuBtn.setText("☰"); menuBtn.setAllCaps(false); menuBtn.setTextSize(18);
    menuBtn.setPadding(dp(12), dp(2), dp(12), dp(2));
    menuBtn.setMinHeight(0); menuBtn.setMinimumHeight(0);
    menuBtn.setMinWidth(0); menuBtn.setMinimumWidth(0);
    menuBtn.setTextColor(0xFF1A1A1A);
    menuBtn.setBackground(null);
    menuBtn.setLayoutParams(new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.START | Gravity.CENTER_VERTICAL));
    LinearLayout titleBar = new LinearLayout(this);
    titleBar.setOrientation(LinearLayout.VERTICAL);
    titleBar.setBackgroundColor(0xFFEDEDED);
    FrameLayout titleHolder = new FrameLayout(this);
    TextView title = new TextView(this);
    title.setText(getString(R.string.app_title));
    title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
    title.setTextColor(0xFF1A1A1A);
    title.setPadding(0, dp(10), 0, dp(10));
    titleHolder.addView(title);
    titleHolder.addView(menuBtn);
    // 标题右侧语言切换按钮:点击循环 跟随系统→中文→English(与系统页选择器同一记忆键)
    langBtn = new TextView(this);
    langBtn.setTextSize(13); langBtn.setTypeface(Typeface.DEFAULT_BOLD);
    langBtn.setTextColor(0xFF1A2B4C);
    langBtn.setBackground(pill(0xFFE7EAF2, dp(12)));
    langBtn.setPadding(dp(10), dp(5), dp(10), dp(5));
    FrameLayout.LayoutParams llblp = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
    llblp.rightMargin = dp(10); llblp.topMargin = dp(6);
    langBtn.setLayoutParams(llblp);
    paintLangBtn();
    langBtn.setOnClickListener(v -> {
      String cur = uiLocale();
      String next = "sys".equals(cur) ? "zh" : "zh".equals(cur) ? "en" : "sys";
      getSharedPreferences("ui", MODE_PRIVATE).edit().putString("locale", next).apply();
      recreate();
    });
    titleHolder.addView(langBtn);
    titleBar.addView(titleHolder);
    View titleDiv = new View(this);
    titleDiv.setBackgroundColor(0xFFE0E0E0);
    titleBar.addView(titleDiv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    root.addView(titleBar);


    // ---- 内容容器(先于 setTab 创建) ----
    body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);

    // ---- 内容区 ----
    root.addView(body, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    // ---- 底部主 tab(等宽四栏) ----
    LinearLayout bar = new LinearLayout(this);
    bar.setOrientation(LinearLayout.HORIZONTAL);
    bar.setBackgroundColor(Color.WHITE);
    bar.setPadding(dp(4), dp(6), dp(4), dp(10));
    View topDiv = new View(this);
    topDiv.setBackgroundColor(0xFFE5E5E5);
    root.addView(topDiv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1)));
    String[] names = {getString(R.string.tab_decision), getString(R.string.tab_report), getString(R.string.tab_gateway), getString(R.string.tab_system)};
    for (int i = 0; i < 4; i++) {
      final int k = i;
      TextView t = new TextView(this);
      t.setText(names[i]); t.setTextSize(13); t.setGravity(Gravity.CENTER);
      t.setPadding(0, dp(8), 0, dp(8));
      t.setOnClickListener(v -> setTab(k));
      t.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      tabBtns[i] = t; bar.addView(t);
    }
    root.addView(bar);

    setTab(0);
    setContentView(root);
  }

  // SAF 选 zip 回调:拷贝到 cache,回填包路径,业务名按文件名预填
  @Override
  protected void onActivityResult(int req, int res, Intent data) {
    super.onActivityResult(req, res, data);
    if (req != REQ_PICK_ZIP || res != RESULT_OK || data == null || data.getData() == null) return;
    final Uri uri = data.getData();
    String name = null;
    try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
      if (c != null && c.moveToFirst()) {
        int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
        if (i >= 0) name = c.getString(i);
      }
    } catch (Throwable ignored) { }
    if (name == null) name = uri.getLastPathSegment();
    if (name == null) name = "picked.zip";
    final String fname = name;
    upStatus.setText(getString(R.string.picking_zip));
    new Thread(() -> {
      try {
        File dst = new File(getCacheDir(), "picked-upload.zip");
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
          byte[] buf = new byte[256 * 1024];
          int n;
          while ((n = in.read(buf)) > 0) { out.write(buf, 0, n); total += n; }
        }
        final long mb = total / 1048576;
        final String dstPath = dst.getAbsolutePath();
        runOnUiThread(() -> {
          upSrc.setText(dstPath);
          if (upTask.getText().toString().trim().isEmpty()) {
            String guess = fname.replaceFirst("(?i)\\.zip$", "").replaceFirst("^laya-litert-", "");
            if (guess.matches("[a-zA-Z0-9_-]{1,32}")) upTask.setText(guess);
          }
          upStatus.setText(getString(R.string.picked_msg, fname, mb));
        });
      } catch (Throwable e) {
        runOnUiThread(() -> upStatus.setText(getString(R.string.read_failed, e.getMessage())));
      }
    }).start();
  }


  private void refreshGatewayBar() {
    if (gwBarText != null) gwBarText.setText(getString(R.string.gateway_bar, com.laya.Gateway.status(this)));
  }

  private void setTab(int k) {
    tab = k;
    getSharedPreferences("ui", MODE_PRIVATE).edit().putInt("tab", k).apply();
    refreshGatewayBar();
    for (int i = 0; i < 4; i++) {
      boolean on = i == k;
      TextView tb = tabBtns[i];
      tb.setTextColor(on ? WX_GREEN : 0xFF7F7F7F);
      tb.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
      tb.setBackground(pill(on ? 0xFFE3F7EC : Color.TRANSPARENT, dp(18)));
      tb.setPadding(0, on ? dp(9) : dp(9), 0, on ? dp(9) : dp(9));
    }
    body.removeAllViews();
    if (k == 0) buildDecisionTab();
    else if (k == 1) buildReportTab();
    else if (k == 2) buildGatewayTab();
    else buildSysTab();
  }

  // ================= ① 决策 =================
  private TextView curBizLabel;
  private boolean railHidden = false; // 左栏显隐跨重建保持(网关轮询会触发页面重建)

  private void buildDecisionTab() {
    // 顶部当前业务提示(业务切换由左侧竖排 tab 完成,标题栏 ☰ 控制左栏显隐,与报表页一致)
    LinearLayout row = new LinearLayout(this);
    row.setPadding(dp(12), dp(8), dp(12), dp(4));
    row.setGravity(Gravity.CENTER_VERTICAL);
    curBizLabel = new TextView(this);
    curBizLabel.setTextSize(12); curBizLabel.setTextColor(0xFF66707E);
    curBizLabel.setPadding(dp(2), 0, 0, 0);
    row.addView(curBizLabel);
    body.addView(row);
    paintChips();

    // 左侧业务 tab 菜单(竖排,可上下滑动):点 chip 切业务并载入该业务历史
    final LinearLayout rail = new LinearLayout(this);
    rail.setOrientation(LinearLayout.VERTICAL);
    rail.setPadding(dp(2), dp(2), dp(2), dp(2));
    final Button[] bizChips = new Button[taskIds.size()];
    for (int i = 0; i < taskIds.size(); i++) {
      final int k = i;
      // 英文单词整词旋转 90°(顺时针,自上而下读)
      Button c = new Button(this);
      String name = taskIds.get(i);
      c.setText(name); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumWidth(0); c.setMinimumHeight(0);
      c.setPadding(0, dp(10), 0, dp(10)); // 旋转后成为左右内边距
      c.setTextColor(k == taskIdx ? Color.WHITE : 0xFF1A2B4C);
      c.setBackground(pill(k == taskIdx ? PRIMARY : 0xFFE7EAF2, dp(10)));
      int visW = dp(40);                                  // 旋转后视觉宽 = 按钮自身高
      int visH = (int) c.getPaint().measureText(name) + dp(28); // 旋转后视觉高 = 按钮自身宽
      c.setRotation(90);
      FrameLayout slot = new FrameLayout(this);
      slot.addView(c, new FrameLayout.LayoutParams(visH, visW, Gravity.CENTER));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(visW, visH);
      slp.bottomMargin = dp(6);
      c.setOnClickListener(v -> {
        taskIdx = k; paintChips(); loadHistory(taskIds.get(k));
        for (int j = 0; j < bizChips.length; j++) {
          bizChips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          bizChips[j].setBackground(pill(j == k ? PRIMARY : 0xFFE7EAF2, dp(10)));
        }
      });
      bizChips[i] = c; rail.addView(slot, slp);
    }
    final LinearLayout leftCol = new LinearLayout(this);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(dp(4), dp(4), dp(0), dp(0));
    ScrollView railScroll = new ScrollView(this);
    railScroll.addView(rail); // 业务 tab 菜单可上下滑动
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(dp(64), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = dp(2);
    leftCol.setLayoutParams(lclp);
    leftCol.setVisibility(railHidden ? View.GONE : View.VISIBLE);

    LinearLayout top = new LinearLayout(this);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.addView(leftCol);

    LinearLayout chatCol = new LinearLayout(this);
    chatCol.setOrientation(LinearLayout.VERTICAL);
    chatCol.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    top.addView(chatCol);

    msgList = new LinearLayout(this);
    msgList.setOrientation(LinearLayout.VERTICAL);
    msgList.setPadding(dp(12), dp(6), dp(12), dp(6));
    scroller = new ScrollView(this);
    scroller.addView(msgList);
    chatCol.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    loadHistory(taskIds.get(taskIdx));


    LinearLayout bottom = new LinearLayout(this);
    bottom.setOrientation(LinearLayout.HORIZONTAL);
    bottom.setGravity(Gravity.CENTER_VERTICAL);
    bottom.setPadding(dp(12), dp(8), dp(12), dp(8));
    bottom.setBackgroundColor(Color.WHITE);
    input = new EditText(this);
    input.setHint(getString(R.string.input_hint, taskLabels.get(taskIdx))); input.setTextSize(14); input.setMaxLines(3);
    input.setBackground(pill(Color.WHITE, dp(22)));
    input.setPadding(dp(14), dp(10), dp(14), dp(10));
    bottom.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button send = new Button(this);
    send.setText(getString(R.string.btn_decide)); send.setTextColor(Color.WHITE); send.setAllCaps(false);
    send.setBackground(pill(PRIMARY, dp(22)));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    slp.leftMargin = dp(8); send.setLayoutParams(slp);
    send.setOnClickListener(v -> sendDecision(input.getText().toString()));
    bottom.addView(send);
    chatCol.addView(bottom);
    body.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    // 标题栏 ☰ 切换本页左栏(与报表页一致);状态记入字段,重建后不丢
    menuBtn.setOnClickListener(v -> {
      railHidden = leftCol.getVisibility() != View.GONE;
      leftCol.setVisibility(railHidden ? View.GONE : View.VISIBLE);
    });
  }

  /** 业务↔日志联动:切换业务时,聊天区载入该业务的历史决策 */
  private void loadHistory(String task) {
    if (msgList == null) return;
    msgList.removeAllViews();
    bot(getString(R.string.history_header, taskLabels.get(taskIds.indexOf(task))));
    java.util.List<org.json.JSONObject> hs = com.laya.DecisionCore.history(this, task, 20);
    if (hs.isEmpty()) bot(getString(R.string.history_empty));
    java.text.SimpleDateFormat df = new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.US);
    for (org.json.JSONObject h : hs) {
      bubble(h.optString("state"), true);
      String when = df.format(new java.util.Date(h.optLong("ts")));
      bot(fmtAnswers(task, h.optJSONObject("decoded"), (int) h.optLong("latencyMs")) + "\n· " + when);
    }
    // 回显最近一次 LLM 风险分析(升级通道产物)
    org.json.JSONObject llm = com.laya.DecisionCore.lastLlm(this, task);
    if (llm != null) {
      String when = df.format(new java.util.Date(llm.optLong("ts")));
      org.json.JSONObject l = llm.optJSONObject("llm");
      String c = l != null ? l.optString("content") : "";
      if (!c.isEmpty()) bot(getString(R.string.last_llm_analysis, when, c));
      else if (llm.has("error")) bot(getString(R.string.last_llm_fail, when, llm.optString("error")));
    }
    scroller.post(() -> scroller.scrollTo(0, scroller.getHeight()));
  }

  private void paintChips() {
    if (curBizLabel != null) curBizLabel.setText(getString(R.string.current_biz_hint, taskLabels.get(taskIdx)));
    if (input != null) input.setHint(getString(R.string.input_hint, taskLabels.get(taskIdx)));
  }

  private void sendDecision(String raw) {
    final String text = raw == null ? "" : raw.trim();
    if (text.isEmpty() || busy) return;
    busy = true;
    input.setText("");
    bubble(text, true);
    bubble(getString(R.string.inferring), false);
    final int ti = taskIdx;
    new Thread(() -> {
      String reply;
      try {
        com.laya.DecisionCore.Result r = com.laya.DecisionCore.decide(this, taskIds.get(ti), text);
        reply = fmtAnswers(taskIds.get(ti), r.answers, (int) r.latencyMs) + getString(R.string.backend_prefix, com.laya.DecisionCore.currentEngine(this));
      } catch (Throwable e) {
        android.util.Log.e("LayaApp", "decision failed", e);
        reply = getString(R.string.infer_failed, e.getClass().getSimpleName(), e.getMessage());
      }
      final String r2 = reply;
      runOnUiThread(() -> { bubble(r2, false); busy = false; });
    }).start();
  }

  private String fmtAnswers(String task, org.json.JSONObject decoded, int ms) {
    StringBuilder sb = new StringBuilder(getString(R.string.decision_result_header, taskLabels.get(taskIds.indexOf(task)))).append("\n");
    java.util.Iterator<String> it = decoded.keys();
    while (it.hasNext()) {
      org.json.JSONObject a = decoded.optJSONObject(it.next());
      if (a == null) continue;
      String type = a.optString("type");
      if ("choice".equals(type)) sb.append("• ").append(a.optString("choice")).append("\n");
      else if ("score".equals(type)) sb.append("• ").append(getString(R.string.label_score)).append(": ").append(String.format("%.2f", a.optDouble("score"))).append("/5\n");
      else if ("noul".equals(type)) sb.append("• ").append(getString(R.string.label_verdict)).append(": ").append(a.optDouble("noul") >= 0.5 ? getString(R.string.yes) : getString(R.string.no)).append("\n");
    }
    sb.append("\n").append(getString(R.string.label_latency)).append(": ").append(ms).append("ms");
    return sb.toString();
  }

  private static double asNum(Object o) {
    if (o instanceof Number) return ((Number) o).doubleValue();
    try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
  }

  // ================= ② 报表 =================
  private int reportKind = 0;
  private int detailPage = 0;   // 详单当前页(0 基)
  private int detailPages = 1;
  private LinearLayout pagerRow;
  private TextView pagerLabel;

  private void buildReportTab() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(12), dp(8), dp(12), dp(8));
    TextView cur = new TextView(this);
    cur.setText(getString(R.string.report_hint)); cur.setTextSize(12); cur.setTextColor(0xFF66707E);
    cur.setPadding(dp(2), 0, 0, 0);
    l.addView(cur);

    // 详单翻页行(仅详单显示)
    pagerRow = new LinearLayout(this);
    pagerRow.setOrientation(LinearLayout.HORIZONTAL);
    pagerRow.setGravity(Gravity.CENTER_VERTICAL);
    pagerRow.setPadding(0, dp(4), 0, dp(4));
    Button prev = new Button(this);
    prev.setText(getString(R.string.page_prev)); prev.setAllCaps(false); prev.setTextSize(12);
    prev.setOnClickListener(v -> { if (detailPage > 0) { detailPage--; renderReport(3); } });
    prev.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(prev);
    pagerLabel = new TextView(this);
    pagerLabel.setTextSize(12); pagerLabel.setGravity(Gravity.CENTER);
    pagerLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(pagerLabel);
    Button next = new Button(this);
    next.setText(getString(R.string.page_next)); next.setAllCaps(false); next.setTextSize(12);
    next.setOnClickListener(v -> { if (detailPage < detailPages - 1) { detailPage++; renderReport(3); } });
    next.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(next);
    pagerRow.setVisibility(View.GONE);
    l.addView(pagerRow);

    // ---- 下钻详单面板(仅 reportKind==4 显示):业务 × 决策等级 × 日期 ----
    drillPane = new LinearLayout(this);
    drillPane.setOrientation(LinearLayout.VERTICAL);
    drillPane.setPadding(0, dp(6), 0, 0);
    LinearLayout fRow1 = new LinearLayout(this);
    fRow1.setGravity(Gravity.CENTER_VERTICAL);
    spinRange = new Spinner(this);
    spinRange.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{getString(R.string.range_7d), getString(R.string.range_today), getString(R.string.range_30d), getString(R.string.range_all)}));
    spinRange.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinRange);
    spinLevel = new Spinner(this);
    spinLevel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{getString(R.string.level_all), getString(R.string.level_high), getString(R.string.level_mid), getString(R.string.level_low)}));
    spinLevel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinLevel);
    spinTask = new Spinner(this);
    java.util.List<String> tOpts = new ArrayList<>();
    tOpts.add(getString(R.string.task_all));
    for (int i = 0; i < taskIds.size(); i++) tOpts.add(taskLabels.get(i) + " (" + taskIds.get(i) + ")");
    spinTask.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tOpts));
    spinTask.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
    fRow1.addView(spinTask);
    drillPane.addView(fRow1);
    Button qBtn = new Button(this);
    qBtn.setText(getString(R.string.drill_query));
    qBtn.setAllCaps(false); qBtn.setTextSize(13);
    qBtn.setOnClickListener(v -> renderDrill());
    drillPane.addView(qBtn);
    drillTables = new LinearLayout(this);
    drillTables.setOrientation(LinearLayout.VERTICAL);
    drillPane.addView(drillTables);
    drillOut = new LinearLayout(this);
    drillOut.setOrientation(LinearLayout.VERTICAL);
    drillPane.addView(drillOut);
    drillPane.setVisibility(View.GONE);
    l.addView(drillPane);

    reportList = new LinearLayout(this);
    reportList.setOrientation(LinearLayout.VERTICAL);
    reportList.setPadding(0, dp(10), 0, 0);
    l.addView(reportList);
    scroller = new ScrollView(this);
    scroller.addView(l);
    renderReport(reportKind);

    // ---- 左侧竖排 tab(与系统页同款):日报/月报/年报/详单/下钻详单 ----
    final FrameLayout holder = new FrameLayout(this);
    holder.addView(scroller);
    holder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] kinds = {getString(R.string.kind_daily), getString(R.string.kind_monthly), getString(R.string.kind_yearly), getString(R.string.kind_detail), getString(R.string.kind_drill)};
    final Button[] chips = new Button[5];
    final LinearLayout rail = new LinearLayout(this);
    rail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 5; i++) {
      final int k = i;
      Button c = new Button(this);
      c.setText(kinds[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        reportKind = k;
        renderReport(k);
        for (int j = 0; j < 5; j++) {
          chips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          chips[j].setBackground(pill(j == k ? PRIMARY : 0xFFE7EAF2, dp(12)));
        }
      });
      chips[i] = c;
      rail.addView(railChip(c));
    }
    final LinearLayout leftCol = new LinearLayout(this);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(dp(4), dp(4), dp(0), dp(0));
    ScrollView railScroll = new ScrollView(this);
    railScroll.addView(rail); // 左侧 tab 菜单可上下滑动(小屏防截断)
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = dp(2);
    leftCol.setLayoutParams(lclp);
    LinearLayout top = new LinearLayout(this);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.addView(leftCol);
    top.addView(holder);
    body.addView(top);
    // 标题栏 ☰ 切换本页左栏
    menuBtn.setOnClickListener(v -> {
      boolean show = leftCol.getVisibility() == View.GONE;
      leftCol.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    // 初始高亮当前类型
    chips[reportKind].setTextColor(Color.WHITE);
    chips[reportKind].setBackground(pill(PRIMARY, dp(12)));
  }
  private LinearLayout reportList;
  private LinearLayout drillPane, drillTables, drillOut;
  private Spinner spinTask, spinLevel, spinRange;

  /** 左栏竖排 tab chip:整词旋转 90°(slot 定尺寸,词长自适应;中英文同构,与决策页业务 tab 同款) */
  private FrameLayout railChip(Button c) {
    int visW = dp(40);
    int visH = (int) c.getPaint().measureText(c.getText().toString()) + dp(28);
    c.setRotation(90); c.setPadding(0, dp(10), 0, dp(10));
    FrameLayout slot = new FrameLayout(this);
    slot.addView(c, new FrameLayout.LayoutParams(visH, visW, Gravity.CENTER));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(visW, visH);
    slp.bottomMargin = dp(6);
    slot.setLayoutParams(slp);
    return slot;
  }

  private TextView dCell(String t, boolean bold, int color, View.OnClickListener oc, float weight) {
    TextView c = new TextView(this);
    c.setText(t); c.setTextSize(12); c.setGravity(Gravity.CENTER);
    c.setTextColor(color != 0 ? color : 0xFF1A2B4C);
    if (bold) c.setTypeface(Typeface.DEFAULT_BOLD);
    c.setBackground(pill(0xFFF6F7FA, dp(6)));
    c.setPadding(dp(4), dp(7), dp(4), dp(7));
    c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight));
    if (oc != null) { c.setTextColor(0xFF3E7BFA); c.setOnClickListener(oc); }
    return c;
  }

  private void renderDrill() {
    final int days = new int[]{7, 1, 30, 0}[spinRange.getSelectedItemPosition()];
    final int lp = spinLevel.getSelectedItemPosition();
    final String level = lp == 0 ? null : new String[]{"高", "中", "低"}[lp - 1]; // 数据键固定,UI 显示已本地化
    final int tp = spinTask.getSelectedItemPosition();
    final String task = tp == 0 ? null : taskIds.get(tp - 1);
    final android.app.Activity act = this;
    new Thread(() -> {
      final JSONObject piv = com.laya.DecisionCore.detailPivot(act, days, task, level);
      runOnUiThread(() -> renderPivot(act, piv, task, level));
    }).start();
  }

  private void renderPivot(android.app.Activity act, JSONObject piv, String taskF, String levelF) {
    drillTables.removeAllViews();
    drillOut.removeAllViews();
    ArrayList<JSONObject> rows = new ArrayList<>();
    JSONArray jr = piv.optJSONArray("rows");
    if (jr != null) for (int i = 0; i < jr.length(); i++) rows.add(jr.optJSONObject(i));
    java.util.Collections.sort(rows, (a, b) -> {
      String ka = a.optString("date") + a.optString("task") + a.optString("level");
      String kb = b.optString("date") + b.optString("task") + b.optString("level");
      return ka.compareTo(kb);
    });
    // 收集维度
    java.util.LinkedHashSet<String> tasks = new java.util.LinkedHashSet<>(), dates = new java.util.LinkedHashSet<>(), levels = new java.util.LinkedHashSet<>();
    for (JSONObject r : rows) { tasks.add(r.optString("task")); dates.add(r.optString("date")); levels.add(r.optString("level")); }
    ArrayList<String> lvOrd = new ArrayList<>(); for (String l : new String[]{"高", "中", "低"}) if (levels.contains(l)) lvOrd.add(l);
    for (String l : levels) if (!lvOrd.contains(l)) lvOrd.add(l);
    ArrayList<String> dOrd = new ArrayList<>(dates); java.util.Collections.reverse(dOrd);
    TextView head = new TextView(this);
    head.setText(getString(R.string.drill_head, piv.optInt("total")));
    head.setTextSize(12); head.setTextColor(0xFF66707E); head.setPadding(dp(4), dp(8), 0, dp(4));
    drillTables.addView(head);
    java.util.Map<String, Integer> grid = new LinkedHashMap<>();
    java.util.Map<String, Integer> gDate = new LinkedHashMap<>();
    for (JSONObject r : rows) {
      grid.merge(r.optString("task") + "|" + r.optString("level"), r.optInt("count"), Integer::sum);
      gDate.merge(r.optString("date") + "|" + r.optString("task"), r.optInt("count"), Integer::sum);
    }
    // 表1:业务 × 等级
    LinearLayout t1 = new LinearLayout(this); t1.setOrientation(LinearLayout.VERTICAL);
    t1.setBackground(pill(0xFFFFFFFF, dp(10))); t1.setPadding(dp(6), dp(6), dp(6), dp(6));
    LinearLayout h1 = new LinearLayout(this);
    h1.addView(dCell(getString(R.string.col_task), true, 0, null, 2.2f));
    for (String lv : lvOrd) h1.addView(dCell(lv, true, 0, null, 1f));
    h1.addView(dCell(getString(R.string.col_total), true, 0, null, 1f));
    t1.addView(h1);
    for (String t : tasks) {
      LinearLayout r = new LinearLayout(this);
      r.addView(dCell(t, true, 0, null, 2.2f));
      int tot = 0;
      for (String lv : lvOrd) {
        int n = grid.getOrDefault(t + "|" + lv, 0); tot += n;
        final String ft = t, flv = lv;
        r.addView(dCell(n == 0 ? "·" : String.valueOf(n), false, 0, v -> drillShow(null, ft, flv), 1f));
      }
      final String ft = t;
      r.addView(dCell(String.valueOf(tot), true, 0, v -> drillShow(null, ft, null), 1f));
      t1.addView(r);
    }
    drillTables.addView(t1);
    // 表2:日期 × 业务
    LinearLayout t2 = new LinearLayout(this); t2.setOrientation(LinearLayout.VERTICAL);
    t2.setBackground(pill(0xFFFFFFFF, dp(10))); t2.setPadding(dp(6), dp(6), dp(6), dp(6));
    LinearLayout h2 = new LinearLayout(this);
    h2.addView(dCell(getString(R.string.col_date), true, 0, null, 1.6f));
    for (String t : tasks) h2.addView(dCell(t, true, 0, null, 1f));
    h2.addView(dCell(getString(R.string.col_total), true, 0, null, 1f));
    t2.addView(h2);
    for (String d : dOrd) {
      LinearLayout r = new LinearLayout(this);
      r.addView(dCell(d.substring(5), false, 0, null, 1.6f));
      int tot = 0;
      for (String t : tasks) {
        int n = gDate.getOrDefault(d + "|" + t, 0); tot += n;
        final String fd = d, ft = t;
        r.addView(dCell(n == 0 ? "·" : String.valueOf(n), false, 0, v -> drillShow(fd, ft, null), 1f));
      }
      final String fd = d;
      r.addView(dCell(String.valueOf(tot), true, 0, v -> drillShow(fd, null, null), 1f));
      t2.addView(r);
    }
    LinearLayout gap = new LinearLayout(this); gap.setPadding(0, dp(8), 0, 0);
    drillTables.addView(gap); drillTables.addView(t2);
  }

  private void drillShow(String date, String task, String level) {
    drillOut.removeAllViews();
    TextView loading = new TextView(this);
    loading.setText(getString(R.string.loading_detail)); loading.setTextSize(12); loading.setPadding(dp(4), dp(8), 0, 0);
    drillOut.addView(loading);
    final android.app.Activity act = this;
    new Thread(() -> {
      final JSONArray es = com.laya.DecisionCore.drillList(act, date, task, level, 200);
      runOnUiThread(() -> {
        drillOut.removeAllViews();
        java.util.Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < taskIds.size(); i++) labels.put(taskIds.get(i), taskLabels.get(i));
        TextView title = new TextView(this);
        title.setText(getString(R.string.drill_title, date != null ? date : getString(R.string.range_any),
            task != null ? labels.getOrDefault(task, task) : getString(R.string.task_all),
            level != null ? level : getString(R.string.level_any), es.length()));
        title.setTextSize(13); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(dp(4), dp(10), 0, dp(4));
        drillOut.addView(title);
        for (int i = 0; i < es.length(); i++) {
          JSONObject e = es.optJSONObject(i); if (e == null) continue;
          LinearLayout card = new LinearLayout(this);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setBackground(pill(0xFFF7F8FA, dp(8)));
          card.setPadding(dp(9), dp(6), dp(9), dp(7));
          LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
          clp.bottomMargin = dp(6); card.setLayoutParams(clp);
          TextView l1 = new TextView(this);
          String lv = e.optString("level");
          int lvc = "高".equals(lv) ? 0xFFD62828 : "中".equals(lv) ? 0xFFE78A00 : "低".equals(lv) ? 0xFF2A9D8F : 0xFF66707E;
          l1.setText(e.optString("time") + "  [" + labels.getOrDefault(e.optString("task"), e.optString("task")) + "]  " + lv
              + "  " + e.optLong("latencyMs") + "ms");
          l1.setTextSize(12); l1.setTypeface(Typeface.DEFAULT_BOLD); l1.setTextColor(lvc);
          card.addView(l1);
          String ans = e.optString("answers", "");
          String basis = e.optString("basis", "");
          TextView l2 = new TextView(this);
          l2.setText((ans.isEmpty() ? "" : ans + "\n") + basis);
          l2.setTextSize(11); l2.setTextColor(0xFF444A55); l2.setPadding(0, dp(1), 0, dp(2));
          card.addView(l2);
          TextView l3 = new TextView(this);
          String st = e.optString("state");
          l3.setText(st.length() > 80 ? st.substring(0, 80) + "…" : st);
          l3.setTextSize(11); l3.setTextColor(0xFF66707E);
          card.addView(l3);
          drillOut.addView(card);
        }
        if (es.length() == 0) {
          TextView empty = new TextView(this);
          empty.setText(getString(R.string.no_records)); empty.setTextSize(12); empty.setTextColor(0xFF66707E);
          empty.setPadding(dp(4), dp(6), 0, 0);
          drillOut.addView(empty);
        }
        scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
      });
    }).start();
  }

  private void renderReport(int kind) {
    final boolean isDetail = kind == 3;
    final boolean isDrill = kind == 4;
    runOnUiThread(() -> {
      pagerRow.setVisibility(isDetail ? View.VISIBLE : View.GONE);
      reportList.setVisibility(isDrill ? View.GONE : View.VISIBLE);
      drillPane.setVisibility(isDrill ? View.VISIBLE : View.GONE);
    });
    if (isDrill) { runOnUiThread(this::renderDrill); return; }
    final android.app.Activity act = this;
    new Thread(() -> {
      try {
        if (isDetail) {
          org.json.JSONObject d = com.laya.DecisionCore.detail(act, detailPage, 20);
          detailPages = d.optInt("pages", 1);
          JSONArray rows = d.optJSONArray("rows");
          String[] heads = {getString(R.string.head_time), getString(R.string.col_task), getString(R.string.head_result), getString(R.string.label_score), getString(R.string.label_verdict), getString(R.string.col_level), getString(R.string.label_latency), getString(R.string.head_content)};
          float[] ws = {1.4f, 0.7f, 1.0f, 0.6f, 0.7f, 0.6f, 0.8f, 1.9f};
          java.util.List<String[]> data = new ArrayList<>();
          if (rows != null) for (int i = 0; i < rows.length(); i++) {
            JSONObject e = rows.optJSONObject(i); if (e == null) continue;
            data.add(new String[]{e.optString("time"), e.optString("task"), e.optString("result"),
                e.optString("score"), e.optString("verdict"), e.optString("level"),
                e.optLong("latencyMs") + "ms", e.optString("state")});
          }
          final java.util.List<String[]> fin = data;
          final int pg = d.optInt("page", 1), pgs = d.optInt("pages", 1);
          runOnUiThread(() -> {
            showTable(heads, ws, fin);
            pagerLabel.setText(getString(R.string.pager_label, pg, pgs));
          });
          return;
        }
        JSONObject rt = com.laya.DecisionCore.reportTable(act, kind);
        JSONArray rows = rt.optJSONArray("rows");
        String[] heads = {getString(R.string.head_period), getString(R.string.head_count, 0), getString(R.string.head_avg_latency), getString(R.string.head_top), getString(R.string.head_dist)};
        float[] ws = {1.2f, 0.8f, 0.9f, 1.1f, 2.0f};
        java.util.List<String[]> data = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
          JSONObject e = rows.optJSONObject(i); if (e == null) continue;
          data.add(new String[]{e.optString("period"), String.valueOf(e.optInt("count")),
              e.optInt("avgLatency") + "ms", e.optString("top"), e.optString("dist")});
        }
        final java.util.List<String[]> fin = data;
        final int tot = rt.optInt("total");
        runOnUiThread(() -> {
          if (fin.isEmpty()) {
            reportList.removeAllViews();
            TextView empty = new TextView(act);
            empty.setText(getString(R.string.no_data)); empty.setTextSize(12); empty.setTextColor(0xFF66707E);
            reportList.addView(empty);
          } else {
            String[] heads2 = new String[heads.length];
            for (int i = 0; i < heads.length; i++) heads2[i] = i == 1 ? getString(R.string.head_count, tot) : heads[i];
            showTable(heads2, ws, fin);
          }
        });
      } catch (Exception e) {
        final String msg = getString(R.string.gen_failed, e.getMessage());
        runOnUiThread(() -> {
          reportList.removeAllViews();
          TextView err = new TextView(act);
          err.setText(msg); err.setTextSize(12); err.setTextColor(0xFFD62828);
          reportList.addView(err);
        });
      }
    }).start();
  }

  /** 通用数据表(白底圆角卡 + 灰底表头 + 权重列宽) */
  private void showTable(String[] heads, float[] ws, java.util.List<String[]> rows) {
    reportList.removeAllViews();
    LinearLayout t = new LinearLayout(this);
    t.setOrientation(LinearLayout.VERTICAL);
    t.setBackground(pill(0xFFFFFFFF, dp(10)));
    t.setPadding(dp(6), dp(4), dp(6), dp(4));
    LinearLayout h = new LinearLayout(this);
    h.setBackground(pill(0xFFEFF2F7, dp(6)));
    for (int i = 0; i < heads.length; i++) h.addView(tCell(heads[i], true, ws[i]));
    t.addView(h);
    for (int r = 0; r < rows.size(); r++) {
      LinearLayout row = new LinearLayout(this);
      String[] cells = rows.get(r);
      for (int i = 0; i < cells.length; i++) row.addView(tCell(cells[i], i == 0, ws[i]));
      t.addView(row);
    }
    reportList.addView(t);
  }

  private TextView tCell(String s, boolean bold, float w) {
    TextView c = new TextView(this);
    c.setText(s == null || s.isEmpty() ? "-" : s);
    c.setTextSize(11);
    c.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    c.setTextColor(bold ? 0xFF1A2B4C : 0xFF444A55);
    c.setPadding(dp(6), dp(5), dp(6), dp(5));
    c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w));
    return c;
  }

  // ================= ③ 网关 =================
  private EditText gwEmailHost, gwEmailUser, gwEmailPass, gwReportTo;
  private EditText gwImapPort, gwSmtpPort; private CheckBox gwSsl;
  private EditText upSrc, upTask; private TextView upStatus;
  private EditText gwMqUrl, gwMqSub, gwMqPub;
  private TextView gwStatus;

  private void buildGatewayTab() {
    // 三个子页面板,左栏 tab 切换
    LinearLayout pMail = new LinearLayout(this);
    pMail.setOrientation(LinearLayout.VERTICAL);
    pMail.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout l = pMail;
    l.addView(hint(getString(R.string.mail_hint)));
    gwEmailHost = fieldU(l, getString(R.string.mail_host_hint));
    gwEmailUser = fieldU(l, getString(R.string.mail_user_hint));
    gwEmailPass = fieldU(l, getString(R.string.mail_pass_hint));
    LinearLayout pr = new LinearLayout(this);
    pr.setOrientation(LinearLayout.HORIZONTAL);
    gwImapPort = fieldU(pr, getString(R.string.imap_port_hint), "143");
    gwSmtpPort = fieldU(pr, getString(R.string.smtp_port_hint), "25");
    LinearLayout.LayoutParams plp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    plp1.rightMargin = dp(8); gwImapPort.setLayoutParams(plp1);
    gwSmtpPort.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    l.addView(pr);
    gwSsl = new CheckBox(this);
    gwSsl.setText(getString(R.string.ssl_hint));
    gwSsl.setTextSize(13); gwSsl.setPadding(0, dp(6), 0, dp(6));
    l.addView(gwSsl);
    gwReportTo = fieldU(l, getString(R.string.report_to_hint));
    Button emailBtn = button(l, getString(R.string.save_start_mail));
    emailBtn.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(getApplicationContext());
        cfg.put("enabled", true);
        cfg.put("email", new JSONObject().put("enabled", true)
            .put("host", gwEmailHost.getText().toString())
            .put("user", gwEmailUser.getText().toString())
            .put("pass", gwEmailPass.getText().toString())
            .put("imapPort", parsePort(gwImapPort.getText().toString(), gwSsl.isChecked() ? 993 : 143))
            .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
            .put("ssl", gwSsl.isChecked()));
        String to = gwReportTo.getText().toString();
        if (!to.isEmpty()) cfg.put("report", new JSONObject().put("to", to));
        gwStatus.setText(com.laya.Gateway.saveAndStart(getApplicationContext(), cfg));
        refreshGatewayBar();
      } catch (Exception e) { gwStatus.setText(getString(R.string.cfg_failed, e.getMessage())); }
    });
    Button emailTestBtn = button(l, getString(R.string.test_imap_btn));
    emailTestBtn.setOnClickListener(v -> {
      gwStatus.setText(getString(R.string.imap_testing));
      new Thread(() -> {
        String r;
        try {
          r = com.laya.Gateway.testEmail(this, new JSONObject()
              .put("host", gwEmailHost.getText().toString())
              .put("user", gwEmailUser.getText().toString())
              .put("pass", gwEmailPass.getText().toString())
              .put("imapPort", parsePort(gwImapPort.getText().toString(), gwSsl.isChecked() ? 993 : 143))
              .put("ssl", gwSsl.isChecked()));
        } catch (Exception e) { r = "❌ IMAP: " + e.getMessage(); }
        final String fr = r;
        runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    Button smtpTestBtn = button(l, getString(R.string.test_smtp_btn));
    smtpTestBtn.setOnClickListener(v -> {
      gwStatus.setText(getString(R.string.smtp_testing));
      new Thread(() -> {
        String r;
        try {
          JSONObject ec = new JSONObject()
              .put("host", gwEmailHost.getText().toString())
              .put("user", gwEmailUser.getText().toString())
              .put("pass", gwEmailPass.getText().toString())
              .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
              .put("ssl", gwSsl.isChecked());
          r = com.laya.Gateway.testSmtp(this, ec, gwReportTo.getText().toString());
        } catch (Exception e) { r = "❌ SMTP: " + e.getMessage(); }
        final String fr = r;
        runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    LinearLayout pMq = new LinearLayout(this);
    pMq.setOrientation(LinearLayout.VERTICAL);
    pMq.setPadding(dp(12), dp(8), dp(12), dp(8));
    l = pMq;
    l.addView(hint(getString(R.string.mqtt_hint)));
    gwMqUrl = fieldU(l, getString(R.string.mqtt_url_hint));
    gwMqSub = fieldU(l, getString(R.string.mqtt_sub_hint), "laya/req/+");
    gwMqPub = fieldU(l, getString(R.string.mqtt_pub_hint), "laya/resp");
    Button mqBtn = button(l, getString(R.string.save_start_mqtt));
    mqBtn.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(getApplicationContext());
        cfg.put("enabled", true);
        cfg.put("mqtt", new JSONObject().put("enabled", true).put("url", gwMqUrl.getText().toString()));
        cfg.put("topics", new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString()));
        gwStatus.setText(com.laya.Gateway.saveAndStart(getApplicationContext(), cfg));
      } catch (Exception e) { gwStatus.setText(getString(R.string.cfg_failed, e.getMessage())); }
    });
    Button mqTestBtn = button(l, getString(R.string.test_mqtt_btn));
    mqTestBtn.setOnClickListener(v -> {
      gwStatus.setText(getString(R.string.mqtt_testing));
      new Thread(() -> {
        String r;
        try {
          JSONObject mc = new JSONObject().put("url", gwMqUrl.getText().toString());
          JSONObject tc = new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString());
          r = com.laya.Gateway.testMqtt(this, mc, tc);
        } catch (Exception e) { r = "❌ MQTT: " + e.getMessage(); }
        final String fr = r;
        runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    l.addView(hint(getString(R.string.upload_hint, com.laya.Gateway.uploadUrl(), com.laya.Gateway.uploadUrl())));
    Button upTestBtn = button(l, getString(R.string.test_upload_btn));
    upTestBtn.setOnClickListener(v -> {
      gwStatus.setText(getString(R.string.upload_testing));
      new Thread(() -> {
        final String r = com.laya.Gateway.testUpload(this);
        runOnUiThread(() -> gwStatus.setText(r));
      }).start();
    });

    // ---- 决策 API 服务(HTTP):POST /decide,随网关配置持久化 ----
    l.addView(hint(getString(R.string.api_hint)));
    final boolean apiOn = com.laya.DecisionApiServer.running();
    Button apiBtn = button(l, getString(apiOn ? R.string.api_stop : R.string.api_start));
    apiBtn.setOnClickListener(v -> {
      boolean next = !com.laya.DecisionApiServer.running();
      try {
        JSONObject cfg = com.laya.Gateway.cfg(getApplicationContext());
        cfg.put("api", new JSONObject().put("enabled", next));
        com.laya.Gateway.saveCfg(getApplicationContext(), cfg);
      } catch (Exception e) { gwStatus.setText(getString(R.string.cfg_failed, e.getMessage())); return; }
      if (next) com.laya.DecisionApiServer.start(getApplicationContext(), com.laya.DecisionApiServer.PORT);
      else com.laya.DecisionApiServer.stopServer();
      apiBtn.setText(getString(next ? R.string.api_stop : R.string.api_start));
      gwStatus.setText(getString(R.string.gateway_bar, com.laya.Gateway.status(this)));
    });

    // ---- LLM 设置(重要+紧急升级通道,3 槽位供可选) ----
    LinearLayout pLlm = new LinearLayout(this);
    pLlm.setOrientation(LinearLayout.VERTICAL);
    pLlm.setPadding(dp(12), dp(8), dp(12), dp(8));
    l = pLlm;
    l.addView(hint(getString(R.string.llm_hint)));
    JSONObject llmCfg = com.laya.Gateway.cfg(this).optJSONObject("llm");
    JSONObject llmSlots = llmCfg != null ? llmCfg.optJSONObject("slots") : null;
    String actId = llmCfg != null ? llmCfg.optString("active", "llm1") : "llm1";
    String[] slotIds = {"llm1", "llm2", "llm3"};
    String[] defNames = {"DeepSeek", "Qwen(通义)", "GLM(智谱)"};
    String[] defUrls = {"https://api.deepseek.com", "https://dashscope.aliyuncs.com/compatible-mode/v1", "https://open.bigmodel.cn/api/paas/v4"};
    String[] defModels = {"deepseek-chat", "qwen-flash", "glm-4-flash"};
    final EditText[] llmName = new EditText[3];
    final EditText[] llmUrl = new EditText[3];
    final EditText[] llmModel = new EditText[3];
    final EditText[] llmKey = new EditText[3];
    final RadioButton[] llmRb = new RadioButton[3];
    for (int i = 0; i < 3; i++) {
      JSONObject s = llmSlots != null ? llmSlots.optJSONObject(slotIds[i]) : null;
      LinearLayout slot = new LinearLayout(this);
      slot.setOrientation(LinearLayout.VERTICAL);
      slot.setBackground(pill(0xFFF7F8FA, dp(10)));
      slot.setPadding(dp(9), dp(6), dp(9), dp(8));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      slp.bottomMargin = dp(8); slot.setLayoutParams(slp);
      LinearLayout head = new LinearLayout(this);
      head.setGravity(Gravity.CENTER_VERTICAL);
      llmRb[i] = new RadioButton(this);
      llmRb[i].setText(getString(R.string.llm_enable)); llmRb[i].setTextSize(12);
      llmRb[i].setChecked(slotIds[i].equals(actId));
      head.addView(llmRb[i]);
      llmName[i] = fieldU(head, getString(R.string.name_hint));
      JSONObject fs = s;
      String nm = s != null ? s.optString("name", defNames[i]) : defNames[i];
      llmName[i].setText(nm);
      llmName[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      slot.addView(head);
      llmUrl[i] = fieldU(slot, getString(R.string.base_url_hint));
      llmUrl[i].setText(s != null ? s.optString("baseURL", defUrls[i]) : defUrls[i]);
      LinearLayout mr = new LinearLayout(this);
      llmModel[i] = fieldU(mr, "Model");
      llmModel[i].setText(s != null ? s.optString("model", defModels[i]) : defModels[i]);
      llmModel[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      llmKey[i] = fieldU(mr, "API Key");
      if (s != null) llmKey[i].setText(s.optString("apiKey", ""));
      llmKey[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      LinearLayout.LayoutParams mlp0 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      mlp0.rightMargin = dp(8); llmModel[i].setLayoutParams(mlp0);
      slot.addView(mr);
      l.addView(slot);
    }
    llmRb[0].setId(901); llmRb[1].setId(902); llmRb[2].setId(903);
    // 手动互斥(RadioGroup 纵向占太高):点一个清其余
    for (int i = 0; i < 3; i++) {
      final int k = i;
      llmRb[i].setOnClickListener(v -> { for (int j = 0; j < 3; j++) llmRb[j].setChecked(j == k); });
    }
    Button llmSave = button(l, getString(R.string.save_llm));
    llmSave.setOnClickListener(v -> {
      try {
        JSONObject slots = new JSONObject();
        int checked = -1;
        for (int i = 0; i < 3; i++) {
          if (llmRb[i].isChecked()) checked = i;
          slots.put(slotIds[i], new JSONObject()
              .put("name", llmName[i].getText().toString())
              .put("baseURL", llmUrl[i].getText().toString().trim())
              .put("model", llmModel[i].getText().toString().trim())
              .put("apiKey", llmKey[i].getText().toString().trim()));
        }
        gwStatus.setText(com.laya.Gateway.saveLlm(this,
            new JSONObject().put("active", checked < 0 ? "llm1" : slotIds[checked]).put("slots", slots)));
      } catch (Exception e) { gwStatus.setText(getString(R.string.llm_cfg_failed, e.getMessage())); }
    });
    Button llmTest = button(l, getString(R.string.test_llm_btn));
    llmTest.setOnClickListener(v -> {
      try {
        int checked = -1;
        for (int i = 0; i < 3; i++) if (llmRb[i].isChecked()) checked = i;
        if (checked < 0) { gwStatus.setText(getString(R.string.llm_pick_first)); return; }
        final String url = llmUrl[checked].getText().toString().trim().replaceAll("/+$", "");
        final String model = llmModel[checked].getText().toString().trim();
        final String key = llmKey[checked].getText().toString().trim();
        final String nm = llmName[checked].getText().toString();
        gwStatus.setText(getString(R.string.testing_x, nm));
        new Thread(() -> {
          String r;
          try {
            HttpURLConnection c = (HttpURLConnection) new URL(url + "/chat/completions").openConnection();
            c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(120000);
            c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
            if (!key.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + key);
            try (OutputStream os = c.getOutputStream()) {
              os.write(new JSONObject().put("model", model).put("max_tokens", 300)
                  .put("messages", new org.json.JSONArray().put(new JSONObject()
                      .put("role", "user").put("content", "只回复两个字母:OK"))).toString().getBytes(StandardCharsets.UTF_8));
            }
            int code = c.getResponseCode();
            java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
            (code < 400 ? c.getInputStream() : c.getErrorStream()).transferTo(bos);
            String body = bos.toString("UTF-8");
            if (code < 400) {
              String txt = new JSONObject(body).optJSONArray("choices") != null
                  ? new JSONObject(body).optJSONArray("choices").optJSONObject(0).optJSONObject("message").optString("content") : "";
              r = getString(R.string.llm_ok, nm, code, txt.trim());
            } else r = getString(R.string.llm_http_err, nm, code, body.substring(0, Math.min(160, body.length())));
          } catch (Exception e) { r = getString(R.string.llm_fail, nm, e.getMessage()); }
          final String fr = r;
          runOnUiThread(() -> gwStatus.setText(fr));
        }).start();
      } catch (Exception e) { gwStatus.setText(getString(R.string.test_failed, e.getMessage())); }
    });

    // ---- 左侧竖排 tab(与报表页同款):邮件/队列/LLM ----
    LinearLayout[] panels = {pMail, pMq, pLlm};
    final ScrollView[] scrolls = new ScrollView[3];
    for (int i = 0; i < 3; i++) {
      ScrollView sv = new ScrollView(this);
      sv.addView(panels[i]);
      scrolls[i] = sv;
    }
    scroller = scrolls[0];
    final FrameLayout gHolder = new FrameLayout(this);
    gHolder.addView(scrolls[0]);
    gHolder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] gkinds = {getString(R.string.gw_mail), getString(R.string.gw_mq), "LLM"};
    final Button[] gchips = new Button[3];
    final LinearLayout grail = new LinearLayout(this);
    grail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 3; i++) {
      final int k = i;
      Button c = new Button(this);
      c.setText(gkinds[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        gHolder.removeAllViews();
        gHolder.addView(scrolls[k]);
        scroller = scrolls[k];
        for (int j = 0; j < 3; j++) {
          gchips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          gchips[j].setBackground(pill(j == k ? PRIMARY : 0xFFE7EAF2, dp(12)));
        }
      });
      gchips[i] = c;
      grail.addView(railChip(c));
    }
    final LinearLayout gLeft = new LinearLayout(this);
    gLeft.setOrientation(LinearLayout.VERTICAL);
    gLeft.setPadding(dp(4), dp(4), dp(0), dp(0));
    gLeft.addView(grail);
    LinearLayout.LayoutParams glclp = new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT);
    glclp.rightMargin = dp(2);
    gLeft.setLayoutParams(glclp);
    LinearLayout gTop = new LinearLayout(this);
    gTop.setOrientation(LinearLayout.HORIZONTAL);
    gTop.addView(gLeft);
    gTop.addView(gHolder);
    gTop.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    body.addView(gTop);
    // 标题栏 ☰ 切换本页左栏
    menuBtn.setOnClickListener(v -> {
      boolean show = gLeft.getVisibility() == View.GONE;
      gLeft.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    // 共用底栏:停止 + 状态
    LinearLayout gBottom = new LinearLayout(this);
    gBottom.setOrientation(LinearLayout.VERTICAL);
    gBottom.setPadding(dp(12), dp(2), dp(12), dp(8));
    Button stopBtn = button(gBottom, getString(R.string.stop_gw));
    stopBtn.setOnClickListener(v -> gwStatus.setText(com.laya.Gateway.stop(getApplicationContext())));
    gwStatus = hint(getString(R.string.gateway_bar, com.laya.Gateway.status(this)));
    gBottom.addView(gwStatus);
    body.addView(gBottom);
    // 初始高亮
    gchips[0].setTextColor(Color.WHITE);
    gchips[0].setBackground(pill(PRIMARY, dp(12)));
  }

  /** 下划线输入框(网关页):不带 pill 背景,走系统默认下划线 */
  private EditText fieldU(LinearLayout parent, String hint) { return fieldU(parent, hint, ""); }
  private EditText fieldU(LinearLayout parent, String hint, String text) {
    EditText e = new EditText(this);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setPadding(dp(4), dp(10), dp(4), dp(10));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }
  private static int parsePort(String s, int def) {
    try { int v = Integer.parseInt(s.trim()); return (v > 0 && v < 65536) ? v : def; } catch (Exception e) { return def; }
  }

  private TextView hint(String s) {
    TextView t = new TextView(this);
    t.setText(s); t.setTextSize(12); t.setTextColor(0xFF666C77); t.setPadding(0, dp(8), 0, dp(4));
    return t;
  }
  private EditText field(LinearLayout parent, String hint) { return field(parent, hint, ""); }
  private EditText field(LinearLayout parent, String hint, String text) {
    EditText e = new EditText(this);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setBackground(pill(Color.WHITE, dp(10)));
    e.setPadding(dp(10), dp(8), dp(10), dp(8));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }
  private Button button(LinearLayout parent, String label) {
    Button b = new Button(this);
    b.setText(label); b.setAllCaps(false); b.setTextColor(Color.WHITE); b.setTextSize(13);
    b.setBackground(pill(PRIMARY, dp(16)));
    b.setPadding(dp(14), dp(8), dp(14), dp(8));
    LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    blp.bottomMargin = dp(8); // 相邻按钮隔开
    parent.addView(b, blp);
    return b;
  }


  // ---- 模型详情:八文件清单 + 大小 + 齐全度(主图 NPU/GPU 二选一,scorer.bin 仅 NPU 用) ----
  private static final String[] MODEL_FILES = {
    "laya_ml_s256_embeds_npu.tflite", "laya_ml_s256_embeds_wfp16.tflite",
    "laya_ml_scorer.bin", "laya_ml_act_head_fp32.tflite",
    "token_embeddings_fp16.bin", "token_embeddings.json",
    "laya_ml_calibration.json", "tokenizer.json",
  };

  /** 业务模型源包目录(multi 基础包目录名无后缀) */
  private File srcDir(String task) {
    return new File("multi".equals(task)
        ? "/sdcard/models/laya-litert/phone" : "/sdcard/models/laya-litert-" + task + "/phone");
  }

  private static void deleteQuiet(File f) {
    if (f == null || !f.exists()) return;
    File[] kids = f.isDirectory() ? f.listFiles() : null;
    if (kids != null) for (File k : kids) deleteQuiet(k);
    f.delete();
  }

  private static String human(long b) {
    return b >= 1048576L ? (b / 1048576L) + "MB" : (b / 1024L) + "KB";
  }

  /** 目录内全部文件打包为 zip(文件位于 zip 根,importPackage 可直接校验导入);ZIP 根含 label.txt 等附加文件也一并带上 */
  private void zipDir(File dir, File dst) throws Exception {
    File[] files = dir.listFiles();
    if (files == null || files.length == 0) throw new IllegalStateException(getString(R.string.src_empty_err, String.valueOf(dir)));
    java.util.zip.ZipOutputStream zos = new java.util.zip.ZipOutputStream(
        new java.io.BufferedOutputStream(new FileOutputStream(dst)));
    zos.setLevel(java.util.zip.Deflater.BEST_SPEED); // 650MB 级模型包,速度优先
    try {
      byte[] buf = new byte[256 * 1024];
      for (File f : files) {
        if (!f.isFile()) continue;
        zos.putNextEntry(new java.util.zip.ZipEntry(f.getName()));
        try (InputStream in = new FileInputStream(f)) {
          int n;
          while ((n = in.read(buf)) > 0) zos.write(buf, 0, n);
        }
        zos.closeEntry();
      }
    } finally {
      zos.close();
    }
  }

  private String modelDetail(String task) {
    File src = srcDir(task);
    File inst = new File(getFilesDir(), "laya-" + task);
    StringBuilder sb = new StringBuilder();
    long total = 0; int have = 0; String npu = null, gpu = null;
    for (String f : MODEL_FILES) {
      File s = new File(src, f);
      if (s.isFile()) {
        have++; total += s.length();
        if (f.endsWith("embeds_npu.tflite")) npu = human(s.length());
        if (f.endsWith("embeds_wfp16.tflite")) gpu = human(s.length());
        sb.append("✓ ").append(f).append("  ").append(human(s.length())).append('\n');
      } else sb.append("✗ ").append(f).append("  ").append(getString(R.string.missing_mark)).append('\n');
    }
    String main = npu != null && gpu != null ? "NPU dispatch(" + npu + ")+GPU wfp16(" + gpu + ")"
        : npu != null ? "NPU dispatch(" + npu + ")" + getString(R.string.npu_pure_suffix) : "GPU wfp16(" + gpu + ")" + getString(R.string.no_npu_suffix);
    sb.insert(0, getString(R.string.fmt_prefix, main) + "\n"
        + getString(R.string.complete_prefix, have, human(total)) + "\n"
        + getString(R.string.src_prefix, src.getAbsolutePath()) + "\n");
    long it = 0; int ih = 0;
    for (String f : MODEL_FILES) { File d = new File(inst, f); if (d.isFile()) { ih++; it += d.length(); } }
    sb.append(getString(R.string.loaded_copy, ih == 0 ? getString(R.string.not_installed)
        : getString(R.string.files_of_8, ih, human(it))));
    return sb.toString();
  }

  // ================= ④ 系统 =================
  private TextView sysView;
  private TextView gwBarText;

  private void buildSysTab() {
    refreshTasks();
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(12), dp(8), dp(12), dp(8));

    // ---- 语言 / Language(应用内切换,立即生效) ----
    LinearLayout langRow = new LinearLayout(this);
    langRow.setOrientation(LinearLayout.VERTICAL);
    langRow.setBackground(pill(0xFFF0F4FF, dp(10)));
    langRow.setPadding(dp(10), dp(8), dp(10), dp(8));
    LinearLayout.LayoutParams langLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    langLp.bottomMargin = dp(10);
    langRow.setLayoutParams(langLp);
    TextView langHead = new TextView(this);
    langHead.setText(getString(R.string.lang_label)); langHead.setTextSize(14); langHead.setTypeface(Typeface.DEFAULT_BOLD);
    langRow.addView(langHead);
    LinearLayout langBtns = new LinearLayout(this);
    langBtns.setOrientation(LinearLayout.HORIZONTAL);
    LinearLayout.LayoutParams btnsLp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    btnsLp.topMargin = dp(4);
    langBtns.setLayoutParams(btnsLp);
    String curLoc = uiLocale();
    String[] locIds = {"sys", "zh", "en"};
    for (String id : locIds) {
      final String fid = id;
      Button b = new Button(this);
      b.setText("sys".equals(id) ? getString(R.string.lang_follow) : "zh".equals(id) ? getString(R.string.lang_zh) : getString(R.string.lang_en));
      b.setAllCaps(false); b.setTextSize(12);
      b.setMinHeight(0); b.setMinimumWidth(0); b.setMinimumHeight(0);
      b.setPadding(dp(12), dp(6), dp(12), dp(6));
      boolean on = id.equals(curLoc);
      b.setTextColor(on ? Color.WHITE : 0xFF1A2B4C);
      b.setBackground(pill(on ? PRIMARY : 0xFFE7EAF2, dp(14)));
      LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      blp.leftMargin = dp(8);
      b.setLayoutParams(blp);
      b.setOnClickListener(v -> {
        if (!fid.equals(uiLocale())) {
          getSharedPreferences("ui", MODE_PRIVATE).edit().putString("locale", fid).apply();
          recreate(); // attachBaseContext 读取新 locale 重建整套 UI
        }
      });
      langBtns.addView(b);
    }
    langRow.addView(langBtns);
    l.addView(langRow);

    // ---- 手动上传模型包输入界面 ----
    LinearLayout up = new LinearLayout(this);
    up.setOrientation(LinearLayout.VERTICAL);
    up.setBackground(pill(0xFFF0F4FF, dp(10)));
    up.setPadding(dp(10), dp(8), dp(10), dp(10));
    LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ulp.bottomMargin = dp(10);
    up.setLayoutParams(ulp);
    TextView upHead = new TextView(this);
    upHead.setText(getString(R.string.upload_head)); upHead.setTextSize(14); upHead.setTypeface(Typeface.DEFAULT_BOLD);
    up.addView(upHead);
    upSrc = fieldU(up, getString(R.string.pkg_path_hint));
    Button pickBtn = button(up, getString(R.string.pick_zip));
    pickBtn.setOnClickListener(v -> {
      Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      it.addCategory(Intent.CATEGORY_OPENABLE);
      it.setType("*/*");
      it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
      startActivityForResult(it, REQ_PICK_ZIP);
    });
    upTask = fieldU(up, getString(R.string.task_name_hint));
    upStatus = new TextView(this);
    upStatus.setTextSize(11); upStatus.setTextColor(0xFF66707E);
    upStatus.setText(getString(R.string.upload_note));
    up.addView(upStatus);
    Button impBtn = button(up, getString(R.string.upload_register));
    impBtn.setOnClickListener(v -> {
      String src = upSrc.getText().toString().trim();
      String task = upTask.getText().toString().trim();
      if (src.isEmpty() || task.isEmpty()) { upStatus.setText(getString(R.string.fill_path_task)); return; }
      upStatus.setText(getString(R.string.uploading));
      new Thread(() -> {
        String err = com.laya.DecisionCore.importPackage(getApplicationContext(), src, task);
        if (err == null && src.equals(new File(getCacheDir(), "picked-upload.zip").getAbsolutePath()))
          new File(getCacheDir(), "picked-upload.zip").delete(); // 选择器中转 zip 用完即删
        runOnUiThread(() -> {
          if (err == null) {
            upStatus.setText(getString(R.string.upload_ok, task));
            rebuildBizList();
          } else upStatus.setText("❌ " + err);
        });
      }).start();
    });
    l.addView(up);

    Button rescan = button(l, getString(R.string.rescan));
    rescan.setOnClickListener(v -> rebuildBizList());
    l.addView(hint(getString(R.string.biz_list_hint)));
    bizListPanel = new LinearLayout(this);
    bizListPanel.setOrientation(LinearLayout.VERTICAL);
    l.addView(bizListPanel);
    rebuildBizList();

    bizListPanel = new LinearLayout(this);
    bizListPanel.setOrientation(LinearLayout.VERTICAL);
    l.addView(bizListPanel);
    rebuildBizList();

    sysView = new TextView(this);
    sysView.setText(com.laya.DecisionCore.backendInfo(this) + getString(R.string.sys_tail));
    sysView.setTextSize(13);
    l.addView(sysView);

    // 泳道数据流独立成「数据流」子页(fig5 同构:一次决策请求的端到端路径)
    LinearLayout p3 = new LinearLayout(this);
    p3.setOrientation(LinearLayout.VERTICAL);
    p3.setPadding(dp(12), dp(8), dp(12), dp(8));
    p3.addView(hint(getString(R.string.flow_hint_data)));
    p3.addView(new DiagramView(this, 1));

    // ---- 子标签页:左侧竖排(系统/架构图/流程图/数据流)+ 显隐开关 ----
    LinearLayout p1 = new LinearLayout(this);
    p1.setOrientation(LinearLayout.VERTICAL);
    p1.setPadding(dp(12), dp(8), dp(12), dp(8));
    p1.addView(hint(getString(R.string.flow_hint_arch)));
    p1.addView(new DiagramView(this, 0));
    LinearLayout p2 = new LinearLayout(this);
    p2.setOrientation(LinearLayout.VERTICAL);
    p2.setPadding(dp(12), dp(8), dp(12), dp(8));
    p2.addView(hint(getString(R.string.flow_hint_proc)));
    p2.addView(new DiagramView(this, 3));

    String[] subNames = {getString(R.string.sub_sys), getString(R.string.sub_arch), getString(R.string.sub_flow), getString(R.string.sub_data)};
    LinearLayout[] subPanels = {l, p1, p2, p3};
    final ScrollView[] subScrolls = new ScrollView[4];
    for (int i = 0; i < 4; i++) {
      ScrollView sv = new ScrollView(this);
      sv.addView(subPanels[i]);
      subScrolls[i] = sv;
    }
    final FrameLayout subHolder = new FrameLayout(this);
    final Button[] chips = new Button[4];
    final LinearLayout rail = new LinearLayout(this);
    rail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 4; i++) {
      final int k = i;
      Button c = new Button(this);
      c.setText(subNames[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        subHolder.removeAllViews();
        subHolder.addView(subScrolls[k]);
        scroller = subScrolls[k];
        for (int j = 0; j < 4; j++) {
          chips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          chips[j].setBackground(pill(j == k ? PRIMARY : 0xFFE7EAF2, dp(12)));
        }
      });
      chips[i] = c;
      rail.addView(railChip(c));
    }
    final LinearLayout leftCol = new LinearLayout(this);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(dp(4), dp(4), dp(0), dp(0));
    ScrollView railScroll = new ScrollView(this);
    railScroll.addView(rail); // 左侧 tab 菜单可上下滑动(小屏防截断)
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(dp(40), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = dp(2);
    leftCol.setLayoutParams(lclp);
    // 显隐开关在标题栏左侧(☰),点击收起/展开左栏
    menuBtn.setOnClickListener(v -> {
      boolean show = leftCol.getVisibility() == View.GONE;
      leftCol.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    LinearLayout sysTop = new LinearLayout(this);
    sysTop.setOrientation(LinearLayout.HORIZONTAL);
    sysTop.addView(leftCol);
    subHolder.addView(subScrolls[0]);
    scroller = subScrolls[0];
    subHolder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    sysTop.addView(subHolder);
    sysTop.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.MATCH_PARENT));
    body.addView(sysTop);
    chips[0].performClick();
  }
  private LinearLayout bizListPanel;

  /** 单个业务卡片(加载态/操作按钮/详情/导出/删除) */
  private LinearLayout bizCard(String task, String label) {
    boolean loaded = new File(getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_wfp16.tflite").isFile()
        || new File(getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_npu.tflite").isFile();

    LinearLayout card = new LinearLayout(this);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(pill(loaded ? 0xFFEAF3FF : 0xFFF7F8FA, dp(10)));
    card.setPadding(dp(10), dp(8), dp(10), dp(10));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.bottomMargin = dp(8);
    card.setLayoutParams(clp);

    TextView head = new TextView(this);
    head.setText((loaded ? "✅ " : "📦 ") + label + "  [" + task + "]");
    head.setTextSize(14); head.setTypeface(Typeface.DEFAULT_BOLD); head.setTextColor(0xFF1A2B4C);
    card.addView(head);
    TextView st = new TextView(this);
    st.setText(getString(loaded ? R.string.loaded_state : R.string.not_loaded_state));
    st.setTextSize(11); st.setTextColor(0xFF66707E);
    st.setPadding(0, dp(2), 0, dp(4));
    card.addView(st);

    // 单按钮动态切换:未加载=加载(装入 app);已加载=卸载(释放空间)
    Button toggle = new Button(this);
    toggle.setText(getString(loaded ? R.string.uninstall : R.string.load_btn));
    toggle.setAllCaps(false); toggle.setTextSize(12);
    toggle.setTextColor(loaded ? 0xFF444A55 : Color.WHITE);
    toggle.setBackground(pill(loaded ? CHIP_OFF : PRIMARY, dp(14)));
    toggle.setPadding(dp(8), dp(6), dp(8), dp(6));
    toggle.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    toggle.setOnClickListener(v -> {
      if (loaded) {
        com.laya.DecisionCore.unload(this, task);
        rebuildBizList();
      } else {
        st.setText(getString(R.string.loading_model));
        new Thread(() -> {
          try { com.laya.DecisionCore.preload(this, task); runOnUiThread(() -> rebuildBizList()); }
          catch (Throwable e) { runOnUiThread(() -> st.setText(getString(R.string.load_failed, e.getMessage()))); }
        }).start();
      }
    });
    card.addView(toggle);

    // 模型详情(展开/收起)+ 导出 zip + 删除业务
    LinearLayout row2 = new LinearLayout(this);
    row2.setOrientation(LinearLayout.HORIZONTAL);
    Button detailBtn = new Button(this);
    detailBtn.setText(getString(R.string.detail_btn)); detailBtn.setAllCaps(false); detailBtn.setTextSize(12);
    detailBtn.setTextColor(0xFF444A55); detailBtn.setBackground(pill(CHIP_OFF, dp(14)));
    detailBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
    Button expBtn = new Button(this);
    expBtn.setText(getString(R.string.export_zip)); expBtn.setAllCaps(false); expBtn.setTextSize(12);
    expBtn.setTextColor(0xFF444A55); expBtn.setBackground(pill(CHIP_OFF, dp(14)));
    expBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
    Button delBtn = new Button(this);
    delBtn.setText(getString(R.string.delete_biz)); delBtn.setAllCaps(false); delBtn.setTextSize(12);
    delBtn.setTextColor(0xFFB3261E); delBtn.setBackground(pill(0xFFFCEAEA, dp(14)));
    delBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
    LinearLayout.LayoutParams half1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half1.rightMargin = dp(6);
    detailBtn.setLayoutParams(half1);
    LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    half2.rightMargin = dp(6);
    expBtn.setLayoutParams(half2);
    delBtn.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    row2.addView(detailBtn); row2.addView(expBtn); row2.addView(delBtn);
    row2.setPadding(0, dp(6), 0, 0);
    card.addView(row2);

    TextView detail = new TextView(this);
    detail.setText(modelDetail(task));
    detail.setTextSize(9);
    detail.setTypeface(Typeface.MONOSPACE);
    detail.setTextColor(0xFF444A55);
    detail.setBackground(pill(0xFFFFFFFF, dp(8)));
    detail.setPadding(dp(8), dp(6), dp(8), dp(6));
    detail.setVisibility(View.GONE);
    detail.setOnClickListener(v -> detail.setVisibility(View.GONE));
    card.addView(detail);
    detailBtn.setOnClickListener(v ->
        detail.setVisibility(detail.getVisibility() == View.GONE ? View.VISIBLE : View.GONE));

    expBtn.setOnClickListener(v -> {
      final File dst = new File("/sdcard/Download", "laya-litert-" + task + ".zip");
      expBtn.setText(getString(R.string.packing)); expBtn.setEnabled(false);
      new Thread(() -> {
        String err = null;
        try {
          dst.getParentFile().mkdirs();
          zipDir(srcDir(task), dst);
        } catch (Throwable e) {
          err = e.getMessage() != null ? e.getMessage() : e.toString();
          dst.delete();
        }
        final String ferr = err;
        runOnUiThread(() -> {
          expBtn.setText(getString(R.string.export_zip)); expBtn.setEnabled(true);
          if (ferr == null)
            new AlertDialog.Builder(this).setTitle(getString(R.string.export_done_title))
                .setMessage(getString(R.string.export_done_msg, dst.getAbsolutePath(), human(dst.length())))
                .setPositiveButton(getString(R.string.ok_btn), null).show();
          else
            new AlertDialog.Builder(this).setTitle(getString(R.string.export_failed_title)).setMessage(ferr).setPositiveButton(getString(R.string.ok_btn), null).show();
        });
      }).start();
    });

    delBtn.setOnClickListener(v -> {
      String srcPath = srcDir(task).getAbsolutePath();
      new AlertDialog.Builder(this)
          .setTitle(getString(R.string.delete_title, task))
          .setMessage(getString(R.string.delete_msg, srcPath, task))
          .setNegativeButton(getString(R.string.cancel), null)
          .setPositiveButton(getString(R.string.delete_btn), (d, w) -> {
            com.laya.DecisionCore.unload(this, task);
            new Thread(() -> {
              deleteQuiet(srcDir(task).getParentFile());
              deleteQuiet(new File(getFilesDir(), "laya-" + task));
              runOnUiThread(() -> rebuildBizList());
            }).start();
          }).show();
    });

    return card;
  }

  /** 局部刷新:仅重建业务卡片列表(替代整页 setTab(3) 重建,避免闪烁) */
  private void rebuildBizList() {
    refreshTasks();
    if (bizListPanel == null) return;
    bizListPanel.removeAllViews();
    for (int i = 0; i < taskIds.size(); i++) bizListPanel.addView(bizCard(taskIds.get(i), taskLabels.get(i)));
  }

  // ================= 通用 =================
  private void bot(String t) { bubble(t, false); }
  private void bubble(String text, boolean user) {
    if (msgList == null) return;
    TextView tv = new TextView(this);
    tv.setText(text); tv.setTextSize(14);
    tv.setTextColor(user ? 0xFF1A2B4C : 0xFF22262E);
    tv.setMaxWidth(dp(280));
    tv.setBackground(pill(user ? USER_BG : BOT_BG, dp(8)));
    tv.setPadding(dp(13), dp(9), dp(13), dp(9));
    LinearLayout row = new LinearLayout(this);
    row.setGravity(user ? Gravity.END : Gravity.START);
    row.setPadding(0, dp(4), 0, dp(4));
    row.addView(tv);
    msgList.addView(row);
    scroller.post(() -> scroller.fullScroll(View.FOCUS_DOWN));
  }
}
