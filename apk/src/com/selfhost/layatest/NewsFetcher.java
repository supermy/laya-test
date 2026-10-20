package com.selfhost.layatest;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Locale;
import java.util.concurrent.ConcurrentLinkedQueue;

/** 快讯拉取(NewsPage 与 NewsAutoService 共用):双源 HTTP 拉取 + 去重队列 */
public final class NewsFetcher {
  private NewsFetcher() {}

  private static final ConcurrentLinkedQueue<JSONObject> PENDING = new ConcurrentLinkedQueue<>();
  private static final java.util.Set<String> SEEN = java.util.Collections.synchronizedSet(new java.util.HashSet<>());

  static JSONObject getJson(String url) throws Exception {
    HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
    c.setConnectTimeout(8000); c.setReadTimeout(15000);
    c.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android) laya-news");
    int code = c.getResponseCode();
    InputStream in = code < 400 ? c.getInputStream() : c.getErrorStream();
    ByteArrayOutputStream bo = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    for (int r; (r = in.read(buf)) > 0; ) bo.write(buf, 0, r);
    check(code < 400, "HTTP " + code);
    return new JSONObject(bo.toString("UTF-8"));
  }

  private static void check(boolean b, String msg) throws Exception { if (!b) throw new Exception(msg); }

  private static String stripHtml(String s) {
    return s == null ? "" : s.replaceAll("<[^>]+>", "").replace("&amp;", "&")
        .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").trim();
  }

  /** 新浪财经:HTTP 拉取 → 入队;返回新增条数。keyPrefix 隔离去重 key(多个自定义源同 id 不互吞) */
  public static int fetchSina(String url) throws Exception { return fetchSina(url, "sina"); }

  public static int fetchSina(String url, String keyPrefix) throws Exception {
    JSONObject o = getJson(url);
    JSONArray arr = o.optJSONObject("result").optJSONObject("data").optJSONObject("feed").optJSONArray("list");
    int fresh = 0;
    SimpleDateFormat df = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US);
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject it = arr.optJSONObject(i); if (it == null) continue;
      String key = keyPrefix + "_" + it.optLong("id");
      String text = it.optString("rich_text").trim();
      if (text.isEmpty() || !SEEN.add(key)) continue;
      JSONObject n = new JSONObject();
      try { n.put("ts", it.has("create_time") ? df.parse(it.optString("create_time")).getTime() : 0L); } catch (Exception e) { n.put("ts", 0L); }
      n.put("source", "新浪财经");
      n.put("text", text);
      n.put("url", it.optString("docurl", ""));
      PENDING.add(n);
      fresh++;
    }
    return fresh;
  }

  /** 华尔街见闻:HTTP 拉取 → 入队;返回新增条数。keyPrefix 隔离去重 key */
  public static int fetchWsc(String url) throws Exception { return fetchWsc(url, "wsc"); }

  public static int fetchWsc(String url, String keyPrefix) throws Exception {
    JSONObject o = getJson(url);
    JSONArray arr = o.optJSONObject("data").optJSONArray("items");
    int fresh = 0;
    for (int i = 0; arr != null && i < arr.length(); i++) {
      JSONObject it = arr.optJSONObject(i); if (it == null) continue;
      String key = keyPrefix + "_" + it.optLong("id");
      String text = stripHtml(it.optString("content_text", it.optString("content", "")));
      if (text.isEmpty() || !SEEN.add(key)) continue;
      JSONObject n = new JSONObject();
      n.put("ts", it.optLong("display_time") * 1000L);
      n.put("source", "华尔街见闻");
      n.put("text", text);
      n.put("url", it.optString("uri", ""));
      PENDING.add(n);
      fresh++;
    }
    return fresh;
  }

  /** 测试样本注入(绕过网络):三条快讯 */
  public static void injectSamples() throws Exception {
    long now = System.currentTimeMillis();
    String[][] samples = {
        {"新浪财经", "公司公告预计上半年净利润同比增长120%至150%,主因产品量价齐升"},
        {"华尔街见闻", "公司收到证监会立案调查通知书,涉嫌信息披露违法违规"},
        {"新浪财经", "公司定于下月15日召开年度股东大会,审议年度报告等议案"},
    };
    int i = 0;
    for (String[] sm : samples) {
      JSONObject n = new JSONObject();
      n.put("ts", now + i * 1000L);
      n.put("source", sm[0]);
      n.put("text", sm[1]);
      n.put("url", "");
      n.put("sample", true);
      PENDING.add(n);
      i++;
    }
  }

  /** 取出队列全部条目(评分消费) */
  public static ArrayList<JSONObject> drainPending() {
    ArrayList<JSONObject> out = new ArrayList<>();
    JSONObject it;
    while ((it = PENDING.poll()) != null) out.add(it);
    return out;
  }

  /** 非破坏读取(NewsPage 展示用):返回队列快照 */
  public static ArrayList<JSONObject> peekPending() {
    return new ArrayList<>(PENDING);
  }

  static String fmtTs(long ts) {
    return new SimpleDateFormat("HH:mm:ss", Locale.US).format(new java.util.Date(ts));
  }
}
