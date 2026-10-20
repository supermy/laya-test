package com.laya;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;

import com.selfhost.layatest.NewsFetcher;

/** 前台服务:资讯自动采集常驻引擎。拉流 → 本地模型评分(followLlm)→ 决策日志;强信号 → 系统通知。 */
public class NewsAutoService extends Service {
  public static final String CHANNEL_ALERTS = "news_alerts";
  public static final String CHANNEL_SVC = "news_svc";
  public static volatile boolean running = false;
  public static final ConcurrentHashMap<String, Long> sourceCounts = new ConcurrentHashMap<>(); // sina/wsc → 条数
  public static volatile String lastError = "";
  public static volatile long lastFetchAt = 0;

  private final Handler handler = new Handler();
  private Runnable tick;
  private static final int NOTIF_ID = 20;

  public NewsAutoService() { super(); }

  @Override
  public IBinder onBind(Intent intent) { return null; }

  @Override
  public int onStartCommand(Intent intent, int flags, int startId) {
    NotificationManager nm = getSystemService(NotificationManager.class);
    nm.createNotificationChannel(new NotificationChannel(CHANNEL_SVC,
        "资讯自动采集", NotificationManager.IMPORTANCE_LOW));
    nm.createNotificationChannel(new NotificationChannel(CHANNEL_ALERTS,
        "快讯强信号", NotificationManager.IMPORTANCE_HIGH));
    Notification n = new Notification.Builder(this, CHANNEL_SVC)
        .setSmallIcon(android.R.drawable.stat_notify_sync)
        .setContentTitle("资讯自动采集运行中")
        .setOngoing(true)
        .build();
    startForeground(NOTIF_ID, n);
    if (!running) {
      running = true;
      handler.post(tick); // 首次立即拉流,tick 自行按 interval 续排
    }
    return START_STICKY;
  }

  {
    tick = this::tick;
  }

  private void tick() {
    if (!running) return;
    final android.content.Context ctx = this;
    new Thread(() -> {
      int fresh = 0;
      StringBuilder errs = new StringBuilder();
      JSONObject nc = null;
      try { nc = Gateway.cfg(ctx).optJSONObject(Cfg.NEWS); } catch (Exception ignored) {}
      if (nc == null) nc = new JSONObject();
      // 每源启用判定:未配置视为启用
      JSONObject sina = nc.optJSONObject(Cfg.SINA);
      JSONObject wsc = nc.optJSONObject(Cfg.WSC);
      boolean sinaOn = sina == null || sina.optBoolean(Cfg.ENABLED, true);
      boolean wscOn = wsc == null || wsc.optBoolean(Cfg.ENABLED, true);
      if (sinaOn) {
        String url = sina != null && !sina.optString(Cfg.URL).isEmpty()
            ? sina.optString(Cfg.URL)
            : "https://zhibo.sina.com.cn/api/zhibo/feed?page=1&page_size=20&zhibo_id=152";
        try { fresh += NewsFetcher.fetchSina(url); } catch (Throwable t) { errs.append("sina:").append(t.getMessage()).append("; "); }
      }
      if (wscOn) {
        String url = wsc != null && !wsc.optString(Cfg.URL).isEmpty()
            ? wsc.optString(Cfg.URL)
            : "https://api-one-wscn.awtmt.com/apiv1/content/lives?channel=global-channel&limit=20";
        try { fresh += NewsFetcher.fetchWsc(url); } catch (Throwable t) { errs.append("wsc:").append(t.getMessage()).append("; "); }
      }
      // 自定义源链接(cfg.news.custom,资讯 tab 维护):按 fmt 复用两种解析器;每轮重读 cfg,增删改下轮自动生效
      org.json.JSONArray custom = nc.optJSONArray("custom");
      for (int i = 0; custom != null && i < custom.length(); i++) {
        JSONObject s = custom.optJSONObject(i);
        if (s == null || s.optString("url").isEmpty()) continue;
        if (!s.optBoolean("enabled", true)) continue; // 链接行「停用」后跳过
        String fmt = s.optString("fmt", "sina");
        // 去重 key 带上 url hash:多个同格式自定义源条目 id 相同不互吞
        String kp = fmt + "#" + Integer.toHexString(s.optString("url").hashCode());
        try {
          fresh += "wsc".equals(fmt) ? NewsFetcher.fetchWsc(s.optString("url"), kp)
                                     : NewsFetcher.fetchSina(s.optString("url"), kp);
        } catch (Throwable t) { errs.append("custom").append(i).append(":").append(t.getMessage()).append("; "); }
      }
      lastFetchAt = System.currentTimeMillis();
      lastError = errs.toString();
      if (fresh > 0) scorePending(ctx);
      handler.postDelayed(this::tick, Math.max(15, nc.optLong(Cfg.INTERVAL, 60)) * 1000L);
    }).start();
  }

  /** 评分未处理的快讯(内存队列),强信号 → 系统通知;LLM 升级由 decide(followLlm) 链路完成 */
  private void scorePending(android.content.Context ctx) {
    ArrayList<JSONObject> pending = NewsFetcher.drainPending();
    for (JSONObject it : pending) {
      String source = it.optString("source");
      Long c = sourceCounts.get(source);
      sourceCounts.put(source, (c == null ? 0 : c) + 1);
      String text = it.optString("text");
      try {
        com.selfhost.layatest.ModelScoreHelper.scoreAndNotify(ctx, "news", source, text);
      } catch (Throwable ignored) {}
    }
  }

  /** 通知:快讯强信号(+LLM 分析内容追加) */
  public static void pushAlert(android.content.Context ctx, String source, String text, String choice, String level, double score, String llmContent) {
    NotificationManager nm = ctx.getSystemService(NotificationManager.class);
    String body = "[" + level + "] " + choice + " · 重要度 " + String.format("%.1f", score) + "/5\n" + text;
    if (llmContent != null && !llmContent.isEmpty())
      body += "\n—— LLM:" + llmContent;
    Notification.Builder b = new Notification.Builder(ctx, CHANNEL_ALERTS)
        .setSmallIcon(android.R.drawable.stat_notify_chat)
        .setContentTitle("快讯强信号 · " + source)
        .setContentText(body.length() > 180 ? body.substring(0, 180) + "…" : body)
        .setStyle(new Notification.BigTextStyle().bigText(body))
        .setAutoCancel(true);
    nm.notify((int) (System.currentTimeMillis() % 100000), b.build());
  }

  @Override
  public void onDestroy() {
    running = false;
    handler.removeCallbacksAndMessages(null);
    super.onDestroy();
  }
}
