package com.selfhost.layatest;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.view.View;
import android.graphics.Color;
import android.view.Gravity;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import com.laya.Cfg;
import org.json.JSONObject;

/** ③ 网关页(从 MainActivity 拆出):资讯/邮件/MQTT/采集/模板 五面板 + 决策 API 开关 + 底栏(LLM 面板已删除,v1.4.50 决策链下线升级通道) */
class GatewayPage {
  private final MainActivity m;
  private TextView gwStatus;
  private EditText gwEmailHost, gwEmailUser, gwEmailPass, gwReportTo;
  private EditText gwImapPort, gwSmtpPort; private CheckBox gwSsl;
  private EditText gwMqUrl, gwMqSub, gwMqPub;
  private EditText gwNewsSina, gwNewsWsc, gwNewsInterval;

  /** 回复模板段落 kind 清单(显示带键提示;保存时截 '(' 前);与 service/reply-templates.mjs templateSpecs 对应 */
  private static final String[] TPL_KINDS = {
    "auto_header()", "auto_open(部门)", "auto_tone(1-5)", "auto_escalate()", "auto_footer()",
    "script_header()", "human_open(部门)", "human_confirm()", "human_escalate_note()",
    "ugc_header()", "ugc_open(正常/广告/其他)", "ugc_escalate()", "ugc_sev(high/low)", "ugc_footer()",
    "ugc_script_header()", "ugc_script_tips()",
    "agent_header()", "agent_escalate()", "agent_route(工作流)", "agent_complex()", "agent_footer()",
    "agent_script_header()", "agent_script_tips()",
    "risk_header()", "risk_strength()", "risk_action(strong/mid/normal)", "risk_footer()",
    "risk_script_header()", "risk_script_tips()",
    "generic_header()", "generic_none()", "generic_conclusion()", "generic_escalate()", "generic_auto()",
    "generic_footer()", "generic_script_header()", "generic_script_tips()"
  };

  GatewayPage(MainActivity m) { this.m = m; }

  private static int parsePort(String s, int def) {
    try { int v = Integer.parseInt(s.trim()); return (v > 0 && v < 65536) ? v : def; } catch (Exception e) { return def; }
  }

  /** 采集样本:按行拆分,去空行(启停/保存共用) */
  private static org.json.JSONArray samplesOf(EditText et) {
    org.json.JSONArray arr = new org.json.JSONArray();
    for (String ln : et.getText().toString().split("\n")) {
      String s = ln.trim();
      if (!s.isEmpty()) arr.put(s);
    }
    return arr;
  }

  /** 采集间隔落盘(SQLite meta;≥15s;非法则保留原值) */
  private static void putCollectInterval(EditText et) {
    try { com.laya.Db.collectSetInterval(null, Long.parseLong(et.getText().toString().trim())); }
    catch (Exception ignore) {}
  }

  /** 采集卡片启停按钮态(on=启用中→显示"停用"灰按钮) */
  private void setCollectToggle(Button tog, boolean on) {
    tog.setText(m.getString(on ? R.string.collect_disable : R.string.collect_enable));
    tog.setBackground(Ui.pill(on ? 0xFF8894A6 : m.PRIMARY, Ui.dp(m,10)));
  }

