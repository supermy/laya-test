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
import android.widget.PopupWindow;
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
    for (kotlin.Pair<String, String> t : com.laya.DecisionCore.scanTasks()) {
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
    refreshTasks();
    buildUi();
    // LLM 升级完成回调:决策页气泡展示风险分析(邮件/MQTT 渠道触发的也在此显示)
    com.laya.DecisionCore.setLlmListener((task, content, err) -> runOnUiThread(() -> {
      if (err != null) bot("🤖 LLM 升级失败 [" + task + "]: " + err);
      else if (!content.isEmpty()) bot("🤖 LLM 风险分析 [" + task + "]\n" + content);
    }));
    // 恢复内置网关(仅配置了 enabled 时)
    if (com.laya.Gateway.cfg(this).optBoolean("enabled")) {
      startForegroundService(new Intent(this, com.laya.GatewayService.class));
      com.laya.Gateway.autoStart(this);
    }
    bot("Laya 业务决策台已就绪。\n" + taskLabels + "\n端侧 LiteRT GPU 推理;报表/邮件/MQTT 网关内置。\n新业务:模型包放 /sdcard/models/laya-litert-<名>/phone/,系统页重扫即加载。");
    handleIntent(getIntent() != null ? getIntent() : null);
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
    title.setText("智能决策业务台");
    title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
    title.setTextColor(0xFF1A1A1A);
    title.setPadding(0, dp(10), 0, dp(10));
    titleHolder.addView(title);
    titleHolder.addView(menuBtn);
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
    String[] names = {"决策", "报表", "网关", "系统"};
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
    upStatus.setText("读取所选 zip…");
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
          upStatus.setText("已选择 " + fname + "(" + mb + "MB),点「上传并注册」完成校验");
        });
      } catch (Throwable e) {
        runOnUiThread(() -> upStatus.setText("❌ 读取失败: " + e.getMessage()));
      }
    }).start();
  }


  private void refreshGatewayBar() {
    if (gwBarText != null) gwBarText.setText("网关: " + com.laya.Gateway.status(this));
  }

  private void setTab(int k) {
    tab = k;
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
  private TextView taskMenuBtn;

  /** 左侧浮动弹出:业务二级菜单 */
  private void showTaskMenu(View anchor) {
    LinearLayout menu = new LinearLayout(this);
    menu.setOrientation(LinearLayout.VERTICAL);
    menu.setBackground(pill(Color.WHITE, dp(14)));
    menu.setPadding(dp(6), dp(6), dp(6), dp(6));
    for (int i = 0; i < taskIds.size(); i++) {
      final int k = i;
      TextView it = new TextView(this);
      it.setText(taskLabels.get(i)); it.setTextSize(14);
      it.setPadding(dp(18), dp(12), dp(18), dp(12));
      it.setOnClickListener(v -> { taskIdx = k; paintChips(); dismissTaskMenu(); loadHistory(taskIds.get(k)); });
      LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      lp.leftMargin = dp(2); lp.rightMargin = dp(2);
      it.setLayoutParams(lp);
      menu.addView(it);
    }
    PopupWindow pw = new PopupWindow(menu, dp(150), LinearLayout.LayoutParams.WRAP_CONTENT, true);
    pw.setBackgroundDrawable(pill(Color.WHITE, dp(14)));
    pw.setElevation(dp(6));
    taskMenuBtn.setTag(pw);
    pw.showAsDropDown(anchor, 0, dp(4));
  }

  private void dismissTaskMenu() {
    if (taskMenuBtn != null && taskMenuBtn.getTag() instanceof PopupWindow) {
      ((PopupWindow) taskMenuBtn.getTag()).dismiss();
    }
  }

  private void buildDecisionTab() {
    // 左侧浮动业务菜单按钮
    LinearLayout row = new LinearLayout(this);
    row.setPadding(dp(12), dp(8), dp(12), dp(4));
    row.setGravity(Gravity.CENTER_VERTICAL);
    taskMenuBtn = new TextView(this);
    taskMenuBtn.setText("☰ " + taskLabels.get(taskIdx));
    taskMenuBtn.setTextSize(13); taskMenuBtn.setTextColor(Color.WHITE);
    taskMenuBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
    taskMenuBtn.setBackground(pill(PRIMARY, dp(18)));
    taskMenuBtn.setOnClickListener(v -> showTaskMenu(v));
    row.addView(taskMenuBtn);
    TextView cur = new TextView(this);
    cur.setText("当前业务:" + taskLabels.get(taskIdx)); cur.setTextSize(12); cur.setTextColor(0xFF66707E);
    cur.setPadding(dp(10), 0, 0, 0);
    row.addView(cur);
    body.addView(row);
    paintChips();

    msgList = new LinearLayout(this);
    msgList.setOrientation(LinearLayout.VERTICAL);
    msgList.setPadding(dp(12), dp(6), dp(12), dp(6));
    scroller = new ScrollView(this);
    scroller.addView(msgList);
    body.addView(scroller, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    loadHistory(taskIds.get(taskIdx));


    LinearLayout bottom = new LinearLayout(this);
    bottom.setOrientation(LinearLayout.HORIZONTAL);
    bottom.setGravity(Gravity.CENTER_VERTICAL);
    bottom.setPadding(dp(12), dp(8), dp(12), dp(8));
    bottom.setBackgroundColor(Color.WHITE);
    input = new EditText(this);
    input.setHint("输入" + taskLabels.get(taskIdx) + "文本"); input.setTextSize(14); input.setMaxLines(3);
    input.setBackground(pill(Color.WHITE, dp(22)));
    input.setPadding(dp(14), dp(10), dp(14), dp(10));
    bottom.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button send = new Button(this);
    send.setText("决策"); send.setTextColor(Color.WHITE); send.setAllCaps(false);
    send.setBackground(pill(PRIMARY, dp(22)));
    LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    slp.leftMargin = dp(8); send.setLayoutParams(slp);
    send.setOnClickListener(v -> sendDecision(input.getText().toString()));
    bottom.addView(send);
    body.addView(bottom);
    loadHistory(taskIds.get(taskIdx));
  }

  /** 业务↔日志联动:切换业务时,聊天区载入该业务的历史决策 */
  private void loadHistory(String task) {
    if (msgList == null) return;
    msgList.removeAllViews();
    bot("「" + taskLabels.get(taskIds.indexOf(task)) + "」历史决策(本机日志,最近 20 条):");
    java.util.List<org.json.JSONObject> hs = com.laya.DecisionCore.history(this, task, 20);
    if (hs.isEmpty()) bot("(暂无历史,输入文本或等网关指令)");
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
      if (!c.isEmpty()) bot("🤖 最近 LLM 风险分析 · " + when + "\n" + c);
      else if (llm.has("error")) bot("🤖 最近 LLM 升级失败(" + when + "): " + llm.optString("error"));
    }
    scroller.post(() -> scroller.scrollTo(0, scroller.getHeight()));
  }

  private void paintChips() {
    if (taskMenuBtn != null) taskMenuBtn.setText("☰ " + taskLabels.get(taskIdx));
    if (input != null) input.setHint("输入" + taskLabels.get(taskIdx) + "文本");
  }

  private void sendDecision(String raw) {
    final String text = raw == null ? "" : raw.trim();
    if (text.isEmpty() || busy) return;
    busy = true;
    input.setText("");
    bubble(text, true);
    bubble("推理中…", false);
    final int ti = taskIdx;
    new Thread(() -> {
      String reply;
      try {
        com.laya.DecisionCore.Result r = com.laya.DecisionCore.decide(this, taskIds.get(ti), text);
        reply = fmtAnswers(taskIds.get(ti), r.answers, (int) r.latencyMs) + "\n后端: " + com.laya.DecisionCore.currentEngine();
      } catch (Throwable e) {
        android.util.Log.e("LayaApp", "decision failed", e);
        reply = "推理失败: " + e.getClass().getSimpleName() + ": " + e.getMessage();
      }
      final String r2 = reply;
      runOnUiThread(() -> { bubble(r2, false); busy = false; });
    }).start();
  }

  private String fmtAnswers(String task, org.json.JSONObject decoded, int ms) {
    StringBuilder sb = new StringBuilder("== ").append(taskLabels.get(taskIds.indexOf(task))).append(" 决策结果 ==\n");
    java.util.Iterator<String> it = decoded.keys();
    while (it.hasNext()) {
      org.json.JSONObject a = decoded.optJSONObject(it.next());
      if (a == null) continue;
      String type = a.optString("type");
      if ("choice".equals(type)) sb.append("• ").append(a.optString("choice")).append("\n");
      else if ("score".equals(type)) sb.append("• 评分: ").append(String.format("%.2f", a.optDouble("score"))).append("/5\n");
      else if ("noul".equals(type)) sb.append("• 判定: ").append(a.optDouble("noul") >= 0.5 ? "是" : "否").append("\n");
    }
    sb.append("\n耗时: ").append(ms).append("ms");
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
    cur.setText("报表来自本机决策日志,左侧 tab 切换"); cur.setTextSize(12); cur.setTextColor(0xFF66707E);
    cur.setPadding(dp(2), 0, 0, 0);
    l.addView(cur);

    // 详单翻页行(仅详单显示)
    pagerRow = new LinearLayout(this);
    pagerRow.setOrientation(LinearLayout.HORIZONTAL);
    pagerRow.setGravity(Gravity.CENTER_VERTICAL);
    pagerRow.setPadding(0, dp(4), 0, dp(4));
    Button prev = new Button(this);
    prev.setText("◀ 上一页"); prev.setAllCaps(false); prev.setTextSize(12);
    prev.setOnClickListener(v -> { if (detailPage > 0) { detailPage--; renderReport(3); } });
    prev.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(prev);
    pagerLabel = new TextView(this);
    pagerLabel.setTextSize(12); pagerLabel.setGravity(Gravity.CENTER);
    pagerLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(pagerLabel);
    Button next = new Button(this);
    next.setText("下一页 ▶"); next.setAllCaps(false); next.setTextSize(12);
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
    spinRange.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"近7天", "今天", "近30天", "全部"}));
    spinRange.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinRange);
    spinLevel = new Spinner(this);
    spinLevel.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"全部等级", "高", "中", "低"}));
    spinLevel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinLevel);
    spinTask = new Spinner(this);
    java.util.List<String> tOpts = new ArrayList<>();
    tOpts.add("全部业务");
    for (int i = 0; i < taskIds.size(); i++) tOpts.add(taskLabels.get(i) + " (" + taskIds.get(i) + ")");
    spinTask.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, tOpts));
    spinTask.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
    fRow1.addView(spinTask);
    drillPane.addView(fRow1);
    Button qBtn = new Button(this);
    qBtn.setText("查询(点下方数字下钻)");
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

    TextView tv = new TextView(this);
    tv.setTextSize(13); tv.setPadding(0, dp(10), 0, 0);
    l.addView(tv);
    scroller = new ScrollView(this);
    scroller.addView(l);
    reportView = tv;
    renderReport(reportKind);

    // ---- 左侧竖排 tab(与系统页同款):日报/月报/年报/详单/下钻详单 ----
    final FrameLayout holder = new FrameLayout(this);
    holder.addView(scroller);
    holder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] kinds = {"日报", "月报", "年报", "详单", "下钻详单"};
    String[] vert = {"日\n报", "月\n报", "年\n报", "详\n单", "下\n钻\n详\n单"};
    final Button[] chips = new Button[5];
    final LinearLayout rail = new LinearLayout(this);
    rail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 5; i++) {
      final int k = i;
      Button c = new Button(this);
      c.setText(vert[i]); c.setAllCaps(false); c.setTextSize(13);
      c.setPadding(dp(2), dp(10), dp(2), dp(10));
      c.setMinHeight(0); c.setMinimumHeight(0);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.bottomMargin = dp(4); c.setLayoutParams(clp);
      c.setOnClickListener(v -> {
        reportKind = k;
        renderReport(k);
        for (int j = 0; j < 5; j++) {
          chips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          chips[j].setBackground(pill(j == k ? PRIMARY : 0xFFE7EAF2, dp(12)));
        }
      });
      chips[i] = c;
      rail.addView(c);
    }
    final LinearLayout leftCol = new LinearLayout(this);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(dp(4), dp(4), dp(0), dp(0));
    leftCol.addView(rail);
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
  private TextView reportView;
  private LinearLayout drillPane, drillTables, drillOut;
  private Spinner spinTask, spinLevel, spinRange;

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
    final String level = lp == 0 ? null : (String) spinLevel.getSelectedItem();
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
    head.setText("共 " + piv.optInt("total") + " 条 · ①业务×等级 ②按日期,点数字下钻");
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
    h1.addView(dCell("业务", true, 0, null, 2.2f));
    for (String lv : lvOrd) h1.addView(dCell(lv, true, 0, null, 1f));
    h1.addView(dCell("合计", true, 0, null, 1f));
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
    h2.addView(dCell("日期", true, 0, null, 1.6f));
    for (String t : tasks) h2.addView(dCell(t, true, 0, null, 1f));
    h2.addView(dCell("合计", true, 0, null, 1f));
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
    loading.setText("详单加载中…"); loading.setTextSize(12); loading.setPadding(dp(4), dp(8), 0, 0);
    drillOut.addView(loading);
    final android.app.Activity act = this;
    new Thread(() -> {
      final JSONArray es = com.laya.DecisionCore.drillList(act, date, task, level, 200);
      runOnUiThread(() -> {
        drillOut.removeAllViews();
        java.util.Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < taskIds.size(); i++) labels.put(taskIds.get(i), taskLabels.get(i));
        TextView title = new TextView(this);
        title.setText("== 详单 " + (date != null ? date : "区间") + " · "
            + (task != null ? labels.getOrDefault(task, task) : "全部业务") + " · "
            + (level != null ? level : "全部等级") + " · " + es.length() + " 条 ==");
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
          empty.setText("(无记录)"); empty.setTextSize(12); empty.setTextColor(0xFF66707E);
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
      reportView.setVisibility(isDrill ? View.GONE : View.VISIBLE);
      drillPane.setVisibility(isDrill ? View.VISIBLE : View.GONE);
    });
    if (isDrill) { runOnUiThread(this::renderDrill); return; }
    new Thread(() -> {
      String s0;
      try {
        if (isDetail) {
          org.json.JSONObject d = com.laya.DecisionCore.detail(this, detailPage, 20);
          detailPages = d.optInt("pages", 1);
          s0 = d.optString("text");
          final String fin = s0;
          final int pg = d.optInt("page", 1), pgs = d.optInt("pages", 1);
          runOnUiThread(() -> {
            reportView.setText(fin);
            pagerLabel.setText("第 " + pg + " / " + pgs + " 页");
          });
          return;
        }
        s0 = com.laya.DecisionCore.report(this, kind);
      } catch (Exception e) { s0 = "生成失败: " + e.getMessage(); }
      final String s = s0;
      runOnUiThread(() -> reportView.setText(s));
    }).start();
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
    l.addView(hint("邮件网关(IMAP 拉取决策指令 → SMTP 回复;主题或正文写 \"laya <业务> <文本>\")"));
    gwEmailHost = fieldU(l, "服务器(本机测试: 127.0.0.1 / 生产: imap.qq.com)");
    gwEmailUser = fieldU(l, "邮箱账号");
    gwEmailPass = fieldU(l, "授权码/密码");
    LinearLayout pr = new LinearLayout(this);
    pr.setOrientation(LinearLayout.HORIZONTAL);
    gwImapPort = fieldU(pr, "IMAP 端口", "143");
    gwSmtpPort = fieldU(pr, "SMTP 端口", "25");
    LinearLayout.LayoutParams plp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    plp1.rightMargin = dp(8); gwImapPort.setLayoutParams(plp1);
    gwSmtpPort.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    l.addView(pr);
    gwSsl = new CheckBox(this);
    gwSsl.setText("SSL(993/465,生产邮箱勾选;本地测试不勾)");
    gwSsl.setTextSize(13); gwSsl.setPadding(0, dp(6), 0, dp(6));
    l.addView(gwSsl);
    gwReportTo = fieldU(l, "日报收件箱(可空,如 boss@localhost)");
    Button emailBtn = button(l, "保存并启动邮件网关");
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
      } catch (Exception e) { gwStatus.setText("配置失败: " + e.getMessage()); }
    });
    Button emailTestBtn = button(l, "测试 IMAP 收件箱");
    emailTestBtn.setOnClickListener(v -> {
      gwStatus.setText("IMAP 测试中…");
      new Thread(() -> {
        String r;
        try {
          r = com.laya.Gateway.testEmail(new JSONObject()
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
    Button smtpTestBtn = button(l, "测试 SMTP 发信(发到日报收件箱)");
    smtpTestBtn.setOnClickListener(v -> {
      gwStatus.setText("SMTP 测试中…");
      new Thread(() -> {
        String r;
        try {
          JSONObject ec = new JSONObject()
              .put("host", gwEmailHost.getText().toString())
              .put("user", gwEmailUser.getText().toString())
              .put("pass", gwEmailPass.getText().toString())
              .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
              .put("ssl", gwSsl.isChecked());
          r = com.laya.Gateway.testSmtp(ec, gwReportTo.getText().toString());
        } catch (Exception e) { r = "❌ SMTP: " + e.getMessage(); }
        final String fr = r;
        runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    LinearLayout pMq = new LinearLayout(this);
    pMq.setOrientation(LinearLayout.VERTICAL);
    pMq.setPadding(dp(12), dp(8), dp(12), dp(8));
    l = pMq;
    l.addView(hint("消息队列(MQTT):订阅 laya/req/+ → 决策 → 发布 laya/resp"));
    gwMqUrl = fieldU(l, "MQTT Broker,如 tcp://192.168.0.168:1883");
    gwMqSub = fieldU(l, "订阅主题", "laya/req/+");
    gwMqPub = fieldU(l, "发布主题", "laya/resp");
    Button mqBtn = button(l, "保存并启动 MQTT 网关");
    mqBtn.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(getApplicationContext());
        cfg.put("enabled", true);
        cfg.put("mqtt", new JSONObject().put("enabled", true).put("url", gwMqUrl.getText().toString()));
        cfg.put("topics", new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString()));
        gwStatus.setText(com.laya.Gateway.saveAndStart(getApplicationContext(), cfg));
      } catch (Exception e) { gwStatus.setText("配置失败: " + e.getMessage()); }
    });
    Button mqTestBtn = button(l, "测试 MQTT(连接+订阅+发布)");
    mqTestBtn.setOnClickListener(v -> {
      gwStatus.setText("MQTT 测试中…");
      new Thread(() -> {
        String r;
        try {
          JSONObject mc = new JSONObject().put("url", gwMqUrl.getText().toString());
          JSONObject tc = new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString());
          r = com.laya.Gateway.testMqtt(mc, tc);
        } catch (Exception e) { r = "❌ MQTT: " + e.getMessage(); }
        final String fr = r;
        runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    l.addView(hint("模型包上传(局域网):浏览器打开 " + com.laya.Gateway.uploadUrl()
        + " 提交 zip+业务名;或 curl -X POST --data-binary @pkg.zip \"" + com.laya.Gateway.uploadUrl() + "/upload?task=名字\""));
    Button upTestBtn = button(l, "测试上传服务");
    upTestBtn.setOnClickListener(v -> {
      gwStatus.setText("上传服务测试中…");
      new Thread(() -> {
        final String r = com.laya.Gateway.testUpload();
        runOnUiThread(() -> gwStatus.setText(r));
      }).start();
    });

    // ---- LLM 设置(重要+紧急升级通道,3 槽位供可选) ----
    LinearLayout pLlm = new LinearLayout(this);
    pLlm.setOrientation(LinearLayout.VERTICAL);
    pLlm.setPadding(dp(12), dp(8), dp(12), dp(8));
    l = pLlm;
    l.addView(hint("LLM 设置(决策后处理重要+紧急业务,3 个供可选;OpenAI 兼容 /chat/completions)"));
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
      llmRb[i].setText("启用"); llmRb[i].setTextSize(12);
      llmRb[i].setChecked(slotIds[i].equals(actId));
      head.addView(llmRb[i]);
      llmName[i] = fieldU(head, "名称");
      JSONObject fs = s;
      String nm = s != null ? s.optString("name", defNames[i]) : defNames[i];
      llmName[i].setText(nm);
      llmName[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      slot.addView(head);
      llmUrl[i] = fieldU(slot, "Base URL(OpenAI 兼容)");
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
    Button llmSave = button(l, "保存 LLM 设置");
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
      } catch (Exception e) { gwStatus.setText("LLM 配置失败: " + e.getMessage()); }
    });
    Button llmTest = button(l, "测试选中的 LLM(发一条 ping)");
    llmTest.setOnClickListener(v -> {
      try {
        int checked = -1;
        for (int i = 0; i < 3; i++) if (llmRb[i].isChecked()) checked = i;
        if (checked < 0) { gwStatus.setText("请先勾选一个 LLM"); return; }
        final String url = llmUrl[checked].getText().toString().trim().replaceAll("/+$", "");
        final String model = llmModel[checked].getText().toString().trim();
        final String key = llmKey[checked].getText().toString().trim();
        final String nm = llmName[checked].getText().toString();
        gwStatus.setText("测试 " + nm + " …");
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
              r = "✅ " + nm + " 连通(" + code + ")回复: " + txt.trim();
            } else r = "❌ " + nm + " HTTP " + code + ": " + body.substring(0, Math.min(160, body.length()));
          } catch (Exception e) { r = "❌ " + nm + " 失败: " + e.getMessage(); }
          final String fr = r;
          runOnUiThread(() -> gwStatus.setText(fr));
        }).start();
      } catch (Exception e) { gwStatus.setText("测试失败: " + e.getMessage()); }
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
    String[] gVert = {"邮\n件", "队\n列", "L\nL\nM"};
    final Button[] gchips = new Button[3];
    final LinearLayout grail = new LinearLayout(this);
    grail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 3; i++) {
      final int k = i;
      Button c = new Button(this);
      c.setText(gVert[i]); c.setAllCaps(false); c.setTextSize(13);
      c.setPadding(dp(2), dp(10), dp(2), dp(10));
      c.setMinHeight(0); c.setMinimumHeight(0);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.bottomMargin = dp(4); c.setLayoutParams(clp);
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
      grail.addView(c);
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
    Button stopBtn = button(gBottom, "停止全部网关");
    stopBtn.setOnClickListener(v -> gwStatus.setText(com.laya.Gateway.stop(getApplicationContext())));
    gwStatus = hint("网关: " + com.laya.Gateway.status(this));
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
    parent.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
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
  private static void zipDir(File dir, File dst) throws Exception {
    File[] files = dir.listFiles();
    if (files == null || files.length == 0) throw new IllegalStateException("源包为空或不存在: " + dir);
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
      } else sb.append("✗ ").append(f).append("  缺\n");
    }
    String main = npu != null && gpu != null ? "NPU dispatch(" + npu + ")+GPU wfp16(" + gpu + ")"
        : npu != null ? "NPU dispatch(" + npu + ")(纯 NPU 包)" : "GPU wfp16(" + gpu + ")(无 NPU 主图)";
    sb.insert(0, "格式: " + main + "\n齐全: " + have + "/8 · 共 " + human(total)
        + "\n源包: " + src.getAbsolutePath() + "\n");
    long it = 0; int ih = 0;
    for (String f : MODEL_FILES) { File d = new File(inst, f); if (d.isFile()) { ih++; it += d.length(); } }
    sb.append("装入副本: ").append(ih == 0 ? "未装入" : ih + "/8 文件 · " + human(it));
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

    // ---- 手动上传模型包输入界面 ----
    LinearLayout up = new LinearLayout(this);
    up.setOrientation(LinearLayout.VERTICAL);
    up.setBackground(pill(0xFFF0F4FF, dp(10)));
    up.setPadding(dp(10), dp(8), dp(10), dp(10));
    LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    ulp.bottomMargin = dp(10);
    up.setLayoutParams(ulp);
    TextView upHead = new TextView(this);
    upHead.setText("⬆ 手动上传模型包"); upHead.setTextSize(14); upHead.setTypeface(Typeface.DEFAULT_BOLD);
    up.addView(upHead);
    upSrc = fieldU(up, "包路径(目录或 zip;可点下方按钮选择)");
    Button pickBtn = button(up, "📁 选择 zip 文件…");
    pickBtn.setOnClickListener(v -> {
      Intent it = new Intent(Intent.ACTION_OPEN_DOCUMENT);
      it.addCategory(Intent.CATEGORY_OPENABLE);
      it.setType("*/*");
      it.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/zip", "application/x-zip-compressed", "application/octet-stream"});
      startActivityForResult(it, REQ_PICK_ZIP);
    });
    upTask = fieldU(up, "业务名(英文,如 demo)");
    upStatus = new TextView(this);
    upStatus.setTextSize(11); upStatus.setTextColor(0xFF66707E);
    upStatus.setText("选择 zip 或手填路径;六文件校验通过后注册为新业务,zip 内路径任意");
    up.addView(upStatus);
    Button impBtn = button(up, "上传并注册");
    impBtn.setOnClickListener(v -> {
      String src = upSrc.getText().toString().trim();
      String task = upTask.getText().toString().trim();
      if (src.isEmpty() || task.isEmpty()) { upStatus.setText("请填包路径和业务名"); return; }
      upStatus.setText("上传中…(zip 约 250MB,校验六文件)");
      new Thread(() -> {
        String err = com.laya.DecisionCore.importPackage(getApplicationContext(), src, task);
        if (err == null && src.equals(new File(getCacheDir(), "picked-upload.zip").getAbsolutePath()))
          new File(getCacheDir(), "picked-upload.zip").delete(); // 选择器中转 zip 用完即删
        runOnUiThread(() -> {
          if (err == null) {
            upStatus.setText("✅ 上传成功,已注册业务 [" + task + "]");
            setTab(3);
          } else upStatus.setText("❌ " + err);
        });
      }).start();
    });
    l.addView(up);

    Button rescan = button(l, "⟳ 重新扫描业务");
    rescan.setOnClickListener(v -> { refreshTasks(); setTab(3); });
    l.addView(hint("已注册业务列表;加载=装入 app 可决策;模型详情=展开八文件清单(点清单收起);删除=移除源包与副本(不可恢复)"));

    for (int i = 0; i < taskIds.size(); i++) {
      final String task = taskIds.get(i);
      final String label = taskLabels.get(i);
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
      st.setText(loaded ? "已装入 app(约 650MB)· 可决策 / 收网关指令" : "未装入 · 决策时自动安装,或点下方[加载]");
      st.setTextSize(11); st.setTextColor(0xFF66707E);
      st.setPadding(0, dp(2), 0, dp(4));
      card.addView(st);

      // 单按钮动态切换:未加载=加载(装入 app);已加载=卸载(释放空间)
      Button toggle = new Button(this);
      toggle.setText(loaded ? "卸载(释放空间)" : "加载(装入 app)");
      toggle.setAllCaps(false); toggle.setTextSize(12);
      toggle.setTextColor(loaded ? 0xFF444A55 : Color.WHITE);
      toggle.setBackground(pill(loaded ? CHIP_OFF : PRIMARY, dp(14)));
      toggle.setPadding(dp(8), dp(6), dp(8), dp(6));
      toggle.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
      toggle.setOnClickListener(v -> {
        if (loaded) {
          com.laya.DecisionCore.unload(this, task);
          setTab(3);
        } else {
          st.setText("加载中…(拷贝 650MB + GPU 编译,约 1-2 分钟)");
          new Thread(() -> {
            try { com.laya.DecisionCore.preload(this, task); runOnUiThread(() -> setTab(3)); }
            catch (Throwable e) { runOnUiThread(() -> st.setText("加载失败: " + e.getMessage())); }
          }).start();
        }
      });
      card.addView(toggle);

      // 模型详情(展开/收起)+ 导出 zip + 删除业务
      LinearLayout row2 = new LinearLayout(this);
      row2.setOrientation(LinearLayout.HORIZONTAL);
      Button detailBtn = new Button(this);
      detailBtn.setText("模型详情"); detailBtn.setAllCaps(false); detailBtn.setTextSize(12);
      detailBtn.setTextColor(0xFF444A55); detailBtn.setBackground(pill(CHIP_OFF, dp(14)));
      detailBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
      Button expBtn = new Button(this);
      expBtn.setText("导出 zip"); expBtn.setAllCaps(false); expBtn.setTextSize(12);
      expBtn.setTextColor(0xFF444A55); expBtn.setBackground(pill(CHIP_OFF, dp(14)));
      expBtn.setPadding(dp(4), dp(6), dp(4), dp(6));
      Button delBtn = new Button(this);
      delBtn.setText("删除业务"); delBtn.setAllCaps(false); delBtn.setTextSize(12);
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
        expBtn.setText("打包中…"); expBtn.setEnabled(false);
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
            expBtn.setText("导出 zip"); expBtn.setEnabled(true);
            if (ferr == null)
              new AlertDialog.Builder(this).setTitle("导出完成")
                  .setMessage(dst.getAbsolutePath() + "\n大小 " + human(dst.length()) + "\n可在系统页「上传并注册」重新导入,或传到其他设备")
                  .setPositiveButton("好", null).show();
            else
              new AlertDialog.Builder(this).setTitle("导出失败").setMessage(ferr).setPositiveButton("好", null).show();
          });
        }).start();
      });

      delBtn.setOnClickListener(v -> {
        String srcPath = srcDir(task).getAbsolutePath();
        new AlertDialog.Builder(this)
            .setTitle("删除业务 [" + task + "]")
            .setMessage("将删除源包 " + srcPath + " 与装入副本(files/laya-" + task + "),不可恢复。确定删除?")
            .setNegativeButton("取消", null)
            .setPositiveButton("删除", (d, w) -> {
              com.laya.DecisionCore.unload(this, task);
              new Thread(() -> {
                deleteQuiet(srcDir(task).getParentFile());
                deleteQuiet(new File(getFilesDir(), "laya-" + task));
                runOnUiThread(() -> { refreshTasks(); setTab(3); });
              }).start();
            }).show();
      });
      l.addView(card);
    }

    sysView = new TextView(this);
    sysView.setText(com.laya.DecisionCore.backendInfo(this) + "\n网关状态见顶部状态栏");
    sysView.setTextSize(13);
    l.addView(sysView);

    // 泳道数据流独立成「数据流」子页(fig5 同构:一次决策请求的端到端路径)
    LinearLayout p3 = new LinearLayout(this);
    p3.setOrientation(LinearLayout.VERTICAL);
    p3.setPadding(dp(12), dp(8), dp(12), dp(8));
    p3.addView(hint("数据流泳道:一次决策请求的端到端路径 — 业务输入 → 引擎链(NPU→GPU→CPU)→ 主图+scorer → 分流(60.9% 本端 / 39.1% 升级 LLM)"));
    p3.addView(new DiagramView(this, 1));

    // ---- 子标签页:左侧竖排(系统/架构图/流程图/数据流)+ 显隐开关 ----
    LinearLayout p1 = new LinearLayout(this);
    p1.setOrientation(LinearLayout.VERTICAL);
    p1.setPadding(dp(12), dp(8), dp(12), dp(8));
    p1.addView(hint("分层决策架构:云端微调闭环 + LLM 升级通道(与 README fig2 同构)"));
    p1.addView(new DiagramView(this, 0));
    LinearLayout p2 = new LinearLayout(this);
    p2.setOrientation(LinearLayout.VERTICAL);
    p2.setPadding(dp(12), dp(8), dp(12), dp(8));
    p2.addView(hint("业务流程:决策完成 → 本端自动处理 / LLM 进一步处理(重要+紧急)→ 日志回流 → 微调闭环(与 README fig4 同构)"));
    p2.addView(new DiagramView(this, 3));

    String[] subNames = {"系统", "架构图", "流程图", "数据流"};
    String[] subVert = {"系\n统", "架\n构\n图", "流\n程\n图", "数\n据\n流"}; // 竖排文字
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
      c.setText(subVert[i]); c.setAllCaps(false); c.setTextSize(13);
      c.setPadding(dp(2), dp(10), dp(2), dp(10));
      c.setMinHeight(0); c.setMinimumHeight(0);
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.bottomMargin = dp(4); c.setLayoutParams(clp);
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
      rail.addView(c);
    }
    final LinearLayout leftCol = new LinearLayout(this);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(dp(4), dp(4), dp(0), dp(0));
    leftCol.addView(rail);
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
