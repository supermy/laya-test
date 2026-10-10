package com.selfhost.layatest;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * ⑤ 快讯页(JevLive 式实时财经快讯分诊):
 * 拉 7x24 快讯流(新浪财经 zhibo API + 华尔街见闻 lives API)→ LLM 逐条四维评分(驱动/置信/紧迫/情绪)→
 * 阈值分级:驱动<0.50 IGNORE;置信<0.55 OBSERVE;否则 ACT。
 * 流式展示,60s 自动刷新(仅本页在前台时拉取);评分走网关页配置的 OpenAI 兼容 LLM 槽位。
 */
class NewsPage {
  private static final int MAX_ITEMS = 60;      // 列表展示上限
  private static final int SCORE_TOP = 30;      // 仅评分最新 N 条(防首屏洪水刷爆 LLM)
  private static final double TH_DRIVE = 0.50;  // IGNORE 阈值
  private static final double TH_CONF = 0.55;   // OBSERVE 阈值
  private static final double TH_STRONG = 0.75; // 强信号阈值(驱动与置信双高)

  private final MainActivity m;
  private LinearLayout list;
  private TextView status;
  private Button autoBtn;
  private final Handler timer = new Handler(Looper.getMainLooper());
  private final java.util.LinkedHashMap<String, JSONObject> items = new java.util.LinkedHashMap<>(); // key→{ts,source,text,url,scores?,reason?}
  private final java.util.HashSet<String> scoringKeys = new java.util.HashSet<>();
  private boolean fetching = false;
  private boolean scoringActive = false;
  private boolean autoOn = true;
  private boolean timerStarted = false;
  private boolean llmMissing = false;
  private TextView[] filterChips;              // 过滤 chips:全部/强信号/信号/观察/忽略
  private String newsFilter = "all";           // 当前过滤:all|strong|signal|observe|ignore

  NewsPage(MainActivity m) { this.m = m; }

