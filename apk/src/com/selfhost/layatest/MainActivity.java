package com.selfhost.layatest;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
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
  private final ReportPage reportPage = new ReportPage(this);
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
    if (com.laya.Gateway.cfg(this).optBoolean(com.laya.Cfg.ENABLED)) {
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


  private void buildUi() {
    LinearLayout root = new LinearLayout(this);
    root.setOrientation(LinearLayout.VERTICAL);
    root.setBackgroundColor(WX_PAGE_BG);

    // ---- 标题栏(仿微信:浅灰底、居中标题、左侧☰菜单按钮、底部分隔线) ----
    menuBtn = new Button(this);
    menuBtn.setText("☰"); menuBtn.setAllCaps(false); menuBtn.setTextSize(18);
    menuBtn.setPadding(Ui.dp(this, 12), Ui.dp(this, 2), Ui.dp(this, 12), Ui.dp(this, 2));
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
    title.setPadding(0, Ui.dp(this, 10), 0, Ui.dp(this, 10));
    titleHolder.addView(title);
    titleHolder.addView(menuBtn);
    // 标题右侧语言切换按钮:点击循环 跟随系统→中文→English(与系统页选择器同一记忆键)
    langBtn = new TextView(this);
    langBtn.setTextSize(13); langBtn.setTypeface(Typeface.DEFAULT_BOLD);
    langBtn.setTextColor(0xFF1A2B4C);
    langBtn.setBackground(Ui.pill(0xFFE7EAF2, Ui.dp(this, 12)));
    langBtn.setPadding(Ui.dp(this, 10), Ui.dp(this, 5), Ui.dp(this, 10), Ui.dp(this, 5));
    FrameLayout.LayoutParams llblp = new FrameLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT, Gravity.END | Gravity.CENTER_VERTICAL);
    llblp.rightMargin = Ui.dp(this, 10); llblp.topMargin = Ui.dp(this, 6);
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
    titleBar.addView(titleDiv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
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
    bar.setPadding(Ui.dp(this, 4), Ui.dp(this, 6), Ui.dp(this, 4), Ui.dp(this, 10));
    View topDiv = new View(this);
    topDiv.setBackgroundColor(0xFFE5E5E5);
    root.addView(topDiv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, Ui.dp(this, 1)));
    String[] names = {getString(R.string.tab_decision), getString(R.string.tab_report), getString(R.string.tab_gateway), getString(R.string.tab_system)};
    for (int i = 0; i < 4; i++) {
      final int k = i;
      TextView t = new TextView(this);
      t.setText(names[i]); t.setTextSize(13); t.setGravity(Gravity.CENTER);
      t.setPadding(0, Ui.dp(this, 8), 0, Ui.dp(this, 8));
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
      tb.setBackground(Ui.pill(on ? 0xFFE3F7EC : Color.TRANSPARENT, Ui.dp(this, 18)));
      tb.setPadding(0, on ? Ui.dp(this, 9) : Ui.dp(this, 9), 0, on ? Ui.dp(this, 9) : Ui.dp(this, 9));
    }
    body.removeAllViews();
    if (k == 0) decisionPage.build();
    else if (k == 1) reportPage.build();
    else if (k == 2) gatewayPage.build();
    else systemPage.build();
  }



  private static double asNum(Object o) {
    if (o instanceof Number) return ((Number) o).doubleValue();
    try { return Double.parseDouble(String.valueOf(o)); } catch (Exception e) { return 0; }
  }

  // ================= ③ 网关 =================




  private static int parsePort(String s, int def) {
    try { int v = Integer.parseInt(s.trim()); return (v > 0 && v < 65536) ? v : def; } catch (Exception e) { return def; }
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
