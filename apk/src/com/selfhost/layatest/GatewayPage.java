package com.selfhost.layatest;

import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.ScrollView;
import android.widget.TextView;
import android.view.View;
import android.graphics.Color;
import android.view.Gravity;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

/** ③ 网关页(从 MainActivity 拆出):邮件/MQTT/LLM 三面板 + 决策 API 开关 + 底栏 */
class GatewayPage {
  private final MainActivity m;
  private TextView gwStatus;
  private EditText gwEmailHost, gwEmailUser, gwEmailPass, gwReportTo;
  private EditText gwImapPort, gwSmtpPort; private CheckBox gwSsl;
  private EditText gwMqUrl, gwMqSub, gwMqPub;

  GatewayPage(MainActivity m) { this.m = m; }

  private static int parsePort(String s, int def) {
    try { int v = Integer.parseInt(s.trim()); return (v > 0 && v < 65536) ? v : def; } catch (Exception e) { return def; }
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
              .put("host", gwEmailHost.getText().toString())
              .put("user", gwEmailUser.getText().toString())
              .put("pass", gwEmailPass.getText().toString())
              .put("imapPort", parsePort(gwImapPort.getText().toString(), gwSsl.isChecked() ? 993 : 143))
              .put("ssl", gwSsl.isChecked()));
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
              .put("host", gwEmailHost.getText().toString())
              .put("user", gwEmailUser.getText().toString())
              .put("pass", gwEmailPass.getText().toString())
              .put("smtpPort", parsePort(gwSmtpPort.getText().toString(), gwSsl.isChecked() ? 465 : 25))
              .put("ssl", gwSsl.isChecked());
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
        cfg.put("enabled", true);
        cfg.put("mqtt", new JSONObject().put("enabled", true).put("url", gwMqUrl.getText().toString()));
        cfg.put("topics", new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString()));
        gwStatus.setText(com.laya.Gateway.saveAndStart(m.getApplicationContext(), cfg));
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); }
    });
    Button mqTestBtn = Ui.button(m,l, m.getString(R.string.test_mqtt_btn));
    mqTestBtn.setOnClickListener(v -> {
      gwStatus.setText(m.getString(R.string.mqtt_testing));
      new Thread(() -> {
        String r;
        try {
          JSONObject mc = new JSONObject().put("url", gwMqUrl.getText().toString());
          JSONObject tc = new JSONObject().put("sub", gwMqSub.getText().toString()).put("pub", gwMqPub.getText().toString());
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
        cfg.put("api", new JSONObject().put("enabled", next));
        com.laya.Gateway.saveCfg(m.getApplicationContext(), cfg);
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.cfg_failed, e.getMessage())); return; }
      if (next) com.laya.DecisionApiServer.start(m.getApplicationContext(), com.laya.DecisionApiServer.PORT, /*ensureFgs=*/true);
      else com.laya.DecisionApiServer.stopServer();
      apiBtn.setText(m.getString(next ? R.string.api_stop : R.string.api_start));
      gwStatus.setText(m.getString(R.string.gateway_bar, com.laya.Gateway.status(m)));
    });

    // ---- LLM 设置(重要+紧急升级通道,3 槽位供可选) ----
    LinearLayout pLlm = new LinearLayout(m);
    pLlm.setOrientation(LinearLayout.VERTICAL);
    pLlm.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    l = pLlm;
    l.addView(Ui.hint(m,m.getString(R.string.llm_hint)));
    JSONObject llmCfg = com.laya.Gateway.cfg(m).optJSONObject("llm");
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
      LinearLayout slot = new LinearLayout(m);
      slot.setOrientation(LinearLayout.VERTICAL);
      slot.setBackground(Ui.pill(0xFFF7F8FA, Ui.dp(m,10)));
      slot.setPadding(Ui.dp(m,9), Ui.dp(m,6), Ui.dp(m,9), Ui.dp(m,8));
      LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
      slp.bottomMargin = Ui.dp(m,8); slot.setLayoutParams(slp);
      LinearLayout head = new LinearLayout(m);
      head.setGravity(Gravity.CENTER_VERTICAL);
      llmRb[i] = new RadioButton(m);
      llmRb[i].setText(m.getString(R.string.llm_enable)); llmRb[i].setTextSize(12);
      llmRb[i].setChecked(slotIds[i].equals(actId));
      head.addView(llmRb[i]);
      llmName[i] = Ui.fieldU(m,head, m.getString(R.string.name_hint));
      JSONObject fs = s;
      String nm = s != null ? s.optString("name", defNames[i]) : defNames[i];
      llmName[i].setText(nm);
      llmName[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      slot.addView(head);
      llmUrl[i] = Ui.fieldU(m,slot, m.getString(R.string.base_url_hint));
      llmUrl[i].setText(s != null ? s.optString("baseURL", defUrls[i]) : defUrls[i]);
      LinearLayout mr = new LinearLayout(m);
      llmModel[i] = Ui.fieldU(m,mr, "Model");
      llmModel[i].setText(s != null ? s.optString("model", defModels[i]) : defModels[i]);
      llmModel[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      llmKey[i] = Ui.fieldU(m,mr, "API Key");
      if (s != null) llmKey[i].setText(s.optString("apiKey", ""));
      llmKey[i].setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
      LinearLayout.LayoutParams mlp0 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      mlp0.rightMargin = Ui.dp(m,8); llmModel[i].setLayoutParams(mlp0);
      slot.addView(mr);
      l.addView(slot);
    }
    llmRb[0].setId(901); llmRb[1].setId(902); llmRb[2].setId(903);
    // 手动互斥(RadioGroup 纵向占太高):点一个清其余
    for (int i = 0; i < 3; i++) {
      final int k = i;
      llmRb[i].setOnClickListener(v -> { for (int j = 0; j < 3; j++) llmRb[j].setChecked(j == k); });
    }
    Button llmSave = Ui.button(m,l, m.getString(R.string.save_llm));
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
        gwStatus.setText(com.laya.Gateway.saveLlm(m,
            new JSONObject().put("active", checked < 0 ? "llm1" : slotIds[checked]).put("slots", slots)));
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.llm_cfg_failed, e.getMessage())); }
    });
    Button llmTest = Ui.button(m,l, m.getString(R.string.test_llm_btn));
    llmTest.setOnClickListener(v -> {
      try {
        int checked = -1;
        for (int i = 0; i < 3; i++) if (llmRb[i].isChecked()) checked = i;
        if (checked < 0) { gwStatus.setText(m.getString(R.string.llm_pick_first)); return; }
        final String url = llmUrl[checked].getText().toString().trim().replaceAll("/+$", "");
        final String model = llmModel[checked].getText().toString().trim();
        final String key = llmKey[checked].getText().toString().trim();
        final String nm = llmName[checked].getText().toString();
        gwStatus.setText(m.getString(R.string.testing_x, nm));
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
              r = m.getString(R.string.llm_ok, nm, code, txt.trim());
            } else r = m.getString(R.string.llm_http_err, nm, code, body.substring(0, Math.min(160, body.length())));
          } catch (Exception e) { r = m.getString(R.string.llm_fail, nm, e.getMessage()); }
          final String fr = r;
          m.runOnUiThread(() -> gwStatus.setText(fr));
        }).start();
      } catch (Exception e) { gwStatus.setText(m.getString(R.string.test_failed, e.getMessage())); }
    });

    // ---- 左侧竖排 tab(与报表页同款):邮件/队列/LLM ----
    LinearLayout[] panels = {pMail, pMq, pLlm};
    final ScrollView[] scrolls = new ScrollView[3];
    for (int i = 0; i < 3; i++) {
      ScrollView sv = new ScrollView(m);
      sv.addView(panels[i]);
      scrolls[i] = sv;
    }
    m.scroller = scrolls[0];
    final FrameLayout gHolder = new FrameLayout(m);
    gHolder.addView(scrolls[0]);
    gHolder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] gkinds = {m.getString(R.string.gw_mail), m.getString(R.string.gw_mq), "LLM"};
    final Button[] gchips = new Button[3];
    final LinearLayout grail = new LinearLayout(m);
    grail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 3; i++) {
      final int k = i;
      Button c = new Button(m);
      c.setText(gkinds[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        gHolder.removeAllViews();
        gHolder.addView(scrolls[k]);
        m.scroller = scrolls[k];
        for (int j = 0; j < 3; j++) {
          gchips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          gchips[j].setBackground(Ui.pill(j == k ? m.PRIMARY : 0xFFE7EAF2, Ui.dp(m,12)));
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
    // 初始高亮
    gchips[0].setTextColor(Color.WHITE);
    gchips[0].setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,12)));
  }
}
