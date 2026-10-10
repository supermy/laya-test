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
  static final int PRIMARY = 0xFF3E7BFA;
  static final int USER_BG = 0xFF95EC69;   // 微信绿气泡
  static final int BOT_BG = 0xFFFFFFFF;     // 白色气泡
  static final int CHIP_OFF = 0xFFF0F1F5;
  private static final int WX_PAGE_BG = 0xFFF5F5F5; // 页面浅灰底
  private static final int WX_GREEN = 0xFF07C160;   // 微信选中绿
  static final int REQ_PICK_ZIP = 41;       // SAF 选 zip 返回码

  // ---- 业务注册表(动态:内置四业务 + /sdcard 上传的扩展包,系统页重扫生效) ----
  final java.util.ArrayList<String> taskIds = new java.util.ArrayList<>();
  final java.util.ArrayList<String> taskLabels = new java.util.ArrayList<>();

  void refreshTasks() {
    taskIds.clear(); taskLabels.clear();
    for (kotlin.Pair<String, String> t : com.laya.DecisionCore.scanTasks(this)) {
      taskIds.add(t.getFirst()); taskLabels.add(t.getSecond());
    }
    if (taskIdx >= taskIds.size()) taskIdx = 0;
  }
  private final DecisionPage decisionPage = new DecisionPage(this);
  private final SystemPage systemPage = new SystemPage(this);
  private final GatewayPage gatewayPage = new GatewayPage(this);
  private int tab = 0; // 0决策 1报表 2网关 3系统
  int taskIdx = 0;
  LinearLayout body;
  Button menuBtn;
  ScrollView scroller;
  private TextView[] tabBtns = new TextView[4];
  private static final boolean USE_GPU_MAIN = true; // GPU 主图;失败自动降级 CPU

  @Override
  public void onCreate(Bundle b) {
    super.onCreate(b);
    final int savedTab = getSharedPreferences("ui", MODE_PRIVATE).getInt("tab", 0); // recreate/切语言后回到原 tab
    refreshTasks();
    buildUi();
    // LLM 升级完成回调:决策页气泡展示风险分析(邮件/MQTT 渠道触发的也在此显示)
    com.laya.DecisionCore.setLlmListener((task, content, err) -> runOnUiThread(() -> {
      if (err != null) decisionPage.bot(getString(R.string.llm_fail_msg, task, err));
      else if (!content.isEmpty()) decisionPage.bot(getString(R.string.llm_analysis_msg, task, content));
    }));
    // 恢复内置网关(仅配置了 enabled 时)
    if (com.laya.Gateway.cfg(this).optBoolean("enabled")) {
      startForegroundService(new Intent(this, com.laya.GatewayService.class));
      com.laya.Gateway.autoStart(this);
    }
    decisionPage.bot(getString(R.string.ready_msg, taskLabels));
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

  String uiLocale() { return getSharedPreferences("ui", MODE_PRIVATE).getString("locale", "sys"); }

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
    if (task != null) for (int k = 0; k < taskIds.size(); k++) if (taskIds.get(k).equals(task)) { taskIdx = k; decisionPage.paintChips(); }
    String text = i.getStringExtra("text");
    if (text != null && !text.isEmpty()) decisionPage.sendDecision(text);
  }

  int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }
  GradientDrawable pill(int c, float r) { GradientDrawable g = new GradientDrawable(); g.setColor(c); g.setCornerRadius(r); return g; }

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
    systemPage.applyPicked(uri, fname);
  }

  void refreshGatewayBar() {
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
    if (k == 0) decisionPage.build();
    else if (k == 1) buildReportTab();
    else if (k == 2) gatewayPage.build();
    else systemPage.build();
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
  FrameLayout railChip(Button c) {
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



  /** 下划线输入框(网关页):不带 pill 背景,走系统默认下划线 */
  EditText fieldU(LinearLayout parent, String hint) { return fieldU(parent, hint, ""); }
  EditText fieldU(LinearLayout parent, String hint, String text) {
    EditText e = new EditText(this);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setPadding(dp(4), dp(10), dp(4), dp(10));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }
  private static int parsePort(String s, int def) {
    try { int v = Integer.parseInt(s.trim()); return (v > 0 && v < 65536) ? v : def; } catch (Exception e) { return def; }
  }

  TextView hint(String s) {
    TextView t = new TextView(this);
    t.setText(s); t.setTextSize(12); t.setTextColor(0xFF666C77); t.setPadding(0, dp(8), 0, dp(4));
    return t;
  }
  EditText field(LinearLayout parent, String hint) { return field(parent, hint, ""); }
  EditText field(LinearLayout parent, String hint, String text) {
    EditText e = new EditText(this);
    e.setHint(hint); e.setTextSize(13); e.setText(text); e.setSingleLine(true);
    e.setBackground(pill(Color.WHITE, dp(10)));
    e.setPadding(dp(10), dp(8), dp(10), dp(8));
    parent.addView(e, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    return e;
  }
  Button button(LinearLayout parent, String label) {
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
  File srcDir(String task) {
    return new File("multi".equals(task)
        ? "/sdcard/models/laya-litert/phone" : "/sdcard/models/laya-litert-" + task + "/phone");
  }

  static void deleteQuiet(File f) {
    if (f == null || !f.exists()) return;
    File[] kids = f.isDirectory() ? f.listFiles() : null;
    if (kids != null) for (File k : kids) deleteQuiet(k);
    f.delete();
  }

  static String human(long b) {
    return b >= 1048576L ? (b / 1048576L) + "MB" : (b / 1024L) + "KB";
  }

  /** 目录内全部文件打包为 zip(文件位于 zip 根,importPackage 可直接校验导入);ZIP 根含 label.txt 等附加文件也一并带上 */
  void zipDir(File dir, File dst) throws Exception {
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

  String modelDetail(String task) {
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
  private TextView gwBarText;


  // ================= 通用 =================

}