  void build() {
    LinearLayout l = new LinearLayout(m);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(Ui.dp(m, 10), Ui.dp(m, 6), Ui.dp(m, 10), Ui.dp(m, 8));

    // ---- 状态行:统计 + 刷新 + 自动开关 ----
    LinearLayout head = new LinearLayout(m);
    head.setGravity(Gravity.CENTER_VERTICAL);
    status = new TextView(m);
    status.setTextSize(12); status.setTextColor(0xFF66707E);
    status.setText(m.getString(R.string.news_none));
    head.addView(status, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    Button refresh = new Button(m);
    refresh.setText(m.getString(R.string.drill_query)); refresh.setAllCaps(false); refresh.setTextSize(12);
    refresh.setMinHeight(0); refresh.setMinimumHeight(0); refresh.setMinimumWidth(0);
    refresh.setPadding(Ui.dp(m, 12), Ui.dp(m, 6), Ui.dp(m, 12), Ui.dp(m, 6));
    refresh.setBackground(Ui.pill(m.PRIMARY, Ui.dp(m, 14)));
    refresh.setTextColor(Color_WHITE());
    refresh.setOnClickListener(v -> fetch());
    LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    rlp.rightMargin = Ui.dp(m, 8);
    head.addView(refresh, rlp);
    autoBtn = new Button(m);
    autoBtn.setAllCaps(false); autoBtn.setTextSize(12);
    autoBtn.setMinHeight(0); autoBtn.setMinimumHeight(0); autoBtn.setMinimumWidth(0);
    autoBtn.setPadding(Ui.dp(m, 12), Ui.dp(m, 6), Ui.dp(m, 12), Ui.dp(m, 6));
    autoBtn.setOnClickListener(v -> { autoOn = !autoOn; paintAuto(); });
    head.addView(autoBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    paintAuto();
    l.addView(head);

    // ---- 过滤 chips(JevLive 式):全部/强信号/信号/观察/忽略,带计数 ----
    LinearLayout frow = new LinearLayout(m);
    frow.setPadding(0, Ui.dp(m, 4), 0, Ui.dp(m, 2));
    String[] fids = {"all", "strong", "signal", "observe", "ignore"};
    String[] flabels = levelLabels();
    filterChips = new TextView[5];
    for (int i = 0; i < 5; i++) {
      final int idx = i;
      TextView c = new TextView(m);
      c.setTextSize(11); c.setGravity(Gravity.CENTER);
      c.setPadding(Ui.dp(m, 6), Ui.dp(m, 5), Ui.dp(m, 6), Ui.dp(m, 5));
      LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
      clp.rightMargin = Ui.dp(m, 4); c.setLayoutParams(clp);
      c.setOnClickListener(v -> { newsFilter = fids[idx]; render(); });
      filterChips[i] = c;
      frow.addView(c);
    }
    l.addView(frow);

    list = new LinearLayout(m);
    list.setOrientation(LinearLayout.VERTICAL);
    l.addView(list);

    ScrollView sc = new ScrollView(m);
    sc.addView(l);
    m.body.addView(sc, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));

    ensureTimer();
    fetch();
  }

  private static int Color_WHITE() { return 0xFFFFFFFF; }

  private void setStatus(String s) { if (status != null) status.setText(s); }

  private void paintAuto() { autoBtn.setText(autoOn ? m.getString(R.string.news_auto_on) : m.getString(R.string.news_auto_off)); }

  // ---- 定时刷新:60s,仅快讯页在前台时拉取 ----
  private final Runnable tick = new Runnable() {
    @Override public void run() {
      if (autoOn && m.tab == 4) fetch();
      timer.postDelayed(this, 60_000);
    }
  };
  private void ensureTimer() {
    if (timerStarted) return;
    timerStarted = true;
    timer.postDelayed(tick, 60_000);
  }

  // ---- 拉取:双源合并,按时间倒序;返回新增条数 ----
  private void fetch() {
    if (fetching) return;
    fetching = true;
    setStatus(m.getString(R.string.news_fetching));
    new Thread(() -> {
      int fresh = 0, errs = 0;
      try { fresh += fetchSina(); } catch (Throwable t) { errs++; }
      try { fresh += fetchWsc(); } catch (Throwable t) { errs++; }
      synchronized (items) { sortByTime(); }
      final int f = fresh, fe = errs;
      m.runOnUiThread(() -> {
        fetching = false;
        String s = m.getString(R.string.news_fetch_ok, items.size(), f);
        if (fe > 0) s += m.getString(R.string.news_fetch_err_part);
        setStatus(s + (llmMissing ? " · " + m.getString(R.string.news_nollm) : ""));
        render();
        scorePending();
      });
    }).start();
  }

  private JSONObject getJson(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(8000); c.setReadTimeout(15000);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) laya-news");
    int code = c.getResponseCode();
    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
    check2(code < 400, "HTTP " + code);
    return new JSONObject(bo.toString("UTF-8"));
  }

  private static void check2(boolean b, String msg) throws Exception { if (!b) throw new Exception(msg); }

  private static String readBody(InputStream in) throws Exception {
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
    return bo.toString("UTF-8");
  }

  private static String stripHtml(String s) {
    return s == null ? "" : s.replaceAll("<[^>]+>", "").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
  }

