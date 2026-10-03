package com.selfhost.layatest;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.FrameLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
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

    // ---- 标题栏(仿微信:浅灰底、居中标题、底部分隔线) ----
    LinearLayout titleBar = new LinearLayout(this);
    titleBar.setOrientation(LinearLayout.VERTICAL);
    titleBar.setBackgroundColor(0xFFEDEDED);
    TextView title = new TextView(this);
    title.setText("智能决策业务台");
    title.setTextSize(17); title.setTypeface(Typeface.DEFAULT_BOLD); title.setGravity(Gravity.CENTER);
    title.setTextColor(0xFF1A1A1A);
    title.setPadding(0, dp(10), 0, dp(10));
    titleBar.addView(title);
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
        reply = fmtAnswers(taskIds.get(ti), r.answers, (int) r.latencyMs) + "\n后端: LiteRT GPU(C API 内置)";
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
  private TextView reportMenuBtn;

  /** 报表类型浮动菜单(左侧,可隐藏) */
  private void showReportMenu(View anchor) {
    LinearLayout menu = new LinearLayout(this);
    menu.setOrientation(LinearLayout.VERTICAL);
    menu.setBackground(pill(Color.WHITE, dp(14)));
    menu.setPadding(dp(6), dp(6), dp(6), dp(6));
    String[] kinds = {"日报", "月报", "年报", "详单"};
    for (int i = 0; i < 4; i++) {
      final int k = i;
      TextView it = new TextView(this);
      it.setText(kinds[i]); it.setTextSize(14);
      it.setPadding(dp(18), dp(12), dp(18), dp(12));
      it.setOnClickListener(v -> {
        reportKind = k;
        if (reportMenuBtn != null) reportMenuBtn.setText("☰ " + kinds[k]);
        renderReport(k);
        dismissReportMenu();
      });
      menu.addView(it, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }
    PopupWindow pw = new PopupWindow(menu, dp(140), LinearLayout.LayoutParams.WRAP_CONTENT, true);
    pw.setBackgroundDrawable(pill(Color.WHITE, dp(14)));
    pw.setElevation(dp(6));
    reportMenuBtn.setTag(pw);
    pw.showAsDropDown(anchor, 0, dp(4));
  }

  private void dismissReportMenu() {
    if (reportMenuBtn != null && reportMenuBtn.getTag() instanceof PopupWindow)
      ((PopupWindow) reportMenuBtn.getTag()).dismiss();
  }

  private int reportKind = 0;
  private int detailPage = 0;   // 详单当前页(0 基)
  private int detailPages = 1;
  private LinearLayout pagerRow;
  private TextView pagerLabel;

  private void buildReportTab() {
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(12), dp(8), dp(12), dp(8));
    LinearLayout row = new LinearLayout(this);
    row.setGravity(Gravity.CENTER_VERTICAL);
    reportMenuBtn = new TextView(this);
    reportMenuBtn.setText("☰ " + (reportKind == 0 ? "日报" : reportKind == 1 ? "月报" : reportKind == 2 ? "年报" : "详单"));
    reportMenuBtn.setTextSize(13); reportMenuBtn.setTextColor(Color.WHITE);
    reportMenuBtn.setPadding(dp(14), dp(8), dp(14), dp(8));
    reportMenuBtn.setBackground(pill(PRIMARY, dp(18)));
    reportMenuBtn.setOnClickListener(v -> showReportMenu(v));
    row.addView(reportMenuBtn);
    TextView cur = new TextView(this);
    cur.setText("报表来自本机决策日志,左侧菜单切换"); cur.setTextSize(12); cur.setTextColor(0xFF66707E);
    cur.setPadding(dp(10), 0, 0, 0);
    row.addView(cur);
    l.addView(row);

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

    TextView tv = new TextView(this);
    tv.setTextSize(13); tv.setPadding(0, dp(10), 0, 0);
    l.addView(tv);
    scroller = new ScrollView(this);
    scroller.addView(l);
    body.addView(scroller);
    reportView = tv;
    renderReport(reportKind);
  }
  private TextView reportView;

  private void renderReport(int kind) {
    final boolean isDetail = kind == 3;
    runOnUiThread(() -> pagerRow.setVisibility(isDetail ? View.VISIBLE : View.GONE));
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
    LinearLayout l = new LinearLayout(this);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(dp(12), dp(8), dp(12), dp(8));
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
    l.addView(hint("模型包上传(局域网):浏览器打开 " + com.laya.Gateway.uploadUrl()
        + " 提交 zip+业务名;或 curl -X POST --data-binary @pkg.zip \"" + com.laya.Gateway.uploadUrl() + "/upload?task=名字\""));
    Button stopBtn = button(l, "停止全部网关");
    stopBtn.setOnClickListener(v -> gwStatus.setText(com.laya.Gateway.stop(getApplicationContext())));
    gwStatus = hint("网关: " + com.laya.Gateway.status(this));
    l.addView(gwStatus);
    scroller = new ScrollView(this);
    scroller.addView(l);
    body.addView(scroller);
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


  // ================= ④ 系统 =================
  private TextView sysView;
  private TextView gwBarText;
  private boolean swimVertical = true; // 泳道图默认竖向展示(转置按钮可切横向)
  private FrameLayout swimHolder;   // 泳道图容器(转置时局部替换,保持滚动位置)

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
    upSrc = fieldU(up, "包路径(目录或 zip,如 /sdcard/Download/laya-litert-demo.zip)");
    upTask = fieldU(up, "业务名(英文,如 demo)");
    upStatus = new TextView(this);
    upStatus.setTextSize(11); upStatus.setTextColor(0xFF66707E);
    upStatus.setText("六文件校验通过后注册为新业务;zip 内路径任意,校验自动完成");
    up.addView(upStatus);
    Button impBtn = button(up, "导入并注册");
    impBtn.setOnClickListener(v -> {
      String src = upSrc.getText().toString().trim();
      String task = upTask.getText().toString().trim();
      if (src.isEmpty() || task.isEmpty()) { upStatus.setText("请填包路径和业务名"); return; }
      upStatus.setText("导入中…(zip 约 250MB,校验六文件)");
      new Thread(() -> {
        String err = com.laya.DecisionCore.importPackage(getApplicationContext(), src, task);
        runOnUiThread(() -> {
          if (err == null) {
            upStatus.setText("✅ 导入成功,已注册业务 [" + task + "]");
            setTab(3);
          } else upStatus.setText("❌ " + err);
        });
      }).start();
    });
    l.addView(up);

    Button rescan = button(l, "⟳ 重新扫描业务");
    rescan.setOnClickListener(v -> { refreshTasks(); setTab(3); });
    l.addView(hint("已注册业务列表如下;加载=装入 app 可决策;卸载=释放空间(源包保留)"));

    for (int i = 0; i < taskIds.size(); i++) {
      final String task = taskIds.get(i);
      final String label = taskLabels.get(i);
      boolean loaded = new File(getFilesDir(), "laya-" + task + "/laya_ml_s256_embeds_wfp16.tflite").isFile();

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
      l.addView(card);
    }

    sysView = new TextView(this);
    sysView.setText("推理后端: LiteRT GPU(C API, app 内置)\n网关状态见顶部状态栏");
    sysView.setTextSize(13);
    l.addView(sysView);

    l.addView(hint("== 架构图 =="));
    l.addView(new DiagramView(this, 0));
    LinearLayout sh = new LinearLayout(this);
    sh.setOrientation(LinearLayout.HORIZONTAL);
    sh.setGravity(Gravity.CENTER_VERTICAL);
    TextView sht = hint("== 业务数据流(泳道)==");
    sht.setPadding(0, 0, 0, 0);
    LinearLayout.LayoutParams shlp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    sht.setLayoutParams(shlp);
    sh.addView(sht);
    Button transpose = new Button(this);
    transpose.setText(swimVertical ? "⇄ 横向显示" : "⇅ 竖向显示");
    transpose.setAllCaps(false); transpose.setTextSize(11);
    transpose.setPadding(dp(10), dp(2), dp(10), dp(2));
    transpose.setMinHeight(0); transpose.setMinimumHeight(0);
    transpose.setOnClickListener(v -> {
      swimVertical = !swimVertical;
      transpose.setText(swimVertical ? "⇄ 横向显示" : "⇅ 竖向显示");
      if (swimHolder != null) {
        swimHolder.removeAllViews();
        swimHolder.addView(new DiagramView(this, swimVertical ? 2 : 1));
      }
    });
    sh.addView(transpose);
    l.addView(sh);
    swimHolder = new FrameLayout(this);
    swimHolder.addView(new DiagramView(this, swimVertical ? 2 : 1));
    l.addView(swimHolder);

    scroller = new ScrollView(this);
    scroller.addView(l);
    body.addView(scroller);
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