  void build() {
    // 三个子页面板,左栏 tab 切换
    LinearLayout pMail = new LinearLayout(m);
    pMail.setOrientation(LinearLayout.VERTICAL);
    pMail.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    LinearLayout l = pMail;
    l.addView(Ui.hint(m,m.getString(R.string.mail_hint)));
    gwEmailHost = Ui.fieldU(m,l, m.getString(R.string.mail_host_hint));
    gwEmailUser = Ui.fieldU(m,l, m.getString(R.string.mail_user_hint));
    gwEmailPass = Ui.fieldU(m,l, m.getString(R.string.mail_pass_hint));
    LinearLayout pr = new LinearLayout(m);
    pr.setOrientation(LinearLayout.HORIZONTAL);
    gwImapPort = Ui.fieldU(m,pr, m.getString(R.string.imap_port_hint), "143");
    gwSmtpPort = Ui.fieldU(m,pr, m.getString(R.string.smtp_port_hint), "25");
    LinearLayout.LayoutParams plp1 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
    plp1.rightMargin = Ui.dp(m,8); gwImapPort.setLayoutParams(plp1);
    gwSmtpPort.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    l.addView(pr);
    gwSsl = new CheckBox(m);
    gwSsl.setText(m.getString(R.string.ssl_hint));
    gwSsl.setTextSize(13); gwSsl.setPadding(0, Ui.dp(m,6), 0, Ui.dp(m,6));
    l.addView(gwSsl);
    gwReportTo = Ui.fieldU(m,l, m.getString(R.string.report_to_hint));
    Button emailBtn = Ui.button(m,l, m.getString(R.string.save_start_mail));
    emailBtn.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(m.getApplicationContext());
        cfg.put(Cfg.ENABLED, true);
        cfg.put(Cfg.EMAIL, new JSONObject().put(Cfg.ENABLED, true)
            .put(Cfg.HOST, gwEmailHost.getText().toString())
            .put(Cfg.USER, gwEmailUser.getText().toString())
            .put(Cfg.PASS, gwEmailPass.getText().toString())
            .put("imapPort", parsePort(gwImapPort.getText().toString(), gwSsl.isChecked() ? 993 : 143))
            .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
            .put(Cfg.SSL, gwSsl.isChecked()));
        String to = gwReportTo.getText().toString();
        if (!to.isEmpty()) cfg.put(Cfg.REPORT, new JSONObject().put(Cfg.TO, to));
        gwStatus.setText(com.laya.Gateway.saveAndStart(m.getApplicationContext(), cfg));
        m.refreshGatewayBar();
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });
    Button emailTestBtn = Ui.button(m,l, m.getString(R.string.test_imap_btn));
    emailTestBtn.setOnClickListener(v -> {
      gwStatus.setText(m.getString(R.string.imap_testing));
      new Thread(() -> {
        String r;
        try {
          r = com.laya.Gateway.testEmail(m, new JSONObject()
              .put(Cfg.HOST, gwEmailHost.getText().toString())
              .put(Cfg.USER, gwEmailUser.getText().toString())
              .put(Cfg.PASS, gwEmailPass.getText().toString())
              .put("imapPort", parsePort(gwImapPort.getText().toString(), gwSsl.isChecked() ? 993 : 143))
              .put(Cfg.SSL, gwSsl.isChecked()));
        } catch (Exception e) { r = "❌ IMAP: " + e.getMessage(); }
        final String fr = r;
        m.runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    Button smtpTestBtn = Ui.button(m,l, m.getString(R.string.test_smtp_btn));
    smtpTestBtn.setOnClickListener(v -> {
      gwStatus.setText(m.getString(R.string.smtp_testing));
      new Thread(() -> {
        String r;
        try {
          JSONObject ec = new JSONObject()
              .put(Cfg.HOST, gwEmailHost.getText().toString())
              .put(Cfg.USER, gwEmailUser.getText().toString())
              .put(Cfg.PASS, gwEmailPass.getText().toString())
              .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
              .put(Cfg.SSL, gwSsl.isChecked());
          r = com.laya.Gateway.testSmtp(m, ec, gwReportTo.getText().toString());
        } catch (Exception e) { r = "❌ SMTP: " + e.getMessage(); }
        final String fr = r;
        m.runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    LinearLayout pMq = new LinearLayout(m);
    pMq.setOrientation(LinearLayout.VERTICAL);
    pMq.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    l = pMq;
    l.addView(Ui.hint(m,m.getString(R.string.mqtt_hint)));
    gwMqUrl = Ui.fieldU(m,l, m.getString(R.string.mqtt_url_hint));
    gwMqSub = Ui.fieldU(m,l, m.getString(R.string.mqtt_sub_hint), "laya/req/+");
    gwMqPub = Ui.fieldU(m,l, m.getString(R.string.mqtt_pub_hint), "laya/resp");
    Button mqBtn = Ui.button(m,l, m.getString(R.string.save_start_mqtt));
    mqBtn.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(m.getApplicationContext());
        cfg.put(Cfg.ENABLED, true);
        cfg.put(Cfg.MQTT, new JSONObject().put(Cfg.ENABLED, true).put(Cfg.URL, gwMqUrl.getText().toString()));
        cfg.put(Cfg.TOPICS, new JSONObject().put(Cfg.SUB, gwMqSub.getText().toString()).put(Cfg.PUB, gwMqPub.getText().toString()));
        gwStatus.setText(com.laya.Gateway.saveAndStart(m.getApplicationContext(), cfg));
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });
    Button mqTestBtn = Ui.button(m,l, m.getString(R.string.test_mqtt_btn));
    mqTestBtn.setOnClickListener(v -> {
      gwStatus.setText(m.getString(R.string.mqtt_testing));
      new Thread(() -> {
        String r;
        try {
          JSONObject mc = new JSONObject().put(Cfg.URL, gwMqUrl.getText().toString());
          JSONObject tc = new JSONObject().put(Cfg.SUB, gwMqSub.getText().toString()).put(Cfg.PUB, gwMqPub.getText().toString());
          r = com.laya.Gateway.testMqtt(m, mc, tc);
        } catch (Exception e) { r = "❌ MQTT: " + e.getMessage(); }
        final String fr = r;
        m.runOnUiThread(() -> gwStatus.setText(fr));
      }).start();
    });
    l.addView(Ui.hint(m,m.getString(R.string.upload_hint, com.laya.Gateway.uploadUrl(), com.laya.Gateway.uploadUrl())));
    Button upTestBtn = Ui.button(m,l, m.getString(R.string.test_upload_btn));
    upTestBtn.setOnClickListener(v -> {
      gwStatus.setText(m.getString(R.string.upload_testing));
      new Thread(() -> {
        final String r = com.laya.Gateway.testUpload(m);
        m.runOnUiThread(() -> gwStatus.setText(r));
      }).start();
    });

    // ---- 决策 API 服务(HTTP):POST /decide,随网关配置持久化 ----
    l.addView(Ui.hint(m,m.getString(R.string.api_hint)));
    final boolean apiOn = com.laya.DecisionApiServer.running();
    Button apiBtn = Ui.button(m,l, m.getString(apiOn ? R.string.api_stop : R.string.api_start));
    apiBtn.setOnClickListener(v -> {
      boolean next = !com.laya.DecisionApiServer.running();
      try {
        JSONObject cfg = com.laya.Gateway.cfg(m.getApplicationContext());
        cfg.put(Cfg.API, new JSONObject().put(Cfg.ENABLED, next));
        com.laya.Gateway.saveCfg(m.getApplicationContext(), cfg);
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); return; }
      if (next) com.laya.DecisionApiServer.start(m.getApplicationContext(), com.laya.DecisionApiServer.PORT, /*ensureFgs=*/true);
      else com.laya.DecisionApiServer.stopServer();
      apiBtn.setText(m.getString(next ? R.string.api_stop : R.string.api_start));
      gwStatus.setText(m.getString(R.string.gateway_bar, com.laya.Gateway.status(m)));
    });

    // ---- 全业务数据采集维护(所有模型包:启停/样本/测试;决策→回复模板全链生效) ----
    LinearLayout pCollect = new LinearLayout(m);
    pCollect.setOrientation(LinearLayout.VERTICAL);
    pCollect.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    l = pCollect;
    l.addView(Ui.hint(m,m.getString(R.string.collect_hint)));
    org.json.JSONObject collectCfg = com.laya.Db.collectAll(m); // SQLite(原 prefs cfg.collect 已迁入)
    EditText gwCollectInterval = Ui.fieldU(m,l, m.getString(R.string.collect_interval_hint),
        String.valueOf(collectCfg != null ? collectCfg.optLong("intervalSec", 60) : 60));
    java.util.List<kotlin.Pair<String, String>> tasks = com.laya.DecisionCore.scanTasks(m);
    final java.util.HashMap<String, EditText> sampleEts = new java.util.HashMap<>();
    // 默认样本:未配置样本时预填,启用即可采集(用户可改)
    final java.util.HashMap<String, String> DEF_SAMPLE = new java.util.HashMap<>();
    DEF_SAMPLE.put("multi", "The invoice shows a double charge for last month's subscription, I request a refund.");
    DEF_SAMPLE.put("ticket", "Our projectors stopped syncing after the firmware update; the field team cannot present today.");
    DEF_SAMPLE.put("ugc", "加微信 xxx888 领取免费福利,限时推广,名额有限速来!");
    DEF_SAMPLE.put("agent", "对比三款投影仪的价格、亮度和售后政策,生成选型报告并预约采购流程。");
    DEF_SAMPLE.put("risk", "新注册账户当日跨境转账6笔共80万元,收款方均为境外个人账户。");
    DEF_SAMPLE.put("news", "央行宣布下调存款准备金率0.5个百分点,释放长期流动性约1万亿元。");
    DEF_SAMPLE.put("news2", "某芯片公司遭主要客户大幅砍单,股价盘前下跌9%。");
    for (kotlin.Pair<String, String> t : tasks) {
      final String task = t.getFirst();
      org.json.JSONObject cc = collectCfg != null ? collectCfg.optJSONObject(task) : null;
      boolean on = cc != null && cc.optBoolean("enabled", false);
      org.json.JSONArray samples = cc != null ? cc.optJSONArray("samples") : null;
      StringBuilder sb = new StringBuilder();
      if (samples != null) for (int i = 0; i < samples.length(); i++) { if (i > 0) sb.append('\n'); sb.append(samples.optString(i)); }
      if (sb.length() == 0) { String def = DEF_SAMPLE.get(task); if (def != null) sb.append(def); }

      LinearLayout card = new LinearLayout(m);
      card.setOrientation(LinearLayout.VERTICAL);
      card.setBackground(Ui.pill(Ui.c(m, 0xFFF7F8FA, 0xFF232833), Ui.dp(m,10)));
      card.setPadding(Ui.dp(m,9), Ui.dp(m,6), Ui.dp(m,9), Ui.dp(m,8));
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      clp.bottomMargin = Ui.dp(m,8); card.setLayoutParams(clp);

      LinearLayout head = new LinearLayout(m);
      head.setGravity(Gravity.CENTER_VERTICAL);
      TextView title = new TextView(m);
      title.setText(t.getSecond() + "(" + task + ")");
      title.setTextSize(12); title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
      title.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
      head.addView(title, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

      // 样本编辑框(默认收起,编辑按钮翻转显隐)
      final EditText sampleEt = new EditText(m);
      sampleEt.setHint(m.getString(R.string.collect_samples_hint));
      sampleEt.setText(sb.toString());
      sampleEt.setTextSize(11);
      sampleEt.setMinLines(2);
      // 主题强制 Light,暗色下默认字色近黑;必须显式配色,否则编辑框黑底黑字"看不见"(同 Ui.field 口径)
      sampleEt.setTextColor(Ui.c(m, 0xFF22262E, 0xFFE2E6EC)); sampleEt.setHintTextColor(Ui.c(m, 0xFF9AA3AD, 0xFF66707E));
      sampleEt.setBackground(Ui.pill(Ui.c(m, 0xFFFFFFFF, 0xFF2A3040), Ui.dp(m,8)));
      sampleEt.setPadding(Ui.dp(m,8), Ui.dp(m,6), Ui.dp(m,8), Ui.dp(m,6));
      sampleEt.setVisibility(View.GONE);

      // 编辑配置按钮:展开/收起该卡样本编辑区(与删除配置对称)
      Button edit = new Button(m);
      edit.setText(m.getString(R.string.collect_edit)); edit.setAllCaps(false); edit.setTextSize(10);
      edit.setMinHeight(0); edit.setMinimumHeight(0); edit.setMinimumWidth(0);
      edit.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      edit.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
      edit.setBackground(Ui.pill(Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,10)));
      edit.setOnClickListener(v -> {
        boolean show = sampleEt.getVisibility() != View.VISIBLE;
        sampleEt.setVisibility(show ? View.VISIBLE : View.GONE);
        edit.setText(m.getString(show ? R.string.collect_collapse : R.string.collect_edit));
      });
      head.addView(edit);

      Button tog = new Button(m);
      tog.setAllCaps(false); tog.setTextSize(10);
      tog.setMinHeight(0); tog.setMinimumHeight(0); tog.setMinimumWidth(0);
      tog.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      tog.setTextColor(Color.WHITE);
      setCollectToggle(tog, on);
      tog.setOnClickListener(v -> {
        try {
          org.json.JSONObject cur = com.laya.Db.collectAll(m.getApplicationContext()).optJSONObject(task);
          boolean nowOn = !(cur != null && cur.optBoolean("enabled", false));
          // 样本、间隔一并落盘:未点保存就切开关也不丢编辑
          com.laya.Db.collectSetTask(m.getApplicationContext(), task, nowOn, samplesOf(sampleEts.get(task)));
          try { com.laya.Db.collectSetInterval(m.getApplicationContext(), Long.parseLong(gwCollectInterval.getText().toString().trim())); } catch (Exception ignore) {}
          setCollectToggle(tog, nowOn); // 局部刷新:原地翻转按钮态,不重建面板
          gwStatus.setText(m.getString(R.string.gateway_bar, com.laya.Gateway.status(m)));
        } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
      });
      head.addView(tog);
      Button del = new Button(m);
      del.setText(m.getString(R.string.collect_delete)); del.setAllCaps(false); del.setTextSize(10);
      del.setMinHeight(0); del.setMinimumHeight(0); del.setMinimumWidth(0);
      del.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      del.setTextColor(Color.WHITE); del.setBackground(Ui.pill(0xFFD64545, Ui.dp(m,10)));
      del.setOnClickListener(v -> {
        try {
          com.laya.Db.collectRemove(m.getApplicationContext(), task);
          // 局部刷新:卡片复位为未配置态(启用按钮 + 预填默认样本),不重建面板
          setCollectToggle(tog, false);
          String def = DEF_SAMPLE.get(task);
          sampleEts.get(task).setText(def != null ? def : "");
          gwStatus.setText(m.getString(R.string.gateway_bar, com.laya.Gateway.status(m)));
        } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
      });
      head.addView(del);
      card.addView(head);

      card.addView(sampleEt);
      sampleEts.put(task, sampleEt);

      Button test = new Button(m);
      test.setText(m.getString(R.string.collect_test_btn)); test.setAllCaps(false); test.setTextSize(10);
      test.setMinHeight(0); test.setMinimumHeight(0); test.setMinimumWidth(0);
      test.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      test.setTextColor(Color.WHITE); test.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,10)));
      test.setOnClickListener(v -> {
        String text = sampleEt.getText().toString().split("\n")[0].trim();
        if (text.isEmpty()) { gwStatus.setText(m.getString(R.string.collect_no_sample)); return; }
        gwStatus.setText(m.getString(R.string.testing_x, task));
        new Thread(() -> {
          String r;
          try {
            com.laya.DecisionCore.Result dr = com.laya.DecisionCore.decide(m.getApplicationContext(), task, text);
            org.json.JSONObject a0 = dr.answers.optJSONObject("q0");
            String main = a0 != null && a0.has("choice") ? a0.optString("choice")
                : (a0 != null && a0.has("score") ? String.valueOf(a0.optDouble("score", 0)) : "?");
            String tplLine = "";
            for (String ln : com.laya.DecisionCore.replyAppendix(m.getApplicationContext(), task, dr.answers, text).split("\n"))
              if (ln.startsWith("【")) { tplLine = ln; break; }
            r = m.getString(R.string.collect_test_done, task, main, dr.latencyMs, tplLine);
          } catch (Exception e) { r = m.getString(R.string.test_failed, e.getMessage()); }
          final String fr = r;
          m.runOnUiThread(() -> gwStatus.setText(fr));
        }).start();
      });
      card.addView(test);
      l.addView(card);
    }
    Button collectSave = Ui.button(m,l, m.getString(R.string.collect_save));
    collectSave.setOnClickListener(v -> {
      try {
        org.json.JSONObject cur = com.laya.Db.collectAll(m.getApplicationContext());
        for (kotlin.Pair<String, String> t : tasks) {
          org.json.JSONObject cc = cur.optJSONObject(t.getFirst());
          com.laya.Db.collectSetTask(m.getApplicationContext(), t.getFirst(), cc != null && cc.optBoolean("enabled", false), samplesOf(sampleEts.get(t.getFirst())));
        }
        putCollectInterval(gwCollectInterval);
        com.laya.Gateway.restartCollect(m.getApplicationContext());
        // 采集随网关启停:网关未启用时只落盘不轮询,如实提示,避免误以为已在采集
        gwStatus.setText(m.getString(com.laya.Gateway.cfg(m.getApplicationContext()).optBoolean(com.laya.Cfg.ENABLED)
            ? R.string.collect_done : R.string.collect_need_gateway));
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });

    // ---- 回复模板覆盖管理(templates 表;DB 覆盖优先,内置默认兜底,删行=恢复默认) ----
    LinearLayout pTpl = new LinearLayout(m);
    pTpl.setOrientation(LinearLayout.VERTICAL);
    pTpl.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    l = pTpl;
    l.addView(Ui.hint(m,m.getString(R.string.tpl_hint)));
    // 业务与语言选择
    LinearLayout tplRow1 = new LinearLayout(m);
    tplRow1.setGravity(Gravity.CENTER_VERTICAL);
    Spinner tplTask = new Spinner(m);
    java.util.List<kotlin.Pair<String, String>> tplTasks = com.laya.DecisionCore.scanTasks(m);
    java.util.List<String> tplTaskOpts = new ArrayList<>();
    final java.util.List<String> tplTaskIds = new ArrayList<>();
    for (kotlin.Pair<String, String> t : tplTasks) {
      // multi 与 ticket 共用模板(ReplyTemplates 内部按 ticket 取段),存取一律归并 ticket
      String eff = t.getFirst().equals("multi") ? "ticket" : t.getFirst();
      if (!tplTaskIds.contains(eff)) { tplTaskIds.add(eff); tplTaskOpts.add(t.getSecond() + " (" + eff + ")"); }
    }
    tplTaskOpts.add(m.getString(R.string.tpl_generic));
    tplTaskIds.add("*");
    tplTask.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, tplTaskOpts));
    tplTask.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
    tplRow1.addView(tplTask);
    Spinner tplLocale = new Spinner(m);
    tplLocale.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, new String[]{"zh", "en"}));
    tplLocale.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    tplRow1.addView(tplLocale);
    l.addView(tplRow1);
    // 覆盖行列表(按所选业务×语言刷新)
    LinearLayout tplList = new LinearLayout(m);
    tplList.setOrientation(LinearLayout.VERTICAL);
    l.addView(tplList);
    final Runnable[] refreshTpl = new Runnable[1];
    refreshTpl[0] = () -> {
      tplList.removeAllViews();
      String task = tplTaskIds.get(tplTask.getSelectedItemPosition());
      String locale = (String) tplLocale.getSelectedItem();
      org.json.JSONArray rows = com.laya.Db.templateList(m.getApplicationContext(), "*".equals(task) ? null : task, locale);
      if (rows.length() == 0) {
        TextView empty = new TextView(m);
        empty.setText(m.getString(R.string.tpl_none));
        empty.setTextSize(11); empty.setTextColor(Ui.c(m, 0xFF6E7382, 0xFF8A93A0));
        empty.setPadding(Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,4));
        tplList.addView(empty);
        return;
      }
      for (int i = 0; i < rows.length(); i++) {
        org.json.JSONObject r = rows.optJSONObject(i);
        if (r == null) continue;
        LinearLayout row = new LinearLayout(m);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(Ui.dp(m,6), Ui.dp(m,3), Ui.dp(m,6), Ui.dp(m,3));
        TextView tv = new TextView(m);
        String body = r.optString("body").replace("\n", "⏎");
        if (body.length() > 48) body = body.substring(0, 48) + "…";
        tv.setText(r.optString("kind") + (r.optString("key").isEmpty() ? "" : "[" + r.optString("key") + "]") + ": " + body);
        tv.setTextSize(11); tv.setTextColor(Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
        row.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        Button del = new Button(m);
        del.setText(m.getString(R.string.tpl_restore)); del.setAllCaps(false); del.setTextSize(10);
        del.setMinHeight(0); del.setMinimumHeight(0); del.setMinimumWidth(0); del.setMinWidth(0);
        del.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
        del.setTextColor(Color.WHITE); del.setBackground(Ui.pill(0xFFD64545, Ui.dp(m,10)));
        long rowid = r.optLong("id");
        del.setOnClickListener(v -> {
          com.laya.Db.templateDelete(m.getApplicationContext(), rowid);
          refreshTpl[0].run();
        });
        row.addView(del);
        tplList.addView(row);
      }
    };
    tplTask.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { if (refreshTpl[0] != null) refreshTpl[0].run(); }
      @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    });
    tplLocale.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
      @Override public void onItemSelected(android.widget.AdapterView<?> p, View v, int pos, long id) { if (refreshTpl[0] != null) refreshTpl[0].run(); }
      @Override public void onNothingSelected(android.widget.AdapterView<?> p) {}
    });
    refreshTpl[0].run(); // 初始填充覆盖行列表
    // 新增/编辑覆盖:kind 下拉 + 键 + 文案
    LinearLayout tplRow2 = new LinearLayout(m);
    tplRow2.setGravity(Gravity.CENTER_VERTICAL);
    Spinner tplKind = new Spinner(m);
    tplKind.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, TPL_KINDS));
    tplKind.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.6f));
    tplRow2.addView(tplKind);
    EditText tplKey = new EditText(m);
    tplKey.setHint(m.getString(R.string.tpl_key)); tplKey.setTextSize(11); tplKey.setSingleLine(true);
    tplKey.setTextColor(Ui.c(m, 0xFF22262E, 0xFFE2E6EC)); tplKey.setHintTextColor(Ui.c(m, 0xFF9AA3AD, 0xFF66707E));
    tplKey.setBackground(Ui.pill(Ui.c(m, 0xFFFFFFFF, 0xFF2A3040), Ui.dp(m,8)));
    tplKey.setPadding(Ui.dp(m,8), Ui.dp(m,6), Ui.dp(m,8), Ui.dp(m,6));
    tplKey.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    tplRow2.addView(tplKey);
    l.addView(tplRow2);
    EditText tplBody = new EditText(m);
    tplBody.setHint(m.getString(R.string.tpl_body)); tplBody.setTextSize(11); tplBody.setMinLines(2);
    tplBody.setTextColor(Ui.c(m, 0xFF22262E, 0xFFE2E6EC)); tplBody.setHintTextColor(Ui.c(m, 0xFF9AA3AD, 0xFF66707E));
    tplBody.setBackground(Ui.pill(Ui.c(m, 0xFFFFFFFF, 0xFF2A3040), Ui.dp(m,8)));
    tplBody.setPadding(Ui.dp(m,8), Ui.dp(m,6), Ui.dp(m,8), Ui.dp(m,6));
    l.addView(tplBody);
    Button tplSave = Ui.button(m,l, m.getString(R.string.tpl_save));
    tplSave.setOnClickListener(v -> {
      try {
        String kind = TPL_KINDS[tplKind.getSelectedItemPosition()];
        kind = kind.substring(0, kind.indexOf('('));
        String task = tplTaskIds.get(tplTask.getSelectedItemPosition());
        com.laya.Db.templateUpsert(m.getApplicationContext(), task, (String) tplLocale.getSelectedItem(), kind,
            tplKey.getText().toString().trim(), tplBody.getText().toString());
        tplBody.setText("");
        gwStatus.setText(m.getString(R.string.tpl_saved));
        refreshTpl[0].run();
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });

    LinearLayout pNews = new LinearLayout(m);
    pNews.setOrientation(LinearLayout.VERTICAL);
    pNews.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    l = pNews;
    l.addView(Ui.hint(m,m.getString(R.string.news_cfg_hint)));
    org.json.JSONObject newsCfg = com.laya.Gateway.cfg(m).optJSONObject(com.laya.Cfg.NEWS);
    org.json.JSONObject sinaCfg = newsCfg != null ? newsCfg.optJSONObject(com.laya.Cfg.SINA) : null;
    org.json.JSONObject wscCfg = newsCfg != null ? newsCfg.optJSONObject(com.laya.Cfg.WSC) : null;
    gwNewsSina = Ui.fieldU(m,l, m.getString(R.string.news_sina_hint),
        sinaCfg != null ? sinaCfg.optString(com.laya.Cfg.URL) : "");
    gwNewsWsc = Ui.fieldU(m,l, m.getString(R.string.news_wsc_hint),
        wscCfg != null ? wscCfg.optString(com.laya.Cfg.URL) : "");
    gwNewsInterval = Ui.fieldU(m,l, m.getString(R.string.news_interval_hint),
        newsCfg != null && newsCfg.has(com.laya.Cfg.INTERVAL) ? String.valueOf(newsCfg.optLong(com.laya.Cfg.INTERVAL, 60)) : "60");
    // ---- 自动数据采集→决策 链路测试(三条示例快讯,注入后走本地评分→决策日志) ----
    // ---- 已配置采集渠道状态:启用/来源/快讯条数 ----
    String[][] chans = {{"sina", "新浪财经"}, {"wsc", "华尔街见闻"}};
    for (String[] ch : chans) {
      org.json.JSONObject sc = newsCfg != null ? newsCfg.optJSONObject(ch[0]) : null;
      boolean on = sc == null || sc.optBoolean(com.laya.Cfg.ENABLED, true);
      String urlKind = sc != null && !sc.optString(com.laya.Cfg.URL).isEmpty() ? "自定义源" : "默认源";
      LinearLayout crow = new LinearLayout(m);
      crow.setGravity(android.view.Gravity.CENTER_VERTICAL);
      crow.setPadding(0, Ui.dp(m,2), 0, Ui.dp(m,2));
      TextView ct = new TextView(m);
      ct.setTextSize(11);
      ct.setTextColor(Ui.c(m, 0xFF444A55, 0xFFB8C0CC));
      ct.setText("· " + ch[1] + (on ? " ✅启用" : " ⛔停用") + " · " + urlKind + " · " + m.newsPage.feedCount(ch[1]) + " 条");
      crow.addView(ct, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      Button tog = new Button(m);
      tog.setText(on ? "停止" : "启用"); tog.setAllCaps(false); tog.setTextSize(10);
      tog.setMinHeight(0); tog.setMinimumHeight(0); tog.setMinimumWidth(0);
      tog.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      tog.setTextColor(Color.WHITE);
      tog.setBackground(Ui.pill(on ? 0xFF8894A6 : m.PRIMARY, Ui.dp(m,10)));
      tog.setOnClickListener(v -> {
        try {
          JSONObject c2 = com.laya.Gateway.cfg(m.getApplicationContext());
          JSONObject n2 = c2.optJSONObject(com.laya.Cfg.NEWS); if (n2 == null) n2 = new JSONObject();
          JSONObject s2 = n2.optJSONObject(ch[0]); if (s2 == null) s2 = new JSONObject();
          // 每业务独立数据源:按 ch[0] 精确写 sina/wsc,不互相污染(旧代码会把 wsc 写进 sina 键)
          boolean nowOn = !s2.optBoolean(com.laya.Cfg.ENABLED, true);
          s2.put(com.laya.Cfg.ENABLED, nowOn);
          n2.put(ch[0], s2);
          c2.put(com.laya.Cfg.NEWS, n2);
          com.laya.Gateway.saveCfg(m.getApplicationContext(), c2);
          // 局部刷新:原地翻转按钮与该行文案,不整页重建(拉取循环每轮实时读 cfg,无需重建)
          String kind = !s2.optString(com.laya.Cfg.URL).isEmpty() ? "自定义源" : "默认源";
          ct.setText("· " + ch[1] + (nowOn ? " ✅启用" : " ⛔停用") + " · " + kind + " · " + m.newsPage.feedCount(ch[1]) + " 条");
          tog.setText(nowOn ? "停止" : "启用");
          tog.setBackground(Ui.pill(nowOn ? 0xFF8894A6 : m.PRIMARY, Ui.dp(m,10)));
        } catch (Exception e) { ct.setText("操作失败: " + e.getMessage()); }
      });
      crow.addView(tog);
      Button test = new Button(m);
      test.setText("测试"); test.setAllCaps(false); test.setTextSize(10);
      test.setMinHeight(0); test.setMinimumHeight(0); test.setMinimumWidth(0);
      test.setPadding(Ui.dp(m,8), Ui.dp(m,3), Ui.dp(m,8), Ui.dp(m,3));
      test.setTextColor(Color.WHITE); test.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,10)));
      final String sample = "sina".equals(ch[0])
          ? "央行宣布下调存款准备金率0.5个百分点,释放长期资金约1万亿元"
          : "美联储宣布加息25个基点,并表示将继续缩表以抑制通胀";
      test.setOnClickListener(v -> m.newsPage.injectTestFeed(sample, "测试-" + ch[1]));
      crow.addView(test);
      l.addView(crow);
    }
    l.addView(Ui.hint(m,m.getString(R.string.news_test_hint)));
    LinearLayout testRow = new LinearLayout(m);
    String[][] tests = {
        {"利好", "公司公告预计上半年净利润同比增长120%至150%,主因产品量价齐升"},
        {"利空", "公司收到证监会立案调查通知书,涉嫌信息披露违法违规"},
        {"中性", "公司定于下月15日召开年度股东大会,审议年度报告等议案"},
    };
    for (String[] t : tests) {
      final String tag = t[0], text = t[1];
      Button tb = new Button(m);
      tb.setText("测试·" + tag); tb.setAllCaps(false); tb.setTextSize(11);
      tb.setMinHeight(0); tb.setMinimumHeight(0); tb.setMinimumWidth(0);
      tb.setPadding(Ui.dp(m,6), Ui.dp(m,5), Ui.dp(m,6), Ui.dp(m,5));
      tb.setTextColor(Color.WHITE); tb.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,10)));
      tb.setOnClickListener(v -> m.newsPage.injectTestFeed(text, "模拟-" + tag));
      testRow.addView(tb, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    }
    LinearLayout.LayoutParams trp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    trp.bottomMargin = Ui.dp(m,8);
    l.addView(testRow, trp);

    Button newsSave = Ui.button(m,l, m.getString(R.string.news_cfg_save));
    newsSave.setOnClickListener(v -> {
      try {
        JSONObject cfg = com.laya.Gateway.cfg(m.getApplicationContext());
        JSONObject news = new JSONObject();
        String su = gwNewsSina.getText().toString().trim();
        String wu = gwNewsWsc.getText().toString().trim();
        if (!su.isEmpty()) news.put(com.laya.Cfg.SINA, new JSONObject().put(com.laya.Cfg.URL, su).put(com.laya.Cfg.ENABLED, true));
        if (!wu.isEmpty()) news.put(com.laya.Cfg.WSC, new JSONObject().put(com.laya.Cfg.URL, wu).put(com.laya.Cfg.ENABLED, true));
        try { news.put(com.laya.Cfg.INTERVAL, Math.max(15, Integer.parseInt(gwNewsInterval.getText().toString().trim()))); } catch (Exception ignore) {}
        cfg.put(com.laya.Cfg.NEWS, news);
        com.laya.Gateway.saveCfg(m.getApplicationContext(), cfg);
        gwStatus.setText(m.getString(R.string.news_cfg_done));
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });

    // ---- 左侧竖排 tab(与报表页同款):资讯/邮件/队列/采集/模板 ----
    LinearLayout[] panels = {pNews, pMail, pMq, pCollect, pTpl}; // 资讯第一
    final ScrollView[] scrolls = new ScrollView[5];
    for (int i = 0; i < 5; i++) {
      ScrollView sv = new ScrollView(m);
      sv.addView(panels[i]);
      scrolls[i] = sv;
    }
    m.scroller = scrolls[0];
    final FrameLayout gHolder = new FrameLayout(m);
    gHolder.addView(scrolls[0]);
    gHolder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] gkinds = {m.getString(R.string.gw_news), m.getString(R.string.gw_mail), m.getString(R.string.gw_mq), m.getString(R.string.gw_collect), m.getString(R.string.gw_tpl)};
    final Button[] gchips = new Button[5];
    final LinearLayout grail = new LinearLayout(m);
    grail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 5; i++) {
      final int k = i;
      Button c = new Button(m);
      c.setText(gkinds[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        gHolder.removeAllViews();
        gHolder.addView(scrolls[k]);
        m.scroller = scrolls[k];
        for (int j = 0; j < 4; j++) {
          gchips[j].setTextColor(j == k ? Color.WHITE : Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
          gchips[j].setBackground(Ui.pill(j == k ? m.PRIMARY : Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,12)));
        }
      });
      gchips[i] = c;
      grail.addView(RailChip.make(m, c));
    }
    final LinearLayout gLeft = new LinearLayout(m);
    gLeft.setOrientation(LinearLayout.VERTICAL);
    gLeft.setPadding(Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,0), Ui.dp(m,0));
    gLeft.addView(grail);
    LinearLayout.LayoutParams glclp = new LinearLayout.LayoutParams(Ui.dp(m,40), LinearLayout.LayoutParams.MATCH_PARENT);
    glclp.rightMargin = Ui.dp(m,2);
    gLeft.setLayoutParams(glclp);
    LinearLayout gTop = new LinearLayout(m);
    gTop.setOrientation(LinearLayout.HORIZONTAL);
    gTop.addView(gLeft);
    gTop.addView(gHolder);
    gTop.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
    m.body.addView(gTop);
    // 标题栏 ☰ 切换本页左栏
    m.menuBtn.setOnClickListener(v -> {
      boolean show = gLeft.getVisibility() == View.GONE;
      gLeft.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    // 共用底栏:停止 + 状态
    LinearLayout gBottom = new LinearLayout(m);
    gBottom.setOrientation(LinearLayout.VERTICAL);
    gBottom.setPadding(Ui.dp(m,12), Ui.dp(m,2), Ui.dp(m,12), Ui.dp(m,8));
    Button stopBtn = Ui.button(m,gBottom, m.getString(R.string.stop_gw));
    stopBtn.setOnClickListener(v -> gwStatus.setText(com.laya.Gateway.stop(m.getApplicationContext())));
    gwStatus = Ui.hint(m,m.getString(R.string.gateway_bar, com.laya.Gateway.status(m)));
    gBottom.addView(gwStatus);
    m.body.addView(gBottom);
    // 初始漆全部 chip(选中高亮,其余 off 样式;默认 Button 样式在深色下露馅)
    for (int j = 0; j < 4; j++) {
      gchips[j].setTextColor(j == 0 ? Color.WHITE : Ui.c(m, 0xFF1A2B4C, 0xFFCFD8E6));
      gchips[j].setBackground(Ui.pill(j == 0 ? m.PRIMARY : Ui.c(m, 0xFFE7EAF2, 0xFF2A3040), Ui.dp(m,12)));
    }
  }
}