  private int fetchSina() throws Exception {
    JSONObject o = getJson("https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size=20&zhibo_id=152");
    JSONArray arr = o.optJSONObject("result").optJSONObject("data").optJSONObject("feed").optJSONArray("list");
    int fresh = 0;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject it = arr.optJSONObject(i); if (it == null) continue;
      String key = "sina_" + it.optLong("id");
      String text = it.optString("rich_text").trim();
      if (text.isEmpty()) continue;
      synchronized (items) {
        if (items.containsKey(key)) continue;
        JSONObject n = new JSONObject();
        n.put("ts", it.has("create_time") ? df.parse(it.optString("create_time")).getTime() : 0L);
        n.put("source", "新浪财经");
        n.put("text", text);
        n.put("url", it.optString("docurl", ""));
        items.put(key, n);
        fresh++;
      }
    }
    return fresh;
  }

  private int fetchWsc() throws Exception {
    JSONObject o = getJson("https://api-one-wscn.awtmt.com/apiv1/content/lives?channel=global-channel&limit=20");
    JSONArray arr = o.optJSONObject("data").optJSONArray("items");
    int fresh = 0;
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject it = arr.optJSONObject(i); if (it == null) continue;
      String key = "wsc_" + it.optLong("id");
      String text = stripHtml(it.optString("content_text", it.optString("content", "")));
      if (text.isEmpty()) continue;
      synchronized (items) {
        if (items.containsKey(key)) continue;
        JSONObject n = new JSONObject();
        n.put("ts", it.optLong("display_time") * 1000L);
        n.put("source", "华尔街见闻");
        n.put("text", text);
        n.put("url", it.optString("uri", ""));
        items.put(key, n);
        fresh++;
      }
    }
    return fresh;
  }

  private void sortByTime() {
    java.util.List<JSONObject> all = new java.util.ArrayList<>(items.values());
    java.util.Collections.sort(all, (a, b) -> Long.compare(b.optLong("ts"), a.optLong("ts")));
    items.clear();
    for (JSONObject o : all) {
      if (items.size() >= MAX_ITEMS * 2) break;
      items.put(o.optString("source") + "|" + o.optLong("ts") + "|" + o.optString("text").hashCode(), o);
    }
  }

  // ---- LLM 评分:逐条同步调用,单线程顺序消费(一次请求一条,结果流式上屏) ----
  private void scorePending() {
    if (scoringActive) return;
    if (com.laya.Gateway.llmActive(m) == null) { llmMissing = true; return; }
    llmMissing = false;
    String key = pickUnscored();
    if (key == null) return;
    scoringActive = true;
    final String k = key;
    new Thread(() -> {
      try {
        JSONObject it;
        synchronized (items) { it = items.get(k); }
        if (it == null || it.has("scores")) return;
        scoringKeys.add(k);
        m.runOnUiThread(() -> setStatus(m.getString(R.string.news_scoring)));
        JSONObject sc = llmScore(it.optString("text"));
        synchronized (items) {
          if (sc != null && !sc.has("error")) {
            String[] dr = decide(sc);
            it.put("scores", sc);
            it.put("level", dr[0]);
            it.put("reason", dr[1]);
          } else {
            String err = sc == null ? "" : sc.optString("error");
            it.put("reason", m.getString(R.string.news_llm_fail)
                + (err.isEmpty() ? "" : ": " + err.substring(0, Math.min(80, err.length()))));
          }
        }
      } catch (Throwable t) {
        // 忽略单条异常,继续下一条
      } finally {
        scoringKeys.remove(k);
        scoringActive = false;
      }
      m.runOnUiThread(() -> { render(); scorePending(); }); // 串行推进下一条
    }).start();
  }

  private String pickUnscored() {
    synchronized (items) {
      int seen = 0;
      for (java.util.Map.Entry<String, JSONObject> e : items.entrySet()) { // items 已按 ts 倒序
        if (++seen > SCORE_TOP) break;
        if (!e.getValue().has("scores") && !scoringKeys.contains(e.getKey())) return e.getKey();
      }
    }
    return null;
  }

  /** OpenAI 兼容 /chat/completions 同步调用,返回 {drive,conf,urg,senti,reason} */
  private JSONObject llmScore(String text) {
    try {
      org.json.JSONObject slot = com.laya.Gateway.llmActive(m);
      if (slot == null) return null;
      String base = slot.optString("baseURL").trim().replaceAll("/$", "");
      String model = slot.optString("model").trim();
      if (base.isEmpty() || model.isEmpty()) return null;
      String prompt = m.getString(R.string.news_prompt) + text.substring(0, Math.min(300, text.length()));
      HttpURLConnection c = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
      c.setRequestMethod("POST"); c.setConnectTimeout(8000); c.setReadTimeout(90_000);
      c.setDoOutput(true); c.setRequestProperty("Content-Type", "application/json");
      String apiKey = slot.optString("apiKey");
      if (!apiKey.isEmpty()) c.setRequestProperty("Authorization", "Bearer " + apiKey);
      c.getOutputStream().write(new JSONObject()
          .put("model", model).put("max_tokens", 200).put("temperature", 0.2)
          .put("chat_template_kwargs", new JSONObject().put("enable_thinking", false))
          .put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", prompt)))
          .toString().getBytes());
      int code = c.getResponseCode();
      String body = readBody(code < 400 ? c.getInputStream() : c.getErrorStream());
      if (code >= 400) return null;
      org.json.JSONObject msg = new JSONObject(body).optJSONArray("choices").optJSONObject(0).optJSONObject("message");
      String content = msg != null ? msg.optString("content").trim() : "";
      if (content.isEmpty() && msg != null) content = msg.optString("reasoning_content").trim(); // 思考型模型正文兜底
      int b = content.indexOf('{'), e = content.lastIndexOf('}');
      if (b < 0 || e <= b) return new JSONObject().put("error", "LLM 输出无 JSON(" + content.substring(0, Math.min(40, content.length())) + "…)");
      JSONObject r = new JSONObject(content.substring(b, e + 1));
      for (String f : new String[]{"drive", "conf", "urg", "senti"}) {
        double v = r.optDouble(f);
        if (Double.isNaN(v)) v = 0;
        r.put(f, Math.max(0, Math.min(1, v)));
      }
      return r;
    } catch (Throwable t) {
      String em = String.valueOf(t.getMessage() != null ? t.getMessage() : t);
      JSONObject r = new JSONObject();
      try { r.put("error", em); } catch (Exception ignore) {}
      return r;
    }
  }

  /** JevLive 四级分诊:忽略 drive<0.50;观察 conf<0.55;强信号 drive&conf 双≥0.75;其余信号 */
  private String[] decide(JSONObject sc) { // {level, reason}
    double drive = sc.optDouble("drive"), conf = sc.optDouble("conf");
    if (drive < TH_DRIVE) return new String[]{"ignore", String.format(Locale.US, m.getString(R.string.news_ig), drive)};
    if (conf < TH_CONF) return new String[]{"observe", String.format(Locale.US, m.getString(R.string.news_ob), conf)};
    if (drive >= TH_STRONG && conf >= TH_STRONG)
      return new String[]{"strong", String.format(Locale.US, m.getString(R.string.news_strong), drive, conf)};
    return new String[]{"signal", String.format(Locale.US, m.getString(R.string.news_act), drive, conf)};
  }

  private static final String[] LEVEL_IDS = {"strong", "signal", "observe", "ignore"};

  private int levelIdx(String l) {
    for (int i = 0; i < LEVEL_IDS.length; i++) if (LEVEL_IDS[i].equals(l)) return i + 1;
    return 0;
  }

  private String levelName(String l) {
    String[] names = levelLabels();
    int i = levelIdx(l);
    return i == 0 ? "" : names[i];
  }

  private String[] levelLabels() {
    return new String[]{m.getString(R.string.news_f_all), m.getString(R.string.news_lvl_strong),
        m.getString(R.string.news_lvl_signal), m.getString(R.string.news_lvl_observe), m.getString(R.string.news_lvl_ignore)};
  }

  private int levelColor(String l) {
    switch (l) {
      case "strong": return 0xFFD62828;
      case "signal": return 0xFFE78A00;
      case "observe": return 0xFF1667C7;
      default: return 0xFF9AA3AD;
    }
  }

  private String levelMarker(String l) {
    switch (l) {
      case "strong": return "●";
      case "signal": return "◆";
      case "observe": return "◇";
      default: return "○";
    }
  }

  // ---- 渲染 ----
  private void render() {
    if (list == null || filterChips == null) return;
    list.removeAllViews();
    int[] cnt = new int[5];
    int shown = 0;
    synchronized (items) {
      for (JSONObject it : items.values()) {
        String l = it.optString("level", "");
        cnt[0]++;
        if (!l.isEmpty()) cnt[levelIdx(l)]++;
        if (shown >= MAX_ITEMS) break;
        if (!newsFilter.equals("all") && !newsFilter.equals(l)) continue;
        shown++;
        list.addView(card(it));
      }
    }
    // chips:标签 + 计数 + 选中态
    String[] flabels = levelLabels();
    String[] fids = {"all", "strong", "signal", "observe", "ignore"};
    for (int i = 0; i < 5; i++) {
      boolean on = newsFilter.equals(fids[i]);
      filterChips[i].setText(flabels[i] + "\n" + cnt[i]);
      filterChips[i].setTextColor(on ? 0xFFFFFFFF : 0xFF1A2B4C);
      filterChips[i].setBackground(Ui.pill(on ? m.PRIMARY : 0xFFE7EAF2, Ui.dp(m, 10)));
    }
  }

  private View card(JSONObject it) {
    boolean scored = it.has("scores");
    String reason = it.optString("reason", scored ? "" : m.getString(R.string.news_wait));
    LinearLayout card = new LinearLayout(m);
    card.setOrientation(LinearLayout.VERTICAL);
    card.setBackground(Ui.pill(0xFFFFFFFF, Ui.dp(m, 8)));
    card.setPadding(Ui.dp(m, 9), Ui.dp(m, 6), Ui.dp(m, 9), Ui.dp(m, 7));
    LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    clp.bottomMargin = Ui.dp(m, 6); card.setLayoutParams(clp);

    // 行1:时间 + 来源 + 标记 + 复制
    LinearLayout r1 = new LinearLayout(m);
    r1.setGravity(Gravity.CENTER_VERTICAL);
    TextView meta = new TextView(m);
    String t = new SimpleDateFormat("MM-dd HH:mm", Locale.US).format(new Date(it.optLong("ts")));
    meta.setText(t + " · " + it.optString("source"));
    meta.setTextSize(11); meta.setTextColor(0xFF8A93A0);
    r1.addView(meta, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    TextView copy = new TextView(m);
    copy.setText("⧉"); copy.setTextSize(13); copy.setPadding(Ui.dp(m, 8), 0, Ui.dp(m, 4), 0);
    copy.setOnClickListener(v -> {
      String clip = it.optString("text") + "\n" + (it.has("scores")
          ? String.format(Locale.US, m.getString(R.string.news_scores),
              fmt(it, "drive"), fmt(it, "conf"), fmt(it, "urg"), fmt(it, "senti")) + "\n" + it.optString("reason") : "");
      ((ClipboardManager) m.getSystemService(android.content.Context.CLIPBOARD_SERVICE))
          .setPrimaryClip(new ClipData("laya", new String[]{"text/plain"}, new ClipData.Item(clip)));
    });
    r1.addView(copy);
    String level = it.optString("level", "");
    if (scored) {
      TextView mk = new TextView(m);
      mk.setText(levelMarker(level) + " " + levelName(level)); mk.setTextSize(12); mk.setTypeface(Typeface.DEFAULT_BOLD);
      mk.setTextColor(levelColor(level)); mk.setPadding(Ui.dp(m, 2), 0, 0, 0);
      r1.addView(mk);
    }
    card.addView(r1);

    // 行2:标题(有链接可点开)
    TextView title = new TextView(m);
    title.setText(it.optString("text")); title.setTextSize(13); title.setTextColor(0xFF1A2B4C);
    title.setPadding(0, Ui.dp(m, 2), 0, 0);
    card.addView(title);

    // 行3:四维分数
    if (scored) {
      TextView sc = new TextView(m);
      sc.setText(String.format(Locale.US, m.getString(R.string.news_scores),
          fmt(it, "drive"), fmt(it, "conf"), fmt(it, "urg"), fmt(it, "senti")));
      sc.setTextSize(11); sc.setTextColor(0xFF5B6B7F); sc.setPadding(0, Ui.dp(m, 2), 0, 0);
      card.addView(sc);
    }

    // 行4:决策理由
    TextView rr = new TextView(m);
    rr.setText(reason); rr.setTextSize(12); rr.setTypeface(Typeface.DEFAULT_BOLD);
    rr.setTextColor(scored ? levelColor(level) : 0xFF9AA3AD);
    rr.setPadding(0, Ui.dp(m, 2), 0, 0);
    card.addView(rr);

    String url = it.optString("url", "");
    if (!url.isEmpty()) title.setTextColor(0xFF1667C7);
    title.setOnClickListener(v -> {
      try { m.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Throwable ignore) {}
    });
    return card;
  }

  private static String fmt(JSONObject it, String f) {
    return String.format(Locale.US, "%.2f", it.optJSONObject("scores").optDouble(f));
  }
}
