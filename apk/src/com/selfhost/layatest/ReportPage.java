package com.selfhost.layatest;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;

/** ② 报表页(从 MainActivity 拆出):日报/月报/年报/详单/下钻详单 */
class ReportPage {
  private final MainActivity m;

  ReportPage(MainActivity m) { this.m = m; }

  // ================= ② 报表 =================
  private int reportKind = 0;
  private int detailPage = 0;   // 详单当前页(0 基)
  private int detailPages = 1;
  private LinearLayout pagerRow;
  private TextView pagerLabel;

  void build() {
    LinearLayout l = new LinearLayout(m);
    l.setOrientation(LinearLayout.VERTICAL);
    l.setPadding(Ui.dp(m,12), Ui.dp(m,8), Ui.dp(m,12), Ui.dp(m,8));
    TextView cur = new TextView(m);
    cur.setText(m.getString(R.string.report_hint)); cur.setTextSize(12); cur.setTextColor(0xFF66707E);
    cur.setPadding(Ui.dp(m,2), 0, 0, 0);
    l.addView(cur);

    // 详单翻页行(仅详单显示)
    pagerRow = new LinearLayout(m);
    pagerRow.setOrientation(LinearLayout.HORIZONTAL);
    pagerRow.setGravity(Gravity.CENTER_VERTICAL);
    pagerRow.setPadding(0, Ui.dp(m,4), 0, Ui.dp(m,4));
    Button prev = new Button(m);
    prev.setText(m.getString(R.string.page_prev)); prev.setAllCaps(false); prev.setTextSize(12);
    prev.setOnClickListener(v -> { if (detailPage > 0) { detailPage--; renderReport(3); } });
    prev.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(prev);
    pagerLabel = new TextView(m);
    pagerLabel.setTextSize(12); pagerLabel.setGravity(Gravity.CENTER);
    pagerLabel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(pagerLabel);
    Button next = new Button(m);
    next.setText(m.getString(R.string.page_next)); next.setAllCaps(false); next.setTextSize(12);
    next.setOnClickListener(v -> { if (detailPage < detailPages - 1) { detailPage++; renderReport(3); } });
    next.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
    pagerRow.addView(next);
    pagerRow.setVisibility(View.GONE);
    l.addView(pagerRow);

    // ---- 下钻详单面板(仅 reportKind==4 显示):业务 × 决策等级 × 日期 ----
    drillPane = new LinearLayout(m);
    drillPane.setOrientation(LinearLayout.VERTICAL);
    drillPane.setPadding(0, Ui.dp(m,6), 0, 0);
    LinearLayout fRow1 = new LinearLayout(m);
    fRow1.setGravity(Gravity.CENTER_VERTICAL);
    spinRange = new Spinner(m);
    spinRange.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, new String[]{m.getString(R.string.range_7d), m.getString(R.string.range_today), m.getString(R.string.range_30d), m.getString(R.string.range_all)}));
    spinRange.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinRange);
    spinLevel = new Spinner(m);
    spinLevel.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, new String[]{m.getString(R.string.level_all), m.getString(R.string.level_high), m.getString(R.string.level_mid), m.getString(R.string.level_low)}));
    spinLevel.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1.4f));
    fRow1.addView(spinLevel);
    spinTask = new Spinner(m);
    java.util.List<String> tOpts = new ArrayList<>();
    tOpts.add(m.getString(R.string.task_all));
    for (int i = 0; i < m.taskIds.size(); i++) tOpts.add(m.taskLabels.get(i) + " (" + m.taskIds.get(i) + ")");
    spinTask.setAdapter(new ArrayAdapter<>(m, android.R.layout.simple_spinner_dropdown_item, tOpts));
    spinTask.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f));
    fRow1.addView(spinTask);
    drillPane.addView(fRow1);
    Button qBtn = new Button(m);
    qBtn.setText(m.getString(R.string.drill_query));
    qBtn.setAllCaps(false); qBtn.setTextSize(13);
    qBtn.setOnClickListener(v -> renderDrill());
    drillPane.addView(qBtn);
    drillTables = new LinearLayout(m);
    drillTables.setOrientation(LinearLayout.VERTICAL);
    drillPane.addView(drillTables);
    drillOut = new LinearLayout(m);
    drillOut.setOrientation(LinearLayout.VERTICAL);
    drillPane.addView(drillOut);
    drillPane.setVisibility(View.GONE);
    l.addView(drillPane);

    reportList = new LinearLayout(m);
    reportList.setOrientation(LinearLayout.VERTICAL);
    reportList.setPadding(0, Ui.dp(m,10), 0, 0);
    l.addView(reportList);
    m.scroller = new ScrollView(m);
    m.scroller.addView(l);
    renderReport(reportKind);

    // ---- 左侧竖排 tab(与系统页同款):日报/月报/年报/详单/下钻详单 ----
    final FrameLayout holder = new FrameLayout(m);
    holder.addView(m.scroller);
    holder.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f));
    String[] kinds = {m.getString(R.string.kind_daily), m.getString(R.string.kind_monthly), m.getString(R.string.kind_yearly), m.getString(R.string.kind_detail), m.getString(R.string.kind_drill)};
    final Button[] chips = new Button[5];
    final LinearLayout rail = new LinearLayout(m);
    rail.setOrientation(LinearLayout.VERTICAL);
    for (int i = 0; i < 5; i++) {
      final int k = i;
      Button c = new Button(m);
      c.setText(kinds[i]); c.setAllCaps(false); c.setTextSize(12);
      c.setMinHeight(0); c.setMinimumHeight(0);
      c.setOnClickListener(v -> {
        reportKind = k;
        renderReport(k);
        for (int j = 0; j < 5; j++) {
          chips[j].setTextColor(j == k ? Color.WHITE : 0xFF1A2B4C);
          chips[j].setBackground(Ui.pill(j == k ? m.PRIMARY : 0xFFE7EAF2, Ui.dp(m,12)));
        }
      });
      chips[i] = c;
      rail.addView(RailChip.make(m, c));
    }
    final LinearLayout leftCol = new LinearLayout(m);
    leftCol.setOrientation(LinearLayout.VERTICAL);
    leftCol.setPadding(Ui.dp(m,4), Ui.dp(m,4), Ui.dp(m,0), Ui.dp(m,0));
    ScrollView railScroll = new ScrollView(m);
    railScroll.addView(rail); // 左侧 tab 菜单可上下滑动(小屏防截断)
    leftCol.addView(railScroll);
    LinearLayout.LayoutParams lclp = new LinearLayout.LayoutParams(Ui.dp(m,40), LinearLayout.LayoutParams.MATCH_PARENT);
    lclp.rightMargin = Ui.dp(m,2);
    leftCol.setLayoutParams(lclp);
    LinearLayout top = new LinearLayout(m);
    top.setOrientation(LinearLayout.HORIZONTAL);
    top.addView(leftCol);
    top.addView(holder);
    m.body.addView(top);
    // 标题栏 ☰ 切换本页左栏
    m.menuBtn.setOnClickListener(v -> {
      boolean show = leftCol.getVisibility() == View.GONE;
      leftCol.setVisibility(show ? View.VISIBLE : View.GONE);
    });
    // 初始高亮当前类型
    chips[reportKind].setTextColor(Color.WHITE);
    chips[reportKind].setBackground(Ui.pill(m.PRIMARY, Ui.dp(m,12)));
  }
  private LinearLayout reportList;
  private LinearLayout drillPane, drillTables, drillOut;
  private Spinner spinTask, spinLevel, spinRange;



  private TextView dCell(String t, boolean bold, int color, View.OnClickListener oc, float weight) {
    TextView c = new TextView(m);
    c.setText(t); c.setTextSize(12); c.setGravity(Gravity.CENTER);
    c.setTextColor(color != 0 ? color : 0xFF1A2B4C);
    if (bold) c.setTypeface(Typeface.DEFAULT_BOLD);
    c.setBackground(Ui.pill(0xFFF6F7FA, Ui.dp(m,6)));
    c.setPadding(Ui.dp(m,4), Ui.dp(m,7), Ui.dp(m,4), Ui.dp(m,7));
    c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, weight));
    if (oc != null) { c.setTextColor(0xFF3E7BFA); c.setOnClickListener(oc); }
    return c;
  }

  private void renderDrill() {
    final int days = new int[]{7, 1, 30, 0}[spinRange.getSelectedItemPosition()];
    final int lp = spinLevel.getSelectedItemPosition();
    final String level = lp == 0 ? null : new String[]{"高", "中", "低"}[lp - 1]; // 数据键固定,UI 显示已本地化
    final int tp = spinTask.getSelectedItemPosition();
    final String task = tp == 0 ? null : m.taskIds.get(tp - 1);
    final android.app.Activity act = m;
    new Thread(() -> {
      final JSONObject piv = com.laya.DecisionCore.detailPivot(act, days, task, level);
      m.runOnUiThread(() -> renderPivot(act, piv, task, level));
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
    TextView head = new TextView(m);
    head.setText(m.getString(R.string.drill_head, piv.optInt("total")));
    head.setTextSize(12); head.setTextColor(0xFF66707E); head.setPadding(Ui.dp(m,4), Ui.dp(m,8), 0, Ui.dp(m,4));
    drillTables.addView(head);
    java.util.Map<String, Integer> grid = new LinkedHashMap<>();
    java.util.Map<String, Integer> gDate = new LinkedHashMap<>();
    for (JSONObject r : rows) {
      grid.merge(r.optString("task") + "|" + r.optString("level"), r.optInt("count"), Integer::sum);
      gDate.merge(r.optString("date") + "|" + r.optString("task"), r.optInt("count"), Integer::sum);
    }
    // 表1:业务 × 等级
    LinearLayout t1 = new LinearLayout(m); t1.setOrientation(LinearLayout.VERTICAL);
    t1.setBackground(Ui.pill(0xFFFFFFFF, Ui.dp(m,10))); t1.setPadding(Ui.dp(m,6), Ui.dp(m,6), Ui.dp(m,6), Ui.dp(m,6));
    LinearLayout h1 = new LinearLayout(m);
    h1.addView(dCell(m.getString(R.string.col_task), true, 0, null, 2.2f));
    for (String lv : lvOrd) h1.addView(dCell(lv, true, 0, null, 1f));
    h1.addView(dCell(m.getString(R.string.col_total), true, 0, null, 1f));
    t1.addView(h1);
    for (String t : tasks) {
      LinearLayout r = new LinearLayout(m);
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
    LinearLayout t2 = new LinearLayout(m); t2.setOrientation(LinearLayout.VERTICAL);
    t2.setBackground(Ui.pill(0xFFFFFFFF, Ui.dp(m,10))); t2.setPadding(Ui.dp(m,6), Ui.dp(m,6), Ui.dp(m,6), Ui.dp(m,6));
    LinearLayout h2 = new LinearLayout(m);
    h2.addView(dCell(m.getString(R.string.col_date), true, 0, null, 1.6f));
    for (String t : tasks) h2.addView(dCell(t, true, 0, null, 1f));
    h2.addView(dCell(m.getString(R.string.col_total), true, 0, null, 1f));
    t2.addView(h2);
    for (String d : dOrd) {
      LinearLayout r = new LinearLayout(m);
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
    LinearLayout gap = new LinearLayout(m); gap.setPadding(0, Ui.dp(m,8), 0, 0);
    drillTables.addView(gap); drillTables.addView(t2);
  }

  private void drillShow(String date, String task, String level) {
    drillOut.removeAllViews();
    TextView loading = new TextView(m);
    loading.setText(m.getString(R.string.loading_detail)); loading.setTextSize(12); loading.setPadding(Ui.dp(m,4), Ui.dp(m,8), 0, 0);
    drillOut.addView(loading);
    final android.app.Activity act = m;
    new Thread(() -> {
      final JSONArray es = com.laya.DecisionCore.drillList(act, date, task, level, 200);
      m.runOnUiThread(() -> {
        drillOut.removeAllViews();
        java.util.Map<String, String> labels = new LinkedHashMap<>();
        for (int i = 0; i < m.taskIds.size(); i++) labels.put(m.taskIds.get(i), m.taskLabels.get(i));
        TextView title = new TextView(m);
        title.setText(m.getString(R.string.drill_title, date != null ? date : m.getString(R.string.range_any),
            task != null ? labels.getOrDefault(task, task) : m.getString(R.string.task_all),
            level != null ? level : m.getString(R.string.level_any), es.length()));
        title.setTextSize(13); title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setPadding(Ui.dp(m,4), Ui.dp(m,10), 0, Ui.dp(m,4));
        drillOut.addView(title);
        for (int i = 0; i < es.length(); i++) {
          JSONObject e = es.optJSONObject(i); if (e == null) continue;
          LinearLayout card = new LinearLayout(m);
          card.setOrientation(LinearLayout.VERTICAL);
          card.setBackground(Ui.pill(0xFFF7F8FA, Ui.dp(m,8)));
          card.setPadding(Ui.dp(m,9), Ui.dp(m,6), Ui.dp(m,9), Ui.dp(m,7));
          LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
          clp.bottomMargin = Ui.dp(m,6); card.setLayoutParams(clp);
          TextView l1 = new TextView(m);
          String lv = e.optString("level");
          int lvc = "高".equals(lv) ? 0xFFD62828 : "中".equals(lv) ? 0xFFE78A00 : "低".equals(lv) ? 0xFF2A9D8F : 0xFF66707E;
          l1.setText(e.optString("time") + "  [" + labels.getOrDefault(e.optString("task"), e.optString("task")) + "]  " + lv
              + "  " + e.optLong("latencyMs") + "ms");
          l1.setTextSize(12); l1.setTypeface(Typeface.DEFAULT_BOLD); l1.setTextColor(lvc);
          card.addView(l1);
          String ans = e.optString("answers", "");
          String basis = e.optString("basis", "");
          TextView l2 = new TextView(m);
          l2.setText((ans.isEmpty() ? "" : ans + "\n") + basis);
          l2.setTextSize(11); l2.setTextColor(0xFF444A55); l2.setPadding(0, Ui.dp(m,1), 0, Ui.dp(m,2));
          card.addView(l2);
          TextView l3 = new TextView(m);
          String st = e.optString("state");
          l3.setText(st.length() > 80 ? st.substring(0, 80) + "…" : st);
          l3.setTextSize(11); l3.setTextColor(0xFF66707E);
          card.addView(l3);
          drillOut.addView(card);
        }
        if (es.length() == 0) {
          TextView empty = new TextView(m);
          empty.setText(m.getString(R.string.no_records)); empty.setTextSize(12); empty.setTextColor(0xFF66707E);
          empty.setPadding(Ui.dp(m,4), Ui.dp(m,6), 0, 0);
          drillOut.addView(empty);
        }
        m.scroller.post(() -> m.scroller.fullScroll(View.FOCUS_DOWN));
      });
    }).start();
  }

  private void renderReport(int kind) {
    final boolean isDetail = kind == 3;
    final boolean isDrill = kind == 4;
    m.runOnUiThread(() -> {
      pagerRow.setVisibility(isDetail ? View.VISIBLE : View.GONE);
      reportList.setVisibility(isDrill ? View.GONE : View.VISIBLE);
      drillPane.setVisibility(isDrill ? View.VISIBLE : View.GONE);
    });
    if (isDrill) { m.runOnUiThread(this::renderDrill); return; }
    final android.app.Activity act = m;
    new Thread(() -> {
      try {
        if (isDetail) {
          org.json.JSONObject d = com.laya.DecisionCore.detail(act, detailPage, 20);
          detailPages = d.optInt("pages", 1);
          JSONArray rows = d.optJSONArray("rows");
          String[] heads = {m.getString(R.string.head_time), m.getString(R.string.col_task), m.getString(R.string.head_result), m.getString(R.string.label_score), m.getString(R.string.label_verdict), m.getString(R.string.col_level), m.getString(R.string.label_latency), m.getString(R.string.head_content)};
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
          m.runOnUiThread(() -> {
            showTable(heads, ws, fin);
            pagerLabel.setText(m.getString(R.string.pager_label, pg, pgs));
          });
          return;
        }
        JSONObject rt = com.laya.DecisionCore.reportTable(act, kind);
        JSONArray rows = rt.optJSONArray("rows");
        String[] heads = {m.getString(R.string.head_period), m.getString(R.string.head_count, 0), m.getString(R.string.head_avg_latency), m.getString(R.string.head_top), m.getString(R.string.head_dist)};
        float[] ws = {1.2f, 0.8f, 0.9f, 1.1f, 2.0f};
        java.util.List<String[]> data = new ArrayList<>();
        if (rows != null) for (int i = 0; i < rows.length(); i++) {
          JSONObject e = rows.optJSONObject(i); if (e == null) continue;
          data.add(new String[]{e.optString("period"), String.valueOf(e.optInt("count")),
              e.optInt("avgLatency") + "ms", e.optString("top"), e.optString("dist")});
        }
        final java.util.List<String[]> fin = data;
        final int tot = rt.optInt("total");
        m.runOnUiThread(() -> {
          if (fin.isEmpty()) {
            reportList.removeAllViews();
            TextView empty = new TextView(act);
            empty.setText(m.getString(R.string.no_data)); empty.setTextSize(12); empty.setTextColor(0xFF66707E);
            reportList.addView(empty);
          } else {
            String[] heads2 = new String[heads.length];
            for (int i = 0; i < heads.length; i++) heads2[i] = i == 1 ? m.getString(R.string.head_count, tot) : heads[i];
            showTable(heads2, ws, fin);
          }
        });
      } catch (Exception e) {
        final String msg = m.getString(R.string.gen_failed, e.getMessage());
        m.runOnUiThread(() -> {
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
    LinearLayout t = new LinearLayout(m);
    t.setOrientation(LinearLayout.VERTICAL);
    t.setBackground(Ui.pill(0xFFFFFFFF, Ui.dp(m,10)));
    t.setPadding(Ui.dp(m,6), Ui.dp(m,4), Ui.dp(m,6), Ui.dp(m,4));
    LinearLayout h = new LinearLayout(m);
    h.setBackground(Ui.pill(0xFFEFF2F7, Ui.dp(m,6)));
    for (int i = 0; i < heads.length; i++) h.addView(tCell(heads[i], true, ws[i]));
    t.addView(h);
    for (int r = 0; r < rows.size(); r++) {
      LinearLayout row = new LinearLayout(m);
      String[] cells = rows.get(r);
      for (int i = 0; i < cells.length; i++) row.addView(tCell(cells[i], i == 0, ws[i]));
      t.addView(row);
    }
    reportList.addView(t);
  }

  private TextView tCell(String s, boolean bold, float w) {
    TextView c = new TextView(m);
    c.setText(s == null || s.isEmpty() ? "-" : s);
    c.setTextSize(11);
    c.setTypeface(bold ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    c.setTextColor(bold ? 0xFF1A2B4C : 0xFF444A55);
    c.setPadding(Ui.dp(m,6), Ui.dp(m,5), Ui.dp(m,6), Ui.dp(m,5));
    c.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, w));
    return c;
  }

}
